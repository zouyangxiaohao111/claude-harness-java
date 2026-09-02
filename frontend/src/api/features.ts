/**
 * feature flags 查询端点封装
 * 对应 nexusai-backend GET /api/v1/features（away-summary 门控等）
 */
import { api } from './rest'

export interface FeatureFlags {
  AWAY_SUMMARY: boolean
  tengu_sedge_lantern: boolean
  /** Agent Swarms 门控（后端 features.agentSwarms · 默认 false；前端 TeamPanel 渲染前提之一） */
  agentSwarms: boolean
}

export const featuresApi = {
  get: () => api<FeatureFlags>('/features'),
}
