import { useEffect, useSyncExternalStore } from 'react'
import {
  useSubagentStore,
  runningSubagentSessionIds,
  type SubagentBySession,
  type SubagentIdentity,
} from '@/stores/subagentStore'
import { tasksApi, type BackgroundTaskDto } from '@/api/tasks'

/**
 * [侧栏运行点 · 子代理那一路]
 *
 * <b>WHY 单独成模块</b>：主代理 `complete` 到达时子代理可能还在跑（AutonomousAgentLoop 不推
 * session.status），侧栏 `runningSessionIds` 只看主 turn 的三路信号 ⇒ 主完成即翻绿点。本模块把
 * 子代理的 running 并进侧栏判据（{@link runningSubagentSessionIds}），并做 2s 常驻对账
 * （REST 终态补录 + 缺席宽限清理，见 {@link reconcileRunningSubagents}）。
 *
 * <b>⛔ 边界</b>：只服务侧栏状态点。`serverRunning` / `turnRunning`（停止键 / Esc / 草稿链）不受影响。
 *
 * <b>性能</b>：`task_progress` 每个子代理 assistant 消息一条 ⇒ 不能直接订阅 `bySession` 再 useMemo
 * （每次 progress 都是新对象 ⇒ 整棵 App 重渲）。这里用 {@link useSyncExternalStore} + 内容相等缓存快照：
 * 集合成员没变就返回**同一个 Set 引用**（App 的 useMemo 依赖才稳定）。
 */

/** 上一次快照（内容相等时沿用，保证引用稳定）。 */
let cachedSnapshot: Set<string> = new Set()
/** 上一次算快照时的 bySession 引用（同引用直接短路）。 */
let cachedBySession: SubagentBySession | null = null

function sameMembers(a: Set<string>, b: Set<string>): boolean {
  if (a.size !== b.size) return false
  for (const v of a) if (!b.has(v)) return false
  return true
}

/** useSyncExternalStore 快照：bySession 换了才重算，且内容相等时返回旧 Set 引用。 */
function getSnapshot(): Set<string> {
  const bySession = useSubagentStore.getState().bySession
  if (bySession === cachedBySession) return cachedSnapshot
  cachedBySession = bySession
  const next = runningSubagentSessionIds(bySession)
  if (sameMembers(next, cachedSnapshot)) return cachedSnapshot
  cachedSnapshot = next
  return cachedSnapshot
}

function subscribe(onChange: () => void): () => void {
  return useSubagentStore.subscribe(onChange)
}

/** 仍有子代理在跑的会话 id 集合（内容不变 ⇒ 引用不变）。 */
export function useRunningSubagentSessionIds(): Set<string> {
  return useSyncExternalStore(subscribe, getSnapshot, getSnapshot)
}

/** 常驻对账间隔（与右栏 `AsyncTasksPanel` 的 REST 兜底补录同节奏）。 */
const RECONCILE_INTERVAL_MS = 2000
/** 「缺席即删」宽限期：连续几次 REST 清单里查不到才 forget（防「刚 register、清单还没反映」误删）。 */
const MISS_THRESHOLD = 2

/** 已有定时器在跑（StrictMode 双挂载 / 多实例防两套定时器）。cleanup 复位 ⇒ 能真正停掉。 */
let started = false
/** 缺席计数：key = `${sid}|${taskId}` → 连续缺席轮数（在场 / 失败 / 终态都不推进）。 */
let missCount = new Map<string, number>()

/** 测试重置：清缺席计数 + 允许重新挂定时器。 */
export function resetSubagentRunningReconcile(): void {
  missCount = new Map()
  started = false
}

/** 该会话下 taskId 对应的**当前** running 身份（await 后重读：终态事件可能先到）。 */
function liveRunningIdentity(sid: string, taskId: string): SubagentIdentity | null {
  const session = useSubagentStore.getState().bySession[sid]
  if (!session) return null
  const direct = session[taskId]
  if (direct) return direct.status === 'running' ? direct : null
  for (const id of Object.values(session)) {
    if (id?.taskId === taskId) return id.status === 'running' ? id : null
  }
  return null
}

