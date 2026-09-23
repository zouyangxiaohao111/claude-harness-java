import { describe, expect, it } from 'vitest'
import { createChatStore, MESSAGE_WINDOW_TURNS, IMAGE_CACHE_MAX_PER_SESSION } from '../chatStore'
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

/** 造 n 个「user + assistant」两轮条（assistant 的 userMessageId 指向其 user ⇒ 合计 n 轮）。 */
const turnSeqs = (n: number, sid = 'sess-1', prefix = 't'): ChatMessageDto[] =>
  Array.from({ length: n }, (_, i) => {
    const uid = `${prefix}${i}`
    return [
      baseMsg(uid, sid),
      { ...baseMsg(`${uid}-a`, sid), role: 'assistant' as const, userMessageId: uid },
    ]
  }).flat()

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

describe('chatStore message.usage 逐块挂载 + finalize 逐块优先', () => {
  it('applyMessageUsage 按 assistantMessageId 挂 usage/上下文快照到流式块（WHY：每条 assistant 流式结束推 message.usage → 实时缓存%/上下文条，不等 turn complete）', () => {
    const s = createChatStore()
    s.getState().ensureStreamBlock('sess-1', 'turn-a')
    s.getState().applyMessageUsage('sess-1', 'turn-a', {
      usage: { input_tokens: 100, output_tokens: 50 },
      contextTokensUsed: 800, contextWindow: 200000, percentLeft: 99,
    })
    const b = s.getState().streams['sess-1']?.[0]
    expect(b?.usage?.input_tokens).toBe(100)
    expect(b?.usage?.output_tokens).toBe(50)
    expect(b?.contextTokensUsed).toBe(800)
    expect(b?.contextWindow).toBe(200000)
    expect(b?.percentLeft).toBe(99)
  })
  it('applyMessageUsage 无匹配块 / 无 assistantMessageId 时 no-op 不崩溃（WHY：纯工具轮该条无文本块，由下一条 assistant / complete meta 兜底）', () => {
    const s = createChatStore()
    s.getState().ensureStreamBlock('sess-1', 'turn-a')
    const before = s.getState().streams['sess-1']
    s.getState().applyMessageUsage('sess-1', 'missing-id', { usage: { input_tokens: 1, output_tokens: 1 } })
    s.getState().applyMessageUsage('sess-1', null, { usage: { input_tokens: 1, output_tokens: 1 } })
    // 引用未变 → 状态未改动；块 usage 仍未挂载
    expect(s.getState().streams['sess-1']).toBe(before)
    expect(s.getState().streams['sess-1']?.[0]?.usage).toBeUndefined()
  })
  it('finalizeBlocks 逐块优先：多轮各块挂自身 usage，不共用末条 meta（WHY：turn 内每条 assistant 显示自己的 usage/上下文，complete 累计只兜底）', () => {
    const s = createChatStore()
    s.getState().ensureStreamBlock('sess-1', 'turn-a')
    s.getState().appendChunk('sess-1', 'turn-a', 'A')
    s.getState().applyMessageUsage('sess-1', 'turn-a', {
      usage: { input_tokens: 1000, output_tokens: 100, decode_ms: 500 },
      contextTokensUsed: 1200, contextWindow: 200000, percentLeft: 99,
    })
    s.getState().ensureStreamBlock('sess-1', 'turn-b')
    s.getState().appendChunk('sess-1', 'turn-b', 'B')
    s.getState().applyMessageUsage('sess-1', 'turn-b', {
      usage: { input_tokens: 2000, output_tokens: 200, decode_ms: 900 },
      contextTokensUsed: 2200, contextWindow: 200000, percentLeft: 98,
    })
    // complete meta 携带 turn 累计 —— 仅兜底（块自身带 usage/快照时以块为准）
    s.getState().finalizeBlocks('sess-1', {
      usage: { input_tokens: 3000, output_tokens: 300 }, contextTokensUsed: 3000,
      contextWindow: 200000, percentLeft: 97, decodeMs: 1400,
    })
    const msgs = s.getState().messages['sess-1'] ?? []
    const ma = msgs.find((m) => m.content === 'A')
    const mb = msgs.find((m) => m.content === 'B')
    expect(ma?.usage?.input_tokens).toBe(1000)
    expect(ma?.contextTokensUsed).toBe(1200)
    expect(ma?.percentLeft).toBe(99)
    expect(ma?.decodeMs).toBe(500)   // decodeMs 取块内 usage.decode_ms，非 complete meta
    expect(mb?.usage?.input_tokens).toBe(2000)
    expect(mb?.contextTokensUsed).toBe(2200)
    expect(mb?.percentLeft).toBe(98)
    expect(mb?.decodeMs).toBe(900)
  })
  it('finalizeBlocks 回落：块未挂 usage 时用 complete meta 兜底（WHY：旧后端无 message.usage / 纯工具轮 → complete 累计照常透传）', () => {
    const s = createChatStore()
    s.getState().ensureStreamBlock('sess-1', 'turn-a')
    s.getState().appendChunk('sess-1', 'turn-a', 'A')
    s.getState().finalizeBlocks('sess-1', {
      usage: { input_tokens: 3000, output_tokens: 300 }, contextTokensUsed: 3000,
      contextWindow: 200000, percentLeft: 97,
    })
    const m = s.getState().messages['sess-1']?.[0]
    expect(m?.usage?.input_tokens).toBe(3000)
    expect(m?.contextTokensUsed).toBe(3000)
    expect(m?.contextWindow).toBe(200000)
  })
})

