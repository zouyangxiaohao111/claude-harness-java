/**
 * Hook REST 端点封装
 *
 * <p>对齐后端 {@code IndividualHookConfig} record（event/config/matcher/source/pluginName
 * 五字段，源码位于 nexusai-backend …/permission/hook/）。
 *
 * <p>端点：GET /api/v1/hooks（后端 HookController 已实现，合并 user/project/local/policy
 * + session + 插件 hook 供 UI 展示）。请求失败（后端未起/网络错）时 HookPanel 走显式错误态。
 */
import { api } from './rest'
import type { HookItem } from './types'

export const hooksApi = {
  /** GET /api/v1/hooks — 读取全部 hook 配置（后端 getAllHooks 合并多 source + 插件 hook） */
  getAllHooks: () => api<HookItem[]>('/hooks'),
}
