import { useEffect, useRef, useState } from 'react'
import type { Univer } from '@univerjs/presets'

interface ExcelPreviewProps {
  /** xlsx 文件字节（null = 字节未就绪，显示加载中） */
  bytes: Uint8Array | null
  /** 字节解析失败信息（调用方解析阶段失败时传入） */
  error?: string | null
}

/** Excel 预览：@univerjs/presets sheets-core 渲染 xlsx 字节 · 实例随容器尺寸自适应 · 卸载时 dispose */
export function ExcelPreview({ bytes, error }: ExcelPreviewProps) {
  const boxRef = useRef<HTMLDivElement>(null)
  const [loading, setLoading] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const showErr = error || err

  useEffect(() => {
    if (!bytes) { setErr(null); setLoading(false); return }
    let disposed = false
    let univer: Univer | null = null
    setErr(null)
    setLoading(true)
    ;(async () => {
      try {
        await import('@univerjs/presets/lib/styles/preset-sheets-core.css')
        const [{ createUniver, LocaleType }, { UniverSheetsCorePreset }, { importFile }] = await Promise.all([
          import('@univerjs/presets'),
          import('@univerjs/preset-sheets-core'),
          import('univer-file-import'),
        ])
        if (disposed || !boxRef.current) return
        const { univer: u, univerAPI } = createUniver({
          locale: LocaleType.ZH_CN,
          presets: [
            UniverSheetsCorePreset({
              container: boxRef.current,
              workerURL: new URL('@univerjs/preset-sheets-core/worker', import.meta.url).toString(),
            }),
          ],
        })
        univer = u
        // importFile 按 file.name 扩展名判定格式，xlsx 字节需包成带名的 File
        const { workbookData } = await importFile(new File([bytes.slice()], 'preview.xlsx'), { includeImages: false })
        if (disposed) return
        univerAPI.createWorkbook(workbookData)
      } catch (e) {
        if (!disposed) setErr(e instanceof Error ? e.message : String(e))
      } finally {
        if (!disposed) setLoading(false)
      }
    })()
    return () => { disposed = true; univer?.dispose() }
  }, [bytes])

  return (
    <div className="excel-preview">
      {(loading || !bytes) && !showErr && <div className="rp-docx-overlay">表格加载中…</div>}
      {showErr && <div className="rp-docx-overlay rp-err">表格加载失败：{showErr}</div>}
      <div ref={boxRef} className="excel-preview-body" />
    </div>
  )
}
