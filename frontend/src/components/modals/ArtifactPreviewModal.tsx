import { useEffect, useState } from 'react'
import { artifactsApi, type ArtifactContent, type SessionArtifactDto } from '@/api/artifacts'
import { ApiError } from '@/api/rest'
import { MarkdownTextV2 } from '@/markdown/vendor/renderer'
import { formatFileSize } from '@/utils/format'

interface ArtifactPreviewModalProps {
  /** 会话 id（产物归属校验在后端：不属于该会话 → 404） */
  sessionId: string
  artifact: SessionArtifactDto
  /** 进来就是编辑态（卡片上的「编辑」按钮） */
  initialEdit?: boolean
  close: () => void
}

/**
 * 产物只读预览 / 编辑（浏览器 / dev 通道）。
 *
 * <p><b>WHY 不走 FileViewModal</b>：那是项目文件通道（读/写 {@code /projects/{id}/file}），
 * 而产物的 {@code localPath} 是<b>归档副本</b>的绝对路径（{@code {configHome}/artifacts/<sessionId>/…}），
 * 不在任何项目根内 —— 走项目通道必然报「文件路径非法」。
 *
 * <p><b>三态（2026-11-01 用户要求「默认预览，可以选择编辑 / 打开源代码模式」）</b>：
 * <ul>
 *   <li><b>预览</b>（默认）：markdown → MarkdownText 渲染；html → 沙箱 iframe；其余文本 → {@code <pre>}；</li>
 *   <li><b>源码</b>：原样文本（只读），便于核对 Markdown 原文；</li>
 *   <li><b>编辑</b>：可直接改，保存 {@code PUT /content} 写回归档副本（不动项目源文件）。</li>
 * </ul>
 *
 * <p>⛔ 旧实现的默认态取决于调用方传不传 {@code initialEdit}，且「非编辑」时 markdown 才渲染 ——
 * 但用户看到的仍是「点开就是一块代码/文本框」的观感。现在把「预览」做成显式的默认模式。
 */
export function ArtifactPreviewModal({ sessionId, artifact, initialEdit = false, close }: ArtifactPreviewModalProps) {
  const [data, setData] = useState<ArtifactContent | null>(null)
  const [error, setError] = useState<string | null>(null)
  /**
   * 当前模式：preview（默认）/ source（只读源码）/ edit（可编辑）。
   *
   * <p>用户 2026-11-01：<b>默认预览</b>，需要时可切「编辑」或「源码」。
   * 卡片上的「编辑」按钮仍走 {@code initialEdit=true} → 直接进 edit。
   */
  const [mode, setMode] = useState<'preview' | 'source' | 'edit'>(initialEdit ? 'edit' : 'preview')
  const [draft, setDraft] = useState('')
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    let alive = true
    setData(null)
    setError(null)
    artifactsApi
      .content(sessionId, artifact.id)
      .then((d) => {
        if (!alive) return
        setData(d)
        setDraft(d.content ?? '')
      })
      .catch((e) => { if (alive) setError(e instanceof ApiError ? e.userMessage() : String(e)) })
    return () => { alive = false }
  }, [sessionId, artifact.id])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') close() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [close])

  const save = async () => {
    setSaving(true)
    setError(null)
    try {
      await artifactsApi.saveContent(sessionId, artifact.id, draft)
      setData((prev) => (prev ? { ...prev, content: draft } : prev))
      setMode('preview')   // 保存后回到预览：用户改完最想看到的是渲染结果
    } catch (e) {
      setError(e instanceof ApiError ? e.userMessage() : String(e))
    } finally {
      setSaving(false)
    }
  }

  const isMarkdown = data?.mime === 'text/markdown'
  const isHtml = data?.mime === 'text/html'
  /** 可编辑 = 文本类（binary 产物后端 PUT /content 会 400 ⇒ 不给编辑入口） */
  const editable = !!data && !data.binary && data.content != null
  /** 真正进入编辑态：内容还没到 / 是二进制 ⇒ 不给编辑框（否则会显示一个空 textarea） */
  const isEditing = mode === 'edit' && editable

  return (
    <div className="overlay-backdrop artifact-overlay" onClick={close}>
      <div className="artifact-modal" onClick={(e) => e.stopPropagation()}>
        <div className="artifact-modal-header">
          <span className="artifact-modal-title" title={artifact.localPath ?? artifact.title}>
            {artifact.title}
          </span>
          {data && (
            <span className="artifact-modal-meta">
              {data.mime} · {formatFileSize(data.size)}
              {data.truncated ? ' · 已截断（仅显示前 512KB）' : ''}
            </span>
          )}
          <div className="artifact-modal-actions">
            {/* 模式切换：预览 / 源码（编辑/源码互斥，二者都是对原文的操作） */}
            {editable && mode !== 'edit' && (
              <div className="artifact-mode-switch" role="group" aria-label="查看模式">
                <button
                  className={`artifact-mode-btn${mode === 'preview' ? ' on' : ''}`}
                  onClick={() => setMode('preview')}
                  title="渲染预览"
                >
                  预览
                </button>
                <button
                  className={`artifact-mode-btn${mode === 'source' ? ' on' : ''}`}
                  onClick={() => setMode('source')}
                  title="查看源代码"
                >
                  源码
                </button>
              </div>
            )}
            {mode !== 'edit' && editable && (
              <button className="diff-tbtn" onClick={() => setMode('edit')} title="编辑（保存写回归档副本）">编辑</button>
            )}
            {isEditing && (
              <>
                <button className="diff-tbtn save" onClick={() => void save()} disabled={saving}>
                  {saving ? '保存中…' : '保存'}
                </button>
                <button
                  className="diff-tbtn"
                  onClick={() => { setDraft(data?.content ?? ''); setMode('preview') }}
                  disabled={saving}
                  title="放弃修改并回到预览"
                >
                  取消
                </button>
              </>
            )}
            <button className="artifact-modal-close" onClick={close} aria-label="关闭">✕</button>
          </div>
        </div>
        <div className="artifact-modal-body">
          {!data && !error && <div className="artifact-modal-state">正在读取产物内容…</div>}
          {error && <div className="artifact-modal-state artifact-modal-error">{error}</div>}
          {data?.binary && <div className="artifact-modal-state">二进制产物无法在浏览器预览</div>}
          {isEditing ? (
            <textarea
              className="artifact-modal-editor"
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              spellCheck={false}
            />
          ) : mode === 'source' && data && !data.binary ? (
            /* 源码模式：原样文本（只读）—— 用户要核对 Markdown 原文时用 */
            <pre className="artifact-modal-pre">{data.content ?? ''}</pre>
          ) : data && !data.binary && data.content != null && (
            isMarkdown
              ? <MarkdownTextV2 text={data.content} className="content md artifact-modal-md markdown-prose" />
              : isHtml
                ? <iframe className="artifact-modal-frame" title={artifact.title} sandbox="" srcDoc={data.content} />
                : <pre className="artifact-modal-pre">{data.content}</pre>
          )}
        </div>
      </div>
    </div>
  )
}
