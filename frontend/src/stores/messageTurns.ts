import type { ChatMessageDto } from '../api/types'

/**
 * [轮判据] 轮属性判据的【唯一真源】。
 *
 * <p>WHY 单文件：MessageList 的渲染分组（components/center/MessageList.tsx 的 groups）、渲染层折叠
 * （同文件折叠行的 {@link orderedTurnKeys}）与通用工具 {@link capTailTurns} **必须用同一套判据** ——
 * 几处各写一份必然漂移，届时「渲染出来的轮」与「被折叠/裁剪的轮」不是同一个东西，切点会落在一轮中间。
 *
 * <p>⚠️ store 侧已<b>不再</b>用它裁剪：用户 2026-10-29 裁定「不应该挤掉而是折叠显示」⇒ chatStore 的
 * 追加路径只增不裁，默认窗口（最近 3 轮）由渲染层折叠行表达。
 *
 * <p>⚠️ `dropTextInBriefTurns`（utils/sendUserMessage.ts:146）是 MessageList 渲染前的展示变换，
 * 但它只把 assistant 的 content 置空、**不改条数也不改顺序**（走 messages.map）⇒ 轮键集与分组结果
 * 不受它影响，故本模块的判据**不必**先跑它（跑不跑结果相同）。
 */

/** isMeta = true 但属「实时展示行」的两种 subtype（tool_use_summary / stop_hook_summary）。 */
export function isLiveDisplayRow(m: ChatMessageDto): boolean {
  return (m.author === 'attachment' && m.subtype === 'tool_use_summary')
    || m.subtype === 'stop_hook_summary'
}

/** 非用户提问的 subtype（后台通知 / 调度 / 队列 / 离开摘要 / 子代理回执 / 压缩边界 / 实时展示行）：
 *  这些即使 role=user 也**不得**作为轮次起点 —— 否则后台通知会自成一轮，长过程里多出一个
 *  `N nexus` 头（用户裁定：后台通知不单独分轮，只按用户消息算轮次）。 */
const NON_USER_TURN_SUBTYPES = new Set<string>([
  'tool_use_summary', 'stop_hook_summary',
  'task_notification', 'task_started', 'task_progress',
  'scheduled_task_fire', 'cron', 'queue', 'away_summary',
  'compact_boundary', 'microcompact_boundary', 'snip_boundary', 'snip_marker',
  'subagent_result', 'subagent_receipt', 'subagent_start', 'subagent_stop',
])

/** 是否「**真正的用户提问行**」（= 轮次起点）：role=user、非 meta、非 system/attachment 作者、
 *  非 compact summary、非后台/系统 subtype。
 *
 *  <p>渲染分组（MessageList）与窗口裁剪（{@link capTailTurns}）**共用**此判据：用户裁定
 *  「只按用户消息算轮次」，后台通知 / cron / 队列 / 离开摘要 / 子代理回执都不开新轮。 */
export function isUserTurnRow(m: ChatMessageDto): boolean {
  if (m.role !== 'user') return false
  if (m.isMeta) return false
  if (m.author === 'system' || m.author === 'attachment') return false
  if (m.isCompactSummary === true) return false
  if (m.subtype != null && NON_USER_TURN_SUBTYPES.has(m.subtype)) return false
  return true
}

/** 该条是否参与「对话流」（渲染 + 轮归属）。判据与 MessageList groups 的三条 continue 逐条同源。 */
export function isDialogueRow(m: ChatMessageDto): boolean {
  if (m.role === 'tool') return false
  // [transcript-only] 仅 transcript 可见的 user 消息不进普通对话流（TraceView 不过滤，本模块也不涉及）
  if (m.role === 'user' && m.isVisibleInTranscriptOnly === true) return false
  // isMeta 默认跳过；tool_use_summary / stop_hook_summary 两个实时展示行放行
  if (m.isMeta && !isLiveDisplayRow(m)) return false
  return true
}

/**
 * 轮键：一条消息归属哪一轮。与 MessageList groups 的 push 键同源（cap 与渲染的【单一真源】）。
 *
 * <p>规则（三段）：
 * <ol>
 *   <li><b>有当前轮</b>（调用方传入 {@code prevTurnKey} = 最近一条**真实用户提问**的 id）→ 一律归它。
 *       ⚠️ 段自身的 {@code userMessageId} **不优先** —— 它可能指向后台/系统消息
 *       （task_notification / cron / 队列 / 子代理回执），让段自成一键就会多出一个 `N nexus` 头；</li>
 *   <li><b>无当前轮</b>（窗口头部无提问行）→ 用自身 {@code userMessageId}；</li>
 *   <li>两者皆无 → 回落自身 {@code id}（自成一键，保窗口裁剪安全）。</li>
 * </ol>
 *
 * <p>⚠️ 调用方必须【按序扫描】，并用 {@link isUserTurnRow} 推进 {@code prevTurnKey}
 * （只有真正的用户提问才是轮次起点；无锚行 / 后台行不得推进）。
 *
 * @param prevTurnKey 前向扫描中最近一条真实用户提问的 id（无 → null/undefined）
 */
export function turnKeyOf(m: ChatMessageDto, prevTurnKey?: string | null): string {
  return prevTurnKey ?? m.userMessageId ?? m.id ?? ''
}

