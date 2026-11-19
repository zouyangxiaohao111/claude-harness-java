import { useEffect, useMemo, useState } from 'react'
import { BASE_URL } from '@/api/rest'
import { PdfPreview } from '@/components/preview/PdfPreview'
import { DocxPreview } from '@/components/preview/DocxPreview'
import { ExcelPreview } from '@/components/preview/ExcelPreview'
import { MarkdownTextV2 } from '@/markdown/vendor/renderer'
import { previewKindOfAttachment } from '@/utils/standalonePreview'

/** 待发附件的可预览载荷（Composer `PendingAttachment` 的子集）。 */
export interface AttachPreviewSource {
  filename: string
  mediaType?: string
  /** ≤5MB 直传内容（dataURL 或纯 base64） */
  base64?: string
  /** local-read 模式本地绝对路径 */
  path?: string
  /** >5MB 上传后的附件表 id */
  contentId?: string
}

/**
 * 待发附件预览 · 与发送后共用同一套渲染组件（PdfPreview / DocxPreview / ExcelPreview / 图片）。
 *
 * <p>⛔ 不走 `openStandalone`：那是独立窗口，载荷经 localStorage 传递，≤5MB 附件的 base64
 * 会撑爆 quota（写失败 = 预览空白）。待发阶段字节就在手边（base64 / path / contentId），
 * 当前窗口直接渲染最可靠。
 */
export function AttachPreviewModal({ att, sessionId, onClose }: {
  att: AttachPreviewSource
  sessionId: string
  onClose: () => void
}) {
  const [bytes, setBytes] = useState<Uint8Array | null>(null)
  const [url, setUrl] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const kind = previewKindOfAttachment(att)
  /** markdown：字节 → 文本（与主对话同一渲染器） */
  const mdText = useMemo(
    () => (kind === 'markdown' && bytes ? new TextDecoder('utf-8').decode(bytes) : null),
    [kind, bytes],
  )

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  useEffect(() => {
    let cancelled = false
    let objUrl: string | null = null
    setBytes(null)
    setUrl(null)
    setError(null)
    ;(async () => {
      try {
        // 图片：base64 直接当 dataURL（免一次字节解码）
        if (kind === 'image' && att.base64) {
          const src = /^data:/.test(att.base64)
            ? att.base64
            : `data:${att.mediaType || 'image/png'};base64,${att.base64}`
          if (!cancelled) setUrl(src)
          return
        }
        let b: Uint8Array
        if (att.base64) {
          const raw = att.base64.replace(/^data:[^;]+;base64,/, '')
          const bin = atob(raw)
          b = new Uint8Array(bin.length)
          for (let i = 0; i < bin.length; i++) b[i] = bin.charCodeAt(i)
        } else if (att.path) {
          const { readFile } = await import('@tauri-apps/plugin-fs')
          b = await readFile(att.path)
        } else if (att.contentId) {
          const res = await fetch(`${BASE_URL}/attachments/content/${sessionId}/${att.contentId}`)
          if (!res.ok) throw new Error(`HTTP ${res.status}`)
          b = new Uint8Array(await res.arrayBuffer())
        } else {
          throw new Error('附件内容不可用（缺少 base64 / path / contentId）')
        }
        if (cancelled) return
        if (kind === 'pdf' || kind === 'image' || kind === 'video' || kind === 'audio') {
          objUrl = URL.createObjectURL(new Blob([b.slice()], {
            type: att.mediaType || (kind === 'pdf' ? 'application/pdf' : 'application/octet-stream'),
          }))
          setUrl(objUrl)
        } else {
          setBytes(b)
        }
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e))
      }
    })()
    return () => {
      cancelled = true
      if (objUrl) URL.revokeObjectURL(objUrl)
    }
  }, [att, sessionId, kind])

  return (
    <div className="overlay-backdrop artifact-overlay" onClick={onClose}>
      <div className="attach-preview-modal" onClick={(e) => e.stopPropagation()}>
        <div className="attach-preview-head">
          <span className="attach-preview-title" title={att.filename}>{att.filename}</span>
          <button type="button" className="attach-preview-close" onClick={onClose} title="关闭">×</button>
        </div>
        <div className="attach-preview-body">
          {error ? <div className="rp-err">{error}</div>
            : kind === 'pdf' && url ? <PdfPreview url={url} />
            : kind === 'docx' ? <DocxPreview bytes={bytes} error={error} />
            : kind === 'xlsx' ? <ExcelPreview bytes={bytes} error={error} />
            : kind === 'markdown' ? (
              <div className="attach-preview-md">
                {mdText != null
                  ? <MarkdownTextV2 text={mdText} className="content md markdown-prose" />
                  : <div className="rp-hint">加载中…</div>}
              </div>
            )
            : kind === 'image' && url ? <img className="attach-preview-img" src={url} alt={att.filename} />
            : kind === 'video' && url ? <video className="attach-preview-media" controls src={url} />
            : kind === 'audio' && url ? <audio className="attach-preview-media" controls src={url} />
            : <div className="rp-hint">加载中…</div>}
        </div>
      </div>
    </div>
  )
}
