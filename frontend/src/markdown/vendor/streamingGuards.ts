/**
 * 流式守卫 · 让「未闭合的 Markdown」在流式期间也能正确渲染。
 *
 * 来源：WeKnora（pinned 80e8345）frontend/src/utils/chatMarkdownRenderer.ts，逐行照搬。
 *
 * 为什么需要这一层：流式输出时文本一点点长出来，任意时刻都可能停在语法中间
 * （只有开头的 `**`、只到一半的 `![图片](`、末尾孤零零的 `- `）。
 * 直接交给 marked 会渲染出裸露符号或错位块。
 * 源站的解法是在解析**之前**逐个修好，而不是放弃解析。
 *
 * ⚠️ 调用顺序不可改 —— 见 chatMarkdownRenderer.ts 的流水线注释。
 */

// ── 常量（按依赖顺序排列）────────────────────────────────────────────────

const COMPLETE_MARKDOWN_CODE_RE = /(```[\s\S]*?```|~~~[\s\S]*?~~~|`[^`\n]*`)/g

const FLANKING_BOLD = /(?<!\*)\*\*(?=\S)([^*\n]*?\p{P})\*\*(?=[\p{L}\p{N}])/gu

const FLANKING_BOLD_OPEN = /(?<=[\p{L}\p{N}])\*\*(?=\p{P})([^*\n]+?)\*\*/gu

const FLANKING_ITALIC = /(?<![*\p{L}\p{N}])\*(?=\S)([^*\n]*?\p{P})\*(?=[\p{L}\p{N}])/gu

const FLANKING_STRIKE = /(?<!~)~~(?=\S)([^~\n]*?\p{P})~~(?=[\p{L}\p{N}])/gu

const FLANKING_STRIKE_OPEN = /(?<=[\p{L}\p{N}])~~(?=\p{P})([^~\n]+?)~~/gu

const IMAGE_URL_SCHEME = '(?:https?|resource|storage|local|minio|s3|cos|tos|oss|obs|ks3)'

const FULLWIDTH_IMAGE_CLOSE_RE = new RegExp(
  `(!\\[[^\\]\\n]*\\]\\(${IMAGE_URL_SCHEME}://[^）\\n]*?)）`,
  'gi',
)

const FULLWIDTH_IMAGE_OPEN_RE = new RegExp(`(!\\[[^\\]\\n]*\\])（(?=${IMAGE_URL_SCHEME}://)`, 'gi')

const LEGACY_IMAGES_WRAPPER_RE = /<\/?images\b[^>]*>/gi

const LEGACY_IMAGE_BLOCK_RE = /<image\b([^>]*)>([\s\S]*?)<\/images?>/gi

const LEGACY_IMAGE_CAPTION_RE = /<image_caption>([\s\S]*?)<\/image_caption>/i

const LEGACY_IMAGE_OCR_RE = /<image_ocr>([\s\S]*?)<\/image_ocr>/i

const LEGACY_IMAGE_ORIGINAL_RE = /<image_original>([\s\S]*?)<\/image_original>/i

const STREAMING_IMAGE_PLACEHOLDER =
  '<span class="streaming-image-loading"><span class="streaming-image-loading__skeleton"></span></span>'


// ── 守卫函数 ───────────────────────────────────────────────────────────


export function preprocessMathDelimiters(rawText: string): string {
  if (!rawText || typeof rawText !== 'string') return ''
  return rawText
    .replace(/\\\[([\s\S]*?)\\\]/g, '$$$$$1$$$$')
    .replace(/\\\(([\s\S]*?)\\\)/g, '$$$1$$')
}


export function replaceIncompleteImageWithPlaceholder(content: string): string {
  if (!content) return ''

  const lastImgStart = content.lastIndexOf('![')
  if (lastImgStart < 0) return content

  const tail = content.slice(lastImgStart)
  const hasImageOpen = tail.startsWith('![')
  const hasBracketClose = tail.includes(']')
  const hasParenOpen = tail.includes('(')
  const hasParenClose = tail.includes(')')
  if (!hasImageOpen) return content

  if (!hasBracketClose || (hasParenOpen && !hasParenClose)) {
    return content.slice(0, lastImgStart) + STREAMING_IMAGE_PLACEHOLDER
  }

  return content
}


