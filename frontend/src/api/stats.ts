import { api } from './rest'
import type { StatsResponse } from './types'

/**
 * 用量统计 API（S3 · GET /api/v1/stats）
 * [V101] 后端读侧重构：数据源 = `llm_usage_calls` 明细表（唯一真源，聚合下推 SQL，无 rollup 表）。
 * 响应 = `range`（本次查询区间）+ `totals`（含成本分项/缓存节省/命中率 + LEGACY 历史行单列）+
 * `byDay`（按调用发生日）+ `byModel`（totalTokens/costYuan/分项/cacheHitRate）+ byCategory/byAgent。
 * 端点支持 `?from=&to=` 区间过滤（本页当前只取全量，`range` 供展示/对账）。
 * 失败抛 ApiError，由调用方（UsageCostModal「统计」标签页）自行容错。
 */
export const statsApi = {
  /** 全量用量统计 */
  get: () => api<StatsResponse>('/stats'),
}
