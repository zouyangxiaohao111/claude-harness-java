import { useCallback, useEffect, useState } from 'react'
import { sessionApi } from '@/api/sessions'
import type { SessionToolDto } from '@/api/types'
import { ApiError } from '@/api/rest'

interface Props {
  /** 当前会话 id（空串不拉取 · 切换时组件自动重新拉取） */
  sessionId: string
  showToast: (msg: string, type?: 'success' | 'info') => void
  /** 关闭面板（点击外部 / Esc / 头部 ×） */
  onClose: () => void
  /** 禁用工具数变化通知（供入口 chip 角标展示） */
  onCountChange?: (count: number) => void
}

/**
 * 核心编排工具（Agent/TaskStop/SendMessage）：后端禁用返回 400。
 * 前端对「禁用」操作直接灰显（禁了也白禁），但若工具已处于禁用态仍允许恢复。
 */
const CORE_ORCHESTRATION_TOOLS = new Set(['Agent', 'TaskStop', 'SendMessage'])
const isCoreTool = (name: string) => CORE_ORCHESTRATION_TOOLS.has(name)

/** 常用工具 → 图标路径（未命中回落通用齿轮） */
const TOOL_ICON_PATHS: Record<string, string> = {
  bash: 'M4 5l8 7-8 7M12 19h8',
  read: 'M4 5a2 2 0 012-2h4v18H6a2 2 0 01-2-2V5zM14 3h4a2 2 0 012 2v14a2 2 0 01-2 2h-4V3z',
  edit: 'M17 3l4 4L8 20l-5 1 1-5L17 3z',
  write: 'M17 3l4 4L8 20l-5 1 1-5L17 3z',
  glob: 'M3 7a2 2 0 012-2h4l2 2h8a2 2 0 012 2v8a2 2 0 01-2 2H5a2 2 0 01-2-2V7z',
  grep: 'M11 19a8 8 0 100-16 8 8 0 000 16zM21 21l-4.35-4.35',
  websearch: 'M12 2a10 10 0 100 20 10 10 0 000-20zM2 12h20M12 2a15 15 0 010 20 15 15 0 010-20z',
  webfetch: 'M12 2a10 10 0 100 20 10 10 0 000-20zM2 12h20M12 2a15 15 0 010 20 15 15 0 010-20z',
  task: 'M9 11l3 3L22 4M21 12v7a2 2 0 01-2 2H5a2 2 0 01-2-2V5a2 2 0 012-2h11',
  agent: 'M9 11l3 3L22 4M21 12v7a2 2 0 01-2 2H5a2 2 0 01-2-2V5a2 2 0 012-2h11',
  taskstop: 'M4 4h12v12H4zM8 8l4 4M12 8l-4 4',
  sendmessage: 'M22 2L11 13M22 2l-7 20-4-9-9-4 22-7z',
  todowrite: 'M9 11l3 3L22 4M21 12v7a2 2 0 01-2 2H5a2 2 0 01-2-2V5a2 2 0 012-2h11',
  skill: 'M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z',
  notebook: 'M6 4h12a1 1 0 011 1v15a1 1 0 01-1 1H6a1 1 0 01-1-1V5a1 1 0 011-1zM9 8h6M9 12h6',
}
const DEFAULT_TOOL_PATH = 'M12 2a10 10 0 100 20 10 10 0 000-20zM12 8v5M12 16h.01'

function ToolIcon({ name }: { name: string }) {
  const lower = name.toLowerCase()
  const hit = Object.keys(TOOL_ICON_PATHS).find((k) => lower.includes(k))
  const d = hit ? TOOL_ICON_PATHS[hit] : DEFAULT_TOOL_PATH
  return (
    <span className="tool-icon">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
        <path d={d} />
      </svg>
    </span>
  )
}

export function SessionToolsPanel({ sessionId, showToast, onClose, onCountChange }: Props) {
  const [tools, setTools] = useState<SessionToolDto[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [pending, setPending] = useState<string | null>(null)

  const load = useCallback(async () => {
    if (!sessionId) return
    setError(null)
    try {
      const list = await sessionApi.listTools(sessionId)
      setTools(list)
      onCountChange?.(list.filter((t) => t.disabled).length)
    } catch (e) {
      setError(e instanceof ApiError ? e.userMessage() : String(e))
      setTools(null)
      onCountChange?.(0)
    }
  }, [sessionId, onCountChange])

  // 会话切换 → 清空旧列表并重新拉取（监听 sessionId）
  useEffect(() => {
    setTools(null)
    void load()
  }, [load])

  // Esc 关闭
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const toggle = async (t: SessionToolDto) => {
    if (!sessionId || pending) return
    setPending(t.name)
    try {
      const updated = await sessionApi.setToolEnabled(sessionId, t.name, !t.disabled)
      const next = (tools ?? []).map((x) => (x.name === updated.name ? updated : x))
      setTools(next)
      onCountChange?.(next.filter((x) => x.disabled).length)
      showToast(updated.disabled ? `已禁用工具 ${updated.userFacingName}` : `已恢复工具 ${updated.userFacingName}`, 'success')
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    } finally {
      setPending(null)
    }
  }

  const disabledCount = tools?.filter((t) => t.disabled).length ?? 0

  return (
    <div className="tools-panel" role="dialog" aria-label="会话工具禁用/恢复">
      <div className="tools-panel-header">
        <span className="tools-panel-title">会话工具</span>
        {tools && <span className="tools-panel-count">已禁用 {disabledCount}</span>}
        <button className="tools-panel-close" onClick={onClose} title="关闭" aria-label="关闭">×</button>
      </div>
      {tools == null ? (
        <div className="tools-panel-state">
          {error ? (
            <div className="tools-panel-error">
              <span>{error}</span>
              <button className="tools-panel-retry" onClick={() => void load()}>重试</button>
            </div>
          ) : (
            <span className="tools-panel-loading">加载中…</span>
          )}
        </div>
      ) : tools.length === 0 ? (
        <div className="tools-panel-state">当前会话暂无可用工具</div>
      ) : (
        <div className="tools-panel-list">
          {tools.map((t) => {
            const coreLocked = isCoreTool(t.name) && !t.disabled
            const busy = pending === t.name
            return (
              <div key={t.name} className={`tool-row${t.disabled ? ' disabled' : ''}`}>
                <ToolIcon name={t.name} />
                <div className="tool-info">
                  <div className="tool-name">{t.userFacingName || t.name}</div>
                  <div className="tool-key">{t.name}</div>
                </div>
                <button
                  className={`toggle-btn ${t.disabled ? 'restore' : 'disable'}`}
                  disabled={coreLocked || busy}
                  onClick={() => void toggle(t)}
                  title={coreLocked ? '核心编排工具不可禁用' : (t.disabled ? '恢复此工具' : '禁用此工具')}
                >
                  {t.disabled ? '恢复' : '禁用'}
                </button>
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}
