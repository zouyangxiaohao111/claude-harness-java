import { api } from './rest'
import { API_BASE } from './base'
import type { ExternalMarket, MarketConnector, MarketExpert, MarketSkill, MarketUseExpertResult, PluginInstallResult } from './types'

/** 技能市场 BASE：契约在 /api/market/* 下（与 agentApi /agents/list 同域，地址单一来源见 ./base） */
const MARKET_BASE = API_BASE

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
  /** 外部插件市场（自建 MinIO / 静态 HTTP）· GET /api/market/external?url= · 后端下载解析 marketplace.json */
  listExternal: (url: string) =>
    api<ExternalMarket>(`/market/external${buildQs({ url })}`, {}, MARKET_BASE),
  /** 安装外部市场插件 · POST /api/plugins/install（后端自动 reconcile 市场 + 安装链） */
  installPlugin: (body: { pluginId: string; marketplaceUrl: string; scope?: string }) =>
    api<PluginInstallResult>(`/plugins/install`, { method: 'POST', body }, MARKET_BASE),
  /** 已安装且启用的插件名列表 · GET /api/plugins/installed（「已安装」标记初始化） */
  listInstalled: () =>
    api<string[]>(`/plugins/installed`, {}, MARKET_BASE),
  /**
   * 已安装插件明细（含内容指纹）· GET /api/plugins/installed/detail
   * 用途：市场条目指纹 ≠ 本地指纹 ⇒ 即使名称、版本都相同也显示「更新」。
   */
  listInstalledDetails: () =>
    api<InstalledPluginDetail[]>(`/plugins/installed/detail`, {}, MARKET_BASE),
  /**
   * 安装进度 · GET /api/plugins/install/progress?pluginId=
   * 后端无记录或终态过期 → 204 空体（api() 返回 undefined），前端据此停止轮询。
   */
  getInstallProgress: (pluginId: string) =>
    api<InstallProgress | undefined>(
      `/plugins/install/progress${buildQs({ pluginId })}`, {}, MARKET_BASE),

  // ── 市场管理（设置页「市场管理」）────────────────────────────────────

  /** 市场清单（含每个插件的全部历史版本）· GET /api/market/manage/list */
  manageList: () => api<MarketManifest>(`/market/manage/list`, {}, MARKET_BASE),

  /** 上架本地目录 · POST /api/market/manage/publish（版本留空 = 自动 patch+1） */
  publishPlugin: (body: PublishPluginRequest) =>
    api<PublishPluginResult>(`/market/manage/publish`, { method: 'POST', body }, MARKET_BASE),

  /** 回滚到历史版本 · POST /api/market/manage/rollback（只改清单指向，不删包） */
  rollbackPlugin: (name: string, version: string) =>
    api<PublishPluginResult>(
      `/market/manage/rollback${buildQs({ name, version })}`, { method: 'POST' }, MARKET_BASE),
}

// ── 市场管理（上架 / 版本历史 / 回滚）────────────────────────────────────

/** 单个插件的某个历史版本 */
export interface MarketVersionEntry {
  version: string
  source: string
  fingerprint: string
  publishedAt: string
  size?: number
}

/** 市场清单条目（后端 MarketPublishService 维护） */
export interface MarketManagedPlugin {
  name: string
  displayName?: string
  description?: string
  /** 当前生效版本 */
  version?: string
  category?: string
  /** 当前生效版本的市场相对目录（如 skills/doc-writer/0.1.1） */
  source?: string
  /** 内容指纹 sha256:...· 用于判断「内容有没有变」 */
  fingerprint?: string
  updatedAt?: string
  /** 全部历史版本（不删包，可回滚） */
  versions?: MarketVersionEntry[]
}

/** 市场清单 */
export interface MarketManifest {
  name?: string
  version?: string
  plugins: MarketManagedPlugin[]
}

/** 上架请求 */
export interface PublishPluginRequest {
  sourcePath: string
  name?: string
  displayName?: string
  description?: string
  category?: 'skill' | 'expert' | 'connector'
  tags?: string[]
  /** 留空 → 自动 patch +1 */
  version?: string
}

/** 上架 / 回滚结果 */
export interface PublishPluginResult {
  status: 'ok' | 'unchanged' | 'failed'
  message: string
  name?: string
  version?: string
  source?: string
  fingerprint?: string
  size?: number
}

/** 已安装插件明细（后端 PluginInstallService.InstalledPluginDetail） */
export interface InstalledPluginDetail {
  name: string
  version?: string | null
  installPath?: string | null
  /** 本地安装目录的内容指纹；不可读时为空 */
  fingerprint?: string | null
}

/** 安装进度快照（后端 PluginInstallProgressTracker.Progress）· percent 0~100 */
export interface InstallProgress {
  pluginId: string
  /** PREPARING / DOWNLOADING / EXTRACTING / INSTALLING / REFRESHING / DONE / FAILED */
  stage: string
  percent: number
  message: string
  updatedAt: number
}
