import { useEffect, useRef, useState } from 'react'
import { artifactsApi, type ArtifactContent, type SessionArtifactDto } from '@/api/artifacts'
import { ApiError } from '@/api/rest'
import { monaco, monacoLangOf, MONACO_COMMON_OPTS } from '@/utils/monaco'
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
 *   <li>「在浏览器中打开」：raw 端点绝对地址 → 系统浏览器 / 新标签。</li>
 * </ul>
 *
 * <p><b>三态（2026-11-01 用户要求「产物默认打开时运行而不是编辑」）</b>：
 * <ul>
 *   <li><b>预览</b>（<b>默认</b>）：沙箱 iframe <b>直接运行</b> HTML —— 用户点开产物是想看效果，
 *       不是想看代码。旧实现默认进 Monaco（哪怕只读）⇒ 满屏代码，被用户点名「直接展示编辑框」。</li>
 *   <li><b>源码</b>：Monaco 只读，看/复制代码；</li>
 *   <li><b>编辑</b>：Monaco 可写 + 保存写回归档副本。</li>
 * </ul>
 *
 * <p>⚠️ iframe 与 Monaco 都在 DOM 里按需挂载（切换模式重建）—— Monaco 实例昂贵，
 * 不要在预览态也偷偷创建（会更慢且抢内存）。
 */
export function ArtifactViewModal({ sessionId, artifact, initialEdit = false, close }: ArtifactViewModalProps) {
  const [data, setData] = useState<ArtifactContent | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  /** 默认「预览」（运行 iframe）；卡片上的「编辑」按钮仍可 initialEdit 直达编辑态。 */
  const [mode, setMode] = useState<'preview' | 'source' | 'edit'>(initialEdit ? 'edit' : 'preview')
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

  // Monaco 只在源码/编辑态挂载（预览态用 iframe，不需要编辑器实例）
  useEffect(() => {
    if (mode === 'preview') return
    if (!boxRef.current || !data) return
    const ed = monaco.editor.create(boxRef.current, {
      value: data.content ?? '',
      language: monacoLangOf(artifact.title),
      readOnly: mode === 'source',
      ...MONACO_COMMON_OPTS,
    })
    editorRef.current = ed
    return () => { ed.dispose(); editorRef.current = null }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [data, mode === 'preview'])

  const save = async () => {
    const content = editorRef.current?.getValue() ?? ''
    setSaving(true)
    try {
      await artifactsApi.saveContent(sessionId, artifact.id, content)
      setData((prev) => (prev ? { ...prev, content } : prev))
      setMode('preview')   // 保存后回到预览：用户改完最想看到的是运行效果
    } catch (e) {
      setError(e instanceof ApiError ? e.userMessage() : String(e))
    } finally {
      setSaving(false)
    }
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
            {mode === 'edit' && <span className="file-view-edit-badge">编辑中</span>}
          </div>
          <div className="file-view-actions">
            {/* 模式切换：预览 / 源码 */}
            {mode !== 'edit' && (
              <div className="artifact-mode-switch" role="group" aria-label="查看模式">
                <button
                  className={`artifact-mode-btn${mode === 'preview' ? ' on' : ''}`}
                  onClick={() => setMode('preview')}
                  title="运行预览（沙箱 iframe）"
                >
                  预览
                </button>
                <button
                  className={`artifact-mode-btn${mode === 'source' ? ' on' : ''}`}
                  onClick={() => setMode('source')}
                  title="查看 HTML 源码"
                >
                  源码
                </button>
              </div>
            )}
            <button className="diff-tbtn" onClick={() => void openInBrowser()} title="用系统浏览器打开产物">
              在浏览器中打开
            </button>
            {mode !== 'edit' ? (
              <button className="diff-tbtn" onClick={() => setMode('edit')} title="切换编辑模式（保存写回归档副本）">编辑</button>
            ) : (
              <>
                <button className="diff-tbtn save" onClick={() => void save()} disabled={saving}>{saving ? '保存中…' : '保存'}</button>
                <button className="diff-tbtn" onClick={() => setMode('source')} disabled={saving}>取消</button>
              </>
            )}
          </div>
          <button className="diff-close" onClick={close}>
            <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: 14, height: 14 }}>
              <path d="M3 3L11 11M11 3L3 11" />
            </svg>
          </button>
        </div>
        <div className={`file-view-body${mode === 'preview' ? ' html-preview-body' : ' monaco-body'}`}>
          {loading ? (
            <div className="file-view-empty">加载中…</div>
          ) : error ? (
            <div className="file-view-empty" style={{ color: 'var(--error-dark)' }}>{error}</div>
          ) : mode === 'preview' ? (
            /* 默认态：直接运行 HTML（沙箱 iframe）—— 点开产物是要看效果，不是看代码 */
            <iframe
              className="artifact-run-frame"
              sandbox="allow-scripts allow-modals allow-forms allow-popups"
              srcDoc={data?.content ?? ''}
              title={`${artifact.title} · 运行预览`}
            />
          ) : (
            <div ref={boxRef} className="monaco-box" />
          )}
        </div>
      </div>
    </div>
  )
}
