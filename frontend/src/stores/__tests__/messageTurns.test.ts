import { describe, expect, it } from 'vitest'
import { capTailTurns, headAnchorKeyOf, isDialogueRow, isLiveDisplayRow, isNotificationInjectedUserRow, isSystemInjectedRow, isUserTurnRow, orderedTurnKeys, turnKeyByRowId, turnKeyOf } from '../messageTurns'
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

describe('[2026-11-02 用户实测「nexus 回复被切成多段」] 后台通知注入行不得算轮次起点', () => {
  /**
   * <b>WHY</b>：后端空闲路径把 `<task-notification>` XML 原文当普通 prompt 注入，该行
   * `isMeta=false` 且 `subtype=null` ⇒ 旧判据把它当**轮次起点** ⇒ 它后面的 assistant 段
   * 归属新轮 ⇒ 用户看到「一个回复被切成两段 + 第二个 nexus 头」。
   * 这里锁住「前端兜底判据」本身；后端置 isMeta 是另一条并行防线。
   */
  const NOTIF = '<task-notification>\n<task-id>bgvyngk2n</task-id>\n<status>completed</status>\n</task-notification>'

  it('isNotificationInjectedUserRow：按正文开头识别（含前导空白与发送层前缀）', () => {
    expect(isNotificationInjectedUserRow(msg('n1', { content: NOTIF }))).toBe(true)
    expect(isNotificationInjectedUserRow(msg('n2', { content: '\n  ' + NOTIF }))).toBe(true)
    expect(isNotificationInjectedUserRow(msg('n3', { content: 'A background agent completed a task:\n<task-id>x</task-id>' }))).toBe(true)
    // 对照组：普通提问 / assistant 行 / 空内容
    expect(isNotificationInjectedUserRow(msg('u1', { content: '帮我看看这个任务' }))).toBe(false)
    expect(isNotificationInjectedUserRow(msg('a1', { role: 'assistant', content: NOTIF }))).toBe(false)
    expect(isNotificationInjectedUserRow(msg('e1', { content: '' }))).toBe(false)
  })

  it('isUserTurnRow：通知行即使 isMeta=false / subtype=null 也不算轮次起点', () => {
    const notif = msg('n1', { content: NOTIF, isMeta: false, subtype: null })
    expect(isUserTurnRow(notif)).toBe(false)
    // 对照组：同一形态但正文是真实提问 ⇒ 仍是起点（证明判据不是「凡 user 行都拦」）
    expect(isUserTurnRow(msg('u1', { isMeta: false, subtype: null }))).toBe(true)
  })

  it('turnKeyOf 链路：通知行之后不产生新轮键', () => {
    const all = [
      msg('u1', { userMessageId: 'u1' }),
      msg('a1', { role: 'assistant', userMessageId: 'u1' }),
      msg('n1', { content: NOTIF, userMessageId: 'n1' }),
      msg('a2', { role: 'assistant', userMessageId: 'n1' }),
    ]
    // 前向扫描：只有 u1 开新轮；通知行 n1 不开轮 ⇒ a2 仍归 u1
    const keys = new Set(all.filter(isDialogueRow).map((m) => turnKeyOf(m, 'u1')))
    expect(keys.size).toBe(1)
    expect([...keys][0]).toBe('u1')
  })
})

