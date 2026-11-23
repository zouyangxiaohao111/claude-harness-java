import { api } from './rest'
import { API_BASE } from './base'

/**
 * 连接器（技能市场「连接器」tab）REST 客户端。
 *
 * <p><b>契约来源</b>：`D:/tmp/octop-acp-plan.md` §1.1（冻结契约）——后端
 * `apis/market/ConnectorController.java`，前缀 `/api/market/connectors`。
 * ⛔ 字段名与路径前缀不要自行改；连接器视图类型住本文件，不动 `api/types.ts`
 * （那个文件有唯一属主）。
 *
 * <p>与 IM 渠道的 `im.ts` 同形：start 返回授权页地址 + state，前端轮询 poll；
 * ⛔ 不是 device code 流程（不要照搬 deviceCode 命名）。
 */
const CONNECTOR_BASE = API_BASE
const P = '/market/connectors'

/** 授权方式（后端枚举值，原样透传） */
export type ConnectorAuthType = 'oauth2' | 'apiKey' | 'personalToken' | 'customFields' | 'imapAppPassword'

/** 连接状态（后端枚举值；`reauth_required` = 凭据还在但已失效，需要重新授权） */
export type ConnectorState = 'unconnected' | 'pending' | 'connected' | 'error' | 'reauth_required'

/** 探测到的 MCP 工具（连接器 `/test` 的 tools[] 形状） */
export interface ConnectorTool {
  name: string
  description?: string | null
}

/** 连接器目录条目（GET /） */
export interface ConnectorDto {
  id: string
  /** 应用选择用的 key（`mcp__<key>__*` 工具前缀口径由后端裁定，前端只透传） */
  key: string
  name: string
  description?: string | null
  /** 授权方式（连接器列表只含后端 catalog 条目，恒有值） */
  authType: ConnectorAuthType
  category?: string | null
  scopes?: string[] | null
  connected: boolean
  status: ConnectorState
  account?: string | null
  /** token 过期时间（epoch millis；null = 未知/不过期） */
  expiresAt?: number | null
  /** 默认隐藏的条目（后端条目标记）· 前端不渲染 */
  hidden?: boolean
  /** 图标标识（catalog.icon → 本地打包图标；取不到退回首字母） */
  icon?: string | null
  /** 品牌色（卡片 hover 边框/底色、分类 chip 用它） */
  color?: string | null
  /** 目录阶段（available 等） */
  phase?: string | null
  /** 官方文档/获取凭据说明页（后端透出后才有；没有就不渲染入口） */
  docUrl?: string | null
  /** 授权提示（后端透出的扁平中文串；没有不伪造） */
  authHint?: string | null
  /**
   * 后端能力分档（**优先于按 authType 推导**）：
   * supported=可授权可用 / credentialOnly=只能存凭据 / unavailable=本轮不可用（如 gateway 未实现）。
   */
  supportTier?: 'supported' | 'credentialOnly' | 'unavailable' | null
  /** unavailable 时给用户的不可用原因（其余档为 null） */
  unavailableReason?: string | null
  /** 是否官方 MCP（后端下发后才显示「官方 MCP」徽标） */
  official?: boolean | null
  /**
   * 官方 MCP 的凭据注入方式（缺省时表单按 authType 回落到旧通用表单）：
   * bearer=Authorization: Bearer <token>；header=自定义请求头；
   * query=拼到 URL 查询参数；oauth2=走授权流。
   */
  authMode?: 'bearer' | 'header' | 'query' | 'oauth2' | null
  /** header 模式的请求头名（如 X-Lark-MCP-UAT / X-Tencent-Meeting-Token） */
  authHeaderName?: string | null
  /** 兼容后端另一种命名 */
  headerName?: string | null
  /** 后端声明的凭据字段（优先于 authMode 通用表单） */
  credentialFields?: Array<{
    key: string
    label?: string | null
    fieldType?: string | null
    placeholder?: string | null
    help?: string | null
    required?: boolean
    secret?: boolean
  }> | null
}

/** POST /{id}/start 响应（授权页地址 + 一次性 state） */
export interface ConnectorStartResponse {
  authorizeUrl: string
  state: string
  /** loopback 回调地址（展示 + 复制给平台后台配置） */
  redirectUri: string
  /** 轮询间隔（秒） */
  interval: number
  expiresIn: number
}