describe('chatStore streamTicks（[chat-switch-stream-align] 流式活动节拍 · 滚底按会话隔离）', () => {
  it('appendChunk/appendReasoning 成功 → 本会话节拍 +1；块不存在 no-op 不递增；会话间隔离', () => {
    const s = createChatStore()
    expect(s.getState().streamTicks['sess-1'] ?? 0).toBe(0)
    s.getState().ensureStreamBlock('sess-1', 'turn-a')
    expect(s.getState().streamTicks['sess-1'] ?? 0).toBe(0) // 建块不推进节拍
    s.getState().appendChunk('sess-1', 'turn-a', '一')
    expect(s.getState().streamTicks['sess-1'] ?? 0).toBe(1)
    s.getState().appendReasoning('sess-1', 'turn-a', '想')
    expect(s.getState().streamTicks['sess-1'] ?? 0).toBe(2)
    // 不匹配块 → no-op，节拍不推进（WHY：滚底订阅靠 tick 判断「本会话内容推进」，无效 append 不得触发滚动）
    s.getState().appendChunk('sess-1', 'missing', 'x')
    s.getState().appendReasoning('sess-1', 'missing', 'x')
    expect(s.getState().streamTicks['sess-1'] ?? 0).toBe(2)
    // 会话间隔离：sess-2 未推进（WHY：A 会话打字不得拖拽当前显示的 B 容器滚动）
    expect(s.getState().streamTicks['sess-2'] ?? 0).toBe(0)
  })
  it('ensureStreamBlock 建块不推进；clearStream/finalize 不清节拍（节拍只表示内容推进，不随块生命周期归零）', () => {
    const s = createChatStore()
    s.getState().ensureStreamBlock('sess-1', 'turn-a')
    s.getState().appendChunk('sess-1', 'turn-a', 'x')
    s.getState().clearStream('sess-1')
    expect(s.getState().streamTicks['sess-1'] ?? 0).toBe(1) // 保留：切回后内容继续推进 tick 变 → 正确触发一次滚底
  })
})

