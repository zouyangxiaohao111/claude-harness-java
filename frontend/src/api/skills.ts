/**
 * Skill REST 端点封装
 * 对应 nexusai-backend Phase C1 端点
 */
import { api } from './rest'
import type {
  Skill,
  CreateSkillRequest,
  UpdateSkillRequest,
  SkillImprovementSuggestion,
  SkillImprovementDecisionResponse,
} from './types'

export const skillApi = {
  list: () => api<Skill[]>('/skills'),
  create: (req: CreateSkillRequest) => api<Skill>('/skills', { method: 'POST', body: req }),
  update: (id: string, req: UpdateSkillRequest) =>
    api<Skill>(`/skills/${encodeURIComponent(id)}`, { method: 'PATCH', body: req }),
  remove: (id: string) =>
    api<void>(`/skills/${encodeURIComponent(id)}`, { method: 'DELETE' }),
}

/**
 * Skill Improvement 决策端点（FNT-DC-01/FE-10）· 对齐后端 SkillImprovementController：
 * GET /api/v1/skill-improvement/suggestion（无待定 suggestion → 204）+ POST /decision。
 * sessionId 接受 "sess-xxx" 或合规 UUID（后端 parseSessionUuid 归一化到同一 store 键）。
 */
export const skillImprovementApi = {
  getSuggestion: (sessionId: string) =>
    api<SkillImprovementSuggestion>(
      `/skill-improvement/suggestion?sessionId=${encodeURIComponent(sessionId)}`,
    ),
  postDecision: (sessionId: string, applied: boolean) =>
    api<SkillImprovementDecisionResponse>(
      '/skill-improvement/decision',
      { method: 'POST', body: { sessionId, applied } },
    ),
}
