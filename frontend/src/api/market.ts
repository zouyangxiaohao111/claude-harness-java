import { api } from './rest'
import type { MarketConnector, MarketExpert, MarketSkill, MarketUseExpertResult } from './types'

/** 技能市场 BASE：契约在 /api/market/* 下（与 agentApi /agents/list 同域 http://localhost:3458/api） */
const MARKET_BASE = 'http://localhost:3458/api'

/** 组装查询串：过滤掉 undefined/空串的键（sessionId 可选传——市场源暂与会话无关，为将来个人化/鉴权预留） */
function buildQs(params: Record<string, string | number | undefined>): string {
  const entries = Object.entries(params).filter(([, v]) => v !== undefined && v !== '')
  if (entries.length === 0) return ''
  return `?${entries.map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`).join('&')}`
}

/** 技能市场 API（代理腾讯 workbuddy 市场 · 后端 MarketController）
 *  契约：GET /api/market/expert?page&page_size / GET /api/market/skill / GET /api/market/connector /
 *        POST /api/market/expert/{marketId}/use {sessionId}。 */
export const marketApi = {
  /** 远端专家列表（GET /api/market/expert · 分页拉取，浏览一页 50 条已足够） */
  listExperts: (sessionId?: string, page = 1, pageSize = 50) =>
    api<MarketExpert[]>(`/market/expert${buildQs({ page, page_size: pageSize, sessionId })}`, {}, MARKET_BASE),
  /** 远端技能列表（GET /api/market/skill · 后端支持分页，骨架取一页 200 拉全目录；前端聚合成 categories） */
  listSkills: (sessionId?: string, page = 1, pageSize = 200) =>
    api<MarketSkill[]>(`/market/skill${buildQs({ page, page_size: pageSize, sessionId })}`, {}, MARKET_BASE),
  /** 远端连接器列表（GET /api/market/connector · 同上分页取全） */
  listConnectors: (sessionId?: string, page = 1, pageSize = 200) =>
    api<MarketConnector[]>(`/market/connector${buildQs({ page, page_size: pageSize, sessionId })}`, {}, MARKET_BASE),
  /** 使用远端专家（POST /api/market/expert/{marketId}/use · 后端构造成本地 agent + 设会话 mainThreadAgent） */
  useExpert: (sessionId: string, marketId: string) =>
    api<MarketUseExpertResult>(`/market/expert/${encodeURIComponent(marketId)}/use`, { method: 'POST', body: { sessionId } }, MARKET_BASE),
}
