import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { knowledgeApi } from '@/api/knowledge'
import type { ChunkRevision, ChunkView, Knowledge } from '@/api/knowledge'
import { ApiError } from '@/api/rest'
import { MarkdownTextV2 } from '@/markdown/vendor/renderer'
import { KbAssistantPanel } from './KbAssistantPanel'
import { KbDocPreview, kbFileExtOf, kbPreviewKindOf } from './KbDocPreview'
import {
  KbProcessingTimeline,
  type FetchSpansFn,
  type TimelineSummary,
} from './KbProcessingTimeline'

interface Props {
  knowledge: Knowledge
  onClose: () => void
  showToast: (msg: string, type?: 'success' | 'info') => void
  /**
   * 打开时定位并高亮该分块（知识库回答的「引用」点击跳转用，对齐源站 ChatReferencesDrawer 的来源视图）。
   * 传了就直接进「分块」视图并滚到该块。
   */
  initialChunkId?: string
}

/**
 * 内容区视图 —— **三态**，逐字对齐源码 `doc-content.vue:557`
 * （`const viewMode = ref<'chunks' | 'merged' | 'preview'>('merged')`）。
 *
 * <ul>
 *   <li>{@code preview} —— 原格式预览（pdf / docx / xlsx 走专用渲染器）。
 *       只有**有原始文件且类型可预览**时才提供这个 tab（源码 {@code canPreview()}，:754）。</li>
 *   <li>{@code merged}  —— 分块合并后的正文，走 Markdown 渲染（源码 :1878）。
 *       md / txt / csv / json 这类没有「原格式」的文档就只有这个视图。</li>
 *   <li>{@code chunks}  —— 逐块列表（编辑 / 停用 / 溯源都在这里）。</li>
 * </ul>
 *
 * <p>⭐ 源码的按钮是**按类型二选一**显示的（:1841 / :1846）：
 * `canPreview()` 为真 ⇒ 显示「预览」；为假 ⇒ 显示「全文」。
 * ⛔ 不是两个都露出来让用户点一个必然空白的 tab。
 */
type ViewMode = 'preview' | 'merged' | 'chunks'

/**
 * 分块正文长度上限 —— 与后端 `KbChunkService.MAX_CONTENT_CHARS` 逐字对齐（200_000）。
 *
 * <p>⚠️ 后端用的是 Java `String.length()`（UTF-16 code unit 数），前端 `String.length`
 * 口径完全相同 ⇒ 这里不需要按码点换算，两边数出来是同一个数。
 */
const MAX_CONTENT_CHARS = 200_000

// ══════════════════════════════════════════════════════════════════════════
// 二级抽屉（处理流水线）宽度 —— 源站 `doc-content.vue:354-415` 逐字移植
// ══════════════════════════════════════════════════════════════════════════

/** 源站的 key 是 `weknora-trace-drawer-width`，本仓换掉品牌名（⛔ 不共用源站的键）。 */
const TRACE_DRAWER_WIDTH_KEY = 'nexusai-trace-drawer-width'
const TRACE_DRAWER_DEFAULT_WIDTH = 820
const TRACE_DRAWER_MIN_WIDTH = 560

/** 源站 `traceDrawerMaxWidth()`：`min(1400, max(560, floor(innerWidth * 0.92)))`。 */
function traceDrawerMaxWidth(): number {
  const inner = typeof window === 'undefined' ? TRACE_DRAWER_DEFAULT_WIDTH : window.innerWidth
  return Math.min(1400, Math.max(TRACE_DRAWER_MIN_WIDTH, Math.floor(inner * 0.92)))
}

function clampTraceDrawerWidth(width: number): number {
  return Math.max(TRACE_DRAWER_MIN_WIDTH, Math.min(traceDrawerMaxWidth(), width))
}

/**
 * 耗时文案 —— 源站 `doc-content.vue:337-344`（`formatTimelineDuration`）。
 *
 * <p>⚠️ 与 `KbProcessingTimeline` 内部的 `formatDuration` **口径不同**：这里 `0` 显示 `—`、
 * `<60s` 固定两位小数。⛔ 不要图省事复用组件里的那个（那是瀑布图条形标签的口径）。
 */
function formatTimelineDuration(ms: number): string {
  if (!ms || ms < 0) return '—'
  if (ms < 1000) return `${Math.round(ms)}ms`
  if (ms < 60000) return `${(ms / 1000).toFixed(2)}s`
  const mins = Math.floor(ms / 60000)
  const rem = ((ms % 60000) / 1000).toFixed(1)
  return `${mins}m${rem}s`
}

/**
 * `traceEntryTheme` —— 源站 `doc-content.vue:493-506`。
 *
 * <p>⚠️ 用「theme 名」而不是整串 class：class 拼装只有一处，theme 名要单独判。
 * `t-button--theme-success` 等字面量在下面的映射表里（测试按字面量锚定）。
 */
function traceEntryTheme(status: string): 'success' | 'danger' | 'warning' | 'default' {
  switch (status) {
    case 'done':
    case 'completed':
      return 'success'
    case 'failed':
      return 'danger'
    case 'running':
    case 'processing':
    case 'pending':
      return 'warning'
    default:
      return 'default'
  }
}

/** theme 名 → tdesign 按钮类（⛔ 不要把 class 拼在 `t-button--theme-${x}` 里：
 *  那样文件里就不存在 `t-button--theme-success` 字面量，既有对齐测试会假红）。 */
const TRACE_THEME_CLASS: Record<ReturnType<typeof traceEntryTheme>, string> = {
  success: 't-button--theme-success',
  danger: 't-button--theme-danger',
  warning: 't-button--theme-warning',
  default: 't-button--theme-default',
}


/** 用户可见的报错文案（后端 RFC7807 detail 优先，其次原样消息）。 */
function errorText(e: unknown): string {
  if (e instanceof ApiError) {
    const msg = e.userMessage()
    // ⭐ 最常见的一类：「后端进程还没重启 ⇒ 新端点不存在」。
    //    Spring 会报 `NoResourceFoundException: No static resource …` / `Request method 'PUT' is not supported`，
    //    原样贴给用户等于没说。这里补一句可操作的话（原始字符串仍保留在末尾，便于排查）。
    if (/NoResourceFoundException|Request method '[A-Z]+' is not supported/i.test(msg)) {
      return `${msg}（若刚升级过：后端进程需重启后才会有这个接口）`
    }
    return msg
  }
  return e instanceof Error ? e.message : String(e)
}

/**
 * 重新生成的「没生成」原因 → 中文。
 *
 * <p>⭐ 后端 `KbPostProcessOutcome.detail` 是**给日志看**的内部标记
 * （`question_disabled` / `provider_not_configured` / `empty_batch` …），
 * 直接贴进 toast 会让用户看到一屏英文标识符。这里逐条翻译，
 * ⚠️ 认不出的**原样带出**（⛔ 不要编一个「未知原因」把真实信息吞掉）。
 */
function regenerateReasonLabel(detail: string | null | undefined, status: string): string {
  const d = (detail ?? '').trim()
  if (d === 'question_off') {
    return '该知识库没有开启「问题生成」'
  }
  if (d === 'question_no_indexing') {
    return '该知识库的「向量索引」和「关键词索引」都关着，问句没有可写入的索引通道'
  }
  // 兼容旧后端 / 历史日志里的合并值（2026-11-12 起后端已拆成上面两个）
  if (d === 'question_disabled') {
    return '该知识库没有开启「问题生成」（或索引策略未启用向量/关键词索引）'
  }
  if (d === 'provider_not_configured') {
    return '问题生成模型未配置或不可用'
  }
  if (d.startsWith('empty_batch') || d.startsWith('无可生成块')) {
    return '该分块没有可生成问句的正文（图片块 / 空块）'
  }
  if (d === 'knowledge_not_found') {
    return '所属文档不存在'
  }
  if (d === 'kb_not_found') {
    return '所属知识库不存在'
  }
  if (d.startsWith('解析已被新一轮取代')) {
    return d
  }
  if (d.startsWith('全部块生成失败')) {
    return `模型调用失败（${d}）`
  }
  return d || `未生成（状态 ${status}）`
}

/**
 * 分块编辑历史里的来源标签。
 *
 * <p>⚠️ 语义要看清：后端 `KbChunkService#writeSnapshot(chunk, source)` 是**改正文之前**
 * 写的快照 ⇒ 每条 revision 里存的都是「这次变更<b>之前</b>的内容」，
 * `source` 表示「<b>因为什么</b>发生了这次变更」：
 * <ul>
 *   <li>`user` = 用户手动编辑（所以这条装的是编辑前的内容）</li>
 *   <li>`rollback` = 回滚操作（这条装的是回滚前的内容）</li>
 * </ul>
 * ⛔ 不要写成「手动编辑」—— 那会让用户以为这条记录就是自己编辑后的文本。
 */
function editSourceLabel(source: string | null | undefined): string {
  switch (source) {
    case 'user':
      return '编辑前快照'
    case 'rollback':
      return '回滚前快照'
    case '':
    case null:
    case undefined:
      return '未知来源'
    default:
      return source
  }
}

/**
 * 溯源定位（后端 `KbSourceLocator` 的 7 种 `type`）。
 *
 * <p>⚠️ 字段全部是**可选**的：后端 `@JsonInclude(NON_NULL)` + 零值归一为 null
 * （`KbSourceLocator` 紧凑构造器）⇒ 该类型用不到的字段在 JSON 里<b>根本不出现</b>。
 */
interface SourceLocator {
  type?: string
  mapping?: string | null
  partial?: boolean
  page?: number | null
  block?: number | null
  slide?: number | null
  sheet?: string | null
  row_start?: number | null
  row_end?: number | null
  /** 码点偏移（不是 `String.length`） */
  start?: number | null
  end?: number | null
  start_ms?: number | null
  end_ms?: number | null
  section?: number | null
  title?: string | null
  quote?: string | null
}

/**
 * 容错解析 `kb_chunk.source_locators`。
 *
 * <p>⭐ 坏数据是**真实存在**的（历史数据 / 手改库 / 将来格式演进）⇒ 一条解析失败
 * 绝不能让整个分块列表崩掉，这里按「无定位」处理，界面什么都不显示。
 *
 * @param raw 后端给的 JSON 字符串（可 null）
 * @returns 定位数组（永不 null）
 */
/**
 * 数一个分块挂了几张图（`image_info` 是 JSON 数组）。
 *
 * <p>⚠️ 坏 JSON / null / 非数组一律按 0 计 —— 这只是一个统计数字，
 * 不值得为它让整个 Trace 弹层报错。
 */
export function parseImageCount(raw: string | null | undefined): number {
  if (!raw) return 0
  try {
    const v = JSON.parse(raw)
    return Array.isArray(v) ? v.length : 0
  } catch {
    return 0
  }
}

export function parseLocators(raw: string | null | undefined): SourceLocator[] {
  if (!raw) return []
  try {
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed.filter((x): x is SourceLocator => !!x && typeof x === 'object')
  } catch {
    return []
  }
}

