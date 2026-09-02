import { api } from './rest'
import type { StatsResponse } from './types'

/**
 * 用量统计 API（S3 · GET /api/v1/stats）
 * 后端 StatsController：全量聚合 totals + 按天 byDay + 按模型 byModel。
 * 失败抛 ApiError，由调用方（UsageCostModal「统计」标签页）自行容错。
 */
export const statsApi = {
  /** 全量用量统计 */
  get: () => api<StatsResponse>('/stats'),
}
