/**
 * 用户气泡里技能 chip / 内联 `/name` 的「技能说明」浮层（用户 2026-11-26：技能能渲染但点不了）。
 *
 * <p>设计稿：sess-0abfe0a1 deliverables/技能召唤消息气泡设计.html ——「点任意技能 chip
 * 可看技能说明浮层（模拟点开 SKILL.md）」。这里不是模拟：正文走
 * {@code GET /skills/{name}/content}（带 sessionId，project 级技能才查得到）。
 *
 * <p>正文为空 / 请求失败时只显示已有的 description + 可读错误，⛔ 不编造正文。
 * 用 portal 挂到 body：消息容器有 overflow 裁剪，浮层挂在气泡里会被切掉。
 */
import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { skillApi } from '@/api/skills'
import { MarkdownTextV2 } from '@/markdown/vendor/renderer'

/** 触发浮层的 chip 的视口矩形（点击瞬间取，避免滚动后错位）。 */
export interface SkillPreviewRect {
  left: number
  top: number
  bottom: number
}

export interface SkillPreviewPopoverProps {
  /** 注册名（不含 `/`） */
  name: string
  displayName?: string | null
  icon?: string | null
  description?: string | null
  rect: SkillPreviewRect
  sessionId?: string | null
  onClose: () => void
}

interface PreviewState {
  state: 'loading' | 'ok' | 'error'
  markdown: string
  contentPath: string | null
  error: string | null
}

const POPOVER_WIDTH = 340

export function SkillPreviewPopover({
  name, displayName, icon, description, rect, sessionId, onClose,
}: SkillPreviewPopoverProps) {
  const boxRef = useRef<HTMLDivElement | null>(null)
  const [preview, setPreview] = useState<PreviewState>({
    state: 'loading', markdown: '', contentPath: null, error: null,
  })

  useEffect(() => {
    let alive = true
    setPreview({ state: 'loading', markdown: '', contentPath: null, error: null })
    skillApi.content(name, sessionId ?? undefined).then(
      (r) => {
        if (!alive) return
        const md = r?.markdown ?? r?.content ?? ''
        setPreview({
          state: md.trim() ? 'ok' : 'error',
          markdown: md,
          contentPath: r?.contentPath ?? null,
          error: md.trim() ? null : '正文为空（后端未返回内容）',
        })
      },
      (e) => {
        if (!alive) return
        setPreview({
          state: 'error', markdown: '', contentPath: null,
          error: e instanceof Error ? e.message : String(e),
        })
      },
    )
    return () => { alive = false }
  }, [name, sessionId])

  // 关闭出口：点浮层外 / Esc（捕获阶段，免得被编辑框等上层处理器抢先）
  useEffect(() => {
    const onDown = (e: MouseEvent) => {
      if (boxRef.current && !boxRef.current.contains(e.target as Node)) onClose()
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      e.preventDefault()
      e.stopPropagation()
      onClose()
    }
    document.addEventListener('mousedown', onDown)
    window.addEventListener('keydown', onKey, true)
    return () => {
      document.removeEventListener('mousedown', onDown)
      window.removeEventListener('keydown', onKey, true)
    }
  }, [onClose])

  // 贴 chip 下方；靠近视口底部时翻到上方（否则只能露出一条缝）
  const left = Math.max(8, Math.min(rect.left, window.innerWidth - POPOVER_WIDTH - 8))
  const spaceBelow = window.innerHeight - rect.bottom - 16
  const spaceAbove = rect.top - 16
  const placeAbove = spaceBelow < 220 && spaceAbove > spaceBelow
  const pos = placeAbove
    ? { bottom: window.innerHeight - rect.top + 8, maxHeight: Math.max(180, spaceAbove - 8) }
    : { top: rect.bottom + 8, maxHeight: Math.max(180, spaceBelow) }

  return createPortal(
    <div
      className="skill-pop"
      ref={boxRef}
      role="dialog"
      aria-label="技能说明"
      style={{ left, width: POPOVER_WIDTH, ...pos }}
    >
      <div className="skill-pop-head">
        {icon
          ? <img className="skill-pop-icon" src={icon} alt="" />
          : <span className="skill-pop-icon-fallback" aria-hidden="true">✦</span>}
        <div className="skill-pop-title">
          <b>{(displayName ?? '').trim() || name}</b>
          <span className="skill-pop-name">/{name}</span>
        </div>
        <button type="button" className="skill-pop-close" title="关闭" aria-label="关闭" onClick={onClose}>✕</button>
      </div>
      <div className="skill-pop-path" title={preview.contentPath ?? undefined}>
        {preview.contentPath || `skills/${name}/SKILL.md`}
      </div>
      {description && <div className="skill-pop-desc">{description}</div>}
      <div className="skill-pop-body">
        {preview.state === 'loading' && <div className="skill-pop-hint">读取 SKILL.md…</div>}
        {preview.state === 'error' && <div className="skill-pop-hint error">正文不可用：{preview.error}</div>}
        {preview.state === 'ok' && (
          <MarkdownTextV2 text={preview.markdown} className="content md skill-pop-md" />
        )}
      </div>
    </div>,
    document.body,
  )
}