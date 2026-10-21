/**
 * Workflow 运行 REST 端点封装
 * 对应 nexusai-backend WorkflowController
 */
import { api } from './rest'
import type { WorkflowRunDto } from './types'

export const workflowApi = {
  /**
   * run 列表（按 updatedAt 降序）。
   *
   * <p>必须带 sessionId：后端按该会话项目的 workflow 目录过滤。
   * 不传 → 进程默认目录（旧的跨项目串台行为），仅无会话场景使用。
   */
  listRuns: (sessionId?: string | null) =>
    api<WorkflowRunDto[]>(`/workflows/runs${sessionId ? `?sessionId=${encodeURIComponent(sessionId)}` : ''}`),
  /** 单个 run 详情（点开时调用 · 未命中 → 404） */
  getRun: (runId: string) =>
    api<WorkflowRunDto>(`/workflows/runs/${encodeURIComponent(runId)}`),
  /** 停止 workflow run（404 无该 run） */
  killRun: (runId: string) =>
    api<{ success: boolean }>(`/workflows/runs/${encodeURIComponent(runId)}/kill`, { method: 'POST' }),
}
