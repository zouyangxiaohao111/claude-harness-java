import { useEffect, useMemo, useState } from 'react'
import type { SessionDto } from '@/api/types'
import { compareSessions, compareGroups } from '@/utils/sessionOrder'

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
  /** 运行中会话 id 集合（有 stream = 运行中 · 蓝点 ongoing） */
  runningSessionIds?: Set<string>
  /** 等待权限/提问的会话 id 集合（permissionQueue 中 · 橙红闪烁，优先于运行点） */
  pendingSessionIds?: Set<string>
  /** 运行完成且未读的会话 id 集合（静止绿点 · 切到该会话即清除） */
  doneUnreadIds?: Set<string>
  /** 重命名会话（App 调 sessionApi.update + 本地同步） */
  onRenameSession?: (id: string, title: string) => void
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

export function SessionList({ sessions, activeSession, switchSession, projectPathFor, projectNameFor, onCreateInProject, onCreateSession, onAddWorkspace, onDeleteSession, onRenameSession, runningSessionIds, pendingSessionIds, doneUnreadIds, onOpenAgentMarket, onOpenKnowledgeBase, onToggleCollapse, collapsed, flatHistory, workspaceRedesign, onOpenSettings, onOpenSearch, onOpenSchedules, schedulesActive, groupsDefaultCollapsed }: SessionListProps) {
  const [collapsedGroups, setCollapsedGroups] = useState<Set<string>>(new Set())
  // 默认折叠口径：只记录**用户/自动展开过**的组（collapsedGroups 的反面）。
  // 两套 state 并存而不是合成一个 Map：默认展开与默认折叠是两种语义，用一个布尔直接翻转
  // 会让「用户手动展开的组」在模式切换时被误判成「用户手动折叠」，语义丢失。
  const [expandedGroups, setExpandedGroups] = useState<Set<string>>(new Set())
  // 会话操作菜单：menuId（打开的菜单）+ renameId/renameDraft（重命名输入）
  const [menuId, setMenuId] = useState<string | null>(null)
  const [renameId, setRenameId] = useState<string | null>(null)
  const [renameDraft, setRenameDraft] = useState('')

  /** 当前会话所在组键（无会话 = null）。默认折叠口径下用它自动展开，保证当前会话可见。 */
  const activeGroupKey = useMemo(() => {
    const s = sessions.find((x) => x.id === activeSession)
    if (!s) return null
    return flatHistory ? FLAT_GROUP_KEY : (s.mainProjectId ?? '__unbound__')
  }, [sessions, activeSession, flatHistory])

  // 默认折叠：当前会话所在组自动展开。
  // 依赖 = activeSession（会话 id）+ activeGroupKey，刻意**不含 sessions 引用**：
  //   · 轮询/重排换 sessions 数组不会反复顶开用户手动折叠的组；
  //   · 切会话 / 新建会话（activeSession id 变化）一定触发 —— 新建会话即使落在**同一组**
  //     （activeGroupKey 不变）也能把该组展开。
  useEffect(() => {
    if (!groupsDefaultCollapsed || flatHistory || !activeGroupKey) return
    setExpandedGroups((prev) => (prev.has(activeGroupKey) ? prev : new Set(prev).add(activeGroupKey)))
  }, [groupsDefaultCollapsed, flatHistory, activeGroupKey, activeSession])

  // [对齐 deepseek-harness] 收起 = 窄 rail（不消失）：始终能再展开
  if (collapsed) {
    return (
      <div className={`left left-rail-collapsed${workspaceRedesign ? ' left-workspace' : ''}`}>
        <button className="rail-collapse-btn rail-expand" title="展开会话栏" aria-label="展开会话栏" onClick={onToggleCollapse}>
          {/* 折叠态也用面板图标（不再用 ">" 箭头）：图标本身就是「侧栏」语义，
              折叠前后形状一致，用户不用重新认一遍。 */}
          <svg viewBox="0 0 16 14" fill="none" width="18" height="16">
            <rect x="1.1" y="1.1" width="13.8" height="11.8" rx="2.6" stroke="currentColor" strokeWidth="1.35" />
            <line x1="6" y1="1.4" x2="6" y2="12.6" stroke="currentColor" strokeWidth="1.35" strokeLinecap="round" />
          </svg>
        </button>
        <button className="new-session-btn rail-new" title="新建会话" onClick={() => onCreateSession?.()}><PlusIcon size={13} /></button>
        {/* 改版收起态：保留导航图标（定时任务 / 技能市场 / 知识库） */}
        {workspaceRedesign && (
          <div className="rail-nav">
            <button className="rail-nav-btn" title="定时任务" aria-label="定时任务" onClick={() => onOpenSchedules?.()}>
              <svg viewBox="0 0 24 24" className="i" fill="none" stroke="currentColor" strokeWidth="1.6"><circle cx="12" cy="12" r="8.4" /><path d="M12 7v5l3 2" strokeLinecap="round" /></svg>
            </button>
            <button className="rail-nav-btn" title="技能市场" aria-label="技能市场" onClick={() => onOpenAgentMarket?.()}>
              <svg viewBox="0 0 24 24" className="i" fill="none" stroke="currentColor" strokeWidth="1.6"><path d="M4 20L15 9" strokeLinecap="round" /><path d="M14 4l1 2.6L17.6 8 15 9l-1 2.6L13 9l-2.6-1L13 6.4 14 4z" strokeLinejoin="round" /></svg>
            </button>
            <button className="rail-nav-btn" title="知识库" aria-label="知识库" onClick={() => onOpenKnowledgeBase?.()}>
              <svg viewBox="0 0 24 24" className="i" fill="none" stroke="currentColor" strokeWidth="1.6"><ellipse cx="12" cy="5.8" rx="7.5" ry="3" /><path d="M4.5 5.8v12.4c0 1.7 3.4 3 7.5 3s7.5-1.3 7.5-3V5.8" /><path d="M4.5 12c0 1.7 3.4 3 7.5 3s7.5-1.3 7.5-3" /></svg>
            </button>
          </div>
        )}
      </div>
    )
  }

  // 按 mainProjectId 分组；flatHistory 下全部归入单一隐式组 ⇒ 平铺、无分组标题。
  const groups = new Map<string, SessionDto[]>()
  for (const s of sessions) {
    const key = flatHistory ? FLAT_GROUP_KEY : (s.mainProjectId ?? '__unbound__')
    const arr = groups.get(key) ?? []
    arr.push(s)
    groups.set(key, arr)
  }
  // 分组标题序：口径与理由见 compareGroups（比对函数已提出到 sessionOrder.ts 以便覆盖测试 ——
  // 原实现 inline 在此处且对两个非 unbound 组恒返回 0，是本次缺陷的同一处却零测试覆盖）。
  const orderedKeys = [...groups.keys()].sort((a, b) => compareGroups(
    { key: a, sessions: groups.get(a) ?? [] },
    { key: b, sessions: groups.get(b) ?? [] },
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

      {sessions.length === 0 && (
        <div className="sidebar-empty">暂无会话 · 点击 + 新建</div>
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
              <div className={`label ${collapsed ? 'collapsed' : ''}`} onClick={() => toggleGroup(key)}>
                <ChevronIcon open={!collapsed} />
                <FolderIcon />
                <span className="group-path" title={path || groupTitle}>{groupTitle}</span>
                <span className="count">{groupSessions.length}</span>
                {!isUnbound && (
                  <div className="add" title="在该项目内新建会话" onClick={(e) => { e.stopPropagation(); onCreateInProject?.(key) }}>
                    <PlusIcon size={10} />
                  </div>
                )}
              </div>
            )}
            {visible.map((s) => (
              <div
                key={s.id}
                className={`session-item ${activeSession === s.id ? 'active' : ''}`}
                onClick={() => switchSession(s.id)}
              >
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
                <span className="time">{formatRelativeTime(s.updatedAt ?? s.createdAt)}</span>
                <span className="session-menu-btn" title="会话操作" onClick={(e) => { e.stopPropagation(); setMenuId(menuId === s.id ? null : s.id) }}>⋯</span>
                {menuId === s.id && (
                  <div className="session-menu">
                    <div className="sm-item" onClick={(e) => { e.stopPropagation(); setRenameId(s.id); setRenameDraft(s.title || ''); setMenuId(null) }}>重命名</div>
                    <div className="sm-item danger" onClick={(e) => { e.stopPropagation(); onDeleteSession?.(s.id) }}>删除</div>
                  </div>
                )}
              </div>
            ))}
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
    </div>
  )
}
