import { useEffect, useRef, useState } from 'react'
import { projectApi, type FileContent } from '@/api/projects'
import { ApiError } from '@/api/rest'
import { monaco, monacoLangOf, MONACO_COMMON_OPTS } from '@/utils/monaco'
import { openStandalone } from '@/utils/standalonePreview'
import { MarkdownTextV2 } from '@/markdown/vendor/renderer'

interface FileViewModalProps {
  /** 项目 id（读文件） */
  projectId: string
  /** 文件相对路径（与文件树 path 一致） */
  path: string
  close: () => void
}

/**
 * 项目文件查看/编辑（Monaco · 默认只读 · 「编辑」切换写回 PUT /projects/{id}/file）。
 *
 * docx/xlsx/pdf/image/video/audio 已被 App.openProjectFile 分流到独立预览窗，不再进入本 modal；
 * 本 modal 只承载文本/代码文件 + html（源码查看/编辑/保存；html 可「运行」→ 独立预览窗）。
 */
export function FileViewModal({ projectId, path, close }: FileViewModalProps) {
  const [data, setData] = useState<FileContent | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [editing, setEditing] = useState(false)
  const [saving, setSaving] = useState(false)
  const editorRef = useRef<monaco.editor.IStandaloneCodeEditor | null>(null)
  const boxRef = useRef<HTMLDivElement>(null)
  const isHtml = /\.html?$/i.test(path)
  /**
   * [2026-11-02 用户实测「项目下面的 md 系列文档没有预览」] Markdown 三态。
   *
   * <p><b>WHY</b>：本组件原先把**所有**文本文件都丢进 Monaco（连只读态也是代码视图），
   * 而 md 在项目里是最常看的文档格式 —— 打开满屏 `#` / `**` 源码等于没有预览能力。
   * 产物侧（ArtifactPreviewModal）早已有「预览/源码/编辑」三态，这里对齐同一套交互：
   * 默认**预览**（渲染后的 Markdown），要改再切「编辑」，想看原文切「源码」。
   *
   * <p>非 Markdown 文件不显示切换（没有「渲染后」形态可言），保持原有单态 Monaco。
   */
  const isMarkdown = /\.(md|markdown|mdx)$/i.test(path)
  const [mdMode, setMdMode] = useState<'preview' | 'source' | 'edit'>('preview')

  // 读取文件（纯文本；html 也需要 content 供「运行」独立窗）
  useEffect(() => {
    let alive = true
    setLoading(true)
    setError(null)
    projectApi
      .file(projectId, path)
      .then((d) => { if (alive) { setData(d); setLoading(false) } })
      .catch((e) => {
        if (alive) { setError(e instanceof ApiError ? e.userMessage() : String(e)); setLoading(false) }
      })
    return () => { alive = false }
  }, [projectId, path])

  // Monaco 生命周期：data 就绪后创建编辑器（默认只读查看）
  useEffect(() => {
    // [2026-11-02] Markdown **预览态不创建编辑器**（那正是「打开就是源码」的根因）。
    //   只有非 markdown，或 markdown 切到 source/edit 时才挂载 Monaco。
    if (isMarkdown && mdMode === 'preview') return
    if (!boxRef.current || !data) return
    const ed = monaco.editor.create(boxRef.current, {
      value: data.content,
      language: monacoLangOf(path),
      readOnly: true,
      ...MONACO_COMMON_OPTS,
    })
    editorRef.current = ed
    return () => { ed.dispose(); editorRef.current = null }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [data, path, isMarkdown, mdMode === 'preview'])

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
      // [2026-11-02] Markdown 保存后回预览（改完最想看到的是渲染结果，与产物侧同口径）
      if (isMarkdown) setMdMode('preview')
    } catch (e) {
      setError(e instanceof ApiError ? e.userMessage() : String(e))
    } finally {
      setSaving(false)
    }
  }

  /**
   * Markdown 编辑态取消：丢弃 Monaco 里的改动，回到预览。
   *
   * <p>⚠️ 必须把编辑器内容**重置回 data.content**：`mdMode` 从 edit 切回 preview 时
   * Monaco 会被卸载（effect 提前 return），下次再进编辑会按 `data.content` 重建 ——
   * 若不重置，用户会看到自己「取消」掉的草稿又回来了。
   */
  const cancelMdEdit = () => {
    editorRef.current?.setValue(data?.content ?? '')
    setMdMode('preview')
    setError(null)
  }

  const fileName = path.split('/').pop() ?? path

  // HTML 文件「运行」→ 独立预览窗口（StandalonePreviewView html 分支，sandbox iframe 运行）。
  const runHtml = () => {
    if (!data) return
    openStandalone({ type: 'html', title: `${fileName} · 运行`, code: data.content })
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
            {/* [2026-11-02] Markdown 三态：默认预览（渲染后），可切源码 / 编辑 */}
            {isMarkdown && data && mdMode !== 'edit' && (
              <div className="artifact-mode-switch" role="group" aria-label="查看模式">
                <button
                  className={`artifact-mode-btn${mdMode === 'preview' ? ' on' : ''}`}
                  onClick={() => setMdMode('preview')}
                  title="渲染预览"
                >预览</button>
                <button
                  className={`artifact-mode-btn${mdMode === 'source' ? ' on' : ''}`}
                  onClick={() => setMdMode('source')}
                  title="查看 Markdown 原文"
                >源码</button>
              </div>
            )}
            {isHtml && data && !editing && (
              <button className="diff-tbtn" onClick={runHtml} title="在独立窗口运行预览">运行</button>
            )}
            {isMarkdown ? (
              mdMode === 'edit' ? (
                <>
                  <button className="diff-tbtn save" onClick={() => void save()} disabled={saving}>{saving ? '保存中…' : '保存'}</button>
                  <button
                    className="diff-tbtn"
                    onClick={cancelMdEdit}
                    disabled={saving}
                    title="放弃修改并回到预览"
                  >取消</button>
                </>
              ) : (
                <button className="diff-tbtn" onClick={() => setMdMode('edit')} title="编辑（保存写回项目）">编辑</button>
              )
            ) : (
              !editing ? (
                <button className="diff-tbtn" onClick={toggleEdit} title="切换编辑模式（保存写回项目）">编辑</button>
              ) : (
                <>
                  <button className="diff-tbtn save" onClick={() => void save()} disabled={saving}>{saving ? '保存中…' : '保存'}</button>
                  <button className="diff-tbtn" onClick={toggleEdit} disabled={saving}>取消</button>
                </>
              )
            )}
          </div>
          <button className="diff-close" onClick={close}>
            <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: 14, height: 14 }}>
              <path d="M3 3L11 11M11 3L3 11" />
            </svg>
          </button>
        </div>
        <div className={`file-view-body ${isMarkdown && mdMode === 'preview' ? 'md-preview-body' : 'monaco-body'}`}>
          {loading ? (
            <div className="file-view-empty">加载中…</div>
          ) : error ? (
            <div className="file-view-empty" style={{ color: 'var(--error-dark)' }}>{error}</div>
          ) : isMarkdown && mdMode === 'preview' ? (
            /* 默认态：渲染后的 Markdown（用户要「有预览」，不是满屏 # 和 **） */
            <MarkdownTextV2 text={data?.content ?? ''} className="content md file-view-md" />
          ) : (
            <div ref={boxRef} className="monaco-box" />
          )}
        </div>
      </div>
    </div>
  )
}