/** 毫秒 → `mm:ss`（≥1 小时给 `h:mm:ss`）。 */
function formatMs(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000))
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  const mm = String(m).padStart(2, '0')
  const ss = String(s).padStart(2, '0')
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`
}

/**
 * 单条定位的**摘要**文案（分块卡片头部那一行）。
 *
 * <p>7 种类型各一句，⛔ 不做「无定位」这种占位文案 —— 格式化不出来就返回空串，
 * 由调用方过滤掉，界面宁可不显示也不误导。
 */
function formatLocator(l: SourceLocator): string {
  switch (l.type) {
    case 'pdf':
      return l.page ? `第 ${l.page} 页` : ''
    case 'docx':
      return l.block ? `第 ${l.block} 段` : ''
    case 'slide': {
      // 派单口径：默认 `第 N 页幻灯片`；`title` 有值时**改用**标题（标题可读性更强）
      const t = l.title && l.title.trim() ? l.title.trim() : ''
      if (t) return `幻灯片「${t}」`
      return l.slide ? `第 ${l.slide} 页幻灯片` : ''
    }
    case 'sheet': {
      const name = (l.sheet && l.sheet.trim()) ? l.sheet.trim() : '工作表'
      const from = l.row_start
      const to = l.row_end
      if (from && to && to !== from) return `${name} 第 ${from}-${to} 行`
      if (from) return `${name} 第 ${from} 行`
      return name
    }
    case 'text':
      return l.start != null && l.end != null ? `字符 ${l.start}-${l.end}` : ''
    case 'time':
      if (l.start_ms == null && l.end_ms == null) return ''
      return `${formatMs(l.start_ms ?? 0)}-${formatMs(l.end_ms ?? 0)}`
    case 'section':
      return l.section ? `第 ${l.section} 节` : ''
    default:
      // 未知 type：只有明确存在 page 时才说「第 N 页」，其余一律不显示（⛔ 不自造措辞）
      return l.page ? `第 ${l.page} 页` : ''
  }
}

/** 详情弹层用的完整位置描述（摘要 + 标题/行区间等补充信息）。 */
function formatLocatorDetail(l: SourceLocator): string {
  const base = formatLocator(l)
  if (l.type === 'slide') {
    const page = l.slide ? `第 ${l.slide} 页` : ''
    const title = l.title && l.title.trim() ? `「${l.title.trim()}」` : ''
    const combined = [page, title].filter(Boolean).join(' ')
    if (combined) return `幻灯片 ${combined}`
  }
  if (l.type === 'docx' && base) return `Word 正文 ${base}`
  if (l.type === 'pdf' && base) return `PDF ${base}`
  return base
}

/** 定位类型的中文标签（详情弹层每行开头）。 */
function locatorTypeLabel(type: string | undefined): string {
  switch (type) {
    case 'pdf':
      return 'PDF'
    case 'docx':
      return 'Word'
    case 'slide':
      return '幻灯片'
    case 'sheet':
      return '工作表'
    case 'text':
      return '纯文本'
    case 'time':
      return '音频'
    case 'section':
      return '章节'
    default:
      return type || '未知'
  }
}

/**
 * 卡片头部那行定位摘要：最多 2 条，用 ` + ` 连，更多补 `等 N 处`，任一条 partial 补 `（部分）`。
 *
 * @returns 空串 = 什么都不显示
 */
export function locatorSummary(locators: SourceLocator[]): string {
  const parts = locators.map(formatLocator).filter((s) => s.length > 0)
  if (parts.length === 0) return ''
  const shown = parts.slice(0, 2).join(' + ')
  const extra = parts.length > 2 ? ` 等 ${parts.length} 处` : ''
  const partial = locators.some((l) => l.partial) ? '（部分）' : ''
  return `${shown}${extra}${partial}`
}

/** 辅助召回问题（`metadata.generated_questions[]`，形态见后端 `KbGeneratedQuestions`）。 */
interface GeneratedQuestion {
  id?: string
  question: string
  content_revision?: number
}

/**
 * 从 `metadata` 原始 JSON 里读问句。
 *
 * <p>⭐ 三种坏数据都要能扛：`metadata` 为 null / 不是对象 / 问句数组不存在。
 * 后端 `KbGeneratedQuestions.read` 同时兼容「纯字符串数组」和「对象数组」两种历史形态，
 * 这里也照做（否则老数据会一条都不显示）。
 */
export function parseGeneratedQuestions(raw: string | null | undefined): GeneratedQuestion[] {
  if (!raw) return []
  try {
    const parsed: unknown = JSON.parse(raw)
    if (!parsed || typeof parsed !== 'object') return []
    const arr = (parsed as { generated_questions?: unknown }).generated_questions
    if (!Array.isArray(arr)) return []
    const out: GeneratedQuestion[] = []
    for (const item of arr) {
      if (typeof item === 'string') {
        const q = item.trim()
        if (q) out.push({ question: q })
        continue
      }
      if (item && typeof item === 'object') {
        const q = (item as { question?: unknown }).question
        if (typeof q === 'string' && q.trim()) {
          const rec = item as { id?: unknown; content_revision?: unknown }
          out.push({
            question: q.trim(),
            id: typeof rec.id === 'string' ? rec.id : undefined,
            content_revision: typeof rec.content_revision === 'number' ? rec.content_revision : undefined,
          })
        }
      }
    }
    return out
  } catch {
    return []
  }
}

/**
 * 文档详情抽屉 · 逐项对齐源侧真机 `.doc-main-drawer`。
 *
 * <h2>⭐ 真机结构（2026-11-06 从 localhost 实测，⛔ 不要凭记忆写）</h2>
 * <pre>
 * .doc-main-drawer（右侧抽屉，宽 654px）
 *   ├─ header   高 67px，padding 14px 18px，底部 1px #e7e7e7
 *   │            ├ 文件图标 + 文件名（可换行）
 *   │            └ 右上：下载 / 追溯 / 助手 三个图标按钮
 *   └─ body（padding 24px 32px 级别的分节）
 *        ├ 基本信息      两行 上传时间 / 类型（label 12px rgba(0,0,0,.6)）
 *        ├ 自定义元数据  「添加元数据字段」
 *        ├ 摘要         可折叠摘要正文
 *        └ 文件内容      「共 N 个片段」+ 右侧 [预览] [查看分块] 两个按钮
 * </pre>
 *
 * <h2>⚠️ 本仓的真实边界（如实说明，⛔ 不造假）</h2>
 * <ul>
 *   <li>解析流水线圆点：2026-11-12 核对源站 `doc-content.vue` 抽屉 body **没有**这块
 *       （源侧 `kp-timeline` 只用于卡片 hover 的 compact popover 与二级 Trace 抽屉），
 *       本仓原先自造的三颗圆点已删除；真实处理进度走 Trace 二级抽屉（`KbProcessingTimeline`）。</li>
 *   <li>「自定义元数据」：本仓 `kb_knowledge` 无 metadata 列 ⇒ 显示「暂无元数据字段」，
 *       添加按钮点了如实说未接入。</li>
 *   <li>「摘要」：本仓有解析产出的 `summary` 字段就显示，没有就说明未生成。</li>
 *   <li>「预览」：按文件后缀分派 —— pdf → `PdfPreview`；docx → `DocxPreview`；
 *       xlsx → `ExcelPreview`；文本类 → 解析正文；其余如实说「不支持在线预览」+ 下载。
 *       三个渲染器都是本仓已有的 `components/preview/*`，本抽屉只负责取字节与分派。</li>
 * </ul>
 */
/** 引用跳转后的分块高亮（源站用整段浅黄底标示「命中的原文区域」） */
const HIGHLIGHT_CHUNK: React.CSSProperties = {
  background: '#fffbe6', boxShadow: 'inset 3px 0 0 #f7ba1e', borderRadius: 6,
}

