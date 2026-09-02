import { useEffect, useState } from 'react'
import type { SessionContext, SettingsTab } from '@/types'
import { projectApi, type FileNode } from '@/api/projects'
import { workflowApi } from '@/api/workflows'
import { tasksApi } from '@/api/tasks'
import { subagentApi } from '@/api/subagents'
import { ApiError } from '@/api/rest'
import type { AgentTranscriptMessage, Schedule, ScheduleKind, WorkflowRunDto } from '@/api/types'
import { useSchedules } from '@/hooks/useSchedules'
import { cronToHuman } from '@/components/modals/SchedulesPanel'
import { useSubagentStore, type SubagentIdentity } from '@/stores/subagentStore'
import { TeamPanel } from '@/components/right/TeamPanel'
import { TodoPanel } from '@/components/right/TodoPanel'
import { AsyncTasksPanel } from '@/components/right/AsyncTasksPanel'

type RightTab = 'files' | 'tasks' | 'projects'

/** 稳定空身份 map（selector 兜底用 · 避免 `?? {}` 每次新对象触发 zustand 无限重渲染） */
const EMPTY_IDENTITY_MAP: Record<string, SubagentIdentity> = {}

/** 子代理活动时间 HH:MM */
function formatActTime(ts: number): string {
  const d = new Date(ts)
  return `${d.getHours()}:${String(d.getMinutes()).padStart(2, '0')}`
}

/** 子代理卡片（现代规范 · 头像色块 + 状态标签 + 点击展开活动时间线 + 「详细」看完整执行记录） */
function SubagentCard({ id, onKill, sessionId }: {
  id: SubagentIdentity
  onKill?: (id: SubagentIdentity) => void
  sessionId: string | null
}) {
  const [open, setOpen] = useState(false)
  const [detailOpen, setDetailOpen] = useState(false)
  const statusLabel = id.status === 'running' ? '运行中' : id.status === 'done' ? '已完成' : id.status === 'failed' ? '失败' : '已停止'
  return (
    <div className={`subagent-card ${id.status}${open ? ' open' : ''}`} onClick={() => setOpen((v) => !v)}>
      <div className="sc-head">
        <span className="avatar" style={{ background: id.color }}>{id.name.slice(0, 1)}</span>
        <span className="sc-info">
          <span className="name">@{id.name}</span>
          <span className="desc">{id.taskType ?? '子代理任务'}{id.currentTool ? ` · ${id.currentTool}` : ''}</span>
        </span>
        <span className={`sc-status ${id.status}`}>{statusLabel}</span>
        {id.status === 'running' && onKill && (
          <button className="subagent-kill" title="停止任务" onClick={(e) => { e.stopPropagation(); onKill(id) }}>⏹</button>
        )}
        <button className="sc-detail" title="查看完整执行记录" onClick={(e) => { e.stopPropagation(); setDetailOpen(true) }}>详细</button>
        <svg className={`sc-chevron ${open ? 'open' : ''}`} viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="2">
          <path d="M2 4L6 8L10 4" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      </div>
      {open && (
        <div className="sc-timeline">
          {id.activities.map((a, i) => (
            <div key={i} className={`sc-event ${a.type}`}>
              <span className="sc-dot" />
              <span className="sc-text">{a.text}</span>
              <span className="sc-time">{formatActTime(a.ts)}</span>
            </div>
          ))}
        </div>
      )}
      {detailOpen && id.taskId && sessionId && (
        <TranscriptModal sessionId={sessionId} taskId={id.taskId} name={id.name} onClose={() => setDetailOpen(false)} />
      )}
    </div>
  )
}

/** 子代理分组弹窗（三态模块点开 · 现代化：遮罩 + 居中卡片 + 清单滚动） */
function SubagentModal({ filter, subs, onClose, onKill, sessionId }: {
  filter: 'running' | 'done' | 'stopped'
  subs: SubagentIdentity[]
  onClose: () => void
  onKill?: (id: SubagentIdentity) => void
  sessionId: string | null
}) {
  const title = filter === 'running' ? '进行中的子代理' : filter === 'done' ? '已完成的子代理' : '已停止的子代理'
  return (
    <div className="overlay-backdrop" onClick={onClose}>
      <div className="subagent-modal" onClick={(e) => e.stopPropagation()}>
        <div className="sam-head">
          <span className="sam-title">{title}<span className="sam-count">{subs.length}</span></span>
          <button type="button" className="sam-close" onClick={onClose} aria-label="关闭">
            <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="M3 3L9 9M9 3L3 9" /></svg>
          </button>
        </div>
        <div className="sam-list">
          {subs.length === 0 ? (
            <div className="sam-empty">暂无</div>
          ) : (
            subs.map((id) => <SubagentCard key={id.taskId ?? id.name} id={id} onKill={onKill} sessionId={sessionId} />)
          )}
        </div>
      </div>
    </div>
  )
}

