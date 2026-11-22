/**
 * 工具卡状态判定 · 对齐 CC {@code UserToolResultMessage.tsx:40-104} 的分派顺序。
 *
 * <p><b>CC 真源分派顺序</b>：
 * <ol>
 *   <li>{@code content.startsWith(CANCEL_MESSAGE)} → 取消卡</li>
 *   <li>{@code content.startsWith(REJECT_MESSAGE)} 或 {@code content === INTERRUPT_MESSAGE_FOR_TOOL_USE}
 *       → 拒绝卡（拒绝内部再按前缀细分 Interrupted 等）</li>
 *   <li>{@code param.is_error} → 错误卡</li>
 *   <li>否则成功卡</li>
 * </ol>
 *
 * <p><b>WHY 单独成模块</b>：实时事件（{@code MessageToolResultEvent}）与 F5 重拉
 * （{@code GET /messages}）最终都填到同一个 {@code ToolCallDto}，本函数是两者的<b>唯一</b>判定入口，
 * 避免出现「实时是拒绝、重拉变失败」的两套逻辑。
 *
 * <p>前缀常量逐字取自后端 {@code PermissionRejectMessages}（REJECT/CANCEL）与
 * {@code LlmAgentLoop.INTERRUPT_MESSAGE_FOR_TOOL_USE}；⛔ 不要本地改写文案。
 */

/** 后端 {@code PermissionRejectMessages.CANCEL_MESSAGE} 的稳定前缀（权威源同名常量）。 */
export const CANCEL_MESSAGE_PREFIX = "The user doesn't want to take this action right now."

/** 后端 {@code PermissionRejectMessages.REJECT_MESSAGE} 的稳定前缀。 */
export const REJECT_MESSAGE_PREFIX = "The user doesn't want to proceed with this tool use."

/** 后端 {@code PermissionRejectMessages.SUBAGENT_REJECT_MESSAGE} 的稳定前缀（子代理拒绝）。 */
export const SUBAGENT_REJECT_MESSAGE_PREFIX = 'Permission for this tool use was denied.'

/** 后端 {@code LlmAgentLoop.INTERRUPT_MESSAGE_FOR_TOOL_USE}（逐字相等判定，对齐 CC）。 */
export const INTERRUPT_MESSAGE_FOR_TOOL_USE = '[Request interrupted by user for tool use]'

/** 工具卡状态类别（渲染层用；不是后端契约）。 */
export type ToolStatusKind = 'error' | 'done' | 'interrupted' | 'running' | 'cancelled' | 'rejected'

/** 判定输入 · 与 {@code ToolCallDto} 相关的三个字段（result / isError）+ 流式与问答态。 */
export interface ToolStatusInput {
  result: string | null | undefined
  isError: boolean | null | undefined
  /** live = 本卡属于仍在飞的流式轮。 */
  live?: boolean
  /** answered = AskUserQuestion 已回答但结果未回。 */
  answered?: boolean
}

/** 内容前缀是否为取消文案（CC 第 1 档）。 */
export function isCancelContent(content: string | null | undefined): boolean {
  return typeof content === 'string' && content.startsWith(CANCEL_MESSAGE_PREFIX)
}

/** 内容是否为拒绝/子代理拒绝文案（CC 第 2 档）。 */
export function isRejectContent(content: string | null | undefined): boolean {
  if (typeof content !== 'string') return false
  return content.startsWith(REJECT_MESSAGE_PREFIX) || content.startsWith(SUBAGENT_REJECT_MESSAGE_PREFIX)
}

/** 内容是否为工具中断文案（CC 第 2 档 · 逐字相等）。 */
export function isInterruptContent(content: string | null | undefined): boolean {
  return content === INTERRUPT_MESSAGE_FOR_TOOL_USE
}

/**
 * 工具卡状态判定（唯一入口）。
 *
 * <p>顺序严格对齐 CC：取消 → 拒绝 → 中断 → is_error → 成功/进行中。
 * 「中断」在 CC 里属于拒绝卡的细分；本仓两者可视状态已经区分（拒绝 = 用户拒绝执行，
 * 中断 = 用户停止当前 turn），因此各自独立成档，但仍先于 is_error 判定。
 */
export function classifyToolStatus(input: ToolStatusInput): ToolStatusKind {
  const { result, isError, live = false, answered = false } = input
  // ── CC 第 1 档：取消 ──
  if (isCancelContent(result)) return 'cancelled'
  // ── CC 第 2 档：拒绝（含子代理拒绝）──
  if (isRejectContent(result)) return 'rejected'
  // ── CC 第 2 档细分：工具中断 ──
  if (isInterruptContent(result)) return 'interrupted'
  // ── CC 第 3 档：显式错误 ──
  if (isError) return 'error'
  // ── 其余按有无结果/流式态区分（判据与旧实现一致）──
  const hasResult = result != null
  if (hasResult) return 'done'
  if (live || answered) return 'running'
  return 'interrupted'
}

/** 状态 → 中文标签（ICON/LABEL 同源，避免两处各写一份）。 */
export function toolStatusLabel(kind: ToolStatusKind, opts: { answered?: boolean } = {}): string {
  switch (kind) {
    case 'cancelled':
      return '已取消'
    case 'rejected':
      return '已拒绝'
    case 'interrupted':
      return '已中断'
    case 'error':
      return '失败'
    case 'done':
      return '已完成'
    case 'running':
    default:
      return opts.answered ? '已回答 · 等待结果' : '执行中'
  }
}

/** 状态 → 图标字符（与标签同源）。 */
export function toolStatusIcon(kind: ToolStatusKind): string {
  switch (kind) {
    case 'cancelled':
      return '⊘'
    case 'rejected':
      return '⊘'
    case 'interrupted':
      return '⊘'
    case 'error':
      return '✗'
    case 'done':
      return '✓'
    default:
      return '⊙'
  }
}
