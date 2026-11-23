import { useEffect, useMemo, useState, type CSSProperties } from 'react'
import { createPortal } from 'react-dom'
import { isTauri } from '@tauri-apps/api/core'
import type { SessionDto } from '@/api/types'
import type { ToastType } from '@/types'
import { compareSessions, compareGroups, sortSessionsWithPinned } from '@/utils/sessionOrder'
import { computeDerivedChildCounts, computeFamilyColors } from './sessionFamily'

/** IM 会话块默认展示条数（超出折叠成「查看全部」）—— 该块数量会长，全展开会撑爆左栏。 */
const IM_BLOCK_PREVIEW = 3

/**
 * 左侧栏 · 按项目路径分组，每路径下挂该项目的会话列表。
 * 组标题 = 项目路径（可折叠），会话行 = 运行状态点 + 标题 + 时间。
 */

const PlusIcon = ({ size = 12 }: { size?: number }) => (
  <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: size, height: size }}>
    <path d="M6 2.5V9.5M2.5 6H9.5" />
  </svg>
)
const ChevronIcon = ({ open }: { open: boolean }) => (
  <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: 10, height: 10, transform: open ? 'rotate(90deg)' : 'none', transition: 'transform 120ms var(--ease)', flexShrink: 0 }}>
    <path d="M4 2L8 6L4 10" />
  </svg>
)
const FolderIcon = () => (
  <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: 15, height: 15, flexShrink: 0 }}>
    <path d="M2 4.5C2 3.5 2.5 3 3.5 3H5L6 4.5H10.5C11.5 4.5 12 5 12 6V10C12 11 11.5 11.5 10.5 11.5H3.5C2.5 11.5 2 11 2 10V4.5Z" />
  </svg>
)

interface SessionListProps {
  sessions: SessionDto[]
  activeSession: string
  switchSession: (id: string) => void
  /** projectId → 项目路径（组标题用） */
  projectPathFor?: (projectId: string | null) => string
  /** projectId → 项目名（兜底，路径缺失时用） */
  projectNameFor?: (projectId: string | null) => string
  /**
   * projectId → 项目**注册时间**（`ProjectDto.createdAt` 的 ISO 串）。
   *
   * <p>分组标题序的唯一依据（注册先后，升序）：2026-11-10 用户裁定 —— 组间序排完**不再动**，
   * 只有组内会话序按 updatedAt 动。省略 / 返回 undefined = 该组排在有值之后（兜底组键升序）。
   */
  projectCreatedAtFor?: (projectId: string | null) => string | undefined
  onCreateInProject?: (projectId: string | null) => void
  onCreateSession?: () => void
  /** 点击左栏「技能市场」入口 → 打开技能市场弹窗（App 持有 showMarket state） */
  onOpenAgentMarket?: () => void
  /** 点击左栏「知识库」入口 → 打开知识库/记忆（App 处理，暂占位） */
  onOpenKnowledgeBase?: () => void
  /** 添加工作区（新项目工作组 · App 用本地文件夹选择注册，可添加多个） */
  onAddWorkspace?: () => void
  onOpenSettings?: () => void
  /** 删除会话（App 处理：后端删 + 本地清理 + 切换） */
  onDeleteSession?: (id: string) => void
  /** 复制会话（App 处理：以最后一条消息为分叉点分叉出新会话） */
  onDuplicateSession?: (id: string) => void
  /** 提示（项目分组右键菜单在浏览器环境的兜底文案用；省略 = 静默） */
  showToast?: (msg: string, type?: ToastType) => void
  /** 运行中会话 id 集合（有 stream = 运行中 · 蓝点 ongoing） */
  runningSessionIds?: Set<string>
  /** 等待权限/提问的会话 id 集合（permissionQueue 中 · 橙红闪烁，优先于运行点） */
  pendingSessionIds?: Set<string>
  /** 运行完成且未读的会话 id 集合（静止绿点 · 切到该会话即清除） */
  doneUnreadIds?: Set<string>
  /**
   * [peer-badge] 每会话待处理投递数（sessionId → pending）。
   *
   * <p>数据来自全局 `peerInboxStore.countsBySession`（聚合 topic 的 `pendingCount` +
   * `GET /peer-inbox/counts` 对账）。省略 / 空对象 ⇒ 不渲染任何行内角标（旧宿主零改动）。
   * <p>⚠️ 口径只含 pending：declined/expired/accepted 不计（由 store 保证，组件不重算）。
   */
  peerPendingCounts?: Record<string, number>
  /**
   * [peer-badge] 全局待处理总数 = 左栏顶部汇总（N = `peerInboxStore.totalPending`）。
   * 0 / 省略 ⇒ 整块不渲染。
   */
  peerTotalPending?: number
  /**
   * [peer-badge] 点击全局汇总 → App 决策：0 不弹 / 1 直接跳会话 / ≥2 展开会话级列表。
   * 汇总本身不再直接开面板（App 内部按计数决定）。
   */
  onOpenPeerInbox?: () => void
  /**
   * [peer-badge] 点击某会话行内角标 → 直接跳该会话并定位首条 pending 卡。
   * 未提供时退回 `switchSession`（旧宿主/旧测试零改动）。
   */
  onOpenPeerPending?: (sessionId: string) => void
  /** 重命名会话（App 调 sessionApi.update + 本地同步） */
  onRenameSession?: (id: string, title: string) => void
  /** 置顶 / 取消置顶（App 调 sessionApi.update({ pinned }) + 本地同步） */
  onPinSession?: (id: string, pinned: boolean) => void
  /**
   * 点击左栏「IM 频道」入口 → 主区换到 IM 频道页（App 持有 workspacePage）。
   *
   * <p>位置与「定时任务」同层（都在「新建会话」之后）—— 两者都是能力入口，
   * 不是会话，所以不参与会话排序/筛选。
   */
  onOpenIm?: () => void
  /** IM 频道页是否当前激活（导航项高亮） */
  imActive?: boolean
  /**
   * 有 IM 接线的会话 id 集合 —— 用于左栏「IM 会话」块。
   *
   * <p>数据来自 {@code /api/v1/channels/bindings}（V255）。为空/省略 ⇒ 不渲染该块。
   * <p>⚠️ 与「置顶」的关系（2026-11-23 用户裁定）：**允许重叠** —— 已置顶的 IM 会话
   * 同时在置顶块和 IM 块出现（行首金色实心星表示已置顶）；只有普通项目分组做去重。
   */
  imSessionIds?: Set<string>
  /** 折叠/展开左栏（App 持有状态 · 按钮在左栏头部三条横线） */
  onToggleCollapse?: () => void
  /** 已折叠：只渲染窄 rail（展开按钮 + 新建）· 对齐 deepseek-harness 收起留 rail、永不消失 */
  collapsed?: boolean
  /**
   * 会话列表**平铺**（不按项目分组）。
   *
   * <p>为真时：会话**不渲染分组标题**（无项目折叠头、无「在该项目内新建」+），
   * 且**不渲染「工作区」标签与「添加工作区」入口**。
   * 其余（技能市场 / 知识库 / ＋新会话 / 会话行本身）与常规行为一致。
   *
   * <p>为 false / 省略 = 原有行为逐字节不变。
   */
  flatHistory?: boolean
  /**
   * 左栏改版。
   *
   * <p>为真时：磨玻璃材质、分区标题文案「任务」（右侧搜索 + 添加）、底部用户区
   * （头像 + 本地用户 + 齿轮 → {@link SessionListProps.onOpenSettings}）、隐藏滚动条。
   * 省略 = 原有行为逐字节不变。
   */
  workspaceRedesign?: boolean
  /** 点击「任务」区标题右侧的搜索按钮 → 打开全局搜索面板（App 处理） */
  onOpenSearch?: () => void
  /**
   * 点击左栏「定时任务」入口 → 主区切到定时任务页（App 持有 workspacePage state）。
   *
   * <p>只在 {@link SessionListProps.workspaceRedesign} 语义下渲染；省略 = 不渲染，
   * 非改版场景逐字节不变。
   */
  onOpenSchedules?: () => void
  /** 当前主区是否停在定时任务页（导航项高亮 · App 传入 workspacePage === 'schedules'） */
  schedulesActive?: boolean
  /**
   * 项目分组**默认折叠**。
   *
   * <p>为真时：初始全部组折叠（不是全展开），但**当前 activeSession 所在的组自动展开**
   * （保证当前会话可见；新建会话后其所在组同样自动展开）。用户点组标题可再手动展开/折叠。
   * 为 false / 省略 = 原有「全部展开」口径，flatHistory 也不受影响。
   */
  groupsDefaultCollapsed?: boolean
}

