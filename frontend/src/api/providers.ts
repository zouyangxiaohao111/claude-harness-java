/**
 * Provider / Model REST 端点封装
 * 对应 nexusai-backend Phase 3 端点
 */
import { api } from './rest'
import type {
  Provider,
  ProviderType,
  CreateProviderRequest,
  UpdateProviderRequest,
  Model,
  CreateModelRequest,
  UpdateModelRequest,
  TestConnectionResponse,
  RemoteModelsResponse,
  OAuthAuthorizeResponse,
  OAuthCompleteResponse,
} from './types'

/**
 * 内置品牌状态（后端 `ProviderService.BuiltinStatus` · 只读）。
 *
 * <p>常驻左栏的 5 张内置卡据此渲染：`configured=false` → 点击打开新建表单并预填品牌参数；
 * `configured=true` → 点击切到已存在的 provider。⛔ 后端不下发任何凭据。
 */
export interface BuiltinConnection {
  id: string
  name: string
  modelCount: number
  enabled: boolean
  authMode?: string
}

export interface BuiltinProviderStatus {
  key: string
  /** 左栏分组：国际 / 国内 / 本地（后端 BuiltinProviderCatalog.group） */
  group: string
  name: string
  type: ProviderType
  baseUrl: string
  subtitle: string
  configured: boolean
  /** 命中到的 provider.id（未配置 → null；多条连接时为第一条） */
  providerId: string | null
  /** 命中到的 provider.name（未配置 → null） */
  providerName: string | null
  modelCount: number
  /** [V299] 该品牌下的全部连接（一个品牌可以有多条） */
  connections?: BuiltinConnection[]
}

export const providerApi = {
  // ---- Provider CRUD ----
  list: () => api<Provider[]>('/providers'),

  get: (id: string) => api<Provider>(`/providers/${encodeURIComponent(id)}`),

  create: (req: CreateProviderRequest) =>
    api<Provider>('/providers', { method: 'POST', body: req }),

  update: (id: string, req: UpdateProviderRequest) =>
    api<Provider>(`/providers/${encodeURIComponent(id)}`, { method: 'PATCH', body: req }),

  remove: (id: string) =>
    api<void>(`/providers/${encodeURIComponent(id)}`, { method: 'DELETE' }),

  test: (id: string) =>
    api<TestConnectionResponse>(`/providers/${encodeURIComponent(id)}/test`, { method: 'POST' }),

  // ---- OAuth（ChatGPT 订阅登录 · 仅 type=openai_response 可使用）----
  /** 取授权地址（后端生成 PKCE/state 并落库，前端只负责打开浏览器） */
  oauthAuthorize: (id: string) =>
    api<OAuthAuthorizeResponse>(`/providers/${encodeURIComponent(id)}/oauth/authorize`, { method: 'POST' }),

  /** 提交用户粘回的整条回调 URL（或 code）；失败 400 ⇒ 抛 ApiError，ok=false 时带 message */
  oauthComplete: (id: string, input: string) =>
    api<OAuthCompleteResponse>(`/providers/${encodeURIComponent(id)}/oauth/complete`,
      { method: 'POST', body: { input } }),

  /** 退出订阅登录（204 无 body） */
  oauthLogout: (id: string) =>
    api<void>(`/providers/${encodeURIComponent(id)}/oauth`, { method: 'DELETE' }),

  // ---- Claude（Anthropic）订阅登录 · 监听 127.0.0.1:53692 自动回调 ----
  /**
   * 取 Claude 授权地址（后端生成 PKCE，**state 就是 verifier**；前端只负责打开浏览器）。
   * 后端会同时启动 53692 回调监听，浏览器登录完直接落库。
   */
  oauthClaudeAuthorize: (id: string) =>
    api<OAuthAuthorizeResponse>(`/providers/${encodeURIComponent(id)}/oauth/claude/authorize`,
      { method: 'POST' }),

  /** 提交用户粘回的整条回调 URL（或 code）· 53692 被占用时的手动回退 */
  oauthClaudeComplete: (id: string, input: string) =>
    api<OAuthCompleteResponse>(`/providers/${encodeURIComponent(id)}/oauth/claude/complete`,
      { method: 'POST', body: { input } }),

  /** 退出 Claude 订阅登录（204 无 body） */
  oauthClaudeLogout: (id: string) =>
    api<void>(`/providers/${encodeURIComponent(id)}/oauth/claude`, { method: 'DELETE' }),

  /** 幂等创建 / 返回内置 Claude provider（未配置卡上点「登录 Claude 订阅」时先建条目） */
  ensureOfficialClaude: () =>
    api<Provider>('/providers/official/claude/ensure', { method: 'POST' }),

  // ---- 官方内置 Codex provider（方案 A：列表顶部固定卡片）----
  /**
   * 查官方 Codex provider。
   *
   * <p>未创建过后端回 **204 无 body** ⇒ `api()` 对空响应返回 undefined，这里归一化成 null
   * （调用方只关心「有没有」）。
   */
  officialCodex: () =>
    api<Provider | null>('/providers/official/codex').then((p) => p ?? null),

  /** 幂等创建 / 返回官方 Codex provider（不存在则建一条，已存在原样返回） */
  ensureOfficialCodex: () =>
    api<Provider>('/providers/official/codex/ensure', { method: 'POST' }),

  /**
   * 内置品牌目录 + 每个品牌是否已配置（只读 · 后端不写库、不建记录）。
   * 失败由调用方静默处理（不能让面板空白）。
   */
  listBuiltin: () => api<BuiltinProviderStatus[]>('/providers/builtin'),

  /**
   * 拉取提供商的**远端**模型清单（`GET {baseUrl}/models`）。
   *
   * <p>⚠️ 与 {@link providerApi.listModels} 语义不同：后者是**库里已配置**的模型（持久化结果），
   * 本方法是**提供商当前支持**的清单（外部事实，供导入用）。路径用 `/models/remote` 区分。
   */
  remoteModels: (id: string) =>
    api<RemoteModelsResponse>(`/providers/${encodeURIComponent(id)}/models/remote`),

  // ---- Model CRUD ----
  listModels: (providerId: string) =>
    api<Model[]>(`/providers/${encodeURIComponent(providerId)}/models`),

  createModel: (providerId: string, req: CreateModelRequest) =>
    api<Model>(`/providers/${encodeURIComponent(providerId)}/models`, { method: 'POST', body: req }),

  updateModel: (id: string, req: UpdateModelRequest) =>
    api<Model>(`/models/${encodeURIComponent(id)}`, { method: 'PATCH', body: req }),

  removeModel: (id: string) =>
    api<void>(`/models/${encodeURIComponent(id)}`, { method: 'DELETE' }),
}