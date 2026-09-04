import { useEffect, useRef, useState } from 'react'
import PdfWorker from 'pdfjs-dist/build/pdf.worker.min.mjs?worker'
import type { PDFDocumentProxy, PDFPageProxy } from 'pdfjs-dist'
import { BASE_URL } from '@/api/rest'
import { usePreviewStore, type PreviewTab } from '@/stores/previewStore'

// pdf.js worker 单例（vite `?worker` 端口方式 · `?url` 的 ESM worker 在 dev 不可靠会白屏）
let pdfWorkerPort: Worker | null = null
function ensureWorker(pdfjs: { GlobalWorkerOptions: { workerPort: Worker | null } }) {
  if (!pdfjs.GlobalWorkerOptions.workerPort) {
    if (!pdfWorkerPort) pdfWorkerPort = new PdfWorker()
    pdfjs.GlobalWorkerOptions.workerPort = pdfWorkerPort
  }
}

/** PDF 动态预览：pdf.js 渲染【全部页】纵向排列（上下滚动浏览所有页）· 每页 fit-width ·
 *  ResizeObserver 容器宽变 → 停止 200ms 后按新宽重渲染（拖右栏实时缩放，canvas CSS 拉伸过渡不卡帧） */
function PdfPreview({ url }: { url: string }) {
  const scrollRef = useRef<HTMLDivElement>(null)
  const [pdfDoc, setPdfDoc] = useState<PDFDocumentProxy | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const [renderKey, setRenderKey] = useState(0)
  const [curPage, setCurPage] = useState(1)
  const [jumpVal, setJumpVal] = useState('1')

  // 加载 PDF（path/base64 已由外层转 blob/后端 url · fetch → ArrayBuffer）
  useEffect(() => {
    let cancelled = false
    setErr(null)
    setPdfDoc(null)
    ;(async () => {
      try {
        const pdfjs = await import('pdfjs-dist')
        ensureWorker(pdfjs as { GlobalWorkerOptions: { workerPort: Worker | null } })
        const res = await fetch(url)
        if (!res.ok) throw new Error(`HTTP ${res.status}`)
        const buf = await res.arrayBuffer()
        const doc = await pdfjs.getDocument({ data: buf }).promise
        if (cancelled) return
        setPdfDoc(doc)
      } catch (e) {
        if (!cancelled) setErr(e instanceof Error ? e.message : String(e))
      }
    })()
    return () => { cancelled = true }
  }, [url])

  // 渲染所有页 fit-width（renderKey 变 = 容器宽变化 → 全部按新宽重渲染）
  useEffect(() => {
    if (!pdfDoc) return
    const wrap = scrollRef.current
    if (!wrap) return
    let cancelled = false
    const w = wrap.clientWidth || 300
    ;(async () => {
      try {
        wrap.innerHTML = ''
        // dpr 参与位图分辨率：高分屏（Retina/4K）下 canvas 物理像素 = 逻辑宽 × dpr，否则放大模糊
        const dpr = window.devicePixelRatio || 1
        for (let i = 1; i <= pdfDoc.numPages; i++) {
          if (cancelled) return
          const page: PDFPageProxy = await pdfDoc.getPage(i)
          const base = page.getViewport({ scale: 1 })
          const scale = Math.max(0.05, ((w - 8) / base.width) * dpr)
          const vp = page.getViewport({ scale })
          const canvas = document.createElement('canvas')
          canvas.className = 'pdf-page-canvas'
          canvas.dataset.page = String(i)
          canvas.width = Math.floor(vp.width)
          canvas.height = Math.floor(vp.height)
          canvas.title = `第 ${i} 页`
          wrap.appendChild(canvas)
          // pdf.js v6：render 参数用 canvas（内部取 context）
          await page.render({ canvas, viewport: vp }).promise
        }
      } catch (e) {
        if (!cancelled) setErr(e instanceof Error ? e.message : String(e))
      }
    })()
    return () => { cancelled = true }
  }, [pdfDoc, renderKey])

  // 容器宽变化（拖右栏）→ debounce 后重渲染所有页（拖拽中 canvas CSS 拉伸过渡）
  useEffect(() => {
    const wrap = scrollRef.current
    if (!wrap || !pdfDoc) return
    let timer = 0
    const ro = new ResizeObserver(() => {
      clearTimeout(timer)
      timer = window.setTimeout(() => setRenderKey((k) => k + 1), 200)
    })
    ro.observe(wrap)
    return () => { ro.disconnect(); clearTimeout(timer) }
  }, [pdfDoc])

  // 滚动 → 计算当前页（视口垂直中点所在页 · 顶部显示 当前/总 + 可输入跳转）
  const onScroll = () => {
    const wrap = scrollRef.current
    if (!wrap) return
    const midY = wrap.getBoundingClientRect().top + wrap.clientHeight / 2
    const canvases = wrap.querySelectorAll<HTMLCanvasElement>('.pdf-page-canvas')
    for (const c of canvases) {
      const r = c.getBoundingClientRect()
      if (r.top <= midY && r.bottom >= midY) {
        const p = Number(c.dataset.page) || 1
        if (p !== curPage) { setCurPage(p); setJumpVal(String(p)) }
        break
      }
      if (r.top > midY) break // 视口在更上方页（顶部留白）→ 保持当前
    }
  }
  // 输入页码跳转（回车/失焦）
  const jumpTo = () => {
    const wrap = scrollRef.current
    const p = Math.max(1, Math.min(pdfDoc?.numPages ?? 1, parseInt(jumpVal, 10) || 1))
    const el = wrap?.querySelector<HTMLCanvasElement>(`.pdf-page-canvas[data-page="${p}"]`)
    el?.scrollIntoView({ block: 'start' })
    setCurPage(p)
    setJumpVal(String(p))
  }

  return (
    <div className="pdf-preview">
      <div className="pdf-head">
        <span className="pdf-hint">跳转</span>
        <input
          className="pdf-jump"
          value={jumpVal}
          onChange={(e) => setJumpVal(e.target.value.replace(/[^\d]/g, ''))}
          onBlur={jumpTo}
          onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); jumpTo() } }}
          title="输入页码跳转"
        />
        <span className="pdf-pageno">/ {pdfDoc?.numPages ?? '…'}</span>
        <span className="spacer" />
        <span className="pdf-hint">拖右栏实时缩放</span>
      </div>
      <div className="pdf-scroll" ref={scrollRef} onScroll={onScroll}>
        {err ? <div className="rp-err">PDF 加载失败：{err}</div> : !pdfDoc ? <div className="rp-hint">PDF 加载中…</div> : null}
      </div>
    </div>
  )
}

