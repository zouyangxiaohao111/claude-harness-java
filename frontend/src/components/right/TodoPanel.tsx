import { useEffect, useRef, useState } from 'react'
import { tasksApi } from '@/api/tasks'
import { ApiError } from '@/api/rest'
import type { TodoStatus, TaskListSnapshotDto } from '@/api/types'

interface TodoPanelProps {
  sessionId: string | null
  showToast: (msg: string, type?: 'success' | 'info') => void
  /** 折叠分组已提供标题 → 不再渲染自身标题行 */
  hideHeader?: boolean
  /** 上报任务数给折叠组（0 → 该组折叠成一行） */
  onCount?: (n: number) => void
}


/**
 * 任务清单面板（右栏任务 tab）· 合并端点 GET /tasks/list?sessionId（TaskCreate V2 + TodoWrite V1）。
 * V1/V2 互斥（一个会话只用其一）→ 按非空方显示。3s 轮询刷新（后端落库后自动更新）。
 * 后台任务清单（GET /tasks）由 AsyncTasksPanel 单独承载，不受影响。
 */
/** 任务项视图（V2 带 description/依赖；V1 只有内容与状态） */
interface TodoItemView {
  id: string
  content: string
  status: TodoStatus
  activeForm?: string | null
  description?: string | null
  blocks?: string[]
  blockedBy?: string[]
}

export function TodoPanel({ sessionId, showToast, hideHeader = false, onCount }: TodoPanelProps) {
  /**
   * 快照与其所属会话**绑定存储**（2026-10-21 用户实测「切会话任务清单不跟着变」）。
   *
   * <p>原实现只存 data：切换会话后新请求返回前（或请求失败时）一直显示**上一会话**的任务，
   * 视觉上就是「没按会话过滤」。把 sid 一起存、渲染时比对 ⇒ 切会话立即视为空，
   * 不需要额外 setState（也就不会让折叠组的计数闪一下 0）。
   */
  const [snapshotState, setSnapshotState] = useState<{ sid: string; data: TaskListSnapshotDto } | null>(null)
  const snapshot = snapshotState && snapshotState.sid === sessionId ? snapshotState.data : null
  /** showToast 走 ref：否则父组件每次渲染的新函数引用会让轮询 effect 反复重建 */
  const showToastRef = useRef(showToast)
  useEffect(() => { showToastRef.current = showToast })
  const [filter, setFilter] = useState<TodoStatus | null>(null)
  /** 任务详情（点击任务行打开 · 用户 2026-10-21 要求「任务列表能点开详情」） */
  const [detail, setDetail] = useState<TodoItemView | null>(null)

  // 挂载 + sessionId 变化：2s 轮询合并端点（V2 TaskCreate / V1 TodoWrite 互斥，非空方显示）
  useEffect(() => {
    if (!sessionId) return
    let cancelled = false
    const tick = () => {
      tasksApi
        .listSnapshot(sessionId)
        .then((snap) => { if (!cancelled) setSnapshotState({ sid: sessionId, data: snap }) })
        .catch((e) => { if (!cancelled) showToastRef.current(e instanceof ApiError ? e.userMessage() : String(e), 'info') })
    }
    tick()
    const timer = window.setInterval(tick, 2000)
    return () => { cancelled = true; window.clearInterval(timer) }
  }, [sessionId])

  const v2 = snapshot?.v2Tasks ?? []
  const v1 = snapshot?.v1Todos ?? []
  // V1/V2 互斥：v2Tasks 非空 → 显示 V2（TaskCreate）；否则 V1（TodoWrite）
  const useV2 = v2.length > 0
  // 统一结构（V2 subject / V1 content → content）
  const items: TodoItemView[] = useV2
    ? v2.map((t) => ({
        id: t.id, content: t.subject, status: t.status, activeForm: t.activeForm ?? null,
        description: t.description ?? null, blocks: t.blocks ?? [], blockedBy: t.blockedBy ?? [],
      }))
    : v1.map((t, i) => ({
        id: String(i), content: t.content, status: t.status, activeForm: t.activeForm ?? null,
      }))
  // [D2] 上报任务数给折叠组（必须放在 items 之后，否则 TDZ）
  useEffect(() => { onCount?.(items.length) }, [items.length, onCount])
  // 三态统计（对齐子代理运行状况）：待办=pending · 进行中=in_progress · 已完成=completed
  const pendingItems = items.filter((i) => i.status === 'pending')
  const inProgressItems = items.filter((i) => i.status === 'in_progress')
  const completedItems = items.filter((i) => i.status === 'completed')
  // [D2·b] 展开态 = 筛选胶囊 + 行式列表（用户 2026-10-21：要按设计稿，不要三张 0 卡片）
  const shown = (filter ? items.filter((i) => i.status === filter) : items).slice()
  shown.sort((a, b) => {
    const rank = (s: TodoStatus) => (s === 'in_progress' ? 0 : s === 'pending' ? 1 : 2)
    return rank(a.status) - rank(b.status)
  })
  const chip = (key: TodoStatus | null, label: string, n: number) => (
    <button
      key={key ?? 'all'}
      className={`todo-chip${filter === key ? ' on' : ''}`}
      onClick={() => setFilter(key)}
    >
      {label} {n}
    </button>
  )

  return (
    <>
      {!hideHeader && <div className="task-group-title">任务清单</div>}
      <div className="todo-chips">
        {chip(null, '全部', items.length)}
        {chip('pending', '待办', pendingItems.length)}
        {chip('in_progress', '进行中', inProgressItems.length)}
        {chip('completed', '已完成', completedItems.length)}
      </div>
      {shown.length === 0 ? (
        <div className="todo-empty">没有任务</div>
      ) : (
        <div className="todo-rows">
          {shown.map((t) => (
            <div key={t.id} className={`todo-row ${t.status}`} onClick={() => setDetail(t)} title="查看任务详情">
              <span className={`todo-row-dot ${t.status}`} />
              <span className="todo-row-main">
                <span className="todo-row-title">{t.content}</span>
                {(t.activeForm || (t.blockedBy && t.blockedBy.length > 0)) && (
                  <span className="todo-row-sub">
                    {t.activeForm ?? `等待 ${t.blockedBy!.length} 项前置任务`}
                  </span>
                )}
              </span>
              <span className="todo-row-arrow">›</span>
            </div>
          ))}
        </div>
      )}
      {detail && (
        <TaskDetailModal item={detail} allItems={items} onClose={() => setDetail(null)} />
      )}
    </>
  )
}