/** flatHistory 下的单一隐式组键（不渲染组标题，故该键不出现在界面上）。 */
const FLAT_GROUP_KEY = '__flat__'

/** 相对时间标签：今天 / 昨天 / 前天 / N天前（≤7）/ 月-日（更早） */
function formatRelativeTime(iso?: string): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const now = new Date()
  const startToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const target = new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()
  const days = Math.round((startToday - target) / 86_400_000)
  if (days <= 0) return '今天'
  if (days === 1) return '昨天'
  if (days === 2) return '前天'
  if (days <= 7) return `${days}天前`
  return `${d.getMonth() + 1}/${d.getDate()}`
}

/** 运行时长标签：m:ss（<1h）/ h:mm:ss。 */
function formatRunElapsed(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000))
  const m = Math.floor(total / 60)
  const h = Math.floor(m / 60)
  const ss = String(total % 60).padStart(2, '0')
  return h > 0 ? `${h}:${String(m % 60).padStart(2, '0')}:${ss}` : `${m}:${ss}`
}

export function SessionList({ sessions, activeSession, switchSession, projectPathFor, projectNameFor, projectCreatedAtFor, onCreateInProject, onCreateSession, onAddWorkspace, onDeleteSession, onRenameSession, onPinSession, runningSessionIds, pendingSessionIds, doneUnreadIds, peerPendingCounts, peerTotalPending, onOpenPeerInbox, onOpenPeerPending, onOpenAgentMarket, onOpenKnowledgeBase, onToggleCollapse, collapsed, flatHistory, workspaceRedesign, onOpenSettings, onOpenSearch, onOpenSchedules,
  onOpenIm,
  imActive,
  imSessionIds, schedulesActive, groupsDefaultCollapsed, onDuplicateSession, showToast }: SessionListProps) {
  const [collapsedGroups, setCollapsedGroups] = useState<Set<string>>(new Set())
  // 默认折叠口径：只记录**用户/自动展开过**的组（collapsedGroups 的反面）。
  // 两套 state 并存而不是合成一个 Map：默认展开与默认折叠是两种语义，用一个布尔直接翻转
  // 会让「用户手动展开的组」在模式切换时被误判成「用户手动折叠」，语义丢失。
  const [expandedGroups, setExpandedGroups] = useState<Set<string>>(new Set())
  // 会话操作菜单：menuId（打开的菜单）+ renameId/renameDraft（重命名输入）
  const [menuId, setMenuId] = useState<string | null>(null)
  const [renameId, setRenameId] = useState<string | null>(null)
  const [renameDraft, setRenameDraft] = useState('')
  /** 项目分组标题右键菜单（x/y 视口坐标 + 该组项目路径） */
  const [groupMenu, setGroupMenu] = useState<{ x: number; y: number; path: string } | null>(null)
  // [session-family] 运行 / 置顶 / 派生 / IM 各块的折叠态（默认展开；与项目分组一样只存组件本地，不持久化）
  const [runningOpen, setRunningOpen] = useState(true)
  const [pinnedOpen, setPinnedOpen] = useState(true)
  const [derivedOpen, setDerivedOpen] = useState(true)
  const [imOpen, setImOpen] = useState(true)
  /** 派生块内按「源」分组：已收起的源 id 集合（默认全部展开）。 */
  const [collapsedFamilyRoots, setCollapsedFamilyRoots] = useState<Set<string>>(new Set())
  /** 悬停的家族根 id：同族行一起高亮（跨块 —— 置顶里的创建者 ↔ 派生块里的子会话）。 */
  const [hoverFamily, setHoverFamily] = useState<string | null>(null)
  /** [run-block] 运行块内每会话的时长起点（进入集合时记，离开即清）。 */
  const [runSince, setRunSince] = useState<Map<string, number>>(new Map())
  /** [run-block] 每秒推进一次，让时长走动。 */
  const [, setRunTick] = useState(0)

  // 右键菜单：点击空白 / Esc 关闭（菜单自身 onMouseDown stopPropagation）
  useEffect(() => {
    if (!groupMenu) return
    const close = () => setGroupMenu(null)
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') close() }
    document.addEventListener('mousedown', close)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', close)
      document.removeEventListener('keydown', onKey)
    }
  }, [groupMenu])

  /** 「在文件资源管理器中显示」：桌面壳走 opener；浏览器/e2e 复制路径兜底（不静默无反应） */
  const revealGroupPath = async () => {
    const menu = groupMenu
    if (!menu) return
    setGroupMenu(null)
    if (isTauri()) {
      try {
        const { revealItemInDir } = await import('@tauri-apps/plugin-opener')
        await revealItemInDir(menu.path)
      } catch (e) {
        showToast?.(`打开文件资源管理器失败：${e instanceof Error ? e.message : String(e)}`, 'info')
      }
      return
    }
    try { await navigator.clipboard.writeText(menu.path) } catch { /* ignore */ }
    showToast?.('浏览器环境无法打开资源管理器，已复制路径', 'info')
  }

  // ⋯ 菜单点外部收起：命中菜单或菜单按钮本身不关（按钮自己负责 toggle），其余任意
  // mousedown 都关闭 —— 此前没有任何 document 级监听，菜单会一直挂在界面上。
  useEffect(() => {
    if (menuId == null) return
    const onDocMouseDown = (e: MouseEvent) => {
      const el = e.target as Element | null
      if (el && typeof el.closest === 'function' && el.closest('.session-menu, .session-menu-btn')) return
      setMenuId(null)
    }
    document.addEventListener('mousedown', onDocMouseDown)
    return () => document.removeEventListener('mousedown', onDocMouseDown)
  }, [menuId])

  /** 当前会话所在组键（无会话 = null）。默认折叠口径下用它自动展开，保证当前会话可见。 */
  const activeGroupKey = useMemo(() => {
    const s = sessions.find((x) => x.id === activeSession)
    if (!s) return null
    return flatHistory ? FLAT_GROUP_KEY : (s.mainProjectId ?? '__unbound__')
  }, [sessions, activeSession, flatHistory])

  // [run-block 2026-11-23 用户认可设计稿] 「正在运行」块 = 运行中 ∪ 等待权限。
  //   与下方各视图块允许重叠（不去重、不隐藏）；为空 ⇒ 整块不渲染。排序与分组内序同口径。
  const runningSessions = useMemo(
    () => sessions
      .filter((s) => runningSessionIds?.has(s.id) === true || pendingSessionIds?.has(s.id) === true)
      .sort(compareSessions),
    [sessions, runningSessionIds, pendingSessionIds],
  )
  // 时长起点：仍在集合里沿用旧起点，离开即丢。内容不变时返回旧 Map（不触发重渲）。
  useEffect(() => {
    setRunSince((prev) => {
      const now = Date.now()
      const next = new Map<string, number>()
      for (const s of runningSessions) next.set(s.id, prev.get(s.id) ?? now)
      let changed = prev.size !== next.size
      if (!changed) {
        for (const [id, ts] of next) { if (prev.get(id) !== ts) { changed = true; break } }
      }
      return changed ? next : prev
    })
  }, [runningSessions])
  // 有运行会话时才开 1s 定时器（空块不耗电）。
  useEffect(() => {
    if (runningSessions.length === 0) return
    const timer = window.setInterval(() => setRunTick((v) => v + 1), 1000)
    return () => window.clearInterval(timer)
  }, [runningSessions.length])

  // 默认折叠：当前会话所在组自动展开。
  // 依赖 = activeSession（会话 id）+ activeGroupKey，刻意**不含 sessions 引用**：
  //   · 轮询/重排换 sessions 数组不会反复顶开用户手动折叠的组；
  //   · 切会话 / 新建会话（activeSession id 变化）一定触发 —— 新建会话即使落在**同一组**
  //     （activeGroupKey 不变）也能把该组展开。
  useEffect(() => {
    if (!groupsDefaultCollapsed || flatHistory || !activeGroupKey) return
    setExpandedGroups((prev) => (prev.has(activeGroupKey) ? prev : new Set(prev).add(activeGroupKey)))
  }, [groupsDefaultCollapsed, flatHistory, activeGroupKey, activeSession])

  // [2026-11-01 用户二次裁定] 收起 = **完全隐藏**（不是窄 rail）。
  //   展开入口已移到顶栏红绿灯右侧（TitleBar 的 .topbar-side-toggle）⇒ 这里不再渲染任何东西：
  //   此前保留的 `+` / 定时任务 / 技能市场 / 知识库 四个按钮在 0 宽列里仍会露出一半（用户实测「左边还有 + 等残留」）。
  if (collapsed) {
    return null
  }

  // [V283] 置顶会话从项目分组中取出，单独渲染在最前的「置顶」块（跨项目）。
  //   [session-family] 标题可点击折叠（默认展开）；置顶独占，不再出现在派生 / IM / 项目分组。
  //   非置顶会话走「派生 / IM / 项目分组」三块（见下方 projectSessions 的排除规则）。
  // IM 会话块：有接线的会话。
  //   [session-family 2026-11-23] 不再排除置顶 —— 视图块之间允许重叠（置顶的 IM 会话
  //   在置顶块与 IM 块都出现，行首显示金色实心星表示已置顶）。
  const [imExpanded, setImExpanded] = useState(false)
  const imBlockSessions = useMemo(
    () => (imSessionIds && imSessionIds.size > 0
      ? sortSessionsWithPinned(sessions.filter((s) => imSessionIds.has(s.id)))
      : []),
    [sessions, imSessionIds],
  )

  // [run-badge 2026-11-23] 分组标题右侧的「N 运行中 / N 待处理」文字角标（方案 A）：
  //   组收起时看不到组内会话行，角标让用户一眼找到哪个组里有在跑 / 在等权限的任务。
  //   口径与行内 status-dot 一致：pending 优先于 running —— 同一会话等待权限时只算「待处理」，
  //   避免被两个角标重复计数。0 不渲染；收起 / 展开都显示（标题行一直在）。
  const countPending = (list: SessionDto[]) =>
    pendingSessionIds && pendingSessionIds.size
      ? list.reduce((n, s) => (pendingSessionIds.has(s.id) ? n + 1 : n), 0)
      : 0
  const countRunning = (list: SessionDto[]) =>
    runningSessionIds && runningSessionIds.size
      ? list.reduce((n, s) => (runningSessionIds.has(s.id) && !pendingSessionIds?.has(s.id) ? n + 1 : n), 0)
      : 0
  const renderRunBadges = (list: SessionDto[]) => {
    const running = countRunning(list)
    const pending = countPending(list)
    if (running <= 0 && pending <= 0) return null
    return (
      <>
        {running > 0 && (
          <span className="run-badge" title={`${running} 个会话正在运行`}>
            <span className="dot" aria-hidden="true" />
            {running} 运行中
          </span>
        )}
        {pending > 0 && (
          <span className="run-badge pending" title={`${pending} 个会话等待权限/回答`}>
            <span className="dot" aria-hidden="true" />
            {pending} 待处理
          </span>
        )}
      </>
    )
  }

  const pinnedSessions = sessions.filter((s) => s.pinned === true)
  const unpinnedSessions = sessions.filter((s) => s.pinned !== true)

  // [session-family] 家族色与派生数（纯展示，不参与排序/权限）。
  //   家族 = 沿 derivedFromSessionId 上溯到根；同族同色，两个根两种色。
  const familyColors = useMemo(() => computeFamilyColors(sessions), [sessions])
  const derivedChildCounts = useMemo(() => computeDerivedChildCounts(sessions), [sessions])
  const sessionById = useMemo(() => new Map(sessions.map((s) => [s.id, s])), [sessions])
  // 「派生」块 = 全部家族成员（含置顶 / IM 的成员）。
  //   ⚠️ 三个视图块之间**允许重叠**：置顶的成员同时出现在置顶块与派生块、IM 的成员同时出现在
  //   IM 块与派生块 —— 块回答的是不同问题；只有下面的普通项目分组才做整体去重。
  const derivedSessions = sessions
    .filter((s) => familyColors.has(s.id))
    .sort(compareSessions)
  // 派生块内部按「源」分组：每个家族根一组，组内是它派生出的会话（根自己渲染成组标题）。
  //   组序 = 组内最近更新时间倒序（与项目分组同一口径）；组可单独展开/收起。
  const familyGroups = (() => {
    const byRoot = new Map<string, { rootId: string; color: string; members: SessionDto[]; latest: number }>()
    for (const s of derivedSessions) {
      const fam = familyColors.get(s.id)
      if (!fam) continue
      const group = byRoot.get(fam.rootId) ?? { rootId: fam.rootId, color: fam.color, members: [], latest: 0 }
      group.members.push(s)
      const ts = Date.parse(s.updatedAt ?? s.createdAt ?? '')
      group.latest = Math.max(group.latest, Number.isNaN(ts) ? 0 : ts)
      byRoot.set(fam.rootId, group)
    }
    return [...byRoot.values()].sort((a, b) => b.latest - a.latest)
  })()
  // 项目分组只放「未置顶 + 非 IM + 非家族」——置顶 / IM / 家族已在上面的视图块里，不再重复。
  //   ⚠️ flatHistory 是「平铺、无分组标题」的旧口径：不启用三块拆分，会话全部留在平铺列表里。
  const projectSessions = flatHistory
    ? unpinnedSessions
    : sessions.filter((s) => s.pinned !== true && !(imSessionIds?.has(s.id)) && !familyColors.has(s.id))

  // 按 mainProjectId 分组；flatHistory 下全部归入单一隐式组 ⇒ 平铺、无分组标题。
  const groups = new Map<string, SessionDto[]>()
  for (const s of projectSessions) {
    const key = flatHistory ? FLAT_GROUP_KEY : (s.mainProjectId ?? '__unbound__')
    const arr = groups.get(key) ?? []
    arr.push(s)
    groups.set(key, arr)
  }
  // 分组标题序：口径与理由见 compareGroups（比对函数已提出到 sessionOrder.ts 以便覆盖测试 ——
  // 原实现 inline 在此处且对两个非 unbound 组恒返回 0，是本次缺陷的同一处却零测试覆盖）。
  // [2026-11-10 用户裁定] 组间序 = 项目**注册先后**（projects.created_at），不看任何会话活动；
  // 在后面的项目里新建会话不再把该组顶到最前。
  const orderedKeys = [...groups.keys()].sort((a, b) => compareGroups(
    { key: a, sessions: groups.get(a) ?? [], projectCreatedAt: projectCreatedAtFor?.(a) },
    { key: b, sessions: groups.get(b) ?? [], projectCreatedAt: projectCreatedAtFor?.(b) },
  ))

  const toggleGroup = (key: string) => {
    // 默认折叠口径下记录「展开集」；默认展开口径下记录「折叠集」——两者互斥使用，不混写。
    if (groupsDefaultCollapsed) {
      setExpandedGroups((prev) => {
        const next = new Set(prev)
        if (next.has(key)) next.delete(key); else next.add(key)
        return next
      })
      return
    }
    setCollapsedGroups((prev) => {
      const next = new Set(prev)
      if (next.has(key)) next.delete(key); else next.add(key)
      return next
    })
  }

  /**
   * 会话行 · 置顶 / 派生 / IM / 项目组共用同一渲染（避免多份菜单/状态点逻辑漂移）。
   *
   * <p>`collapse` 只给「派生块的源」用：在标题后插一个 chevron，点它折叠该源的子会话；
   * 行本身仍是完整会话行（hover / 状态点 / 时间 / ⋯ 菜单与别处完全一致）。
   */
  const renderSessionRow = (
    s: SessionDto,
    collapse?: { open: boolean; onToggle: () => void },
    /** [run-block] 有值 = 用运行时长替换相对时间（hover 不淡出）。 */
    runTime?: string,
  ) => {
    // [session-family] 家族色：同族（含创建者）同一色，跨块一致；普通会话无 --fam ⇒ 不渲染色条/不染色徽章。
    const fam = familyColors.get(s.id)
    const famFocus = !!fam && hoverFamily === fam.rootId
    const kidCount = derivedChildCounts.get(s.id) ?? 0
    const rootTitle = fam ? (sessionById.get(fam.rootId)?.title ?? fam.rootId) : ''
    return (
    <div
      key={s.id}
      className={`session-item ${activeSession === s.id ? 'active' : ''}${fam ? ' has-family' : ''}${famFocus ? ' fam-focus' : ''}`}
      data-fam={fam?.rootId}
      style={fam ? ({ '--fam': fam.color } as CSSProperties) : undefined}
      onMouseEnter={fam ? () => setHoverFamily(fam.rootId) : undefined}
      onMouseLeave={fam ? () => setHoverFamily(null) : undefined}
      onClick={() => switchSession(s.id)}
    >
      {/* [session-family] 家族色条：放在左侧留白（x≈15px），与选中行的 3px accent 边、状态点都不重叠 */}
      {fam ? <span className="fam-bar" aria-hidden="true" /> : null}
      {/* [V283] 行首置顶五角星 · 未置顶 hover 才显示（灰色空心），已置顶常显（金色实心）；
          点击直接切换置顶，不触发会话切换（stopPropagation）。 */}
      <button
        type="button"
        className={`session-pin-btn${s.pinned === true ? ' pinned' : ''}`}
        title={s.pinned === true ? '取消置顶' : '置顶'}
        aria-label={s.pinned === true ? '取消置顶' : '置顶'}
        onClick={(e) => { e.stopPropagation(); onPinSession?.(s.id, s.pinned !== true) }}
      >
        <svg viewBox="0 0 16 16" fill={s.pinned === true ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="1.3" strokeLinejoin="round">
          <path d="M8 1.5l2.06 4.18 4.61.67-3.33 3.25.78 4.59L8 11.93l-4.12 2.26.78-4.59L1.33 6.35l4.61-.67L8 1.5z" />
        </svg>
      </button>
      {/* 对齐 Harness sessionStatuses：等待权限（黄）优先于运行中（蓝） */}
      {(() => {
        const pend = pendingSessionIds?.has(s.id)
        const run = runningSessionIds?.has(s.id)
        const dn = doneUnreadIds?.has(s.id)
        if (!pend && !run && !dn) return null
        const cls = pend ? 'pending' : run ? 'running' : 'done'
        const tip = pend ? '等待权限/回答（点开会话处理）' : run ? '运行中' : '已完成 · 未读'
        return <span className={`status-dot ${cls}`} title={tip} />
      })()}
      {renameId === s.id ? (
        <input
          className="session-rename-input"
          value={renameDraft}
          autoFocus
          onChange={(e) => setRenameDraft(e.target.value)}
          onKeyDown={(e) => { e.stopPropagation(); if (e.key === 'Enter') { onRenameSession?.(s.id, renameDraft); setRenameId(null) } if (e.key === 'Escape') setRenameId(null) }}
          onBlur={() => setRenameId(null)}
        />
      ) : (
        <span className="title">{s.title || '新会话'}</span>
      )}
      {/* 派生块的「源」：标题后的 chevron 只折叠该源的子会话，点行本身仍是切会话 */}
      {collapse && (
        <button
          type="button"
          className="session-collapse-btn"
          aria-expanded={collapse.open}
          aria-label={collapse.open ? '收起该源的派生会话' : '展开该源的派生会话'}
          title={collapse.open ? '收起该源的派生会话' : '展开该源的派生会话'}
          onClick={(e) => { e.stopPropagation(); collapse.onToggle() }}
        >
          <ChevronIcon open={collapse.open} />
        </button>
      )}
      {/* [V328] 派生会话标记：由另一个会话经 SessionCreate 创建；仅展示，不做跳转/权限依据。
          [session-family] 徽章取家族色，悬停提示创建者标题（不再裸露会话 id）。 */}
      {s.derivedFromSessionId ? (
        <span className="derived-badge" title={`由「${rootTitle}」创建`}>派生</span>
      ) : null}
      {/* [session-family] 创建者标记：与派生会话同一颜色 + 派生数（悬停说明），让「配对」看得出来 */}
      {fam && kidCount > 0 ? (
        <span className="fam-chip" title={`派生了 ${kidCount} 个会话`}>
          <svg className="fam-fork" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" aria-hidden="true">
            <circle cx="4" cy="3" r="1.5" />
            <circle cx="4" cy="13" r="1.5" />
            <circle cx="12" cy="13" r="1.5" />
            <path d="M4 4.5V11.5M5.5 13h5" />
          </svg>
          {kidCount}
        </span>
      ) : null}
      {/* [peer-badge] 该会话待处理投递数：0 不渲染；点击切到该会话。
          ⚠️ 放在 title 与 time 之间 —— 不遮挡行首状态点/置顶星标，hover 时 time 淡出也不受影响。
          纯文本数字 + 小圆点、无动画（避免与「运行中闪烁」混淆）。 */}
      {(() => {
        const pending = peerPendingCounts?.[s.id] ?? 0
        if (pending <= 0) return null
        return (
          <button
            type="button"
            className="session-peer-badge"
            title={`待处理 ${pending} 条投递`}
            aria-label={`待处理 ${pending} 条投递`}
            onClick={(e) => { e.stopPropagation(); (onOpenPeerPending ?? switchSession)(s.id) }}
          >
            <span className="dot" aria-hidden="true" />
            {pending}
          </button>
        )
      })()}
      {runTime
        ? <span className="run-elapsed" title={`已运行 ${runTime}`}>{runTime}</span>
        : <span className="time">{formatRelativeTime(s.updatedAt ?? s.createdAt)}</span>}
      <span className="session-menu-btn" title="会话操作" onClick={(e) => { e.stopPropagation(); setMenuId(menuId === s.id ? null : s.id) }}>⋯</span>
      {menuId === s.id && (
        <div className="session-menu">
          <div className="sm-item" onClick={(e) => { e.stopPropagation(); onPinSession?.(s.id, s.pinned !== true); setMenuId(null) }}>{s.pinned === true ? '取消置顶' : '置顶'}</div>
          <div className="sm-item" onClick={(e) => { e.stopPropagation(); setRenameId(s.id); setRenameDraft(s.title || ''); setMenuId(null) }}>重命名</div>
          <div className="sm-item" onClick={(e) => { e.stopPropagation(); onDuplicateSession?.(s.id); setMenuId(null) }}>复制</div>
          <div className="sm-item danger" onClick={(e) => { e.stopPropagation(); onDeleteSession?.(s.id) }}>删除</div>
        </div>
      )}
    </div>
    )
  }

  // 左栏面板内折叠开关（顶格 · 无文字标签 · 对齐 deepseek-harness logoRow 样式）。
  // 改版场景（workspaceRedesign）把它渲染在滚动区**之外**的左栏顶部右侧：
  // 与主区 .ws-head 同为 52px 行、右对齐；红绿灯不动。其余场景保持原位置（content 首行）。
  const railHead = (
    <div className="left-rail-head">
      <button className="rail-collapse-btn" title="收起会话栏" aria-label="收起会话栏" onClick={onToggleCollapse}>
        {/* 面板图标（圆角矩形 + 左栏分隔线）· 参考 Open Design 侧栏开关的形态 */}
        <svg viewBox="0 0 16 14" fill="none" width="18" height="16">
          <rect x="1.1" y="1.1" width="13.8" height="11.8" rx="2.6" stroke="currentColor" strokeWidth="1.35" />
          <line x1="6" y1="1.4" x2="6" y2="12.6" stroke="currentColor" strokeWidth="1.35" strokeLinecap="round" />
        </svg>
      </button>
    </div>
  )

  const content = (
    <>
      {!workspaceRedesign && railHead}
      {/* 新建会话入口 · 改版场景作为导航行（加号图标 + 13.5px 文字），其余场景保持原按钮 */}
      {workspaceRedesign ? (
        <button className="new-session-btn ws-nav-new" onClick={() => onCreateSession?.()} title="新建会话">
          <svg className="ent-ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" style={{ width: 17, height: 17, flexShrink: 0 }}>
            <path d="M12 5v14M5 12h14" />
          </svg>
          <span className="ent-t">新建会话</span>
        </button>
      ) : (
        <button className="new-session-btn" onClick={() => onCreateSession?.()} title="新建会话">
          + 新会话
        </button>
      )}
      {/* 左栏入口：定时任务（改版 · 原型 nav 第 2 项）。点击 → 主区切到定时任务页。 */}
      {workspaceRedesign && onOpenSchedules && (
        <button
          className={`left-entry ws-nav-schedules${schedulesActive ? ' on' : ''}`}
          onClick={() => onOpenSchedules()}
          title="定时任务"
        >
          <svg className="ent-ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round">
            <circle cx="12" cy="12" r="9" />
            <path d="M12 6v6l4 2" />
          </svg>
          <span className="ent-t">定时任务</span>
        </button>
      )}
      {/* 左栏入口：IM 频道（一级项，与「定时任务」同层）。
          位置在「新建会话」之后、「定时任务」之前 —— 按使用频率排，IM 比定时任务更常用。 */}
      {workspaceRedesign && onOpenIm && (
        <button
          className={`left-entry ws-nav-im${imActive ? ' on' : ''}`}
          onClick={() => onOpenIm()}
          title="IM 频道"
        >
          <svg className="ent-ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round">
            <path d="M20 11.5c0 3.9-3.6 7-8 7-1 0-2-.2-2.9-.5L4.5 19.5l1.2-3.3C4.6 15.1 4 13.4 4 11.5c0-3.9 3.6-7 8-7s8 3.1 8 7z" />
          </svg>
          <span className="ent-t">IM 频道</span>
        </button>
      )}

      <div className="left-entry" onClick={() => onOpenAgentMarket?.()} title="打开技能市场">
        <svg className="ent-ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6}><circle cx="12" cy="10" r="3.4" /><path d="M4.5 20c.9-3 4-4.4 7.5-4.4s6.6 1.4 7.5 4.4" /></svg>
        <span className="ent-t">技能市场</span>
        <span className="ent-arr">›</span>
      </div>
      <div className="left-entry" onClick={() => onOpenKnowledgeBase?.()} title="打开知识库">
        <svg className="ent-ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6}><ellipse cx="12" cy="5.5" rx="7" ry="3" /><path d="M5 5.5v13c0 1.7 3.1 3 7 3s7-1.3 7-3v-13" /><path d="M5 12c0 1.7 3.1 3 7 3s7-1.3 7-3" /></svg>
        <span className="ent-t">知识库</span>
        <span className="ent-arr">›</span>
      </div>
      {/* 分区标题：「工作区」→「任务」（改版）；右侧搜索 + 添加。非改版场景保留原「工作区」标签。 */}
      {workspaceRedesign ? (
        <div className="task-section-head">
          <span className="ts-title">任务</span>
          <span className="ts-count">{sessions.length}</span>
          <div className="ts-actions">
            <button className="ts-btn" title="搜索任务" aria-label="搜索任务" onClick={() => onOpenSearch?.()}>
              <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.7">
                <circle cx="7" cy="7" r="4.2" />
                <path d="M10.2 10.2L13.3 13.3" strokeLinecap="round" />
              </svg>
            </button>
            <button
              className="ts-btn"
              title={flatHistory ? '新建任务' : '添加工作区'}
              aria-label={flatHistory ? '新建任务' : '添加工作区'}
              onClick={() => (flatHistory ? onCreateSession?.() : onAddWorkspace?.())}
            >
              <PlusIcon size={11} />
            </button>
          </div>
        </div>
      ) : !flatHistory ? (
        <div className="workspace-label">
          <span className="label">工作区</span>
          <span className="add-workspace" title="添加工作区" onClick={() => onAddWorkspace?.()}>
            <PlusIcon size={11} />
          </span>
        </div>
      ) : null}

      {/* [peer-total] 全局待处理汇总：N = totalPending（各会话 pending 之和），0 时整块不渲染。
          点击只开「待收件」面板 —— 汇总跨多个会话，无法确定目标 ⇒ ⛔ 不切会话。 */}
      {(peerTotalPending ?? 0) > 0 && (
        <button
          type="button"
          className="left-peer-total"
          title="查看待处理投递"
          aria-label={`待处理 ${peerTotalPending} 条投递`}
          onClick={() => onOpenPeerInbox?.()}
        >
          <span className="dot" aria-hidden="true" />
          <span className="txt">待处理</span>
          <span className="n">{peerTotalPending}</span>
        </button>
      )}

      {sessions.length === 0 && (
        <div className="sidebar-empty">暂无会话 · 点击 + 新建</div>
      )}

      {/* [run-block 2026-11-23] 正在运行块 · 置顶之上；与下方各块允许重叠（不隐藏不去重） */}
      {!flatHistory && runningSessions.length > 0 && (
        <div className="left-section running-block">
          <div
            className={`label running-label${runningOpen ? '' : ' collapsed'}`}
            onClick={() => setRunningOpen((v) => !v)}
            role="button"
            aria-expanded={runningOpen}
            title={runningOpen ? '收起正在运行' : '展开正在运行'}
          >
            <ChevronIcon open={runningOpen} />
            <i className="run-pulse" aria-hidden="true" />
            <span className="group-path">正在运行</span>
            <span className="count">{runningSessions.length}</span>
            <span className="label-right">{renderRunBadges(runningSessions)}</span>
          </div>
          {runningOpen && runningSessions.map((s) => {
            const since = runSince.get(s.id)
            return renderSessionRow(
              s,
              undefined,
              since === undefined ? undefined : formatRunElapsed(Date.now() - since),
            )
          })}
        </div>
      )}

      {/* [V283] 置顶块 · 跨项目置前。
          [session-family 2026-11-23] 标题可点击折叠（默认展开）；置顶独占 —— 同一条不再出现在
          派生 / IM / 项目分组里（「置顶不隐藏」指留在本块可见）。 */}
      {pinnedSessions.length > 0 && (
        <div className="left-section">
          {!flatHistory && (
            <div
              className={`label pinned-label${pinnedOpen ? '' : ' collapsed'}`}
              onClick={() => setPinnedOpen((v) => !v)}
              role="button"
              aria-expanded={pinnedOpen}
              title={pinnedOpen ? '收起置顶' : '展开置顶'}
            >
              <ChevronIcon open={pinnedOpen} />
              <svg className="pin-mark" viewBox="0 0 16 16" fill="currentColor" role="img" aria-label="已置顶">
                <path d="M8 1.5l2.06 4.18 4.61.67-3.33 3.25.78 4.59L8 11.93l-4.12 2.26.78-4.59L1.33 6.35l4.61-.67L8 1.5z" />
              </svg>
              <span className="group-path">置顶</span>
              <span className="count">{pinnedSessions.length}</span>
              <span className="label-right">{renderRunBadges(pinnedSessions)}</span>
            </div>
          )}
          {(flatHistory || pinnedOpen) && sortSessionsWithPinned(pinnedSessions).map((s) => renderSessionRow(s))}
        </div>
      )}

      {/* [session-family 2026-11-23] 派生块 · 置顶之后、IM 之前。
          · 成员 = 全部家族成员（创建者 + 派生会话 + 孙会话），**不排除置顶/IM 的成员**：
            三个视图块回答不同问题，允许重叠；置顶成员在行首显示金色实心星（其他颜色不变）。
          · 内部按「源」（家族根）分组：源渲染成组标题（可单独展开/收起），组内是它派生的会话。
          · 普通项目分组会排除这些会话（下面的列表只放「未置顶 + 非 IM + 非家族」）。 */}
      {!flatHistory && derivedSessions.length > 0 && (
        <div className="left-section family-block">
          <div
            className={`label family-label${derivedOpen ? '' : ' collapsed'}`}
            onClick={() => setDerivedOpen((v) => !v)}
            role="button"
            aria-expanded={derivedOpen}
            title={derivedOpen ? '收起派生' : '展开派生'}
          >
            <ChevronIcon open={derivedOpen} />
            <svg viewBox="0 0 16 16" width="12" height="12" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" className="family-mark" role="img" aria-label="派生会话">
              <circle cx="4" cy="3" r="1.5" />
              <circle cx="4" cy="13" r="1.5" />
              <circle cx="12" cy="13" r="1.5" />
              <path d="M4 4.5V11.5M5.5 13h5" />
            </svg>
            <span className="group-path">派生</span>
            <span className="count">{derivedSessions.length}</span>
            <span className="label-right">{renderRunBadges(derivedSessions)}</span>
          </div>
          {derivedOpen && familyGroups.map((group) => {
            const rootSession = sessionById.get(group.rootId)
            const open = !collapsedFamilyRoots.has(group.rootId)
            const toggle = () => setCollapsedFamilyRoots((prev) => {
              const next = new Set(prev)
              if (next.has(group.rootId)) next.delete(group.rootId); else next.add(group.rootId)
              return next
            })
            return (
              <div className="family-group" key={group.rootId}>
                {/* 「源」= 完整会话行（hover / 状态点 / 时间 / ⋯ 菜单与别处一致），
                    标题后多一个 chevron 只折叠该源的子会话。创建者已删时退化为只用 id 的静态行。 */}
                {rootSession ? (
                  renderSessionRow(rootSession, { open, onToggle: toggle })
                ) : (
                  <div className="session-item has-family source-missing" style={{ '--fam': group.color } as CSSProperties}>
                    <span className="fam-bar" aria-hidden="true" />
                    <button
                      type="button"
                      className="session-collapse-btn"
                      aria-expanded={open}
                      aria-label={open ? '收起该源的派生会话' : '展开该源的派生会话'}
                      onClick={(e) => { e.stopPropagation(); toggle() }}
                    >
                      <ChevronIcon open={open} />
                    </button>
                    <span className="title">{group.rootId}（源已删除）</span>
                    <span className="time">{group.members.length}</span>
                  </div>
                )}
                {open && group.members
                  .filter((m) => m.id !== group.rootId)
                  .map((m) => renderSessionRow(m))}
              </div>
            )
          })}
        </div>
      )}

      {/* IM 会话块（用户裁定：任务上方除置顶外再加一块）。
          · 数据 = 有接线的会话（imSessionIds 来自 /channels/bindings），**含已置顶的**：
            置顶成员在这里也可见，行首显示金色实心星（视图块允许重叠）。
          · [session-family 2026-11-23] **不再出现在下面的项目分组里**（用户要求去重）；
            与派生块可以重叠 —— 同一会话既在 IM 块又在派生块。
          · 标题可点击折叠；默认最多 3 条 + 「查看全部」——IM 会话数量会长，全展开会把左栏撑爆 */}
      {imBlockSessions.length > 0 && (
        <div className="left-section im-block">
          {!flatHistory && (
            <div
              className={`label im-label${imOpen ? '' : ' collapsed'}`}
              onClick={() => setImOpen((v) => !v)}
              role="button"
              aria-expanded={imOpen}
              title={imOpen ? '收起 IM 会话' : '展开 IM 会话'}
            >
              <ChevronIcon open={imOpen} />
              <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round">
                <path d="M20 11.5c0 3.9-3.6 7-8 7-1 0-2-.2-2.9-.5L4.5 19.5l1.2-3.3C4.6 15.1 4 13.4 4 11.5c0-3.9 3.6-7 8-7s8 3.1 8 7z" />
              </svg>
              <span className="group-path">IM 会话</span>
              <span className="count">{imBlockSessions.length}</span>
              <span className="label-right">
                {renderRunBadges(imBlockSessions)}
                {imOpen && imBlockSessions.length > IM_BLOCK_PREVIEW && (
                  <button
                    onClick={(e) => { e.stopPropagation(); setImExpanded((v) => !v) }}
                    title={imExpanded ? '收起' : '展开全部'}
                    style={{
                      border: 0,
                      background: 'none',
                      color: 'var(--accent)',
                      fontSize: 11.5,
                      cursor: 'pointer',
                      padding: '0 2px',
                    }}
                  >
                    {imExpanded ? '收起' : '查看全部'}
                  </button>
                )}
              </span>
            </div>
          )}
          {(flatHistory || imOpen) &&
            (imExpanded ? imBlockSessions : imBlockSessions.slice(0, IM_BLOCK_PREVIEW)).map((s) => renderSessionRow(s))}
        </div>
      )}

      {orderedKeys.map((key) => {
        const groupSessions = groups.get(key) ?? []
        if (groupSessions.length === 0) return null
        const isUnbound = key === '__unbound__'
        // 组标题：项目名（mono 字体），路径作 tooltip
        const path = isUnbound ? '' : (projectPathFor?.(key) ?? '')
        const name = isUnbound ? '未分组' : (projectNameFor?.(key) ?? '项目')
        const groupTitle = name
        // flatHistory：全部会话在同一隐式组里，无组标题 ⇒ 永不折叠。
        // 默认折叠口径：未在 expandedGroups 里 = 折叠；当前会话组由上方 effect 自动加入。
        const collapsed = flatHistory
          ? false
          : groupsDefaultCollapsed ? !expandedGroups.has(key) : collapsedGroups.has(key)
        // 折叠 → 组内会话全部收起（0 个）；展开 → 全部显示
        // 组内序走 compareSessions（updatedAt 降序 + id 兜底），与后端 ORDER BY 同一口径；
        // 用 slice 而非原地 sort —— groups 里的数组是本渲染新建的，但原地排序会让「组内序」
        // 隐式依赖遍历顺序，显式复制更清楚。
        const visible = collapsed ? [] : [...groupSessions].sort(compareSessions)
        return (
          <div key={key} className="left-section">
            {/* flatHistory：不渲染分组标题 —— 会话平铺，无项目/文件夹分组概念 */}
            {!flatHistory && (
              <div
                className={`label ${collapsed ? 'collapsed' : ''}`}
                onClick={() => toggleGroup(key)}
                onContextMenu={(e) => {
                  e.preventDefault()
                  e.stopPropagation()
                  if (!path) return
                  setGroupMenu({ x: e.clientX, y: e.clientY, path })
                }}
              >
                <ChevronIcon open={!collapsed} />
                <FolderIcon />
                <span className="group-path" title={path || groupTitle}>{groupTitle}</span>
                <span className="count">{groupSessions.length}</span>
                <span className="label-right">
                  {renderRunBadges(groupSessions)}
                  {!isUnbound && (
                    <div className="add" title="在该项目内新建会话" onClick={(e) => { e.stopPropagation(); onCreateInProject?.(key) }}>
                      <PlusIcon size={10} />
                    </div>
                  )}
                </span>
              </div>
            )}
            {visible.map((s) => renderSessionRow(s))}
          </div>
        )
      })}
    </>
  )

  return (
    <div className={`left${workspaceRedesign ? ' left-workspace' : ''}`}>
      {workspaceRedesign && railHead}
      {workspaceRedesign ? <div className="left-ws-scroll">{content}</div> : content}
      {workspaceRedesign && (
        <div className="left-ws-footer">
          <button className="left-user-row" title="打开设置" aria-label="打开设置" onClick={() => onOpenSettings?.()}>
            <span className="left-user-avatar">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round">
                <circle cx="12" cy="8" r="3.4" />
                <path d="M5 20c.9-3.3 3.7-5 7-5s6.1 1.7 7 5" />
              </svg>
            </span>
            <span className="left-user-meta">
              <span className="u">本地用户</span>
              <span className="s">本机数据 · 无账号</span>
            </span>
            <svg className="left-user-gear" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
              <circle cx="12" cy="12" r="3" />
              <path d="M19.4 15a1.6 1.6 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.6 1.6 0 0 0-1.8-.3 1.6 1.6 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.6 1.6 0 0 0-1-1.5 1.6 1.6 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.6 1.6 0 0 0 .3-1.8 1.6 1.6 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.6 1.6 0 0 0 1.5-1 1.6 1.6 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.6 1.6 0 0 0 1.8.3H9a1.6 1.6 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.6 1.6 0 0 0 1 1.5 1.6 1.6 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.6 1.6 0 0 0-.3 1.8V9a1.6 1.6 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.6 1.6 0 0 0-1.5 1z" />
            </svg>
          </button>
        </div>
      )}
      {groupMenu && createPortal(
        <div
          className="project-context-menu"
          style={{ left: groupMenu.x, top: groupMenu.y }}
          onMouseDown={(e) => e.stopPropagation()}
          onContextMenu={(e) => e.preventDefault()}
        >
          <div className="ctx-menu-item" onClick={() => void revealGroupPath()}>在文件资源管理器中显示</div>
        </div>,
        document.body,
      )}
    </div>
  )
}
