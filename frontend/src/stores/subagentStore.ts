import { create } from 'zustand'
import { subagentColor } from '../api/types'

/**
 * 子代理身份 + 活动历史 store（FNT-SUB-04/SUB-10）· 模块级 zustand · 会话级分区 + localStorage 持久化。
 *
 * <p>WHY：后端 ChatMessageDto.author 恒 null（SubagentExecutor 不落子代理名），
 * 前端无法按消息 author 区分子代理。但后端经 /topic/tasks 推 task_started 事件
 * （含 task_id/tool_use_id/description/task_type），前端据此建立
 * <b>tool_use_id / task_id → 显示名 + 颜色</b>的映射，供消息渲染时按子代理身份显示
 * 「● @agentName」带色（对齐 CC AttachmentMessage.tsx:466-479）。
 *
 * <p><b>会话级</b>：身份按 sessionId 分区（bySession）——每个会话的子代理活动历史独立，
 * 切会话不串扰；localStorage 持久化（键 nexusai-subagents），刷新后按会话恢复。
 *
 * <p><b>join key</b>：消息的 {@link ChatMessageDto.toolCallId} ↔ task 事件的
 * {@link TaskStartedEvent.tool_use_id}（后端实测两者同源）。author 缺失时用
 * subagentColor 按名兜底取色。
 */

/** 单条子代理活动（时间线项） */
export interface SubagentActivity {
  type: 'start' | 'progress' | 'done' | 'failed' | 'stopped'
  text: string
  toolName?: string | null
  ts: number
}

export interface SubagentIdentity {
  /** 显示名（优先 description，其次 task_type） */
  name: string
  /** 颜色（subagentColor 按名稳定取色） */
  color: string
  /** 原始 task_type（如 local_bash） */
  taskType?: string | null
  /** 关联 taskId（addActivity/状态更新定位用） */
  taskId?: string
  /** 运行态（running 默认；终态由 addActivity 更新） */
  status: 'running' | 'done' | 'failed' | 'stopped'
  /** 当前执行工具（task_progress.last_tool_name） */
  currentTool?: string | null
  /** 活动时间线（启动 → 进度 → 终态） */
  activities: SubagentActivity[]
}

/** 会话 → 键 → 身份（键 = tool_use_id / task_id） */
export type SubagentBySession = Record<string, Record<string, SubagentIdentity>>

const STORAGE_KEY = 'nexusai-subagents'

/** 从 localStorage 加载（损坏/不可用 → 空 map） */
function loadSaved(): SubagentBySession {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? (JSON.parse(raw) as SubagentBySession) : {}
  } catch { return {} }
}

export interface SubagentState {
  /** 当前会话 id（setSession 设置 · register/addActivity/resolve 缺省基于它） */
  sessionId: string | null
  /** 会话级身份映射（sessionId → 键 → 身份） */
  bySession: SubagentBySession
  /** 切换当前会话（App/useChatSocket 会话变化时调用；不删历史） */
  setSession: (id: string | null) => void
  /** task_started 事件登记子代理身份（toolUseId 为主 join key，taskId 兜底）+ 初始化活动时间线 */
  register: (toolUseId: string | null, taskId: string, name: string, taskType?: string | null, sessionId?: string) => void
  /** 追加活动（progress/终态），更新 status/currentTool */
  addActivity: (taskId: string, activity: SubagentActivity, sessionId?: string) => void
  /** 任务终态移除身份（按 taskId；同时清 toolUseId 键） */
  forget: (taskId: string, sessionId?: string) => void
  /** 按键（tool_use_id / task_id / 显示名）精确查身份（按会话） */
  resolve: (key: string | null | undefined, sessionId?: string) => SubagentIdentity | null
}

export const useSubagentStore = create<SubagentState>()((set, get) => {
  /** 持久化当前 bySession（best-effort） */
  const persist = (bySession: SubagentBySession) => {
    try { localStorage.setItem(STORAGE_KEY, JSON.stringify(bySession)) } catch { /* 配额/隐私模式忽略 */ }
  }
  return {
    sessionId: null,
    bySession: loadSaved(),
    setSession: (id) => set({ sessionId: id }),
    register: (toolUseId, taskId, name, taskType, sessionId) => {
      const sid = sessionId ?? get().sessionId ?? ''
      const identity: SubagentIdentity = {
        name, color: subagentColor(name), taskType: taskType ?? null,
        taskId, status: 'running', currentTool: null,
        activities: [{ type: 'start', text: name, ts: Date.now() }],
      }
      set((st) => {
        const session = { ...(st.bySession[sid] ?? {}) }
        const next = { ...session, [taskId]: identity }
        if (toolUseId && toolUseId !== taskId) next[toolUseId] = identity
        const bySession = { ...st.bySession, [sid]: next }
        persist(bySession)
        return { bySession }
      })
    },
    addActivity: (taskId, activity, sessionId) => {
      const sid = sessionId ?? get().sessionId ?? ''
      set((st) => {
        const session = st.bySession[sid] ?? {}
        const identity = session[taskId] ?? Object.values(session).find((i) => i.taskId === taskId)
        if (!identity) return st
        // 终态幂等：已 done/failed/stopped 再收到终态活动（STOMP 终态事件 + REST 兜底补录
        //   双路径可能各推一次）→ 忽略，防止活动时间线重复追加、状态抖动。
        if ((identity.status === 'done' || identity.status === 'failed' || identity.status === 'stopped')
            && (activity.type === 'done' || activity.type === 'failed' || activity.type === 'stopped')) {
          return st
        }
        const updated: SubagentIdentity = {
          ...identity,
          activities: [...identity.activities, activity],
          status: activity.type === 'progress' ? identity.status
            : activity.type === 'done' ? 'done'
            : activity.type === 'failed' ? 'failed'
            : activity.type === 'stopped' ? 'stopped' : identity.status,
          currentTool: activity.toolName ?? identity.currentTool,
        }
        // 同步更新所有指向该 identity 的别名键（toolUseId / taskId）
        const nextSession = { ...session }
        for (const [k, v] of Object.entries(nextSession)) {
          if (v === identity || k === taskId) nextSession[k] = updated
        }
        const bySession = { ...st.bySession, [sid]: nextSession }
        persist(bySession)
        return { bySession }
      })
    },
    forget: (taskId, sessionId) => {
      const sid = sessionId ?? get().sessionId ?? ''
      set((st) => {
        const session = st.bySession[sid]
        if (!session || !session[taskId]) return st
        const dropped = session[taskId]
        const nextSession = { ...session }
        delete nextSession[taskId]
        for (const [k, v] of Object.entries(nextSession)) {
          if (v === dropped) delete nextSession[k]
        }
        const bySession = { ...st.bySession, [sid]: nextSession }
        persist(bySession)
        return { bySession }
      })
    },
    resolve: (key, sessionId) => {
      if (!key) return null
      const sid = sessionId ?? get().sessionId
      const session = sid ? get().bySession[sid] : undefined
      if (!session) return null
      // 精确键命中（tool_use_id / task_id）
      if (session[key]) return session[key]
      // 按显示名精确命中（author 字段可能是子代理名）
      return Object.values(session).find((id) => id.name === key) ?? null
    },
  }
})
