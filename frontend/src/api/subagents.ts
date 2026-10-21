import { api } from './rest'
import type { AgentTranscriptMessage } from './types'

/** 会话历史子代理摘要（GET /sessions/{sid}/subagents · 来自磁盘 transcript 目录） */
export interface SubagentSummary {
  /** transcript 文件名里的 id（a+16hex）· 与实时事件的 taskId 不是同一个键 */
  agentId: string
  /** agent 类型（meta.json，可能为空） */
  agentType?: string | null
  /** 任务描述（meta.json，可能为空） */
  description?: string | null
  /** 结局推断（completed / failed / unknown · 后端读 transcript 末行） */
  status?: string | null
  /** transcript 文件创建时间（epoch millis · 列表按它倒序；取不到为 null） */
  createdAt?: number | null
}

/** 子代理详情 API（transcript 完整执行记录 · GET /sessions/{sid}/subagents/{agentId}/transcript） */
export const subagentApi = {
  transcript: (sessionId: string, agentId: string) =>
    api<AgentTranscriptMessage[]>(`/sessions/${encodeURIComponent(sessionId)}/subagents/${encodeURIComponent(agentId)}/transcript`),
  /**
   * 会话的历史子代理列表 · 按 transcript 文件 mtime 倒序。
   *
   * <p>存在的理由：右栏原先只接 STOMP 实时事件 + `/tasks`（进程内后台任务，重启即丢），
   * 导致历史会话「子代理运行状况」恒空。磁盘上的 `subagents/agent-*.jsonl` 才是真源。
   */
  list: (sessionId: string, limit = 50) =>
    api<SubagentSummary[]>(`/sessions/${encodeURIComponent(sessionId)}/subagents?limit=${limit}`),
}
