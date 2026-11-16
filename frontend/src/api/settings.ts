/**
 * Settings REST 端点封装
 * 对应 nexusai-backend SettingsController（GET/PUT /api/v1/settings）
 */
import { api } from './rest'
import type { AppSettings, ModelTierRole, UpdateSettingsRequest } from './types'

/**
 * 知识库入库流水线的六池并发配置 · `GET/ PUT /api/v1/settings/ingest-concurrency`。
 *
 * <p>字段名逐字照抄后端契约（⛔ 不要改成 snake_case / 别的名字，改了会静默读不到）：
 * 六个池 `parse` / `postprocess` / `enrichment` / `wiki` / `maintenance` / `shared`
 * 加每次调度认领上限 `maxTasksPerTick`。
 *
 * <p>`source` 是后端算好的**只读**来源标记，不是配置项：
 * <ul>
 *   <li>{@code db}：settings 表 `ingest_concurrency_config` 存过（用户自定义）；</li>
 *   <li>{@code yml}：未自定义，生效的是 `application.yml` 的
 *       `nexusai.knowledge.ingest.*` 默认值。</li>
 * </ul>
 */
export interface IngestConcurrencyConfig {
  parse: number
  postprocess: number
  enrichment: number
  wiki: number
  maintenance: number
  shared: number
  maxTasksPerTick: number
  source: 'db' | 'yml'
}

/** PUT 请求体：全部数值字段可选（未传的键保持现值），`source` 不可写。 */
export type IngestConcurrencyPatch = Partial<Omit<IngestConcurrencyConfig, 'source'>>

/** GET — 读取当前生效的入库并发配置（含来源标记）。 */
export function getIngestConcurrency(): Promise<IngestConcurrencyConfig> {
  return api<IngestConcurrencyConfig>('/settings/ingest-concurrency')
}

/**
 * PUT — 部分更新入库并发配置，返回**生效后**的完整配置（`source=db`）。
 *
 * <p>⚠️ 必须用返回值回填界面：它来自后端落库并热调池之后的同一份对象，
 * 是此刻真正生效的值；拿请求体当结果会在后端校验/回落时显示成与运行值不一致的数字。
 * 越界（每池 1~64 / 每轮 1~50）后端直接 400，错误信息带字段名。
 */
export function putIngestConcurrency(
  patch: IngestConcurrencyPatch,
): Promise<IngestConcurrencyConfig> {
  return api<IngestConcurrencyConfig>('/settings/ingest-concurrency', { method: 'PUT', body: patch })
}

/** [V295] MCP 启动预取全局总闸（`GET/PUT /settings/mcp-prefetch`）。source=db 用户设置过，yml = 系统默认。 */
export interface McpPrefetchConfig {
  enabled: boolean
  source: 'db' | 'yml'
}

/** GET — 读取总闸生效值 + 来源。 */
export function getMcpPrefetch(): Promise<McpPrefetchConfig> {
  return api<McpPrefetchConfig>('/settings/mcp-prefetch')
}

/** PUT — 写总闸（下一次应用启动生效；不热连）。 */
export function putMcpPrefetch(enabled: boolean): Promise<McpPrefetchConfig> {
  return api<McpPrefetchConfig>('/settings/mcp-prefetch', { method: 'PUT', body: { enabled } })
}

/**
 * [V298] LLM 出站线路（HTTP 代理）· `GET/PUT /api/v1/settings/http-proxy`。
 *
 * <p>⚠️ 这是 **LLM 专用**（ChatGPT 登录 + 对话）：WebSearch / WebFetch 走各自的
 * `settings.proxy`，两条通道互不影响 —— 改这里不会让搜索/抓网页跟着走代理。
 *
 * <p>`proxy = null` 表示未配置（出站回落环境变量/直连）。
 */
export interface HttpProxyConfig {
  proxy: string | null
}

/** GET — 读取当前 LLM 出站代理。 */
export function getHttpProxy(): Promise<HttpProxyConfig> {
  return api<HttpProxyConfig>('/settings/http-proxy')
}

/** PUT — 保存 LLM 出站代理；传 `null` / 空串 = 直连（清空配置），下一次请求即生效。 */
export function putHttpProxy(proxy: string | null): Promise<HttpProxyConfig> {
  return api<HttpProxyConfig>('/settings/http-proxy', { method: 'PUT', body: { proxy } })
}

/** 探测命中的本机代理（按延迟升序）。 */
export interface HttpProxyCandidate {
  port: number
  latencyMs: number
}

/**
 * GET — 探测本机常见代理端口。
 *
 * <p>判据不是「端口开着」，而是**真的经它取到一次 HTTPS 204** ⇒ SOCKS 端口、
 * 被别的服务占用的端口都会被排除。范围仅本机回环，不扫局域网。
 */
export function probeHttpProxy(): Promise<{ candidates: HttpProxyCandidate[] }> {
  return api<{ candidates: HttpProxyCandidate[] }>('/settings/http-proxy/probe')
}

/** 实测一条代理能否转发 HTTPS；`ok=false` 时 `error` 是可读短句。 */
export interface HttpProxyTestResult {
  ok: boolean
  latencyMs: number
  error: string | null
}

/** POST — 记住之前实测（失败不拦截保存，只提示）。 */
export function testHttpProxy(proxy: string): Promise<HttpProxyTestResult> {
  return api<HttpProxyTestResult>('/settings/http-proxy/test', { method: 'POST', body: { proxy } })
}

export const settingsApi = {
  /** GET /api/v1/settings — 读取全局设置 */
  get: () => api<AppSettings>('/settings'),

  /** PUT /api/v1/settings — 部分更新（后端 merge 策略：仅覆盖非 null 字段） */
  update: (req: UpdateSettingsRequest) =>
    api<AppSettings>('/settings', { method: 'PUT', body: req }),

  /**
   * DELETE /api/v1/settings/model-tiers/{role} — 解除某个档位与模型的绑定。
   *
   * <p><b>为什么不能靠 PUT 传 `null`</b>：后端 merge 契约是「`null` = 不覆盖」
   * ⇒ 传 `null` 会被当成「这次别动这个字段」，绑定**清不掉**。
   * 清空与覆盖语义相反，故走独立端点。
   *
   * <p>幂等：档位本就未配置时后端不写库，直接返回当前 DTO。
   */
  clearModelTier: (role: ModelTierRole) =>
    api<AppSettings>(`/settings/model-tiers/${role}`, { method: 'DELETE' }),

  /**
   * DELETE /api/v1/settings/model-tiers — 清除全部模型档位绑定（「一键清除所有已配置的模型」）。
   *
   * <p>返回清空后的 DTO；前端须经 `mergeSettings` 合回 state（⛔ 不要另写一份合并）。
   */
  clearAllModelTiers: () =>
    api<AppSettings>('/settings/model-tiers', { method: 'DELETE' }),
}