/**
 * Repair image Markdown when a model localizes its destination parentheses:
 * `![alt]（resource://…）` -> `![alt](resource://…)`.
 *
 * The rewrite is limited to supported image URL schemes and skips code, so
 * ordinary Chinese punctuation and literal Markdown examples stay unchanged.
 * Normalizing an opening parenthesis before the closing one streams in also
 * lets the existing incomplete-image skeleton suppress the unstable tail.
 */
export function normalizeFullwidthMarkdownImageParentheses(content: string): string {
  if (!content || (!content.includes('（') && !content.includes('）'))) return content

  const parts = content.split(COMPLETE_MARKDOWN_CODE_RE)
  for (let i = 0; i < parts.length; i += 2) {
    parts[i] = parts[i]
      .replace(FULLWIDTH_IMAGE_OPEN_RE, '$1(')
      .replace(FULLWIDTH_IMAGE_CLOSE_RE, '$1)')
  }
  return parts.join('')
}


/**
 * Normalize the legacy image-context protocol if a model copies it into an
 * answer. New chat context is Markdown-native, but persisted conversations and
 * older deployments can still contain `<image url="…">` blocks.
 *
 * Complete blocks become ordinary Markdown images and therefore pass through
 * the same URL validation/sanitization as model-authored images. While a block
 * is still streaming, its unfinished tail is replaced by the existing image
 * skeleton so internal tags, captions, and OCR never flash as prose.
 */
export function normalizeLegacyImageContextMarkup(content: string, streaming = false): string {
  if (!content) return content
  const hasLegacyImageTag = /<\/?images?\b/i.test(content)
  const partialTagPattern = /(^|\n)[ \t]*<i(?:m(?:a(?:g(?:e(?:[ \t][^>\n]*)?)?)?)?)?$/i
  const hasPartialStreamingTag = streaming && partialTagPattern.test(content)
  if (!hasLegacyImageTag && !hasPartialStreamingTag) return content

  const parts = content.split(COMPLETE_MARKDOWN_CODE_RE)
  for (let i = 0; i < parts.length; i += 2) {
    parts[i] = normalizeLegacyImageSegment(parts[i])
  }
  if (streaming) {
    // The split always leaves normal Markdown in the final even-indexed part,
    // so code examples containing literal <image> tags stay untouched.
    const tailIndex = parts.length - 1
    let tail = parts[tailIndex]
    const openingMatches = Array.from(tail.matchAll(/<image(?:\s|>)/gi))
    const lastOpening = openingMatches[openingMatches.length - 1]
    if (lastOpening?.index !== undefined) {
      tail = tail.slice(0, lastOpening.index) + STREAMING_IMAGE_PLACEHOLDER
    } else {
      // Also hide the few frames before the opening tag name itself is complete.
      tail = tail.replace(partialTagPattern, `$1${STREAMING_IMAGE_PLACEHOLDER}`)
    }
    parts[tailIndex] = tail
  }
  return parts.join('')
}


/**
 * Hide a trailing Markdown horizontal-rule candidate while content is streaming.
 *
 * A model often emits `---` as the beginning of a table delimiter or another
 * structure. At that exact typewriter frame, marked renders it as `<hr>`, then
 * removes it when more characters arrive. A real completed horizontal rule is
 * still rendered because this guard is enabled only for an active stream.
 */
export function stripTrailingStreamingHorizontalRule(content: string): string {
  if (!content) return content
  return content.replace(
    /(^|\n)[ \t]{0,3}(?:(?:-[ \t]*){3,}|(?:\*[ \t]*){3,}|(?:_[ \t]*){3,})$/,
    '$1',
  )
}


