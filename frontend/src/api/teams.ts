import { api } from './rest'
import type { CreateTeamRequest, TeamDto, TeamMemberDto, TeammateMessageDto } from './types'

/**
 * Team 协作 REST API（/api/v1/teams · 对齐后端 TeamController）。
 * 错误统一抛 ApiError（409 已有团队/解散活跃未退 · 404 不存在 · 400 校验），
 * 调用方用 userMessage() 取友好文案。
 */
export const teamsApi = {
  /** 列本会话的 team（后端按 leadSessionId 过滤，跨会话名册隔离）。sessionId 必填（批 3a：后端口 400）。 */
  list: (sessionId: string) =>
    api<TeamDto[]>(`/teams?sessionId=${encodeURIComponent(sessionId)}`),
  /** team 详情。sessionId 必填（批 3a）：后端按 leadSessionId 比对，不匹配 → 404。 */
  get: (name: string, sessionId: string) =>
    api<TeamDto>(`/teams/${encodeURIComponent(name)}?sessionId=${encodeURIComponent(sessionId)}`),
  /** 创建 team（sessionId 在 body 内，必填） */
  create: (req: CreateTeamRequest) => api<TeamDto>('/teams', { method: 'POST', body: req }),
  /** 解散 team。sessionId 必填（批 3a：旧实现缺值时反查 config 的 leadSessionId 兜底，已删）。 */
  remove: (name: string, sessionId: string, waitMs?: number) =>
    api<TeamDto>(
      `/teams/${encodeURIComponent(name)}?sessionId=${encodeURIComponent(sessionId)}`
        + `${waitMs != null ? `&waitMs=${waitMs}` : ''}`,
      { method: 'DELETE' }),
  addMember: (name: string, member: TeamMemberDto) =>
    api<TeamDto>(`/teams/${encodeURIComponent(name)}/members`, { method: 'POST', body: member }),
  /** spawn 真实子代理成员（后端补端点：Agent 工具 input 带 name → 写 config + 跑进程）。
   *  sessionId 必填（批 3a）：决定新成员 cwd（旧实现无会话 → user.dir 兜底 = 错项目）。 */
  spawnMember: (name: string, sessionId: string, req: { name: string; subagentType?: string; prompt?: string }) =>
    api<TeamDto>(`/teams/${encodeURIComponent(name)}/members/spawn?sessionId=${encodeURIComponent(sessionId)}`,
      { method: 'POST', body: req }),
  removeMember: (name: string, agentId: string) =>
    api<TeamDto>(`/teams/${encodeURIComponent(name)}/members/${encodeURIComponent(agentId)}`, { method: 'DELETE' }),
  /** 停止成员任务（agentId = name@team · 404 不存在 / 409 非 running） */
  kill: (name: string, agentId: string) =>
    api<{ success: boolean }>(`/teams/${encodeURIComponent(name)}/members/${encodeURIComponent(agentId)}/kill`, { method: 'POST' }),
  inbox: (name: string) => api<TeammateMessageDto[]>(`/teams/${encodeURIComponent(name)}/inbox`),
  // [C2 删除] teamsApi.markRead 已删除 —— 其对应的 POST /{teamName}/inbox/read 端点同批删除。
  //   展开收件箱不该把消息标已读（会把队友消息抢在模型之前吞掉）。标读改由消费侧
  //   AgentLoopContext.maybeInjectTeammateMailbox 在构建注入后按谓词执行。
}
