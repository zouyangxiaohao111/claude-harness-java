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
  /**
   * 异步任务清单 · `sessionId` **必传**（批 3a：会话级调用方不应省）。
   *
   * <p>后端该端点 sessionId 仍为可选（(b) 类：全局「查看更多」视图语义，缺省会 WARN 并返回全部
   * 会话的任务）。但本封装只服务于**会话级**调用方（MessageList 找当前会话的前台任务、
   * AsyncTasksPanel 列当前会话任务），省值会让 A 会话看到 B 会话的任务 → 类型上要求必传。
   */
  list: (sessionId: string) => api<BackgroundTaskDto[]>(`/tasks?sessionId=${encodeURIComponent(sessionId)}`),
  /** 任务清单合并端点（TaskCreate V2 + TodoWrite V1 · V1/V2 互斥，前端按非空方显示）· GET /tasks/list?sessionId */
  listSnapshot: (sessionId: string) => api<TaskListSnapshotDto>(`/tasks/list?sessionId=${encodeURIComponent(sessionId)}`),
  /** 停止单任务（404 无该任务 / 409 非 running） */
  killTask: (taskId: string) => api<{ success: boolean }>(`/tasks/${encodeURIComponent(taskId)}/kill`, { method: 'POST' }),
  /** 前台任务转后台（对齐 CC Ctrl+B task:background · 后端 backgroundExistingForegroundTask） */
  background: (taskId: string) => api<{ success: boolean }>(`/tasks/${encodeURIComponent(taskId)}/background`, { method: 'POST' }),
  /** 前台任务全部转后台（对齐 CC Ctrl+B）。[批 3a] sessionId 改为**必传**：调用方（App Ctrl+B）
   *  的语义就是「当前会话」，省值会退化成「全部会话」（后端 (b) 类路径 → WARN + 全量）。 */
  backgroundAll: (sessionId: string) =>
    api<{ success: boolean; backgrounded: number }>(
      `/tasks/background-all?sessionId=${encodeURIComponent(sessionId)}`,
      { method: 'POST' }),
  /** 停止当前会话全部任务（会话级）。
   *  [stop-all 假成功修正] 后端 `success` 不再是常量 true —— `success = (failed == 0)`
   *  （TaskController.stopAll：NOT_RUNNING 幂等口径不算失败，只有真错才计入 failed）。
   *  前端据此分支文案，故响应类型必须带上 `failed`/`stopped`。 */
  stopAllTasks: (sessionId: string) =>
    api<{ success: boolean; stopped: number; failed: number }>(
      `/tasks/stop-all?sessionId=${sessionId}`, { method: 'POST' }),
}
