import { useEffect, useRef, useState } from 'react'
import { artifactsApi, type ArtifactContent, type SessionArtifactDto } from '@/api/artifacts'
import { ApiError } from '@/api/rest'
import { monaco, monacoLangOf, MONACO_COMMON_OPTS } from '@/utils/monaco'
import { openStandalone } from '@/utils/standalonePreview'
import { openArtifactRawInBrowser } from '@/utils/artifactOpen'

interface ArtifactViewModalProps {
  /** 会话 id（产物归属校验在后端：不属于该会话 → 404） */
  sessionId: string
  artifact: SessionArtifactDto
  /** 进来就是编辑态（卡片上的「编辑」按钮） */
  initialEdit?: boolean
  close: () => void
}

/**
 * html 产物查看 / 编辑（Monaco · 与项目文件 {@code FileViewModal} 同一套交互，但数据源不同）。
 *
 * <p><b>WHY 单独一个 modal</b>：产物的 {@code localPath} 是**归档副本**的绝对路径
 * （{@code {configHome}/artifacts/<sessionId>/…}），不在任何项目根内 ⇒ ⛔ 不能走项目文件通道
 * （必报「文件路径非法」）。本 modal 走会话产物接口：
 * <ul>
 *   <li>读：{@code GET /sessions/{sid}/artifacts/{aid}/content}；</li>
 *   <li>存：{@code PUT /sessions/{sid}/artifacts/{aid}/content}（写回归档副本，**不动项目源文件**）；</li>
 *   <li>「运行」：{@code openStandalone({type:'html', code})} → 独立窗沙箱 iframe；</li>
 *   <li>「在浏览器中打开」：raw 端点绝对地址 → 系统浏览器 / 新标签。</li>
 * </ul>
 */
export function ArtifactViewModal({ sessionId, artifact, initialEdit = false, close }: ArtifactViewModalProps) {
  const [data, setData] = useState<ArtifactContent | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [editing, setEditing] = useState(initialEdit)
  const [saving, setSaving] = useState(false)
  const editorRef = useRef<monaco.editor.IStandaloneCodeEditor | null>(null)
  const boxRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    let alive = true
    setLoading(true)
    setError(null)
    artifactsApi
      .content(sessionId, artifact.id)
      .then((d) => { if (alive) { setData(d); setLoading(false) } })
      .catch((e) => {
        if (alive) { setError(e instanceof ApiError ? e.userMessage() : String(e)); setLoading(false) }
      })
    return () => { alive = false }
  }, [sessionId, artifact.id])

  // Monaco 生命周期：内容就绪后创建编辑器（默认只读查看）
  useEffect(() => {
    if (!boxRef.current || !data) return
    const ed = monaco.editor.create(boxRef.current, {
      value: data.content ?? '',
      language: monacoLangOf(artifact.title),
      readOnly: !editing,
      ...MONACO_COMMON_OPTS,
    })
    editorRef.current = ed
    return () => { ed.dispose(); editorRef.current = null }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [data])

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
      await artifactsApi.saveContent(sessionId, artifact.id, content)
      setData((prev) => (prev ? { ...prev, content } : prev))
      setEditing(false)
      editorRef.current?.updateOptions({ readOnly: true })
    } catch (e) {
      setError(e instanceof ApiError ? e.userMessage() : String(e))
    } finally {
      setSaving(false)
    }
  }

  /** 「运行」→ 独立预览窗（沙箱 iframe 执行 html） */
  const runHtml = () => {
    const code = editorRef.current?.getValue() ?? data?.content ?? ''
    openStandalone({ type: 'html', title: `${artifact.title} · 运行`, code })
  }

  /** 「在浏览器中打开」→ 系统浏览器 / 新标签打开 raw 端点（Tauri 下用 openUrl）。
   *  raw 端点还没上线（404）⇒ 降级「复制路径 + 提示」，⛔ 不把用户丢到 404 页面。 */
  const openInBrowser = async () => {
    const ok = await openArtifactRawInBrowser(sessionId, artifact.id)
    if (ok) return
    const path = artifact.localPath
    if (path) {
      try { await navigator.clipboard.writeText(path) } catch { /* 剪贴板被拒不影响提示 */ }
    }
    setError('产物原始地址暂不可用（服务端尚未上线该接口），路径已复制')
  }

  return (
    <div className="diff-backdrop" onClick={close}>
      <div className="file-view-modal" onClick={(e) => e.stopPropagation()}>
        <div className="file-view-header">
          <div className="file-view-info">
            <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: 14, height: 14 }}>
              <path d="M4 2H7L10 5V12H4V2Z" />
            </svg>
            <span className="file-view-name">{artifact.title}</span>
            {data && <span className="file-view-size">{data.size} B</span>}
            {data?.truncated && <span className="file-view-edit-badge">已截断</span>}
            {editing && <span className="file-view-edit-badge">编辑中</span>}
          </div>
          <div className="file-view-actions">
            <button className="diff-tbtn" onClick={() => void openInBrowser()} title="用系统浏览器打开产物">
              在浏览器中打开
            </button>
            <button className="diff-tbtn" onClick={runHtml} title="在独立窗口运行预览">运行</button>
            {!editing ? (
              <button className="diff-tbtn" onClick={toggleEdit} title="切换编辑模式（保存写回归档副本）">编辑</button>
            ) : (
              <>
                <button className="diff-tbtn save" onClick={() => void save()} disabled={saving}>{saving ? '保存中…' : '保存'}</button>
                <button className="diff-tbtn" onClick={toggleEdit} disabled={saving}>取消</button>
              </>
            )}
          </div>
          <button className="diff-close" onClick={close}>
            <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: 14, height: 14 }}>
              <path d="M3 3L11 11M11 3L3 11" />
            </svg>
          </button>
        </div>
        <div className="file-view-body monaco-body">
          {loading ? (
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