/** 定时任务三态弹窗（对齐子代理弹窗样式 · subagent-modal） */
function ScheduleModal({ filter, schedules, onClose }: {
  filter: 'ok' | 'error' | 'none'
  schedules: Schedule[]
  onClose: () => void
}) {
  const shown = filter === 'ok'
    ? schedules.filter((s) => s.lastRunStatus === 'ok')
    : filter === 'error'
      ? schedules.filter((s) => s.lastRunStatus === 'error')
      : schedules.filter((s) => !s.lastRunStatus || s.lastRunStatus === 'none')
  const titleLabel = filter === 'ok' ? '正常' : filter === 'error' ? '异常' : '未运行'
  const [selected, setSelected] = useState<Schedule | null>(null)
  return (
    <div className="overlay-backdrop" onClick={onClose}>
      <div className="subagent-modal" onClick={(e) => e.stopPropagation()}>
        <div className="sam-head">
          <span className="sam-title">定时任务 · {titleLabel}<span className="sam-count">{shown.length}</span></span>
          <button type="button" className="sam-close" onClick={onClose} aria-label="关闭">
            <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="M3 3L9 9M9 3L3 9" /></svg>
          </button>
        </div>
        <div className="sam-list">
          {shown.length === 0 ? (
            <div className="sam-empty">暂无</div>
          ) : shown.map((s) => (
            <div key={s.id} className="schedule-item" title="查看详情" onClick={() => setSelected(s)}>
              <div className="head">
                <span className={`status ${s.lastRunStatus === 'ok' ? 'ok' : s.lastRunStatus === 'error' ? 'error' : 'none'}`}></span>
                <span className="name">{s.name}</span>
                <span className="kind">{s.kind}</span>
              </div>
              <div className="detail">
                <span className={`last-status${s.lastRunStatus === 'error' ? ' err' : ''}`}>{s.lastRunStatus ?? '—'}</span>
                {s.cron ? ` · ${s.cron}` : s.runAt ? ` · ${s.runAt}` : s.intervalSeconds ? ` · 每 ${s.intervalSeconds}s` : ''}
              </div>
            </div>
          ))}
        </div>
      </div>
      {selected && <ScheduleDetailModal schedule={selected} onClose={() => setSelected(null)} />}
    </div>
  )
}

/** 定时任务详情弹窗（点开列表项 → 完整配置/上次运行 · 复用 subagent-modal 弹窗样式） */
function ScheduleDetailModal({ schedule, onClose }: {
  schedule: Schedule
  onClose: () => void
}) {
  const kindLabel: Record<ScheduleKind, string> = { cron: 'Cron', once: '单次', interval: '间隔' }
  const schedText = schedule.kind === 'cron'
    ? (schedule.cron ? cronToHuman(schedule.cron) : '—')
    : schedule.kind === 'once'
      ? (schedule.runAt ?? '—')
      : schedule.intervalSeconds != null ? `每 ${schedule.intervalSeconds} 秒` : '—'
  const statusOk = schedule.lastRunStatus === 'ok' || schedule.lastRunStatus === 'success'
  return (
    <div className="overlay-backdrop" onClick={onClose}>
      <div className="subagent-modal" onClick={(e) => e.stopPropagation()}>
        <div className="sam-head">
          <span className="sam-title">
            {schedule.name}
            <span className="schedule-kind">{kindLabel[schedule.kind]}</span>
            {schedule.lastRunStatus && (
              <span className={`sd-status${statusOk ? ' ok' : schedule.lastRunStatus === 'error' ? ' err' : ''}`}>
                {statusOk ? '正常' : schedule.lastRunStatus === 'error' ? '异常' : schedule.lastRunStatus}
              </span>
            )}
          </span>
          <button type="button" className="sam-close" onClick={onClose} aria-label="关闭">
            <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="M3 3L9 9M9 3L3 9" /></svg>
          </button>
        </div>
        <div className="sam-list sd-list">
          <dl className="sd-rows">
            <div className="sd-row">
              <dt>调度方式</dt>
              <dd>{schedText}</dd>
            </div>
            {schedule.kind === 'cron' && schedule.cron && (
              <div className="sd-row">
                <dt>cron 表达式</dt>
                <dd className="mono">{schedule.cron}</dd>
              </div>
            )}
            <div className="sd-row">
              <dt>上次运行</dt>
              <dd>{schedule.lastRunAt ?? '从未运行'}</dd>
            </div>
            {schedule.description && (
              <div className="sd-row">
                <dt>描述</dt>
                <dd>{schedule.description}</dd>
              </div>
            )}
            {schedule.command && (
              <div className="sd-row">
                <dt>命令（prompt）</dt>
                <dd><pre className="tr-content">{schedule.command}</pre></dd>
              </div>
            )}
          </dl>
        </div>
      </div>
    </div>
  )
}

