import { memo, useEffect, useState } from 'react'
import type { MouseEvent as ReactMouseEvent, ReactNode } from 'react'
import { isTauri } from '@tauri-apps/api/core'
import type { SettingsTab, Project, SessionFile, RightTab } from '@/types'
import { projectApi, type FileNode } from '@/api/projects'
import { workflowApi } from '@/api/workflows'
import { tasksApi } from '@/api/tasks'
import { subagentApi, type SubagentSummary } from '@/api/subagents'
import { ApiError } from '@/api/rest'
import type { SessionArtifacts, SessionArtifactDto } from '@/api/artifacts'
import type { Schedule, ScheduleKind, WorkflowRunDto } from '@/api/types'
import { useSchedules } from '@/hooks/useSchedules'
import { CollapsibleGroup, RUN_ROW_CAP, RunMoreButton, capRunRows } from './CollapsibleGroup'
import { ArtifactCards } from './ArtifactCards'
import { AgentTranscriptView } from './AgentTranscriptView'
import { cronToHuman } from '@/components/modals/SchedulesPanel'
import { useSubagentStore, type SubagentIdentity } from '@/stores/subagentStore'
import { TeamPanel } from '@/components/right/TeamPanel'
import { TodoPanel } from '@/components/right/TodoPanel'
import { AsyncTasksPanel } from '@/components/right/AsyncTasksPanel'
import { useChatStore } from '@/stores/chatStore'
import { TerminalPanel } from '@/components/right/TerminalPanel'
import type { Client } from '@stomp/stompjs'

/**
 * 抽屉形态新增的 tab：产物 / 来源 / 团队 / 运行 / 终端。
 *
 * <p>`@/types` 的 {@link RightTab} 目前只覆盖图标轨的三个 tab；本组件在内部按更宽的联合渲染抽屉，
 * 新增值通过 {@link RightPanelProps.setRightTab} 的窄类型出口收敛。
 */
type PanelTab = RightTab | 'artifacts' | 'sources' | 'team' | 'run' | 'terminal'

/**
 * 省略 `visibleTabs` 时渲染的 tab 集合（图标轨 / 编程助手 / Work 通用等常规场景）。
 */
const DEFAULT_TABS: PanelTab[] = ['files', 'tasks', 'projects']
/**
 * 抽屉形态的 tab 文案（WORK / CODING 改版 · 2026-10-28）。
 *
 * <p>只有出现在本表里的 tab 才会在抽屉里渲染 —— 抽屉的 tab 集合由 App 的 `visibleTabs` 显式给定
 * （WORK = 项目/产物/变更/来源/团队；CODING 额外一个「运行」），旧图标轨的 files/tasks/projects
 * 语义在此被复用：files→变更（changedFiles）、projects→项目、team→团队、run→运行。
 */
