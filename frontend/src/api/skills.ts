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
  /**
   * 技能列表 · `sessionId` 可选（[TL-W1 P4] 后端已加 `?sessionId=` 查询参数）。
   *
   * <p>带 sessionId → 后端把会话注入 RequestContext，SkillRegistry 的 cwdSupplier 能在 REST 线程
   * 解析出**会话绑定项目** ⇒ 列表含该项目的 project 级技能/workflow 命令；不带 → 后端无会话上下文
   * （旧行为：绑定项目的条目在前端列表里消失）。
   */
  list: (sessionId?: string) =>
    api<Skill[]>(`/skills${sessionId ? `?sessionId=${encodeURIComponent(sessionId)}` : ''}`),
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