/** 子代理完整执行记录弹窗（transcript · 消息链渲染：用户 prompt / 助手回复 / 工具调用） */
function TranscriptModal({ sessionId, taskId, name, onClose }: {
  sessionId: string
  taskId: string
  name: string
  onClose: () => void
}) {
  const [msgs, setMsgs] = useState<AgentTranscriptMessage[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    let cancelled = false
    subagentApi.transcript(sessionId, taskId)
      .then((list) => { if (!cancelled) setMsgs(list) })
      .catch((e) => { if (!cancelled) setError(e instanceof ApiError ? e.userMessage() : String(e)) })
    return () => { cancelled = true }
  }, [sessionId, taskId])
  const roleLabel: Record<string, string> = { user: '用户', assistant: '助手', tool: '工具', system: '系统' }
  return (
    <div className="overlay-backdrop" onClick={onClose}>
      <div className="transcript-modal" onClick={(e) => e.stopPropagation()}>
        <div className="sam-head">
          <span className="sam-title">@{name} · 执行记录{msgs ? <span className="sam-count">{msgs.length}</span> : null}</span>
          <button type="button" className="sam-close" onClick={onClose} aria-label="关闭">
            <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="M3 3L9 9M9 3L3 9" /></svg>
          </button>
        </div>
        <div className="sam-list transcript-list">
          {error ? <div className="sam-empty">{error}</div>
            : !msgs ? <div className="sam-empty">加载中…</div>
            : msgs.length === 0 ? <div className="sam-empty">暂无执行记录</div>
            : msgs.map((m, i) => (
              <div key={i} className={`tr-msg ${m.role}${m.isApiError ? ' error' : ''}`}>
                <span className="tr-role">{roleLabel[m.role] ?? m.role}</span>
                {m.content && <pre className="tr-content">{m.content}</pre>}
                {m.toolCalls?.map((t, j) => (
                  <div key={j} className="tr-tool">
                    <span className="tr-tool-name">{t.name}</span>
                    {t.arguments && <pre className="tr-content">{t.arguments}</pre>}
                  </div>
                ))}
                {m.role === 'tool' && m.toolCallId && <span className="tr-toolcall">→ {m.toolCallId}</span>}
              </div>
            ))}
        </div>
      </div>
    </div>
  )
}


interface RightPanelProps {
  sessionContext: SessionContext
  /** 当前激活会话 id（TeamPanel 拉会话 teamContext 用） */
  activeSessionId: string | null
  rightTab: RightTab
  setRightTab: (t: RightTab) => void
  setDiffFile: (name: string | null) => void
  /** 项目文件树点击文件 → 打开真实内容查看（App 处理） */
  onOpenFile: (projectId: string, path: string) => void
  showToast: (msg: string, type?: 'success' | 'info') => void
  /** 打开设置指定 tab（定时任务「+」→ schedules tab） */
  openSettingsAt: (tab: SettingsTab) => void
  flashingProject: string | null
  rollbackFile: (name: string) => void
  confirmFile: (name: string) => void
}

const PlusIcon = ({ size = 12 }: { size?: number }) => (
  <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: size, height: size }}>
    <path d="M6 2.5V9.5M2.5 6H9.5" />
  </svg>
)

