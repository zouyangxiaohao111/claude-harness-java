import { useEffect, useMemo, useState } from 'react'
import { KbRawBytesError, knowledgeApi } from '@/api/knowledge'
import type { ChunkView, Knowledge } from '@/api/knowledge'
import { PdfPreview } from '@/components/preview/PdfPreview'
import { DocxPreview } from '@/components/preview/DocxPreview'
import { ExcelPreview } from '@/components/preview/ExcelPreview'
import { MarkdownTextV2 } from '@/markdown/vendor/renderer'

/**
 * 文档抽屉「预览」区的分派结果。
 *
 * <ul>
 *   <li>`pdf` / `word` / `sheet` —— 有原始字节，交给对应渲染器</li>
 *   <li>`text` —— 文本类（md / txt / json / csv …），正文走 Markdown 渲染器</li>
 *   <li>`other` —— 明确不支持在线预览，给下载入口</li>
 * </ul>
 *
 * <p>⭐ <b>文本类为什么必须渲染 Markdown 而不是原样直出</b>：源侧
 * `doc-content.vue:1878` 是 `<div class="md-content doc-merged-content"
 * v-html="processMarkdown(mergedContent)">` —— 分块合并后的正文经 `marked` 渲染。
 * 直接 `white-space: pre-wrap` 会把 `# 标题` 显示成字面井号（用户截图报的就是这个）。
 */
export type KbPreviewKind = 'pdf' | 'word' | 'sheet' | 'text' | 'other'

/** 文本类扩展名（后端 `KnowledgeExtController#classify` 把前 8 个归为 text）。 */
const TEXT_EXTS = new Set([
  'md', 'markdown', 'txt', 'text', 'json', 'csv',
  'html', 'htm', 'yaml', 'yml', 'xml', 'log', 'ini', 'conf',
])
/** Word 类（`.doc` 是 97-2003 老格式，docx-preview 读不了，见下方提示）。 */
const WORD_EXTS = new Set(['docx', 'doc'])
/** 表格类（`.xls` 是 97-2003 老格式，exceljs 的加载器只认 xlsx 结构）。 */
const SHEET_EXTS = new Set(['xlsx', 'xls', 'xlsm', 'ods'])

/**
 * 从文档元信息推出扩展名（**不裁剪、不猜**）。
 *
 * <p>⚠️ `file_name` 在真实数据里**可能为空**（手工录入 / URL 导入 / 历史数据），
 * 这时退到 `title`；两者都没有再用后端 `file_type`
 * （手工录入的 `file_type` 就是 `"text"` 哨兵，见 `KbKnowledgeExtService:149`）。
 * 都取不到 ⇒ 空串 ⇒ 走「其他」分支，⛔ 不要默认成 pdf。
 */
export function kbFileExtOf(knowledge: Pick<Knowledge, 'file_name' | 'title' | 'file_type'>): string {
  for (const raw of [knowledge.file_name, knowledge.title]) {
    const name = (raw ?? '').trim()
    if (!name) continue
    const dot = name.lastIndexOf('.')
    // `dot <= 0`：没有点，或形如 `.gitignore`（点在最前 ⇒ 不是扩展名）
    if (dot > 0 && dot < name.length - 1) return name.slice(dot + 1).toLowerCase()
  }
  const fallback = (knowledge.file_type ?? '').trim().toLowerCase()
  // 后端无扩展名时写的是 "unknown" 哨兵；空串同样视为未知
  if (!fallback || fallback === 'unknown') return ''
  return fallback
}

/** 扩展名 → 分派种类（纯函数，便于单测与日志对照）。 */
export function kbPreviewKindOf(ext: string): KbPreviewKind {
  if (ext === 'pdf') return 'pdf'
  if (WORD_EXTS.has(ext)) return 'word'
  if (SHEET_EXTS.has(ext)) return 'sheet'
  if (TEXT_EXTS.has(ext)) return 'text'
  return 'other'
}