describe('messageTurns 判据（与 MessageList 渲染分组同源）', () => {
  it('isDialogueRow：tool 行 / 纯 meta 行 / transcript-only user 行不进对话流（WHY：渲染与轮归属必须同一套判据）', () => {
    expect(isDialogueRow(msg('a'))).toBe(true)
    expect(isDialogueRow(msg('a', { role: 'tool' }))).toBe(false)
    expect(isDialogueRow(msg('a', { isMeta: true }))).toBe(false)
    expect(isDialogueRow(msg('a', { role: 'user', isVisibleInTranscriptOnly: true }))).toBe(false)
  })

  it('isDialogueRow：实时展示行 + 系统注入行放行（WHY：isMeta=true 但必须显示成系统提示）', () => {
    expect(isLiveDisplayRow(msg('a', { author: 'attachment', subtype: 'tool_use_summary' }))).toBe(true)
    expect(isDialogueRow(msg('a', { author: 'attachment', subtype: 'tool_use_summary', isMeta: true }))).toBe(true)
    expect(isDialogueRow(msg('a', { subtype: 'stop_hook_summary', isMeta: true }))).toBe(true)
    // [2026-11-02 用户裁定] 系统注入行（技能清单 / 外部改文件 / hook 回执）也要放行 ——
    //   它们不是用户提问，但必须在该轮「已完成」折叠区里显示为系统提示。
    //   ⛔ 不放行 = 主会话整条丢弃、用户永远看不到「系统给模型塞了什么」。
    expect(isDialogueRow(msg('a', { author: 'attachment', subtype: 'skill_listing', isMeta: true }))).toBe(true)
    expect(isDialogueRow(msg('a', { author: 'system', isMeta: true }))).toBe(true)
    // 对照组：isMeta 但既不是实时展示行、author 也不是 attachment/system ⇒ 仍跳过
    //   （防「只要 isMeta 就放行」的宽判据）
    expect(isDialogueRow(msg('a', { subtype: 'skill_listing', isMeta: true }))).toBe(false)
  })

  it('isSystemInjectedRow：历史 transcript 缺 isMeta 时按正文兜底（WHY：旧数据不修就永远是用户气泡）', () => {
    // 意图：子代理 transcript 在 2026-11-02 之前只写 subtype 不写 isMeta（实测
    //   `agent-*.jsonl` 里 `{"role":"user","subtype":"skill_listing","isMeta":null}`）。
    //   后端契约补齐后重启只对新写入生效 ⇒ 不补这道正文兜底，历史执行记录里的技能清单
    //   会永远渲染成用户气泡、并把子代理回复切成两段。
    expect(isSystemInjectedRow(msg('a', {
      author: null, isMeta: false,
      content: '<system-reminder>\nThe following skills are available for use with the Skill tool:\n\n- a: x\n</system-reminder>',
    }))).toBe(true)
    // 首尾空白也算（后端注入的 XML 前后常有换行）
    expect(isSystemInjectedRow(msg('a', { content: '  \n<system-reminder>\nfoo\n</system-reminder>\n ' }))).toBe(true)
    // 对照：普通用户提问不得误判
    expect(isSystemInjectedRow(msg('a', { content: '帮我看看这个仓库' }))).toBe(false)
    // 对照：正文中间出现（不是开头）不算 —— 用户可能就在讨论这个标签
    expect(isSystemInjectedRow(msg('a', { content: '这个 <system-reminder> 是什么意思' }))).toBe(false)
    // [2026-11-02 用户裁定「必须成对」] 只判开头不够：结尾对不上就不是一个完整注入块
    expect(isSystemInjectedRow(msg('a', { content: '<system-reminder> 是什么意思' }))).toBe(false)
    expect(isSystemInjectedRow(msg('a', { content: '<system-reminder>\nfoo' }))).toBe(false)     // 缺闭合
    expect(isSystemInjectedRow(msg('a', { content: 'foo\n</system-reminder>' }))).toBe(false)   // 缺开头
    // 且它同样不能当轮次起点（否则会把回复切成两段）
    expect(isUserTurnRow(msg('a', { content: '<system-reminder>\nx\n</system-reminder>' }))).toBe(false)
  })

  it('isSystemInjectedRow：识别系统注入行，且不把用户提问/compact 摘要/实时展示行算进去', () => {
    // WHY：它是「不是用户消息、但要显示为系统提示」的唯一定义。判错两类后果：
    //   ① 漏判 ⇒ 用户气泡（子代理视图里几 KB 技能清单被当成用户说的话）；
    //   ② 多判 ⇒ 把用户真打的字或 compact 摘要扔进折叠区（内容消失）。
    expect(isSystemInjectedRow(msg('a', { author: 'attachment', subtype: 'skill_listing', isMeta: true }))).toBe(true)
    expect(isSystemInjectedRow(msg('a', { author: 'attachment', subtype: 'edited_text_file', isMeta: true }))).toBe(true)
    expect(isSystemInjectedRow(msg('a', { author: 'system', isMeta: true }))).toBe(true)
    // 非 meta 的普通 user 提问 → 不是注入行
    expect(isSystemInjectedRow(msg('a', { author: 'attachment' }))).toBe(false)
    // compact 摘要 user 消息（isMeta 可能为 true）→ 有专属渲染，不算注入行
    expect(isSystemInjectedRow(msg('a', { author: 'attachment', isMeta: true, isCompactSummary: true }))).toBe(false)
    // 已有专属渲染的两类 live 行 → 不算注入行（否则重复渲染）
    expect(isSystemInjectedRow(msg('a', { author: 'attachment', subtype: 'tool_use_summary', isMeta: true }))).toBe(false)
    expect(isSystemInjectedRow(msg('a', { subtype: 'stop_hook_summary', isMeta: true }))).toBe(false)
    // assistant / tool 行永不是注入行
    expect(isSystemInjectedRow(msg('a', { role: 'assistant', author: 'attachment', isMeta: true }))).toBe(false)
  })

  it('headAnchorKeyOf：窗口头部无锚行归到之后第一个可定轮的行（WHY：否则头部自成一「轮」）', () => {
    // 意图（2026-11-02 用户实测「切会话/F5 后分割多个 nexus 头」+「用户消息之后锁不住历史」）：
    //   分页窗口的起点不一定落在提问行上 —— 实测 GET /messages/page 头部可能是注入行
    //   （isMeta、userMessageId=null）或上一轮被截断的 assistant 段。若不兜底，turnKeyOf 的
    //   「自身 id」档会让头部自成一「轮」：多一个 nexus 头，且 orderedTurnKeys 多一键
    //   ⇒ 渲染层「执行中只留最后 1 轮」算错 ⇒ 历史锁不住。
    const injection = msg('inj', {
      role: 'user', isMeta: true, author: 'attachment', subtype: 'edited_text_file',
      content: '<system-reminder>\nNote: x\n</system-reminder>', userMessageId: null,
    })
    const truncated = msg('ta', { role: 'assistant', userMessageId: 'u-old' })
    const ask = msg('q', { role: 'user', userMessageId: 'q' })
    // 头部行自身带 userMessageId → 本来就无需兜底（返回 null，由 turnKeyOf 第二档接管）
    expect(headAnchorKeyOf([truncated, ask])).toBeNull()
    // 头部是**无锚**注入行 → 归到它之后第一个能定轮的行
    expect(headAnchorKeyOf([injection, truncated, ask])).toBe('u-old')
    expect(headAnchorKeyOf([injection, ask])).toBe('q')
    // 头部直接是提问行 → 不需要兜底
    expect(headAnchorKeyOf([ask, truncated])).toBeNull()
    // 空数组 / 无锚 → null
    expect(headAnchorKeyOf([])).toBeNull()
    // 且 turnKeyByRowId 也据此把头部串进同一轮（不是自成一键）
    // 关键：无锚注入行不再用自身 id 当轮键（那会自成一「轮」⇒ 多个 nexus 头）
    const keys = turnKeyByRowId([injection, truncated, ask])
    expect(keys.get('inj')).toBe('u-old')
    expect(keys.get('ta')).toBe('u-old')
    expect(keys.get('q')).toBe('q')
    // 反面对照：修复前注入行会得到自身 id
    // 头部两行同一轮 + 真提问一轮 ⇒ 恰好两个轮键（修复前会是三个：注入行自成一键）
    expect(orderedTurnKeys([injection, truncated, ask])).toEqual(['u-old', 'q'])
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
