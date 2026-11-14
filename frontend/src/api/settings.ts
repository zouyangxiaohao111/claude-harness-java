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
