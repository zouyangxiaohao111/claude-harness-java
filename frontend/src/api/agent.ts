import { api } from './rest'

const AGENT_BASE = 'http://localhost:3458/api/agent'

export const agentApi = {
  awaySummary: (sessionId: string) =>
    api<string>(`/away-summary?sessionId=${encodeURIComponent(sessionId)}`, { method: 'POST' }, AGENT_BASE),
}
