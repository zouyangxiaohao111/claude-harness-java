import { api } from './rest'
import type { AgentListItem } from './types'

const AGENT_BASE = 'http://localhost:3458/api/agent'
const AGENTS_BASE = 'http://localhost:3458/api'

export const agentApi = {
  awaySummary: (sessionId: string) =>
    api<string>(`/away-summary?sessionId=${encodeURIComponent(sessionId)}`, { method: 'POST' }, AGENT_BASE),
  /** 会话可选专家 agent 列表（GET /agents/list · 后端 AgentsHandler · AgentListItem[]） */
  listAgents: (sessionId: string) =>
    api<AgentListItem[]>(`/agents/list?sessionId=${encodeURIComponent(sessionId)}`, {}, AGENTS_BASE),
}
