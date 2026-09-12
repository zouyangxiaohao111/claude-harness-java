/**
 * 对话/轨迹的 markdown→纯文本投影（移植自 deepseek-harness ui-primitives/markdown/plain-text.ts）。
 *
 * Markdown-to-plain-text projection for compact summaries and labels.
 * Parsing shares the renderer's streaming GFM grammar ({@link parseGfm}), so
 * the projection strips exactly the markup the renderer would draw; raw HTML
 * stays literal, links keep their labels, images keep alt text, and code
 * keeps its source text.
 * ── nexusai 侧已知取舍（2026-09-12 记录，不改上游实现）──────────────────
 * 1. 逐行 trim：代码块与 YAML 的缩进会被抹平（'    if x:' → 'if x:'）。上游唯一消费者
 *    是单行标签；本仓把它用于 <pre> 多行预览属新场景，已拍板接受。
 * 2. 语法：本函数用 parseGfm（流式臂：无 math 扩展、不跑 rescue），故
 *    - 行内 $$x$$ 保持字面；**display 形式 $$\nx\n$$ 投影为 "$$ x $$"（第三种形态）**；
 *    - 粘连标记（`##标题`，即 rescue 会补空格的那类）**也保持字面** —— 本函数只投影，
 *      不做抢救。故「预览」与「展开态渲染」对这类输入可能不同（预览少剥、不藏内容）。
 * 3. 非字符串入参：undefined 静默返回 ''；null/数字/对象会抛 micromark 内部的
 *    TypeError（错误信息不提入参类型）。调用方须自行保证 string。
 * ───────────────────────────────────────────────────────────────────
 */

import { parseGfm } from './parse.ts'

/** Amount of parsed Markdown content returned by the extractor. */
export type MarkdownPlainTextMode = 'all' | 'first-line' | 'first-paragraph'

/** Options for {@link extractMarkdownPlainText}. */
export interface MarkdownPlainTextOptions {
  /** Projection boundary; defaults to the complete document. */
  mode?: MarkdownPlainTextMode
}

interface MarkdownNode {
  type: string
  value?: string
  alt?: string
  children?: MarkdownNode[]
}

function inlineText(node: MarkdownNode): string {
  switch (node.type) {
    case 'text':
    case 'inlineCode':
    case 'code':
      return node.value ?? ''
    case 'image':
    case 'imageReference':
      return node.alt ?? ''
    case 'break':
      return '\n'
    case 'html':
      return node.value ?? ''
    default:
      return node.children?.map(inlineText).join('') ?? ''
  }
}

function compactInline(text: string): string {
  return text.replace(/\s+/g, ' ').trim()
}

function blockText(node: MarkdownNode): string {
  switch (node.type) {
    case 'root':
    case 'blockquote':
      return node.children?.map(blockText).filter(Boolean).join('\n\n') ?? ''
    case 'paragraph':
    case 'heading':
      return compactInline(inlineText(node))
    case 'code':
      return node.value?.trim() ?? ''
    case 'list':
      return node.children?.map(blockText).filter(Boolean).join('\n') ?? ''
    case 'listItem':
      return node.children?.map(blockText).filter(Boolean).join(' ') ?? ''
    case 'table':
      return node.children?.map(blockText).filter(Boolean).join('\n') ?? ''
    case 'tableRow':
      return node.children?.map(blockText).join('\t') ?? ''
    case 'tableCell':
      return compactInline(inlineText(node))
    case 'html':
      return node.value ?? ''
    case 'thematicBreak':
    case 'definition':
      return ''
    default:
      return compactInline(inlineText(node))
  }
}

function findFirstParagraph(node: MarkdownNode): string | undefined {
  if (node.type === 'paragraph') {
    const text = compactInline(inlineText(node))
    if (text !== '') return text
  }
  for (const child of node.children ?? []) {
    const text = findFirstParagraph(child)
    if (text !== undefined) return text
  }
  return undefined
}

function fullText(root: MarkdownNode): string {
  return blockText(root)
    .split('\n')
    .map(line => line.trim())
    .join('\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}

/**
 * Parse GFM Markdown, remove its presentation markup, and preserve raw HTML literally.
 * @param markdown - Markdown source.
 * @param options - Optional extraction boundary.
 * @returns Plain text for the whole document, first visible line, or first semantic paragraph.
 */
export function extractMarkdownPlainText(
  markdown: string,
  options: MarkdownPlainTextOptions = {},
): string {
  const { mode = 'all' } = options
  const root = parseGfm(markdown) as MarkdownNode
  const all = fullText(root)
  switch (mode) {
    case 'all':
      return all
    case 'first-line':
      return all.split('\n').find(line => line !== '') ?? ''
    case 'first-paragraph':
      return findFirstParagraph(root) ?? all.split('\n').find(line => line !== '') ?? ''
  }
}
