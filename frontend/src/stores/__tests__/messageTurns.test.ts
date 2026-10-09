import { describe, expect, it } from 'vitest'
import { capTailTurns, isDialogueRow, isLiveDisplayRow, turnKeyOf } from '../messageTurns'
import type { ChatMessageDto } from '../../api/types'

/** 测试用最小 ChatMessageDto。 */
function msg(id: string, patch: Partial<ChatMessageDto> = {}): ChatMessageDto {
  return {
    id, sessionId: 'sess-1', role: 'user', author: '你', content: `内容-${id}`,
    reasoning: null, toolCalls: null, finishReason: null, inputTokens: null, outputTokens: null,
    reasoningDurationMs: null, time: null, toolCallId: null, assistantMessageId: null, subtype: null,
    isMeta: false, isApiErrorMessage: false, apiError: null, error: null, errorDetails: null,
    matchedRule: null, ...patch,
  }
}

/** 造 n 轮（每轮 user + assistant · 两行都带 userMessageId=uid）。
 *  ⚠️ user 行必须带自身锚：wire 契约 = 「user 消息 userMessageId=自身 id」（ChatMessageDto javadoc /
 *  chatStore.appendMetaUser）。缺锚的 user 行在新 turnKeyOf 规则下会被归到【上一轮】（无锚 → 最近前置锚），
 *  测到的就不是真实轮形态了。 */
const seq = (n: number, prefix = 't'): ChatMessageDto[] =>
  Array.from({ length: n }, (_, i) => {
    const uid = `${prefix}${i}`
    return [msg(uid, { userMessageId: uid }), msg(`${uid}-a`, { role: 'assistant', userMessageId: uid })]
  }).flat()

describe('messageTurns 判据（与 MessageList 渲染分组同源）', () => {
  it('isDialogueRow：tool 行 / 纯 meta 行 / transcript-only user 行不进对话流（WHY：渲染与轮归属必须同一套判据）', () => {
    expect(isDialogueRow(msg('a'))).toBe(true)
    expect(isDialogueRow(msg('a', { role: 'tool' }))).toBe(false)
    expect(isDialogueRow(msg('a', { isMeta: true }))).toBe(false)
    expect(isDialogueRow(msg('a', { role: 'user', isVisibleInTranscriptOnly: true }))).toBe(false)
  })

  it('isDialogueRow：两个实时展示行放行（tool_use_summary / stop_hook_summary）（WHY：它们 isMeta=true 但必须显示）', () => {
    expect(isLiveDisplayRow(msg('a', { author: 'attachment', subtype: 'tool_use_summary' }))).toBe(true)
    expect(isDialogueRow(msg('a', { author: 'attachment', subtype: 'tool_use_summary', isMeta: true }))).toBe(true)
    expect(isDialogueRow(msg('a', { subtype: 'stop_hook_summary', isMeta: true }))).toBe(true)
    // 对照组：同样是 isMeta，但 author 不对 ⇒ 仍被跳过（防「只看 subtype」的宽判据）
    expect(isDialogueRow(msg('a', { author: 'system', subtype: 'tool_use_summary', isMeta: true }))).toBe(false)
  })

  it('turnKeyOf：有锚行取自身锚 / 无锚行取【最近前置锚】/ 前向无锚回落自身 id（WHY：与 MessageList 的 push 键同源，否则两处归轮不同）', () => {
    expect(turnKeyOf(msg('m1'))).toBe('m1')                            // 前向无锚 → 自身 id（兜底：自成一键）
    expect(turnKeyOf(msg('m1', { userMessageId: 'u9' }))).toBe('u9')   // 有锚 → 自身锚（行为不变）
    // [sm-boundary-reload] 无锚行（compact boundary 等）→ 最近前置锚（不得自成一键：
    //   否则被插在 messages 尾的 boundary 会渲染到本轮流式块【之后】= 分割线错位）
    expect(turnKeyOf(msg('b1', { role: 'system', subtype: 'compact_boundary' }), 'u9')).toBe('u9')
    expect(turnKeyOf(msg('b1', { role: 'system', subtype: 'compact_boundary' }), null)).toBe('b1')
  })
})

