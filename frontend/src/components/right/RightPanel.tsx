import { useEffect, useState } from 'react'
import type { SettingsTab, Project, SessionFile } from '@/types'
import { projectApi, type FileNode } from '@/api/projects'
import { workflowApi } from '@/api/workflows'
import { tasksApi } from '@/api/tasks'
import { subagentApi, type SubagentSummary } from '@/api/subagents'
import { ApiError } from '@/api/rest'
import { subagentColor } from '@/api/types'
import type { Schedule, ScheduleKind, WorkflowRunDto } from '@/api/types'
import { useSchedules } from '@/hooks/useSchedules'
import { CollapsibleGroup } from './CollapsibleGroup'
import { AgentTranscriptView } from './AgentTranscriptView'
import { cronToHuman } from '@/components/modals/SchedulesPanel'
import { useSubagentStore, type SubagentIdentity } from '@/stores/subagentStore'
import { TeamPanel } from '@/components/right/TeamPanel'
import { TodoPanel } from '@/components/right/TodoPanel'
import { AsyncTasksPanel } from '@/components/right/AsyncTasksPanel'
import { useChatStore } from '@/stores/chatStore'

type RightTab = 'files' | 'tasks' | 'projects'

/** 稳定空身份 map（selector 兜底用 · 避免 `?? {}` 每次新对象触发 zustand 无限重渲染） */
const EMPTY_IDENTITY_MAP: Record<string, SubagentIdentity> = {}
/** 稳定空文件列表（文件 tab 零态 selector 兜底 · 避免新数组引用触发无限重渲染） */
const EMPTY_FILES: SessionFile[] = []

/** 子代理列表一页条数（用户 2026-10-21 二次裁定：默认 10 条，可「查看更多」） */
const SA_PAGE_SIZE = 10
/**
 * 历史子代理**拉取**上限（不是渲染上限）。
 * 排序（进行中优先 / 创建时间倒序）与筛选胶囊都需要足够样本；渲染仍按 {@link SA_PAGE_SIZE} 分页。
 */
const HISTORY_FETCH_LIMIT = 50

/** 列表筛选胶囊键（'incomplete' = 失败/已停止/未收尾/未知 的合集） */
type SaFilter = 'all' | 'running' | 'completed' | 'incomplete'
/** 单行结局（归一化：实时 done 与历史 completed 视为同一态） */
type SaKind = 'running' | 'completed' | 'failed' | 'stopped' | 'incomplete' | 'unknown'

const SA_KIND_LABEL: Record<SaKind, string> = {
  running: '进行中',
  completed: '已完成',
  failed: '失败',
  stopped: '已停止',
  incomplete: '未收尾',
  unknown: '未知',
}

/** 统一行模型 · 实时（subagentStore）与历史（transcript 磁盘）混排的唯一形态 */
interface SaRow {
  key: string
  /** 主标题（历史用 description，实时用 name） */
  title: string
  /** 副标题（类型 / 当前工具） */
  meta: string
  kind: SaKind
  /** 创建时间（epoch millis · 缺失为 0，排到最后） */
  ts: number
  live?: SubagentIdentity
  hist?: SubagentSummary
}