const DRAWER_TAB_LABEL: Partial<Record<PanelTab, string>> = {
  projects: '项目',
  artifacts: '产物',
  files: '变更',
  sources: '来源',
  team: '团队',
  run: '运行',
  terminal: '终端',
}

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
/** 「运行」tab 分组键（每组独立控制「查看更多 / 收起」） */
type RunGroupKey = 'subagent' | 'workflow' | 'async' | 'schedule'

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
  rightTab: PanelTab
  setRightTab: (t: RightTab) => void
  /** 改动文件行点击 → 打开真实 diff（App 拉 REST /files/diff 喂 DiffModal） */
  onOpenFileRow: (path: string) => void
  /** 项目文件树点击文件 → 打开真实内容查看（App 处理） */
  onOpenFile: (projectId: string, path: string) => void
  /** 「产物 / 来源」= 后端轮末异步抽取结果（省略 = 空态，抽到之前也是空） */
  artifacts?: SessionArtifacts
  /** 产物行点击 → 打开该产物（App 处理 · 按类型分发的统一打开函数） */
  onOpenArtifact?: (a: SessionArtifactDto) => void
  /** 产物卡「编辑」→ 打开可编辑预览（仅文本类产物有该入口 · App 处理） */
  onEditArtifact?: (a: SessionArtifactDto) => void
  /** 产物卡「在浏览器中打开」→ 系统浏览器打开 raw 端点（仅 html 产物有该入口 · App 处理） */
  onOpenArtifactInBrowser?: (a: SessionArtifactDto) => void
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
  /**
   * 只渲染这些 tab（省略 = DEFAULT_TABS）。
   *
   * <p>抽屉形态的 tab 集合由 App 显式指定（产物/来源/团队/运行/终端等）。
   */
  visibleTabs?: PanelTab[]
  /**
   * 抽屉形态（WORK / CODING 改版 · 2026-10-28）：横向 tabs + 右上角 ×，absolute 定位
   * `translateX` 开合，**不占三栏列宽**（语义见设计稿 `.right` / `.right-tabs`）。
   *
   * <p>与图标轨（`drawer=false`）互斥；关闭态组件仍挂载（只是滑出屏幕外），
   * 这样子代理/Workflow 轮询与抽屉内其它状态在开合之间保持一致。
   */
  drawer?: boolean
  /** 抽屉是否展开（`drawer=true` 时由 App 控制） */
  drawerOpen?: boolean
  /** 抽屉右上角 × / Esc 关闭（App 把 drawerOpen 置 false） */
  onDrawerClose?: () => void
  /**
   * 「终端」tab 复用的 STOMP 连接（App 的 useChatSocket clientRef）。
   * 省略 = 终端只显示历史与输入、不订阅实时输出（嵌入方/测试）。
   */
  socketClientRef?: { current: Client | null }
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
/** 运行时长：运行中用 now，终态用 updatedAt（均为后端既有字段） */
const fmtRunDuration = (r: WorkflowRunDto) => {
  const ms = Math.max(0, (r.status === 'running' ? Date.now() : r.updatedAt) - r.startedAt)
  if (ms < 1000) return `${ms}ms`
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`
  const s = Math.floor(ms / 1000)
  return `${Math.floor(s / 60)}m ${s % 60}s`
}
/** 结果归一：returnValue 自带布尔 ok 就用它，否则回落状态文案（不编造字段） */
const runResultLabel = (r: WorkflowRunDto) => {
  const v = r.returnValue
  if (v && typeof v === 'object' && 'ok' in v && typeof (v as { ok?: unknown }).ok === 'boolean') {
    return `ok:${(v as { ok: boolean }).ok}`
  }
  return WORKFLOW_STATUS_META[r.status].label
}

/** 文件树右键菜单状态（fixed 定位，左键文件打开、右键菜单） */
type FileMenuState = { x: number; y: number; node: FileNode }
type FileOpDialogState =
  | { kind: 'newFile' | 'newDir'; node: FileNode; title: string }
  | { kind: 'rename'; node: FileNode; title: string }

/** 文件树单节点 · IDE 风格：目录可展开/折叠，文件点击查看 */
function FileTreeNode({
  node,
  depth,
  expandedDirs,
  onToggle,
  onOpen,
  onQuote,
  onMenu,
}: {
  node: FileNode
  depth: number
  expandedDirs: Set<string>
  onToggle: (path: string) => void
  onOpen: (path: string) => void
  onQuote: (path: string) => void
  onMenu: (e: ReactMouseEvent, node: FileNode) => void
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
          e.preventDefault()
          e.stopPropagation()
          onMenu(e, node)
        }}
        title={isDir ? `${node.path} · 右键菜单` : `${node.path} · 左键查看 · 右键菜单`}
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
              onMenu={onMenu}
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
  onMenu,
}: {
  nodes: FileNode[]
  expandedDirs: Set<string>
  onToggle: (path: string) => void
  onOpen: (path: string) => void
  onQuote: (path: string) => void
  onMenu: (e: ReactMouseEvent, node: FileNode) => void
}) {
  if (nodes.length === 0) {
    return <div className="right-empty">该项目暂无文件（非 git 仓库？）</div>
  }
  return (
    <div className="file-tree">
      {nodes.map((n) => (
        <FileTreeNode key={n.path} node={n} depth={0} expandedDirs={expandedDirs} onToggle={onToggle} onOpen={onOpen} onQuote={onQuote} onMenu={onMenu} />
      ))}
    </div>
  )
}

/** 文件树右键菜单（复用 project-context-menu 样式） */
function FileTreeContextMenu({ menu, onAction }: {
  menu: FileMenuState
  onAction: (action: 'newFile' | 'newDir' | 'reveal' | 'quote' | 'copy' | 'refresh' | 'rename' | 'delete') => void
}) {
  return (
    <div
      className="project-context-menu file-tree-menu"
      style={{ left: menu.x, top: menu.y }}
      onMouseDown={(e) => e.stopPropagation()}
      onContextMenu={(e) => e.preventDefault()}
    >
      <div className="ctx-menu-item" onClick={() => onAction('newFile')}>新建文件…</div>
      <div className="ctx-menu-item" onClick={() => onAction('newDir')}>新建文件夹…</div>
      <div className="ctx-menu-item" onClick={() => onAction('reveal')}>在文件资源管理器中显示</div>
      <div className="ctx-menu-divider"></div>
      <div className="ctx-menu-item" onClick={() => onAction('quote')}>添加到对话</div>
      <div className="ctx-menu-divider"></div>
      <div className="ctx-menu-item" onClick={() => onAction('copy')}>复制路径</div>
      <div className="ctx-menu-item" onClick={() => onAction('refresh')}>刷新</div>
      <div className="ctx-menu-item" onClick={() => onAction('rename')}>重命名</div>
      <div className="ctx-menu-item danger" onClick={() => onAction('delete')}>删除</div>
    </div>
  )
}

/** 新建/重命名单行输入弹窗（不用 window.prompt：Tauri WebView 支持不稳定） */
function FileOpDialog({ state, onCancel, onConfirm }: {
  state: FileOpDialogState
  onCancel: () => void
  onConfirm: (value: string) => void
}) {
  const [value, setValue] = useState(state.kind === 'rename' ? state.node.name : '')
  useEffect(() => {
    setValue(state.kind === 'rename' ? state.node.name : '')
  }, [state])
  return (
    <div className="overlay-backdrop" onClick={onCancel}>
      <div className="file-op-dialog" onClick={(e) => e.stopPropagation()}>
        <div className="file-op-title">{state.title}</div>
        <div className="file-op-hint">
          {state.kind === 'rename' ? `重命名 ${state.node.path}` : `位置：${state.node.type === 'dir' ? state.node.path : (state.node.path.split('/').slice(0, -1).join('/') || '项目根')}/`}
        </div>
        <input
          className="settings-input"
          autoFocus
          value={value}
          onChange={(e) => setValue(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') onConfirm(value)
            if (e.key === 'Escape') onCancel()
          }}
          placeholder={state.kind === 'rename' ? '新名称' : (state.kind === 'newDir' ? '文件夹名' : '文件名')}
        />
        <div className="file-op-actions">
          <button className="file-op-btn" onClick={onCancel}>取消</button>
          <button className="file-op-btn primary" onClick={() => onConfirm(value)}>确定</button>
        </div>
      </div>
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

/** 运行 tab 子代理图标（线性人形 · 底色/描色由 .sub-icon.<kind> 决定） */
const SUBAGENT_ICON_SVG = (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
    <circle cx="12" cy="8" r="3.2" />
    <path d="M5 20c.9-3.2 3.6-5 7-5s6.1 1.8 7 5" />
  </svg>
)

/** 抽屉空态图标（线性 SVG · 与项目图标同一 stroke 风格） */
const EMPTY_ICON_SVG: Record<'file' | 'link' | 'team', ReactNode> = {
  file: (
    <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round">
      <path d="M5.2 2.8h6.1L15 6.5v10.7H5.2z" />
      <path d="M11.1 2.9v3.7h3.8" />
    </svg>
  ),
  link: (
    <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round">
      <path d="M8.4 11.6l3.2-3.2" />
      <path d="M9.3 6.6l1.3-1.3a3 3 0 0 1 4.2 4.2l-1.3 1.3" />
      <path d="M10.7 13.4l-1.3 1.3a3 3 0 0 1-4.2-4.2l1.3-1.3" />
    </svg>
  ),
  team: (
    <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="7.4" cy="7" r="2.5" />
      <path d="M2.9 15.4c.5-2.6 2.1-4 4.5-4s4 1.4 4.5 4" />
      <path d="M12.6 5.2a2.4 2.4 0 0 1 0 4.6" />
      <path d="M14 11.6c1.7.3 2.8 1.6 3.2 3.8" />
    </svg>
  ),
}

/** 来源行副标题：从 url 取域名（解析失败回落原串） */
function hostOf(url: string | null): string {
  if (!url) return ''
  try { return new URL(url).hostname } catch { return url }
}

/** 抽屉空态：图标 + 标题 + 说明（可选动作）· 用户 2026-10-28 裁定不要虚线框 */
function DrawerEmpty({ icon, title, desc, action }: {
  icon: 'file' | 'link' | 'team'
  title: string
  desc: string
  action?: ReactNode
}) {
  return (
    <div className="right-empty-rich">
      <span className="re-icon" aria-hidden="true">{EMPTY_ICON_SVG[icon]}</span>
      <div className="re-title">{title}</div>
      <div className="re-desc">{desc}</div>
      {action}
    </div>
  )
}

export const RightPanel = memo(function RightPanel({
  activeSessionId,
  mainProject,
  rightTab,
  setRightTab,
  onOpenFileRow,
  onOpenFile,
  artifacts,
  onOpenArtifact,
  onEditArtifact,
  onOpenArtifactInBrowser,
  onQuoteFile,
  showToast,
  openSettingsAt,
  flashingProject,
  onRollbackFile,
  onToggleCollapse,
  collapsed = false,
  visibleTabs,
  drawer = false,
  drawerOpen = false,
  onDrawerClose,
  socketClientRef,
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
  /** 「运行」tab 各分组是否展开全部（按组独立 · 每组默认只出 5 条 · 进行中不限 · 用户 2026-10-28 裁定） */
  const [runShowAllByGroup, setRunShowAllByGroup] = useState<Record<string, boolean>>({})
  const runShowAll = (key: RunGroupKey) => !!runShowAllByGroup[key]
  const toggleRunShowAll = (key: RunGroupKey) =>
    setRunShowAllByGroup((v) => ({ ...v, [key]: !v[key] }))
  /** ⤢ 扩大：右栏向左覆盖中间对话区（用户 2026-10-21：不要弹窗） */
  const [expandedView, setExpandedView] = useState(false)
  /** 抽屉宽度（可拖拽左缘调整；持久化 localStorage） */
  const [drawerW, setDrawerW] = useState<number>(() => {
    const saved = typeof localStorage !== 'undefined' ? Number(localStorage.getItem('nexusai-right-drawer-w')) : 0
    return Number.isFinite(saved) && saved >= 320 ? saved : 384
  })
  useEffect(() => {
    const appEl = document.querySelector('.app') as HTMLElement | null
    appEl?.style.setProperty('--right-drawer-w', `${drawerW}px`)
    try { localStorage.setItem('nexusai-right-drawer-w', String(drawerW)) } catch { /* ignore */ }
  }, [drawerW])
  const startDrawerResize = (e: ReactMouseEvent) => {
    e.preventDefault()
    const startX = e.clientX
    const startW = drawerW
    const move = (ev: MouseEvent) => {
      const maxW = Math.max(420, Math.round(window.innerWidth * 0.7))
      setDrawerW(Math.max(320, Math.min(maxW, startW - (ev.clientX - startX))))
    }
    const up = () => {
      document.removeEventListener('mousemove', move)
      document.removeEventListener('mouseup', up)
    }
    document.addEventListener('mousemove', move)
    document.addEventListener('mouseup', up)
  }
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
  const [, setTeamCount] = useState<number>(0)
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
    // 「运行」抽屉 tab 与旧「任务」tab 同源（子代理 / Workflow / 异步 / 定时四组共用同一份数据）
    if (rightTab !== 'tasks' && rightTab !== 'run') return
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
  // 文件树写操作后自增 → 重新拉取（保留展开集合）
  const [fileTreeReload, setFileTreeReload] = useState(0)
  const [fileMenu, setFileMenu] = useState<FileMenuState | null>(null)
  const [opDialog, setOpDialog] = useState<FileOpDialogState | null>(null)
  const refreshFileTree = () => setFileTreeReload((v) => v + 1)
  useEffect(() => {
    let alive = true
    const id = main.id
    if (!id) { setFileTree(null); return }
    // 只在「项目」tab 拉树；切走再切回、或抽屉重新打开时都会重拉，避免拿到旧列表。
    if (rightTab !== 'projects') return
    setFileTreeLoading(true)
    setFileTreeError(false)
    setFileTree(null)
    projectApi
      .files(id)
      .then((nodes) => { if (alive) { setFileTree(nodes); setFileTreeLoading(false) } })
      .catch(() => { if (alive) { setFileTreeError(true); setFileTreeLoading(false) } })
    return () => { alive = false }
  }, [main.id, fileTreeReload, rightTab, drawerOpen])

  // 右键菜单：点击空白 / Esc 关闭（菜单自身 onMouseDown stopPropagation）
  useEffect(() => {
    if (!fileMenu) return
    const close = () => setFileMenu(null)
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') close() }
    document.addEventListener('mousedown', close)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', close)
      document.removeEventListener('keydown', onKey)
    }
  }, [fileMenu])

  const toggleDir = (path: string) => {
    setExpandedDirs((prev) => {
      const next = new Set(prev)
      if (next.has(path)) next.delete(path); else next.add(path)
      return next
    })
  }

  const absolutePathOf = (node: FileNode) => {
    const root = (main.path || '').replace(/[\\/]+$/, '')
    return root ? `${root}/${node.path}` : node.path
  }

  const copyEntryPath = async (node: FileNode) => {
    try {
      await navigator.clipboard.writeText(absolutePathOf(node))
      showToast('已复制路径', 'info')
    } catch {
      showToast(absolutePathOf(node), 'info')
    }
  }

  const revealEntry = async (node: FileNode) => {
    const abs = absolutePathOf(node)
    if (isTauri()) {
      try {
        const { revealItemInDir } = await import('@tauri-apps/plugin-opener')
        await revealItemInDir(abs)
      } catch (e) {
        showToast(`打开文件资源管理器失败：${e instanceof Error ? e.message : String(e)}`, 'info')
      }
      return
    }
    // 浏览器/e2e：无桌面壳，复制路径兜底
    try { await navigator.clipboard.writeText(abs) } catch { /* ignore */ }
    showToast('浏览器环境无法打开资源管理器，已复制路径', 'info')
  }

  const deleteEntry = async (node: FileNode) => {
    if (!main.id) return
    if (!window.confirm(`删除 ${node.path}${node.type === 'dir' ? '（目录会递归删除）' : ''}？`)) return
    try {
      await projectApi.deleteEntry(main.id, node.path)
      showToast('已删除', 'success')
      refreshFileTree()
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    }
  }

  const openFileMenu = (e: ReactMouseEvent, node: FileNode) => {
    const MENU_W = 214
    const MENU_H = 236
    setFileMenu({
      x: Math.max(0, Math.min(e.clientX, window.innerWidth - MENU_W)),
      y: Math.max(0, Math.min(e.clientY, window.innerHeight - MENU_H)),
      node,
    })
  }

  const onFileMenuAction = (action: 'newFile' | 'newDir' | 'reveal' | 'quote' | 'copy' | 'refresh' | 'rename' | 'delete') => {
    const node = fileMenu?.node
    if (!node) return
    setFileMenu(null)
    switch (action) {
      case 'newFile': setOpDialog({ kind: 'newFile', node, title: '新建文件' }); break
      case 'newDir': setOpDialog({ kind: 'newDir', node, title: '新建文件夹' }); break
      case 'reveal': void revealEntry(node); break
      case 'quote': onQuoteFile(node.path); showToast('已添加到对话', 'success'); break
      case 'copy': void copyEntryPath(node); break
      case 'refresh': refreshFileTree(); break
      case 'rename': setOpDialog({ kind: 'rename', node, title: '重命名' }); break
      case 'delete': void deleteEntry(node); break
    }
  }

  const confirmFileOp = async (value: string) => {
    const dlg = opDialog
    if (!dlg) return
    if (!main.id) {
      showToast('未绑定项目，无法操作文件', 'info')
      return
    }
    const name = value.trim()
    if (!name || name.includes('/') || name.includes('\\')) {
      showToast('名称不能为空，且不能包含路径分隔符', 'info')
      return
    }
    try {
      if (dlg.kind === 'rename') {
        await projectApi.renameEntry(main.id, dlg.node.path, name)
        showToast('已重命名', 'success')
      } else {
        const base = dlg.node.type === 'dir' ? dlg.node.path : dlg.node.path.split('/').slice(0, -1).join('/')
        await projectApi.createEntry(main.id, base ? `${base}/${name}` : name, dlg.kind === 'newDir' ? 'dir' : 'file')
        showToast(dlg.kind === 'newDir' ? '已新建文件夹' : '已新建文件', 'success')
      }
      setOpDialog(null)
      refreshFileTree()
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    }
  }

  // 当前 tab 的面板标题与计数（图标轨只有图标，标题放内容区顶部）
  // 本场景实际渲染的 tab 集合：显式给了 visibleTabs 就用它，否则 DEFAULT_TABS。
  const tabs = visibleTabs ?? DEFAULT_TABS
  // 实际高亮的 tab：rightTab 不在本场景集合里时（切场景后的首帧）兜底到集合首个。
  const shownTab: PanelTab = tabs.includes(rightTab) ? rightTab : (tabs[0] ?? rightTab)
  /** 抽屉 tab 可能超出 @/types 的 RightTab 联合（types 尚未并入），调用处统一收敛到该 setter */
  const setTab = (t: PanelTab) => setRightTab(t as RightTab)
  const tabTitle = shownTab === 'files' ? '文件'
    : shownTab === 'tasks' ? '任务'
    : '项目'
  const tabCount = shownTab === 'files' ? files.length
    : shownTab === 'tasks' ? tasksCount
    : (mainProject.name ? 1 : 0)

  // ==================================================================
  //  内容区块（2026-10-28 WORK / CODING 抽屉改版）
  //  图标轨（其余场景）与右侧抽屉（WORK / CODING）共用同一份 JSX / 数据 /
  //  停止入口 —— 抽屉只是换了外层容器与 tab 呈现，不复制任何任务逻辑。
  // ==================================================================

  /** 「变更」= 本会话改动文件（点行看真实 diff，行内可回滚） */
  const filesSection = (
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
  )

  /** [产物口径 2026-10-28 用户纠正] 产物 ≠ 修改的文件：只放后端轮末异步抽取出的最终交付物。 */
  const deliverables = artifacts?.deliverables ?? []
  const artifactsSection = (
    <>
      {deliverables.length === 0 ? (
        <DrawerEmpty
          icon="file"
          title="任务结束后自动整理交付物"
          desc="这里只放你最终要的结果文件（报告 / 文档 / 设计稿 / 导出物），不包含中间代码。"
        />
      ) : (
        /* [2026-10-31 产物卡片改版] 3 列网格卡片（按类型图标）· 与正文下方同一套组件/同一套图标/
           同一个点击处理函数（App.openArtifact） */
        <ArtifactCards
          items={deliverables}
          onOpen={onOpenArtifact}
          onEdit={onEditArtifact}
          onOpenInBrowser={onOpenArtifactInBrowser}
          compact
        />
      )}
    </>
  )

  /** 「来源」= 本轮引用的外部资料（后端抽取 · 点击用系统浏览器打开） */
  const sources = artifacts?.sources ?? []
  const openSource = async (a: SessionArtifactDto) => {
    // 无 url 的来源（本地文件型）→ 回落到产物打开通路
    if (!a.url) { onOpenArtifact?.(a); return }
    if (isTauri()) {
      try {
        const { openUrl } = await import('@tauri-apps/plugin-opener')
        await openUrl(a.url)
      } catch (e) {
        showToast(`打开链接失败：${e instanceof Error ? e.message : String(e)}`, 'info')
      }
      return
    }
    window.open(a.url, '_blank', 'noopener')
  }
  const sourcesSection = sources.length === 0 ? (
    <DrawerEmpty
      icon="link"
      title="本轮没有引用外部资料"
      desc="引用 @文件、知识库、网页搜索或用户提供的 URL 后，会在这里列出。"
    />
  ) : (
    <>
      {sources.map((a) => (
        <div key={a.id} className="file-row" title={a.url ?? a.title} onClick={() => void openSource(a)}>
          <span className="file-main">
            <span className="icon">{EMPTY_ICON_SVG.link}</span>
            <span className="file-stack">
              <span className="name">{a.title}</span>
              <span className="file-sub">
                {[hostOf(a.url), a.summary ?? ''].filter(Boolean).join(' · ')}
              </span>
            </span>
          </span>
          <span className="todo-row-arrow">›</span>
        </div>
      ))}
    </>
  )

  /** 「项目」= 现有项目卡 + 项目文件树（右键菜单 / 新建重命名弹窗复用同一份状态） */
  const projectsSection = (
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
              onMenu={openFileMenu}
            />
          )}
          <div className="right-hint">↑ 点击查看 · 目录展开 · 右键新建/重命名/删除</div>
          {fileMenu && (
            <FileTreeContextMenu
              menu={fileMenu}
              onAction={onFileMenuAction}
            />
          )}
          {opDialog && (
            <FileOpDialog
              state={opDialog}
              onCancel={() => setOpDialog(null)}
              onConfirm={confirmFileOp}
            />
          )}
        </>
      ) : (
        <div className="right-empty">未绑定项目 · 在输入框上方选择项目开始对话</div>
      )}
    </>
  )

  /** 「团队」= 现有 TeamPanel（抽屉里是独立 tab，图标轨里是折叠分组） */
  const teamSection = (
    <div className="team-section">
      {activeSessionId && (
        <TeamPanel
          sessionId={activeSessionId}
          showToast={showToast}
          hideHeader
          onCount={setTeamCount}
          onViewMember={(agentId, name, active) => setViewing({ agentId, name, subtitle: active ? '运行中' : '离线', active })}
          renderEmpty={(onCreate) => (
            <DrawerEmpty
              icon="team"
              title="暂无团队"
              desc="创建一个团队后，可以并行分派子代理，成员状态与收件箱会显示在这里。"
              action={<button className="re-action" type="button" onClick={onCreate}>+ 创建团队</button>}
            />
          )}
        />
      )}
    </div>
  )

  /** 任务清单（仅图标轨的「任务」tab 保留；抽屉的「运行」口径不含它） */
  const todoSection = (
    // keepMounted：切换会话要立刻重拉任务（折叠即卸载 ⇒ TodoPanel 不轮询 ⇒ 计数恒 0）
    <CollapsibleGroup title="任务清单" count={todoCount} keepMounted>
      {activeSessionId && <TodoPanel sessionId={activeSessionId} showToast={showToast} hideHeader onCount={setTodoCount} />}
    </CollapsibleGroup>
  )

  /** 子代理运行状况（实时 + 历史混排 · 行内停止 · 点行看执行记录） */
  const subagentSection = (() => {
    // 子代理身份去重：同一 toolUseId/taskId 双键指向同一对象，按 taskId 去重
    //   （按 name 会误并同名成员——并行探索成员 description 相同 → 3 个只显 2 个）
    const seen = new Set<string>()
    const subs = Object.values(subagentIdentities).filter((id) => {
      const key = id.taskId ?? id.name
      if (seen.has(key)) return false
      seen.add(key)
      return true
    })
    // 历史项（磁盘 transcript）：实时里已有的按 taskId 去重（两者键不同源，能对上就排除）
    const liveIds = new Set(subs.map((x) => x.taskId).filter(Boolean) as string[])
    const hist = historySubs.filter((h) => !liveIds.has(h.agentId))
    const totalCount = subs.length + hist.length

    // ---- 统一行模型：实时 + 历史混排 ----
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
    // 抽屉「运行」tab 每组最多 5 条（进行中永远全显示）；图标轨沿用原分页
    const capped = drawer
      ? capRunRows(filtered, (r) => r.kind === 'running', runShowAll('subagent'), RUN_ROW_CAP)
      : null
    const shown = capped ? capped.shown : filtered.slice(0, visibleLimit)
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
                      {/* 状态图标（原型 .ric）：颜色承担状态，右侧只留状态文字 */}
                      <span className={`subagent-icon ${r.kind}`} title={SA_KIND_LABEL[r.kind]} aria-hidden="true">
                        {SUBAGENT_ICON_SVG}
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
                {drawer ? (
                  <RunMoreButton over={capped?.over ?? 0} showAll={runShowAll('subagent')} onToggle={() => toggleRunShowAll('subagent')} />
                ) : filtered.length > visibleLimit && (
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
  })()

  /** 异步任务（后台命令 / 子代理 / 队友 / workflow 监视） */
  const asyncSection = (
    // keepMounted：异步面板负责把 REST 里的 local_agent/teammate 补录进 subagentStore，
    // 折叠即卸载会让「子代理运行状况」永远为空（2026-10-21 实测）
    <CollapsibleGroup title="异步任务" count={asyncCount} keepMounted>
      <AsyncTasksPanel
        activeSessionId={activeSessionId}
        showToast={showToast}
        hideHeader
        onCount={setAsyncCount}
        maxRows={drawer ? RUN_ROW_CAP : 0}
        showAll={runShowAll('async')}
        onToggleShowAll={() => toggleRunShowAll('async')}
      />
    </CollapsibleGroup>
  )

  /** 定时任务（详情弹窗随组渲染）· 定时任务无「进行中」态，全部按历史截断 */
  const { shown: shownSchedules, over: schedulesOver } = capRunRows(
    schedules.list, () => false, runShowAll('schedule'), drawer ? RUN_ROW_CAP : 0,
  )
  const scheduleSection = (
    <>
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
            {shownSchedules.map((sc) => {
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
            <RunMoreButton over={schedulesOver} showAll={runShowAll('schedule')} onToggle={() => toggleRunShowAll('schedule')} />
          </div>
        )}
      </CollapsibleGroup>
      {detailSchedule && (
        <ScheduleDetailModal schedule={detailSchedule} onClose={() => setDetailSchedule(null)} />
      )}
    </>
  )

  /** Workflow 运行（REST + 轮询；运行中可停止，点卡展开阶段树）· 运行中不参与截断 */
  const { shown: shownRuns, over: runsOver } = capRunRows(
    workflowRuns, (r) => r.status === 'running', runShowAll('workflow'), drawer ? RUN_ROW_CAP : 0,
  )
  const workflowSection = (
    <CollapsibleGroup title="Workflow 运行" count={workflowRuns.length}>
      {workflowLoading ? (
        <div className="right-empty">加载中…</div>
      ) : workflowRuns.length === 0 ? (
        <div className="todo-empty">暂无 workflow 运行</div>
      ) : (
        <>
        {shownRuns.map((r) => {
          const meta = WORKFLOW_STATUS_META[r.status]
          const phaseIdx = r.currentPhase && r.declaredPhases?.length
            ? r.declaredPhases.indexOf(r.currentPhase) + 1
            : null
          const toolCount = (r.agents ?? []).reduce((s, a) => s + (a.toolCount ?? 0), 0)
          const tokenCount = (r.agents ?? []).reduce((s, a) => s + (a.tokenCount ?? 0), 0)
          const expanded = expandedRunId === r.runId
          // 收起态一行摘要（后端字段拼装 · 拿不到的段落不显示）
          const stepCount = r.declaredPhases?.length || r.phases?.length || 0
          const summaryParts = [
            ...(r.status === 'running' ? [] : [runResultLabel(r)]),
            ...(stepCount > 0 ? [`${stepCount} 步`] : []),
            fmtRunDuration(r),
          ]
          const summary = `${r.status === 'running' ? '进行中' : '结果'}：${summaryParts.join(' · ')}`
          const hasRaw = r.returnValue !== undefined
          return (
            <div
              key={r.runId}
              className={`workflow-card ${expanded ? ' expanded' : ''}`}
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
                {[
                  phaseIdx ? `阶段 ${r.currentPhase} (${phaseIdx}/${r.declaredPhases.length})` : null,
                  `${r.agentCount} agents`,
                  `工具 ${toolCount}`,
                  `tokens ${fmtTokens(tokenCount)}`,
                ].filter(Boolean).join(' · ')}
              </div>
              <div className="workflow-summary">
                {summary}
                {hasRaw && (expanded ? '（点击收起原始输出）' : '（点击查看原始输出）')}
              </div>
              {r.status === 'failed' && r.error && <div className="workflow-error">✗ {r.error}</div>}
              {/* 原始返回只在展开态显示（收起态用上面的摘要行） */}
              {expanded && hasRaw && (
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
        })}
        <RunMoreButton over={runsOver} showAll={runShowAll('workflow')} onToggle={() => toggleRunShowAll('workflow')} />
        </>
      )}
    </CollapsibleGroup>
  )

  /**
   * 「运行」= 子代理 / Workflow / 异步任务 / 定时任务（用户 2026-10-28 裁定的分组顺序）。
   * 团队有独立 tab，任务清单仅在图标轨的「任务」tab 中保留。
   */
  const runSection = (
    <>
      {subagentSection}
      {workflowSection}
      {asyncSection}
      {scheduleSection}
    </>
  )

  /** 「运行」徽标 = 进行中的子代理 + 进行中的 Workflow（子代理按 taskId/name 去重，双键同对象只算一个） */
  const runningSubagentKeys = new Set(
    Object.values(subagentIdentities)
      .filter((id) => id.status === 'running')
      .map((id) => id.taskId ?? id.name),
  )
  const runActiveCount =
    runningSubagentKeys.size +
    workflowRuns.filter((r) => r.status === 'running').length

  /** 抽屉里实际渲染的 tab（未登记文案的 tab 不出现） */
  const drawerTabs = tabs.filter((t) => DRAWER_TAB_LABEL[t])

  // Esc 关闭抽屉（与原型一致）。抽屉关闭时只是滑出屏外、组件仍挂载，故仅在展开态监听。
  useEffect(() => {
    if (!drawer || !drawerOpen) return
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onDrawerClose?.() }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [drawer, drawerOpen, onDrawerClose])

  // ---- 抽屉形态（WORK / CODING）：横向 tabs + 右上角 ×，absolute + transform 开合 ----
  if (drawer) {
    return (
      <div className={`right right-drawer${drawerOpen ? ' open' : ''}${expandedView ? ' right-fullscreen' : ''}`} style={{ width: drawerW }}>
        <div className="right-drawer-resizer" onMouseDown={startDrawerResize} title="拖拽调整宽度" />
        <div className="right-drawer-tabs">
          {drawerTabs.map((t) => (
            <button
              key={t}
              type="button"
              className={`rtab${shownTab === t ? ' on' : ''}`}
              onClick={() => setTab(t)}
            >
              {DRAWER_TAB_LABEL[t]}
              {t === 'run' && runActiveCount > 0 && <span className="rt-badge">{runActiveCount}</span>}
            </button>
          ))}
          <button className="right-close" type="button" onClick={onDrawerClose} title="关闭" aria-label="关闭">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round">
              <path d="M6 6l12 12M18 6L6 18" />
            </svg>
          </button>
        </div>
        {/* 终端要占满整个抽屉高度（xterm 自适应依赖容器真高度）→ 该 tab 去掉 body 的滚动与内边距 */}
        <div className={`right-drawer-body${shownTab === 'terminal' && !viewing ? ' term-body' : ''}`}>
          {viewing ? (
            <AgentTranscriptView
              sessionId={activeSessionId}
              agentId={viewing.agentId}
              name={viewing.name}
              subtitle={viewing.subtitle}
              active={viewing.active}
              onBack={() => setViewing(null)}
              showToast={showToast}
            />
          ) : shownTab === 'projects' ? projectsSection
            : shownTab === 'files' ? filesSection
            : shownTab === 'artifacts' ? artifactsSection
            : shownTab === 'sources' ? sourcesSection
            : shownTab === 'team' ? teamSection
            : shownTab === 'run' ? runSection
            : shownTab === 'terminal' ? (
              activeSessionId
                // key=sessionId：切会话必须重新挂载（清掉旧会话的 xterm 实例并重拉列表）
                ? <TerminalPanel key={activeSessionId} sessionId={activeSessionId} clientRef={socketClientRef} showToast={showToast} />
                : <div className="right-empty">请先打开一个会话</div>
            )
            : <div className="right-empty">暂无内容</div>}
        </div>
      </div>
    )
  }

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
        {tabs.includes('files') && (
          <button
            className={`right-rail-btn${shownTab === 'files' ? ' on' : ''}`}
            onClick={() => setRightTab('files')}
            title="文件"
            aria-label="文件"
          >
            <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5">
              <path d="M3 2.6h6.2L13 6.4V13a.8.8 0 0 1-.8.8H3.8a.8.8 0 0 1-.8-.8V3.4A.8.8 0 0 1 3.8 2.6Z" strokeLinejoin="round" />
              <path d="M9 2.8v3.8h3.8" strokeLinejoin="round" />
            </svg>
          </button>
        )}
        {tabs.includes('tasks') && (
          <button
            className={`right-rail-btn${shownTab === 'tasks' ? ' on' : ''}`}
            onClick={() => setRightTab('tasks')}
            title="任务"
            aria-label="任务"
          >
            <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5">
              <rect x="2.6" y="2.6" width="10.8" height="10.8" rx="2.2" />
              <path d="M5.4 8.2l1.7 1.7 3.5-3.6" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </button>
        )}
        {tabs.includes('projects') && (
          <button
            className={`right-rail-btn${shownTab === 'projects' ? ' on' : ''}`}
            onClick={() => setRightTab('projects')}
            title="项目"
            aria-label="项目"
          >
            <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5">
              <rect x="2.4" y="3.2" width="11.2" height="9.6" rx="1.8" />
              <path d="M2.4 6.2h11.2" strokeLinecap="round" />
            </svg>
          </button>
        )}
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
        {shownTab === 'files' && filesSection}

        {shownTab === 'tasks' && (
          <>
            {teamSection}
            {todoSection}
            {subagentSection}
            {asyncSection}
            {scheduleSection}
            {workflowSection}
          </>
        )}

        {shownTab === 'projects' && projectsSection}
        </div>
        </>
        )}
      </div>
    </div>
  )
})
