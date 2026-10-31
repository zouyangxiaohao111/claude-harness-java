import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import type { PivotCandidateDto } from '@/api/types'

/** 历史提问面板的数据与动作（App 持有候选与压缩/裁剪流程）。 */
export interface WorkspaceHistoryPanelState {
  open: boolean
  loading: boolean
  error: string | null
  candidates: PivotCandidateDto[]
  onClose: () => void
  onRetry: () => void
  /** 点击提问行 → 跳转到对话区对应消息（压缩 / 裁剪仍走各自按钮）。 */
  onJump: (messageId: string) => void
  onCompact: () => void
  onTrim: () => void
}

interface WorkspaceHeaderProps {
  /** null = 新建任务态（不显示标题）。 */
  title: string | null
  /** WORK 场景：标题为空时回落「新任务」；CODING 回落「新会话」。 */
  isWork: boolean
  onRename: (title: string) => void
  onSearch: () => void
  onShare: () => void
  onTrace: () => void
  traceActive: boolean
  /** 轨迹 = 全部消息流：该会话消息条数（null/0 不显示角标）。 */
  traceCount?: number | null
  onHistory: () => void
  history: WorkspaceHistoryPanelState
  /** 是否渲染右侧抽屉开合按钮（合同全屏表面等右栏不挂载的形态为 false）。 */
  showPanelToggle: boolean
  rightCollapsed: boolean
  onTogglePanel: () => void
}

/** 历史提问单行预览：折空白 + 截 60 字（与 DialogOpsModal 的列表预览同一意图）。 */
function previewOf(c: PivotCandidateDto): string {
  const text = (c.previewSource ?? '').replace(/\s+/g, ' ').trim()
  return text.slice(0, 60) || '(空消息)'
}

