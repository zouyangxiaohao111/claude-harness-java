/**
 * Settings REST 端点封装
 * 对应 nexusai-backend SettingsController（GET/PUT /api/v1/settings）
 */
import { api } from './rest'
import type { AppSettings, UpdateSettingsRequest } from './types'

export const settingsApi = {
  /** GET /api/v1/settings — 读取全局设置 */
  get: () => api<AppSettings>('/settings'),

  /** PUT /api/v1/settings — 部分更新（后端 merge 策略：仅覆盖非 null 字段） */
  update: (req: UpdateSettingsRequest) =>
    api<AppSettings>('/settings', { method: 'PUT', body: req }),
}
