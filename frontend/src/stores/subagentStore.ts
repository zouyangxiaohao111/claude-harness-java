import { create } from 'zustand'
import { subagentColor } from '../api/types'
import type { SubagentUsageStat } from '../utils/subagentStats'

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
  /**
   * [D2/D3] 最近一次 task 事件的统计（{@code task_progress.usage} 进度累计值 /
   * {@code task_notification.usage} 终态值）· 缺省 null = 尚无数据（展示侧显示「统计不可用」）。
   * 只存最近一条（后端每次给的都是最新值，前端不累加 —— 累加会与后端口径打架）。
   */
  usage?: SubagentUsageStat | null
  /**
   * [R3-3] 冷启动从 localStorage 恢复且**尚无本轮实时事件**佐证的身份（仅 running 会被标）：
   * 卡片因此<b>不启用本地实时计时</b> —— 否则一个早已结束、只是没收到终态事件的残留任务会显示
   * 「已运行 N 小时」的假时长（旧「运行中」虚高问题的形态之一）。任一实时事件（register /
   * addActivity / setUsage）到达即清标志。
   */
  restored?: boolean
}

/** 会话 → 键 → 身份（键 = tool_use_id / task_id） */
export type SubagentBySession = Record<string, Record<string, SubagentIdentity>>

const STORAGE_KEY = 'nexusai-subagents'

/**
 * 从 localStorage 加载（损坏/不可用 → 空 map）。
 *
 * <p><b>[F1] 按 taskId 归组重别名（承重）</b>：内存态里同一身份对象被挂 `taskId` / `toolUseId`
 * 两个键（见 {@link SubagentState.register}），但持久化是 {@code JSON.stringify} —— JSON 无引用语义
 * ⇒ 同一身份被写成<b>两份内容相同的副本</b>，`parse` 回来是<b>两个不同对象</b>。若不重别名：
 * <ul>
 *   <li>{@link subagentListOfSession} 的按对象去重失效 ⇒ 用量弹窗「子代理（本会话）」同一 @名字
 *       <b>出现两行</b>（且 key 相同），且永不消失；</li>
 *   <li>{@link SubagentState.addActivity} / {@link SubagentState.setUsage} 的别名同步靠<b>对象身份</b>
 *       （{@code v === identity}）⇒ 两键分叉后只有 `taskId` 键被更新，`toolUseId` 键<b>永停旧对象</b>
 *       （消息侧按 toolCallId 查身份的 {@code resolve} 也读陈旧副本）。</li>
 * </ul>
 * 归组规则：同一 `taskId`（缺省回落键名）的所有键共享一个对象；组代表优先取 `key === taskId` 的那份
 * （写路径按 taskId 更新 ⇒ 它最新），组代表上打 {@link SubagentIdentity.restored}（[R3-3]：仅 running）。
 */
function loadSaved(): SubagentBySession {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return {}
    const parsed = JSON.parse(raw) as SubagentBySession
    const out: SubagentBySession = {}
    for (const [sid, session] of Object.entries(parsed ?? {})) {
      const entries = Object.entries(session ?? {}).filter((e): e is [string, SubagentIdentity] => !!e[1])
      // ① 每组（taskId）挑代表：优先 key === taskId 的那份（写路径按 taskId 更新 ⇒ 最新）
      const canonical = new Map<string, SubagentIdentity>()
      for (const [k, id] of entries) {
        const gk = id.taskId ?? k
        if (!canonical.has(gk) || k === gk) canonical.set(gk, id)
      }
      // ② 全部分配到共享对象（同一 taskId 的所有键 === 同一个引用）
      const sharedByGroup = new Map<string, SubagentIdentity>()
      const next: Record<string, SubagentIdentity> = {}
      for (const [k, id] of entries) {
        const gk = id.taskId ?? k
        let shared = sharedByGroup.get(gk)
        if (!shared) {
          const base = canonical.get(gk) ?? id
          shared = base.status === 'running' ? { ...base, restored: true } : base
          sharedByGroup.set(gk, shared)
        }
        next[k] = shared
      }
      out[sid] = next
    }
    return out
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
  /** [D2/D3] 写该任务最新统计（task_progress 累计值 / task_notification 终态值；覆盖写，不清身份/状态）。
   *  [R3-4] `opts.onlyIfRunning=true`（task_progress 来源）→ 身份已终态则**不覆盖**：终态值是权威
   *  （乱序/补投的进度事件不得把终态数改回进度数）；终态来源（task_notification）不传该 opts。 */
  setUsage: (taskId: string, usage: SubagentUsageStat | null, sessionId?: string,
    opts?: { onlyIfRunning?: boolean }) => void
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
        usage: null,   // [D2/D3] 尚无统计（等 task_progress / task_notification 写）
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
          restored: false,   // [R3-3] 实时活动 = 存活佐证 → 清「冷启动恢复」标志（卡片恢复实时计时）
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
    setUsage: (taskId, usage, sessionId, opts) => {
      const sid = sessionId ?? get().sessionId ?? ''
      set((st) => {
        const session = st.bySession[sid] ?? {}
        const identity = session[taskId] ?? Object.values(session).find((i) => i.taskId === taskId)
        if (!identity) return st   // 未登记的子代理（bash 任务 / 已 forget）→ no-op，不凭空造身份
        // [R3-4] 终态后进度不覆盖（终态值权威）；restored 清空（实时事件 = 存活佐证）
        if (opts?.onlyIfRunning
            && (identity.status === 'done' || identity.status === 'failed' || identity.status === 'stopped')) {
          return st
        }
        const updated: SubagentIdentity = { ...identity, usage, restored: false }
        // 同 addActivity：同步更新所有指向该 identity 的别名键（toolUseId / taskId）
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

/**
 * [D2] 本会话的子代理清单（用量弹窗「子代理（本会话）」区块的数据源）。
 *
 * <p><b>必须去重</b>：register 把**同一个 identity 对象**同时挂在 {@code toolUseId} 与 {@code taskId}
 * 两个键下（别名键）→ 直接 {@code Object.values} 会让同一个子代理出现两行。
 *
 * @param bySession store 的会话分区表
 * @param sessionId 会话 id（null/未登记 → 空数组）
 * @return 该会话的子代理身份（去重后，保持登记顺序）
 */
export function subagentListOfSession(
  bySession: SubagentBySession,
  sessionId: string | null | undefined,
): SubagentIdentity[] {
  const session = sessionId ? bySession[sessionId] : undefined
  if (!session) return []
  const seen = new Set<SubagentIdentity>()
  const out: SubagentIdentity[] = []
  for (const id of Object.values(session)) {
    if (seen.has(id)) continue
    seen.add(id)
    out.push(id)
  }
  return out
}

/** 失败原因文本的占位符（useChatSocket 在 summary/output_file 都缺时写入 '…' —— 不是真原因）。 */
const FAILURE_REASON_PLACEHOLDER = '…'

/**
 * [D2] 子代理失败原因（弹窗失败行那行小字的数据源）。
 *
 * <p>取**最后一条** failed 活动的文本；占位 {@code '…'} / 空白 → null = 不渲染那行小字
 * （没有原因文本时不得显示占位符）。
 *
 * @param id 子代理身份
 * @return 失败原因文本；非失败 / 无原因 → null
 */
export function failureReasonOf(id: SubagentIdentity): string | null {
  if (id.status !== 'failed') return null
  for (let i = id.activities.length - 1; i >= 0; i--) {
    const a = id.activities[i]
    if (a.type !== 'failed') continue
    const text = (a.text ?? '').trim()
    return text && text !== FAILURE_REASON_PLACEHOLDER ? text : null
  }
  return null
}
