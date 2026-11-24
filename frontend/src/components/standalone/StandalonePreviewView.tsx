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
import type { Window as TauriWindow } from '@tauri-apps/api/window'
import { usePreviewContent } from '@/components/preview/usePreviewContent'
import { PdfPreview } from '@/components/preview/PdfPreview'
import { DocxPreview } from '@/components/preview/DocxPreview'
import { ExcelPreview } from '@/components/preview/ExcelPreview'
import { IS_TAURI, STANDALONE_LS_KEY, readStandalonePayload, type StandalonePayload } from '@/utils/standalonePreview'
import { MarkdownTextV2 } from '@/markdown/vendor/renderer'

export function StandalonePreviewView() {
  const [payload, setPayload] = useState<StandalonePayload | null>(readStandalonePayload)
  const content = usePreviewContent(payload)

  // 无边框窗口（decorations:false）自绘标题栏控制：与知识库独立窗同一做法
  // —— 系统标题栏底色不受页面 CSS 控制，会顶出一条异色。
  const [win, setWin] = useState<TauriWindow | null>(null)
  const [maximized, setMaximized] = useState(false)
  useEffect(() => {
    if (!IS_TAURI) return
    let alive = true
    let un: (() => void) | undefined
    void (async () => {
      try {
        const { getCurrentWindow } = await import('@tauri-apps/api/window')
        const w = getCurrentWindow()
        if (!alive) return
        setWin(w)
        try { setMaximized(await w.isMaximized()) } catch { /* 窗口正在关闭 */ }
        un = await w.onResized(() => {
          void w.isMaximized().then((v) => { if (alive) setMaximized(v) }).catch(() => {})
        })
      } catch { /* 非桌面环境 */ }
    })()
    return () => { alive = false; un?.() }
  }, [])
  const act = (fn: 'minimize' | 'toggleMaximize' | 'close') => {
    if (!win) return
    void win[fn]().catch((e) => console.warn('[standalone] 窗口操作被拒', fn, e))
  }

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
  ) : type === 'markdown' ? (
    // Markdown 与主对话同一渲染器 + 同一套正文排版（content md markdown-prose）
    <div className="stand-md">
      {code != null
        ? <MarkdownTextV2 text={code} className="content md markdown-prose" />
        : <div className="rp-hint">加载中…</div>}
    </div>
  ) : type === 'text' ? (
    <pre className="stand-text">{code ?? ''}</pre>
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
      <div className="stand-bar" data-tauri-drag-region>
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
        {IS_TAURI && (
          <span className="stand-win-actions">
            <button type="button" className="stand-win-btn" title="最小化" aria-label="最小化" onClick={() => act('minimize')}>
              <svg viewBox="0 0 10 10" width="10" height="10" aria-hidden="true"><path d="M1 5h8" stroke="currentColor" strokeWidth="1" /></svg>
            </button>
            <button type="button" className="stand-win-btn" title={maximized ? '向下还原' : '最大化'} aria-label="最大化" onClick={() => act('toggleMaximize')}>
              {maximized ? (
                <svg viewBox="0 0 10 10" width="10" height="10" fill="none" stroke="currentColor" strokeWidth="1" aria-hidden="true">
                  <rect x="1.5" y="3.5" width="5" height="5" />
                  <path d="M3.5 3.5v-2h5v5h-2" />
                </svg>
              ) : (
                <svg viewBox="0 0 10 10" width="10" height="10" fill="none" stroke="currentColor" strokeWidth="1" aria-hidden="true">
                  <rect x="1.5" y="1.5" width="7" height="7" />
                </svg>
              )}
            </button>
            <button type="button" className="stand-win-btn is-close" title="关闭" aria-label="关闭" onClick={() => act('close')}>
              <svg viewBox="0 0 10 10" width="10" height="10" aria-hidden="true"><path d="M1.5 1.5l7 7M8.5 1.5l-7 7" stroke="currentColor" strokeWidth="1" /></svg>
            </button>
          </span>
        )}
      </div>
      <div className="stand-body">{body}</div>
    </div>
  )
}
