/**
 * 「独立预览窗口」整屏宿主（取代 StandaloneHtmlView）。
 * main.tsx 在 location.hash 命中 `#/standalone-preview` 时渲染本组件而非整 App——
 * 它不订阅 STOMP/不打扰主会话：对话侧继续跑，本窗口独立存在。
 *
 * 载荷 = 描述符（STANDALONE_LS_KEY）：主窗每次「打开」写入 → 本窗监听 storage + focus
 * 回读 → 自动刷到最新（开着一个 PDF 再点 HTML 运行 → 同窗切换为 HTML）。实际内容由
 * usePreviewContent 自行 fetch / 读盘，不进 localStorage。
 */
import { useEffect, useState } from 'react'
import { usePreviewContent } from '@/components/preview/usePreviewContent'
import { PdfPreview } from '@/components/preview/PdfPreview'
import { DocxPreview } from '@/components/preview/DocxPreview'
import { ExcelPreview } from '@/components/preview/ExcelPreview'
import { STANDALONE_LS_KEY, readStandalonePayload, type StandalonePayload } from '@/utils/standalonePreview'

export function StandalonePreviewView() {
  const [payload, setPayload] = useState<StandalonePayload | null>(readStandalonePayload)
  const content = usePreviewContent(payload)

  useEffect(() => {
    const onStorage = (e: StorageEvent) => {
      if (e.key === STANDALONE_LS_KEY && e.newValue) setPayload(readStandalonePayload())
    }
    const onFocus = () => setPayload(readStandalonePayload())
    window.addEventListener('storage', onStorage)
    window.addEventListener('focus', onFocus)
    return () => {
      window.removeEventListener('storage', onStorage)
      window.removeEventListener('focus', onFocus)
    }
  }, [])

  useEffect(() => {
    document.title = payload?.title || '独立预览'
  }, [payload?.title])

  const type = payload?.type
  const { code, url, bytes, loading, error } = content
  const showErr = !!error && !url && !bytes && !code

  const body = type === 'html' ? (
    <iframe
      className="shtml-frame"
      sandbox="allow-scripts allow-modals allow-forms allow-popups"
      srcDoc={code ?? ''}
      title="HTML 运行预览"
    />
  ) : showErr ? (
    <div className="rp-err">{error}</div>
  ) : loading && !url && !bytes ? (
    <div className="rp-hint">加载中…</div>
  ) : type === 'pdf' && url ? (
    <PdfPreview url={url} />
  ) : type === 'docx' ? (
    <DocxPreview bytes={bytes ?? null} error={error ?? null} />
  ) : type === 'xlsx' ? (
    <ExcelPreview bytes={bytes ?? null} error={error ?? null} />
  ) : type === 'image' && url ? (
    <img className="stand-img" src={url} alt={payload?.title || ''} draggable={false} />
  ) : type === 'video' && url ? (
    <video className="stand-media" controls src={url} />
  ) : type === 'audio' && url ? (
    <audio className="stand-audio" controls src={url} />
  ) : (
    <div className="rp-err">{error ?? '暂不支持预览该文件类型'}</div>
  )

  return (
    <div className="stand">
      <div className="stand-bar">
        <span className="stand-title" title={payload?.title || ''}>{payload?.title || '独立预览'}</span>
        <span className="stand-hint">在主界面再次点开会自动刷新本窗口</span>
        <span className="spacer" />
        <button
          type="button"
          className="stand-reload"
          title="重新加载（回读最新内容）"
          onClick={() => setPayload(readStandalonePayload())}
        >
          ↻
        </button>
      </div>
      <div className="stand-body">{body}</div>
    </div>
  )
}