export function KbDocDrawer({ knowledge, onClose, showToast, initialChunkId }: Props) {
  /**
   * 是否有「原格式预览」—— 逐条对齐源码 `doc-content.vue:754` 的 `canPreview()`：
   *
   * <pre>
   * if (details.type !== 'file') return false;   // ① 非文件来源
   * const ft = resolveFilePreviewExt(...);
   * if (!ft) return false;                        // ② 拿不到后缀
   * if (audioExtensions.has(ft)) return false;    // ③ 音频不走预览 tab
   * return isKnownPreviewableExt(ft);             // ④ 后缀已知可预览
   * </pre>
   *
   * <p><b>① 为什么必须先判 `type`</b>：本仓 `kb_knowledge.type` 有四个取值 ——
   * `file`（上传的文件）/ `manual`（手工创建）/ `url`（网页导入）/ `faq`（FAQ 条目）。
   * 后三种**根本没有原始文件**（`KbKnowledgeExtService:139/221` 建的行没有 file_path）。
   * 不判这一层的话，只要标题里碰巧带 `.pdf` 就会显示「预览」按钮，
   * 点进去必然 404 —— 用户看到一个点了就报错的 tab。
   *
   * <p><b>③ 音频</b>：本仓还没接 ASR/播放器，`audioExtensions` 判据先不引入；
   * 等接入时在这里加一行即可（⛔ 不要现在假装支持）。
   *
   * <p><b>④</b> = 后缀解析出的 kind 属于 pdf / word / sheet —— 只有这三类有真正的原格式渲染器；
   * md / txt / csv 等文本类没有「原格式」可言 ⇒ 那个 tab 换成「全文」（合并正文 + Markdown）。
   */
  const previewable = useMemo(() => {
    if (knowledge.type !== 'file') return false
    const kind = kbPreviewKindOf(kbFileExtOf(knowledge))
    return kind === 'pdf' || kind === 'word' || kind === 'sheet'
  }, [knowledge])

  /**
   * 文件内容区视图。
   *
   * <p>默认值按类型走（对齐源码 `doc-content.vue:762-777` 的 watch）：
   * 可原格式预览 ⇒ `preview`；否则 ⇒ `merged`（全文）。
   * ⚠️ 预览不可用时**不**自动跳分块 —— 那样会闪一下且用户不知道发生了什么。
   */
  // 引用跳转：直接进「分块」视图（源站来源视图就是在原文里定位该块）
  const [view, setView] = useState<ViewMode>(initialChunkId ? 'chunks' : (previewable ? 'preview' : 'merged'))


  /** 高亮的分块 id（引用跳转带进来的；仅视觉标记，不参与编辑） */
  const [highlightChunkId] = useState<string | null>(initialChunkId ?? null)
  const [chunks, setChunks] = useState<ChunkView[]>([])

  // 引用跳转：分块加载完滚到目标块（居中），让用户一眼看到「这段原文出自哪」
  useEffect(() => {
    if (!highlightChunkId) return
    const t = window.setTimeout(() => {
      const el = document.querySelector(`[data-chunk-id="${highlightChunkId}"]`)
      if (el && typeof (el as HTMLElement).scrollIntoView === 'function') {
        (el as HTMLElement).scrollIntoView({ block: 'center', behavior: 'smooth' })
      }
    }, 300)
    return () => window.clearTimeout(t)
  }, [highlightChunkId, chunks.length])
  const [loading, setLoading] = useState(true)
  const [chunkError, setChunkError] = useState<string | null>(null)
  /** 分块重试计数器（「正文加载失败 → 重试」用；+1 重新拉一次） */
  const [chunksReloadKey, setChunksReloadKey] = useState(0)
  /** 正在切换启停的分块 id（请求中禁用开关，防连点重复写库）。 */
  const [togglingChunkId, setTogglingChunkId] = useState<string | null>(null)
  /** 「问助手」是否展开右侧 440px 面板（设计稿 11 页两栏形态）。 */
  const [assistantOpen, setAssistantOpen] = useState(false)
  /** 带问题打开助手时的一次性初始问题（`undefined` = 走正常空态）。 */
  const [assistantInitialQuestion, setAssistantInitialQuestion] = useState<string | undefined>(undefined)

  /**
   * 带问题打开助手面板（Trace 里「诊断」按钮）。
   *
   * 源站 `AssistantActionButton.vue` 用 setPageSelection 传结构化上下文，
   * 本仓没有 selection 机制 ⇒ 把 context 拼进问题正文，让模型能看到（登记为偏离）。
   * 关面板时把 state 复位成 undefined ⇒ `KbAssistantPanel` 的 consumed ref 复位，
   * 下次点同一个诊断按钮能再次触发。
   */
  const openAssistantWith = useCallback((question: string, context?: string) => {
    const q = context?.trim() ? `${question}\n\n附带的错误信息：\n${context.trim()}` : question
    setAssistantInitialQuestion(q)
    setAssistantOpen(true)
  }, [])

  // ── 摘要（源侧 summary-section）──────────────────────────────────
  /**
   * 摘要正文。来源优先顺序：
   * ① 本组件内改过/生成过（`summaryText` state，用户刚操作完就是这个）
   * ② `knowledge.description`（后端把摘要写回这一列 —— 见 KbSummaryService 的
   *    「写回 description / profile」；⛔ 不是只存在分块里）
   *
   * ⚠️ 上一版这里写「本仓没有 summary 正文列，正文落在分块里」——**那是错的**：
   * `kb_knowledge.description` 就是摘要正文的落点，抽屉里完全可以显示它。
   */
  const [summaryText, setSummaryText] = useState(() => knowledge.description ?? '')
  const [summaryEditing, setSummaryEditing] = useState(false)
  const [summaryDraft, setSummaryDraft] = useState('')
  const [summarySaving, setSummarySaving] = useState(false)
  const [summaryRefreshing, setSummaryRefreshing] = useState(false)
  const [summaryError, setSummaryError] = useState<string | null>(null)

  /** 重新生成摘要（同步 LLM 调用，必须自己维护 loading）。 */
  const regenerateSummary = useCallback(async () => {
    setSummaryRefreshing(true)
    setSummaryError(null)
    try {
      const r = await knowledgeApi.regenerateSummary(knowledge.id)
      // ⚠️ 判据是 status 而不是「有没有正文」：failed 时 description 可能还是旧的
      if (r.summary_status === 'completed') {
        setSummaryText(r.description)
        showToast('摘要已生成', 'success')
      } else if (r.summary_status === 'failed') {
        // ⚠️ 必须把后端给的原因翻成人话：只说「失败」用户完全无从排查。
        //    实测最常见的一条是「知识库没配摘要模型」——那条必须指向去设置里配。
        setSummaryError(summaryFailureHint(r.detail))
      } else {
        setSummaryError(`摘要状态：${r.summary_status || '未知'}`)
      }
    } catch (e) {
      setSummaryError(e instanceof ApiError ? e.message : '摘要生成失败')
    } finally {
      setSummaryRefreshing(false)
    }
  }, [knowledge.id, showToast])

  /** 保存手改的摘要。 */
  const saveSummary = useCallback(async () => {
    setSummarySaving(true)
    setSummaryError(null)
    try {
      await knowledgeApi.updateSummary(knowledge.id, summaryDraft)
      setSummaryText(summaryDraft)
      setSummaryEditing(false)
      showToast('摘要已保存', 'success')
    } catch (e) {
      setSummaryError(e instanceof ApiError ? e.message : '保存失败')
    } finally {
      setSummarySaving(false)
    }
  }, [knowledge.id, summaryDraft, showToast])

  // ── 自定义元数据（源侧 metadata-section）─────────────────────────
  /** 保存成功后立刻回显用（父级不重拉时列表要能反映新值）。null = 尚未保存过。 */
  const [metaLocal, setMetaLocal] = useState<Record<string, unknown> | null>(null)

  /**
   * 元数据是「对象」，但后端 `kb_knowledge.custom_metadata` 是 **TEXT 存 JSON 串**
   * （`KnowledgeDto.customMetadata: String`）⇒ 读要 parse、写要 stringify。
   *
   * ⚠️ parse 失败**不能抛**：这一列可能是历史脏数据 / 手工 SQL 写进去的半截 JSON，
   * 抛了会让整个抽屉白屏。降级为空对象（并在编辑保存时覆盖掉脏值）。
   */
  const metaEntries = useMemo<Array<[string, unknown]>>(() => {
    // 保存成功后优先用本地值（父级可能不立刻重拉，列表要能反映刚保存的结果）
    if (metaLocal) return Object.entries(metaLocal)
    const raw = knowledge.custom_metadata
    if (!raw || !raw.trim()) return []
    try {
      const parsed = JSON.parse(raw)
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return []
      return Object.entries(parsed)
    } catch {
      return []
    }
  }, [knowledge.custom_metadata, metaLocal])

  const [metaEditing, setMetaEditing] = useState(false)
  const [metaDraft, setMetaDraft] = useState<MetaRow[]>([])
  const [metaSaving, setMetaSaving] = useState(false)
  const [metaError, setMetaError] = useState<string | null>(null)

  /** 进编辑态：把当前值拷成可编辑行；空的话先给一行空行。 */
  const startMetaEdit = useCallback(() => {
    const rows = metaEntries.map(([k, v], i) => makeMetaRow(k, v, i))
    setMetaDraft(rows.length ? rows : [makeMetaRow('', '', 0)])
    setMetaError(null)
    setMetaEditing(true)
  }, [metaEntries])

  const addMetaRow = useCallback(() => {
    setMetaDraft((d) => (d.length >= META_MAX_ROWS ? d : [...d, makeMetaRow('', '', metaRowSeed++)]))
  }, [])

  const patchMetaRow = useCallback((id: number, patch: Partial<MetaRow>) => {
    setMetaDraft((d) => d.map((r) => (r.id === id ? { ...r, ...patch } : r)))
  }, [])

  /** 校验并保存元数据。⛔ 校验失败要**停在编辑态**，不能把用户输入丢掉。 */
  const saveMeta = useCallback(async () => {
    setMetaError(null)
    const value: Record<string, unknown> = {}
    try {
      for (const row of metaDraft) {
        const key = row.key.trim()
        // 整行都空 ⇒ 用户加了行但没填，跳过（不要因为空行拦住保存）
        if (!key && !row.value.trim() && row.type !== 'null') continue
        if (!key) throw new Error('字段名不能为空')
        if (Object.prototype.hasOwnProperty.call(value, key)) {
          throw new Error(`字段名重复：${key}`)
        }
        value[key] = parseMetaValue(row)
      }
    } catch (e) {
      setMetaError(e instanceof Error ? e.message : '校验失败')
      return
    }
    setMetaSaving(true)
    try {
      await knowledgeApi.updateCustomMetadata(knowledge.id, value)
      // 回写本地视图（父组件不重拉，用户立刻能看到新值）
      setMetaEditing(false)
      showToast('元数据已保存', 'success')
      // ⚠️ 不在这里 setState 改 knowledge（它是 prop）：靠父级下次刷新。
      //    编辑态退出即可，列表区显示的是 knowledge.custom_metadata（旧值）——
      //    为让用户立刻看到，下面用一个本地覆盖值。
      setMetaLocal(value)
    } catch (e) {
      setMetaError(e instanceof ApiError ? e.message : '保存失败')
    } finally {
      setMetaSaving(false)
    }
  }, [metaDraft, knowledge.id, showToast])

  // ── 分块弹层（编辑 / 历史 / 父块 / 辅助召回问题 / 溯源详情）────────────
  /** 编辑弹层：draft 是用户正在输入的内容；失败时**保留弹层**（否则输入丢失）。 */
  const [editState, setEditState] = useState<{
    chunkId: string
    index: number
    draft: string
    saving: boolean
    error: string | null
  } | null>(null)
  /** 历史弹层：回滚也是写新版本 ⇒ 必须二次确认。 */
  const [historyState, setHistoryState] = useState<{
    chunkId: string
    index: number
    revisions: ChunkRevision[]
    loading: boolean
    error: string | null
    revertingRevision: number | null
  } | null>(null)
  /** 父块弹层：只读展示父块正文。 */
  const [parentState, setParentState] = useState<{
    chunkId: string
    index: number
    chunk: ChunkView | null
    loading: boolean
    error: string | null
  } | null>(null)
  /** 辅助召回问题弹层（数据来自 metadata.generated_questions；+ / ↻ 走真实端点）。 */
  const [questionsState, setQuestionsState] = useState<{
    chunkId: string
    index: number
    questions: GeneratedQuestion[]
    /** 新增输入框是否展开 */
    composerOpen: boolean
    /** 新增输入内容 */
    draft: string
    /** 正在保存的那条：`''` = 新增中；非空 = 正在改这条旧文本 */
    savingKey: string | null
    /** 正在改哪条（旧问句文本），null = 没有在编辑 */
    editingKey: string | null
    /** 编辑草稿 */
    editDraft: string
    /** 重新生成中 */
    regenerating: boolean
    /** 最近一次失败原因（不清空，用户能看到） */
    error: string | null
  } | null>(null)
  /** 溯源定位详情弹层（列出全部 locator）。 */
  const [locatorsState, setLocatorsState] = useState<{
    chunkId: string
    index: number
    locators: SourceLocator[]
  } | null>(null)

  /**
   * 二级抽屉（处理流水线 · 源侧 `.kp-secondary-drawer` 820px）是否打开。
   *
   * <p>⚠️ 变量名沿用 `traceOpen`：Esc 的层级判断（{@link modalOpen} / {@link closeTopModal}）
   * 已经把它当「最上面一层」，换名字只会多一处漏改的风险。
   */
  const [traceOpen, setTraceOpen] = useState(false)

  /** 二级抽屉宽度（源站 `:354-415`：localStorage 记忆 + 拖拽 + resize 重夹）。 */
  const [traceDrawerWidth, setTraceDrawerWidth] = useState(TRACE_DRAWER_DEFAULT_WIDTH)
  const [traceDrawerResizing, setTraceDrawerResizing] = useState(false)
  /** 拖拽起点（用 ref：mousemove 里要读最新值，且不需要触发重渲染）。 */
  const traceResizeStart = useRef({ x: 0, width: TRACE_DRAWER_DEFAULT_WIDTH })

  /**
   * 隐藏挂载（`compact`）上报的两件事实。
   *
   * <p>⭐ 它们是 header 按钮的**唯一数据源**：没有这个隐藏挂载，用户在打开二级抽屉之前
   * 按钮既不显示、也不知道状态色与耗时（源站 `doc-content.vue:1631-1637` 同款机制）。
   */
  const [, setHasTimelineSpans] = useState(false)
  const [timelineSummary, setTimelineSummary] = useState<TimelineSummary>({
    totalMs: 0, status: '', stageIndex: 0, stageTotal: 0, stageLabel: '',
  })

  /** 是否有弹层打开 —— Esc 要先关弹层，再关抽屉（否则一次 Esc 直接退出、输入全丢）。 */
  const modalOpen =
    editState !== null ||
    historyState !== null ||
    parentState !== null ||
    questionsState !== null ||
    locatorsState !== null ||
    traceOpen

  /**
   * Esc 只退**最上面一层**弹层（⛔ 不要一次全关：编辑弹层里可能有尚未保存的输入，
   * 一次 Esc 把整个抽屉也关掉会让用户白改一遍）。
   *
   * <p>层级顺序与渲染顺序一致：编辑 > 历史 > 父块 > 辅助问题 > 溯源。
   */
  const closeTopModal = useCallback(() => {
    if (editState) {
      // 保存中不允许 Esc 退出（请求还在飞，退了会丢掉结果提示）
      if (!editState.saving) setEditState(null)
      return
    }
    if (historyState) {
      if (historyState.revertingRevision === null) setHistoryState(null)
      return
    }
    if (parentState) { setParentState(null); return }
    if (questionsState) { setQuestionsState(null); return }
    if (locatorsState) { setLocatorsState(null); return }
    if (traceOpen) { setTraceOpen(false); return }
  }, [editState, historyState, parentState, questionsState, locatorsState, traceOpen])

  /** 用后端返回的最新分块替换列表里的那一条（列表顺序/长度不变）。 */
  const replaceChunk = useCallback((updated: ChunkView) => {
    setChunks((prev) => prev.map((c) => (c.id === updated.id ? updated : c)))
  }, [])

  useEffect(() => {
    let alive = true
    setLoading(true)
    setChunkError(null)
    console.info(`[KbDocDrawer] 开始加载分块 knowledgeId=${knowledge.id} 尝试=${chunksReloadKey + 1}`)
    knowledgeApi
      .listChunks(knowledge.id)
      .then((res) => {
        if (!alive) return
        const rows = Array.isArray(res) ? res : []
        console.info(
          `[KbDocDrawer] 分块就绪 knowledgeId=${knowledge.id} 数量=${rows.length}` +
          ` 总字数=${rows.reduce((n, c) => n + (c.content?.length ?? 0), 0)}`
        )
        setChunks(rows)
      })
      .catch((e) => {
        if (!alive) return
        const msg = e instanceof Error ? e.message : String(e)
        console.warn(`[KbDocDrawer] 分块加载失败 knowledgeId=${knowledge.id} 原因=${msg}`)
        setChunkError(msg)
      })
      .finally(() => { if (alive) setLoading(false) })
    return () => { alive = false }
  }, [knowledge.id, chunksReloadKey])

  // Esc 关闭：先逐层退弹层，弹层全关才关抽屉（与源侧抽屉一致）
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      // ⚠️ 必须**无条件**阻断冒泡：`App.tsx:2817` 也注册了
      // `useEscapeKey(showKnowledge, () => setShowKnowledge(false))`（冒泡阶段）——
      // 抽屉只在 modalOpen 时 stopPropagation 的话，没有弹层时按一次 Esc 会把
      // 抽屉和**整个知识库场景**一起关掉（用户被迫从主对话重新进入）。
      e.stopPropagation()
      e.preventDefault()
      if (modalOpen) {
        closeTopModal()
        return
      }
      onClose()
    }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [onClose, modalOpen, closeTopModal])

  const title = knowledge.title || knowledge.file_name || knowledge.id
  const sizeText = formatSize(knowledge.file_size)

  // ══════════════════════════════════════════════════════════════════════
  // 处理流水线（Trace）：取数 / 宽度拖拽 / 入口按钮（源站 doc-content.vue:325-518）
  // ══════════════════════════════════════════════════════════════════════

  /**
   * 取数函数（注入 `KbProcessingTimeline.fetchSpans`）。
   *
   * <p>⛔ 不在这里抛异常：组件把「抛异常」和「success:false」都当失败，但只有后者能带
   * 中文原因（`message` 会显示成「处理记录加载失败：{原因}」）。所以网络错/HTTP 错
   * 都折成 `{ success: false, message }`。
   *
   * <p>⚠️ `success` **原样透传**后端包装里的布尔值 —— 后端判「没有 trace」时也返回
   * 200 + `success:true` + 空 trace，那属于「真实的无记录」，不是加载失败。
   */
  const fetchSpans = useCallback<FetchSpansFn>(async (id, attempt) => {
    try {
      const res = await knowledgeApi.getKnowledgeSpans(id, attempt)
      return { success: !!res?.success, data: res?.data }
    } catch (e) {
      return { success: false, message: errorText(e) }
    }
  }, [])

  /**
   * 「重新解析」（源站 `onRetry` 的上游动作）。
   *
   * <p>失败必须**重新抛出**：组件拿到异常就不做「重置四件 + 重拉」，否则用户会看到
   * 一棵没变的老树、还以为是重试成功。提示在这里给（组件里那句注释是
   * 「重试失败由调用方提示」）。
   */
  const retryParse = useCallback(async (id: string) => {
    try {
      await knowledgeApi.reparse(id)
      showToast('已提交重新解析', 'success')
    } catch (e) {
      showToast(`重新解析失败：${errorText(e)}`, 'info')
      throw e
    }
  }, [showToast])

  // ── 宽度：初值从 localStorage 读（源站 :372-383）────────────────────
  useEffect(() => {
    try {
      const raw = window.localStorage.getItem(TRACE_DRAWER_WIDTH_KEY)
      const parsed = raw ? parseInt(raw, 10) : NaN
      if (!Number.isNaN(parsed)) setTraceDrawerWidth(clampTraceDrawerWidth(parsed))
    } catch {
      /* ignore quota / private mode */
    }
  }, [])

  // ── 窗口 resize 时重夹（源站 :416-421）──────────────────────────────
  useEffect(() => {
    const onResize = () => setTraceDrawerWidth((w) => clampTraceDrawerWidth(w))
    window.addEventListener('resize', onResize)
    return () => window.removeEventListener('resize', onResize)
  }, [])

  // ── 拖拽（源站 :385-415）：向右拖变窄、向左拖变宽 ────────────────────
  useEffect(() => {
    if (!traceDrawerResizing) return
    // 收尾时要写 localStorage；用局部变量跟住最新值，避免把 width 塞进依赖
    // （塞进去会让每次 mousemove 都拆挂一次监听器）。
    let latest = traceResizeStart.current.width
    const onMove = (e: MouseEvent) => {
      // ⚠️ 是 `startX - clientX`（抽屉贴右，向左拖才是变宽）
      latest = clampTraceDrawerWidth(traceResizeStart.current.width + traceResizeStart.current.x - e.clientX)
      setTraceDrawerWidth(latest)
    }
    const onUp = () => {
      setTraceDrawerResizing(false)
      try {
        window.localStorage.setItem(TRACE_DRAWER_WIDTH_KEY, String(latest))
      } catch {
        /* ignore */
      }
    }
    document.addEventListener('mousemove', onMove)
    document.addEventListener('mouseup', onUp)
    // 源站把光标/选中态写在 body 上（拖出抽屉边界时不丢手型）
    document.body.style.cursor = 'col-resize'
    document.body.style.userSelect = 'none'
    return () => {
      document.removeEventListener('mousemove', onMove)
      document.removeEventListener('mouseup', onUp)
      document.body.style.cursor = ''
      document.body.style.userSelect = ''
    }
  }, [traceDrawerResizing])

  const startTraceDrawerResize = (e: { preventDefault: () => void; clientX: number }) => {
    e.preventDefault()
    traceResizeStart.current = { x: e.clientX, width: traceDrawerWidth }
    setTraceDrawerResizing(true)
  }

  /**
   * 换文档 ⇒ 清掉上一个文档的 span 事实（源站 `watch(() => props.details?.id)`，:331-335）。
   *
   * <p>⚠️ 不清的话：切到一篇「没有 span」的老文档时，按钮会沿用上一份的绿色 + 耗时。
   */
  useEffect(() => {
    setHasTimelineSpans(false)
    setTraceOpen(false)
    setTimelineSummary({ totalMs: 0, status: '', stageIndex: 0, stageTotal: 0, stageLabel: '' })
  }, [knowledge.id])

  const traceTheme = traceEntryTheme(timelineSummary.status)
  /** 源站 `traceEntryTitle`（:505-518）：有耗时显示耗时，否则显示阶段进度。 */
  const traceEntryTitle = useMemo(() => {
    let tip = '查看 Trace'
    if (timelineSummary.totalMs > 0) {
      tip += ` · ${formatTimelineDuration(timelineSummary.totalMs)}`
    } else if (timelineSummary.stageTotal > 0) {
      tip += ` · ${timelineSummary.stageIndex}/${timelineSummary.stageTotal}`
    }
    return tip
  }, [timelineSummary])

  // ══════════════════════════════════════════════════════════════════════
  // 五个分块操作（编辑 / 历史 / 父块 / 辅助召回问题 / 溯源）
  // ══════════════════════════════════════════════════════════════════════

  /** 打开编辑弹层（预填当前 content）。 */
  const openEdit = (c: ChunkView, index: number) => {
    setEditState({ chunkId: c.id, index, draft: c.content ?? '', saving: false, error: null })
  }

  /**
   * 保存编辑。
   *
   * <p>⚠️ 成功后**只**说「内容已保存」：后端在事务提交后才重建索引，索引失败也只
   * `log.error`、响应仍 200 ⇒ 前端无法得知索引状态（`ChunkController#update`）。
   */
  const saveEdit = async () => {
    const st = editState
    if (!st || st.saving) return
    const content = st.draft
    if (content.trim().length === 0) return          // 按钮本来禁用，这里再兜一层
    if (content.length > MAX_CONTENT_CHARS) return   // 同上
    setEditState({ ...st, saving: true, error: null })
    try {
      const updated = await knowledgeApi.updateChunk(knowledge.id, st.chunkId, { content })
      replaceChunk(updated)
      setEditState(null)
      showToast('内容已保存', 'success')
    } catch (e) {
      // ⛔ 不关弹层：关了这一屏的输入就没了
      setEditState({ ...st, saving: false, error: errorText(e) })
      showToast(`保存失败：${errorText(e)}`, 'info')
    }
  }

  /** 打开历史弹层（列表倒序由后端给）。 */
  const openHistory = async (c: ChunkView, index: number) => {
    setHistoryState({ chunkId: c.id, index, revisions: [], loading: true, error: null, revertingRevision: null })
    try {
      const rows = await knowledgeApi.listChunkRevisions(knowledge.id, c.id)
      setHistoryState({
        chunkId: c.id, index, revisions: Array.isArray(rows) ? rows : [],
        loading: false, error: null, revertingRevision: null,
      })
    } catch (e) {
      setHistoryState({
        chunkId: c.id, index, revisions: [], loading: false, error: errorText(e), revertingRevision: null,
      })
    }
  }

  /**
   * 回滚到指定版本（**二次确认**后才发请求）。
   *
   * <p>⚠️ 回滚 = 写一条新 revision，不删历史、也不可撤销 ⇒ 必须让用户明确确认。
   */
  const doRevert = async (revision: number) => {
    const st = historyState
    if (!st || st.revertingRevision !== null) return
    const target = st.revisions.find((r) => r.revision === revision)
    const ok = window.confirm(
      `确认回滚到 v${revision}？\n\n` +
      '回滚会把该版本的内容写回正文，并生成一条新版本（历史不会被删除）。' +
      (target ? `\n\n目标版本内容预览：\n${(target.content ?? '').slice(0, 120)}` : '')
    )
    if (!ok) return
    setHistoryState({ ...st, revertingRevision: revision, error: null })
    try {
      const updated = await knowledgeApi.revertChunk(knowledge.id, st.chunkId, revision)
      replaceChunk(updated)
      setHistoryState(null)
      showToast(`已回滚到 v${revision}`, 'success')
    } catch (e) {
      setHistoryState({ ...st, revertingRevision: null, error: errorText(e) })
      showToast(`回滚失败：${errorText(e)}`, 'info')
    }
  }

  /**
   * 打开父块弹层。
   *
   * <p>⚠️ 本仓现状（如实）：**没有父子块切分**（长块没拆成子块），
   * 但**摘要块会挂靠到首个正文块**（`KbSummaryService:447` `setParentChunkId(firstTextId)`）
   * ⇒ ⛔ 不能说「所有分块都是顶层块」。
   */
  const openParent = async (c: ChunkView, index: number) => {
    const parentId = c.parent_chunk_id
    if (!parentId) {
      showToast(
        '本块没有父块（当前解析未产出父子块切分；仅摘要块会挂靠到首个正文块）',
        'info'
      )
      return
    }
    setParentState({ chunkId: c.id, index, chunk: null, loading: true, error: null })
    try {
      const parent = await knowledgeApi.getChunkById(parentId)
      setParentState({ chunkId: c.id, index, chunk: parent, loading: false, error: null })
    } catch (e) {
      // ⛔ 不假装成功：加载失败就说失败，弹层里保留错误，用户能看到是哪个 id
      setParentState({ chunkId: c.id, index, chunk: null, loading: false, error: errorText(e) })
    }
  }

  /**
   * 切换分块启停（源侧 doc-content.vue:2118 `toggleChunkEnabled`）。
   *
   * <p><b>为什么真的调后端而不是本地改 state</b>：`is_enabled` 决定该块是否参与检索
   * （`KbIndexWriter` 只索引启用块、`KbHybridSearcher` 也只返回启用块）。
   * 只改本地 state 会得到「界面看着停用了、搜索照样命中」的假象。
   *
   * <p>⚠️ 用**响应里的分块**替换本地那条：后端会同时刷 `update_time`/索引状态，
   * 本地拼的会一点刷新就变样。
   */
  const toggleChunkEnabled = async (c: ChunkView) => {
    if (togglingChunkId) return
    const next = !c.is_enabled
    setTogglingChunkId(c.id)
    try {
      const updated = await knowledgeApi.updateChunk(knowledge.id, c.id, { is_enabled: next })
      replaceChunk(updated)
      showToast(next ? '已启用该分块' : '已停用该分块（不再参与检索）', 'success')
    } catch (e) {
      // ⛔ 不假装成功：失败必须让用户知道，否则会以为已经停用了
      showToast(`切换失败：${errorText(e)}`, 'info')
    } finally {
      setTogglingChunkId(null)
    }
  }

  /** 打开辅助召回问题弹层（数据只读自 metadata；+ / ↻ / 编辑走真实端点）。 */
  const openQuestions = (c: ChunkView, index: number) => {
    setQuestionsState({
      chunkId: c.id,
      index,
      questions: parseGeneratedQuestions(c.metadata),
      composerOpen: false,
      draft: '',
      savingKey: null,
      editingKey: null,
      editDraft: '',
      regenerating: false,
      error: null,
    })
  }

  /**
   * 问句写入后的统一收尾：用后端返回的分块替换列表里的那条（metadata 已含新问句）。
   *
   * <p>⭐ 必须以**响应里的 chunk** 为准，而不是本地拼接：后端会补 id / 版本号，
   * 本地拼的列表一点刷新就变样。
   */
  const applyQuestionResult = (updated: ChunkView, questions: string[]) => {
    replaceChunk(updated)
    setQuestionsState((prev) => prev ? {
      ...prev,
      questions: questions.map((q) => ({ question: q })),
      composerOpen: false,
      draft: '',
      savingKey: null,
      editingKey: null,
      editDraft: '',
      error: null,
    } : prev)
  }

  /** 新增一条问句（`question_id` 留空 ⇒ 后端按新增处理）。 */
  const addQuestion = async () => {
    const st = questionsState
    if (!st || st.savingKey !== null) return
    const text = st.draft.trim()
    if (!text) return
    setQuestionsState({ ...st, savingKey: '', error: null })
    try {
      const r = await knowledgeApi.upsertChunkQuestion(st.chunkId, { question: text })
      applyQuestionResult(r.chunk, r.questions)
      showToast('问句已添加', 'success')
      if (!r.index_rebuilt) {
        // ⚠️ 后端已打 ERROR；这里必须让用户知道「搜不到新问句」，⛔ 不笼统说成功
        showToast('问句已保存，但索引重建失败 —— 搜索暂时仍是旧文本', 'info')
      }
    } catch (e) {
      const msg = errorText(e)
      console.warn(`[KbDocDrawer] 新增问句失败 chunk=${st.chunkId} 原因=${msg}`)
      setQuestionsState({ ...st, savingKey: null, error: msg })
    }
  }

  /** 修改一条问句（本仓无问句 id ⇒ `question_id` 传**旧问句文本**）。 */
  const saveQuestionEdit = async (oldText: string) => {
    const st = questionsState
    if (!st || st.savingKey !== null) return
    const text = st.editDraft.trim()
    if (!text) return
    setQuestionsState({ ...st, savingKey: oldText, error: null })
    try {
      const r = await knowledgeApi.upsertChunkQuestion(st.chunkId, {
        question_id: oldText,
        question: text,
      })
      applyQuestionResult(r.chunk, r.questions)
      showToast('问句已更新', 'success')
      if (!r.index_rebuilt) {
        showToast('问句已保存，但索引重建失败 —— 搜索暂时仍是旧文本', 'info')
      }
    } catch (e) {
      const msg = errorText(e)
      console.warn(`[KbDocDrawer] 修改问句失败 chunk=${st.chunkId} 旧文本=${oldText} 原因=${msg}`)
      setQuestionsState({ ...st, savingKey: null, error: msg })
    }
  }

  /** 重新生成该分块的全部问句（会真的调一次模型）。 */
  const regenerateQuestions = async () => {
    const st = questionsState
    if (!st || st.regenerating || st.savingKey !== null) return
    setQuestionsState({ ...st, regenerating: true, error: null })
    try {
      const r = await knowledgeApi.regenerateChunkQuestions(st.chunkId)
      replaceChunk(r.chunk)
      setQuestionsState((prev) => prev ? {
        ...prev,
        questions: r.questions.map((q) => ({ question: q })),
        regenerating: false,
        composerOpen: false,
        draft: '',
        editingKey: null,
        editDraft: '',
      } : prev)
      if (r.status === 'DONE') {
        showToast(`已重新生成 ${r.questions.length} 条问句`, 'success')
      } else {
        // ⛔ 不假装成功：SKIPPED 的原因（未配置模型/开关关闭/无正文）要原样告诉用户
        showToast(`未生成问句：${regenerateReasonLabel(r.detail, r.status)}`, 'info')
      }
    } catch (e) {
      const msg = errorText(e)
      console.warn(`[KbDocDrawer] 重新生成问句失败 chunk=${st.chunkId} 原因=${msg}`)
      setQuestionsState({ ...st, regenerating: false, error: msg })
    }
  }

  /** 删除一条问句（后端按问句文本匹配）。 */
  const deleteQuestion = async (text: string) => {
    const st = questionsState
    if (!st || st.savingKey !== null || st.regenerating) return
    if (!window.confirm(`删除这条问句？\n\n${text}`)) return
    setQuestionsState({ ...st, savingKey: text, error: null })
    try {
      await knowledgeApi.deleteChunkQuestion(st.chunkId, text)
      // ⚠️ 删除端点只返回 {deleted}，不返回新列表 ⇒ 按本地过滤（并且重拉一次分块让 metadata 对齐）
      setQuestionsState((prev) => prev ? {
        ...prev,
        questions: prev.questions.filter((q) => q.question !== text),
        savingKey: null,
        editingKey: null,
        error: null,
      } : prev)
      showToast('问句已删除', 'success')
      // metadata 已变 ⇒ 重拉该块（metadata 原样透传，刷新后列表才与库一致）
      try {
        const fresh = await knowledgeApi.getChunkById(st.chunkId)
        replaceChunk(fresh)
      } catch (e) {
        console.warn(`[KbDocDrawer] 删除问句后重拉分块失败 chunk=${st.chunkId} 原因=${errorText(e)}`)
      }
    } catch (e) {
      const msg = errorText(e)
      console.warn(`[KbDocDrawer] 删除问句失败 chunk=${st.chunkId} 文本=${text} 原因=${msg}`)
      setQuestionsState({ ...st, savingKey: null, error: msg })
    }
  }

  /** 打开溯源详情弹层（列出全部 locator）。 */
  const openLocators = (c: ChunkView, index: number) => {
    setLocatorsState({ chunkId: c.id, index, locators: parseLocators(c.source_locators) })
  }

  /**
   * 历史弹层里「哪条是当前正文」的版本号。
   *
   * <p>判据见 {@link currentRevisionNumber}：比的是**内容相等**，不是版本号大小
   * （后端快照是「改前写」，最大版本号装的是上一次的正文）。
   */
  const historyCurrentRevision = useMemo(() => {
    if (!historyState) return null
    return currentRevisionNumber(
      chunks.find((c) => c.id === historyState.chunkId),
      historyState.revisions
    )
  }, [historyState, chunks])

  return (
    /* 源侧 t-drawer 结构（doc-content.vue:1597-1627）：
       t-drawer 元素本身同时挂 doc-main-drawer（`<t-drawer :class="['doc-main-drawer', …]">`），
       ⚠️ 必须同元素 —— `kb-shared.css:352` 的选择器是 `.t-drawer.doc-main-drawer .t-drawer__header`。

       ⭐ 设计稿 11 页是「抽屉 654 + 助手 440」两栏。本仓抽屉是 `position:fixed` 贴右，
       助手面板同样 fixed、贴在抽屉左缘（`right:654px`），⛔ 不套 flex 外壳
       —— 套了会把抽屉的 fixed 定位基准换掉，整条抽屉会跑位。 */
    <div className="doc-main-drawer t-drawer t-drawer--open t-drawer--right">
      <div className="t-drawer__mask" onClick={onClose} />
      <div
        className="t-drawer__content-wrapper t-drawer__content-wrapper--right"
        style={{ width: 654, maxWidth: '100vw', transform: 'none' }}
        role="dialog"
        aria-modal="true"
        aria-label={`文档详情：${title}`}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="t-drawer__header">
          <div className="doc-drawer-header">
            <div className="doc-drawer-header-icon">
              <span className="row-file-icon-wrap" style={{ width: 32, height: 38, fontSize: 20 }}>📄</span>
            </div>
            <div className="doc-drawer-header-text">
              <div className="doc-drawer-header-title" title={title}>{title}</div>
            </div>
            {/* 源侧 doc-content.vue:1608-1625 —— 三个 header-action-btn，图标是 t-icon：
                ① download（下载）② chart-line（查看 Trace）③ AssistantActionsButton（问助手）。
                ⚠️ 「问 WeKnora 助手」的 WeKnora 是源站品牌名残留：源侧文案是
                `askAboutDocument: '问 {name}'`，name = 助手显示名。本仓助手不叫 WeKnora
                ⇒ 统一写「问助手」（见 i18n 口径：⛔ 用户可见文案不出现外部产品名）。 */}
            {/* 源侧 doc-content.vue:1608-1625 逐字结构：
                ① 下载：`<button class="t-button ... t-button--icon-only header-action-btn">`
                   ＋ `<svg class="t-icon t-icon-download" style="font-size:16px">`
                ② 查看 Trace：同款但 `t-button--theme-success` + `trace-entry-btn`
                ③ 问助手：**不是 t-button**，而是 `<button class="assistant-button is-icon-only">`
                   ＋ `<span class="resource-icon" mask-image=星形>`（AssistantButton.vue）
                ⚠️ 三条都必须是 `<button>`（源侧连下载也是 button，不是 `<a href>`）——
                   本仓原来把下载写成 `<a>`、图标写成 `width/height="16"` 且丢了 `t-icon` 类，
                   `.t-icon{width:1em;height:1em}` 不生效 ⇒ 图标尺寸与描边都不对。
                ⚠️ 「问 WeKnora 助手」的 WeKnora 是源站品牌名，本仓写「问助手」。 */}
            <div className="header-actions">
              <button
                type="button"
                className="t-button t-button--variant-text t-button--theme-default t-button--shape-square t-size-s t-button--icon-only header-action-btn"
                title="下载"
                aria-label="下载"
                onClick={() => { window.location.href = knowledgeApi.downloadUrl(knowledge.id) }}
              >
                <svg className="t-icon t-icon-download" style={{ fontSize: 16 }} aria-hidden="true">
                  <use href="#t-icon-download" />
                </svg>
              </button>
              {/* 查看 Trace（源站 `components/doc-content.vue:1616` 的判据是
                  `v-if="details.id && hasTimelineSpans"`）。
                  ① 入口**始终给**：只对齐 `details.id`（有文档就有入口），
                     ⛔ **不对齐 `hasTimelineSpans`** —— 本仓 V284 建 span 表**之前**
                     解析的文档在 `kb_processing_span` 里没有行，照抄该判据会让那些文档
                     **完全没有 Trace 入口**（旧版那个无条件按钮已在本批删除）。
                     点开无数据时组件自己渲染五段 pending 占位。
                  ② theme 跟着最近一次 span 的状态变（done→success / failed→danger /
                     running|pending→warning）；
                  ③ title 里带瀑布图总耗时，没有耗时才退回「查看 Trace」。 */}
              {knowledge.id && (
                <button
                  type="button"
                  className={`t-button t-button--variant-text ${TRACE_THEME_CLASS[traceTheme]} t-button--shape-square t-size-s t-button--icon-only header-action-btn trace-entry-btn`}
                  title={traceEntryTitle}
                  aria-label="查看 Trace"
                  onClick={() => setTraceOpen(true)}
                >
                  <svg className="t-icon t-icon-chart-line" style={{ fontSize: 16 }} aria-hidden="true">
                    <use href="#t-icon-chart-line" />
                  </svg>
                </button>
              )}
              <button
                type="button"
                className={`assistant-button is-icon-only${assistantOpen ? ' is-active' : ''}`}
                title="问助手"
                aria-label="问助手"
                aria-pressed={assistantOpen}
                data-assistant-ignore=""
                onClick={() => setAssistantOpen((v) => !v)}
              >
                <span className="resource-icon assistant-resource-icon" aria-hidden="true" />
              </button>
            </div>
          </div>
          {/* 源侧 closeBtn 由 t-drawer 自带（.t-drawer__close-btn 绝对定位）；
              本仓手写 DOM ⇒ 图标也用同源 svg（原本是字符 ✕，与描边风格不一致）。 */}
          <button type="button" className="t-drawer__close-btn" onClick={onClose} title="关闭" aria-label="关闭">
            <svg className="t-icon t-icon-close" style={{ fill: 'none' }} aria-hidden="true">
              <use href="#t-icon-close" />
            </svg>
          </button>
        </div>

        <div className="t-drawer__body narrow-scrollbar">
          <div className="doc-markdown-root doc-drawer-body setting-drawer__body">
            {/* 隐藏挂载（源站 doc-content.vue:1630-1636 的 `div.kp-trigger-shadow`，
                `.kp-trigger-shadow{display:none}` 见 kb-shared.css:345）。
                ⭐ 缺了它，header 的「查看 Trace」按钮在用户**打开二级抽屉之前**
                既不会出现、也不会有状态色和耗时 —— 数据全靠这棵离屏组件在轮询。
                ⚠️ `gracePoll={false}`：关掉抽屉后不该为迟到的 wiki 子 span 一直打 /spans。 */}
            <div className="kp-trigger-shadow" aria-hidden="true">
              <KbProcessingTimeline
                knowledgeId={knowledge.id}
                parseStatus={knowledge.parse_status}
                compact
                gracePoll={false}
                fetchSpans={fetchSpans}
                onHasSpans={setHasTimelineSpans}
                onSummary={setTimelineSummary}
                showToast={showToast}
              />
            </div>

            {/* 基本信息（源侧 .setting-drawer__section > .doc-detail-rows）*/}
            <section className="setting-drawer__section">
              <h4 className="setting-drawer__section-title"><span>基本信息</span></h4>
              <div className="doc-detail-rows">
                <div className="doc-detail-row">
                  <span className="doc-detail-label">上传时间</span>
                  <span className="doc-detail-value">{knowledge.created_at ?? '—'}</span>
                </div>
                <div className="doc-detail-row">
                  <span className="doc-detail-label">类型</span>
                  <span className="doc-detail-value">
                    <span className="t-tag t-tag--default t-tag--light t-size-s">
                      <span className="t-tag__text">{(knowledge.file_type || '未知').toUpperCase()}</span>
                    </span>
                    {sizeText && <span className="row-muted"> · {sizeText}</span>}
                  </span>
                </div>
                <div className="doc-detail-row">
                  <span className="doc-detail-label">状态</span>
                  <span className="doc-detail-value">{knowledge.parse_status || '—'}</span>
                </div>
                <div className="doc-detail-row">
                  <span className="doc-detail-label">片段数</span>
                  <span className="doc-detail-value">{chunks.length} 个</span>
                </div>
              </div>
            </section>

            {/* 自定义元数据（源侧 doc-content.vue:1692-1756 metadata-section）*/}
            <section className="setting-drawer__section metadata-section">
              <div className="section-title-actions">
                <h4 className="setting-drawer__section-title">
                  <span>自定义元数据</span>
                  {/* 源侧 icon="info-circle" 14px + tooltip 说明能力边界 */}
                  <span
                    className="metadata-capability-icon"
                    title="用于文档总结，并在文档被召回后作为文档级上下文辅助回答；不会参与索引或影响召回排序"
                    aria-label="自定义元数据说明"
                  >
                    <svg width="14" height="14" aria-hidden="true"><use href="#t-icon-info-circle" /></svg>
                  </span>
                  {/* 源侧 metadata-count：N/20（上限 20 条） */}
                  {metaEntries.length > 0 && (
                    <span className="metadata-count">{metaEntries.length}/20</span>
                  )}
                </h4>
                {/* 源侧只在非编辑态显示「编辑」；编辑态由下方保存/取消承担 */}
                {!metaEditing && (
                  <button
                    type="button"
                    className="t-button t-button--variant-text t-button--theme-default t-button--shape-square t-size-s icon-action-btn"
                    style={{ height: 28 }}
                    title="编辑"
                    aria-label="编辑自定义元数据"
                    onClick={startMetaEdit}
                  >
                    <span className="t-button__text">
                      <svg width="15" height="15" aria-hidden="true"><use href="#t-icon-edit" /></svg>
                    </span>
                  </button>
                )}
              </div>

              {!metaEditing ? (
                <div className="metadata-display">
                  {metaEntries.length > 0 ? (
                    <div className="metadata-grid">
                      {metaEntries.map(([k, v]) => (
                        <div className="metadata-item" key={k}>
                          <span className="metadata-item-key">{k}</span>
                          <span className="metadata-item-value">{formatMetaValue(v)}</span>
                        </div>
                      ))}
                    </div>
                  ) : (
                    /* 源侧空态是**按钮**（点它直接进编辑），不是纯文字 */
                    <button type="button" className="metadata-empty-action" onClick={startMetaEdit}>
                      <svg width="15" height="15" aria-hidden="true"><use href="#t-icon-add" /></svg>
                      <span>添加元数据字段</span>
                    </button>
                  )}
                </div>
              ) : (
                <div className="metadata-editor">
                  {metaDraft.map((row) => (
                    <div className="metadata-editor-row" key={row.id}>
                      <input
                        className="t-input__inner metadata-key-input"
                        value={row.key}
                        placeholder="字段名"
                        aria-label="字段名"
                        onChange={(e) => patchMetaRow(row.id, { key: e.target.value })}
                      />
                      <select
                        className="t-input metadata-type-select"
                        value={row.type}
                        aria-label="字段类型"
                        onChange={(e) => patchMetaRow(row.id, { type: e.target.value as MetaType })}
                      >
                        <option value="string">文本</option>
                        <option value="number">数字</option>
                        <option value="boolean">布尔</option>
                        <option value="null">空</option>
                      </select>
                      {row.type === 'boolean' ? (
                        <select
                          className="t-input metadata-value-input"
                          value={row.value}
                          aria-label="字段值"
                          onChange={(e) => patchMetaRow(row.id, { value: e.target.value })}
                        >
                          <option value="true">true</option>
                          <option value="false">false</option>
                        </select>
                      ) : row.type === 'null' ? (
                        <div className="metadata-null-value">null</div>
                      ) : (
                        <input
                          className="t-input__inner metadata-value-input"
                          value={row.value}
                          placeholder="字段值"
                          aria-label="字段值"
                          onChange={(e) => patchMetaRow(row.id, { value: e.target.value })}
                        />
                      )}
                      <button
                        type="button"
                        className="t-button t-button--variant-text t-button--theme-default t-button--shape-square t-size-s icon-action-btn metadata-remove-btn"
                        style={{ height: 28 }}
                        title="删除"
                        aria-label="删除这一行"
                        onClick={() => setMetaDraft((d) => d.filter((r) => r.id !== row.id))}
                      >
                        <span className="t-button__text">
                          <svg width="15" height="15" aria-hidden="true"><use href="#t-icon-delete" /></svg>
                        </span>
                      </button>
                    </div>
                  ))}
                  {metaDraft.length < META_MAX_ROWS && (
                    <button type="button" className="metadata-add-row" onClick={addMetaRow}>
                      <svg width="15" height="15" aria-hidden="true"><use href="#t-icon-add" /></svg>
                      <span>添加元数据字段</span>
                    </button>
                  )}
                  {metaError && <p className="metadata-error">{metaError}</p>}
                  <div className="metadata-actions">
                    <button
                      type="button"
                      className="t-button t-button--variant-outline t-button--theme-default t-size-s"
                      disabled={metaSaving}
                      onClick={() => { setMetaEditing(false); setMetaError(null) }}
                    >
                      <span className="t-button__text">取消</span>
                    </button>
                    <button
                      type="button"
                      className="t-button t-button--variant-base t-button--theme-primary t-size-s"
                      disabled={metaSaving}
                      onClick={() => void saveMeta()}
                    >
                      <span className="t-button__text">{metaSaving ? '保存中…' : '保存'}</span>
                    </button>
                  </div>
                </div>
              )}
            </section>

            {/* 摘要（源侧 doc-content.vue:1769-1828 summary-section）*/}
            <section className="setting-drawer__section summary-section">
              <div className="section-title-actions">
                <div className="summary-section-heading">
                  <h4 className="setting-drawer__section-title"><span>摘要</span></h4>
                  {summaryRefreshing && (
                    <span className="summary-refreshing-indicator">生成中…</span>
                  )}
                </div>
                {!summaryEditing && (
                  <div className="summary-title-actions">
                    <button
                      type="button"
                      className="t-button t-button--variant-text t-button--theme-default t-button--shape-square t-size-s icon-action-btn"
                      style={{ height: 28 }}
                      title="编辑摘要"
                      aria-label="编辑摘要"
                      disabled={summaryRefreshing}
                      onClick={() => { setSummaryDraft(summaryText); setSummaryEditing(true) }}
                    >
                      <span className="t-button__text">
                        <svg width="15" height="15" aria-hidden="true"><use href="#t-icon-edit" /></svg>
                      </span>
                    </button>
                    <button
                      type="button"
                      className="t-button t-button--variant-text t-button--theme-default t-button--shape-square t-size-s icon-action-btn"
                      style={{ height: 28 }}
                      title="重新生成摘要"
                      aria-label="重新生成摘要"
                      disabled={summaryRefreshing}
                      onClick={() => void regenerateSummary()}
                    >
                      <span className="t-button__text">
                        <svg width="15" height="15" aria-hidden="true"><use href="#t-icon-refresh" /></svg>
                      </span>
                    </button>
                  </div>
                )}
              </div>

              {summaryEditing ? (
                <div className="summary_editor">
                  <textarea
                    className="t-textarea__inner"
                    rows={4}
                    value={summaryDraft}
                    placeholder="暂无文档摘要"
                    aria-label="摘要正文"
                    onChange={(e) => setSummaryDraft(e.target.value)}
                  />
                  {summaryError && <p className="metadata-error">{summaryError}</p>}
                  <div className="summary_editor_actions">
                    <button
                      type="button"
                      className="t-button t-button--variant-outline t-button--theme-default t-size-s"
                      disabled={summarySaving}
                      onClick={() => { setSummaryEditing(false); setSummaryError(null) }}
                    >
                      <span className="t-button__text">取消</span>
                    </button>
                    <button
                      type="button"
                      className="t-button t-button--variant-base t-button--theme-primary t-size-s"
                      disabled={summarySaving}
                      onClick={() => void saveSummary()}
                    >
                      <span className="t-button__text">{summarySaving ? '保存中…' : '保存'}</span>
                    </button>
                  </div>
                </div>
              ) : summaryText ? (
                /* 有正文：直接展示（源侧还有折叠/展开，本仓正文较短，先不做截断） */
                <div className="summary_wrapper">
                  <div className="summary_content">{summaryText}</div>
                </div>
              ) : (
                <div className="summary_loading">
                  {knowledge.summary_status === 'pending' || knowledge.summary_status === 'processing' ? (
                    <span>正在生成摘要…</span>
                  ) : (
                    <>
                      <span>暂无文档摘要</span>
                      <button
                        type="button"
                        className="t-button t-button--variant-text t-button--theme-primary t-size-s"
                        disabled={summaryRefreshing}
                        onClick={() => void regenerateSummary()}
                      >
                        <span className="t-button__text">生成摘要</span>
                      </button>
                    </>
                  )}
                </div>
              )}
            </section>

            {/* 文件内容（源侧 doc-content-section）*/}
            <section className="setting-drawer__section doc-content-section">
              <div className="doc-content-section-head">
                <div className="doc-content-section-head-left">
                  <h4 className="setting-drawer__section-title"><span>文件内容</span></h4>
                  <span className="chunk-count">共 {chunks.length} 个片段</span>
                </div>
                <div className="view-mode-buttons">
                  {/* ⭐ 按类型二选一（源码 doc-content.vue:1841 / :1846）：
                      可原格式预览 ⇒ 显示「预览」；否则显示「全文」。
                      ⛔ 不要两个都露 —— 用户会点到一个必然空白的 tab。 */}
                  {previewable ? (
                    <button
                      type="button"
                      className={`t-button t-size-s view-mode-btn ${view === 'preview'
                        ? 't-button--variant-base t-button--theme-primary'
                        : 't-button--variant-outline t-button--theme-default'}`}
                      onClick={() => setView('preview')}
                    >
                      <span className="t-button__text">预览</span>
                    </button>
                  ) : (
                    <button
                      type="button"
                      className={`t-button t-size-s view-mode-btn ${view === 'merged'
                        ? 't-button--variant-base t-button--theme-primary'
                        : 't-button--variant-outline t-button--theme-default'}`}
                      onClick={() => setView('merged')}
                    >
                      <span className="t-button__text">全文</span>
                    </button>
                  )}
                  <button
                    type="button"
                    className={`t-button t-size-s view-mode-btn ${view === 'chunks'
                      ? 't-button--variant-base t-button--theme-primary'
                      : 't-button--variant-outline t-button--theme-default'}`}
                    onClick={() => setView('chunks')}
                  >
                    <span className="t-button__text">查看分块</span>
                  </button>
                </div>
              </div>

              {/* ⭐ view 真正控制内容区（此前只影响按钮高亮 ⇒ 点「预览」什么都没变）。
                  `preview` 与 `merged` 都渲染 KbDocPreview，用 mode 区分：
                    · preview —— 原格式（pdf/docx/xlsx 走专用渲染器）
                    · merged  —— 分块合并正文 + Markdown 渲染（源侧 doc-content.vue:1878） */}
              {(view === 'preview' || view === 'merged') && (
                <KbDocPreview
                  knowledge={knowledge}
                  chunks={chunks}
                  chunksLoading={loading}
                  chunksError={chunkError}
                  mode={view === 'merged' ? 'merged' : 'auto'}
                  onSwitchToChunks={() => setView('chunks')}
                  onRetryChunks={() => setChunksReloadKey((k) => k + 1)}
                />
              )}

              {view === 'chunks' && loading && <div className="no_content">正在加载分块…</div>}
              {view === 'chunks' && !loading && chunkError && (
                <div className="no_content" style={{ textAlign: 'left' }}>
                  分块加载失败：{chunkError}
                  <div style={{ marginTop: 10 }}>
                    <button
                      type="button"
                      className="t-button t-button--variant-outline t-button--theme-default t-size-s"
                      onClick={() => setChunksReloadKey((k) => k + 1)}
                    >
                      <span className="t-button__text">重试</span>
                    </button>
                  </div>
                </div>
              )}
              {view === 'chunks' && !loading && !chunkError && chunks.length === 0 && (
                <div className="no_content">还没有分块（文档需解析完成）</div>
              )}
              {view === 'chunks' && !loading && !chunkError && chunks.length > 0 && (
                <div className="chunk-list">
                  {chunks.map((c, i) => {
                    // 溯源摘要：解析不出就空串 ⇒ 该行整块不渲染（⛔ 不显示「无定位」）
                    const locators = parseLocators(c.source_locators)
                    const locSummary = locatorSummary(locators)
                    return (
                    <div
                      className="chunk-item"
                      key={c.id}
                      data-chunk-id={c.id}
                      style={highlightChunkId === c.id ? HIGHLIGHT_CHUNK : undefined}
                    >
                      <div className="chunk-header">
                        <div className="chunk-heading">
                          <span className="chunk-index">片段 {i + 1}</span>
                          {/* ⛔ 这里**不渲染任何东西**：源站接口不返回 char_count / token_count
                              ⇒ `getChunkMeta` 恒返回空串，那个 `<span class="chunk-meta">` 是空的。
                              用户明确要求「这个也不要了」⇒ 不输出空节点（DOM 更干净，
                              视觉效果与源站的空 span 完全一致）。 */}
                          {/* 源站没有「上下文标题」这个元素（本仓特有）⇒ 保留内容，
                              用 inline 变量上色，不新增 CSS 类 */}
                          {c.context_header && (
                            <span
                              title={c.context_header}
                              style={{ color: 'var(--td-text-color-placeholder)', fontSize: 'var(--app-text-xs)', minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
                            >
                              {c.context_header}
                            </span>
                          )}
                          {/* 溯源摘要**不放在这里**：源站卡片头部只有「片段 N」+「N 字」，
                              本仓曾在头部追加「第 2 段 + 第 3 段 等 3 处」——
                              用户实测指出「这个在原测是没有的」。
                              溯源入口挪到右侧操作排（见下），信息不丢、头部也不挤。 */}
                        </div>
                        <div className="chunk-header-right">
                          {/* 源侧 5 个操作：父块上下文 / 辅助召回问题 / 编辑 / 分块编辑历史 / 启用开关。
                              本批前 4 个已接真实接口；启用开关仍只读（写接口不在本批范围）。 */}
                          {/* ⭐⭐ 源侧 doc-content.vue:1906 是 `v-if="chunk.hasParent"`，
                              而 `hasParent = !!item.parent_chunk_id`（:1500）
                              —— **没有父块时这个按钮根本不渲染**。
                              本仓原来无条件渲染，点了只弹一句 toast「本块没有父块」
                              ⇒ 用户看到的是一个点了没反应的按钮（实测反馈「打不开父级上下文」）。
                              ⇒ 按源站改成条件渲染：没有父块就不出现这个按钮。 */}
                          {c.parent_chunk_id && (
                          <button
                            type="button"
                            className={`t-button t-button--variant-text t-button--theme-default t-button--shape-square t-size-s t-button--icon-only icon-action-btn${parentState?.chunkId === c.id ? ' is-active' : ''}`}
                            title="查看父块上下文"
                            aria-label="查看父块上下文"
                            onClick={() => void openParent(c, i)}
                          >
                            <svg className="t-icon t-icon-git-branch" style={{ fontSize: 15 }} aria-hidden="true">
                              <use href="#t-icon-git-branch" />
                            </svg>
                          </button>
                          )}
                          <button
                            type="button"
                            className={`t-button t-button--variant-text t-button--theme-default t-button--shape-square t-size-s t-button--icon-only icon-action-btn chunk-question-entry${questionsState?.chunkId === c.id ? ' is-active' : ''}`}
                            title="辅助召回问题"
                            aria-label="辅助召回问题"
                            aria-pressed={questionsState?.chunkId === c.id}
                            onClick={() => openQuestions(c, i)}
                          >
                            <svg className="t-icon t-icon-help-circle" style={{ fontSize: 15 }} aria-hidden="true">
                              <use href="#t-icon-help-circle" />
                            </svg>
                          </button>
                          <button
                            type="button"
                            className={`t-button t-button--variant-text t-button--theme-default t-button--shape-square t-size-s t-button--icon-only icon-action-btn${editState?.chunkId === c.id ? ' is-active' : ''}`}
                            title="编辑"
                            aria-label="编辑"
                            onClick={() => openEdit(c, i)}
                          >
                            <svg className="t-icon t-icon-edit" style={{ fontSize: 15 }} aria-hidden="true">
                              <use href="#t-icon-edit" />
                            </svg>
                          </button>
                          <button
                            type="button"
                            className={`t-button t-button--variant-text t-button--theme-default t-button--shape-square t-size-s t-button--icon-only icon-action-btn${historyState?.chunkId === c.id ? ' is-active' : ''}`}
                            title="分块编辑历史"
                            aria-label="分块编辑历史"
                            onClick={() => void openHistory(c, i)}
                          >
                            <svg className="t-icon t-icon-history" style={{ fontSize: 15 }} aria-hidden="true">
                              <use href="#t-icon-history" />
                            </svg>
                          </button>
                          {/* 溯源定位（本仓特有：源站没有这个按钮）。
                              ⚠️ 只在**真的有定位**时渲染 —— 没有定位的块给一个点了没反应的
                              按钮，正是用户反馈「图标无法交互」那类问题。
                              入口从头部文字摘要改成图标按钮，与其它操作同排。 */}
                          {locSummary && (
                            <button
                              type="button"
                              className={`t-button t-button--variant-text t-button--theme-default t-button--shape-square t-size-s icon-action-btn${locatorsState?.chunkId === c.id ? ' is-active' : ''}`}
                              style={{ height: 28 }}
                              title={`溯源定位：${locSummary}`}
                              aria-label="查看溯源定位"
                              onClick={() => openLocators(c, i)}
                            >
                              <svg width="15" height="15" aria-hidden="true"><use href="#t-icon-info-circle" /></svg>
                            </button>
                          )}
                          <span className="chunk-toolbar-divider" />
                          {/* 源侧 doc-content.vue:2118 是 `<t-switch @change="toggleChunkEnabled">`
                              —— **真的能切**（`PUT /chunks/{kid}/{id}` 的 `is_enabled`）。
                              本仓此前写成只读 `<span>` + `aria-disabled` ⇒ 用户点不动，
                              且标题写着「本页暂不支持切换启停」（等于承认功能缺失）。
                              ⇒ 改成真按钮 + 真实调用；滚动中/请求中禁用防连点。 */}
                          {/* 源侧 doc-content.vue:2118 结构逐字：
                              `<div class="t-switch t-size-s t-is-checked">`
                                `<span class="t-switch__handle"></span>`
                                `<div class="t-switch__content t-size-s"></div>`
                              `</div>`
                              ⚠️ 源站是 `<div>` + tdesign 内部事件；本仓无该运行时，
                              必须自己给 role/aria/onClick 才能点（否则就是一个哑 div，
                              正是用户反馈「启用 关闭无法关闭 开启」的原因）。
                              ⚠️ 也要补 `t-switch__content` —— 缺它时 `.t-switch` 里的
                              handle 定位会按不同盒模型算（源站 DOM 里它是存在的）。 */}
                          <div
                            className={`t-switch t-size-s${c.is_enabled ? ' t-is-checked' : ''}`}
                            role="switch"
                            tabIndex={0}
                            aria-checked={c.is_enabled}
                            aria-label={c.is_enabled ? '停用此分块' : '启用此分块'}
                            aria-disabled={togglingChunkId === c.id}
                            title={c.is_enabled ? '已启用（点击停用：停用后不参与检索）' : '已停用（点击启用）'}
                            onClick={() => { if (togglingChunkId !== c.id) void toggleChunkEnabled(c) }}
                            onKeyDown={(e) => {
                              if (e.key === 'Enter' || e.key === ' ') {
                                e.preventDefault()
                                if (togglingChunkId !== c.id) void toggleChunkEnabled(c)
                              }
                            }}
                          >
                            <span className="t-switch__handle" />
                            <div className="t-switch__content t-size-s" />
                          </div>
                        </div>
                      </div>
                      {/* ⭐ 源站 doc-content.vue:2139 是
                          `<div class="md-content" v-html="chunk.processedContent">`
                          —— 分块正文是**渲染后的 markdown**，不是纯文本。
                          本仓此前直接 `{c.content}` 输出原始串 ⇒ 标题字面露出 `# / ##`、
                          表格退化成一行竖线、列表没有缩进，「布局和源站不一致」的观感就来自这里。
                          ⇒ 改用与对话正文同一个渲染入口（MarkdownText）渲染同一份内容。 */}
                      <MarkdownTextV2
                        text={c.content ?? ''}
                        className={`md-content${c.is_enabled ? '' : ' chunk-disabled'}`}
                      />
                    </div>
                    )
                  })}
                </div>
              )}
            </section>
          </div>
        </div>
      </div>
      {/* 右侧助手（设计稿 11 页 440px）—— fixed 贴抽屉左缘，⛔ 不嵌进抽屉内容区 */}
      {assistantOpen && (
        <KbAssistantPanel
          baseId={knowledge.knowledge_base_id}
          knowledgeId={knowledge.id}
          docTitle={title}
          onClose={() => { setAssistantOpen(false); setAssistantInitialQuestion(undefined) }}
          showToast={showToast}
          initialQuestion={assistantInitialQuestion}
        />
      )}

      {/* 二级抽屉 · 处理流水线（源侧 `doc-content.vue:1639-1655`）。
          ⚠️ 不复用 ChunkModal：源站是 **820px 贴右抽屉**（可拖宽），不是 640px 居中弹窗。
          ⚠️ z-index 必须是 2100：现有主抽屉 1500、ChunkModal 1600、宽度拖拽把手 2101
          —— 取 1500 / 1600 会被主抽屉或分块弹层盖住（面板打开了却看不见）。 */}
      {traceOpen && createPortal(
        /* `.kb-scope` 只做设计 token 宿主（portal 已脱离原 `.kb-scope` 子树，
           不包这层 `var(--td-*)` 全部解析为空）—— 与 {@link ChunkModal} 同款处理。 */
        <div className="kb-scope" style={{ display: 'contents' }}>
          <div
            role="presentation"
            onClick={() => setTraceOpen(false)}
            style={{ position: 'fixed', inset: 0, zIndex: 2099, background: 'rgba(0,0,0,0.45)' }}
          />
          {/* 宽度拖拽把手（源站 :1641-1646 + `.trace-drawer-resize-handle`，z-index 2101） */}
          <div
            className="trace-drawer-resize-handle"
            style={{ right: traceDrawerWidth }}
            role="separator"
            aria-orientation="vertical"
            aria-label="拖拽调整面板宽度"
            title="拖拽调整面板宽度"
            onMouseDown={startTraceDrawerResize}
          >
            <div className="trace-drawer-resize-line" />
          </div>
          <div
            className={`kp-secondary-drawer${traceDrawerResizing ? ' is-resizing' : ''}`}
            role="dialog"
            aria-modal="true"
            aria-label="处理流水线"
            onClick={(e) => e.stopPropagation()}
            style={{
              // kb-02.css 把 `.kp-secondary-drawer` 钉成 820px 固定宽 ⇒ 拖拽必须行内覆盖
              width: traceDrawerWidth,
              minWidth: traceDrawerWidth,
              maxWidth: '100vw',
              position: 'fixed',
              top: 0,
              right: 0,
              bottom: 0,
              zIndex: 2100,
              background: 'var(--td-bg-color-container, #fff)',
              boxShadow: '-6px 0 24px rgba(0,0,0,.12)',
              transition: traceDrawerResizing ? 'none' : undefined,
            }}
          >
            <div className="kp-drawer-shell">
              {/* 完整模式（compact=false）：头部工具栏 + 瀑布图 + 详情面板。
                  `showClose` 的 ✕ 与 Esc 走同一个关闭动作（都是 setTraceOpen(false)）。 */}
              <KbProcessingTimeline
                knowledgeId={knowledge.id}
                parseStatus={knowledge.parse_status}
                docTitle={title}
                showClose
                onClose={() => setTraceOpen(false)}
                fetchSpans={fetchSpans}
                onRetryParse={retryParse}
                showToast={showToast}
                onOpenAssistant={openAssistantWith}
              />
            </div>
          </div>
        </div>,
        document.body,
      )}

      {/* ── 分块弹层（z-index 高于抽屉的 1500）──────────────────────── */}

      {/* 1. 编辑内容 */}
      {editState && (
        <ChunkModal
          title={`编辑片段 ${editState.index + 1}`}
          onClose={() => { if (!editState.saving) setEditState(null) }}
        >
          <textarea
            value={editState.draft}
            onChange={(e) => setEditState({ ...editState, draft: e.target.value })}
            rows={14}
            spellCheck={false}
            style={EDIT_TEXTAREA_STYLE}
            disabled={editState.saving}
            aria-label="分块内容"
          />
          {/* 字符计数放右下角（派单口径），超限转红 */}
          <div
            style={{
              marginTop: 6,
              textAlign: 'right',
              fontSize: 'var(--app-text-sm)',
              color: editState.draft.length > MAX_CONTENT_CHARS
                ? 'var(--td-error-color)'
                : 'var(--td-text-color-placeholder)',
            }}
          >
            已用 {editState.draft.length} / {MAX_CONTENT_CHARS} 字符
            {editState.draft.trim().length === 0 && '（内容不能为空，如需隐藏请用「停用」）'}
          </div>
          <div style={EDIT_FOOTER_STYLE}>
            <button
              type="button"
              className="t-button t-button--variant-outline t-button--theme-default t-size-s"
              onClick={() => setEditState(null)}
              disabled={editState.saving}
            >
              <span className="t-button__text">取消</span>
            </button>
            <button
              type="button"
              className="t-button t-button--variant-base t-button--theme-primary t-size-s"
              onClick={() => void saveEdit()}
              disabled={
                editState.saving ||
                editState.draft.trim().length === 0 ||
                editState.draft.length > MAX_CONTENT_CHARS
              }
            >
              <span className="t-button__text">{editState.saving ? '保存中…' : '保存'}</span>
            </button>
          </div>
          {editState.draft.length > MAX_CONTENT_CHARS && (
            <p style={ERROR_TEXT_STYLE}>分块内容超过上限 {MAX_CONTENT_CHARS} 字符，请精简后再保存。</p>
          )}
          {editState.error && <p style={ERROR_TEXT_STYLE}>保存失败：{editState.error}</p>}
          {/* ⚠️ 索引重建在事务提交后异步发生、失败也不改 HTTP 状态 ⇒ 只能承诺「已保存」 */}
          <p style={{ ...HINT_TEXT_STYLE }}>
            保存后正文立即写入；检索索引由后端在事务提交后重建，重建结果不会回传前端。
          </p>
        </ChunkModal>
      )}

      {/* 2. 编辑历史 + 回滚 */}
      {historyState && (
        <ChunkModal
          title={`分块编辑历史 · 片段 ${historyState.index + 1}`}
          onClose={() => { if (historyState.revertingRevision === null) setHistoryState(null) }}
        >
          {historyState.loading && <p style={HINT_TEXT_STYLE}>正在加载历史…</p>}
          {!historyState.loading && historyState.error && (
            <p style={ERROR_TEXT_STYLE}>历史加载失败：{historyState.error}</p>
          )}
          {!historyState.loading && !historyState.error && historyState.revisions.length === 0 && (
            <p style={HINT_TEXT_STYLE}>该分块暂无编辑历史（只有编辑或回滚过才会产生版本记录）</p>
          )}
          {/* ⭐ 实话：本仓历史是「变更前快照」⇒ 当前正文只有在被后续编辑/回滚覆盖后才会入列表。
              没有任何版本内容等于当前正文时，如实说明，⛔ 不挑一条冒充「当前」。 */}
          {historyState.revisions.length > 0 && historyCurrentRevision == null && (
            <p style={HINT_TEXT_STYLE}>
              当前正文（v{chunks.find((c) => c.id === historyState.chunkId)?.content_revision ?? 0}）
              尚未存为历史记录：列表里每一条保存的都是「那次变更之前」的内容，
              当前正文会在下次编辑或回滚时被写入。因此下面没有标「当前」的条目。
            </p>
          )}
          {historyState.revisions.length > 0 && (
            <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: 8 }}>
              {historyState.revisions.map((r) => {
                const rev = r.revision ?? 0
                const isCurrent = historyCurrentRevision != null && rev === historyCurrentRevision
                return (
                  <li
                    key={r.id}
                    style={{
                      border: '1px solid var(--td-component-stroke)',
                      borderRadius: 'var(--app-radius-sm)',
                      padding: '8px 10px',
                      display: 'flex',
                      alignItems: 'flex-start',
                      gap: 10,
                    }}
                  >
                    <div style={{ minWidth: 0, flex: 1 }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                        <span style={{ fontWeight: 600, fontSize: 'var(--app-text-base)' }}>v{rev}</span>
                        {isCurrent && (
                          <span
                            style={{
                              fontSize: 'var(--app-text-xs)',
                              padding: '0 6px',
                              borderRadius: 'var(--app-radius-xs, 4px)',
                              background: 'var(--td-brand-color-light)',
                              color: 'var(--td-text-color-brand)',
                            }}
                          >
                            当前
                          </span>
                        )}
                        <span style={{ fontSize: 'var(--app-text-xs)', color: 'var(--td-text-color-placeholder)' }}>
                          {r.edited_at ?? r.created_at ?? '—'}
                        </span>
                        <span style={{ fontSize: 'var(--app-text-xs)', color: 'var(--td-text-color-secondary)' }}>
                          {editSourceLabel(r.edit_source)}
                        </span>
                        {!r.is_enabled && (
                          <span style={{ fontSize: 'var(--app-text-xs)', color: 'var(--td-text-color-placeholder)' }}>
                            停用态
                          </span>
                        )}
                      </div>
                      <p
                        style={{
                          margin: '4px 0 0',
                          fontSize: 'var(--app-text-sm)',
                          color: 'var(--td-text-color-secondary)',
                          wordBreak: 'break-word',
                        }}
                      >
                        {(r.content ?? '').slice(0, 80) || '（空）'}
                        {(r.content ?? '').length > 80 ? '…' : ''}
                      </p>
                    </div>
                    {!isCurrent && (
                      <button
                        type="button"
                        className="t-button t-button--variant-outline t-button--theme-default t-size-s"
                        style={{ flexShrink: 0 }}
                        disabled={historyState.revertingRevision !== null}
                        onClick={() => void doRevert(rev)}
                      >
                        <span className="t-button__text">
                          {historyState.revertingRevision === rev ? '回滚中…' : '回滚到此版本'}
                        </span>
                      </button>
                    )}
                  </li>
                )
              })}
            </ul>
          )}
          <p style={HINT_TEXT_STYLE}>
            版本号越大越新；每条记录存的是「那次变更<b>之前</b>的内容」。回滚会把目标内容写回正文并追加一条新版本，历史不会被删除。
          </p>
        </ChunkModal>
      )}

      {/* 3. 父块上下文（只读） */}
      {parentState && (
        <ChunkModal
          title={`父块上下文 · 片段 ${parentState.index + 1}`}
          onClose={() => setParentState(null)}
        >
          {parentState.loading && <p style={HINT_TEXT_STYLE}>正在加载父块…</p>}
          {!parentState.loading && parentState.error && (
            <p style={ERROR_TEXT_STYLE}>父块加载失败：{parentState.error}</p>
          )}
          {!parentState.loading && !parentState.error && parentState.chunk && (
            <>
              <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 8 }}>
                <span style={{ fontSize: 'var(--app-text-xs)', color: 'var(--td-text-color-placeholder)' }}>
                  ID：{parentState.chunk.id}
                </span>
                <span style={{ fontSize: 'var(--app-text-xs)', color: 'var(--td-text-color-placeholder)' }}>
                  {parentState.chunk.content?.length ?? 0} 字
                </span>
                {parentState.chunk.chunk_type && (
                  <span style={{ fontSize: 'var(--app-text-xs)', color: 'var(--td-text-color-placeholder)' }}>
                    类型：{parentState.chunk.chunk_type}
                  </span>
                )}
              </div>
              <div style={PARENT_CONTENT_STYLE}>{parentState.chunk.content}</div>
            </>
          )}
        </ChunkModal>
      )}

      {/* 4. 辅助召回问题（+ 新增 / 逐条编辑 / ↻ 重新生成） */}
      {questionsState && (
        <ChunkModal
          title={`辅助召回问题 · 片段 ${questionsState.index + 1}`}
          onClose={() => setQuestionsState(null)}
        >
          {/* 标题栏右侧两个操作（对齐源站 chunk-popup-actions）：+ 添加 / ↻ 重新生成 */}
          <div
            className="chunk-popup-actions"
            style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 10, justifyContent: 'flex-end' }}
          >
            <span style={{ marginRight: 'auto', fontSize: 'var(--app-text-sm)', color: 'var(--td-text-color-secondary)' }}>
              共 {questionsState.questions.length} 条
            </span>
            <button
              type="button"
              title="添加辅助召回问题"
              aria-label="添加辅助召回问题"
              disabled={questionsState.savingKey !== null || questionsState.regenerating}
              onClick={() => setQuestionsState((prev) => prev ? {
                ...prev, composerOpen: !prev.composerOpen, draft: '', editingKey: null, error: null,
              } : prev)}
              style={QUESTION_ACTION_BTN_STYLE}
            >
              +
            </button>
            <button
              type="button"
              title="重新生成问题"
              aria-label="重新生成问题"
              disabled={questionsState.regenerating || questionsState.savingKey !== null}
              onClick={() => void regenerateQuestions()}
              style={QUESTION_ACTION_BTN_STYLE}
            >
              {questionsState.regenerating ? '…' : '↻'}
            </button>
          </div>

          {/* 新增问句输入区 */}
          {questionsState.composerOpen && (
            <div style={{ display: 'flex', gap: 6, marginBottom: 10 }}>
              <input
                autoFocus
                value={questionsState.draft}
                onChange={(e) => setQuestionsState((prev) => prev ? { ...prev, draft: e.target.value } : prev)}
                onKeyDown={(e) => { if (e.key === 'Enter') void addQuestion() }}
                placeholder="输入一条用户可能会用来搜索本段的问句"
                aria-label="新问句"
                style={QUESTION_INPUT_STYLE}
              />
              <button
                type="button"
                className="doc-filter-toggle"
                disabled={questionsState.savingKey !== null || !questionsState.draft.trim()}
                onClick={() => void addQuestion()}
              >
                {questionsState.savingKey === '' ? '保存中…' : '添加'}
              </button>
            </div>
          )}

          {questionsState.questions.length === 0 ? (
            <p style={HINT_TEXT_STYLE}>
              本分块暂无辅助召回问题。可点右上角「+」手写一条，或点「↻」让模型重新生成
              （需要知识库开启问题生成并有可用模型）。
            </p>
          ) : (
            <>
              <ol style={{ margin: 0, paddingLeft: 20, display: 'flex', flexDirection: 'column', gap: 6 }}>
                {questionsState.questions.map((q, qi) => {
                  const editing = questionsState.editingKey === q.question
                  return (
                    <li key={`${qi}-${q.question}`} style={{ fontSize: 'var(--app-text-base)' }}>
                      {editing ? (
                        <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
                          <input
                            autoFocus
                            value={questionsState.editDraft}
                            onChange={(e) => setQuestionsState((prev) => prev ? { ...prev, editDraft: e.target.value } : prev)}
                            onKeyDown={(e) => {
                              if (e.key === 'Enter') void saveQuestionEdit(q.question)
                              if (e.key === 'Escape') setQuestionsState((prev) => prev ? { ...prev, editingKey: null } : prev)
                            }}
                            aria-label="编辑问句"
                            style={QUESTION_INPUT_STYLE}
                          />
                          <button
                            type="button"
                            className="doc-filter-toggle"
                            disabled={questionsState.savingKey !== null || !questionsState.editDraft.trim()}
                            onClick={() => void saveQuestionEdit(q.question)}
                          >
                            {questionsState.savingKey === q.question ? '保存中…' : '保存'}
                          </button>
                          <button
                            type="button"
                            className="doc-filter-toggle"
                            disabled={questionsState.savingKey !== null}
                            onClick={() => setQuestionsState((prev) => prev ? { ...prev, editingKey: null } : prev)}
                          >
                            取消
                          </button>
                        </div>
                      ) : (
                        <div style={{ display: 'flex', alignItems: 'flex-start', gap: 6 }}>
                          <span style={{ flex: 1, minWidth: 0 }}>{q.question}</span>
                          {q.content_revision != null && (
                            <span style={{ fontSize: 'var(--app-text-xs)', color: 'var(--td-text-color-placeholder)' }}>
                              v{q.content_revision}
                            </span>
                          )}
                          <button
                            type="button"
                            title="修改这条问句"
                            aria-label="修改这条问句"
                            disabled={questionsState.savingKey !== null || questionsState.regenerating}
                            onClick={() => setQuestionsState((prev) => prev ? {
                              ...prev, editingKey: q.question, editDraft: q.question, error: null,
                            } : prev)}
                            style={QUESTION_INLINE_BTN_STYLE}
                          >
                            ✎
                          </button>
                          <button
                            type="button"
                            title="删除这条问句"
                            aria-label="删除这条问句"
                            disabled={questionsState.savingKey !== null || questionsState.regenerating}
                            onClick={() => void deleteQuestion(q.question)}
                            style={QUESTION_INLINE_BTN_STYLE}
                          >
                            🗑
                          </button>
                        </div>
                      )}
                    </li>
                  )
                })}
              </ol>
              <p style={HINT_TEXT_STYLE}>
                这些问句会被拼进该分块的检索文本，用户用问句搜索也能命中本块。
              </p>
            </>
          )}
          {questionsState.error && (
            <p style={ERROR_TEXT_STYLE}>操作失败：{questionsState.error}</p>
          )}
        </ChunkModal>
      )}

      {/* 5. 溯源定位详情 */}
      {locatorsState && (
        <ChunkModal
          title={`溯源定位 · 片段 ${locatorsState.index + 1}`}
          onClose={() => setLocatorsState(null)}
        >
          {locatorsState.locators.length === 0 ? (
            <p style={HINT_TEXT_STYLE}>本分块没有可用的溯源定位</p>
          ) : (
            <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: 10 }}>
              {locatorsState.locators.map((l, li) => (
                <li
                  key={li}
                  style={{
                    border: '1px solid var(--td-component-stroke)',
                    borderRadius: 'var(--app-radius-sm)',
                    padding: '8px 10px',
                  }}
                >
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                    <span style={{ fontWeight: 600, fontSize: 'var(--app-text-base)' }}>
                      {locatorTypeLabel(l.type)}
                    </span>
                    <span style={{ fontSize: 'var(--app-text-sm)', color: 'var(--td-text-color-secondary)' }}>
                      {formatLocatorDetail(l) || '（无位置信息）'}
                    </span>
                    {l.partial && (
                      <span style={{ fontSize: 'var(--app-text-xs)', color: 'var(--td-text-color-placeholder)' }}>
                        （部分）
                      </span>
                    )}
                    {l.mapping && (
                      <span style={{ fontSize: 'var(--app-text-xs)', color: 'var(--td-text-color-placeholder)' }}>
                        对齐：{l.mapping}
                      </span>
                    )}
                  </div>
                  {/* quote 为空就不显示这一行（后端空串已被归一成 null） */}
                  {l.quote && (
                    <p
                      style={{
                        margin: '4px 0 0',
                        fontSize: 'var(--app-text-sm)',
                        color: 'var(--td-text-color-secondary)',
                        wordBreak: 'break-word',
                      }}
                    >
                      「{l.quote.slice(0, 100)}
                      {l.quote.length > 100 ? '…' : ''}」
                    </p>
                  )}
                </li>
              ))}
            </ul>
          )}
        </ChunkModal>
      )}
  </div>
)
}