interface Props {
  knowledge: Knowledge
  /** 已加载的分块（文本类预览直接拼它们，⛔ 不再发一次请求） */
  chunks: ChunkView[]
  chunksLoading: boolean
  chunksError: string | null
  /**
   * 视图意图（对齐源码 `viewMode` 的三态）。
   *
   * <ul>
   *   <li>{@code auto}（缺省）—— 按类型自动分派：pdf/word/sheet 走原格式渲染器，
   *       文本类走合并正文。抽屉的「预览」按钮用这个。</li>
   *   <li>{@code merged} —— **强制**合并正文 + Markdown 渲染，忽略原格式。
   *       抽屉的「全文」按钮用这个。</li>
   * </ul>
   */
  mode?: 'auto' | 'merged'
  /** 失败态里的「切到分块」——由抽屉切 view，⛔ 本组件不自己跳（会闪） */
  onSwitchToChunks: () => void
  onRetryChunks?: () => void
}

/** 小按钮（复用 t-button 体系，⛔ 不新增 CSS 类）。 */
function Btn({
  children,
  onClick,
  href,
  variant = 'outline',
}: {
  children: React.ReactNode
  onClick?: () => void
  href?: string
  variant?: 'outline' | 'primary'
}) {
  const cls = `t-button t-size-s ${variant === 'primary'
    ? 't-button--variant-base t-button--theme-primary'
    : 't-button--variant-outline t-button--theme-default'}`
  if (href) {
    return (
      <a className={cls} href={href} download>
        <span className="t-button__text">{children}</span>
      </a>
    )
  }
  return (
    <button type="button" className={cls} onClick={onClick}>
      <span className="t-button__text">{children}</span>
    </button>
  )
}

const HINT_BASE = {
  margin: '10px 0 0',
  fontSize: 'var(--app-text-sm, 12px)',
  color: 'var(--td-text-color-placeholder, rgba(0,0,0,.4))',
  lineHeight: 1.6,
} as const

/**
 * 文档抽屉里的「预览」区。
 *
 * <h2>为什么按扩展名而不是 mimeType 分派</h2>
 * 本仓下载端点固定返回 `application/octet-stream`（`KnowledgeExtController:129`），
 * mime 拿不到任何信息；`file_type` 也可能只是 `unknown` 哨兵。
 *
 * <h2>三种失败态都要有出口（⛔ 不许静默空白）</h2>
 * <ol>
 *   <li>404 没有原始文件（手工创建 / URL 导入）⇒ 说明 + 「切到分块」</li>
 *   <li>取字节失败（网络 / 5xx）⇒ 原始错误 + 「重试」</li>
 *   <li>渲染器自身失败 ⇒ 由 DocxPreview / ExcelPreview 的 `error` 展示，
 *       下方常驻「下载原文件」出口供用户用本地应用打开</li>
 * </ol>
 */