/**
 * 任务详情弹窗（用户 2026-10-21：任务列表项要能点开）。
 *
 * <p>字段全部来自 TaskItem（V2）：subject→content / description / activeForm / blocks / blockedBy。
 * V1（TodoWrite）没有描述与依赖 → 对应区块不渲染，不编造。
 */
function TaskDetailModal({ item, allItems, onClose }: {
  item: TodoItemView
  allItems: TodoItemView[]
  onClose: () => void
}) {
  const label = item.status === 'pending' ? '待办' : item.status === 'in_progress' ? '进行中' : '已完成'
  const nameOf = (id: string) => allItems.find((x) => x.id === id)?.content ?? id
  const blockedBy = item.blockedBy ?? []
  const blocks = item.blocks ?? []
  return (
    <div className="overlay-backdrop" onClick={onClose}>
      <div className="subagent-modal task-detail" onClick={(e) => e.stopPropagation()}>
        <div className="sam-head">
          <span className="sam-title">{item.content}</span>
          <button type="button" className="sam-close" onClick={onClose} aria-label="关闭">
            <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="M3 3L9 9M9 3L3 9" /></svg>
          </button>
        </div>
        <div className="sam-list task-detail-body">
          <div className="td-meta">
            <span className={`td-badge ${item.status}`}>{label}</span>
            <span className="td-id">#{item.id}</span>
          </div>
          {item.activeForm && (
            <div className="td-sec">
              <div className="td-label">当前动作</div>
              <div className="td-val">{item.activeForm}</div>
            </div>
          )}
          {item.description && (
            <div className="td-sec">
              <div className="td-label">描述</div>
              <div className="td-val">{item.description}</div>
            </div>
          )}
          {blockedBy.length > 0 && (
            <div className="td-sec">
              <div className="td-label">等待</div>
              <div className="td-items">
                {blockedBy.map((id) => <span key={id} className="td-item wait">{nameOf(id)}</span>)}
              </div>
            </div>
          )}
          {blocks.length > 0 && (
            <div className="td-sec">
              <div className="td-label">阻塞</div>
              <div className="td-items">
                {blocks.map((id) => <span key={id} className="td-item block">{nameOf(id)}</span>)}
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