/**
 * 分块弹层的通用外壳（遮罩 + 白卡）。
 *
 * <p>⭐⭐ 必须走 {@code createPortal(document.body)}：
 * 抽屉根节点是 `.t-drawer`（`position:fixed; overflow:hidden`），且 `.kb-scene-panel`
 * 祖先也带 `overflow:hidden` + 圆角裁剪；若弹层直接渲染在抽屉的 React 树里，
 * 遮罩会被裁在抽屉矩形内、`position:fixed` 还会被带 `transform` 的祖先改变包含块
 * ⇒ 弹层跑到屏幕外或只盖住半个抽屉（本仓 RightPanel 的抽屉已踩过同一个坑）。
 *
 * <p>⚠️ Esc 由最外层 `KbDocDrawer` 统一处理（捕获阶段逐层退），这里不再单独监听，
 * 否则两层监听会各自退一次。
 */
function ChunkModal({
  title,
  onClose,
  children,
}: {
  title: string
  onClose: () => void
  children: ReactNode
}) {
  return createPortal(
    /* `.kb-scope` 只做设计 token 宿主（该 class 自身无布局规则）⇒ display:contents 不产生盒子。
       ⚠️ 必须包这一层：portal 挂在 document.body 上，已不在原 `.kb-scope` 子树里，
       不包的话 `var(--td-text-color-*)` / `var(--app-text-*)` 全部解析为空。 */
    <div className="kb-scope" style={{ display: 'contents' }}>
    <div
      role="presentation"
      onClick={onClose}
      style={{
        position: 'fixed',
        inset: 0,
        zIndex: 1600,
        background: 'rgba(0,0,0,0.45)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: 20,
      }}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label={title}
        onClick={(e) => e.stopPropagation()}
        style={{
          width: '100%',
          maxWidth: 640,
          maxHeight: 'calc(100vh - 40px)',
          display: 'flex',
          flexDirection: 'column',
          background: 'var(--td-bg-color-container, #fff)',
          color: 'var(--td-text-color-primary, rgba(0,0,0,.9))',
          borderRadius: 'var(--app-radius-lg, 10px)',
          boxShadow: 'var(--td-shadow-3, 0 8px 30px rgba(0,0,0,.2))',
          overflow: 'hidden',
        }}
      >
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 12,
            padding: '12px 16px',
            borderBottom: '1px solid var(--td-component-stroke, #e7e7e7)',
            flexShrink: 0,
          }}
        >
          <span style={{ fontSize: 'var(--app-text-xl, 16px)', fontWeight: 600 }}>{title}</span>
          <button
            type="button"
            onClick={onClose}
            title="关闭"
            aria-label="关闭"
            style={{
              border: 'none',
              background: 'transparent',
              cursor: 'pointer',
              fontSize: 16,
              lineHeight: 1,
              padding: 4,
              color: 'var(--td-text-color-secondary, rgba(0,0,0,.6))',
            }}
          >
            ✕
          </button>
        </div>
        <div style={{ padding: '14px 16px', overflow: 'auto', minHeight: 0 }}>{children}</div>
      </div>
    </div>
    </div>,
    document.body
  )
}

