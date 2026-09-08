import { useEffect, useRef, useState } from 'react'
import PdfWorker from 'pdfjs-dist/build/pdf.worker.min.mjs?worker'
import type { PDFDocumentProxy, PDFPageProxy } from 'pdfjs-dist'

// pdf.js worker 单例（vite `?worker` 端口方式 · `?url` 的 ESM worker 在 dev 不可靠会白屏）
let pdfWorkerPort: Worker | null = null
function ensureWorker(pdfjs: { GlobalWorkerOptions: { workerPort: Worker | null } }) {
  if (!pdfjs.GlobalWorkerOptions.workerPort) {
    if (!pdfWorkerPort) pdfWorkerPort = new PdfWorker()
    pdfjs.GlobalWorkerOptions.workerPort = pdfWorkerPort
  }
}

/** PDF 动态预览（自 RightPreview 整体搬移，供独立窗/右栏共用）：
 *  pdf.js 渲染【全部页】纵向排列（上下滚动浏览所有页）· 每页 fit-width ·
 *  ResizeObserver 容器宽变 → 停止 200ms 后按新宽重渲染。props 仅依赖单个 url（fetch）。 */
export function PdfPreview({ url }: { url: string }) {
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

  // 容器宽变化（拖右栏/独立窗缩放）→ debounce 后重渲染所有页
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
        <span className="pdf-hint">拖拽缩放</span>
      </div>
      <div className="pdf-scroll" ref={scrollRef} onScroll={onScroll}>
        {err ? <div className="rp-err">PDF 加载失败：{err}</div> : !pdfDoc ? <div className="rp-hint">PDF 加载中…</div> : null}
      </div>
    </div>
  )
}
