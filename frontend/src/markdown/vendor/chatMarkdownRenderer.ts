/**
 * 聊天 Markdown 渲染主入口 · 照搬源站 20 步流水线。
 *
 * 来源：WeKnora（pinned 80e8345）frontend/src/utils/chatMarkdownRenderer.ts 的
 * `renderChatMarkdown()`（约 100 行）。
 *
 * <h2>⭐⭐ 为什么照搬这条流水线</h2>
 * 本仓旧的 `MarkdownText.tsx` 在流式期走「纯文本直出」（`stream-raw`），
 * 理由是「增量解析与收口结果不一致」。源站的解法不是放弃解析，而是
 * <b>在解析前把未闭合语法逐个修好</b>：
 *
 * <pre>
 * 1. stripTrailingStreamingListMarker     末尾悬挂的 `- ` / `1. `
 * 2. stripTrailingStreamingHorizontalRule 末尾悬挂的 `---`
 * 3. normalizeLegacyImageContextMarkup    旧版 <image> 标签
 * 4. stripIncompleteCitationTag           `<kb` 未闭合 → 先藏起来
 * 5. preserveCitationTags                 把完整标签换成占位符
 * 6. normalizeFullwidthMarkdownImageParentheses 全角括号图片语法
 * 7. normalizeSandboxArtifactRefs         沙箱产物引用合并成单 token
 * 8. replaceIncompleteImageWithPlaceholder `![` 未闭合 → 骨架占位
 * 9. preprocessMathDelimiters             \[...\] → $$...$$
 * 10. restoreCitationTags                 占位符换回标签
 * 11. joinCitationTagsToPreviousLine      引用标签贴到上一行
 * 12. stripTrailingStreamingListMarker    再跑一次（强调符修复可能重新暴露）
 * 13. closeDanglingStreamingEmphasis      未闭合的 ** 补全
 * 14. prepareMarkdown                     调用方自定义（Mermaid 预处理钩子）
 * 15. repairFlankingEmphasis              CJK 相邻的强调符
 * 16. extractCitationHtmlPlaceholders     标签 → HTML 占位符（防净化器破坏属性）
 * 17. escapeMarkdown                      调用方提供的转义
 * 18. marked.parse(...)                   ← 真正解析
 * 19. restoreCitationHtmlPlaceholders + collapseStandaloneCitations
 * 20. renderEntityLinks → wrapTables → markStrong → sanitize → injectMermaid → tailFade
 * </pre>
 *
 * <h2>⚠️ 顺序不可改</h2>
 * 第 12 步注释写得很清楚：修完强调符可能**重新暴露**一个裸 `   - `，
 * 所以 list marker 的守卫要跑两次。调换顺序会让下一个列表项开始时闪一下空列表项。
 *
 * @see ./configure.ts         marked 全局配置
 * @see ./chatPostProcess.ts   第 20 步里的三个后处理
 */
import { marked } from 'marked'
import type { Renderer, Token, TokensList } from 'marked'

import {
  collapseStandaloneCitationParagraphs,
  extractCitationHtmlPlaceholders,
  joinCitationTagsToPreviousLine,
  preserveCitationTags,
  restoreCitationHtmlPlaceholders,
  restoreCitationTags,
  stripIncompleteCitationTag,
  type CitationKnowledgeRef,
} from './citationMarkdown'
import { renderEntityLinks, type EntityLinkLabels } from './entityLinks'
import { normalizeSandboxArtifactRefs } from './sandboxArtifactRefs'
import {
  applyStreamingTailFade,
  markStandaloneStrongParagraphs,
  wrapChatMarkdownTables,
} from './chatPostProcess'
import { configureMarkedForChatMarkdown } from './configure'
import {
  closeDanglingStreamingEmphasis,
  normalizeFullwidthMarkdownImageParentheses,
  normalizeLegacyImageContextMarkup,
  preprocessMathDelimiters,
  repairFlankingEmphasis,
  replaceIncompleteImageWithPlaceholder,
  stripTrailingStreamingHorizontalRule,
  stripTrailingStreamingListMarker,
} from './streamingGuards'

