import { api } from './rest'
import type { TodoSnapshotDto } from './types'

/**
 * Todo 清单 REST API（/api/v1/sessions/{sessionId}/todos · 对齐后端 TodoStatusController）。
 * 响应 TodoSnapshotDto（单会话：todoKey + todos 数组 + updatedAt + availableTodoKeys）。
 * 错误统一抛 ApiError，调用方用 userMessage() 取友好文案。
 */
export const todosApi = {
  /** 拉取当前会话 todo 清单（初始化/重连兜底） */
  get: (sessionId: string) => api<TodoSnapshotDto>(`/sessions/${encodeURIComponent(sessionId)}/todos`),
}