// ---- Workflow 运行辅助 ----
/** status 小写值域 → 展示元数据（运行中 ● / 完成 ✅ / 失败 ✗ / 已停止 ⏹） */
const WORKFLOW_STATUS_META: Record<WorkflowRunDto['status'], { dot: string; label: string }> = {
  // 状态色统一 CSS 变量（--accent/--success/--error/--ink-faint）· 与全站主题一致，不硬编码 hex
  running: { dot: 'var(--accent)', label: '运行中' },
  completed: { dot: 'var(--success)', label: '完成' },
  failed: { dot: 'var(--error)', label: '失败' },
  killed: { dot: 'var(--ink-faint)', label: '已停止' },
}
const fmtTokens = (n: number) => (n >= 1000 ? `${(n / 1000).toFixed(1)}k` : String(n))
/** returnValue JSON 摘要（超长截断） */
const summarizeJson = (v: unknown) => {
  if (v == null) return ''
  const s = typeof v === 'string' ? v : JSON.stringify(v)
  return s && s.length > 60 ? `${s.slice(0, 60)}…` : (s ?? '')
}

/** 文件树单节点 · IDE 风格：目录可展开/折叠，文件点击查看 */
function FileTreeNode({
  node,
  depth,
  expandedDirs,
  onToggle,
  onOpen,
}: {
  node: FileNode
  depth: number
  expandedDirs: Set<string>
  onToggle: (path: string) => void
  onOpen: (path: string) => void
}) {
  const isDir = node.type === 'dir'
  const expanded = isDir ? expandedDirs.has(node.path) : false
  const paddingLeft = 8 + depth * 14
  return (
    <>
      <div
        className={`file-tree-node ${isDir ? 'dir' : 'file'}`}
        style={{ paddingLeft }}
        onClick={() => (isDir ? onToggle(node.path) : onOpen(node.path))}
        title={node.path}
      >
        {isDir ? (
          <span className={`chev ${expanded ? 'open' : ''}`}>
            <svg viewBox="0 0 10 10" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: 8, height: 8 }}>
              <path d="M3 4L5 6.5L7 4" />
            </svg>
          </span>
        ) : (
          <span className="dot"></span>
        )}
        <span className={`icon ${isDir ? 'dir' : 'file'}`}>
          {isDir ? (
            <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: 12, height: 12 }}>
              <path d="M2 4.5C2 3.5 2.5 3 3.5 3H5L6 4.5H10.5C11.5 4.5 12 5 12 6V10C12 11 11.5 11.5 10.5 11.5H3.5C2.5 11.5 2 11 2 10V4.5Z" />
            </svg>
          ) : (
            <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: 12, height: 12 }}>
              <path d="M4 2H7L10 5V12H4V2Z" />
            </svg>
          )}
        </span>
        <span className="name">{node.name}</span>
      </div>
      {isDir && expanded && node.children && (
        <div className="file-tree-children">
          {node.children.map((c) => (
            <FileTreeNode
              key={c.path}
              node={c}
              depth={depth + 1}
              expandedDirs={expandedDirs}
              onToggle={onToggle}
              onOpen={onOpen}
            />
          ))}
        </div>
      )}
    </>
  )
}

/** 项目文件树 · IDE 项目结构（目录在前文件在后 · 点击文件查看 diff） */
function ProjectFileTree({
  nodes,
  expandedDirs,
  onToggle,
  onOpen,
}: {
  nodes: FileNode[]
  expandedDirs: Set<string>
  onToggle: (path: string) => void
  onOpen: (path: string) => void
}) {
  if (nodes.length === 0) {
    return <div className="right-empty">该项目暂无文件（非 git 仓库？）</div>
  }
  return (
    <div className="file-tree">
      {nodes.map((n) => (
        <FileTreeNode key={n.path} node={n} depth={0} expandedDirs={expandedDirs} onToggle={onToggle} onOpen={onOpen} />
      ))}
    </div>
  )
}
const FolderSvg = () => (
  <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5">
    <path d="M2 4.5C2 3.5 2.5 3 3.5 3H5L6 4.5H10.5C11.5 4.5 12 5 12 6V10C12 11 11.5 11.5 10.5 11.5H3.5C2.5 11.5 2 11 2 10V4.5Z" />
  </svg>
)
const FileSvg = () => (
  <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5">
    <path d="M2 2H8L12 6V12H2Z" />
    <path d="M8 2V6H12" />
  </svg>
)
const NewFileSvg = () => (
  <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5">
    <circle cx="7" cy="7" r="5" />
    <path d="M7 4V10M4 7H10" />
  </svg>
)

