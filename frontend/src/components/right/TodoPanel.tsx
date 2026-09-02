import { useEffect, useState } from 'react'
import { tasksApi } from '@/api/tasks'
import { ApiError } from '@/api/rest'
import type { TodoStatus, TaskListSnapshotDto } from '@/api/types'

interface TodoPanelProps {
  sessionId: string | null
  showToast: (msg: string, type?: 'success' | 'info') => void
}

/** 状态图标（SVG/字符：pending ○ / in_progress ● / completed ✓ · 不用 emoji） */
function TodoStatusIcon({ status }: { status: TodoStatus }) {
  if (status === 'completed') {
    return (
      <span className="todo-status completed">
        <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="2" style={{ width: 10, height: 10 }}>
          <path d="M2 6.5L4.5 9L10 3" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      </span>
    )
  }
  return (
    <span className={`todo-status ${status}`}>
      <span className="dot" />
    </span>
  )
}

/**
 * 任务清单面板（右栏任务 tab）· 合并端点 GET /tasks/list?sessionId（TaskCreate V2 + TodoWrite V1）。
 * V1/V2 互斥（一个会话只用其一）→ 按非空方显示。3s 轮询刷新（后端落库后自动更新）。
 * 后台任务清单（GET /tasks）由 AsyncTasksPanel 单独承载，不受影响。
 */
export function TodoPanel({ sessionId, showToast }: TodoPanelProps) {
  const [snapshot, setSnapshot] = useState<TaskListSnapshotDto | null>(null)
  const [filter, setFilter] = useState<TodoStatus | null>(null)

  // 挂载 + sessionId 变化：2s 轮询合并端点（V2 TaskCreate / V1 TodoWrite 互斥，非空方显示）
  useEffect(() => {
    if (!sessionId) {
      setSnapshot(null)
      return
    }
    let cancelled = false
    const fetch = () => {
      tasksApi
        .listSnapshot(sessionId)
        .then((snap) => { if (!cancelled) setSnapshot(snap) })
        .catch((e) => { if (!cancelled) showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info') })
    }
    fetch()
    const timer = window.setInterval(fetch, 2000)
    return () => { cancelled = true; window.clearInterval(timer) }
  }, [sessionId, showToast])

  const v2 = snapshot?.v2Tasks ?? []
  const v1 = snapshot?.v1Todos ?? []
  // V1/V2 互斥：v2Tasks 非空 → 显示 V2（TaskCreate）；否则 V1（TodoWrite）
  const useV2 = v2.length > 0
  // 统一结构（V2 subject / V1 content → content）
  const items: { id: string; content: string; status: TodoStatus; activeForm?: string | null }[] = useV2
    ? v2.map((t) => ({ id: t.id, content: t.subject, status: t.status, activeForm: t.activeForm ?? null }))
    : v1.map((t, i) => ({ id: String(i), content: t.content, status: t.status, activeForm: t.activeForm ?? null }))
  // 三态统计（对齐子代理运行状况）：待办=pending · 进行中=in_progress · 已完成=completed
  const pendingItems = items.filter((i) => i.status === 'pending')
  const inProgressItems = items.filter((i) => i.status === 'in_progress')
  const completedItems = items.filter((i) => i.status === 'completed')
  const stat = (label: string, list: typeof items, f: TodoStatus) => (
    <div className={`sa-stat ${f === 'in_progress' ? 'running' : f === 'completed' ? 'done' : 'stopped'}`} onClick={() => setFilter(f)} title={`查看${label}（${list.length}）`}>
      <span className="sa-count">{list.length}</span>
      <span className="sa-label">{label}</span>
    </div>
  )

  return (
    <>
      <div className="task-group-title">任务清单</div>
      {/* 常驻三态统计（对齐子代理运行状况 · 无任务也显示 0 0 0） */}
      <div className="subagent-stats">
        {stat('待办', pendingItems, 'pending')}
        {stat('进行中', inProgressItems, 'in_progress')}
        {stat('已完成', completedItems, 'completed')}
      </div>
      {filter && (
        <TodoModal filter={filter} items={items} onClose={() => setFilter(null)} />
      )}
    </>
  )
}

/** 任务清单三态弹窗（对齐子代理弹窗样式 · subagent-modal） */
function TodoModal({ filter, items, onClose }: {
  filter: TodoStatus
  items: { id: string; content: string; status: TodoStatus; activeForm?: string | null }[]
  onClose: () => void
}) {
  const shown = items.filter((i) => i.status === filter)
  const titleLabel = filter === 'pending' ? '待办' : filter === 'in_progress' ? '进行中' : '已完成'
  return (
    <div className="overlay-backdrop" onClick={onClose}>
      <div className="subagent-modal" onClick={(e) => e.stopPropagation()}>
        <div className="sam-head">
          <span className="sam-title">任务清单 · {titleLabel}<span className="sam-count">{shown.length}</span></span>
          <button type="button" className="sam-close" onClick={onClose} aria-label="关闭">
            <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="M3 3L9 9M9 3L3 9" /></svg>
          </button>
        </div>
        <div className="sam-list">
          {shown.length === 0 ? (
            <div className="sam-empty">暂无</div>
          ) : shown.map((t) => (
            <div key={t.id} className={`todo-item ${t.status}`}>
              <TodoStatusIcon status={t.status} />
              <div className="todo-content">
                <div className="text">{t.content}</div>
                {t.status === 'in_progress' && t.activeForm && (
                  <div className="todo-active-form">{t.activeForm}</div>
                )}
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}