/**
 * 算出「当前正文对应的那条历史记录」的版本号（没有则 null）。
 *
 * <p>⭐⭐ <b>为什么不用「版本号最大 / 等于 content_revision」</b>（派单原判据）：
 * 后端 `KbChunkService#writeSnapshot` 是在**改正文之前**写的快照 ——
 * 第一次编辑先落一条 revision=1（内容是**改前**原文），然后才把正文改成新内容、
 * `content_revision` 从 0 变 1。⇒ 版本号最大的那条装的其实是「上一次的正文」，
 * 按版本号打「当前」会把用户已经在看的正文标成另一条，属于**用户可见的假信息**。
 *
 * <p>因此判据改成**内容相等**：`r.content === 当前正文`。
 * 若历史里没有任何一条与当前正文相同（正文刚改过、快照还没轮到它），
 * 就一条都不打「当前」—— 这也是实话，而不是挑一条冒充。
 *
 * <p>多条相同内容时取**版本号最大**的那条（最近一次是这个内容）。
 */
function currentRevisionNumber(
  chunk: ChunkView | undefined,
  revisions: ChunkRevision[]
): number | null {
  const current = chunk?.content
  if (current == null) return null
  let best: number | null = null
  for (const r of revisions) {
    if (r.revision == null || (r.content ?? '') !== current) continue
    if (best == null || r.revision > best) best = r.revision
  }
  return best
}