/** 渲染选项。 */
export interface RenderChatMarkdownOptions {
  /** marked 渲染器（代码块/图片等自定义渲染） */
  renderer: Renderer
  /** 转义函数（调用方决定，例如保留某些 HTML） */
  escapeMarkdown: (markdown: string) => string
  /** HTML 净化（⛔ 必传 —— 这是安全边界，不能省） */
  sanitizeHtml: (html: string) => string
  /** 源文本仍在增长，可能以不完整的 Markdown 结尾 */
  streaming?: boolean
  /** 是否折叠独占一段的引用标记（默认折叠） */
  collapseStandaloneCitations?: boolean
  /** 引用块引用（用于把 `<kb id>` 解析成可点链接） */
  knowledgeReferences?: CitationKnowledgeRef[] | null
  /** 实体链接标签（不传 ⇒ 不做实体链接渲染） */
  entityLinkLabels?: EntityLinkLabels
}

/** 预处理产物：marked 能稳定解析的文本 + 第 16 步抽出的引用 HTML 片段表。 */
export interface PreprocessedChatMarkdown {
  /** 第 1–17 步处理后的 markdown（即 `marked.parse` 的入参）。 */
  readonly markdown: string
  /** 引用 HTML 片段（占位符 → HTML）；块级渲染时每块都要用它还原。 */
  readonly htmlSnippets: readonly string[]
}

/**
 * 流式流水线第 1–17 步（**不含解析**）：把原始文本修成 marked 能稳定解析的形态。
 *
 * <p>抽出来是为了让块级冻结（{@link renderChatMarkdownIncremental}）与全量
 * （{@link renderChatMarkdown}）共用同一份预处理 —— 两边各写一份必然漂移。
 */
export function preprocessChatMarkdown(
  rawMarkdown: unknown,
  options: RenderChatMarkdownOptions,
): PreprocessedChatMarkdown {
  const rawText = typeof rawMarkdown === 'string' ? rawMarkdown : String(rawMarkdown || '')
  if (!rawText.trim()) return { markdown: '', htmlSnippets: [] }

  // ── 第 1–2 步：末尾悬挂的列表标记 / 分隔线 ──────────────────────────────
  const streamingSafeText = options.streaming
    ? stripTrailingStreamingListMarker(stripTrailingStreamingHorizontalRule(rawText))
    : rawText

  // ── 第 3 步：旧版图片标签 ───────────────────────────────────────────────
  const imageContextSafeText = normalizeLegacyImageContextMarkup(
    streamingSafeText,
    Boolean(options.streaming),
  )

  // ── 第 4 步：未闭合的引用标签先藏起来 ───────────────────────────────────
  const citationSafeText = stripIncompleteCitationTag(imageContextSafeText)

  // ── 第 5 步：完整标签换成占位符 ─────────────────────────────────────────
  const { text: tagSafe, tags } = preserveCitationTags(citationSafeText)

  // ── 第 6 步：全角括号图片语法 ───────────────────────────────────────────
  const normalizedImageMarkdown = normalizeFullwidthMarkdownImageParentheses(tagSafe)

  // ── 第 7 步：沙箱产物引用（文件名含空格/括号会被 marked 拆开）───────────
  const artifactSafe = normalizeSandboxArtifactRefs(normalizedImageMarkdown)

  // ── 第 8 步：未闭合的图片 → 骨架占位 ────────────────────────────────────
  const imageSafe = replaceIncompleteImageWithPlaceholder(artifactSafe)

  // ── 第 9 步：数学定界符 ─────────────────────────────────────────────────
  const mathSafe = preprocessMathDelimiters(imageSafe)

  // ── 第 10–11 步：标签换回并贴到上一行 ───────────────────────────────────
  const restoredTags = restoreCitationTags(mathSafe, tags)
  const inlineTags = joinCitationTagsToPreviousLine(restoredTags)

  // ── 第 12–13 步：再跑一次列表守卫 + 补齐未闭合强调 ──────────────────────
  const balancedInline = options.streaming
    ? stripTrailingStreamingListMarker(closeDanglingStreamingEmphasis(inlineTags))
    : inlineTags

  // ── 第 15 步：CJK 相邻的强调符 ──────────────────────────────────────────
  const flankingSafeMarkdown = repairFlankingEmphasis(balancedInline)

  // ── 第 16 步：标签 → HTML 占位符（防净化器破坏 chunk_id 属性）───────────
  const { content: markdownWithPlaceholders, htmlSnippets } =
    extractCitationHtmlPlaceholders(flankingSafeMarkdown, options.knowledgeReferences)

  // ── 第 17 步：转义 ──────────────────────────────────────────────────────
  const escapedMarkdown = options.escapeMarkdown(markdownWithPlaceholders)

  return { markdown: escapedMarkdown, htmlSnippets }
}

