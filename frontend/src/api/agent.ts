import { api } from './rest'
import { API_BASE } from './base'
import type { AgentListItem } from './types'

const AGENT_BASE = `${API_BASE}/agent`
const AGENTS_BASE = API_BASE

export const agentApi = {
  // [2026-11-02] 后端该端点 `produces=TEXT_PLAIN_VALUE` ⇒ Accept 必须是 text/plain，
  //   否则 406（错误体是 HTML）→ 前端 JSON.parse 抛 Unexpected token。
  awaySummary: (sessionId: string) =>
    api<string>(`/away-summary?sessionId=${encodeURIComponent(sessionId)}`, { method: 'POST', accept: 'text/plain' }, AGENT_BASE),
  /** 会话可选专家 agent 列表（GET /agents/list · 后端 AgentsHandler · AgentListItem[]） */
  listAgents: (sessionId: string) =>
    api<AgentListItem[]>(`/agents/list?sessionId=${encodeURIComponent(sessionId)}`, {}, AGENTS_BASE),
}