describe('chatStore hasMore / prependMessages（[window-paging] 有界历史窗口）', () => {
  it('setHasMore 落 store 且按会话隔离（WHY：顶部「加载更早」按钮显隐按会话独立）', () => {
    const s = createChatStore()
    s.getState().setHasMore('sess-1', true)
    s.getState().setHasMore('sess-2', false)
    expect(s.getState().hasMore['sess-1']).toBe(true)
    expect(s.getState().hasMore['sess-2']).toBe(false)
  })
  it('prependMessages 头部插更早 + overlap 幂等去重 + 更新 hasMore（向上翻页保持 created_at 时序）', () => {
    const s = createChatStore()
    s.getState().setMessages('sess-1', [baseMsg('m3'), baseMsg('m4')])
    s.getState().prependMessages('sess-1', [baseMsg('m1'), baseMsg('m2'), baseMsg('m3')], true)
    expect(s.getState().messages['sess-1']?.map((m) => m.id)).toEqual(['m1', 'm2', 'm3', 'm4'])
    expect(s.getState().hasMore['sess-1']).toBe(true)
  })
  it('prependMessages 前页含 snip_boundary → 并入 snippedIds（照常「已裁剪」标注）', () => {
    const s = createChatStore()
    s.getState().setMessages('sess-1', [baseMsg('m5')])
    const boundary: ChatMessageDto = { ...baseMsg('b1'), subtype: 'snip_boundary', snipMetadata: { removedUuids: ['u1', 'u2'] } }
    s.getState().prependMessages('sess-1', [boundary, baseMsg('m4')], false)
    expect(s.getState().snippedIds['sess-1']).toEqual(['u1', 'u2'])
    expect(s.getState().hasMore['sess-1']).toBe(false)
  })
})

