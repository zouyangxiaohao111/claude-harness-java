import { api } from './rest'
import type { CreateTeamRequest, TeamDto, TeamMemberDto, TeammateMessageDto } from './types'

/**
 * Team 协作 REST API（/api/v1/teams · 对齐后端 TeamController）。
 * 错误统一抛 ApiError（409 已有团队/解散活跃未退 · 404 不存在 · 400 校验），
 * 调用方用 userMessage() 取友好文案。
 */
export const teamsApi = {
  list: () => api<TeamDto[]>('/teams'),
  get: (name: string) => api<TeamDto>(`/teams/${encodeURIComponent(name)}`),
  create: (req: CreateTeamRequest) => api<TeamDto>('/teams', { method: 'POST', body: req }),
  remove: (name: string, waitMs?: number) =>
    api<TeamDto>(`/teams/${encodeURIComponent(name)}${waitMs != null ? `?waitMs=${waitMs}` : ''}`, { method: 'DELETE' }),
  addMember: (name: string, member: TeamMemberDto) =>
    api<TeamDto>(`/teams/${encodeURIComponent(name)}/members`, { method: 'POST', body: member }),
  /** spawn 真实子代理成员（后端补端点：Agent 工具 input 带 name → 写 config + 跑进程） */
  spawnMember: (name: string, req: { name: string; subagentType?: string; prompt?: string }) =>
    api<TeamDto>(`/teams/${encodeURIComponent(name)}/members/spawn`, { method: 'POST', body: req }),
  removeMember: (name: string, agentId: string) =>
    api<TeamDto>(`/teams/${encodeURIComponent(name)}/members/${encodeURIComponent(agentId)}`, { method: 'DELETE' }),
  /** 停止成员任务（agentId = name@team · 404 不存在 / 409 非 running） */
  kill: (name: string, agentId: string) =>
    api<{ success: boolean }>(`/teams/${encodeURIComponent(name)}/members/${encodeURIComponent(agentId)}/kill`, { method: 'POST' }),
  inbox: (name: string) => api<TeammateMessageDto[]>(`/teams/${encodeURIComponent(name)}/inbox`),
  markRead: (name: string, from?: string) =>
    api<void>(`/teams/${encodeURIComponent(name)}/inbox/read`, {
      method: 'POST',
      body: from ? { from, read: true } : { read: true },
    }),
}