export function KbDocPreview({
  knowledge,
  chunks,
  chunksLoading,
  chunksError,
  mode = 'auto',
  onSwitchToChunks,
  onRetryChunks,
}: Props) {
  const ext = useMemo(() => kbFileExtOf(knowledge), [knowledge])
  /**
   * 分派类型。
   *
   * <p>{@code mode === 'merged'} ⇒ 无论什么后缀都按 {@code text} 走（合并正文 + Markdown）。
   * 这是源码「全文」tab 的语义：有原格式的文档也能看纯文本全文，
   * ⛔ 不是"原格式失败的兜底"。
   *
   * <p>⭐ <b>非 {@code file} 类型一律按 text 走</b>（对齐源码 `canPreview()` 的第一条判据
   * `details.type !== 'file' ⇒ false`）：`manual` / `url` / `faq` 三种知识条目**没有原始文件**
   * （`KbKnowledgeExtService:139/221` 建行时没有 file_path），走字节类分支只会拿到 404。
   * 它们的正文就在分块里 ⇒ 用合并正文渲染，而不是弹一个「没有原文件」的错。
   */
  const kind = useMemo<KbPreviewKind>(() => {
    if (mode === 'merged') return 'text'
    if (knowledge.type !== 'file') return 'text'
    return kbPreviewKindOf(ext)
  }, [mode, knowledge, ext])

  /** 原始字节（pdf / word / sheet 共用一条取字节链路）。 */
  const [bytes, setBytes] = useState<Uint8Array | null>(null)
  const [fetching, setFetching] = useState(false)
  /** 非 404 的取字节失败（网络 / 5xx） */
  const [loadError, setLoadError] = useState<string | null>(null)
  /** 后端 404：文档没有原始文件 */
  const [missingFile, setMissingFile] = useState(false)
  /** 重试计数器：+1 触发重新取字节 */
  const [reloadKey, setReloadKey] = useState(0)

  const needsBytes = kind === 'pdf' || kind === 'word' || kind === 'sheet'

  // 分派留痕：排查时能一眼看出「为什么走了这个分支」
  useEffect(() => {
    console.info(
      `[KbDocPreview] 预览分派 id=${knowledge.id} 文件名=${knowledge.file_name ?? '(空)'}` +
      ` 标题=${knowledge.title ?? '(空)'} file_type=${knowledge.file_type ?? '(空)'}` +
      ` 后缀=${ext || '(无)'} → 分支=${kind}`
    )
    if (kind === 'other') {
      console.warn(
        `[KbDocPreview] 后缀 ${ext || '(无)'} 无对应在线渲染器 ⇒ 显示「不支持预览」提示（可下载后本地打开）`
      )
    }
    if (ext === 'doc' || ext === 'xls') {
      console.warn(
        `[KbDocPreview] ${ext} 为 97-2003 老格式：docx-preview / exceljs 均只认 OOXML 结构，` +
        '渲染多半会失败，已提示用户下载后用本地应用打开'
      )
    }
  }, [knowledge.id, knowledge.file_name, knowledge.title, knowledge.file_type, ext, kind])

  // 取原始字节（pdf/word/sheet）
  useEffect(() => {
    if (!needsBytes) {
      // 切到不需要字节的分支时清干净，避免上一篇文档的字节残留
      setBytes(null)
      setFetching(false)
      setLoadError(null)
      setMissingFile(false)
      return
    }
    let cancelled = false
    setFetching(true)
    setBytes(null)
    setLoadError(null)
    setMissingFile(false)
    const t0 = performance.now()
    console.info(
      `[KbDocPreview] 开始取原始字节 id=${knowledge.id} 分支=${kind} 后缀=${ext}` +
      ` 下载地址=${knowledgeApi.downloadUrl(knowledge.id)} 尝试=${reloadKey + 1}`
    )
    knowledgeApi
      .rawBytes(knowledge.id)
      .then((b) => {
        if (cancelled) return
        const ms = Math.round(performance.now() - t0)
        console.info(`[KbDocPreview] 原始字节就绪 id=${knowledge.id} 字节=${b.byteLength} 耗时=${ms}ms`)
        if (b.byteLength === 0) {
          // 空文件不是「成功」：渲染器会白屏，必须如实说
          setLoadError('后端返回了 0 字节文件，无法渲染（原始文件可能已损坏）')
          console.warn(`[KbDocPreview] 原始文件为 0 字节 id=${knowledge.id}`)
          return
        }
        setBytes(b)
      })
      .catch((e: unknown) => {
        if (cancelled) return
        const status = e instanceof KbRawBytesError ? e.status : -1
        const msg = e instanceof Error ? e.message : String(e)
        if (e instanceof KbRawBytesError && e.isMissingFile) {
          setMissingFile(true)
          console.warn(
            `[KbDocPreview] 后端 404（没有原始文件）id=${knowledge.id} 后缀=${ext} 原始信息=${msg}`
          )
          return
        }
        setLoadError(msg)
        console.warn(
          `[KbDocPreview] 取原始字节失败 id=${knowledge.id} 状态=${status} 后缀=${ext} 原因=${msg}`
        )
      })
      .finally(() => {
        if (!cancelled) setFetching(false)
      })
    return () => { cancelled = true }
  }, [knowledge.id, needsBytes, kind, ext, reloadKey])

  // PDF：字节 → blob URL 交给 PdfPreview（它有自己的一套加载/报错 UI，但 404 由上面统一拦下）
  //
  // ⚠️ 必须在 effect 里建/撤销，⛔ 不能写在 useMemo 里：useMemo 可能在渲染阶段被
  //    StrictMode 双执行，会创建两个 objectURL 且只释放一个（内存泄漏）。
  const [pdfUrl, setPdfUrl] = useState<string | null>(null)
  useEffect(() => {
    if (kind !== 'pdf' || !bytes) { setPdfUrl(null); return }
    const url = URL.createObjectURL(new Blob([bytes.slice()], { type: 'application/pdf' }))
    console.info(`[KbDocPreview] 生成 PDF blob URL 字节=${bytes.byteLength}`)
    setPdfUrl(url)
    return () => {
      URL.revokeObjectURL(url)
      console.info('[KbDocPreview] 已释放 PDF blob URL')
    }
  }, [kind, bytes])

  /** 文本类正文（分块按顺序拼接，与后端 `/preview` 的口径一致）。 */
  const textContent = useMemo(() => {
    if (kind !== 'text') return ''
    return chunks.map((c) => c.content ?? '').join('\n\n')
  }, [kind, chunks])

  useEffect(() => {
    if (kind !== 'text') return
    console.info(
      `[KbDocPreview] 文本类预览 id=${knowledge.id} 分块=${chunks.length}` +
      ` 正文字符=${textContent.length}${chunksError ? ` 分块加载失败=${chunksError}` : ''}`
    )
  }, [kind, knowledge.id, chunks.length, textContent.length, chunksError])

  // ── 其他/未知：明确不支持（⛔ 不显示空白） ─────────────────────────
  if (kind === 'other') {
    return (
      <div className="no_content" style={{ textAlign: 'left', lineHeight: 1.8 }}>
        <div>
          {ext
            ? `该格式（.${ext}）暂不支持在线预览，请下载后用本地应用打开。`
            : '该文档没有可识别的文件后缀，暂不支持在线预览，请下载后用本地应用打开。'}
        </div>
        <div style={{ marginTop: 10, display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          <Btn href={knowledgeApi.downloadUrl(knowledge.id)}>下载原文件</Btn>
          <Btn onClick={onSwitchToChunks}>查看分块</Btn>
        </div>
      </div>
    )
  }

  // ── 文本类：展示解析出的正文 ────────────────────────────────────────
  if (kind === 'text') {
    if (chunksLoading) return <div className="no_content">正在加载正文…</div>
    if (chunksError) {
      return (
        <div className="no_content" style={{ textAlign: 'left' }}>
          正文加载失败：{chunksError}
          <div style={{ marginTop: 10, display: 'flex', gap: 8 }}>
            {onRetryChunks && <Btn onClick={onRetryChunks}>重试</Btn>}
            <Btn onClick={onSwitchToChunks}>查看分块</Btn>
          </div>
        </div>
      )
    }
    if (!textContent.trim()) {
      return <div className="no_content">还没有可展示的正文（文档需解析完成）</div>
    }
    return (
      /* ⭐ 外层 .document-preview —— 源码 document-preview.vue:876 / :1060 就是这层：
            `.document-preview { min-height: 200px; position: relative; }`
            它负责给预览区一个最小高度与定位上下文，里层 `.preview-markdown` 才是滚动容器。 */
      <div className="document-preview" style={{ minHeight: 200, position: 'relative' }}>
        {/* ⭐ 独立预览框 —— 逐字对齐源码 `document-preview.vue:1052` 的 `.preview-container()`：
              border:1px solid var(--td-component-stroke)
              border-radius: var(--app-radius-sm)
              overflow:auto · background: var(--td-bg-color-container)
              + `.preview-markdown { padding: 20px 24px }`（:1306）
            ⚠️ 之前正文直接铺在抽屉里 ⇒ 和「基本信息/摘要」等区块挤在一起，没有预览边界
            （用户报「看起来就合在一起一样」）。这块必须是个**自带边框与滚动的白纸**，
            视觉上像文档阅读器，而不是又一段普通正文。 */}
        <div
          className="preview-markdown"
          style={{
            border: '1px solid var(--td-component-stroke)',
            borderRadius: 'var(--app-radius-sm)',
            overflow: 'auto',
            maxHeight: 'calc(100vh - 200px)',
            background: 'var(--td-bg-color-container)',
            padding: '20px 24px',
          }}
        >
          {/* 正文走 Markdown 渲染器（源侧 doc-content.vue:1878 同样用 marked 渲染
              `.md-content.doc-merged-content`）。本仓的 `MarkdownText` 是对话正文在用的
              同一个 mdast 渲染器，带 GFM 表格 / 代码高亮 / 安全转义。
              ⛔ 不要退回 `white-space: pre-wrap` —— 那会把 `# 标题` 显示成字面井号。 */}
          <MarkdownTextV2 text={textContent} className="md-content doc-merged-content" />
        </div>
        <p style={HINT_BASE}>
          以上是按分块顺序拼接的解析正文（{chunks.length} 个分块 · {textContent.length} 字）。
        </p>
        <div style={{ marginTop: 8 }}>
          <Btn href={knowledgeApi.downloadUrl(knowledge.id)}>下载原文件</Btn>
        </div>
      </div>
    )
  }

  // ── 字节类：404 / 失败 / 加载中 / 渲染 ──────────────────────────────
  if (missingFile) {
    return (
      <div className="no_content" style={{ textAlign: 'left', lineHeight: 1.8 }}>
        <div>
          该文档没有保存原始文件（手工创建或 URL 导入的文档不保存原文件），无法原格式预览，只能查看分块。
        </div>
        <div style={{ marginTop: 10 }}>
          <Btn onClick={onSwitchToChunks}>查看分块</Btn>
        </div>
      </div>
    )
  }

  if (loadError) {
    return (
      <div className="no_content" style={{ textAlign: 'left', lineHeight: 1.8 }}>
        <div>原文件加载失败：{loadError}</div>
        <div style={{ marginTop: 10, display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          <Btn onClick={() => setReloadKey((k) => k + 1)} variant="primary">重试</Btn>
          <Btn href={knowledgeApi.downloadUrl(knowledge.id)}>下载原文件</Btn>
          <Btn onClick={onSwitchToChunks}>查看分块</Btn>
        </div>
      </div>
    )
  }

  if (fetching || !bytes) {
    return <div className="no_content">原文件加载中…</div>
  }

  // 渲染区：容器必须有确定宽高（Univer / docx-preview / pdf.js 都按容器尺寸排版）
  //
  // ⚠️ 必须是 flex 纵向容器：`PdfPreview` 的根类 `.pdf-preview` 是 `flex:1;min-height:0`，
  //    父级不是 flex 时它会被当成普通块 ⇒ 全部页面撑满高度、抽屉里出现双滚动条。
  const boxStyle = {
    width: '100%',
    height: '62vh',
    minHeight: 320,
    display: 'flex',
    flexDirection: 'column',
    overflow: 'hidden',
  } as const
  const legacyNote = ext === 'doc' || ext === 'xls'

  return (
    <div>
      {legacyNote && (
        <p style={{ ...HINT_BASE, margin: '0 0 8px', color: 'var(--td-warning-color, #e37318)' }}>
          这是 97-2003 老格式（.{ext}），在线渲染器只支持新格式（.docx / .xlsx）⇒
          下面很可能渲染失败，建议下载后用本地应用打开。
        </p>
      )}
      <div style={boxStyle}>
        {kind === 'pdf' && (pdfUrl
          ? <PdfPreview url={pdfUrl} />
          : <div className="no_content">PDF 准备中…</div>)}
        {kind === 'word' && <DocxPreview bytes={bytes} error={null} />}
        {kind === 'sheet' && <ExcelPreview bytes={bytes} error={null} />}
      </div>
      <div style={{ marginTop: 8, display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
        <Btn href={knowledgeApi.downloadUrl(knowledge.id)}>下载原文件</Btn>
        <span style={{ ...HINT_BASE, margin: 0 }}>
          渲染不出内容时（老格式 / 特殊字体）请用本地应用打开原文件。
        </span>
      </div>
    </div>
  )
}
