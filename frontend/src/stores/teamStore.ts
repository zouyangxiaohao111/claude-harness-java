import { create } from 'zustand'
import type { TeamDto, TeammateMessageDto, TeammateMessageEvent } from '../api/types'

/**
 * Team 协作 store（TeamPanel + useChatSocket 共享）· 模块级 zustand。
 *
 * <p>leadSessionId 是 STOMP 订阅键（/topic/sessions/{leadSessionId}/team-* · 方案3 按 lead 会话推送）。
 * TeamPanel 挂载时从会话 teamContext.leadSessionId 提取后写入；useChatSocket 读取它决定
 * 是否订阅 teams topic，并在收到事件时回调本 store 的 action（消息追加 / 团队状态变更）。
 *
 * <p><b>[2026-10-23 · 多会话并行隔离] 团队态按 lead 会话分桶</b>：原实现只有一个全局
 * {@code messages/unread/inbox} —— 两个会话各自有团队时，B 的 teammate 消息会追加进 A 面板
 * （且没有"当前会话"这层筛选可按）。对齐 dsh 的「状态一律实例级」（每个 Session 自带
 * queueMirror / projectionStore / pendingInteractions），这里以 leadSessionId 为键。
 * 切走再切回同一团队时消息流仍在（缓存），不再是「切会话即清空」。
 */

/** 单个 lead 会话的团队态切片。 */
export interface TeamSlice {
  /** 团队名（无 team 为 null） */
  teamName: string | null
  /** 团队详情（teamsApi.get 拉取 · 实时刷新） */
  team: TeamDto | null
  /** STOMP 实时 teammate 消息流（/topic/sessions/{leadSessionId}/team-messages） */
  messages: TeammateMessageEvent[]
  /** 未读计数（收件箱折叠角标） */
  unread: number
  /** 历史收件箱（teamsApi.inbox 拉取 · 展开收件箱时刷新） */
  inbox: TeammateMessageDto[] | null
}

/** 稳定的空切片（无团队的会话共用同一引用，避免每次渲染造新对象）。 */
export const EMPTY_TEAM_SLICE: TeamSlice = Object.freeze({
  teamName: null, team: null, messages: [], unread: 0, inbox: null,
}) as TeamSlice

export interface TeamState {
  /** 当前展示会话对应的 team 订阅键（TeamPanel 写入；useChatSocket 据此订阅 team topics） */
  leadSessionId: string | null
  /** Agent Swarms 门控（features.agentSwarms · 设置页开关同步；false → TeamPanel 隐藏） */
  agentSwarms: boolean
  /** leadSessionId → 该团队的完整状态（多会话并行各自独立） */
  byLead: Record<string, TeamSlice>
  // actions
  /** 声明当前展示会话的 team（name/lead 均 null = 该会话没有团队 → 只清指针，保留其它团队的缓存） */
  setTeamName: (name: string | null, leadSessionId?: string | null) => void
  setAgentSwarms: (enabled: boolean) => void
  setTeam: (leadSessionId: string | null, team: TeamDto | null) => void
  addMessage: (leadSessionId: string, evt: TeammateMessageEvent) => void
  setInbox: (leadSessionId: string | null, list: TeammateMessageDto[] | null) => void
  markAllRead: (leadSessionId?: string | null) => void
  /**
   * 清空团队态：
   * - 传 lead → 删该团队的桶（团队解散）；
   * - 不传 → 只把当前指针置空，**保留**各团队缓存（会话无 team / 切到非团队会话时用）。
   */
  clear: (leadSessionId?: string | null) => void
}

/** 取某个 lead 的切片；不存在 → 稳定空切片（选择器可直接返回，引用恒定）。 */
export function teamSliceOf(st: Pick<TeamState, 'byLead'>, leadSessionId: string | null | undefined): TeamSlice {
  if (!leadSessionId) return EMPTY_TEAM_SLICE
  return st.byLead[leadSessionId] ?? EMPTY_TEAM_SLICE
}

export const useTeamStore = create<TeamState>()((set) => ({
  leadSessionId: null,
  agentSwarms: false,
  byLead: {},
  setTeamName: (teamName, leadSessionId = null) => set((st) => {
    if (!teamName || !leadSessionId) return { leadSessionId: null }
    const prev = st.byLead[leadSessionId]
    // 同团队（tab 切换/重挂载/轮询刷新）保留消息流；换团队才重置该桶（新团队上下文）
    if (prev && prev.teamName === teamName) {
      return st.leadSessionId === leadSessionId ? st : { leadSessionId }
    }
    return {
      leadSessionId,
      byLead: { ...st.byLead, [leadSessionId]: { ...EMPTY_TEAM_SLICE, teamName } },
    }
  }),
  setAgentSwarms: (agentSwarms) => set({ agentSwarms }),
  setTeam: (leadSessionId, team) => set((st) => {
    if (!leadSessionId) return st
    const prev = teamSliceOf(st, leadSessionId)
    // 内容守卫：轮询刷新数据未变则不更新 store（避免订阅者 re-render → 展开态闪烁）
    if (JSON.stringify(prev.team) === JSON.stringify(team)) return st
    return { byLead: { ...st.byLead, [leadSessionId]: { ...prev, team } } }
  }),
  addMessage: (leadSessionId, evt) => set((st) => {
    if (!leadSessionId) return st
    const prev = teamSliceOf(st, leadSessionId)
    return {
      byLead: {
        ...st.byLead,
        [leadSessionId]: { ...prev, messages: [...prev.messages, evt], unread: prev.unread + 1 },
      },
    }
  }),
  setInbox: (leadSessionId, inbox) => set((st) => {
    if (!leadSessionId) return st
    return { byLead: { ...st.byLead, [leadSessionId]: { ...teamSliceOf(st, leadSessionId), inbox } } }
  }),
  markAllRead: (leadSessionId) => set((st) => {
    // 不传 → 把所有团队标已读（设置页/全局快捷键场景）；传 → 只标该团队
    if (!leadSessionId) {
      const byLead: Record<string, TeamSlice> = {}
      for (const [k, v] of Object.entries(st.byLead)) byLead[k] = { ...v, unread: 0 }
      return { byLead }
    }
    const prev = teamSliceOf(st, leadSessionId)
    return { byLead: { ...st.byLead, [leadSessionId]: { ...prev, unread: 0 } } }
  }),
  clear: (leadSessionId) => set((st) => {
    if (!leadSessionId) {
      return st.leadSessionId === null ? st : { leadSessionId: null }
    }
    const byLead = { ...st.byLead }
    delete byLead[leadSessionId]
    return { byLead, leadSessionId: st.leadSessionId === leadSessionId ? null : st.leadSessionId }
  }),
}))
