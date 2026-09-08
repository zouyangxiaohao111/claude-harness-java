import { useEffect, useRef, useState } from 'react'

interface DocxPreviewProps {
  /** docx 文件字节（null = 未就绪，显示加载中） */
  bytes: Uint8Array | null
  /** 取字节阶段失败信息（解析前） */
  error?: string | null
}

/** Word 预览：docx-preview 渲染字节（自 RightPreview/FileViewModal 收敛的单消费者）。 */
export function DocxPreview({ bytes, error }: DocxPreviewProps) {
  const boxRef = useRef<HTMLDivElement>(null)
  const [loading, setLoading] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const showErr = error || err

  useEffect(() => {
    if (!bytes) { setLoading(false); setErr(null); return }
    let cancelled = false
    setErr(null)
    setLoading(true)
    ;(async () => {
      try {
        if (cancelled || !boxRef.current) return
        const { renderAsync: ra } = await import('docx-preview')
        await ra(new Blob([bytes.slice()]), boxRef.current)
      } catch (e) {
        if (!cancelled) setErr(e instanceof Error ? e.message : String(e))
      } finally {
        if (!cancelled) setLoading(false)
      }
    })()
    return () => { cancelled = true }
  }, [bytes])

  return (
    <div className="rp-docx">
      {(!bytes || loading) && !showErr && <div className="rp-docx-overlay">Word 渲染中…</div>}
      {showErr && <div className="rp-docx-overlay rp-err">Word 渲染失败：{showErr}</div>}
      <div ref={boxRef} className="rp-docx-body" />
    </div>
  )
}
