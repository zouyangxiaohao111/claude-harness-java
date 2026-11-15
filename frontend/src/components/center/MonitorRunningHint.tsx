import { useEffect, useMemo, useState } from 'react'
import { tasksApi, type BackgroundTaskDto } from '@/api/tasks'

/**
 * 「正在监听」悬浮胶囊（输入框上方，与「子代理运行中」并排）。
 *
 * <h2>WHY</h2>
 * Monitor 任务在后台跑，主会话里只看到一条工具调用回执；用户在工作时不知道
 * 「现在到底在盯什么」。这个胶囊常显正在监听的描述，点开直达右栏「运行」tab。
 *
 * <h2>显示口径</h2>
 * 只统计 {@code type === 'monitor' && status === 'running'}：
 * 一个 → 直接显示监听描述；多个 → 「正在监听 N 项 · 最近一条描述」。
 * 没有运行中的监控时不渲染（Monitor 是偶发能力，不需要常驻入口）。
 */
const POLL_MS = 5000

export function MonitorRunningHint({
  sessionId,
  onOpen,
}: {
  /** 当前会话 id（空串 = 未进入会话，不渲染） */
  sessionId: string
  /** 点击 → 打开右栏「运行」tab */
  onOpen: () => void
}) {
  const [tasks, setTasks] = useState<BackgroundTaskDto[]>([])

  useEffect(() => {
    if (!sessionId) {
      setTasks([])
      return
    }
    let alive = true
    const load = () => {
      tasksApi.list(sessionId)
        .then((list) => {
          if (!alive) return
          const running = (list ?? []).filter((t) => t.type === 'monitor' && t.status === 'running')
          setTasks((prev) => (JSON.stringify(prev) === JSON.stringify(running) ? prev : running))
        })
        .catch(() => {})
    }
    load()
    const timer = setInterval(load, POLL_MS)
    return () => { alive = false; clearInterval(timer) }
  }, [sessionId])

  const title = useMemo(
    () => tasks.map((t) => t.description || '未命名监控').join('\n'),
    [tasks],
  )

  if (!sessionId || tasks.length === 0) return null

  const first = tasks[0].description || '未命名监控'
  const label = tasks.length === 1
    ? `正在监听：${first}`
    : `正在监听 ${tasks.length} 项 · ${first}`

  return (
    <button
      type="button"
      className="subagent-running-hint monitor-running-hint"
      onClick={onOpen}
      title={`${title}\n点开查看与停止`}
      aria-label={`${tasks.length} 个监控运行中，点开查看`}
    >
      <span className="srh-dot monitor" aria-hidden="true" />
      <span className="mrh-label">{label}</span>
      {tasks.length > 1 && <span className="srh-count">{tasks.length}</span>}
      <span className="srh-arrow" aria-hidden="true">›</span>
    </button>
  )
}
