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
  /**
   * GET /api/v1/hooks?sessionId= — 读取全部 hook 配置（后端 getAllHooks 合并多 source + 插件 hook）。
   *
   * <p><b>sessionId 必填（批 3a）</b>：后端已去掉 MDC 兜底 —— 缺值直接 400。后端非空时走
   * settings + session 合并（SESSION_HOOK 源），故**必须**带当前会话；调用方无会话时不得调用
   * 本接口（显式错误态，而非静默返回 settings-only 列表）。
   */
  getAllHooks: (sessionId: string) =>
    api<HookItem[]>(`/hooks?sessionId=${encodeURIComponent(sessionId)}`),
}