/**
 * 按渲染口径给【每条对话行】算出它所属的轮键（与 MessageList groups 前向趟逐条同源）。
 *
 * <p>返回值是 Map（插入序 = 消息时间序），供 {@link orderedTurnKeys}（轮的出现顺序）与
 * 「某条消息属于哪一轮」（历史提问跳转需先展开目标所在轮）使用。
 * 非对话行（tool 行 / 纯 meta 行）不产生条目 —— 它们不参与轮分组。
 */
export function turnKeyByRowId(msgs: ChatMessageDto[]): Map<string, string> {
  const out = new Map<string, string>()
  let turnId: string | null = null
  let prevKey: string | null = null
  for (const m of msgs) {
    if (!isDialogueRow(m)) continue
    if (isUserTurnRow(m)) turnId = m.id
    const key = turnKeyOf(m, turnId ?? prevKey)
    prevKey = key
    out.set(m.id, key)
  }
  return out
}

/**
 * 已加载消息里的轮键，**按出现顺序**（= 时间序）去重。
 *
 * <p>渲染层折叠的判据：比「默认展开窗口」更早的**头部连续若干轮**折叠成一行。
 * 与 MessageList groups 的键序列同源（同一前向趟），因此「折叠的轮」与「渲染分组的轮」恒为同一批。
 */
export function orderedTurnKeys(msgs: ChatMessageDto[]): string[] {
  const keys: string[] = []
  const seen = new Set<string>()
  for (const key of turnKeyByRowId(msgs).values()) {
    if (seen.has(key)) continue
    seen.add(key)
    keys.push(key)
  }
  return keys
}

/**
 * 保留【最近 maxTurns 个整轮】（LRU：新消息进 → 最老的一整轮出）。
 *
 * <p>⚠️ 通用工具，<b>追加路径不得使用</b>：用户 2026-10-29 裁定「已加载的轮永远不因新消息而消失」
 *   ⇒ chatStore 已不再调用本函数（折叠由渲染层负责）。保留给「确需丢弃」的显式场景。
 *
 * <p>⚠️ **整轮保留** —— 绝不把一轮切半（user 还在而 assistant 已被裁），否则渲染出半截轮。
 * 归属规则：**每一行（含 tool 行 / 纯 meta 行）都属于「它最近的前置对话行」所在的那一轮**。
 *   · 因此非对话行不会自成一「轮」（不可见的占位不会顶掉真实轮），
 *   · 也不会无主残留（否则尾部不断追加的 meta 行永远不被裁 = 又一处无界增长）。
 * 头部若存在「其前不存在任何对话行」的行，视为无主，随更早的轮一起丢弃。
 *
 * @param msgs     按时间序的消息数组
 * @param maxTurns 保留的最大轮数（<= 0 视为不裁剪）
 * @returns 未超上限时**原样返回同一引用**（避免无谓重渲/重排）；超限时返回尾段切片
 */
export function capTailTurns<T extends ChatMessageDto>(msgs: T[], maxTurns: number): T[] {
  if (maxTurns <= 0 || msgs.length === 0) return msgs
  // ① 正向一趟：给每一行算出「它所属的轮键」—— 对话行取自己的键（有锚 = 自身锚；无锚 = 最近前置锚，
  //   前向无锚则自身 id · 见 turnKeyOf）；**非对话行（tool 行 / 纯 meta 行）沿用最近一个前置对话行的键**。
  //   这样它们既不会自成一「轮」（否则不可见的占位会顶掉真实轮），也不会无主残留（否则尾部不断追加的
  //   meta 行永远不被裁，又变成无界增长）。
  const rowKey: (string | null)[] = new Array(msgs.length).fill(null)
  let cur: string | null = null          // 最近前置【对话行】的键（非对话行沿用）
  let anchor: string | null = null       // 最近前置【有锚】对话行的 userMessageId（无锚行归组用）
  for (let i = 0; i < msgs.length; i++) {
    const m = msgs[i]!
    if (isDialogueRow(m)) {
      // [只按用户提问算轮次] 只有真正的用户提问行才开新轮（后台通知 / 压缩边界 / 实时展示行都不算）
      if (isUserTurnRow(m)) anchor = m.id
      cur = turnKeyOf(m, anchor)
    }
    rowKey[i] = cur
  }
  // ② 反向一趟：从尾部取最多 maxTurns 个不同的键 = 要保留的轮
  const keep = new Set<string>()
  for (let i = msgs.length - 1; i >= 0 && keep.size < maxTurns; i--) {
    const k = rowKey[i]
    if (k) keep.add(k)
  }
  // ③ 切点 = 第一行「所属轮仍在保留集内」的下标（往下走到碰到更早的轮为止）
  let cutAt = msgs.length
  for (let i = msgs.length - 1; i >= 0; i--) {
    const k = rowKey[i]
    if (k !== null && !keep.has(k)) break
    cutAt = i
  }
  // ④ 头部无主行（其前没有任何对话行）本就该随更早的轮一起丢弃
  while (cutAt < msgs.length && rowKey[cutAt] === null) cutAt++
  if (cutAt === 0 || cutAt >= msgs.length) return msgs
  return msgs.slice(cutAt)
}