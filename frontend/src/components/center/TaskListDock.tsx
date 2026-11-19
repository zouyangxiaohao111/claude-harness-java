import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { tasksApi } from '@/api/tasks'
import type { TaskListSnapshotDto, TodoStatus } from '@/api/types'

/**
 * [task-list-inline] TaskCreate 任务 · 主会话胶囊入口（与子代理/监控胶囊同一排）。
 *
 * 显示口径（用户 2026-11-19 裁定）：
 *  - 没有创建任务 → 不显示；
 *  - 任务全部完成 → 保留 5 秒后调 resetTaskList 删任务文件并隐藏（对齐 CC useTasksV2）；
 *  - 只有部分完成（如 1/10）时显示，并像「正在监听」一样闪烁蓝色；
 *  - 点击胶囊弹出任务明细（portal 到 body，避免被输入区裁剪）。
 *
 * 样式复用监控胶囊的类名 `subagent-running-hint monitor-running-hint`
 * （蓝色底 + 呼吸动画 + 圆点），因此与监控完全一致。
 */

interface TaskListDockProps {
  sessionId: string | null
  /** 当前轮是否在跑（保留形参，供后续使用；明细里的进行中行仍按任务状态显示） */
  running: boolean
  /** 人工提醒模型更新清单 */
  onRemind?: () => void
}

interface Row {
  id: string
  subject: string
  status: TodoStatus
  activeForm?: string | null
  blockedBy: string[]
  owner?: string | null
  /** running teammate 的最近活动摘要（对齐 CC TaskListV2 进行中任务第二行） */
  activity?: string | null
}

const MAX_ROWS = 12
/** 全部任务完成后延迟多久再清空任务文件 · 对齐 CC useTasksV2.ts:20 HIDE_DELAY_MS=5000。 */
const HIDE_DELAY_MS = 5000
/** 「最近完成」保留在优先级顶部的时长 · 对齐 CC TaskListV2.tsx:24 RECENT_COMPLETED_TTL_MS。 */
const RECENT_COMPLETED_TTL_MS = 30_000

/** 稳定 id 升序 · 对齐 CC TaskListV2.tsx byIdAsc（数字 id 按数值，否则字典序）。 */
function byIdAsc(a: Row, b: Row): number {
  const an = parseInt(a.id, 10)
  const bn = parseInt(b.id, 10)
  if (!Number.isNaN(an) && !Number.isNaN(bn)) return an - bn
  return a.id.localeCompare(b.id)
}