/** 编辑框样式（多行、等宽可读、随弹层高度伸缩）。 */
const EDIT_TEXTAREA_STYLE = {
  width: '100%',
  boxSizing: 'border-box' as const,
  resize: 'vertical' as const,
  padding: '8px 10px',
  fontSize: 'var(--app-text-base, 14px)',
  lineHeight: 1.6,
  color: 'var(--td-text-color-primary, rgba(0,0,0,.9))',
  background: 'var(--td-bg-color-container, #fff)',
  border: '1px solid var(--td-component-border, #dcdcdc)',
  borderRadius: 'var(--app-radius-sm, 6px)',
  fontFamily: 'inherit',
}

const EDIT_FOOTER_STYLE = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  gap: 12,
  marginTop: 10,
  flexWrap: 'wrap' as const,
}

const ERROR_TEXT_STYLE = {
  margin: '8px 0 0',
  fontSize: 'var(--app-text-sm, 12px)',
  color: 'var(--td-error-color, #e34d59)',
}

const HINT_TEXT_STYLE = {
  margin: '10px 0 0',
  fontSize: 'var(--app-text-xs, 11px)',
  color: 'var(--td-text-color-placeholder, rgba(0,0,0,.4))',
  lineHeight: 1.6,
}

/** 问句弹层右上角「+ / ↻」方形按钮（内联样式，⛔ 不改全局 CSS）。 */
const QUESTION_ACTION_BTN_STYLE = {
  width: 26,
  height: 26,
  display: 'inline-flex' as const,
  alignItems: 'center' as const,
  justifyContent: 'center' as const,
  border: '1px solid var(--td-component-stroke, #e7e7e7)',
  borderRadius: 'var(--app-radius-sm, 6px)',
  background: 'transparent',
  cursor: 'pointer',
  fontSize: 14,
  lineHeight: 1,
  color: 'var(--td-text-color-primary, rgba(0,0,0,.9))',
}

