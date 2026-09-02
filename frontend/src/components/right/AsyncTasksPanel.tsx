import { useCallback, useEffect, useState } from 'react'
import { tasksApi, type BackgroundTaskDto } from '@/api/tasks'
import { ApiError } from '@/api/rest'
import { useSubagentStore } from '@/stores/subagentStore'

/**
 * 异步任务清单面板（任务 tab 内嵌模块 · 独立组件隔离轮询）。
 *
 * <p>WHY：4s 轮询若放 RightPanel 主组件会触发整个任务 tab 重渲染（TeamPanel/子代理/
 * workflow 全部）→ 卡顿。抽成独立组件，asyncTasks state + 轮询 effect 都在本组件内，
 * 只有本组件 re-render，任务 tab 其他模块不受影响。
 *
 * <p>默认展示 5 个（running 优先）·「查看更多」弹窗全量 · 全部停止为当前会话级。
 */
const TASK_TYPE_LABEL: Record<string, string> = {
  local_bash: '命令', local_agent: '子代理', in_process_teammate: '队友',
  local_workflow: 'workflow', monitor_mcp: '监控',
}
const TASK_STATUS_LABEL: Record<string, string> = {
  pending: '等待', running: '运行中', completed: '已完成', failed: '失败', killed: '已停止',
}

export function AsyncTasksPanel({ activeSessionId, showToast }: {
  activeSessionId: string | null
  showToast: (msg: string, type?: 'success' | 'info') => void
}) {
  const [asyncTasks, setAsyncTasks] = useState<BackgroundTaskDto[]>([])
  const [asyncTasksOpen, setAsyncTasksOpen] = useState(false)
  /** 三态弹窗过滤（进行中/已完成/已停止 · 对齐子代理运行状况点开查看） */
  const [statFilter, setStatFilter] = useState<'running' | 'done' | 'stopped' | null>(null)
  // 常驻展示（对齐子代理运行状况 · 无折叠）：2s 轮询始终执行 REST 兜底补录，子代理实时恢复

  // 会话级加载（list(activeSessionId) 传会话 id · 弹窗打开时也刷新，确保会话隔离最新）
  const load = useCallback(() => {
    if (!activeSessionId) return
    tasksApi.list(activeSessionId)
      .then((list) => {
        // [subagent-restore 2026-08-25] REST 兜底补录：STOMP /topic/tasks 不重放历史事件，子代理
        //   task_started 若在 STOMP 断连/未订阅窗口被 drain 即丢失 → subagentStore 空 → 「子代理运行
        //   状况」区不显示。此处从 REST 同源（BackgroundTaskRunner 持久 task store）对 type=local_agent
        //   且 store 未登记的任务补录 register —— 4s 轮询恢复子代理卡片，STOMP 丢失不再留白。
        for (const t of list ?? []) {
          if (t.type !== 'local_agent') continue
          const st = useSubagentStore.getState()
          const existing = st.bySession[activeSessionId]?.[t.id]
          if (existing) continue
          // taskId 作 key（register 第 2 参；toolUseId null 与 STOMP 事件同构）。description 兜底 task_type。
          st.register(null, t.id, t.description || t.type, t.type, activeSessionId)
          // 按 REST 状态初始化三态（completed→done · failed/killed→stopped）：STOMP 不重放终态，
          //   补录后若不补终态活动，子代理恒显示「运行中」（常驻三态下虚高）
          if (t.status === 'completed') {
            st.addActivity(t.id, { type: 'done', text: t.status, ts: Date.now() }, activeSessionId)
          } else if (t.status === 'failed' || t.status === 'killed') {
            st.addActivity(t.id, { type: 'stopped', text: t.status, ts: Date.now() }, activeSessionId)
          }
        }
        // 只展示真异步后台任务（isBackgrounded=true；undefined=旧后端兼容保留）：同步前台任务
        //   （如 Bash 工具）已在对话内工具卡展示，不重复出现在异步任务面板。
        const filtered = (list ?? []).filter((t) => t.isBackgrounded !== false)
        // 内容守卫：轮询数据未变则不 setState（避免新数组引用触发 re-render → 任务 tab 抖动）
        setAsyncTasks((prev) => (JSON.stringify(prev) === JSON.stringify(filtered) ? prev : filtered))
      })
      .catch(() => {})
  }, [activeSessionId])
  // 2s 轮询（会话级 · 仅本组件 re-render）。常驻展示（无折叠）→ 始终轮询 REST 兜底补录
  useEffect(() => {
    load()
    const t = setInterval(load, 2000)
    return () => clearInterval(t)
  }, [load])

  // running 优先排序
  const sortedTasks = [...asyncTasks].sort((a, b) =>
    (a.status === 'running' ? 0 : 1) - (b.status === 'running' ? 0 : 1))
  // 三态统计（对齐子代理运行状况）：进行中=pending+running · 已完成=completed · 已停止=failed+killed
  const runningTasks = sortedTasks.filter((t) => t.status === 'pending' || t.status === 'running')
  const doneTasks = sortedTasks.filter((t) => t.status === 'completed')
  const stoppedTasks = sortedTasks.filter((t) => t.status === 'failed' || t.status === 'killed')
  const renderStat = (label: string, list: BackgroundTaskDto[], filter: 'running' | 'done' | 'stopped') => (
    <div className={`sa-stat ${filter}`} onClick={() => { setStatFilter(filter); setAsyncTasksOpen(true); load() }} title={`查看${label}（${list.length}）`}>
      <span className="sa-count">{list.length}</span>
      <span className="sa-label">{label}</span>
    </div>
  )

  const handleKillTask = async (taskId: string) => {
    try {
      const res = await tasksApi.killTask(taskId)
      if (!res?.success) { showToast('停止失败', 'info'); return }
      showToast('任务已停止', 'success')
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    }
  }
  const handleStopAll = async () => {
    if (!activeSessionId) return
    try {
      const res = await tasksApi.stopAllTasks(activeSessionId)
      if (!res?.success) { showToast('停止失败', 'info'); return }
      showToast('已发送全部停止', 'success')
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    }
  }

  return (
    <>
      <div className="task-group-title async-title">
        <span>异步任务</span>
        <span className="task-actions" onClick={(e) => e.stopPropagation()}>
          <button className="stop-all" onClick={() => { if (confirm('停止当前会话全部异步任务？')) void handleStopAll() }}>全部停止</button>
        </span>
      </div>
      {/* 常驻三态统计（对齐子代理运行状况 · 无任务也显示 0 0 0） */}
      <div className="subagent-stats">
        {renderStat('进行中', runningTasks, 'running')}
        {renderStat('已完成', doneTasks, 'done')}
        {renderStat('已停止', stoppedTasks, 'stopped')}
      </div>
      {asyncTasksOpen && statFilter && (
        <AsyncTasksDialog tasks={sortedTasks} filter={statFilter} onClose={() => { setAsyncTasksOpen(false); setStatFilter(null) }} onKill={handleKillTask} onStopAll={handleStopAll} />
      )}
    </>
  )
}

