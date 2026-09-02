import { describe, expect, it } from 'vitest'
import { createChatStore } from '../chatStore'
import type { ChatMessageDto } from '../../api/types'

/** 测试用最小 ChatMessageDto（避免每个用例重复造全字段）。 */
function baseMsg(id: string, sessionId: string = 'sess-1'): ChatMessageDto {
  return {
    id, sessionId, role: 'user', author: '你', content: `内容-${id}`,
    reasoning: null, toolCalls: null, finishReason: null, inputTokens: null, outputTokens: null,
    reasoningDurationMs: null, time: null, toolCallId: null, assistantMessageId: null, subtype: null, isMeta: false,
    isApiErrorMessage: false, apiError: null, error: null, errorDetails: null, matchedRule: null,
  }
}

describe('chatStore agentStatus', () => {
  it('setAgentStatus 更新会话运行状态，默认 idle（WHY：session.status 事件驱动 StreamHeader 状态点，初始必须为「就绪」）', () => {
    const s = createChatStore()
    expect(s.getState().agentStatus).toBe('idle')
    s.getState().setAgentStatus('thinking')
    expect(s.getState().agentStatus).toBe('thinking')
    s.getState().setAgentStatus('streaming')
    expect(s.getState().agentStatus).toBe('streaming')
    s.getState().setAgentStatus('idle')
    expect(s.getState().agentStatus).toBe('idle')
  })
})

describe('chatStore 块级流式（契约 #1：chunk 带真实轮 id → 按轮建块）', () => {
  it('ensureStreamBlock 按 assistantMessageId 建独立块，同轮重复 chunk 复用最后块（WHY：每轮思考/工具/正文独立展示，不得合并进单条流）', () => {
    const s = createChatStore()
    s.getState().ensureStreamBlock('sess-1', 'turn-a')
    s.getState().ensureStreamBlock('sess-1', 'turn-a') // 同轮多个 chunk 共享 id → 复用
    s.getState().ensureStreamBlock('sess-1', 'turn-b')
    const blocks = s.getState().streams['sess-1'] ?? []
    expect(blocks).toHaveLength(2)
    expect(blocks[0].assistantMessageId).toBe('turn-a')
    expect(blocks[1].assistantMessageId).toBe('turn-b')
  })
  it('appendChunk 按块 id 累积，轮次间隔离（WHY：多轮工具调用中每轮正文独立，后轮增量不得污染前轮）', () => {
    const s = createChatStore()
    s.getState().ensureStreamBlock('sess-1', 'turn-a')
    s.getState().appendChunk('sess-1', 'turn-a', '正文一')
    s.getState().ensureStreamBlock('sess-1', 'turn-b')
    s.getState().appendChunk('sess-1', 'turn-b', '正文二')
    const blocks = s.getState().streams['sess-1'] ?? []
    expect(blocks[0].content).toBe('正文一')
    expect(blocks[1].content).toBe('正文二')
  })
  it('appendReasoning 按块累积；纯思考轮（思考完直接调工具、无正文）content 为空（WHY：块三字段皆可空，覆盖「无 context」轮次形态）', () => {
    const s = createChatStore()
    s.getState().ensureStreamBlock('sess-1', 'turn-a')
    s.getState().appendReasoning('sess-1', 'turn-a', '思考一')
    s.getState().appendReasoning('sess-1', 'turn-a', '思考二')
    const blocks = s.getState().streams['sess-1'] ?? []
    expect(blocks[0].reasoning).toBe('思考一思考二')
    expect(blocks[0].content).toBe('')
  })
  it('clearStream 清空该会话块列表（WHY：停止后回到可发送态，不再显示「正在思考…」）', () => {
    const s = createChatStore()
    s.getState().ensureStreamBlock('sess-1', 'turn-a')
    expect(s.getState().streams['sess-1']?.length).toBe(1)
    s.getState().clearStream('sess-1')
    expect(s.getState().streams['sess-1']).toBeUndefined()
  })
  it('addToolCall 按块 id 精确挂工具卡片，id 不匹配不挂（WHY：后端同源前 tool_call id≠流式块 id，宁可缺也不挂错轮；同源后自动精确归属）', () => {
    const s = createChatStore()
    s.getState().ensureStreamBlock('sess-1', 'turn-a')
    s.getState().addToolCall('sess-1', 'turn-a', { id: 'tc-1', name: 'Bash', arguments: '{"cmd":"ls"}', result: null, isError: null })
    s.getState().addToolCall('sess-1', 'other-id', { id: 'tc-2', name: 'Grep', arguments: null, result: null, isError: null })
    const blocks = s.getState().streams['sess-1'] ?? []
    expect(blocks[0].toolCalls).toHaveLength(1)
    expect(blocks[0].toolCalls[0].name).toBe('Bash')
  })
  it('fillToolResult 按 toolCallId 匹配卡片填 result/isError（WHY：回放推 tool_call 与 tool_result 分属两事件，须用 toolCallId 跨块关联）', () => {
    const s = createChatStore()
    s.getState().ensureStreamBlock('sess-1', 'turn-a')
    s.getState().addToolCall('sess-1', 'turn-a', { id: 'tc-1', name: 'Bash', arguments: '{"cmd":"ls"}', result: null, isError: null })
    s.getState().fillToolResult('sess-1', 'tc-1', '{"ok":true}', false)
    const blocks = s.getState().streams['sess-1'] ?? []
    expect(blocks[0].toolCalls[0].result).toBe('{"ok":true}')
    expect(blocks[0].toolCalls[0].isError).toBe(false)
  })
  it('finalizeBlocks 将流式块转 assistant 消息并清流（WHY：complete 收口，id=turnAssistantId 与后端落库同源后即 DB 权威 id，前端免重拉）', () => {
    const s = createChatStore()
    s.getState().ensureStreamBlock('sess-1', 'turn-a')
    s.getState().appendReasoning('sess-1', 'turn-a', '思考')
    s.getState().appendChunk('sess-1', 'turn-a', '正文')
    s.getState().addToolCall('sess-1', 'turn-a', { id: 'tc-1', name: 'Bash', arguments: '{"cmd":"ls"}', result: 'ok', isError: false })
    s.getState().finalizeBlocks('sess-1')
    expect(s.getState().streams['sess-1']).toBeUndefined()
    const msg = s.getState().messages['sess-1']?.find(m => m.role === 'assistant')
    expect(msg?.id).toBe('turn-a')
    expect(msg?.content).toBe('正文')
    expect(msg?.reasoning).toBe('思考')
    expect(msg?.toolCalls?.[0].name).toBe('Bash')
  })
  it('permission 入队/出队', () => {
    const s = createChatStore()
    s.getState().enqueuePermission({ kind: 'message', sessionId: 'sess-1', requestId: 'r1', toolName: 'edit' })
    expect(s.getState().permissionQueue.length).toBe(1)
    s.getState().dequeuePermission('r1')
    expect(s.getState().permissionQueue.length).toBe(0)
  })
  it('expirePermission 超时留痕为系统消息', () => {
    const s = createChatStore()
    s.getState().enqueuePermission({ kind: 'message', sessionId: 'sess-1', requestId: 'r1', toolName: 'edit_file' })
    s.getState().expirePermission('sess-1', 'r1')
    expect(s.getState().permissionQueue.length).toBe(0)
    expect(s.getState().messages['sess-1']?.some(m => m.subtype === 'permission_timeout')).toBe(true)
  })
})