describe('messageTurns capTailTurns（窗口 = 最近 N 轮，整轮保留）', () => {
  it('未超上限 → 返回同一引用（WHY：引用稳定才不触发无谓重渲/重排）', () => {
    const all = seq(3)
    expect(capTailTurns(all, 50)).toBe(all)
    expect(capTailTurns(all, 3)).toBe(all)
  })

  it('超上限 → 保留最近 N 轮，最老的整轮被挤出（WHY：LRU，新消息进则最老一轮出）', () => {
    const all = seq(10)               // t0..t9 = 20 条
    const kept = capTailTurns(all, 4) // 保留 t6..t9
    expect(kept).toHaveLength(8)
    expect(kept[0].id).toBe('t6')
    expect(kept[kept.length - 1].id).toBe('t9-a')
    expect(kept.some((m) => m.id === 't5-a')).toBe(false)
  })

  it('⭐ 绝不把一轮切半：切点前的 assistant 不会「只留一半」（WHY：切半会渲染出「有问无答」的半截轮）', () => {
    const all = seq(10)
    const kept = capTailTurns(all, 1)
    expect(kept.map((m) => m.id)).toEqual(['t9', 't9-a'])
  })

  it('tool 行 / 纯 meta 行不占轮，且切点之前的噪声行随其所属旧轮一起丢弃（WHY：否则残留孤儿工具卡片）', () => {
    const all: ChatMessageDto[] = [
      msg('t0'),
      msg('t0-tool', { role: 'tool' }),
      msg('t0-meta', { isMeta: true }),
      msg('t0-a', { role: 'assistant', userMessageId: 't0' }),
      ...seq(3, 'x'),                       // x0..x2 三轮
      msg('orphan-tool', { role: 'tool' }), // 紧跟其后、属 x2 轮中间的噪声行
      msg('x2-a2', { role: 'assistant', userMessageId: 'x2' }),
    ]
    const kept = capTailTurns(all, 2)       // 保留最近 2 轮 = x1..x2
    expect(kept.some((m) => m.id === 't0-tool')).toBe(false)
    expect(kept.some((m) => m.id === 't0-meta')).toBe(false)
    expect(kept.some((m) => m.id === 'x0')).toBe(false)
    // x2 轮内的噪声行在其轮内 ⇒ 随轮保留（它是本轮的行，不是切点之前的孤儿）
    expect(kept.some((m) => m.id === 'orphan-tool')).toBe(true)
    const keptTurns = new Set(kept.filter((m) => isDialogueRow(m)).map((m) => turnKeyOf(m)))
    expect(keptTurns.size).toBe(2)
  })

  it('[sm-boundary-reload] 无锚 boundary 行随其所属轮保留/丢弃（不自成一键 · 不额外占轮）', () => {
    const all = [...seq(3), msg('b1', { role: 'system', subtype: 'compact_boundary' })]
    // 只留最后一轮：boundary 归组到 t2（最近前置锚）→ 随之保留，且不额外占一轮
    const kept = capTailTurns(all, 1)
    expect(kept.map((m) => m.id)).toEqual(['t2', 't2-a', 'b1'])
    // 3 轮（boundary 不占轮）⇒ 未超限 → 原样返回同一引用
    expect(capTailTurns(all, 3)).toBe(all)
  })

  it('全无对话行 / 空数组 / maxTurns<=0 → 原样返回（WHY：不制造无谓的新数组）', () => {
    const onlyNoise = [msg('a', { role: 'tool' }), msg('b', { isMeta: true })]
    expect(capTailTurns(onlyNoise, 1)).toBe(onlyNoise)
    const empty: ChatMessageDto[] = []
    expect(capTailTurns(empty, 1)).toBe(empty)
    const all = seq(10)
    expect(capTailTurns(all, 0)).toBe(all)
  })
})