// [内存/性能] 窗口 = 最近 MESSAGE_WINDOW_TURNS 个【对话轮】（用户 2026-09-23 裁定）。
// 沿革：原实现四条追加路径全不裁剪 → 无界涨（WebView2 renderer 实测 1.2GB）；先补「按条裁 300」，
//   但用户实测「窗口没遵循 50、随对话不断增大」—— 300 条远大于 50，且点过「加载更早」后
//   extendedWindow 会把上限【永久】抬到 500 条。现统一为「轮」口径，判据与 MessageList 渲染分组同源
//   （唯一真源 = stores/messageTurns）。
// 这些用例守住两条不变量：①追加有界（漏一条路径即再泄漏）②裁的是【整轮】（切半会渲染出半截轮）。
describe('chatStore 有界窗口（窗口 = 最近 N 个对话轮）', () => {
  const many = (n: number, sid = 'sess-1', prefix = 'm') =>
    Array.from({ length: n }, (_, i) => baseMsg(`${prefix}${i}`, sid))

  it('finalizeBlocks 追加后超窗口 → 从头部挤出最老的整轮，轮数稳定在 N（WHY：无线增长是 renderer 1.2GB 的根因）', () => {
    const s = createChatStore()
    s.getState().setMessages('sess-1', many(MESSAGE_WINDOW_TURNS))
    s.getState().ensureStreamBlock('sess-1', 'turn-x')
    s.getState().appendChunk('sess-1', 'turn-x', '正文')
    s.getState().finalizeBlocks('sess-1')
    const msgs = s.getState().messages['sess-1'] ?? []
    expect(msgs).toHaveLength(MESSAGE_WINDOW_TURNS)      // 51 轮 → 裁回 50
    expect(msgs.some((m) => m.id === 'm0')).toBe(false)  // 最老的一轮被挤出
    expect(msgs[msgs.length - 1].id).toBe('turn-x')      // 最新（块落库）仍在
  })

  it('appendMetaUser（渲染不可见的占位）不占轮、不挤出真实轮；expirePermission 的可见留痕占轮（WHY：轮的计数口径必须与「渲染可见性」一致，否则看不见的占位会顶掉用户真实对话）', () => {
    const s = createChatStore()
    s.getState().setMessages('sess-1', many(MESSAGE_WINDOW_TURNS))
    s.getState().appendMetaUser('sess-1', 'u-new')   // isMeta 默认 true ⇒ groups 跳过 ⇒ 不算一轮
    let msgs = s.getState().messages['sess-1'] ?? []
    expect(msgs.some((m) => m.id === 'm0')).toBe(true)   // 真实轮没被挤出（占位不占轮）
    expect(msgs[msgs.length - 1].id).toBe('u-new')

    s.getState().enqueuePermission({ kind: 'message', sessionId: 'sess-1', requestId: 'r1', toolName: 'edit' })
    s.getState().expirePermission('sess-1', 'r1')        // role=system / isMeta=false ⇒ 渲染可见 ⇒ 占一轮
    msgs = s.getState().messages['sess-1'] ?? []
    expect(msgs.some((m) => m.id === 'm0')).toBe(false)  // 最老的真实轮被挤出
    expect(msgs.some((m) => m.id === 'perm-r1')).toBe(true)
  })

  it('setMessages（服务端整表替换路径）按设计【不裁剪】（WHY：头部可能是 compact 摘要 / boundary 标记，裁了会误删标记 —— 分工见 setMessages 注释）', () => {
    const s = createChatStore()
    s.getState().setMessages('sess-1', turnSeqs(80))     // 80 轮 = 160 条
    expect(s.getState().messages['sess-1']).toHaveLength(160)
  })

  it('窗口按【轮】而非按条：一轮两条时保留整轮，绝不切半（WHY：切半会渲染出「有问无答」的半截轮）', () => {
    const s = createChatStore()
    // ⚠️ 必须走 appendMessages：setMessages 是「服务端整表替换」路径，按设计不裁剪（见上一个用例）
    s.getState().appendMessages('sess-1', turnSeqs(MESSAGE_WINDOW_TURNS + 10))  // 60 轮 = 120 条
    const msgs = s.getState().messages['sess-1'] ?? []
    expect(msgs).toHaveLength(MESSAGE_WINDOW_TURNS * 2)   // 50 轮 × 2 条
    // 被挤出的最老一轮：user 与 assistant 一起走（不得只留 assistant）
    expect(msgs.some((m) => m.id === 't0')).toBe(false)
    expect(msgs.some((m) => m.id === 't0-a')).toBe(false)
    // 最老保留轮：user 与 assistant 成对在（切点落在轮首，不落轮中）
    expect(msgs[0].id).toBe('t10')
    expect(msgs[1].id).toBe('t10-a')
    expect(msgs[msgs.length - 1].id).toBe(`t${MESSAGE_WINDOW_TURNS + 9}-a`)
  })

  it('按【轮】计数而非按条计数：轮内多出的 tool / 纯 meta 行不额外占轮（WHY：口径必须与 MessageList 渲染分组同源）', () => {
    const s = createChatStore()
    // 59 轮（t0..t58）；把最老一轮 t0 换成「user + tool行 + meta行 + assistant」四条
    const rest = turnSeqs(59).slice(2)   // t1..t58（118 条）
    const withNoise: ChatMessageDto[] = [
      baseMsg('t0'),
      { ...baseMsg('t0-tool'), role: 'tool' },
      { ...baseMsg('t0-meta'), isMeta: true },
      { ...baseMsg('t0-a'), role: 'assistant', userMessageId: 't0' },
      ...rest,
    ]
    s.getState().setMessages('sess-1', withNoise)
    s.getState().appendMessages('sess-1', [baseMsg('tail')])   // 60 轮 → 触发裁剪到 50 轮
    const msgs = s.getState().messages['sess-1'] ?? []
    // 结论一：t0 整轮（连同其 tool/meta 噪声行）被挤出 —— 噪声行没被当成独立轮而留下
    expect(msgs.some((m) => m.id === 't0-tool')).toBe(false)
    expect(msgs.some((m) => m.id === 't0-meta')).toBe(false)
    // 结论二：留下的轮数恰好 = MESSAGE_WINDOW_TURNS（用对话行去重计数，不依赖条数算术）
    const keptTurns = new Set(msgs.filter((m) => m.role !== 'tool' && !m.isMeta).map((m) => m.userMessageId ?? m.id))
    expect(keptTurns.size).toBe(MESSAGE_WINDOW_TURNS)
    expect(msgs[msgs.length - 1].id).toBe('tail')
  })

  it('prepend 不施加轮窗口裁剪（WHY：prepend 是用户显式行为；若这里也裁，刚加载的更早页被同次舍掉 = 按钮点了没反应）', () => {
    const s = createChatStore()
    s.getState().setMessages('sess-1', many(MESSAGE_WINDOW_TURNS))
    s.getState().prependMessages('sess-1', many(50, 'sess-1', 'old'), true)
    const msgs = s.getState().messages['sess-1'] ?? []
    expect(msgs).toHaveLength(MESSAGE_WINDOW_TURNS + 50)
    expect(msgs[0].id).toBe('old0')   // 更早页确实进了窗口头部
  })

  it('prepend 后下一次 append 按 LRU 从头部挤出最早一轮（用户 2026-09-23 裁定：来一条，上面的归为历史）', () => {
    const s = createChatStore()
    s.getState().setMessages('sess-1', many(MESSAGE_WINDOW_TURNS))
    s.getState().prependMessages('sess-1', many(50, 'sess-1', 'old'), true)
    s.getState().appendMessages('sess-1', [baseMsg('new1')])
    const msgs = s.getState().messages['sess-1'] ?? []
    expect(msgs).toHaveLength(MESSAGE_WINDOW_TURNS)       // 恒为最近 50 轮
    expect(msgs.some((m) => m.id === 'old0')).toBe(false) // 更早页被挤出（回看走「加载更早」重拉）
    expect(msgs[msgs.length - 1].id).toBe('new1')
  })

  it('clearSession 释放该会话全部会话级状态（含 imageCache/hasMore/msgTotals/snippedIds）（WHY：删会话后这些键永不读取，纯占内存）', () => {
    const s = createChatStore()
    s.getState().setMessages('sess-1', [baseMsg('m1')])
    s.getState().setHasMore('sess-1', true)
    s.getState().setMsgTotal('sess-1', 9)
    s.getState().markSnipped('sess-1', ['u1'])
    s.getState().setImageCache('sess-1', { img1: { mediaType: 'image/png', base64: 'AAA' } })
    s.getState().setConversationId('sess-1', 'conv-1')
    s.getState().ensureStreamBlock('sess-1', 'turn-a')
    s.getState().appendChunk('sess-1', 'turn-a', 'x') // 置 streamTicks
    s.getState().clearSession('sess-1')
    const st = s.getState()
    expect(st.messages['sess-1']).toBeUndefined()
    expect(st.streams['sess-1']).toBeUndefined()
    expect(st.imageCache['sess-1']).toBeUndefined()
    expect(st.hasMore['sess-1']).toBeUndefined()
    expect(st.msgTotals['sess-1']).toBeUndefined()
    expect(st.snippedIds['sess-1']).toBeUndefined()
    expect(st.conversationIds['sess-1']).toBeUndefined()
    expect(st.streamTicks['sess-1']).toBeUndefined()
  })
})