/** 异步任务清单弹窗（三态过滤 · 对齐子代理运行状况点开查看） */
function AsyncTasksDialog({ tasks, filter, onClose, onKill, onStopAll }: {
  tasks: BackgroundTaskDto[]
  filter: 'running' | 'done' | 'stopped'
  onClose: () => void
  onKill: (taskId: string) => void
  onStopAll: () => void
}) {
  const shown = filter === 'running'
    ? tasks.filter((t) => t.status === 'pending' || t.status === 'running')
    : filter === 'done'
      ? tasks.filter((t) => t.status === 'completed')
      : tasks.filter((t) => t.status === 'failed' || t.status === 'killed')
  const running = shown.filter((t) => t.status === 'running')
  const titleLabel = filter === 'running' ? '进行中' : filter === 'done' ? '已完成' : '已停止'
  return (
    <div className="overlay-backdrop" onClick={onClose}>
      <div className="subagent-modal" onClick={(e) => e.stopPropagation()}>
        <div className="sam-head">
          <span className="sam-title">异步任务 · {titleLabel}<span className="sam-count">{shown.length}</span></span>
          <button type="button" className="sam-close" onClick={onClose} aria-label="关闭">
            <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="M3 3L9 9M9 3L3 9" /></svg>
          </button>
        </div>
        <div className="sam-list">
          <div className="async-tasks-summary">
            <span>⚡ {running.length} 运行中 · 共 {shown.length} 个</span>
            <button className="stop-all" onClick={() => { if (confirm('停止当前会话全部异步任务？')) onStopAll() }}>全部停止</button>
          </div>
          <div className="async-tasks-list">
            {shown.length === 0 ? (
              <div className="sam-empty">暂无</div>
            ) : shown.map((t) => (
              <div key={t.id} className={`async-task-row ${t.status}`}>
                <span className={`at-type ${t.type}`}>{TASK_TYPE_LABEL[t.type] ?? t.type}</span>
                <span className="at-status">{TASK_STATUS_LABEL[t.status] ?? t.status}</span>
                <span className="at-desc" title={t.description}>{t.description}</span>
                {t.status === 'running' && (
                  <button className="at-kill" title="停止任务" onClick={() => onKill(t.id)}>⏹</button>
                )}
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  )
}
