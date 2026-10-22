import { api } from './rest'
import type { SessionDto, SessionCreateRequest, SessionUpdateRequest, SessionToolDto, SessionToolPatchRequest, SessionUsageResponse } from './types'

export const sessionApi = {
  list: () => api<SessionDto[]>('/sessions'),
  get: (id: string) => api<SessionDto>(`/sessions/${encodeURIComponent(id)}`),
  create: (req: SessionCreateRequest) => api<SessionDto>('/sessions', { method: 'POST', body: req }),
  update: (id: string, req: SessionUpdateRequest) =>
    api<SessionDto>(`/sessions/${encodeURIComponent(id)}`, { method: 'PATCH', body: req }),
  remove: (id: string) => api<void>(`/sessions/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  /** 会话工具列表（GET /sessions/{id}/tools · 被禁工具仍在列表，disabled=true 供恢复） */
  listTools: (id: string) => api<SessionToolDto[]>(`/sessions/${encodeURIComponent(id)}/tools`),
  /** 会话工具禁用/恢复（PATCH /sessions/{id}/tools/{toolName} · body { enabled }，非 toggle） */
  setToolEnabled: (id: string, toolName: string, enabled: boolean) =>
    api<SessionToolDto>(`/sessions/${encodeURIComponent(id)}/tools/${encodeURIComponent(toolName)}`, {
      method: 'PATCH',
      body: { enabled } satisfies SessionToolPatchRequest,
    }),
  /**
   * [usage-gateway] 会话级用量明细（GET /sessions/{id}/usage · 记账侧权威值）。
   * 供「用量与花费」弹窗「子代理（本会话）」行按 taskId 关联；老后端无该端点 → 抛错，
   * 调用方静默回退 task 事件统计（不得因此阻塞弹窗）。
   */
  usage: (id: string) => api<SessionUsageResponse>(`/sessions/${encodeURIComponent(id)}/usage`),
  /**
   * 会话运行时状态（GET /sessions/{id}/state）· 对齐 dsh「宿主在每次打开/切换会话时**重放**
   * 未回答的 pending question/approval」（stable rpcId replay）。
   *
   * <p>pendingPermissions 与 STOMP permission-requests 事件同形，前端可直接入队。
   * 切会话/重连时调用 —— 补齐「请求到达时该会话未订阅」而丢失的交互。
   */
  state: (id: string) =>
    api<{ sessionId: string; pendingPermissions: PermissionEventPayload[] | null }>(
      `/sessions/${encodeURIComponent(id)}/state`,
    ),
}

/** 会话状态里的待处理权限/提问（与 STOMP `/permission-requests` 事件同形）。 */
export interface PermissionEventPayload {
  requestId: string
  /** 工具调用 id（requestId 通常相同）· 回答后给对应工具卡打「已回答」标记 */
  toolUseId?: string | null
  toolName?: string
  toolInput?: unknown
  description?: string | null
  reason?: { type?: string; detail?: string; reason?: string } | null
  warning?: string | null
  suggestions?: unknown[] | null
  timestampMs?: number
  workerName?: string | null
  workerBadgeColor?: string | null
  displayInput?: unknown
}
