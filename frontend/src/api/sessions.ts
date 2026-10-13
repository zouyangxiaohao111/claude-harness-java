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
}
