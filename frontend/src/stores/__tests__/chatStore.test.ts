import { describe, expect, it } from 'vitest'
import { createChatStore, IMAGE_CACHE_MAX_PER_SESSION } from '../chatStore'
import { FOLD_DEFAULT_TURNS } from '../messageTurns'
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

/** 造 n 个「user + assistant」两轮条（两行 userMessageId 都指向该 user ⇒ 合计 n 轮）。
 *  ⚠️ user 行必须带自身锚：wire 契约 = 「user 消息 userMessageId=自身 id」（ChatMessageDto javadoc /
 *  appendMetaUser）。缺锚的 user 行在新 turnKeyOf 规则下会被归到【上一轮】（无锚 → 最近前置锚）。 */
const turnSeqs = (n: number, sid = 'sess-1', prefix = 't'): ChatMessageDto[] =>
  Array.from({ length: n }, (_, i) => {
    const uid = `${prefix}${i}`
    return [
      { ...baseMsg(uid, sid), userMessageId: uid },
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

  it('usageSource 逐块挂载 + finalize 透传到消息（WHY：D1 底部数字只统计「用户自己的请求」—— 来源必须跟着 usage 一路到消息，否则后台任务 run 的快照仍会被当成用户的最新一条）', () => {
    const s = createChatStore()
    s.getState().ensureStreamBlock('sess-1', 'turn-a')
    s.getState().appendChunk('sess-1', 'turn-a', 'A')
    s.getState().applyMessageUsage('sess-1', 'turn-a', {
      usage: { input_tokens: 100, output_tokens: 10 },
      contextTokensUsed: 100, contextWindow: 200000, percentLeft: 99,
      usageSource: 'background',
    })
    const b = s.getState().streams['sess-1']?.[0]
    expect(b?.usageSource).toBe('background')

    s.getState().finalizeBlocks('sess-1')
    const m = s.getState().messages['sess-1']?.[0]
    expect(m?.usageSource).toBe('background')
  })

  it('usageSource 缺省（旧帧 / 旧消息）保持「无值」—— 展示侧按「用户来源」计（不得臆造 background 隐藏数据）', () => {
    const s = createChatStore()
    s.getState().ensureStreamBlock('sess-1', 'turn-a')
    s.getState().applyMessageUsage('sess-1', 'turn-a', { usage: { input_tokens: 1, output_tokens: 1 } })
    expect(s.getState().streams['sess-1']?.[0]?.usageSource).toBeUndefined()
    s.getState().finalizeBlocks('sess-1')
    // 块转消息的字段一律 `?? null`（与相邻 usage/totalCostUsd 同款）→ 无来源 = null（非 'background'）
    expect(s.getState().messages['sess-1']?.[0]?.usageSource ?? null).toBeNull()
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

// [折叠 = 真删 · 2026-11-02 用户裁定（推翻 2026-10-29）]
//
// 沿革：2026-10-29 裁定「不应该挤掉而是折叠显示」⇒ store 只增不减、靠渲染层折叠行遮住。
//   2026-11-02 用户实测长会话卡顿后推翻：「随着对话变多会卡顿，不应该默认显示 2 轮次、收起来的
//   会话默认不渲染吗；另外会话收起来代表内存删除，展开就是重新获取后端的会话，不是取 store 的；
//   F5 应该也是这样」。
//
// WHY 只藏 DOM 不够：MessageList 的 foldedSet 虽不渲染，但 groups / flatRows / turnKeyByRowId /
//   turnUsage / footerByRowKey 等十几处 useMemo 每轮都要扫全部已加载消息 —— 消息越多越慢。
//
// 本组守住新不变量：
//   ① 追加任何消息后，内存里**只留最近 FOLD_DEFAULT_TURNS 轮**，且**整轮不切半**；
//   ② 被移出的真实轮数累加进 foldedTurnCounts（渲染层「已折叠 N 轮」），且 hasMore 置 true；
//   ③ setMessages（服务端整表替换）照收不裁 —— 调用方已按 N 轮取数，折叠计数归零。
describe('chatStore 折叠 = 真删（内存只留最近 N 轮 · 用户 2026-11-02 裁定）', () => {
  const KEEP = FOLD_DEFAULT_TURNS

  it('append 新轮后：内存只剩最近 N 轮，被移出的轮计入 foldedTurnCounts（WHY：卡顿根因是全部消息每轮全扫）', () => {
    const s = createChatStore()
    s.getState().setMessages('sess-1', turnSeqs(KEEP))
    s.getState().appendMessages('sess-1', turnSeqs(1, 'sess-1', 'n'))   // 追加第 N+1 轮
    const msgs = s.getState().messages['sess-1'] ?? []
    expect(msgs).toHaveLength(KEEP * 2)                                 // 内存有界（2 轮 × 2 行）
    expect(msgs.some((m) => m.id === 't0')).toBe(false)                 // 最早一轮已移出内存
    expect(s.getState().foldedTurnCounts['sess-1']).toBe(1)             // 但计入「已折叠」
    expect(s.getState().hasMore['sess-1']).toBe(true)                   // 且知道还能往回拉
    expect(msgs[msgs.length - 1]!.id).toBe('n0-a')                      // 新内容在
  })

  it('连续追加 200 轮：内存条数**有界**，折叠计数随移出轮数增长（WHY：这就是卡顿修复的本体）', () => {
    const s = createChatStore()
    s.getState().appendMessages('sess-1', turnSeqs(1, 'sess-1', 'first'))
    for (let i = 0; i < 200; i++) s.getState().appendMessages('sess-1', turnSeqs(1, 'sess-1', `x${i}`))
    const msgs = s.getState().messages['sess-1'] ?? []
    expect(msgs.length).toBeLessThanOrEqual(KEEP * 2)                   // ⛔ 不再线性增长
    expect(msgs[msgs.length - 1]!.id).toBe('x1990-a')                   // 最新一轮在
    // 201 轮里保留 KEEP 轮 ⇒ 移出 201 - KEEP 轮
    expect(s.getState().foldedTurnCounts['sess-1']).toBe(201 - KEEP)
  })

  it('单次批量追加也不切半轮（WHY：一轮几百条 tool 消息是正常形态，切半会渲染出残轮）', () => {
    const s = createChatStore()
    s.getState().setMessages('sess-1', turnSeqs(KEEP))
    s.getState().appendMessages('sess-1', turnSeqs(150, 'sess-1', 'b'))
    const msgs = s.getState().messages['sess-1'] ?? []
    expect(msgs).toHaveLength(KEEP * 2)
    // 最后一行人与其 user 行必须成对（整轮保留）
    expect(msgs[msgs.length - 1]!.id).toBe('b149-a')
    expect(msgs.some((m) => m.id === 'b149')).toBe(true)
  })

  it('finalizeBlocks / appendMetaUser / 权限留痕 追加的新行都在，且内存仍有界', () => {
    const s = createChatStore()
    s.getState().setMessages('sess-1', turnSeqs(KEEP))
    s.getState().ensureStreamBlock('sess-1', 'turn-x')
    s.getState().appendChunk('sess-1', 'turn-x', '正文')
    s.getState().finalizeBlocks('sess-1')
    s.getState().appendMetaUser('sess-1', 'u-new')
    s.getState().enqueuePermission({ kind: 'message', sessionId: 'sess-1', requestId: 'r1', toolName: 'edit' })
    s.getState().expirePermission('sess-1', 'r1')
    const msgs = s.getState().messages['sess-1'] ?? []
    expect(msgs.some((m) => m.id === 'turn-x')).toBe(true)  // 块落库行在
    expect(msgs.some((m) => m.id === 'u-new')).toBe(true)   // 元消息行在
    expect(msgs.some((m) => m.id === 'perm-r1')).toBe(true) // 权限超时留痕在
    expect(msgs[msgs.length - 1]!.id).toBe('perm-r1')
    expect(msgs.length).toBeLessThanOrEqual((KEEP + 1) * 2 + 3)  // 有界（容忍尾部附加行）
  })

  it('insertServerMessages / addToolUseSummary / addStopHookSummary 的新行不丢', () => {
    const s = createChatStore()
    s.getState().setMessages('sess-1', turnSeqs(KEEP))
    s.getState().insertServerMessages('sess-1', [
      { ...baseMsg('b1'), role: 'system', author: 'system', subtype: 'compact_boundary' },
    ])
    s.getState().addToolUseSummary('sess-1', { id: 'sum1', content: '/tool ok', userMessageId: `t${KEEP - 1}` })
    s.getState().addStopHookSummary('sess-1', {
      id: 'sh1', userMessageId: `t${KEEP - 1}`,
      payload: { hookCount: 1, hookErrors: [], preventedContinuation: false },
    })
    const msgs = s.getState().messages['sess-1'] ?? []
    for (const id of ['b1', 'sum1', 'sh1']) {
      expect(msgs.some((m) => m.id === id), `${id} 不应被追加路径丢弃`).toBe(true)
    }
  })

  it('setMessages（服务端整表替换）照收不裁，且折叠计数归零（WHY：调用方已按 N 轮取数）', () => {
    const s = createChatStore()
    s.getState().setMessages('sess-1', turnSeqs(80))     // 80 轮 = 160 条
    expect(s.getState().messages['sess-1']).toHaveLength(160)
    expect(s.getState().foldedTurnCounts['sess-1']).toBe(0)
  })

  it('appendMessages 同 id 幂等：重复追加不产生重复行（WHY：用户实测整轮内容重复十几次）', () => {
    // 意图：本入口是「重拉 / 重放 / 乐观气泡」的共用尾部追加路径，此前**无 id 去重**。
    //   同一条消息被追加两次（queue.drained 重复投递、乐观气泡与回放同 id 各来一次）时，
    //   messages 里会出现同 id 两行 ⇒ 渲染层 row key 相同 ⇒ 整块内容重复显示、同一提问被算成两轮。
    //   语义与 finalizeBlocks 的 dupIdx 一致：已存在则**原地替换**（保位置、不重插）。
    const s = createChatStore()
    s.getState().appendMessages('sess-1', [baseMsg('q1')])
    s.getState().appendMessages('sess-1', [baseMsg('q1')])
    s.getState().appendMessages('sess-1', [baseMsg('a1'), baseMsg('a1')])
    const msgs = s.getState().messages['sess-1'] ?? []
    expect(msgs.map((m) => m.id)).toEqual(['q1', 'a1'])   // 重复 id 只保留一行，位置不变
  })

  it('revealOlderTurns：展开折叠行 → 更早的轮回到头部，折叠计数扣减（WHY：展开=重新取后端）', () => {
    const s = createChatStore()
    s.getState().setMessages('sess-1', turnSeqs(2, 'sess-1', 'cur'))
    // 模拟「后端拉回更早 2 轮」
    s.getState().revealOlderTurns('sess-1', turnSeqs(2, 'sess-1', 'old'))
    const msgs = s.getState().messages['sess-1'] ?? []
    expect(msgs).toHaveLength(8)                                  // 2 旧轮 + 2 现轮
    expect(msgs[0]!.id).toBe('old0')                              // 更早的轮在头部
    // 折叠计数从 3 → 1（拉回 2 轮）
    s.getState().revealOlderTurns('sess-1', turnSeqs(1, 'sess-1', 'older'))
    expect(s.getState().messages['sess-1']).toHaveLength(10)
  })

  it('pruneFoldedTurns：显式裁剪到 N 轮并累计折叠数（WHY：收口入口可被测试与调用方直接用）', () => {
    const s = createChatStore()
    s.getState().setMessages('sess-1', turnSeqs(6))
    expect(s.getState().messages['sess-1']).toHaveLength(12)
    s.getState().pruneFoldedTurns('sess-1', 2)
    expect(s.getState().messages['sess-1']).toHaveLength(4)        // 只留 2 轮
    expect(s.getState().foldedTurnCounts['sess-1']).toBe(4)        // 移出 4 轮
    expect(s.getState().hasMore['sess-1']).toBe(true)
    // 幂等：再裁一次没有可删的 → 计数不变
    s.getState().pruneFoldedTurns('sess-1', 2)
    expect(s.getState().foldedTurnCounts['sess-1']).toBe(4)
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
// 乐观 user 气泡）的唯一入口。「不丢弃已加载轮」的不变量见上方 describe；这里只守「空追加不换引用」
// （避免每次 onLoadOlder 未命中/气泡为空时制造新数组 → 整列表重渲）。
describe('chatStore appendMessages（空追加早退）', () => {
  it('空追加早退、保持数组引用稳定（WHY：避免无谓重渲）', () => {
    const s = createChatStore()
    s.getState().setMessages('sess-1', [baseMsg('m1')])
    const before = s.getState().messages
    s.getState().appendMessages('sess-1', [])
    expect(s.getState().messages).toBe(before)
  })
})

// [sm-boundary-reload] 压缩（SM/auto）落库成功即经 stream 通道推 message.insert（整行载荷 = compact
//   boundary 行）→ 不等 turn 收尾 / F5，分割线就该出现；同一 id 还有第二条通道（complete.compacted
//   收尾重拉 = setMessages 整表替换）。本组守住两条不变量：①即时插行 ②两条通道按 id 收敛（不得重复行）。
describe('[sm-boundary-reload] insertServerMessages（message.insert 即时插行）', () => {
  /** compact boundary 行（形状对齐后端 MessageInsertEvent.messages 元素：
   *  role=system + author=system + subtype=compact_boundary + isMeta=false · CompactBoundaryMessage.toChatMessageDto）。 */
  const boundaryRow = (id = 'b1'): ChatMessageDto => ({
    ...baseMsg(id), role: 'system', author: 'system', subtype: 'compact_boundary',
    content: 'Conversation compacted', isMeta: false,
  })

  it('插入并追加到列表尾（WHY：压缩落库那一刻分割线就要显示，不等 turn 收尾 / F5）', () => {
    const s = createChatStore()
    s.getState().setMessages('sess-1', [baseMsg('m1'), baseMsg('m2')])
    s.getState().insertServerMessages('sess-1', [boundaryRow()])
    const msgs = s.getState().messages['sess-1'] ?? []
    expect(msgs).toHaveLength(3)
    expect(msgs[msgs.length - 1]?.id).toBe('b1')   // 插到列表尾（当前点）
  })

  it('同 id 幂等：重复到达不产生重复行（WHY：即时层 + 重放/重拉双通道双达不得出双行）', () => {
    const s = createChatStore()
    s.getState().setMessages('sess-1', [baseMsg('m1')])
    s.getState().insertServerMessages('sess-1', [boundaryRow()])
    s.getState().insertServerMessages('sess-1', [boundaryRow()])   // 同 id 再来
    const msgs = s.getState().messages['sess-1'] ?? []
    expect(msgs.filter((m) => m.id === 'b1')).toHaveLength(1)
  })

  it('与重拉收敛：重拉尾页已含该行时，同 id 事件到达不再插（WHY：complete.compacted 重拉先到、实时推后到）', () => {
    const s = createChatStore()
    s.getState().setMessages('sess-1', [baseMsg('m1'), baseMsg('m2'), boundaryRow()])  // 重拉（整表替换）已含 boundary 行
    s.getState().insertServerMessages('sess-1', [boundaryRow()])
    const msgs = s.getState().messages['sess-1'] ?? []
    expect(msgs).toHaveLength(3)
    expect(msgs.filter((m) => m.id === 'b1')).toHaveLength(1)
  })

  it('boundary 行随其所属轮保留；追加新轮时内存按轮裁剪（2026-11-02 折叠=真删）', () => {
    const s = createChatStore()
    s.getState().setMessages('sess-1', turnSeqs(50))     // 已加载 50 轮（t0..t49）
    s.getState().insertServerMessages('sess-1', [boundaryRow()])
    let msgs = s.getState().messages['sess-1'] ?? []
    expect(msgs[msgs.length - 1]?.id).toBe('b1')          // 插入的行在尾部
    // 再来一条真实轮（user+assistant）→ 内存裁到最近 N 轮，但**尾部**的 boundary 不丢
    s.getState().appendMessages('sess-1', turnSeqs(1, 'sess-1', 'z'))
    msgs = s.getState().messages['sess-1'] ?? []
    expect(msgs.some((m) => m.id === 'b1')).toBe(true)     // boundary 随其所属轮保留（在尾部）
    expect(msgs[msgs.length - 1]?.id).toBe('z0-a')         // 新轮在
    expect(msgs.some((m) => m.id === 't0')).toBe(false)    // 最老轮已移出内存（真删）
    expect(s.getState().foldedTurnCounts['sess-1']).toBeGreaterThan(0)
    expect(msgs.length).toBeLessThanOrEqual(FOLD_DEFAULT_TURNS * 2 + 1)
  })
})

describe('setMessages 保留本地 imageData（修「截图发送后不显示、F5 才出现」第二条腿）', () => {
  /**
   * WHY：整表替换会丢掉只在本地存在的 `imageData`（base64，DB 不存该字段）。而重拉可能发生在后端
   * 回写 `image_paste_ids` **之前**（实测建行→回写约 0.9s）—— 那一刻新行既无 imageData 也无
   * imagePasteIds ⇒ 图彻底消失，用户感知就是「必须 F5」。变异点：删掉续接逻辑 → 本用例红。
   */
  it('⭐ 重拉回来的同 id 行缺图 ⇒ 把本地乐观 imageData 续接上', () => {
    const s = createChatStore()
    const local: ChatMessageDto = { ...baseMsg('m1'), imageData: [{ base64: 'AAA', mediaType: 'image/png' }] }
    s.getState().setMessages('sess-1', [local])
    // 重拉：后端尚未回写 image_paste_ids ⇒ 该行两个图片字段都是空
    s.getState().setMessages('sess-1', [{ ...baseMsg('m1'), imagePasteIds: [] }])
    const got = (s.getState().messages['sess-1'] ?? [])[0]
    expect(got?.imageData, '本地 base64 必须续接（否则图整段消失，只能等 F5）').toEqual([
      { base64: 'AAA', mediaType: 'image/png' },
    ])
  })

  it('后端已回写 imagePasteIds ⇒ 用读侧渠道，不覆盖本地 imageData（渲染源优先级不变）', () => {
    const s = createChatStore()
    s.getState().setMessages('sess-1', [
      { ...baseMsg('m1'), imageData: [{ base64: 'AAA', mediaType: 'image/png' }] },
    ])
    s.getState().setMessages('sess-1', [{ ...baseMsg('m1'), imagePasteIds: ['123'] }])
    const got = (s.getState().messages['sess-1'] ?? [])[0]
    expect(got?.imagePasteIds).toEqual(['123'])
    expect(got?.imageData, '已是权威 id 列表 ⇒ 不塞回 base64（避免双份内存）').toBeUndefined()
  })

  it('本地没有 imageData 的行原样返回（不凭空造字段）', () => {
    const s = createChatStore()
    s.getState().setMessages('sess-1', [baseMsg('m1')])
    s.getState().setMessages('sess-1', [{ ...baseMsg('m1'), content: '新内容' }])
    const got = (s.getState().messages['sess-1'] ?? [])[0]
    expect(got?.imageData).toBeUndefined()
    expect(got?.content).toBe('新内容')
  })
})