/**
 * 渲染聊天 Markdown。
 *
 * @param rawMarkdown 原始文本（任何类型，非字符串会被 String() 化）
 * @param options     渲染选项
 * @returns 可直接塞进 `dangerouslySetInnerHTML` 的 HTML（已净化）
 */
export function renderChatMarkdown(
  rawMarkdown: unknown,
  options: RenderChatMarkdownOptions,
): string {
  const rawText = typeof rawMarkdown === 'string' ? rawMarkdown : String(rawMarkdown || '')
  if (!rawText.trim()) return ''

  configureMarkedForChatMarkdown()

  const { markdown: escapedMarkdown, htmlSnippets } = preprocessChatMarkdown(rawText, options)

  // ── 第 18 步：真正解析 ──────────────────────────────────────────────────
  const html = marked.parse(escapedMarkdown, {
    renderer: options.renderer,
    breaks: true,
    async: false,
  }) as string

  // ── 第 19 步：占位符换回 HTML + 折叠独占段落的引用 ──────────────────────
  const restoredHtml = restoreCitationHtmlPlaceholders(html, htmlSnippets)
  const citationHtml = options.collapseStandaloneCitations === false
    ? restoredHtml
    : collapseStandaloneCitationParagraphs(restoredHtml)

  // ── 第 20 步：实体链接 → 表格包裹 → 加粗小标题 → 净化 → 尾部落灰 ────────
  const entityHtml = options.entityLinkLabels
    ? renderEntityLinks(citationHtml, options.entityLinkLabels)
    : citationHtml
  const tableWrappedHtml = wrapChatMarkdownTables(entityHtml)
  const strongTitleHtml = markStandaloneStrongParagraphs(tableWrappedHtml)
  const sanitized = options.sanitizeHtml(strongTitleHtml)
  return options.streaming ? applyStreamingTailFade(sanitized) : sanitized
}

/* ==========================================================================
 * 实验：流式期「块级冻结」增量渲染
 *
 * <p><b>WHY</b>：全量路径每帧 `marked.parse(全文)` + DOMPurify + innerHTML 整树重建，
 * 成本随回复长度线性增长（实测正文 3.6K 字时峰值 6.6ms/帧）。
 *
 * <p><b>做法</b>：预处理仍是全文（17 步是正则，比 marked 便宜且是块边界的前提），
 * 解析按 marked 顶层 token 切块 —— 前部块只要 `token.raw` 与上帧一致就复用其成品
 * HTML，只重解析尾部；组件层再按块渲染，前部块的 DOM 不再被重写。
 *
 * <p><b>已知偏差（与 dsh 的 prefix-freeze 同源）</b>：
 * <ul>
 *   <li>独占段落的引用折叠（{@link collapseStandaloneCitationParagraphs}）在拼接后
 *       对全文再跑一次，保住跨块语义；没有 citation 时零成本短路。</li>
 *   <li>引用定义（`[x][ref]` 的 `def` token）通过共享 `tokens.links` 传给每个单块
 *       parser，因此跨块引用仍能解析。</li>
 * </ul>
 * ========================================================================== */

/** 一个已定稿（或本帧刚定稿）的顶层块。 */
export interface FrozenMarkdownBlock {
  /** 预处理后文本中的起始偏移 —— 流式追加时前部块恒稳定，用作帧间对齐。 */
  readonly start: number
  /** token 原文：与上帧相同 ⇒ 该块的解析结果不可能变，可复用 HTML。 */
  readonly raw: string
  /** 成品 HTML（已还原引用片段、已净化、已做块局部后处理）。 */
  readonly html: string
}