/** 问句输入框（新增 / 行内编辑共用）。 */
const QUESTION_INPUT_STYLE = {
  flex: 1,
  minWidth: 0,
  boxSizing: 'border-box' as const,
  padding: '5px 8px',
  fontSize: 'var(--app-text-base, 14px)',
  color: 'var(--td-text-color-primary, rgba(0,0,0,.9))',
  background: 'var(--td-bg-color-container, #fff)',
  border: '1px solid var(--td-component-border, #dcdcdc)',
  borderRadius: 'var(--app-radius-sm, 6px)',
  fontFamily: 'inherit',
}

/** 问句行内的「✎ / 🗑」小按钮。 */
const QUESTION_INLINE_BTN_STYLE = {
  border: 'none',
  background: 'transparent',
  padding: 2,
  cursor: 'pointer',
  fontSize: 13,
  lineHeight: 1,
  color: 'var(--td-text-color-secondary, rgba(0,0,0,.6))',
  flex: 'none' as const,
}

/** 父块正文（只读）：等宽、可纵向滚动、长文不撑破弹层。 */
const PARENT_CONTENT_STYLE = {
  whiteSpace: 'pre-wrap' as const,
  wordBreak: 'break-word' as const,
  fontSize: 'var(--app-text-base, 14px)',
  lineHeight: 1.7,
  maxHeight: '50vh',
  overflow: 'auto',
  padding: '10px 12px',
  background: 'var(--td-bg-color-secondarycontainer, #f3f3f3)',
  borderRadius: 'var(--app-radius-sm, 6px)',
}

