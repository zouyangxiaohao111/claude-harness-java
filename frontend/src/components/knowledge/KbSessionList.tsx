import { useCallback, useEffect, useState } from 'react'
import { knowledgeApi } from '@/api/knowledge'
import { sessionApi } from '@/api/sessions'
import type { SessionDto } from '@/api/types'

interface Props {
  /** 当前打开的会话（高亮） */
  activeSessionId: string | null
  onPick: (id: string) => void
  /** 删除成功后的通知（宿主据此清掉当前会话 / 切到空白会话） */
  onDeleted?: (id: string) => void
}

/** 相对时间标签（与主会话 SessionList 同一口径）：今天 / 昨天 / 前天 / N天前（≤7）/ 月-日 */
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

/**
 * 左侧历史对话：只列「知识库工作区项目」下的会话（与主对话的会话隔离开显示）。
 *
 * <p>行样式与交互直接复用主会话左栏的 `.session-item` 一族（hover 背景、时间淡出、
 * 右侧「⋯ 会话操作」按钮 → 下拉里删除），不再自造一套 hover/删除按钮。
 */
export function KbSessionList({ activeSessionId, onPick, onDeleted }: Props) {
  const [sessions, setSessions] = useState<SessionDto[]>([])
  /** 已打开操作菜单的会话 id（同一时刻只开一个，与主会话 SessionList 同口径） */
  const [menuId, setMenuId] = useState<string | null>(null)

  const refresh = useCallback(async () => {
    try {
      const [proj, list] = await Promise.all([knowledgeApi.workspaceProject(), sessionApi.list()])
      // 空会话（还没发过消息）也要列：用户点「新对话」刚建的那条必须在左栏看得见，
      // 否则左栏像是「新建没生效」（2026-11-13 用户报）。其余空会话（历史遗留）不列。
      const mine = list
        .filter((s) => s.mainProjectId === proj.id
          && ((s.messageCount ?? 0) > 0 || s.id === activeSessionId))
        .sort((a, b) => (b.updatedAt ?? '').localeCompare(a.updatedAt ?? ''))
      setSessions(mine)
    } catch {
      // 拉不到就不显示列表（不影响对话本身）
    }
  }, [activeSessionId])

  useEffect(() => { void refresh() }, [refresh, activeSessionId])

  // 点菜单外面关闭（与主会话 SessionList 同一判据：落在 .session-menu / .session-menu-btn 内不关）
  useEffect(() => {
    if (menuId == null) return
    const onDocClick = (e: MouseEvent) => {
      const el = e.target as HTMLElement | null
      if (el && typeof el.closest === 'function' && el.closest('.session-menu, .session-menu-btn')) return
      setMenuId(null)
    }
    document.addEventListener('click', onDocClick)
    return () => document.removeEventListener('click', onDocClick)
  }, [menuId])

  /** 删除会话（后端 DELETE /sessions/{id}）；删的是当前会话时通知宿主切走。 */
  const remove = useCallback(async (id: string) => {
    setMenuId(null)
    try {
      await sessionApi.remove(id)
      setSessions((prev) => prev.filter((x) => x.id !== id))
      onDeleted?.(id)
    } catch {
      // 删除失败（已被别处删掉 / 网络）：刷新列表即可，不弹窗打断（独立窗没有 toast 容器）
      void refresh()
    }
  }, [onDeleted, refresh])

  const groups = groupByDay(sessions)

  return (
    <div style={WRAP}>
      {groups.length === 0 ? (
        <div style={EMPTY}>暂无会话</div>
      ) : groups.map((g) => (
        <div key={g.label}>
          <div style={GROUP_LABEL}>{g.label}</div>
          {g.items.map((s) => (
            <div
              key={s.id}
              className={`session-item${s.id === activeSessionId ? ' active' : ''}`}
              // 主会话的行首缩进是给项目图标的（46px），知识库没有分组图标 ⇒ 与分组标题左对齐
              style={ROW}
              onClick={() => onPick(s.id)}
              title={s.title}
            >
              <span className="title">{displayTitle(s)}</span>
              <span className="time">{formatRelativeTime(s.updatedAt ?? s.createdAt)}</span>
              <span
                className="session-menu-btn"
                title="会话操作"
                onClick={(e) => { e.stopPropagation(); setMenuId(menuId === s.id ? null : s.id) }}
              >⋯</span>
              {menuId === s.id && (
                <div className="session-menu">
                  <div className="sm-item danger" onClick={(e) => { e.stopPropagation(); void remove(s.id) }}>删除</div>
                </div>
              )}
            </div>
          ))}
        </div>
      ))}
    </div>
  )
}

/**
 * 行标题：后端建会话时 title 默认是模型名（如 `deepseek/deepseek-flash`）——
 * 那种值等于「还没起标题」，显示「新对话」（与标题栏同一口径）。
 */
function displayTitle(s: SessionDto): string {
  const t = (s.title ?? '').trim()
  if (!t) return '新对话'
  const model = (s.modelName ?? '').trim()
  if (model && (t === model || t === model.split('/').pop())) return '新对话'
  return t
}

/** 按「今天 / 昨天 / 更早」分组（用 updatedAt 的本地日期比较）。 */
function groupByDay(list: SessionDto[]): Array<{ label: string; items: SessionDto[] }> {
  const today = new Date()
  const key = (d: string | undefined): string => {
    if (!d) return '更早'
    const t = new Date(d)
    if (Number.isNaN(t.getTime())) return '更早'
    const days = Math.floor((new Date(today.getFullYear(), today.getMonth(), today.getDate()).getTime()
      - new Date(t.getFullYear(), t.getMonth(), t.getDate()).getTime()) / 86_400_000)
    if (days <= 0) return '今天'
    if (days === 1) return '昨天'
    return '更早'
  }
  const order = ['今天', '昨天', '更早']
  const map = new Map<string, SessionDto[]>()
  for (const s of list) {
    const k = key(s.updatedAt)
    const arr = map.get(k)
    if (arr) arr.push(s)
    else map.set(k, [s])
  }
  return order.filter((k) => map.has(k)).map((k) => ({ label: k, items: map.get(k)! }))
}

const WRAP: React.CSSProperties = { padding: '10px 0 16px' }
const EMPTY: React.CSSProperties = { padding: '6px 10px', fontSize: 12, color: '#9aa0aa' }
const GROUP_LABEL: React.CSSProperties = { padding: '10px 10px 4px', fontSize: 12, color: '#8a8f99' }
/** 覆盖主侧栏的用户/分组图标缩进，其余尺寸走 `.session-item` 全局样式 */
const ROW: React.CSSProperties = { paddingLeft: 10, paddingRight: 6 }