describe('chatStore 消息删除/停止', () => {
  it('removeMessage 按 id 删除后该消息不再出现在会话消息列表中（WHY：删除需真实反映到 transcript）', () => {
    const s = createChatStore()
    s.getState().setMessages('sess-1', [baseMsg('m1'), baseMsg('m2')])
    s.getState().removeMessage('sess-1', 'm1')
    const msgs = s.getState().messages['sess-1'] ?? []
    expect(msgs.some(m => m.id === 'm1')).toBe(false)
    expect(msgs.some(m => m.id === 'm2')).toBe(true)
  })
  it('removeMessage 只删除目标会话内的目标消息，不影响其他会话', () => {
    const s = createChatStore()
    s.getState().setMessages('sess-1', [baseMsg('m1')])
    s.getState().setMessages('sess-2', [baseMsg('x1', 'sess-2')])
    s.getState().removeMessage('sess-1', 'm1')
    expect(s.getState().messages['sess-1']?.length ?? 0).toBe(0)
    expect(s.getState().messages['sess-2']?.some(m => m.id === 'x1')).toBe(true)
  })
  it('clearStream 移除该会话流式状态（WHY：停止后回到可发送态，不再显示「停止」按钮）', () => {
    const s = createChatStore()
    s.getState().ensureStreamBlock('sess-1', 'turn-a')
    expect(s.getState().streams['sess-1']?.length).toBe(1)
    s.getState().clearStream('sess-1')
    expect(s.getState().streams['sess-1']).toBeUndefined()
  })
})

describe('chatStore conversationId', () => {
  it('setConversationId 落 store 且按会话隔离（WHY：partial 压缩后新 conversationId 用于消息 row key 刷新，跨会话不得串扰）', () => {
    const s = createChatStore()
    expect(s.getState().conversationIds['sess-1']).toBeUndefined()
    s.getState().setConversationId('sess-1', 'conv-1')
    expect(s.getState().conversationIds['sess-1']).toBe('conv-1')
    s.getState().setConversationId('sess-2', 'conv-2')
    expect(s.getState().conversationIds['sess-1']).toBe('conv-1')
    expect(s.getState().conversationIds['sess-2']).toBe('conv-2')
  })
})
