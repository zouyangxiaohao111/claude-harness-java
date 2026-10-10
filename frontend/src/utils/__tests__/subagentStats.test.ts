import { describe, expect, it } from 'vitest'
import { SUBAGENT_STATUS_LABEL, formatDurationMs, formatSubagentStat } from '../subagentStats'

/**
 * 子代理统计行格式（D2 用量弹窗「子代理（本会话）」行 / D3 任务卡片统计行**共用**同一纯函数）。
 *
 * <h2>WHY（规则九 · 意图而非行为）</h2>
 * <p>两处展示必须数值口径一致（同一任务的 tokens/调用次数/时长在两处看起来必须是同一个数），
 * 所以只有一份格式化实现。数据源 = 后端 /topic/tasks 的 {@code usage{total_tokens, tool_uses,
 * duration_ms}}（task_progress 累计值 / task_notification 终态值）。
 *
 * <h2>RED（改坏哪条红）</h2>
 * <ul>
 *   <li>把缺项当 0 渲染（`u.totalTokens ?? 0`）→ 「缺项只显示有值的段」红（凭空造出没上报的数）；</li>
 *   <li>无任何字段时返回 ''（而不是 null）→ 「无任何数据 → null」红（调用方会把空串当有效行渲染，
 *       「统计不可用」永不出现）；</li>
 *   <li>时长改回裸毫秒 → 「39.5s / 1m5s」红（用户看不懂毫秒）。</li>
 * </ul>
 */
describe('formatSubagentStat · 子代理统计行（D2 弹窗行 / D3 卡片行共用）', () => {
  it('三项齐备：33.6k tokens · 5 次调用 · 39.5s（任务书示例口径）', () => {
    expect(formatSubagentStat({ totalTokens: 33600, toolUses: 5, durationMs: 39500 }))
      .toBe('33.6k tokens · 5 次调用 · 39.5s')
  })

  it('缺项只显示有值的段（不得臆造 0 —— 字段缺省 = 没上报该口径）', () => {
    expect(formatSubagentStat({ totalTokens: 1200 })).toBe('1.2k tokens')
    expect(formatSubagentStat({ toolUses: 0 })).toBe('0 次调用')
    expect(formatSubagentStat({ durationMs: 39500 })).toBe('39.5s')
  })

  it('无任何数据 → null（调用方据此显示「统计不可用」，不得渲染空串）', () => {
    expect(formatSubagentStat(null)).toBeNull()
    expect(formatSubagentStat(undefined)).toBeNull()
    expect(formatSubagentStat({})).toBeNull()
    expect(formatSubagentStat({ totalTokens: null, toolUses: null, durationMs: null })).toBeNull()
  })

  it('时长格式：<60s 一位小数；≥60s 走 m s（39.5s / 1m5s / 2m0s）', () => {
    expect(formatDurationMs(39500)).toBe('39.5s')
    expect(formatDurationMs(0)).toBe('0.0s')
    expect(formatDurationMs(65000)).toBe('1m5s')
    expect(formatDurationMs(119500)).toBe('2m0s')
  })

  it('状态标签与任务卡片既有文案一致（运行中/已完成/失败/已停止）', () => {
    expect(SUBAGENT_STATUS_LABEL).toEqual({
      running: '运行中', done: '已完成', failed: '失败', stopped: '已停止',
    })
  })
})