/** 结局归一：实时 status 与历史 status 不同源，统一成 SaKind */
function toSaKind(status: string | null | undefined): SaKind {
  switch (status) {
    case 'running': return 'running'
    case 'done':
    case 'completed': return 'completed'
    case 'failed': return 'failed'
    case 'stopped': return 'stopped'
    case 'incomplete': return 'incomplete'
    default: return 'unknown'
  }
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

interface RightPanelProps {
  /** 当前激活会话 id（文件 tab 从 chatStore.changedFiles 取 · TeamPanel 拉 teamContext 用） */
  activeSessionId: string | null
  /** 会话绑定项目（「项目」tab · App 从 sessionProject 取 · 未绑定=空项目 → 未绑定零态） */
  mainProject: Project
  rightTab: RightTab
  setRightTab: (t: RightTab) => void
  /** 改动文件行点击 → 打开真实 diff（App 拉 REST /files/diff 喂 DiffModal） */
  onOpenFileRow: (path: string) => void
  /** 项目文件树点击文件 → 打开真实内容查看（App 处理） */
  onOpenFile: (projectId: string, path: string) => void
  /** [Phase3] 文件树右键「引用到对话」→ 插入 @path 到输入框（App 处理 · 与 @ 补全同效） */
  onQuoteFile: (path: string) => void
  showToast: (msg: string, type?: 'success' | 'info') => void
  /** 打开设置指定 tab（定时任务「+」→ schedules tab） */
  openSettingsAt: (tab: SettingsTab) => void
  flashingProject: string | null
  /** 回滚本会话改动（App 二次确认 + 调 revert REST + 刷新列表） */
  onRollbackFile: (path: string) => void
  /** 收起右栏：图标轨最顶部（文件上方）的面板按钮 */
  onToggleCollapse?: () => void
  /** 折叠态（与左栏同一套做法：不卸载组件，只渲染窄条 + 展开按钮） */
  collapsed?: boolean
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
  onQuote,
}: {
  node: FileNode
  depth: number
  expandedDirs: Set<string>
  onToggle: (path: string) => void
  onOpen: (path: string) => void
  onQuote: (path: string) => void
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
        onContextMenu={(e) => {
          if (isDir) return
          e.preventDefault()
          onQuote(node.path)
        }}
        title={isDir ? node.path : `${node.path} · 左键查看 · 右键引用到对话`}
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
              onQuote={onQuote}
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
  onQuote,
}: {
  nodes: FileNode[]
  expandedDirs: Set<string>
  onToggle: (path: string) => void
  onOpen: (path: string) => void
  onQuote: (path: string) => void
}) {
  if (nodes.length === 0) {
    return <div className="right-empty">该项目暂无文件（非 git 仓库？）</div>
  }
  return (
    <div className="file-tree">
      {nodes.map((n) => (
        <FileTreeNode key={n.path} node={n} depth={0} expandedDirs={expandedDirs} onToggle={onToggle} onOpen={onOpen} onQuote={onQuote} />
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
  activeSessionId,
  mainProject,
  rightTab,
  setRightTab,
  onOpenFileRow,
  onOpenFile,
  onQuoteFile,
  showToast,
  openSettingsAt,
  flashingProject,
  onRollbackFile,
  onToggleCollapse,
  collapsed = false,
}: RightPanelProps) {
  // [2026-10-21] 折叠态：参照左栏（SessionList 的 collapsed 分支）——只渲染窄条 + 展开按钮
  if (collapsed) {
    return (
      <div
        className="right right-rail-collapsed"
        /* 内联兜底：折叠窄条的宽度/方向/背景不依赖样式表（历史上 `.right` 被改成 row-reverse、
           且 `.app.right-collapsed .right` 有 display:none / flex 两条互相覆盖的规则） */
        /* 用户裁定：折叠后**不展示右侧面板**，只在原位置留一个裸按钮（无背景条、无边框） */
        style={{
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'flex-start',
          paddingTop: 12,
          background: 'transparent',
          border: 'none',
          boxShadow: 'none',
          overflow: 'visible',
        }}
      >
        <button
          className="rail-collapse-btn"
          onClick={onToggleCollapse}
          title="展开上下文面板"
          aria-label="展开上下文面板"
        >
          <svg viewBox="0 0 16 14" fill="none" width="15" height="14">
            <rect x="1.1" y="1.1" width="13.8" height="11.8" rx="2.6" stroke="currentColor" strokeWidth="1.35" />
            <line x1="10" y1="1.4" x2="10" y2="12.6" stroke="currentColor" strokeWidth="1.35" strokeLinecap="round" />
          </svg>
        </button>
      </div>
    )
  }

  // 「文件」tab 数据 = 本会话 agent 改动文件（chatStore.changedFiles · files.changed 事件/GET /files 写入 · 无=[]零态）
  const files = useChatStore((s) => s.changedFiles[activeSessionId ?? ''] ?? EMPTY_FILES)
  // 任务 tab 数据：定时任务（真实后端）+ 子代理身份（/topic/tasks 事件登记）
  // [cwd3] 传本面板的 activeSessionId：定时任务创建必须带会话锚（见 useSchedules javadoc）。
  const schedules = useSchedules(activeSessionId)
  /** 右栏对话视图（F2）：点子代理/成员 → 内容区切换成该 agent 的执行记录 */
  const [viewing, setViewing] = useState<{ agentId: string; name: string; subtitle?: string; active: boolean } | null>(null)
  /** 历史子代理（磁盘 transcript 目录 · 切会话重拉）：补上「重启后 /tasks 里没有」的那部分 */
  const [historySubs, setHistorySubs] = useState<SubagentSummary[]>([])
  /**
   * 子代理列表渲染分页（默认 5 条 · 用户 2026-10-21 两次强调「默认 5 条可下一页」）。
   *
   * <p>拉取与渲染分离：一次拉 {@link HISTORY_FETCH_LIMIT} 条（排序/过滤要有足够样本），
   * 渲染只出前 {@code visibleLimit} 条 —— 否则列表会把整段历史一次性铺开。
   */
  const [visibleLimit, setVisibleLimit] = useState(SA_PAGE_SIZE)
  /** 子代理列表筛选胶囊：全部 / 进行中 / 已完成 / 未完成（失败+停止+未收尾+未知） */
  const [saListFilter, setSaListFilter] = useState<SaFilter>('all')
  /** ⤢ 扩大：右栏向左覆盖中间对话区（用户 2026-10-21：不要弹窗） */
  const [expandedView, setExpandedView] = useState(false)
  const subagentIdentities = useSubagentStore((s) => s.bySession[activeSessionId ?? ''] ?? EMPTY_IDENTITY_MAP)
  const tasksCount = schedules.list.length + Object.keys(subagentIdentities).length
  // [F2] 进入 agent 对话视图 → 右栏临时加宽到 420（退出恢复用户原宽度）
  useEffect(() => {
    if (!viewing) return
    const root = document.documentElement
    const prev = root.style.getPropertyValue('--right-w')
    // 进入对话视图 420；用户仍可用 resizer 拖拽（resizer 改的就是这个变量）。
    // ⤢ 全屏走 CSS fixed 覆盖，不再动 grid。
    if (!expandedView) root.style.setProperty('--right-w', '420px')
    return () => {
      if (prev) root.style.setProperty('--right-w', prev)
      else root.style.removeProperty('--right-w')
    }
  }, [viewing, expandedView])

  // 切会话 → 拉该会话的历史子代理（磁盘真源）；失败静默（不影响实时数据）
  useEffect(() => {
    if (!activeSessionId) { setHistorySubs([]); return }
    let cancelled = false
    subagentApi.list(activeSessionId, HISTORY_FETCH_LIMIT)
      .then((list) => { if (!cancelled) setHistorySubs(list ?? []) })
      .catch(() => { if (!cancelled) setHistorySubs([]) })
    return () => { cancelled = true }
  }, [activeSessionId])

  // 切会话 → 筛选与分页回到默认（否则上一会话翻到第 40 条会带过来）
  useEffect(() => {
    setSaListFilter('all')
    setVisibleLimit(SA_PAGE_SIZE)
  }, [activeSessionId])

  // Workflow 运行：任务 tab 激活时拉一次 + 4s 轮询（后端 WorkflowController）
  const [workflowRuns, setWorkflowRuns] = useState<WorkflowRunDto[]>([])
  const [workflowLoading, setWorkflowLoading] = useState(false)
  const [expandedRunId, setExpandedRunId] = useState<string | null>(null)
  const [runDetail, setRunDetail] = useState<WorkflowRunDto | null>(null)
  // [D2] 折叠分组计数（子组件上报）：为 0 只占一行，有数据自动展开
  const [teamCount, setTeamCount] = useState<number>(0)
  const [todoCount, setTodoCount] = useState<number>(0)
  const [asyncCount, setAsyncCount] = useState<number>(0)
  /** 定时任务三态弹窗过滤（正常/异常/未运行 · null=关闭） */
  /** 定时任务详情（点行打开 · 复用已有 ScheduleDetailModal） */
  const [detailSchedule, setDetailSchedule] = useState<Schedule | null>(null)
  /** 停止子代理任务（卡片 ⏹ · /tasks/{id}/kill） */
  const handleKillSubagent = async (id: SubagentIdentity) => {
    if (!id.taskId) return
    try {
      const res = await tasksApi.killTask(id.taskId)
      if (!res?.success) { showToast('停止失败', 'info'); return }
      // [stop-终态乐观] 立刻置 stopped：即使 killed 终态事件丢失，卡片也不再停"运行中"（事件到达时幂等覆盖）
      //   ⚠️ 显式传第三个实参 activeSessionId（`?? ''` 与本面板读取子代理桶的键 bySession[activeSessionId ?? ''] 同源）：
      //   addActivity 的 `sessionId ?? get().sessionId ?? ''` 是给存量调用方的全局单槽回落，本批纪律要求
      //   新调用点一律显式传 sid —— 否则并发会话下单槽可能指向"上一个请求残留的别会话"（比 null 更坏的第三态），
      //   乐观终态会写进别会话桶：卡片所在桶仍停"运行中"，而别会话被误标。
      useSubagentStore.getState().addActivity(id.taskId, { type: 'stopped', text: '已停止', ts: Date.now() }, activeSessionId ?? '')
      showToast(`已停止 @${id.name}`, 'success')
    } catch (e) {
      // [stop-404 幂等] 任务已不存在（已结束/已移除）→ 本地清身份，避免卡片永久"运行中"
      if (e instanceof ApiError && (e.status === 404 || e.status === 409)) {
        // [刀 2b-i] 显式传 sessionId（不再落到 forget 的全局单槽 `sessionId ?? get().sessionId`）：
        //   单槽在并发会话下会把清理打到"上一个请求残留的别会话"桶。`?? ''` 与本面板读取子代理桶的
        //   键（bySession[activeSessionId ?? '']）同源，保证清的就是卡片所在的那个桶。
        // [刀 2b-ii · 409 收敛] 409 = 后端 stopTask 说该任务已非运行态（NOT_RUNNING，
        //   TaskController.java:229-231），本地卡片不该再停在"运行中"。此处选「与 404 同款的本地清理」，
        //   理由：①与 404 出口（任务已不存在 ⇒ 清本地身份）语义同构，都是"后端已无该运行任务 ⇒
        //   本地无 running 可维护"；②不伪造终态类型 —— 后端未告知是 completed 还是 killed，凭空写
        //   'stopped' 会把"已完成"误标成"已停止"（且 addActivity 的终态幂等守卫会挡住轮询随后的精确回写）；
        //   ③该任务的服务端终态记录仍在后端任务清单里可查（异步任务面板照常展示），本地清掉的只是陈旧身份。
        useSubagentStore.getState().forget(id.taskId, activeSessionId ?? '')
        showToast('该任务已结束（已清理状态）', 'info')
        return
      }
      // [本批修正 · 判据收窄] 收敛判据从 `e instanceof ApiError` 收窄为**仅 404/409**（见上分支）。
      //   WHY：api/rest.ts 把**网络层错误**也包成 ApiError(status = 0)（:97-103 的 fetch catch），
      //   把一切 `!res.ok` 包成 ApiError（:105-107，含 400/401/403/500…）。这些情况下后端**可能仍在
      //   正常跑该任务**（网络抖动、鉴权/服务端临时故障），清本地身份 + 弹「该任务已结束（已清理状态）」
      //   是**误导**：卡片闪一下消失、活动时间线被重置，而任务其实还在跑（2s 后轮询又把它登记回来）。
      //   ⛔ 非 404/409 的 ApiError 不静默吞 —— 落到下方原样 toast（保留既有行为）。
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
    // 切会话先清空：否则会拿上一项目（甚至另一项目）的 run 列表闪 2 秒
    setWorkflowRuns([])
    setExpandedRunId(null)
    setRunDetail(null)
    let cancelled = false
    const load = () => {
      // 必须带本会话：后端按该会话项目的 workflow 目录过滤（否则跨项目串台 · 2026-10-21 用户实测）
      workflowApi.listRuns(activeSessionId)
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
  }, [rightTab, activeSessionId])

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

  // 当前 tab 的面板标题与计数（图标轨只有图标，标题放内容区顶部）
  const tabTitle = rightTab === 'files' ? '文件' : rightTab === 'tasks' ? '任务' : '项目'
  const tabCount = rightTab === 'files' ? files.length : rightTab === 'tasks' ? tasksCount : (mainProject.name ? 1 : 0)

  return (
    <div className={`right${expandedView ? ' right-fullscreen' : ''}`}>
      {/* [2026-10-21] 图标轨贴**右边缘**（用户裁定）：导航在右、内容在左，VS Code 活动栏语义 */}
      <div className="right-rail">
        {/* 收起按钮放在**文件上方**（与其它图标同一列、同一个 rail 容器）；无边框无阴影 */}
        {onToggleCollapse && (
          <button
            className="right-rail-btn right-rail-collapse"
            onClick={onToggleCollapse}
            title="收起上下文面板"
            aria-label="收起上下文面板"
          >
            <svg viewBox="0 0 16 14" fill="none" width="15" height="14">
              <rect x="1.1" y="1.1" width="13.8" height="11.8" rx="2.6" stroke="currentColor" strokeWidth="1.35" />
              <line x1="10" y1="1.4" x2="10" y2="12.6" stroke="currentColor" strokeWidth="1.35" strokeLinecap="round" />
            </svg>
          </button>
        )}
        <button
          className={`right-rail-btn${rightTab === 'files' ? ' on' : ''}`}
          onClick={() => setRightTab('files')}
          title="文件"
          aria-label="文件"
        >
          <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5">
            <path d="M3 2.6h6.2L13 6.4V13a.8.8 0 0 1-.8.8H3.8a.8.8 0 0 1-.8-.8V3.4A.8.8 0 0 1 3.8 2.6Z" strokeLinejoin="round" />
            <path d="M9 2.8v3.8h3.8" strokeLinejoin="round" />
          </svg>
        </button>
        <button
          className={`right-rail-btn${rightTab === 'tasks' ? ' on' : ''}`}
          onClick={() => setRightTab('tasks')}
          title="任务"
          aria-label="任务"
        >
          <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5">
            <rect x="2.6" y="2.6" width="10.8" height="10.8" rx="2.2" />
            <path d="M5.4 8.2l1.7 1.7 3.5-3.6" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </button>
        <button
          className={`right-rail-btn${rightTab === 'projects' ? ' on' : ''}`}
          onClick={() => setRightTab('projects')}
          title="项目"
          aria-label="项目"
        >
          <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5">
            <rect x="2.4" y="3.2" width="11.2" height="9.6" rx="1.8" />
            <path d="M2.4 6.2h11.2" strokeLinecap="round" />
          </svg>
        </button>
      </div>
      <div className="right-main">
        {viewing ? (
          <AgentTranscriptView
            sessionId={activeSessionId}
            agentId={viewing.agentId}
            name={viewing.name}
            subtitle={viewing.subtitle}
            active={viewing.active}
            onBack={() => { setViewing(null); setExpandedView(false) }}
            expanded={expandedView}
            onExpand={() => setExpandedView((v) => !v)}
            showToast={showToast}
          />
        ) : (
        <>
        <div className="right-title">
          <span>{tabTitle}</span>
          {tabCount > 0 && <span className="right-title-count">· {tabCount}</span>}
        </div>
        <div className="right-body">
        {rightTab === 'files' && (
          <>
            {files.length === 0 ? (
              <div className="right-empty">该会话暂无文件变更</div>
            ) : (
              files.map((f) => (
                <div key={f.path} className="file-row">
                  <span className="file-main" title="查看真实 diff" onClick={() => onOpenFileRow(f.path)}>
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
                      title="回滚到本会话改动前"
                      onClick={(e) => { e.stopPropagation(); onRollbackFile(f.path) }}
                    >
                      <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.8" style={{ width: 14, height: 14 }}>
                        <path d="M3.5 8C3.5 5 5.5 3 8 3C9.5 3 10.8 3.7 11.6 4.8" strokeLinecap="round" strokeLinejoin="round" />
                        <path d="M12 2.5V5H9.5" strokeLinecap="round" strokeLinejoin="round" />
                      </svg>
                    </button>
                  </span>
                </div>
              ))
            )}
            {files.length > 0 && <div className="right-hint">↑ 点击文件查看本会话真实 diff · Esc 关闭</div>}
          </>
        )}

        {rightTab === 'tasks' && (
          <>
            {/* 任务模块卡片（独立模块阴影展示 · 对齐输入框 .input-box） */}
            {/* [2026-10-21 D2] 六个模块 → 折叠分组：为 0 的组只占一行，有数据自动展开 */}
            <CollapsibleGroup title="TEAM 协作" count={teamCount}>
              {activeSessionId && (
                <TeamPanel
                  sessionId={activeSessionId}
                  showToast={showToast}
                  hideHeader
                  onCount={setTeamCount}
                  onViewMember={(agentId, name, active) => setViewing({ agentId, name, subtitle: active ? '运行中' : '离线', active })}
                />
              )}
            </CollapsibleGroup>
            {/* keepMounted：切换会话要立刻重拉任务（折叠即卸载 ⇒ TodoPanel 不轮询 ⇒ 计数恒 0） */}
            <CollapsibleGroup title="任务清单" count={todoCount} keepMounted>
              {activeSessionId && <TodoPanel sessionId={activeSessionId} showToast={showToast} hideHeader onCount={setTodoCount} />}
            </CollapsibleGroup>
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
              // [D2] 展开态 = 行式列表（用户 2026-10-21：按设计稿，不要三张 0 卡片）
              // 历史项（磁盘 transcript）：实时里已有的按 taskId 去重（两者键不同源，能对上就排除）
              const liveIds = new Set(subs.map((x) => x.taskId).filter(Boolean) as string[])
              const hist = historySubs.filter((h) => !liveIds.has(h.agentId))
              const totalCount = subs.length + hist.length

              // ---- 统一行模型：实时 + 历史混排（用户 2026-10-21 三次反馈的核心）----
              //   ① 颜色：两种来源统一用彩色头像 + 彩色状态徽章（此前实时行只有 7px 灰点）
              //   ② 排序：进行中优先，其余按**创建时间**倒序（历史时间是后端 fileCreatedAt）
              //   ③ 过滤：全部 / 进行中 / 已完成 / 未完成（失败+已停止+未收尾+未知）
              const rows: SaRow[] = []
              for (const id of subs) {
                rows.push({
                  key: `l-${id.taskId ?? id.name}`,
                  title: id.name || '子代理',
                  meta: [id.taskType, id.currentTool].filter(Boolean).join(' · '),
                  kind: toSaKind(id.status),
                  // activities[0] = 启动事件（store 里唯一可靠的创建时间锚）
                  ts: id.activities[0]?.ts ?? 0,
                  live: id,
                })
              }
              for (const h of hist) {
                const title = h.description || h.agentType || h.agentId
                rows.push({
                  key: `h-${h.agentId}`,
                  title,
                  meta: h.agentType || 'agent',
                  kind: toSaKind(h.status),
                  ts: h.createdAt ?? 0,
                  hist: h,
                })
              }
              rows.sort((a, b) => {
                const ra = a.kind === 'running' ? 0 : 1
                const rb = b.kind === 'running' ? 0 : 1
                if (ra !== rb) return ra - rb
                return b.ts - a.ts
              })
              const isIncomplete = (k: SaKind) => k !== 'running' && k !== 'completed'
              const filtered = rows.filter((r) =>
                saListFilter === 'all' ? true
                  : saListFilter === 'running' ? r.kind === 'running'
                  : saListFilter === 'completed' ? r.kind === 'completed'
                  : isIncomplete(r.kind))
              const shown = filtered.slice(0, visibleLimit)
              const chip = (key: SaFilter, label: string, n: number) => (
                <button
                  key={key}
                  className={`todo-chip${saListFilter === key ? ' on' : ''}`}
                  onClick={() => { setSaListFilter(key); setVisibleLimit(SA_PAGE_SIZE) }}
                >
                  {label} {n}
                </button>
              )
              return (
                <CollapsibleGroup title="子代理运行状况" count={totalCount}>
                  {totalCount === 0 ? (
                    <div className="todo-empty">暂无子代理</div>
                  ) : (
                    <>
                      <div className="todo-chips">
                        {chip('all', '全部', rows.length)}
                        {chip('running', '进行中', rows.filter((r) => r.kind === 'running').length)}
                        {chip('completed', '已完成', rows.filter((r) => r.kind === 'completed').length)}
                        {chip('incomplete', '未完成', rows.filter((r) => isIncomplete(r.kind)).length)}
                      </div>
                      {filtered.length === 0 ? (
                        <div className="todo-empty">没有符合条件的子代理</div>
                      ) : (
                        <div className="todo-rows">
                          {shown.map((r) => {
                            const agentId = r.live?.taskId ?? r.hist?.agentId
                            return (
                              <div
                                key={r.key}
                                className={`todo-row${r.kind === 'running' ? ' in-progress' : ''}`}
                                onClick={() => {
                                  if (!agentId) return
                                  setViewing({
                                    agentId,
                                    name: r.live ? r.live.name : (r.hist?.agentType || '子代理'),
                                    subtitle: SA_KIND_LABEL[r.kind],
                                    active: r.kind === 'running',
                                  })
                                }}
                                title="查看该子代理的对话"
                              >
                                <span className="sub-avatar" style={{ background: r.live?.color || subagentColor(r.title) }}>
                                  {r.title.charAt(0).toUpperCase()}
                                </span>
                                <span className="todo-row-main">
                                  <span className="todo-row-title one-line">{r.title}</span>
                                  <span className="todo-row-sub">{r.meta || '子代理'}</span>
                                </span>
                                <span className={`sub-status ${r.kind}`}>{SA_KIND_LABEL[r.kind]}</span>
                                {/* 停止入口随三态弹窗删除后迁到行内（仅运行中且有 taskId） */}
                                {r.kind === 'running' && r.live?.taskId && (
                                  <button
                                    className="subagent-kill"
                                    title="停止任务"
                                    onClick={(e) => { e.stopPropagation(); void handleKillSubagent(r.live!) }}
                                  >⏹</button>
                                )}
                                <span className="todo-row-arrow">›</span>
                              </div>
                            )
                          })}
                          {filtered.length > visibleLimit && (
                            <button
                              className="sub-more"
                              onClick={() => setVisibleLimit((n) => n + SA_PAGE_SIZE)}
                            >查看更多（还有 {filtered.length - visibleLimit} 条）</button>
                          )}
                        </div>
                      )}
                    </>
                  )}
                </CollapsibleGroup>
              )
            })()}

            {/* keepMounted：异步面板负责把 REST 里的 local_agent/teammate 补录进 subagentStore，
                折叠即卸载会让「子代理运行状况」永远为空（2026-10-21 实测） */}
            <CollapsibleGroup title="异步任务" count={asyncCount} keepMounted>
              <AsyncTasksPanel activeSessionId={activeSessionId} showToast={showToast} hideHeader onCount={setAsyncCount} />
            </CollapsibleGroup>

            <CollapsibleGroup
              title="定时任务"
              count={schedules.list.length}
              action={(
                <span className="add-schedule" title="添加定时任务" onClick={() => openSettingsAt('schedules')}>
                  <PlusIcon size={11} />
                </span>
              )}
            >
              {schedules.loading ? (
                <div className="todo-empty">加载中…</div>
              ) : schedules.list.length === 0 ? (
                <div className="todo-empty">暂无定时任务</div>
              ) : (
                <div className="todo-rows">
                  {schedules.list.map((sc) => {
                    const status = sc.lastRunStatus === 'ok' ? '正常' : sc.lastRunStatus === 'error' ? '异常' : '未运行'
                    const dot = sc.lastRunStatus === 'ok' ? 'completed' : sc.lastRunStatus === 'error' ? 'pending' : 'pending'
                    return (
                      <div key={sc.id} className="todo-row" onClick={() => setDetailSchedule(sc)} title="查看定时任务详情">
                        <span className={`todo-row-dot ${dot}`} />
                        <span className="todo-row-main">
                          <span className="todo-row-title">{sc.name}</span>
                          <span className="todo-row-sub">{status}{sc.lastRunAt ? ` · 上次 ${sc.lastRunAt}` : ''}</span>
                        </span>
                        <span className="todo-row-arrow">›</span>
                      </div>
                    )
                  })}
                </div>
              )}
            </CollapsibleGroup>
            {detailSchedule && (
              <ScheduleDetailModal schedule={detailSchedule} onClose={() => setDetailSchedule(null)} />
            )}

            {/* Workflow 运行（REST 拉取 + 4s 轮询） */}
            <CollapsibleGroup title="Workflow 运行" count={workflowRuns.length}>
            {workflowLoading ? (
              <div className="right-empty">加载中…</div>
            ) : workflowRuns.length === 0 ? (
              <div className="todo-empty">暂无 workflow 运行</div>
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
            </CollapsibleGroup>
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
                    onQuote={(path) => onQuoteFile(path)}
                  />
                )}
                <div className="right-hint">↑ 点击查看 · 目录展开 · 文件右键「引用到对话」</div>
              </>
            ) : (
              <div className="right-empty">未绑定项目 · 在输入框上方选择项目开始对话</div>
            )}
          </>
        )}
        </div>
        </>
        )}
      </div>
    </div>
  )
}
