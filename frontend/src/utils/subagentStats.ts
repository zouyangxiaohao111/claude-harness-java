import { compactNumber } from './format'

/**
 * 子代理统计的**唯一格式化单点**（D2 用量弹窗「子代理（本会话）」行 / D3 任务卡片统计行共用）。
 *
 * <p><b>数据源</b>：后端 {@code /topic/tasks} 的 {@code usage{total_tokens, tool_uses, duration_ms}}
 * （{@code SdkEventQueue.TaskUsage}）—— {@code task_progress} 带进度累计值（AgentProgressTracker
 * 逐 assistant 消息累加）、{@code task_notification} 带终态值。前端在 useChatSocket 里 camel 化后
 * 写进 subagentStore（{@code SubagentIdentity.usage}），本模块只负责展示口径。
 *
 * <p><b>为什么单点</b>：同一任务在两处显示必须是同一个数/同一种写法（否则用户对不上账）。
 *
 * <p>⛔ 缺项一律**不渲染**（不臆造 0）：字段缺省 = 该口径后端没上报（如 task_notification 的
 * {@code loopResult == null} 失败路径 usage 恒 0/缺），造 0 会让用户以为「真的一 token 没烧」。
 */
export interface SubagentUsageStat {
  /** 累计 token（task_progress = 进度累计 / task_notification = 终态值） */
  totalTokens?: number | null
  /** 工具调用次数 */
  toolUses?: number | null
  /** 耗时（ms） */
  durationMs?: number | null
}

/** 状态标签（与任务卡片既有文案一致 · 卡片状态徽标 / 弹窗行共用）。 */
export const SUBAGENT_STATUS_LABEL: Record<'running' | 'done' | 'failed' | 'stopped', string> = {
  running: '运行中',
  done: '已完成',
  failed: '失败',
  stopped: '已停止',
}

/**
 * 时长显示（ms → '39.5s' / '1m5s'）。
 *
 * <p>不足 1 分钟保一位小数（与任务书示例 '39.5s' 同形）；≥1 分钟走 {@code m s}（整数秒，秒用四舍五入
 * 后归一 —— 119.5s 显示 '2m0s' 而不是 '1m60s'）。
 */
export function formatDurationMs(ms: number): string {
  const totalSec = Math.round(ms / 1000)
  if (totalSec < 60) return `${(ms / 1000).toFixed(1)}s`
  const m = Math.floor(totalSec / 60)
  return `${m}m${totalSec % 60}s`
}

/**
 * 统计行文本：{@code '33.6k tokens · 5 次调用 · 39.5s'}（各段按可用性裁剪）。
 *
 * @param u 该任务的统计（null/undefined/全空 → null）
 * @return 统计行文本；**null = 无任何可用数据**（调用方显示「统计不可用」，不得渲染空串）
 */
export function formatSubagentStat(u: SubagentUsageStat | null | undefined): string | null {
  if (!u) return null
  const parts: string[] = []
  if (u.totalTokens != null) parts.push(`${compactNumber(u.totalTokens)} tokens`)
  if (u.toolUses != null) parts.push(`${u.toolUses} 次调用`)
  if (u.durationMs != null) parts.push(formatDurationMs(u.durationMs))
  return parts.length > 0 ? parts.join(' · ') : null
}
