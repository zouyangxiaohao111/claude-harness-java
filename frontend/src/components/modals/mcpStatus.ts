import type { McpRuntimeStatus, McpServer } from '@/api/types'

/** MCP 运行时状态徽标（连接器「自定义」列表与 MCP 面板共用同一套文案） */
export interface McpStatusBadge {
  cls: 'connected' | 'needs-auth' | 'failed'
  label: string
}

/** [V295] 运行时状态（后端现算，不落库），优先于 status */
export const RUNTIME_LABEL: Record<McpRuntimeStatus, McpStatusBadge> = {
  connected: { cls: 'connected', label: '已连接' },
  pending: { cls: 'needs-auth', label: '待审批' },
  failed: { cls: 'failed', label: '连接失败' },
  needsAuth: { cls: 'needs-auth', label: '待授权' },
  /** 凭据仍在但已失效 ⇒ 用户需重新授权 */
  reauth_required: { cls: 'needs-auth', label: '需重新授权' },
  disabled: { cls: 'needs-auth', label: '已停用' },
  stopped: { cls: 'needs-auth', label: '未连接' },
}

/** 老后端无 runtimeStatus 时按 status 回落 */
export const CONN_STATE: Record<string, McpStatusBadge> = {
  running: { cls: 'connected', label: '已连接' },
  stopped: { cls: 'needs-auth', label: '未连接' },
  error: { cls: 'failed', label: '连接失败' },
  pending: { cls: 'needs-auth', label: '待审批' },
  reauth_required: { cls: 'needs-auth', label: '需重新授权' },
}

/** server → 徽标（runtimeStatus 优先，缺省回落三态映射） */
export function statusBadgeOf(m: McpServer): McpStatusBadge {
  return m.runtimeStatus ? RUNTIME_LABEL[m.runtimeStatus] : (CONN_STATE[m.status] ?? CONN_STATE.stopped)
}
