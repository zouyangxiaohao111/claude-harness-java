/**
 * Workflow 运行 REST 端点封装
 * 对应 nexusai-backend WorkflowController
 */
import { api } from './rest'
import type { WorkflowRunDto } from './types'

export const workflowApi = {
  /** 全部 run（挂载时调用 · 水合历史，按 updatedAt 降序） */
  listRuns: () => api<WorkflowRunDto[]>('/workflows/runs'),
  /** 单个 run 详情（点开时调用 · 未命中 → 404） */
  getRun: (runId: string) =>
    api<WorkflowRunDto>(`/workflows/runs/${encodeURIComponent(runId)}`),
  /** 停止 workflow run（404 无该 run） */
  killRun: (runId: string) =>
    api<{ success: boolean }>(`/workflows/runs/${encodeURIComponent(runId)}/kill`, { method: 'POST' }),
}
