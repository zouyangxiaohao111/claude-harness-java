import { useEffect, useState } from 'react'
import { artifactsApi, type ArtifactContent, type SessionArtifactDto } from '@/api/artifacts'
import { ApiError } from '@/api/rest'
import { MarkdownText } from '@/markdown/MarkdownText'
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
 * <p>渲染口径：markdown → MarkdownText；html → 沙箱 iframe（{@code sandbox=""} 禁脚本）；
 * 其余文本 → 原文 {@code <pre>}。二进制不会进本弹窗（App 侧改为复制路径 + 提示）。
 *
 * <p>编辑：文本类产物可就地改并 {@code PUT /content} —— <b>写回归档副本</b>，不动项目源文件。
 */
export function ArtifactPreviewModal({ sessionId, artifact, initialEdit = false, close }: ArtifactPreviewModalProps) {
  const [data, setData] = useState<ArtifactContent | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [editing, setEditing] = useState(initialEdit)
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
      setEditing(false)
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
  const canEdit = editing && editable

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
            {!editing && editable && (
              <button className="diff-tbtn" onClick={() => setEditing(true)} title="编辑（保存写回归档副本）">编辑</button>
            )}
            {canEdit && (
              <>
                <button className="diff-tbtn save" onClick={() => void save()} disabled={saving}>
                  {saving ? '保存中…' : '保存'}
                </button>
                <button className="diff-tbtn" onClick={() => setEditing(false)} disabled={saving}>取消</button>
              </>
            )}
            <button className="artifact-modal-close" onClick={close} aria-label="关闭">✕</button>
          </div>
        </div>
        <div className="artifact-modal-body">
          {!data && !error && <div className="artifact-modal-state">正在读取产物内容…</div>}
          {error && <div className="artifact-modal-state artifact-modal-error">{error}</div>}
          {data?.binary && <div className="artifact-modal-state">二进制产物无法在浏览器预览</div>}
          {canEdit ? (
            <textarea
              className="artifact-modal-editor"
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              spellCheck={false}
            />
          ) : data && !data.binary && data.content != null && (
            isMarkdown
              ? <MarkdownText text={data.content} className="content md artifact-modal-md" />
              : isHtml
                ? <iframe className="artifact-modal-frame" title={artifact.title} sandbox="" srcDoc={data.content} />
                : <pre className="artifact-modal-pre">{data.content}</pre>
          )}
        </div>
      </div>
    </div>
  )
}