/** GET /{id}/poll?state= 响应 */
export interface ConnectorPollResponse {
  state: 'PENDING' | 'AUTHORIZED' | 'ERROR'
  message?: string | null
  account?: string | null
}

/** GET /{id}/status · POST /{id}/credentials 响应 */
export interface ConnectorStatusResponse {
  connected: boolean
  authType: ConnectorAuthType
  account?: string | null
  /** token 过期时间（epoch millis；null = 未知/不过期） */
  expiresAt?: number | null
  missingScopes?: string[] | null
}

/**
 * 连接测试结果（POST /{id}/test）
 *
 * <p>`ok=false` 也走 200，`message` 是本次真实探测结果（含 HTTP 状态码 / 协议版本 / 工具数等事实）。
 * ⛔ 不要用 GET 探活冒充「测试连接」。
 */
export interface ConnectorTestResponse {
  ok: boolean
  message: string
  latencyMs: number
  /**
   * 探测深度（后端判定，前端只按它出文案）：
   * - `mcp_initialize`：initialize + tools/list 成功；
   * - `connect_only`：能发起请求但 MCP 握手失败（含 401）；
   * - `none`：尚未连接。
   *
   * 缺省（老后端/未返回）时按 `ok` 回落。
   */
  probeLevel?: 'mcp_initialize' | 'connect_only' | 'none' | null
  /** 探测到的工具数（后端目前把工具数写在 message 里；有独立字段时才用它） */
  toolCount?: number | null
  /**
   * 探测到的工具清单（后端 tools/list 结果）· **两种形状都要能吃**：
   * 连接器返回 `{name,description}[]`，MCP `/test` 返回 `string[]`（只含工具名）。
   */
  tools?: Array<ConnectorTool | string> | null
}

/** DELETE /{id} 响应 */
export interface ConnectorRevokedResponse {
  revoked: boolean
}

export const connectorsApi = {
  /**
   * 连接器目录 · GET /api/market/connectors[?includeHidden=true]
   *
   * <p>默认后端只回可见条目；连接器页要展示全量 30 条（qcc/tencent-ardot 标 hidden 但要在页面上
   * 置灰展示 + 「内部/企业」徽标），所以前端默认 **includeHidden=true**。
   */
  list: (includeHidden = true) =>
    api<{ connectors: ConnectorDto[] }>(
      `${P}${includeHidden ? '?includeHidden=true' : ''}`, {}, CONNECTOR_BASE),
  /** 发起授权 · POST /{id}/start（返回授权页地址，由前端用系统浏览器打开） */
  start: (id: string) =>
    api<ConnectorStartResponse>(`${P}/${encodeURIComponent(id)}/start`, { method: 'POST' }, CONNECTOR_BASE),
  /** 轮询授权结果 · GET /{id}/poll?state= */
  poll: (id: string, state: string) =>
    api<ConnectorPollResponse>(
      `${P}/${encodeURIComponent(id)}/poll?state=${encodeURIComponent(state)}`, {}, CONNECTOR_BASE),
  /** 当前连接状态 · GET /{id}/status */
  status: (id: string) =>
    api<ConnectorStatusResponse>(`${P}/${encodeURIComponent(id)}/status`, {}, CONNECTOR_BASE),
  /**
   * 手动凭证 · POST /{id}/credentials
   *
   * <p>两类用途：① 非 oauth 类型（apiKey / personalToken / customFields / imapAppPassword）直接落凭证；
   * ② oauth2 的 `requiresManualClient` 场景（用户自备 clientId/clientSecret）。
   */
  bindCredentials: (id: string, authType: ConnectorAuthType, fields: Record<string, string>) =>
    api<ConnectorStatusResponse>(
      `${P}/${encodeURIComponent(id)}/credentials`,
      { method: 'POST', body: { authType, fields } },
      CONNECTOR_BASE),
  /** 测试连接 · POST /{id}/test（真实调用后端，message 为本次结果） */
  test: (id: string) =>
    api<ConnectorTestResponse>(`${P}/${encodeURIComponent(id)}/test`, { method: 'POST' }, CONNECTOR_BASE),
  /** 断开（吊销凭证）· DELETE /{id} */
  disconnect: (id: string) =>
    api<ConnectorRevokedResponse>(`${P}/${encodeURIComponent(id)}`, { method: 'DELETE' }, CONNECTOR_BASE),
}