describe('chatStore imageCache 淘汰上限（[内存] base64 永不释放 → 有界）', () => {
  it('setImageCache 超 IMAGE_CACHE_MAX_PER_SESSION 按插入序淘汰最旧、保留最新（WHY：粘贴/缩略图 base64 每条数 MB）', () => {
    const s = createChatStore()
    const img = (i: number) => ({ [`img${i}`]: { mediaType: 'image/png', base64: 'A'.repeat(8) } })
    const bulk = Object.assign({}, ...Array.from({ length: IMAGE_CACHE_MAX_PER_SESSION + 1 }, (_, i) => img(i)))
    s.getState().setImageCache('sess-1', bulk)
    const cache = s.getState().imageCache['sess-1'] ?? {}
    expect(Object.keys(cache)).toHaveLength(IMAGE_CACHE_MAX_PER_SESSION)
    expect(cache['img0']).toBeUndefined()                                  // 最旧被淘汰
    expect(cache[`img${IMAGE_CACHE_MAX_PER_SESSION}`]).toBeDefined()       // 最新保留
  })

  it('setImageCache 会话间隔离（WHY：A 会话图片淘汰不得影响 B 会话）', () => {
    const s = createChatStore()
    s.getState().setImageCache('sess-1', { a: { mediaType: 'image/png', base64: 'A' } })
    s.getState().setImageCache('sess-2', { b: { mediaType: 'image/png', base64: 'B' } })
    expect(s.getState().imageCache['sess-1']?.['a']).toBeDefined()
    expect(s.getState().imageCache['sess-2']?.['b']).toBeDefined()
  })
})