/** 帧间缓存。调用方按「会话:消息」持有；文本非追加时自动整体重建。 */
export interface BlockFreezeCache {
  readonly blocks: readonly FrozenMarkdownBlock[]
  /**
   * 上一帧 lexer 收集到的引用定义表指纹。
   *
   * <p><b>WHY 必须参与复用判据</b>：`[x][ref]` 的解析依赖**全文**定义表，而定义可以
   * 出现在正文之后（流式期后到）。后到时前面块的 `token.raw` 没变，但它的链接解析结果
   * 变了 —— 只看 raw 会复用上一帧的字面量 HTML（测试实测踩到）。
   * 定义表变化 ⇒ 本帧不复用（全量重解析一次），下一次追加恢复复用。
   */
  readonly linksKey: string
}

/** 增量渲染结果。`cache` 传给下一帧；`reusedBlocks` 仅用于观测/测试。 */
export interface IncrementalChatMarkdown {
  readonly html: string
  readonly cache: BlockFreezeCache
  readonly reusedBlocks: number
}

/** 块内局部后处理（与全量第 19–20 步同序，⛔ 不含跨块的引用段落折叠）。 */
function finalizeBlockHtml(
  rawHtml: string,
  htmlSnippets: readonly string[],
  options: RenderChatMarkdownOptions,
): string {
  let out = restoreCitationHtmlPlaceholders(rawHtml, htmlSnippets)
  if (options.entityLinkLabels) out = renderEntityLinks(out, options.entityLinkLabels)
  out = wrapChatMarkdownTables(out)
  out = markStandaloneStrongParagraphs(out)
  return options.sanitizeHtml(out)
}

/**
 * 块级冻结渲染（实验，与 {@link renderChatMarkdown} 输出应逐字节一致）。
 *
 * @param rawMarkdown 原始文本（流式期每帧的完整累积值）
 * @param options     与全量路径同一份选项
 * @param cache       上一帧返回的缓存（首帧传 null）
 */
export function renderChatMarkdownIncremental(
  rawMarkdown: unknown,
  options: RenderChatMarkdownOptions,
  cache: BlockFreezeCache | null,
): IncrementalChatMarkdown {
  const rawText = typeof rawMarkdown === 'string' ? rawMarkdown : String(rawMarkdown || '')
  if (!rawText.trim()) return { html: '', cache: { blocks: [], linksKey: '' }, reusedBlocks: 0 }

  configureMarkedForChatMarkdown()

  const { markdown, htmlSnippets } = preprocessChatMarkdown(rawText, options)
  const tokens = marked.lexer(markdown)
  // 最后一块永远处于「可能被追加改写」的状态 ⇒ 不复用（其余靠 raw 相等判定）。
  const reusableLimit = tokens.length - 1
  const parseOptions = { renderer: options.renderer, breaks: true }
  // 引用定义表变化 ⇒ 前部块的链接解析结果可能变 ⇒ 本帧全部重解析。
  const linksKey = JSON.stringify(tokens.links ?? {})
  const cacheUsable = cache !== null && cache.linksKey === linksKey

  const blocks: FrozenMarkdownBlock[] = []
  let offset = 0
  let reusedBlocks = 0

  for (let i = 0; i < tokens.length; i++) {
    const token = tokens[i] as Token
    const start = offset
    offset += token.raw.length

    const previous = cacheUsable ? cache?.blocks[i] : undefined
    if (
      i < reusableLimit
      && previous !== undefined
      && previous.start === start
      && previous.raw === token.raw
    ) {
      blocks.push(previous)
      reusedBlocks++
      continue
    }

    // 单块解析：把全文的引用定义表挂到单元素列表上，跨块 `[x][ref]` 才不会退化成字面量。
    const single = [token] as unknown as TokensList
    single.links = tokens.links
    const blockHtml = marked.parser(single, parseOptions)
    blocks.push({ start, raw: token.raw, html: finalizeBlockHtml(blockHtml, htmlSnippets, options) })
  }

  let html = blocks.map(block => block.html).join('')
  // 跨块语义：独占段落的引用折叠必须在拼接后跑（全量路径也是全文级）。
  if (options.collapseStandaloneCitations !== false) {
    html = collapseStandaloneCitationParagraphs(html)
  }
  if (options.streaming) html = applyStreamingTailFade(html)
  return { html, cache: { blocks, linksKey }, reusedBlocks }
}