/**
 * 摘要状态的实话。
 *
 * <p>⭐ 本仓 `kb_knowledge.summary_status` 是唯一真值；`kb_knowledge` 没有 summary 正文列，
 * 摘要正文在分块/后处理产物里。所以这里只报状态，⛔ 不拼一段假摘要。
 */
// ══════════════════════════════════════════════════════════════
// 自定义元数据：行模型与值解析（对齐源侧 doc-content.vue:78-152）
// ══════════════════════════════════════════════════════════════

/** 元数据值类型（源侧 metadataTypeOptions 的四档）。 */
type MetaType = 'string' | 'number' | 'boolean' | 'null'

/** 编辑态的一行。`id` 只用于 React key / 定位，不提交。 */
interface MetaRow {
  id: number
  key: string
  value: string
  type: MetaType
}

/** 元数据上限（源侧 20：编辑态 `v-if="metadataDraft.length < 20"`）。 */
const META_MAX_ROWS = 20
/** 行 id 自增种子（模块级：同一会话内不重复即可，不需要全局唯一）。 */
let metaRowSeed = 0

/** 由「已有值」推断类型（源侧 makeMetadataRow 同构）。 */
function metaTypeOf(v: unknown): MetaType {
  if (v === null) return 'null'
  if (typeof v === 'number') return 'number'
  if (typeof v === 'boolean') return 'boolean'
  return 'string'
}

function makeMetaRow(key: string, value: unknown, id: number): MetaRow {
  return {
    id,
    key,
    // null 的「值」在 UI 上不可编辑（渲染成固定文本 null），存空串即可
    value: value === null ? '' : String(value),
    type: metaTypeOf(value),
  }
}

/**
 * 行 → 提交值。
 *
 * <p>⚠️ 数字类型必须校验：`Number('abc')` 是 NaN，`JSON.stringify` 会把它写成 `null`
 * —— 用户会看到「保存成功、值却没了」。这里 fail loud。
 */
function parseMetaValue(row: MetaRow): unknown {
  if (row.type === 'null') return null
  if (row.type === 'boolean') return row.value === 'true'
  if (row.type === 'number') {
    const n = Number(row.value)
    if (!row.value.trim() || !Number.isFinite(n)) {
      throw new Error(`「${row.key}」需要数字，收到：${row.value || '(空)'}`)
    }
    return n
  }
  return row.value
}

/** 展示值（源侧 formatMetadataValue 同构）。 */
function formatMetaValue(v: unknown): string {
  if (v === null) return 'null'
  if (typeof v === 'boolean') return v ? 'true' : 'false'
  return String(v)
}

/**
 * 摘要失败原因 → 可操作的中文提示。
 *
 * <p>后端 `detail` 是管线内部码（`no_summary_model` / `insufficient_content` /
 * `no_text_chunks` / `provider_not_configured` / `summary_call_failed: …`），
 * 直接甩给用户等于没说。这里逐条翻译成「下一步该做什么」。
 *
 * <p>⚠️ 认不出的码**原样带出**，⛔ 不要吞成「未知错误」—— 那样下次排查又得去翻日志。
 */
function summaryFailureHint(detail: string | undefined): string {
  const d = (detail || '').trim()
  if (!d) return '摘要生成失败（后端未给出原因，请查看服务端日志）'
  if (d.includes('no_summary_model') || d.includes('provider_not_configured')) {
    return '该知识库还没配置摘要模型 —— 请到「知识库设置 → 模型配置」里选一个摘要模型后重试'
  }
  if (d.includes('insufficient_content')) {
    return '可抽取的文字太少（可能是扫描件 / 纯图片文档），无法生成摘要'
  }
  if (d.includes('no_text_chunks')) {
    return '这份文档还没有可用的文本分块 —— 请先确认解析完成'
  }
  if (d.includes('summary_call_failed')) {
    return `调用摘要模型失败：${d.replace('summary_call_failed:', '').trim()}`
  }
  if (d.includes('superseded')) {
    return '文档正在重新解析，本轮摘要被新一轮取代 —— 解析完成后再试'
  }
  return `摘要生成失败：${d}`
}

function formatSize(bytes: number | undefined | null): string {
  if (!bytes || bytes <= 0) return ''
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}
