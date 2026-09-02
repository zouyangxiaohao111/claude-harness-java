import { create } from 'zustand'
import type { TeamDto, TeammateMessageDto, TeammateMessageEvent } from '../api/types'

/**
 * Team 协作 store（TeamPanel + useChatSocket 共享）· 模块级 zustand。
 *
 * <p>leadSessionId 是 STOMP 订阅键（/topic/sessions/{leadSessionId}/team-* · 方案3 按 lead 会话推送）。
 * TeamPanel 挂载时从会话 teamContext.leadSessionId 提取后写入；useChatSocket 读取它决定
 * 是否订阅 teams topic，并在收到事件时回调本 store 的 action（消息追加 / 团队状态变更）。
 */
export interface TeamState {
  /** 当前会话的团队名（无 team 为 null） */
  teamName: string | null
  /** 创建团队的会话 id（STOMP 订阅键：/topic/sessions/{leadSessionId}/team-* · 从 SessionDto.teamContext.leadSessionId 取） */
  leadSessionId: string | null
  /** Agent Swarms 门控（features.agentSwarms · 设置页开关同步；false → TeamPanel 隐藏） */
  agentSwarms: boolean
  /** 团队详情（teamsApi.get 拉取 · 实时刷新） */
  team: TeamDto | null
  /** STOMP 实时 teammate 消息流（/topic/sessions/{leadSessionId}/team-messages） */
  messages: TeammateMessageEvent[]
  /** 未读计数（收件箱折叠角标） */
  unread: number
  /** 历史收件箱（teamsApi.inbox 拉取 · 展开收件箱时刷新） */
  inbox: TeammateMessageDto[] | null
  // actions
  setTeamName: (name: string | null, leadSessionId?: string | null) => void
  setAgentSwarms: (enabled: boolean) => void
  setTeam: (team: TeamDto | null) => void
  addMessage: (evt: TeammateMessageEvent) => void
  setInbox: (list: TeammateMessageDto[] | null) => void
  markAllRead: () => void
  /** 清空全部团队态（解散 / 会话无 team / 会话切换） */
  clear: () => void
}

export const useTeamStore = create<TeamState>()((set) => ({
  teamName: null,
  leadSessionId: null,
  agentSwarms: false,
  team: null,
  messages: [],
  unread: 0,
  inbox: null,
  setTeamName: (teamName, leadSessionId = null) => set((st) => {
    // 同团队（tab 切换/重挂载）保留消息流；切换团队则重置（新团队上下文）
    if (st.teamName === teamName && st.leadSessionId === leadSessionId) return st
    return { teamName, leadSessionId, messages: [], unread: 0, inbox: null }
  }),
  setAgentSwarms: (agentSwarms) => set({ agentSwarms }),
  setTeam: (team) => set((st) => {
    // 内容守卫：轮询刷新数据未变则不更新 store（避免订阅者 re-render → 展开态闪烁）
    if (JSON.stringify(st.team) === JSON.stringify(team)) return st
    return { team }
  }),
  addMessage: (evt) => set((st) => ({ messages: [...st.messages, evt], unread: st.unread + 1 })),
  setInbox: (inbox) => set({ inbox }),
  markAllRead: () => set({ unread: 0 }),
  clear: () => set({ teamName: null, leadSessionId: null, team: null, messages: [], unread: 0, inbox: null }),
}))
