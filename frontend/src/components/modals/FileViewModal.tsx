import { useEffect, useRef, useState } from 'react'
import { projectApi, type FileContent } from '@/api/projects'
import { ApiError } from '@/api/rest'
import { monaco, monacoLangOf, MONACO_COMMON_OPTS } from '@/utils/monaco'
import { isDocxName, isXlsxName } from '@/utils/fileType'
import { ExcelPreview } from '@/components/right/ExcelPreview'
import { usePreviewStore } from '@/stores/previewStore'

interface FileViewModalProps {
  /** 项目 id（读文件） */
  projectId: string
  /** 文件相对路径（与文件树 path 一致） */
  path: string
  close: () => void
}

/** 项目文件查看/编辑（Monaco · 默认只读 · 「编辑」切换写回 PUT /projects/{id}/file · docx 走 docx-preview 只读渲染） */
export function FileViewModal({ projectId, path, close }: FileViewModalProps) {
  const [data, setData] = useState<FileContent | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [editing, setEditing] = useState(false)
  const [saving, setSaving] = useState(false)
  const editorRef = useRef<monaco.editor.IStandaloneCodeEditor | null>(null)
  const boxRef = useRef<HTMLDivElement>(null)
  const docxBoxRef = useRef<HTMLDivElement>(null)
  const [docxErr, setDocxErr] = useState<string | null>(null)
  const [xlsxBytes, setXlsxBytes] = useState<Uint8Array | null>(null)
  const [xlsxErr, setXlsxErr] = useState<string | null>(null)
  const isDocx = isDocxName(path)
  const isXlsx = isXlsxName(path)
  const isHtml = /\.html?$/i.test(path)

  // 读取文件
  useEffect(() => {
    let alive = true
    setLoading(true)
    setError(null)
    // docx/xlsx 二进制：不拉文本（乱码），由下方字节 effect 走 raw 字节渲染
    if (isDocx || isXlsx) { setLoading(false); return }
    projectApi
      .file(projectId, path)
      .then((d) => { if (alive) { setData(d); setLoading(false) } })
      .catch((e) => {
        if (alive) { setError(e instanceof ApiError ? e.userMessage() : String(e)); setLoading(false) }
      })
    return () => { alive = false }
  }, [projectId, path, isDocx, isXlsx])

  // docx → 后端 raw 字节 → docx-preview 渲染（容器恒挂载）
  useEffect(() => {
    if (!isDocx) return
    let alive = true
    setDocxErr(null)
    const run = async () => {
      try {
        const res = await projectApi.raw(projectId, path)
        const buf = await res.arrayBuffer()
        if (!alive || !docxBoxRef.current) return
        const { renderAsync: ra } = await import('docx-preview')
        await ra(new Blob([buf]), docxBoxRef.current)
      } catch (e) {
        if (alive) setDocxErr(e instanceof Error ? e.message : String(e))
      }
    }
    void run()
    return () => { alive = false }
  }, [projectId, path, isDocx])

  // xlsx → 后端 raw 字节 → ExcelPreview 渲染
  useEffect(() => {
    if (!isXlsx) return
    let alive = true
    setXlsxBytes(null)
    setXlsxErr(null)
    const run = async () => {
      try {
        const res = await projectApi.raw(projectId, path)
        const buf = await res.arrayBuffer()
        if (alive) setXlsxBytes(new Uint8Array(buf))
      } catch (e) {
        if (alive) setXlsxErr(e instanceof Error ? e.message : String(e))
      }
    }
    void run()
    return () => { alive = false }
  }, [projectId, path, isXlsx])

  // Monaco 生命周期：data 就绪后创建编辑器（默认只读查看）
  useEffect(() => {
    if (!boxRef.current || !data) return
    const ed = monaco.editor.create(boxRef.current, {
      value: data.content,
      language: monacoLangOf(path),
      readOnly: true,
      ...MONACO_COMMON_OPTS,
    })
    editorRef.current = ed
    return () => { ed.dispose(); editorRef.current = null }
  }, [data, path])

  const toggleEdit = () => {
    const next = !editing
    setEditing(next)
    setError(null)
    editorRef.current?.updateOptions({ readOnly: !next })
  }

  const save = async () => {
    const content = editorRef.current?.getValue() ?? ''
    setSaving(true)
    try {
      await projectApi.write(projectId, path, content)
      setData((prev) => (prev ? { ...prev, content } : prev))
      setEditing(false)
      editorRef.current?.updateOptions({ readOnly: true })
    } catch (e) {
      setError(e instanceof ApiError ? e.userMessage() : String(e))
    } finally {
      setSaving(false)
    }
  }

  const fileName = path.split('/').pop() ?? path

  // HTML 文件「运行」：右栏 iframe 预览（复用模型代码运行通道 · 可独立打开）
  const runHtml = () => {
    if (!data) return
    usePreviewStore.getState().open({ kind: 'html', title: `${fileName} · 运行`, code: data.content })
    close()
  }

  return (
    <div className="diff-backdrop" onClick={close}>
      <div className="file-view-modal" onClick={(e) => e.stopPropagation()}>
        <div className="file-view-header">
          <div className="file-view-info">
            <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: 14, height: 14 }}>
              <path d="M4 2H7L10 5V12H4V2Z" />
            </svg>
            <span className="file-view-name">{fileName}</span>
            <span className="file-view-path">{path}</span>
            {data && <span className="file-view-size">{data.size} B</span>}
            {editing && <span className="file-view-edit-badge">编辑中</span>}
          </div>
          <div className="file-view-actions">
            {isHtml && data && !editing && (
              <button className="diff-tbtn" onClick={runHtml} title="在右栏运行预览（可独立打开）">运行</button>
            )}
            {!isDocx && !isXlsx && !editing ? (
              <button className="diff-tbtn" onClick={toggleEdit} title="切换编辑模式（保存写回项目）">编辑</button>
            ) : !isDocx && !isXlsx ? (
              <>
                <button className="diff-tbtn save" onClick={() => void save()} disabled={saving}>{saving ? '保存中…' : '保存'}</button>
                <button className="diff-tbtn" onClick={toggleEdit} disabled={saving}>取消</button>
              </>
            ) : null}
          </div>
          <button className="diff-close" onClick={close}>
            <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: 14, height: 14 }}>
              <path d="M3 3L11 11M11 3L3 11" />
            </svg>
          </button>
        </div>
        <div className="file-view-body monaco-body">
          {isDocx ? (
            <div className="fvm-docx">
              {docxErr && <div className="file-view-empty" style={{ color: 'var(--error-dark)' }}>Word 渲染失败：{docxErr}</div>}
              <div ref={docxBoxRef} className="fvm-docx-body" />
            </div>
          ) : isXlsx ? (
            <ExcelPreview bytes={xlsxBytes} error={xlsxErr} />
          ) : loading ? (
            <div className="file-view-empty">加载中…</div>
          ) : error ? (
            <div className="file-view-empty" style={{ color: 'var(--error-dark)' }}>{error}</div>
          ) : (
            <div ref={boxRef} className="monaco-box" />
          )}
        </div>
      </div>
    </div>
  )
}