/**
 * Stabilize dangling inline markers while content is streaming.
 *
 * Two distinct mid-stream artifacts are smoothed out:
 *
 *  - A marker run with **nothing after it yet** (`… 实验**\n5. *`, or a bare
 *    trailing `**`) is ambiguous — it might open bold, italic, or be the start
 *    of the next `**`. Rendering it shows literal `*`/`~` that vanish a frame
 *    later, so it is hidden until real content follows (like the citation/image
 *    guards).
 *  - A marker run **with content after it** but no closer (`**平台地址：…`) is a
 *    genuinely opened emphasis; its closer is appended so marked renders it as
 *    emphasis from the first frame instead of showing raw `**` and then snapping
 *    to bold (and, for a standalone bold line, also gaining the subtitle
 *    margin) — the jump reported on streamed bold lists/headings.
 *
 * Inline/fenced code and leading list bullets are masked so their literal
 * `*`/`` ` `` are never treated as emphasis, and an open fenced block is left
 * untouched.
 */
export function closeDanglingStreamingEmphasis(text: string): string {
  if (!text || !/[*~`]/.test(text)) return text

  // Mask code so markers inside it are never counted; \u0000 keeps offsets.
  let masked = maskMatches(text, /```[\s\S]*?```/g)
  if (isOdd(masked, /```/g)) {
    const open = masked.lastIndexOf('```')
    masked = masked.slice(0, open) + masked.slice(open).replace(/[^\n]/g, '\u0000')
  }
  masked = maskMatches(masked, /`[^`\n]*`/g)
  let appendInlineCode = false
  if (isOdd(masked, /`/g)) {
    appendInlineCode = true
    const open = masked.lastIndexOf('`')
    masked = masked.slice(0, open) + masked.slice(open).replace(/[^\n]/g, '\u0000')
  }

  // Drop a trailing emphasis-marker run with no content after it: ambiguous and
  // not yet renderable. Anchored to the current line so earlier text is intact.
  let working = text
  if (!appendInlineCode) {
    const trailing = masked.match(/[*~]+[ \t]*$/)
    if (trailing) {
      working = text.slice(0, text.length - trailing[0].length)
      masked = masked.slice(0, masked.length - trailing[0].length)
    }
  }

  // Leading list/bullet markers are structure, not emphasis.
  masked = masked.replace(/^(\s*)([*+-])(\s)/gm, (_m, p1, _p2, p3) => `${p1}\u0000${p3}`)

  const needStrike = isOdd(masked, /~~/g)
  const withoutBold = maskMatches(masked, /\*\*/g)
  const needBold = isOdd(masked, /\*\*/g)
  const needItalic = isOdd(withoutBold, /\*/g)

  let closers = ''
  if (appendInlineCode) closers += '`'
  if (needItalic) closers += '*'
  if (needBold) closers += '**'
  if (needStrike) closers += '~~'

  if (!closers) return working

  // A closing emphasis delimiter must not be preceded by whitespace (CommonMark
  // flanking rule), so insert the closers before any trailing whitespace. Bold
  // content with internal spaces (`**XBRL Reporting `) otherwise renders literal
  // (non-bold) on every frame that pauses on a space, flickering bold on/off.
  const trailingWs = working.match(/\s+$/)
  if (!trailingWs) return working + closers
  const cut = working.length - trailingWs[0].length
  return working.slice(0, cut) + closers + working.slice(cut)
}


/**
 * Render emphasis the model clearly intended but CommonMark refuses to parse.
 *
 * CommonMark's flanking rule rejects a closing `**`/`*`/`~~` that is preceded by
 * punctuation and immediately followed by a letter/number, so a very common
 * model pattern like `**XBRL（…语言）**是一种` (and even ASCII `**a)**b`) renders
 * as literal asterisks — both mid-stream and when complete. The mirror case is
 * an *opening* `**`/`~~` preceded by a letter/number and immediately followed by
 * punctuation (`知识库中**《手册》**整理`), which CommonMark also refuses to open.
 * We convert just those blocked patterns into explicit HTML so they bold
 * reliably. Code spans/fences are skipped so their literal markers are untouched.
 */