export function RightPanel({
  sessionContext,
  activeSessionId,
  rightTab,
  setRightTab,
  setDiffFile,
  onOpenFile,
  showToast,
  openSettingsAt,
  flashingProject,
  rollbackFile,
  confirmFile,
}: RightPanelProps) {
  const { files, mainProject } = sessionContext
  // 任务 tab 数据：定时任务（真实后端）+ 子代理身份（/topic/tasks 事件登记）
  const schedules = useSchedules()
  const subagentIdentities = useSubagentStore((s) => s.bySession[activeSessionId ?? ''] ?? EMPTY_IDENTITY_MAP)
  const tasksCount = schedules.list.length + Object.keys(subagentIdentities).length

  // Workflow 运行：任务 tab 激活时拉一次 + 4s 轮询（后端 WorkflowController）
  const [workflowRuns, setWorkflowRuns] = useState<WorkflowRunDto[]>([])
  const [workflowLoading, setWorkflowLoading] = useState(false)
  const [expandedRunId, setExpandedRunId] = useState<string | null>(null)
  const [runDetail, setRunDetail] = useState<WorkflowRunDto | null>(null)
  // 子代理三态模块弹窗过滤（进行中/已完成/已停止 · null=关闭）
  const [saFilter, setSaFilter] = useState<'running' | 'done' | 'stopped' | null>(null)
  /** 定时任务三态弹窗过滤（正常/异常/未运行 · null=关闭） */
  const [scheduleFilter, setScheduleFilter] = useState<'ok' | 'error' | 'none' | null>(null)
  /** 停止子代理任务（卡片 ⏹ · /tasks/{id}/kill） */
  const handleKillSubagent = async (id: SubagentIdentity) => {
    if (!id.taskId) return
    try {
      const res = await tasksApi.killTask(id.taskId)
      if (!res?.success) { showToast('停止失败', 'info'); return }
      showToast(`已停止 @${id.name}`, 'success')
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    }
  }
  /** 停止 workflow run（卡 ⏹ · /workflows/runs/{id}/kill） */
  const killRun = async (runId: string) => {
    try {
      const res = await workflowApi.killRun(runId)
      if (!res?.success) { showToast('停止失败', 'info'); return }
      showToast('已停止 workflow', 'success')
      setWorkflowRuns((prev) => prev.map((r) => r.runId === runId ? { ...r, status: 'killed' } : r))
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    }
  }
  useEffect(() => {
    if (rightTab !== 'tasks') return
    let cancelled = false
    const load = () => {
      workflowApi.listRuns()
        .then((list) => {
          if (cancelled) return
          // 内容守卫：轮询数据未变则不 setState（避免新数组引用触发 RightPanel 全量 re-render → 任务 tab 抖动）
          setWorkflowRuns((prev) => (JSON.stringify(prev) === JSON.stringify(list) ? prev : list))
          setWorkflowLoading(false)
        })
        .catch(() => { if (!cancelled) setWorkflowLoading(false) })
    }
    setWorkflowLoading(true)
    load()
    const timer = setInterval(load, 2000)
    return () => { cancelled = true; clearInterval(timer) }
  }, [rightTab])

  /** 点开卡片 → 拉 run 详情（展开 phase 树 + agent 列表）；再点收起 */
  const toggleRunDetail = async (runId: string) => {
    if (expandedRunId === runId) { setExpandedRunId(null); setRunDetail(null); return }
    setExpandedRunId(runId)
    try {
      const detail = await workflowApi.getRun(runId)
      setRunDetail(detail)
    } catch { /* 后端未就绪 / 404：保留列表数据 */ }
  }

  // 单项目模式：直接用 App 传入的 mainProject（会话绑定项目），不叠加真实列表
  const main = mainProject

  // 项目 tab 文件树：拉取当前主项目的 git 文件结构（IDE 风格 · 设计稿 v3）
  const [fileTree, setFileTree] = useState<FileNode[] | null>(null)
  const [fileTreeLoading, setFileTreeLoading] = useState(false)
  const [fileTreeError, setFileTreeError] = useState(false)
  // 展开的目录 path 集合
  const [expandedDirs, setExpandedDirs] = useState<Set<string>>(new Set())
  useEffect(() => {
    let alive = true
    const id = main.id
    if (!id) { setFileTree(null); return }
    setFileTreeLoading(true)
    setFileTreeError(false)
    setFileTree(null)
    projectApi
      .files(id)
      .then((nodes) => { if (alive) { setFileTree(nodes); setFileTreeLoading(false) } })
      .catch(() => { if (alive) { setFileTreeError(true); setFileTreeLoading(false) } })
    return () => { alive = false }
  }, [main.id])

  const toggleDir = (path: string) => {
    setExpandedDirs((prev) => {
      const next = new Set(prev)
      if (next.has(path)) next.delete(path); else next.add(path)
      return next
    })
  }

  return (
    <div className="right">
      <div className="right-tabs">
        <div className={`right-tab ${rightTab === 'files' ? 'active' : ''}`} onClick={() => setRightTab('files')}>
          文件 <span className="count">{files.length}</span>
        </div>
        <div className={`right-tab ${rightTab === 'tasks' ? 'active' : ''}`} onClick={() => setRightTab('tasks')}>
          任务 <span className="count">{tasksCount}</span>
        </div>
        <div className={`right-tab ${rightTab === 'projects' ? 'active' : ''}`} onClick={() => setRightTab('projects')}>
          项目 <span className="count">{mainProject.name ? 1 : 0}</span>
        </div>
      </div>
      <div className="right-body">
        {rightTab === 'files' && (
          <>
            {files.length === 0 ? (
              <div className="right-empty">该会话暂无文件变更</div>
            ) : (
              files.map((f) => (
                <div key={f.name} className="file-row">
                  <span className="file-main" onClick={() => setDiffFile(f.name)}>
                    <span className="icon">{f.isNew ? <NewFileSvg /> : <FileSvg />}</span>
                    <span className="name">{f.name}</span>
                    <span className="stats-line">
                      {f.adds > 0 && <span className="add">+{f.adds}</span>}
                      {f.dels > 0 && <span className="del">−{f.dels}</span>}
                    </span>
                  </span>
                  <span className="file-actions">
                    <button
                      className="file-action-btn rollback"
                      title="回滚此次变更"
                      onClick={(e) => { e.stopPropagation(); rollbackFile(f.name) }}
                    >
                      <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.8" style={{ width: 14, height: 14 }}>
                        <path d="M3.5 8C3.5 5 5.5 3 8 3C9.5 3 10.8 3.7 11.6 4.8" strokeLinecap="round" strokeLinejoin="round" />
                        <path d="M12 2.5V5H9.5" strokeLinecap="round" strokeLinejoin="round" />
                      </svg>
                    </button>
                    <button
                      className="file-action-btn confirm"
                      title="确认此次变更"
                      onClick={(e) => { e.stopPropagation(); confirmFile(f.name) }}
                    >
                      <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="2" style={{ width: 14, height: 14 }}>
                        <path d="M3 8L6.5 11.5L13 4.5" strokeLinecap="round" strokeLinejoin="round" />
                      </svg>
                    </button>
                  </span>
                </div>
              ))
            )}
            {files.length > 0 && <div className="right-hint">↑ 点击任一文件查看 diff · Esc 关闭</div>}
          </>
        )}

        {rightTab === 'tasks' && (
          <>
            {/* 任务模块卡片（独立模块阴影展示 · 对齐输入框 .input-box） */}
            <div className="task-module-card">
              {activeSessionId && <TeamPanel sessionId={activeSessionId} showToast={showToast} />}
            </div>
            <div className="task-module-card">
              {activeSessionId && <TodoPanel sessionId={activeSessionId} showToast={showToast} />}
            </div>
            <div className="task-module-card">
            <div className="task-group-title">子代理运行状况</div>
            {(() => {
              // 子代理身份去重：同一 toolUseId/taskId 双键指向同一对象，按 taskId 去重
              //   （按 name 会误并同名成员——并行探索成员 description 相同 → 3 个只显 2 个）
              const seen = new Set<string>()
              const subs = Object.values(subagentIdentities).filter((id) => {
                const key = id.taskId ?? id.name
                if (seen.has(key)) return false
                seen.add(key)
                return true
              })
              // 常驻三态：无子代理也显示 0 0 0（对齐用户期望 · 不显示「暂无子代理运行」空态）
              // 三态分组：进行中 / 已完成 / 已停止（failed+stopped 归停止）
              const running = subs.filter((id) => id.status === 'running')
              const done = subs.filter((id) => id.status === 'done')
              const stopped = subs.filter((id) => id.status === 'failed' || id.status === 'stopped')
              const renderStat = (label: string, list: SubagentIdentity[], filter: 'running' | 'done' | 'stopped') => (
                <div className={`sa-stat ${filter}`} onClick={() => setSaFilter(filter)} title={`查看${label}（${list.length}）`}>
                  <span className="sa-count">{list.length}</span>
                  <span className="sa-label">{label}</span>
                </div>
              )
              return (
                <>
                  <div className="subagent-stats">
                    {renderStat('进行中', running, 'running')}
                    {renderStat('已完成', done, 'done')}
                    {renderStat('已停止', stopped, 'stopped')}
                  </div>
                  {saFilter && (
                    <SubagentModal
                      filter={saFilter}
                      subs={saFilter === 'running' ? running : saFilter === 'done' ? done : stopped}
                      onClose={() => setSaFilter(null)}
                      onKill={handleKillSubagent}
                      sessionId={activeSessionId}
                    />
                  )}
                </>
              )
            })()}
            </div>

            <div className="task-module-card">
              <AsyncTasksPanel activeSessionId={activeSessionId} showToast={showToast} />
            </div>

            <div className="task-module-card">
              <div className="task-group-title">
                <span>定时任务</span>
                <span className="add-schedule" title="添加定时任务" onClick={() => openSettingsAt('schedules')}>
                  <PlusIcon size={11} />
                </span>
              </div>
              {schedules.loading ? (
                <div className="right-empty">加载中…</div>
              ) : (() => {
                // 定时任务三态（对齐子代理运行状况）：正常=ok · 异常=error · 未运行=none/null
                const ok = schedules.list.filter((s) => s.lastRunStatus === 'ok')
                const err = schedules.list.filter((s) => s.lastRunStatus === 'error')
                const none = schedules.list.filter((s) => !s.lastRunStatus || s.lastRunStatus === 'none')
                const stat = (label: string, list: Schedule[], filter: 'ok' | 'error' | 'none') => (
                  <div className={`sa-stat ${filter}`} onClick={() => setScheduleFilter(filter)} title={`查看${label}（${list.length}）`}>
                    <span className="sa-count">{list.length}</span>
                    <span className="sa-label">{label}</span>
                  </div>
                )
                return (
                  <>
                    <div className="subagent-stats">
                      {stat('正常', ok, 'ok')}
                      {stat('异常', err, 'error')}
                      {stat('未运行', none, 'none')}
                    </div>
                    {scheduleFilter && (
                      <ScheduleModal filter={scheduleFilter} schedules={schedules.list} onClose={() => setScheduleFilter(null)} />
                    )}
                  </>
                )
              })()}
            </div>

            {/* Workflow 运行（REST 拉取 + 4s 轮询） */}
            <div className="task-module-card">
            <div className="task-group-title"><span>Workflow 运行</span></div>
            {workflowLoading ? (
              <div className="right-empty">加载中…</div>
            ) : workflowRuns.length === 0 ? (
              <div className="right-empty">暂无 workflow 运行</div>
            ) : (
              workflowRuns.map((r) => {
                const meta = WORKFLOW_STATUS_META[r.status]
                const phaseIdx = r.currentPhase && r.declaredPhases?.length
                  ? r.declaredPhases.indexOf(r.currentPhase) + 1
                  : null
                const toolCount = (r.agents ?? []).reduce((s, a) => s + (a.toolCount ?? 0), 0)
                const tokenCount = (r.agents ?? []).reduce((s, a) => s + (a.tokenCount ?? 0), 0)
                const expanded = expandedRunId === r.runId
                return (
                  <div
                    key={r.runId}
                    className={`workflow-card ${expanded ? 'expanded' : ''}`}
                    onClick={() => void toggleRunDetail(r.runId)}
                  >
                    <div className="workflow-head">
                      <span className="workflow-dot" style={{ background: meta.dot }} />
                      <span className="workflow-name">{r.workflowName}</span>
                      <span className="workflow-status">{meta.label}</span>
                      {r.status === 'running' && (
                        <button className="workflow-kill" title="停止运行" onClick={(e) => { e.stopPropagation(); void killRun(r.runId) }}>⏹ 停止</button>
                      )}
                    </div>
                    <div className="workflow-meta">
                      {phaseIdx
                        ? `阶段: ${r.currentPhase} (${phaseIdx}/${r.declaredPhases.length})`
                        : `${r.agentCount} agents`}
                    </div>
                    <div className="workflow-stats">
                      {r.agentCount} agents · 工具 {toolCount} · tokens {fmtTokens(tokenCount)}
                    </div>
                    {r.status === 'failed' && r.error && <div className="workflow-error">✗ {r.error}</div>}
                    {r.status === 'completed' && r.returnValue !== undefined && (
                      <div className="workflow-return">返回: {summarizeJson(r.returnValue)}</div>
                    )}
                    {expanded && runDetail && runDetail.runId === r.runId && (
                      <div className="workflow-detail">
                        {runDetail.phases?.length > 0 && (
                          <div className="workflow-phases">
                            {runDetail.phases.map((p, i) => (
                              <div key={i} className={`workflow-phase ${p.status === 'done' ? 'done' : 'running'}`}>
                                {p.status === 'done' ? '✓' : '●'} {p.title}
                              </div>
                            ))}
                          </div>
                        )}
                        {runDetail.agents?.length > 0 && (
                          <div className="workflow-agents">
                            {runDetail.agents.map((a) => (
                              <div key={a.id} className="workflow-agent">
                                <span className="wa-dot" style={{ background: a.status === 'done' ? 'var(--success)' : 'var(--accent)' }} />
                                <span className="wa-label">{a.label ?? 'agent'}</span>
                                <span className="wa-phase">{a.phase ?? ''}</span>
                                <span className="wa-count">
                                  {a.toolCount ? `tools ${a.toolCount}` : ''}
                                  {a.tokenCount ? ` · ${fmtTokens(a.tokenCount)}` : ''}
                                </span>
                              </div>
                            ))}
                          </div>
                        )}
                      </div>
                    )}
                  </div>
                )
              })
            )}
            </div>
          </>
        )}

        {rightTab === 'projects' && (
          <>
            {main.id ? (
              <>
                {/* 单项目模式（设计稿 v3）：项目卡 + 文件树，无主次/无切换/无绑定新项目 */}
                <div className="ctx-label" style={{ paddingLeft: 0 }}>当前项目</div>
                <div className={`main-card single ${flashingProject === main.name ? 'flash' : ''}`}>
                  <div className="icon"><FolderSvg /></div>
                  <div className="info">
                    <div className="name-row">
                      <span className="name">{main.name}</span>
                    </div>
                    <div className="meta">
                      <span>{main.branch}</span>
                      <span style={{ color: 'var(--ink-faint)' }}>·</span>
                      {main.dirty > 0 && (
                        <>
                          <span className="dirty">{main.dirty} 未提交</span>
                          <span style={{ color: 'var(--ink-faint)' }}>·</span>
                        </>
                      )}
                      <span>{main.agents} agents</span>
                    </div>
                  </div>
                  {main.agents > 0 && <div className="status-dot" title="agents 运行中"></div>}
                </div>

                {/* 项目文件树（IDE 项目结构 · 设计稿 v3） */}
                <div className="sub-header">
                  <span>项目文件</span>
                </div>
                {fileTreeLoading ? (
                  <div className="right-empty">加载文件树…</div>
                ) : fileTreeError ? (
                  <div className="right-hint" style={{ color: 'var(--warning)' }}>文件树加载失败</div>
                ) : (
                  <ProjectFileTree
                    nodes={fileTree ?? []}
                    expandedDirs={expandedDirs}
                    onToggle={toggleDir}
                    onOpen={(path) => main.id ? onOpenFile(main.id, path) : undefined}
                  />
                )}
                <div className="right-hint">↑ 点击文件查看 · 目录可展开</div>
              </>
            ) : (
              <div className="right-empty">未绑定项目 · 在输入框上方选择项目开始对话</div>
            )}
          </>
        )}
      </div>
    </div>
  )
}