/** 候选时间（月/日 时:分）；解析失败静默返回空串。 */
function timeOf(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  return d.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

/**
 * WORK / CODING 共用的主区头部（2026-10-28 左栏改版配套）。
 *
 * <p>左 = 任务标题（静态文本，hover 淡入铅笔；点铅笔才进入编辑，Enter/失焦保存，Esc 撤销）；
 * 右 = 搜索 / 分享 / 轨迹 / 历史提问 / 面板开合。不再有「工作台」文案与「对话 / 轨迹」双 tab。
 */
export function WorkspaceHeader({
  title,
  isWork,
  onRename,
  onSearch,
  onShare,
  onTrace,
  traceActive,
  traceCount,
  onHistory,
  history,
  showPanelToggle,
  rightCollapsed,
  onTogglePanel,
}: WorkspaceHeaderProps) {
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState(title ?? '')
  const [width, setWidth] = useState(120)
  const inputRef = useRef<HTMLInputElement>(null)
  const measureRef = useRef<HTMLSpanElement>(null)
  const cancelRef = useRef(false)
  const historyBtnRef = useRef<HTMLButtonElement>(null)
  const historyPanelRef = useRef<HTMLDivElement>(null)

  // 会话切换 / 外部重命名 → 非编辑态同步草稿
  useEffect(() => {
    if (!editing) setDraft(title ?? '')
  }, [title, editing])

  // 进入编辑：聚焦并全选一次（不放在 draft 变化上，避免每次输入重置选择）
  useEffect(() => {
    if (!editing) return
    const id = requestAnimationFrame(() => {
      inputRef.current?.focus()
      inputRef.current?.select()
    })
    return () => cancelAnimationFrame(id)
  }, [editing])

  // 宽度按内容自适应：min 120 / max 360（不是 flex:1 撑满）
  useLayoutEffect(() => {
    if (!editing) return
    const w = measureRef.current?.offsetWidth ?? 0
    setWidth(Math.min(360, Math.max(120, w + 20)))
  }, [editing, draft])

  const startEdit = () => {
    if (!title) return
    cancelRef.current = false
    setDraft(title)
    setEditing(true)
  }

  const commit = () => {
    if (cancelRef.current) {
      cancelRef.current = false
      return
    }
    const next = draft.trim()
    setEditing(false)
    if (next && next !== title) onRename(next)
    else setDraft(title ?? '')
  }

  const cancel = () => {
    cancelRef.current = true
    setDraft(title ?? '')
    setEditing(false)
  }

  // 历史提问面板：点面板外部关闭（与原型一致；× / Esc 两条通道仍保留）
  useEffect(() => {
    if (!history.open) return
    const onDown = (e: MouseEvent) => {
      const target = e.target as Node | null
      if (!target) return
      if (historyPanelRef.current?.contains(target) || historyBtnRef.current?.contains(target)) return
      history.onClose()
    }
    document.addEventListener('mousedown', onDown)
    return () => document.removeEventListener('mousedown', onDown)
  }, [history.open, history.onClose])

  const displayTitle = title ? title : isWork ? '新任务' : '新会话'
  // 轨迹 = 全部消息流 ⇒ 角标显示条数（用户 2026-10-29 裁定：千级 k / 万级 w 缩写，具体值放 tooltip）
  const traceBadge = (() => {
    if (traceCount == null || traceCount <= 0) return null
    if (traceCount >= 10000) return `${(traceCount / 10000).toFixed(1)}w`
    if (traceCount >= 1000) return `${(traceCount / 1000).toFixed(1)}k`
    return String(traceCount)
  })()
  const traceLabel = traceActive ? '返回对话' : traceCount != null && traceCount > 0 ? `轨迹 · 共 ${traceCount} 条` : '轨迹'

  return (
    <div className="ws-head">
      {title !== null && (
        <div className="ws-title-wrap">
          {editing ? (
            <>
              <input
                ref={inputRef}
                className="ws-title-input"
                value={draft}
                style={{ width }}
                aria-label="任务标题"
                onChange={(e) => setDraft(e.target.value)}
                onBlur={commit}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') { e.preventDefault(); inputRef.current?.blur() }
                  else if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); cancel() }
                }}
              />
            </>
          ) : (
            <>
              <span className="ws-title-text" title={displayTitle}>{displayTitle}</span>
              <button className="ws-title-pencil" type="button" title="重命名任务" aria-label="重命名任务" onClick={startEdit}>
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M14 4l6 6L9 21H3v-6L14 4z" />
                  <path d="M12 6l6 6" />
                </svg>
              </button>
            </>
          )}
        </div>
      )}
      {/* 隐形量尺放在 wrap 外（wrap 宽度由 input 自身决定，放里面会被它反约束） */}
      {editing && <span ref={measureRef} className="ws-title-measure" aria-hidden="true">{draft || ' '}</span>}

      <div className="ws-head-actions">
        <button className="ws-icon-btn" type="button" title="搜索" aria-label="搜索" onClick={onSearch}>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round">
            <circle cx="10.7" cy="10.7" r="6.4" />
            <path d="M15.5 15.5L20 20" />
          </svg>
        </button>
        <button className="ws-icon-btn" type="button" title="分享" aria-label="分享" onClick={onShare}>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
            <path d="M12 15V4M8 8l4-4 4 4" />
            <path d="M5 14v5a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-5" />
          </svg>
        </button>
        <button
          className={`ws-icon-btn${traceActive ? ' on' : ''}`}
          type="button"
          title={traceLabel}
          aria-label={traceLabel}
          onClick={onTrace}
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round">
            <path d="M4 6h9M4 12h6M4 18h10" />
            <circle cx="17.5" cy="6" r="1.6" />
            <circle cx="14.5" cy="12" r="1.6" />
            <circle cx="18.5" cy="18" r="1.6" />
          </svg>
          {traceBadge != null && <span className="ws-trace-badge">{traceBadge}</span>}
        </button>
        <button
          ref={historyBtnRef}
          className={`ws-icon-btn${history.open ? ' on' : ''}`}
          type="button"
          title="历史提问"
          aria-label="历史提问"
          onClick={onHistory}
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
            <circle cx="12" cy="12" r="8.4" />
            <path d="M12 7v5l3 2" />
          </svg>
        </button>
        {showPanelToggle && (
          <button
            className={`ws-icon-btn${rightCollapsed ? '' : ' on'}`}
            type="button"
            title={rightCollapsed ? '打开面板' : '收起面板'}
            aria-label={rightCollapsed ? '打开面板' : '收起面板'}
            onClick={onTogglePanel}
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinejoin="round">
              <rect x="3" y="4" width="18" height="16" rx="3" />
              <path d="M15 4v16" />
            </svg>
          </button>
        )}
      </div>

      {history.open && (
        <div ref={historyPanelRef} className="ws-history-panel">
          <div className="ws-hp-head">
            历史提问
            <span className="ws-hp-count">{history.candidates.length}</span>
            <button className="ws-hp-close" type="button" title="关闭" aria-label="关闭" onClick={history.onClose}>×</button>
          </div>
          {history.loading ? (
            <div className="ws-hp-state">加载中…</div>
          ) : history.error ? (
            <div className="ws-hp-state">
              <span>{history.error}</span>
              <button type="button" className="ws-hp-retry" onClick={history.onRetry}>重试</button>
            </div>
          ) : history.candidates.length === 0 ? (
            <div className="ws-hp-state">当前会话暂无提问</div>
          ) : (
            <div className="ws-hp-list">
              {history.candidates.map((c) => (
                <div key={c.id} className="ws-hp-item">
                  <button className="ws-hp-main" type="button" title={previewOf(c)} onClick={() => history.onJump(c.id)}>
                    <span className="ws-hp-q">{previewOf(c)}</span>
                    <span className="ws-hp-time">{timeOf(c.createdAt)}</span>
                  </button>
                  <div className="ws-hp-acts">
                    <button className="ws-hp-act" type="button" onClick={history.onCompact}>压缩</button>
                    <button className="ws-hp-act trim" type="button" onClick={history.onTrim}>裁剪</button>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  )
}
