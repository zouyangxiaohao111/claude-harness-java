import type { ChatMessageDto } from '../api/types'

/**
 * [窗口 = 最近 N 轮] 轮判据的【唯一真源】。
 *
 * <p>WHY 单文件：MessageList 的渲染分组（components/center/MessageList.tsx 的 groups）与
 * chatStore 的窗口裁剪（stores/chatStore.ts 里各追加路径的 capTailTurns 调用）**必须用同一套判据** ——
 * 两处各写一份必然漂移，届时「渲染出来的轮」与「被裁剪的轮」不是同一个东西，切点会落在一轮中间。
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
 *   <li><b>有锚行</b>（{@code userMessageId} 非空）→ 取自身锚（行为不变）；</li>
 *   <li><b>无锚行</b>（compact boundary 行 / 本地追加的无锚行等）→ 取<b>最近前置有锚行</b>的键
 *       （{@code prevAnchorKey}）。⚠️ 不得自成一键：boundary 行被插在 messages 尾，自成一键会在渲染时
 *       排到本轮流式块<b>之后</b>（分割线错位到回复后面 —— [sm-boundary-reload] 修复的病灶）；</li>
 *   <li>前向<b>无任何锚</b>（窗口头部被裁 / 首行即无锚）→ 回落自身 {@code id}（自成一键，保窗口裁剪安全）。</li>
 * </ol>
 *
 * <p>⚠️ 调用方必须【按序扫描】，且只把<b>非空 {@code userMessageId}</b> 推进 {@code prevAnchorKey}
 * （无锚行不得把自己的键当成锚传给后面的行 —— 否则「无锚 → 自身 id」的兜底会级联成伪锚）。
 *
 * @param prevAnchorKey 前向扫描中最近一个非空 {@code userMessageId}（无 → null/undefined）
 */
export function turnKeyOf(m: ChatMessageDto, prevAnchorKey?: string | null): string {
  return m.userMessageId ?? prevAnchorKey ?? m.id ?? ''
}

/**
 * 保留【最近 maxTurns 个整轮】（LRU：新消息进 → 最老的一整轮出）。
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
      if (m.userMessageId) anchor = m.userMessageId   // ⚠️ 只被有锚行推进（无锚行不得污染锚）
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