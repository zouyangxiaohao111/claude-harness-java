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
import type { Renderer } from 'marked'

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