export function TaskListDock({ sessionId, running, onRemind }: TaskListDockProps) {
  const [snapshot, setSnapshot] = useState<{ sid: string; data: TaskListSnapshotDto } | null>(null)
  const [open, setOpen] = useState(false)
  const [anchor, setAnchor] = useState<{ left: number; bottom: number } | null>(null)
  const [resetRetry, setResetRetry] = useState(0)
  /** owner 名/agentId → 最近活动摘要（对齐 CC appState.tasks[].progress.recentActivities） */
  const [activities, setActivities] = useState<Record<string, string>>({})
  const btnRef = useRef<HTMLButtonElement | null>(null)
  /** 每个任务最近一次被观察到转为 completed 的时间 · 对齐 CC TaskListV2.tsx:47-70。 */
  const completionTimestampsRef = useRef(new Map<string, number>())
  const previousCompletedIdsRef = useRef<Set<string> | null>(null)

  const data = snapshot && snapshot.sid === sessionId ? snapshot.data : null

  useEffect(() => {
    if (!sessionId) return
    // 切会话先清空活动映射，避免同名 owner 复用上一个会话的活动
    setActivities({})
    let cancelled = false
    const tick = () => {
      tasksApi.listSnapshot(sessionId)
        .then((snap) => { if (!cancelled) setSnapshot({ sid: sessionId, data: snap }) })
        .catch(() => { /* 辅助状态：失败静默，下一轮重试 */ })
      // [activity-summary] 拉 in-process teammate 的活动摘要（对齐 CC appState.tasks）
      if (typeof tasksApi.list === 'function') {
        tasksApi.list(sessionId)
          .then((list) => {
            if (cancelled) return
            const map: Record<string, string> = {}
            for (const t of list) {
              if (!t.activity) continue
              if (t.ownerName) map[t.ownerName] = t.activity
              if (t.agentId) map[t.agentId] = t.activity
            }
            setActivities((prev) => {
              const prevKeys = Object.keys(prev)
              const same = prevKeys.length === Object.keys(map).length
                && prevKeys.every((k) => prev[k] === map[k])
              return same ? prev : map
            })
          })
          .catch(() => { /* 辅助状态：失败静默 */ })
      }
    }
    tick()
    const timer = window.setInterval(tick, 2000)
    return () => { cancelled = true; window.clearInterval(timer) }
  }, [sessionId])

  // Ctrl+T 开合明细
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.ctrlKey && (e.key === 't' || e.key === 'T')) {
        e.preventDefault()
        setOpen((v) => !v)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  // 对齐 CC useTasksV2 #fetch：内部任务（metadata._internal）不参与显示与完成判断
  const v2 = (data?.v2Tasks ?? []).filter((t) => !t.metadata?._internal)
  const v1 = data?.v1Todos ?? []
  const rows: Row[] = v2.length > 0
    ? v2.map((t) => ({
        id: t.id, subject: t.subject, status: t.status,
        activeForm: t.activeForm ?? null, blockedBy: t.blockedBy ?? [],
        owner: t.owner ?? null,
        activity: t.owner ? activities[t.owner] ?? null : null,
      }))
    : v1.map((t, i) => ({ id: String(i), subject: t.content, status: t.status, blockedBy: [] }))

  const done = rows.filter((r) => r.status === 'completed').length
  const runningRow = rows.filter((r) => r.status === 'in_progress').length
  const pending = rows.length - done - runningRow
  const allDone = rows.length > 0 && done === rows.length
  /** 只有 V2（任务文件）才有可删清单；V1 todos 存 sessions.todos 列，全部完成后直接隐藏。 */
  const resettable = v2.length > 0

  // 全部完成 → 5 秒后 resetTaskList（提水位 + 删任务文件），对齐 CC useTasksV2.ts:157-176。
  // 这里不是单纯隐藏：旧批任务文件必须删掉，否则下一批任务会把它们一起计入（如 12 旧 + 1 新）。
  // 5 秒内出现未完成任务 / 切换会话 ⇒ effect 清理取消计时；reset 失败 ⇒ 递增 resetRetry 重排。
  useEffect(() => {
    if (!sessionId || !allDone || !resettable) return
    const sid = sessionId
    let cancelled = false
    const timer = window.setTimeout(() => {
      tasksApi.listSnapshot(sid)
        .then(async (snap) => {
          if (cancelled) return
          // 复核：5 秒窗口内可能又被改成未完成（对齐 CC #onHideTimerFired 的 allStillCompleted）
          const snapV2 = snap.v2Tasks ?? []
          const snapV1 = snap.v1Todos ?? []
          const stillAllDone = snapV2.length > 0
            ? snapV2.every((t) => t.status === 'completed')
            : snapV1.length > 0 && snapV1.every((t) => t.status === 'completed')
          if (!stillAllDone) return
          await tasksApi.resetTaskList(sid)
          if (!cancelled) setSnapshot({ sid, data: { ...snap, v2Tasks: [], v1Todos: [] } })
        })
        .catch(() => { if (!cancelled) setResetRetry((v) => v + 1) })
    }, HIDE_DELAY_MS)
    return () => { cancelled = true; window.clearTimeout(timer) }
  }, [sessionId, allDone, resettable, resetRetry])

  // 没有任务 → 不渲染；V2 全部完成仍显示 5 秒（effect 到点清空）；V1 全部完成直接隐藏（无可删文件）
  if (!sessionId || rows.length === 0 || (allDone && !resettable)) return null

  const unresolved = new Set(rows.filter((r) => r.status !== 'completed').map((r) => r.id))

  // 完成时间跟踪（30s 内完成的排最前）· 对齐 CC TaskListV2.tsx:47-70
  const currentCompletedIds = new Set(rows.filter((r) => r.status === 'completed').map((r) => r.id))
  if (previousCompletedIdsRef.current === null) {
    previousCompletedIdsRef.current = new Set(currentCompletedIds)
  }
  const now = Date.now()
  for (const id of currentCompletedIds) {
    if (!previousCompletedIdsRef.current.has(id)) completionTimestampsRef.current.set(id, now)
  }
  for (const id of Array.from(completionTimestampsRef.current.keys())) {
    if (!currentCompletedIds.has(id)) completionTimestampsRef.current.delete(id)
  }
  previousCompletedIdsRef.current = currentCompletedIds

  // 超限重排 · 对齐 CC TaskListV2.tsx:146-171：
  //   最近完成（30s 内）→ 进行中 → 未开始（未阻塞优先）→ 旧完成
  let ordered: Row[]
  if (rows.length > MAX_ROWS) {
    const recentCompleted: Row[] = []
    const olderCompleted: Row[] = []
    for (const r of rows.filter((x) => x.status === 'completed')) {
      const ts = completionTimestampsRef.current.get(r.id)
      if (ts && now - ts < RECENT_COMPLETED_TTL_MS) recentCompleted.push(r)
      else olderCompleted.push(r)
    }
    recentCompleted.sort(byIdAsc)
    olderCompleted.sort(byIdAsc)
    const inProgress = rows.filter((r) => r.status === 'in_progress').sort(byIdAsc)
    const pendingRows = rows.filter((r) => r.status === 'pending').sort((a, b) => {
      const aBlocked = a.blockedBy.some((id) => unresolved.has(id))
      const bBlocked = b.blockedBy.some((id) => unresolved.has(id))
      if (aBlocked !== bBlocked) return aBlocked ? 1 : -1
      return byIdAsc(a, b)
    })
    ordered = [...recentCompleted, ...inProgress, ...pendingRows, ...olderCompleted]
  } else {
    ordered = [...rows].sort(byIdAsc)
  }

  const shown = ordered.slice(0, MAX_ROWS)
  const hiddenRows = ordered.slice(MAX_ROWS)
  const hiddenParts: string[] = []
  const hiddenInProgress = hiddenRows.filter((r) => r.status === 'in_progress').length
  const hiddenPending = hiddenRows.filter((r) => r.status === 'pending').length
  const hiddenCompleted = hiddenRows.filter((r) => r.status === 'completed').length
  if (hiddenInProgress > 0) hiddenParts.push(`${hiddenInProgress} 个进行中`)
  if (hiddenPending > 0) hiddenParts.push(`${hiddenPending} 个未开始`)
  if (hiddenCompleted > 0) hiddenParts.push(`${hiddenCompleted} 个已完成`)

  const toggle = () => {
    if (!open && btnRef.current) {
      const r = btnRef.current.getBoundingClientRect()
      setAnchor({ left: Math.max(8, r.left), bottom: window.innerHeight - r.top + 8 })
    }
    setOpen((v) => !v)
  }

  const panel = open && anchor ? createPortal(
    <>
      <div
        onClick={() => setOpen(false)}
        style={{ position: 'fixed', inset: 0, zIndex: 900 }}
      />
      <div
        className="task-list-pop"
        role="dialog"
        aria-label="任务清单"
        style={{
          position: 'fixed',
          left: anchor.left,
          bottom: anchor.bottom,
          width: 360,
          maxHeight: 260,
          overflowY: 'auto',
          background: '#fff',
          border: '1px solid #E2E2E2',
          borderRadius: 12,
          boxShadow: '0 12px 40px rgba(20,24,31,.18)',
          padding: '11px 13px',
          zIndex: 901,
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 12, color: '#8a93a2', marginBottom: 8 }}>
          <span>
            <b style={{ color: '#2c3440' }}>{rows.length}</b> 个任务（
            <b style={{ color: '#2D6E3D' }}>{done}</b> 已完成
            {runningRow > 0 && <>，<b style={{ color: '#0f5fd4' }}>{runningRow}</b> 进行中</>}
            ，<b style={{ color: '#2c3440' }}>{pending}</b> 未完成）
          </span>
          <span style={{ marginLeft: 'auto', display: 'inline-flex', gap: 10 }}>
            {onRemind && (
              <button
                onClick={onRemind}
                title="发一条提醒，让模型重新核对并更新任务清单"
                style={{ border: 0, background: 'transparent', color: '#0f5fd4', fontSize: 12, cursor: 'pointer', padding: 0 }}
              >
                提醒更新
              </button>
            )}
            <button
              onClick={() => setOpen(false)}
              style={{ border: 0, background: 'transparent', color: '#8a93a2', fontSize: 12, cursor: 'pointer', padding: 0 }}
            >
              收起
            </button>
          </span>
        </div>
        {shown.map((r) => {
          const blocked = r.blockedBy.filter((id) => unresolved.has(id))
          const icon = r.status === 'completed' ? '✓' : r.status === 'in_progress' ? '■' : '□'
          const iconColor = r.status === 'completed' ? '#5DB872' : r.status === 'in_progress' ? '#0f5fd4' : '#9aa3b2'
          return (
            <div key={r.id} style={{ display: 'flex', alignItems: 'flex-start', gap: 8, padding: '2px 0' }}>
              <span style={{ width: 14, flex: 'none', color: iconColor, fontSize: 12, marginTop: 3 }}>{icon}</span>
              <div style={{ minWidth: 0, flex: 1 }}>
                <div
                  style={{
                    fontSize: 13.5, lineHeight: 1.5, wordBreak: 'break-word',
                    color: r.status === 'completed' ? '#9aa3b2' : r.status === 'in_progress' ? '#14181f' : '#2c3440',
                    fontWeight: r.status === 'in_progress' ? 600 : 400,
                    textDecoration: r.status === 'completed' ? 'line-through' : 'none',
                  }}
                >
                  {r.subject}
                  {r.owner && (
                    <span style={{ fontSize: 11.5, color: '#8a93a2', marginLeft: 6 }}>
                      (@{r.owner})
                    </span>
                  )}
                  {blocked.length > 0 && (
                    <span style={{ fontSize: 11.5, color: '#8a93a2', marginLeft: 6 }}>
                      ↳ blocked by {[...blocked].sort((a, b) => parseInt(a, 10) - parseInt(b, 10)).map((x) => `#${x}`).join(', ')}
                    </span>
                  )}
                </div>
                {r.status === 'in_progress' && blocked.length === 0 && r.activity ? (
                  // 活动摘要（CC TaskListV2 第二行）：running teammate 的最近工具活动；被 blocked 不显示
                  <div style={{
                    fontSize: 11.5, color: '#8a93a2', margin: '1px 0 2px',
                    whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                  }}>
                    {r.activity.endsWith('…') ? r.activity : `${r.activity}…`}
                  </div>
                ) : r.status === 'in_progress' && r.activeForm ? (
                  <div style={{
                    fontSize: 11.5, color: '#8a93a2', margin: '1px 0 2px',
                    whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                  }}>
                    {r.activeForm}
                  </div>
                ) : null}
              </div>
            </div>
          )
        })}
        {hiddenRows.length > 0 && (
          <div style={{ fontSize: 11.5, color: '#9aa3b2', marginTop: 5 }}>… +{hiddenParts.join('，')}</div>
        )}
      </div>
    </>,
    document.body,
  ) : null

  return (
    <>
      <button
        ref={btnRef}
        type="button"
        className="subagent-running-hint monitor-running-hint"
        onClick={toggle}
        title={`${running ? '任务进行中' : '任务未全部完成'}\n点开查看明细`}
        aria-label={`任务 ${done}/${rows.length}，点开查看`}
      >
        <span className="srh-dot monitor" aria-hidden="true" />
        {/* 胶囊口径 = 已完成/总数（用户 2026-11-19：显示 11/12 才直观；原先有进行中任务时
            显示的是「进行中数/总数」(1/12)，看起来像还剩多少，不是进度） */}
        <span className="mrh-label">任务 {done}/{rows.length}</span>
        <span className="srh-arrow" aria-hidden="true">›</span>
      </button>
      <style>{'.task-list-pop{scrollbar-width:none;-ms-overflow-style:none}'
        + '.task-list-pop::-webkit-scrollbar{display:none;width:0;height:0}'}</style>
      {panel}
    </>
  )
}
