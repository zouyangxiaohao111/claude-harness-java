import { useEffect, useMemo, useRef, useState } from 'react'
import { artifactsApi, type ArtifactContent, type SessionArtifactDto } from '@/api/artifacts'
import { ApiError } from '@/api/rest'
import { MarkdownText } from '@/markdown/MarkdownText'
import { monaco, monacoLangOf, MONACO_COMMON_OPTS } from '@/utils/monaco'
import { artifactKindOf, isTextArtifact } from '@/utils/artifactKind'
import { openArtifactRawInBrowser } from '@/utils/artifactOpen'
import { ArtifactKindIcon } from '@/components/right/ArtifactCards'
import { formatFileSize, compactNumber } from '@/utils/format'
import { usePreviewContent } from '@/components/preview/usePreviewContent'
import { PdfPreview } from '@/components/preview/PdfPreview'
import { DocxPreview } from '@/components/preview/DocxPreview'
import { ExcelPreview } from '@/components/preview/ExcelPreview'
import type { PreviewType, StandalonePayload } from '@/utils/standalonePreview'

/**
 * 产物独立侧栏（Artifact Dock）。
 *
 * <p><b>WHY 单独一个面板，不复用 RightPanel 抽屉</b>（用户 2026-11-02 裁定）：
 * 抽屉是「项目 / 产物 / 变更 / 来源 / 团队 / 运行 / 终端」七个 tab 的**导航器** —— 产物只是其中一格。
 * 而实时预览要的是「单个产物的沉浸查看」：点进来就该看到内容，不该先选 tab、再选 card。
 * 把两者混在一起，用户永远要确认「我现在看的是哪条栏」。故本面板只服务**产物自动打开**这一个场景，
 * 与抽屉并存：抽屉用于浏览全部会话资源，本面板用于跟进刚生成的产物。
 *
 * <p><b>与模态框的关系</b>：内容渲染与 {@code ArtifactViewModal}（html）/ {@code ArtifactPreviewModal}（文本）
 * 同一套口径（三态预览/源码/编辑、沙箱 iframe、Monaco 惰性挂载、保存写回归档副本），
 * 但容器是右侧列而非居中遮罩 —— 这样能边看产物边对话。右上角「全屏」按钮会回调 {@code onExpand}，
 * 由 App 打开原来的模态框（保留「看大表格」这个既有能力）。
 */
export interface ArtifactDockProps {
  sessionId: string
  /** 当前查看的产物（null = 面板收起） */
  artifact: SessionArtifactDto | null
  /** 同会话的其他产物（底部小列表供切换） */
  items: SessionArtifactDto[]
  /** 切换查看的产物 */
  onSelect: (a: SessionArtifactDto) => void
  /** 收起面板（回到完全隐藏态） */
  onClose: () => void
  /** 打开原来的居中模态框（「全屏」） */
  onExpand?: (a: SessionArtifactDto) => void
  /** 用户手动拖宽后回写（App 存 state，避免面板自己持有宽度） */
  width: number
  onWidthChange: (w: number) => void
}

/** 面板宽度上下限（与抽屉同量级；下限保证 html 预览还有可用宽度）。 */
const MIN_W = 320
const MAX_W = 860