/** 右侧覆盖预览（占据整个右栏）：HTML 运行结果（sandbox iframe）· PDF（pdf.js 全部页滚动 + 动态 fit-width）·
 *  docx（docx-preview）· 视频/音频（原生播放器）。
 *  附件内容源：path（local-read 本地读盘）→ base64（发送时）→ url（F5 后端内容端点）。关闭恢复右侧原 tab。 */
export function RightPreview({ preview }: { preview: PreviewTab }) {
  const close = usePreviewStore((s) => s.close)
  const item = preview.kind === 'attachment' ? preview.item : undefined
  const isDocx = !!item && /\.docx$/i.test(item.filename)
  const isPdf = !!item && (item.type === 'pdf' || /\.pdf$/i.test(item.filename))

  const [playUrl, setPlayUrl] = useState<string | null>(null)
  const [docxLoading, setDocxLoading] = useState(false)
  const [docxErr, setDocxErr] = useState<string | null>(null)
  const docxBoxRef = useRef<HTMLDivElement>(null)

  const readLocal = async (p: string): Promise<Uint8Array> => {
    const { readFile } = await import('@tauri-apps/plugin-fs')
    return readFile(p)
  }
  const backendUrl = (u: string) => (u.startsWith('http') ? u : `${BASE_URL}${u}`)

  // 附件非 docx → playUrl（path 本地 blob / base64 dataURL / url 后端）
  useEffect(() => {
    if (!item || isDocx) return
    let cancelled = false
    let localUrl: string | null = null
    if (item.path) {
      readLocal(item.path)
        .then((b) => { if (cancelled) return; localUrl = URL.createObjectURL(new Blob([b.slice()], { type: item.mediaType ?? 'application/octet-stream' })); setPlayUrl(localUrl) })
        .catch(() => { if (!cancelled) setPlayUrl(null) })
    } else if (item.base64) {
      setPlayUrl(`data:${item.mediaType || 'application/octet-stream'};base64,${item.base64}`)
    } else if (item.url) {
      setPlayUrl(backendUrl(item.url))
    } else setPlayUrl(null)
    return () => { cancelled = true; if (localUrl) URL.revokeObjectURL(localUrl) }
  }, [item, isDocx])

  // docx → docx-preview 渲染
  useEffect(() => {
    if (!item || !isDocx) return
    let cancelled = false
    setDocxLoading(true)
    setDocxErr(null)
    const run = async () => {
      try {
        let bytes: Uint8Array
        if (item.path) bytes = await readLocal(item.path)
        else if (item.base64) {
          const bin = atob(item.base64)
          bytes = new Uint8Array(bin.length)
          for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i)
        } else if (item.url) {
          const res = await fetch(backendUrl(item.url))
          if (!res.ok) throw new Error(`HTTP ${res.status}`)
          bytes = new Uint8Array(await res.arrayBuffer())
        } else throw new Error('附件内容不可用')
        if (cancelled || !docxBoxRef.current) return
        const { renderAsync: ra } = await import('docx-preview')
        await ra(new Blob([bytes.slice()]), docxBoxRef.current)
      } catch (e) {
        if (!cancelled) setDocxErr(e instanceof Error ? e.message : String(e))
      } finally {
        if (!cancelled) setDocxLoading(false)
      }
    }
    void run()
    return () => { cancelled = true }
  }, [item, isDocx])

  return (
    <div className="rp-wrap">
      <div className="rp-head">
        <span className="rp-title" title={preview.title}>{preview.title}</span>
        <span className="spacer" />
        <button className="rp-close" onClick={close} title="关闭预览（恢复右侧原 tab）">✕</button>
      </div>
      <div className="rp-body">
        {preview.kind === 'html' ? (
          <iframe
            className="rp-frame"
            sandbox="allow-scripts allow-modals allow-forms allow-popups"
            srcDoc={preview.code ?? ''}
            title="HTML 运行预览"
          />
        ) : item?.type === 'video' && playUrl ? (
          <video controls src={playUrl} className="rp-media" />
        ) : item?.type === 'audio' && playUrl ? (
          <audio controls src={playUrl} className="rp-audio" />
        ) : isPdf && playUrl ? (
          <PdfPreview url={playUrl} />
        ) : isDocx ? (
          docxLoading
            ? <div className="rp-hint">Word 渲染中…</div>
            : docxErr
              ? <div className="rp-err">Word 渲染失败：{docxErr}</div>
              : <div ref={docxBoxRef} className="rp-docx" />
        ) : (
          <div className="rp-err">
            附件内容不可用（local-read 请确认本地文件存在；F5 重拉需后端内容端点）
            {/* TEMP 诊断：定位附件走了哪条通道 */}
            <div className="apv-debug">fields: path={!!item?.path} base64={!!item?.base64} url={item?.url ?? 'null'} contentId={item?.contentId ?? 'null'} type={item?.type}</div>
          </div>
        )}
      </div>
    </div>
  )
}
