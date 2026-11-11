/**
 * 源站 chatMarkdownRenderer 的后处理函数 · 逐行照搬。
 *
 * 来源：WeKnora（pinned 80e8345）frontend/src/utils/chatMarkdownRenderer.ts
 *
 * 这三个都在 marked.parse **之后**处理 HTML：
 * - wrapChatMarkdownTables          表格包一层 div（横向滚动容器）
 * - markStandaloneStrongParagraphs  只有加粗的段落标为「小标题」（加间距）
 * - applyStreamingTailFade          流式时最后一段文字落灰渐变
 */

const STANDALONE_STRONG_PARAGRAPH_RE =
  /<p>\s*<strong>((?:(?!<\/strong>)[\s\S])*?)<\/strong>\s*<\/p>/g

const TAIL_TEXT_RE = />([^<>]+)</g


export function wrapChatMarkdownTables(html: string): string {
  if (!html || !html.includes('<table')) return html
  return html.replace(
    /<table\b[\s\S]*?<\/table>/gi,
    (tableHtml) => `<div class="chat-markdown-table">${tableHtml}</div>`,
  )
}


/**
 * Tag paragraphs whose entire content is a single bold run (e.g. a model that
 * emits `**小节标题：**` instead of a real heading) so they can be styled as a
 * subtitle deterministically.
 *
 * This replaces the CSS `p:has(> strong:only-child)` heuristic, which was
 * streaming-unstable: CSS `:only-child` ignores text nodes, so a normal
 * paragraph that momentarily contained exactly one completed `**bold**` run
 * (surrounded by plenty of body text) matched it and gained a large top margin,
 * then lost it once a second bold run finished — a visible spacing jump
 * mid-stream. Matching the whole paragraph content here only fires when the
 * bold run truly is the entire paragraph.
 */
export function markStandaloneStrongParagraphs(html: string): string {
  if (!html || !html.includes('<strong>')) return html
  return html.replace(
    STANDALONE_STRONG_PARAGRAPH_RE,
    (match: string, inner: string, offset: number, full: string) => {
      // A `<p>` that is a list item's content is list text, not a standalone
      // subtitle. Marking it adds a large margin that shifts sibling items when
      // a streamed list flips from tight to loose, so leave list paragraphs to
      // the list's own (zero) margins.
      if (/<li>\s*$/.test(full.slice(0, offset))) return match
      return `<p class="md-strong-title"><strong>${inner}</strong></p>`
    },
  )
}


/**
 * Soften the most-recently streamed characters with a trailing fade.
 *
 * While the answer streams, the newest tail text is wrapped in a
 * `stream-fade-tail` span so CSS can fade its trailing edge (the newest words
 * are faint and settle to full color as more text arrives — the "tail reveal"
 * look). Runs on the final HTML so the span is never touched by the markdown
 * parser or sanitizer, and only the innermost last text run is wrapped.
 */
export function applyStreamingTailFade(html: string, tailLength = 24): string {
  if (!html) return html

  // Track the last text run that actually has visible characters. Whitespace-only
  // runs (e.g. the `\n` marked emits between `</li>` and `</ol>`) are skipped so
  // they do not win the "last run" and suppress the fade.
  let lastMatch: RegExpExecArray | null = null
  let match: RegExpExecArray | null
  TAIL_TEXT_RE.lastIndex = 0
  while ((match = TAIL_TEXT_RE.exec(html)) !== null) {
    if (match[1].trim()) lastMatch = match
  }
  if (!lastMatch) return html

  const text = lastMatch[1]

  const textStart = lastMatch.index + 1
  const textEnd = textStart + text.length
  const chars = Array.from(text)
  const tailChars = chars.slice(Math.max(0, chars.length - tailLength))
  const tail = tailChars.join('')
  // Fade only the trailing run, keeping any leading whitespace outside the span.
  const tailTrimmed = tail.replace(/^\s+/, '')
  if (!tailTrimmed) return html
  const head = text.slice(0, text.length - tailTrimmed.length)
  const wrapped = `${head}<span class="stream-fade-tail">${tailTrimmed}</span>`
  return html.slice(0, textStart) + wrapped + html.slice(textEnd)
}
