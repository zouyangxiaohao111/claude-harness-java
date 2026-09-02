import { api } from './rest'
import type { AgentTranscriptMessage } from './types'

/** 子代理详情 API（transcript 完整执行记录 · GET /sessions/{sid}/subagents/{agentId}/transcript） */
export const subagentApi = {
  transcript: (sessionId: string, agentId: string) =>
    api<AgentTranscriptMessage[]>(`/sessions/${encodeURIComponent(sessionId)}/subagents/${encodeURIComponent(agentId)}/transcript`),
}
