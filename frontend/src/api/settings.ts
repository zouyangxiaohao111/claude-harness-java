/**
 * Settings REST 端点封装
 * 对应 nexusai-backend SettingsController（GET/PUT /api/v1/settings）
 */
import { api } from './rest'
import type { AppSettings, ModelTierRole, UpdateSettingsRequest } from './types'

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