// [内存] appendMessages 是 App 三条本地追加路径（queue.drained 排队气泡 / away-summary 回插 / 发送成功
// 乐观 user 气泡）的唯一入口。这些路径原先各自 setMessages([...prev, new]) 绕过裁剪 —— 其中 away-summary
// 由 blur 触发、不保证随后有 finalize 兜底 → 该会话生命周期内只增不减（核验复现 300+400=700）。
// 本组守住「本地追加也有界」这条不变量：若有人改回 setMessages 拼接，长度断言会立即失败。
describe('chatStore appendMessages（本地追加也必须有界）', () => {
  it('连续 append 超过 MESSAGE_WINDOW_TURNS 轮 → 轮数稳定在 N，不随追加次数增长（WHY：核验曾复现 300+400=700）', () => {
    const s = createChatStore()
    for (let i = 0; i < MESSAGE_WINDOW_TURNS + 400; i++) {
      s.getState().appendMessages('sess-1', [baseMsg(`a${i}`)])
    }
    const msgs = s.getState().messages['sess-1'] ?? []
    expect(msgs).toHaveLength(MESSAGE_WINDOW_TURNS)   // 450 轮 → 稳定 50
    expect(msgs.some((m) => m.id === 'a0')).toBe(false)  // 最老的先走
    expect(msgs[msgs.length - 1].id).toBe(`a${MESSAGE_WINDOW_TURNS + 399}`) // 最新仍在
  })

  it('单次批量追加超上限同样裁到 N（WHY：queue.drained 一次可回插多条）', () => {
    const s = createChatStore()
    s.getState().setMessages('sess-1', Array.from({ length: MESSAGE_WINDOW_TURNS }, (_, i) => baseMsg(`m${i}`)))
    s.getState().appendMessages('sess-1', Array.from({ length: 150 }, (_, i) => baseMsg(`batch${i}`)))
    expect(s.getState().messages['sess-1']).toHaveLength(MESSAGE_WINDOW_TURNS)
  })

  it('空追加早退、保持数组引用稳定（WHY：避免无谓重渲）', () => {
    const s = createChatStore()
    s.getState().setMessages('sess-1', [baseMsg('m1')])
    const before = s.getState().messages
    s.getState().appendMessages('sess-1', [])
    expect(s.getState().messages).toBe(before)
  })

  it('批量追加超窗口时按【轮】裁，不把一轮切半（WHY：切半会渲染出半截轮）', () => {
    const s = createChatStore()
    s.getState().appendMessages('sess-1', turnSeqs(MESSAGE_WINDOW_TURNS + 5))   // 55 轮 = 110 条
    const msgs = s.getState().messages['sess-1'] ?? []
    expect(msgs).toHaveLength(MESSAGE_WINDOW_TURNS * 2)
    expect(msgs[0].userMessageId ?? msgs[0].id).toBe('t5')                     // 切点在轮首
    expect(msgs[msgs.length - 1].id).toBe(`t${MESSAGE_WINDOW_TURNS + 4}-a`)
  })
})