/**
 * [侧栏运行点 · 常驻对账] 把 store 里**仍 running** 的子代理身份与 REST 任务清单对齐一轮。
 *
 * <p><b>WHY 常驻而不是一次性</b>：终态事件（STOMP `task_notification`）丢失不只在冷启动发生——
 * 实时任务同样可能丢 ⇒ 一次性清扫只能救冷启动那份残留，丢帧的实时任务会让侧栏**永久闪**。
 *
 * <p><b>三道门</b>：
 * <ol>
 *   <li>页面在后台（{@code document.visibilityState === 'hidden'}）⇒ 跳过本轮；</li>
 *   <li>某会话没有任何 running 身份 ⇒ **不发请求**（空闲零 HTTP）；</li>
 *   <li>{@code tasksApi.list} 抛错 ⇒ 该会话本轮**完全跳过**：不推进 missCount、不改 store
 *       （否则后端启动期连续两次失败会把活着的任务误删）。</li>
 * </ol>
 *
 * <p><b>对账口径</b>（逐个 running 身份）：REST 查不到 → 连续 {@link MISS_THRESHOLD} 次才
 * {@code forget}；completed → 补 {@code done}；failed / killed → 补 {@code stopped}；
 * pending / running / 已不在 running → 什么都不做并清缺席计数（在场就不算缺席）。
 *
 * <p><b>测试桩兼容</b>：既有测试 mock 了 {@code @/api/tasks} 但没 {@code list}（生产恒有）⇒ 静默跳过。
 */
async function reconcileRunningSubagents(): Promise<void> {
  if (typeof tasksApi.list !== 'function') return          // 测试桩兼容（生产恒有 list）
  if (document.visibilityState === 'hidden') return       // 门① 后台页不查

  const bySession = useSubagentStore.getState().bySession ?? {}
  /** 本轮仍有 running 身份的键；轮末清掉不在其中的缺席计数（防 Map 无限增长）。 */
  const keep = new Set<string>()

  for (const [sid, session] of Object.entries(bySession)) {
    // 按 taskId 去重：register 把同一 identity 同时挂在 taskId / toolUseId 两键下
    const running = new Map<string, SubagentIdentity>()
    for (const [key, id] of Object.entries(session ?? {})) {
      if (!id || id.status !== 'running') continue
      const taskId = id.taskId ?? key
      if (!running.has(taskId)) running.set(taskId, id)
    }
    if (running.size === 0) continue                      // 门② 无 running ⇒ 不发请求

    for (const taskId of running.keys()) keep.add(`${sid}|${taskId}`)

    let list: BackgroundTaskDto[]
    try {
      list = (await tasksApi.list(sid)) ?? []
    } catch {
      continue                                            // 门③ 失败：不计数、不改 store
    }
    const restById = new Map(list.map((t) => [t.id, t]))

    for (const taskId of running.keys()) {
      const key = `${sid}|${taskId}`
      if (!liveRunningIdentity(sid, taskId)) {            // 已终态 / 已 forget（await 期间）
        missCount.delete(key)
        continue
      }
      const task = restById.get(taskId)
      if (task) {
        missCount.delete(key)                             // 在场 ⇒ 不算缺席
        if (task.status === 'completed') {
          useSubagentStore.getState().addActivity(taskId, { type: 'done', text: task.status, ts: Date.now() }, sid)
        } else if (task.status === 'failed' || task.status === 'killed') {
          useSubagentStore.getState().addActivity(taskId, { type: 'stopped', text: task.status, ts: Date.now() }, sid)
        }
        // pending / running → 保持 running（侧栏继续闪）
        continue
      }
      const misses = (missCount.get(key) ?? 0) + 1
      if (misses >= MISS_THRESHOLD) {
        missCount.delete(key)
        useSubagentStore.getState().forget(taskId, sid)
      } else {
        missCount.set(key, misses)
      }
    }
  }

  for (const key of [...missCount.keys()]) if (!keep.has(key)) missCount.delete(key)
}

/**
 * App 里挂一次：2s 常驻对账定时器。cleanup 真正 `clearInterval`（StrictMode 双挂载不跑两套、
 * 测试能停）；模块级 {@link started} 防同一时刻两个定时器。
 */
export function useSubagentRunningReconcile(): void {
  useEffect(() => {
    if (started) return
    if (typeof tasksApi.list !== 'function') return       // 测试桩兼容：不挂永不清理的定时器
    started = true
    const timer = window.setInterval(() => { void reconcileRunningSubagents() }, RECONCILE_INTERVAL_MS)
    return () => { started = false; window.clearInterval(timer) }
  }, [])
}