export function repairFlankingEmphasis(text: string): string {
  if (!text || !/[*~]/.test(text)) return text
  const parts = text.split(/(```[\s\S]*?```|`[^`\n]*`)/g)
  for (let i = 0; i < parts.length; i += 2) {
    parts[i] = repairFlankingEmphasisSegment(parts[i])
  }
  return parts.join('')
}


/**
 * Hide a trailing list/underline marker that has no content after it yet.
 *
 * While streaming, the moment a nested bullet's dash arrives before its text
 * (`1. **AAAAA**\n   - `), marked reads the lone `-` as a setext underline and
 * renders the previous line as a giant `<h2>`, which collapses back to bold text
 * once the bullet content streams in — a large layout swing. An ordered marker
 * with no content (`text\n1. `) and bare setext underlines (`==`/`--`) cause the
 * same fl/flicker. Dropping the dangling marker line defers it by a token until
 * it is unambiguous; a real list/heading still renders once content follows.
 *
 * A trailing line that is only a number (`…\n1`) is the start of an ordered
 * marker before its `.`/`)` arrives; rendering it shows a bare unstyled "1" that
 * disappears a frame later, so it is hidden too. Because the line must be only
 * the number, in-sentence numbers (`值是 1`) are unaffected.
 */
export function stripTrailingStreamingListMarker(text: string): string {
  if (!text) return text
  return text.replace(/(^|\n)[ \t]*(?:[-*+]|[-=]{2,}|\d{1,9}[.)]?)[ \t]*$/, '$1')
}


function normalizeLegacyImageSegment(content: string): string {
  return content
    .replace(
      LEGACY_IMAGE_BLOCK_RE,
      (_match, attributes: string, body: string) => legacyImageBlockToMarkdown(attributes, body),
    )
    .replace(LEGACY_IMAGES_WRAPPER_RE, '')
}


function maskMatches(text: string, regex: RegExp): string {
  return text.replace(regex, (match) => match.replace(/[^\n]/g, '\u0000'))
}


function isOdd(text: string, marker: RegExp): boolean {
  const matches = text.match(marker)
  return matches ? matches.length % 2 === 1 : false
}


function repairFlankingEmphasisSegment(segment: string): string {
  return segment
    .replace(FLANKING_BOLD, '<strong>$1</strong>')
    .replace(FLANKING_BOLD_OPEN, '<strong>$1</strong>')
    .replace(FLANKING_STRIKE, '<del>$1</del>')
    .replace(FLANKING_STRIKE_OPEN, '<del>$1</del>')
    .replace(FLANKING_ITALIC, '<em>$1</em>')
}


function legacyImageBlockToMarkdown(attributes: string, body: string): string {
  const original = body.match(LEGACY_IMAGE_ORIGINAL_RE)?.[1]?.trim()
  if (original) return original

  const urlMatch = attributes.match(/\burl\s*=\s*(["'])(.*?)\1/i)
  const url = urlMatch?.[2]?.trim() || ''
  const caption = legacyImageField(body, LEGACY_IMAGE_CAPTION_RE)
  const ocr = legacyImageField(body, LEGACY_IMAGE_OCR_RE)
  if (!url) return caption || ocr

  const alt = escapeMarkdownImageAlt(caption || ocr || 'image')
  return `![${alt}](${url})`
}


function legacyImageField(body: string, pattern: RegExp): string {
  const value = body.match(pattern)?.[1] || ''
  return value.replace(/<[^>]*>/g, '').replace(/\s+/g, ' ').trim()
}


function escapeMarkdownImageAlt(value: string): string {
  return value.replace(/\\/g, '\\\\').replace(/\[/g, '\\[').replace(/\]/g, '\\]')
}
