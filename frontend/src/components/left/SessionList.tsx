import { useState } from 'react'
import type { SessionDto } from '@/api/types'

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
  <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: 16, height: 16, flexShrink: 0 }}>
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
}

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

export function SessionList({ sessions, activeSession, switchSession, projectPathFor, projectNameFor, onCreateInProject, onCreateSession, onAddWorkspace, onDeleteSession, onRenameSession, runningSessionIds, pendingSessionIds, doneUnreadIds, onOpenAgentMarket, onOpenKnowledgeBase }: SessionListProps) {
  const [collapsedGroups, setCollapsedGroups] = useState<Set<string>>(new Set())
  // 会话操作菜单：menuId（打开的菜单）+ renameId/renameDraft（重命名输入）
  const [menuId, setMenuId] = useState<string | null>(null)
  const [renameId, setRenameId] = useState<string | null>(null)
  const [renameDraft, setRenameDraft] = useState('')

  // 按 mainProjectId 分组
  const groups = new Map<string, SessionDto[]>()
  for (const s of sessions) {
    const key = s.mainProjectId ?? '__unbound__'
    const arr = groups.get(key) ?? []
    arr.push(s)
    groups.set(key, arr)
  }
  const orderedKeys = [...groups.keys()].sort((a, b) => (a === '__unbound__' ? 1 : b === '__unbound__' ? -1 : 0))

  const toggleGroup = (key: string) => setCollapsedGroups((prev) => {
    const next = new Set(prev)
    if (next.has(key)) next.delete(key); else next.add(key)
    return next
  })

  return (
    <div className="left">
      {/* 新会话按钮（工作区上方，居中 · 设计稿 v7） */}
      <button className="new-session-btn" onClick={() => onCreateSession?.()} title="新建会话">
        + 新会话
      </button>
      {/* 左栏入口：技能市场 / 知识库（透明胶囊 · hover 浮现 · 点开各自面板） */}
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
      {/* 工作区标签 + 添加工作区按钮（hover tooltip · 设计稿 v7） */}
      <div className="workspace-label">
        <span className="label">工作区</span>
        <span className="add-workspace" title="添加工作区" onClick={() => onAddWorkspace?.()}>
          <PlusIcon size={11} />
        </span>
      </div>

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
        const collapsed = collapsedGroups.has(key)
        // 折叠 → 组内会话全部收起（0 个）；展开 → 全部显示
        const visible = collapsed ? [] : groupSessions
        return (
          <div key={key} className="left-section">
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
    </div>
  )
}