export function ArtifactDock({
  sessionId, artifact, items, onSelect, onClose, onExpand, width, onWidthChange,
}: ArtifactDockProps) {
  const [data, setData] = useState<ArtifactContent | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [mode, setMode] = useState<'preview' | 'source' | 'edit'>('preview')
  const [draft, setDraft] = useState('')
  const [saving, setSaving] = useState(false)
  const editorRef = useRef<monaco.editor.IStandaloneCodeEditor | null>(null)
  const boxRef = useRef<HTMLDivElement>(null)

  const kind = artifact ? artifactKindOf(artifact) : 'file'
  const editable = !!artifact && isTextArtifact(artifact)
  const isHtml = kind === 'html'

  // 换产物 → 重置三态与内容（编辑态切走不保存，用户明确换了目标）
  useEffect(() => {
    if (!artifact) return
    let alive = true
    setLoading(true)
    setError(null)
    setData(null)
    setMode('preview')
    artifactsApi.content(sessionId, artifact.id)
      .then((d) => {
        if (!alive) return
        setData(d)
        setDraft(d.content ?? '')
        setLoading(false)
      })
      .catch((e) => {
        if (!alive) return
        setError(e instanceof ApiError ? e.userMessage() : String(e))
        setLoading(false)
      })
    return () => { alive = false }
  }, [sessionId, artifact?.id])

  // Monaco 只在源码/编辑态挂载（预览态用 iframe / Markdown，不需要编辑器实例）
  useEffect(() => {
    if (mode === 'preview') return
    if (!boxRef.current || !data || data.binary || data.content == null) return
    if (!artifact) return
    const ed = monaco.editor.create(boxRef.current, {
      value: data.content,
      language: monacoLangOf(artifact.title),
      readOnly: mode === 'source',
      ...MONACO_COMMON_OPTS,
    })
    editorRef.current = ed
    return () => { ed.dispose(); editorRef.current = null }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [data, mode === 'preview', artifact?.id])

  /**
   * 二进制/富媒体类型（pdf / docx / xlsx / 图片）→ 走既有渲染器。
   *
   * <p><b>WHY 必须接这一步</b>：{@link ArtifactContent} 的 content 对二进制恒为 null
   * （后端不下发 base64 避免打爆响应体）⇒ 面板只靠它只能显示「暂不支持」。
   * 既有渲染器（pdf.js / docx-preview / Univer）要的是**字节或 url**，统一由
   * {@code usePreviewContent} 从 artifacts/{id}/raw 端点取 —— 与独立预览窗同一条链路。
   */
  const richType: PreviewType | null = (() => {
    if (!artifact) return null
    switch (kind) {
      case 'pdf': return 'pdf'
      case 'docx': return 'docx'
      case 'xlsx': return 'xlsx'
      case 'img': return 'image'
      default: return null
    }
  })()
  const richPayload: StandalonePayload | null = useMemo(
    () => (artifact && richType
      ? { v: 1, type: richType, title: artifact.title, artifact: { sessionId, artifactId: artifact.id } }
      : null),
    [artifact?.id, richType, sessionId, artifact?.title],
  )
  const rich = usePreviewContent(richPayload)

  const save = async () => {
    if (!artifact) return
    const content = editorRef.current?.getValue() ?? draft
    setSaving(true)
    setError(null)
    try {
      await artifactsApi.saveContent(sessionId, artifact.id, content)
      setData((prev) => (prev ? { ...prev, content } : prev))
      setMode('preview')
    } catch (e) {
      setError(e instanceof ApiError ? e.userMessage() : String(e))
    } finally {
      setSaving(false)
    }
  }

  const openInBrowser = async () => {
    if (!artifact) return
    const ok = await openArtifactRawInBrowser(sessionId, artifact.id)
    if (ok) return
    const path = artifact.localPath
    if (path) { try { await navigator.clipboard.writeText(path) } catch { /* 剪贴板被拒不影响提示 */ } }
    setError('产物原始地址暂不可用（服务端尚未上线该接口），路径已复制')
  }

  // ── 拖宽（左缘）──
  const startResize = (e: React.MouseEvent) => {
    e.preventDefault()
    const startX = e.clientX
    const startW = width
    const onMove = (ev: MouseEvent) => {
      // 面板在右侧 ⇒ 鼠标往左拖 = 变宽
      const next = Math.max(MIN_W, Math.min(MAX_W, startW - (ev.clientX - startX)))
      onWidthChange(next)
    }
    const onUp = () => {
      window.removeEventListener('mousemove', onMove)
      window.removeEventListener('mouseup', onUp)
      document.body.style.userSelect = ''
    }
    document.body.style.userSelect = 'none'
    window.addEventListener('mousemove', onMove)
    window.addEventListener('mouseup', onUp)
  }

  if (!artifact) return null

  const isText = editable && data != null && !data.binary && data.content != null
  const isMarkdown = data?.mime === 'text/markdown'

  return (
    <div className="artifact-dock" style={{ width }}>
      <div className="ad-resizer" onMouseDown={startResize} title="拖拽调整宽度" />

      {/* 顶部：类型图标 + 文件名 + 全屏 / 浏览器 / 收起 */}
      <div className="ad-head">
        <span className={`ad-ico k-${kind}`}><ArtifactKindIcon kind={kind} size={13} /></span>
        <span className="ad-name" title={artifact.localPath ?? artifact.title}>{artifact.title}</span>
        <div className="ad-acts">
          {onExpand && (
            <button type="button" className="ad-btn" onClick={() => onExpand(artifact)} title="全屏查看" aria-label="全屏查看">
              <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round">
                <path d="M6 2.5H2.5V6M10 2.5h3.5V6M6 13.5H2.5V10M10 13.5h3.5V10" />
              </svg>
            </button>
          )}
          {isHtml && (
            <button type="button" className="ad-btn" onClick={() => void openInBrowser()} title="在浏览器中打开" aria-label="在浏览器中打开">
              <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round">
                <path d="M6.5 9.5a2.2 2.2 0 0 0 3.1 0l2-2a2.2 2.2 0 0 0-3.1-3.1L7.6 5.3" />
                <path d="M9.5 6.5a2.2 2.2 0 0 0-3.1 0l-2 2a2.2 2.2 0 0 0 3.1 3.1l.9-.9" />
              </svg>
            </button>
          )}
          <button type="button" className="ad-btn" onClick={onClose} title="收起侧栏" aria-label="收起侧栏">
            <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round">
              <path d="M9.5 4.5L6 8l3.5 3.5" />
            </svg>
          </button>
        </div>
      </div>

      {/* 三态切换（仅文本类有；二进制没有编辑/源码概念） */}
      {isText && (
        <div className="ad-modes" role="group" aria-label="查看模式">
          <button type="button" className={`ad-mode${mode === 'preview' ? ' on' : ''}`} onClick={() => setMode('preview')}>预览</button>
          <button type="button" className={`ad-mode${mode === 'source' ? ' on' : ''}`} onClick={() => setMode('source')}>源码</button>
          {editable && (
            <button type="button" className={`ad-mode${mode === 'edit' ? ' on' : ''}`} onClick={() => setMode('edit')}>编辑</button>
          )}
          {mode === 'edit' && (
            <span className="ad-save-group">
              <button type="button" className="ad-save" onClick={() => void save()} disabled={saving}>{saving ? '保存中…' : '保存'}</button>
              <button type="button" className="ad-cancel" onClick={() => { setDraft(data?.content ?? ''); setMode('preview') }} disabled={saving}>取消</button>
            </span>
          )}
        </div>
      )}

      {/* 内容区 */}
      <div className={`ad-stage${mode === 'preview' && isHtml ? ' is-html' : ''}`}>
        {loading && <div className="ad-state">正在读取产物内容…</div>}
        {!loading && error && <div className="ad-state ad-error">{error}</div>}
        {/* [2026-11-02] 二进制/富媒体：有渲染器就走渲染器（pdf.js / docx-preview / Univer / img） */}
        {!loading && !error && data?.binary && richType && (
          rich.loading && !rich.url && !rich.bytes ? <div className="ad-state">加载中…</div>
            : rich.error && !rich.url && !rich.bytes ? <div className="ad-state ad-error">{rich.error}</div>
            : richType === 'pdf' && rich.url ? <div className="ad-rich"><PdfPreview url={rich.url} /></div>
            : richType === 'image' && rich.url ? <div className="ad-rich"><img className="ad-img" src={rich.url} alt={artifact.title} draggable={false} /></div>
            : richType === 'docx' ? <div className="ad-rich"><DocxPreview bytes={rich.bytes ?? null} error={rich.error ?? null} /></div>
            : richType === 'xlsx' ? <div className="ad-rich"><ExcelPreview bytes={rich.bytes ?? null} error={rich.error ?? null} /></div>
            : <div className="ad-state">暂不支持预览</div>
        )}
        {!loading && !error && data?.binary && !richType && (
          <div className="ad-state">
            {kind === 'pptx' ? 'PPT 暂不支持内嵌预览' : '二进制产物无法在此预览'}
            {onExpand && <button type="button" className="ad-state-btn" onClick={() => onExpand(artifact)}>用系统应用打开</button>}
          </div>
        )}
        {!loading && !error && !data?.binary && data != null && (
          mode === 'edit' && isText ? (
            <textarea
              className="ad-editor"
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              spellCheck={false}
            />
          ) : mode === 'source' && isText ? (
            <div ref={boxRef} className="ad-monaco" />
          ) : mode === 'preview' && isHtml ? (
            <iframe
              className="ad-frame"
              sandbox="allow-scripts allow-modals allow-forms allow-popups"
              srcDoc={data.content ?? ''}
              title={`${artifact.title} · 预览`}
            />
          ) : isMarkdown ? (
            <MarkdownText text={data.content ?? ''} className="content md ad-md" />
          ) : isText ? (
            <pre className="ad-pre">{data.content}</pre>
          ) : (
            <div className="ad-state">
              {kind === 'pptx' ? 'PPT 暂不支持内嵌预览' : '该类型暂不支持内嵌预览'}
              {onExpand && <button type="button" className="ad-state-btn" onClick={() => onExpand(artifact)}>用系统应用打开</button>}
            </div>
          )
        )}
      </div>

      {/* 底部小列表：同会话全部产物（**有一个就显示一个**）。
          [2026-11-02 用户实测「底下的产物列表没了」] 原条件是 `items.length > 1` ——
          只产出一个文件时整个列表块不渲染，看起来像「这个面板没有产物列表」。
          列表的价值不只是切换，更是「本会话一共有几份产物」的总览 ⇒ 单个也要显示。 */}
      {items.length > 0 && (
        <div className="ad-list">
          {items.map((it) => {
            const k = artifactKindOf(it)
            return (
              <button
                key={it.id}
                type="button"
                className={`ad-item${it.id === artifact.id ? ' on' : ''}`}
                onClick={() => onSelect(it)}
                title={it.localPath ?? it.title}
              >
                <span className={`ad-ico sm k-${k}`}><ArtifactKindIcon kind={k} size={10} /></span>
                <span className="ad-item-name">{it.title}</span>
                {it.sizeBytes != null && <span className="ad-item-size">{compactNumber(it.sizeBytes)}</span>}
              </button>
            )
          })}
        </div>
      )}
      {/* 元信息行（大小 / 类型） */}
      {data && (
        <div className="ad-meta">
          {data.mime} · {formatFileSize(data.size)}
          {data.truncated ? ' · 已截断（仅前 512KB）' : ''}
        </div>
      )}
    </div>
  )
}
