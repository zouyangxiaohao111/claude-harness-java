import { api } from './rest'
import type { TaskListSnapshotDto } from './types'

/**
 * 后端 BackgroundTaskDto（TaskFrameworkService 统一承载所有异步任务：
 * local_bash / local_agent / in_process_teammate / local_workflow / monitor_mcp）。
 * 内存态 · listTasks 即时拉取（后续转 DB 持久化）。
 */
export interface BackgroundTaskDto {
  id: string
  type: string            // local_bash | local_agent | in_process_teammate | local_workflow | monitor_mcp
  status: string          // pending | running | completed | failed | killed
  description: string
  toolUseId?: string | null
  startTime: number
  endTime?: number | null
  agentId?: string | null
  /** 是否已后台化（true=真异步后台任务 · false=前台同步任务如 Bash 工具卡已展示）· 后端 TaskDto 透出 */
  isBackgrounded?: boolean
}

export const tasksApi = {
  /** 会话级任务清单（sessionId 可选 · 只列当前会话任务） */
  list: (sessionId?: string) => api<BackgroundTaskDto[]>(`/tasks${sessionId ? `?sessionId=${sessionId}` : ''}`),
  /** 任务清单合并端点（TaskCreate V2 + TodoWrite V1 · V1/V2 互斥，前端按非空方显示）· GET /tasks/list?sessionId */
  listSnapshot: (sessionId: string) => api<TaskListSnapshotDto>(`/tasks/list?sessionId=${encodeURIComponent(sessionId)}`),
  /** 停止单任务（404 无该任务 / 409 非 running） */
  killTask: (taskId: string) => api<{ success: boolean }>(`/tasks/${encodeURIComponent(taskId)}/kill`, { method: 'POST' }),
  /** 前台任务转后台（对齐 CC Ctrl+B task:background · 后端 backgroundExistingForegroundTask） */
  background: (taskId: string) => api<{ success: boolean }>(`/tasks/${encodeURIComponent(taskId)}/background`, { method: 'POST' }),
  /** 前台任务全部转后台（对齐 CC Ctrl+B · 会话级可选 · 后端按类型自动分发） */
  backgroundAll: (sessionId?: string) =>
    api<{ success: boolean; backgrounded: number }>(
      `/tasks/background-all${sessionId ? `?sessionId=${encodeURIComponent(sessionId)}` : ''}`,
      { method: 'POST' }),
  /** 停止当前会话全部任务（会话级） */
  stopAllTasks: (sessionId: string) => api<{ success: boolean }>(`/tasks/stop-all?sessionId=${sessionId}`, { method: 'POST' }),
}
