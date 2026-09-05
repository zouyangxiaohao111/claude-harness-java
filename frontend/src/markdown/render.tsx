/**
 * mdast→React 直渲（对齐 deepseek-harness render.tsx，去掉 CSS Module / 文件 mention /
 * inline-code URL 提升；新增 breaks 软换行）。
 *
 * 安全策略（替代旧 DOMPurify 的职责）：
 * - raw HTML 一律按字面量文本输出，不建 DOM；
 * - 链接/图片目标过协议白名单（http/https/mailto，经 normalizeUri）；
 * - 图片要求绝对 http(s)，否则渲染 alt 文本；
 * - 外链一律 target="_blank" rel="noopener noreferrer"。
 *
 * 渲染 DOM 不做「整段 innerHTML」——全部是 React 元素，流式冻结块可缓存复用。
 */
import { Fragment, createElement } from 'react'
import type { Key, ReactNode } from 'react'
import type * as Md from 'mdast'
import type {} from 'mdast-util-math'
import { normalizeUri } from 'micromark-util-sanitize-uri'
import { CodeBlock } from './CodeBlock.tsx'
import { renderTexToReact } from './katex.tsx'
import type { PositionedBlock } from './incremental.ts'

/** 渲染用普通 class（无 CSS Modules，host 样式在 globals.css 里按同名类实现）。 */
const css = {
  markdown: '',
  tableScroll: 'md-table-scroll',
  imageAlt: 'md-image-alt',
  image: 'md-image',
}

function sanitizeUrl(url: string): string {
  try {
    switch (new URL(url).protocol) {
      case 'http:':
      case 'https:':
      case 'mailto:':
        return url
      default:
        return ''
    }
  } catch {
    // 相对路径 / 其它不可解析目标一律不放行。
    return ''
  }
}

function remoteImageUrl(url: string): string | undefined {
  try {
    const protocol = new URL(url).protocol
    return protocol === 'http:' || protocol === 'https:' ? url : undefined
  } catch {
    return undefined
  }
}

/** 链接/图片引用目标（文档级收集，首定义优先）。 */
export interface ReferenceTargets {
  definitions: Map<string, Md.Definition>
  footnotes: Map<string, Md.FootnoteDefinition>
}

export function createReferenceTargets(): ReferenceTargets {
  return { definitions: new Map(), footnotes: new Map() }
}

/** 深搜收集 definition / footnoteDefinition 进 targets（跨增量段共享）。 */
export function collectReferenceTargets(
  nodes: readonly Md.RootContent[],
  targets: ReferenceTargets,
): void {
  for (const node of nodes) {
    if (node.type === 'definition') {
      const id = node.identifier.toUpperCase()
      if (!targets.definitions.has(id)) targets.definitions.set(id, node)
    } else if (node.type === 'footnoteDefinition') {
      const id = node.identifier.toUpperCase()
      if (!targets.footnotes.has(id)) targets.footnotes.set(id, node)
    }
    if ('children' in node) collectReferenceTargets(node.children, targets)
  }
}

export interface MarkdownRenderContext {
  /** 流式臂：代码块不高亮（也不渲染 ```math 为 TeX）。 */
  readonly streaming: boolean
  /** 软换行：段落内单个 \n 渲成 <br>（对齐旧 marked breaks:true；默认 true）。 */
  readonly breaks?: boolean
  /** html 代码块「运行」回调（lang=html 时 CodeBlock 渲染运行按钮）。 */
  readonly onRunHtml?: ((code: string) => void) | undefined
  /** 锚点内：交互元素不得嵌套。 */
  readonly inLink?: boolean
  readonly targets: ReferenceTargets
  readonly footnoteOrder: string[]
  readonly footnoteCounts: Map<string, number>
}

export function renderBlocks(
  blocks: readonly PositionedBlock[],
  context: MarkdownRenderContext,
): ReactNode[] {
  return blocks
    .map(block => renderNode(block.node, block.key, context))
    .filter(element => element !== null)
}

/** 块间插入 '\n' 文本节点（与 settled/streaming 两臂一致）。 */
export function wrapBlockChildren(elements: readonly ReactNode[], edges: boolean): ReactNode[] {
  const wrapped: ReactNode[] = []
  for (const element of elements) {
    if (edges || wrapped.length > 0) wrapped.push('\n')
    wrapped.push(element)
  }
  if (edges && elements.length > 0) wrapped.push('\n')
  return wrapped
}

type BlockEntry = { paragraph: ReactNode[] } | { element: ReactNode }

function renderBlockEntries(
  blocks: readonly Md.RootContent[],
  context: MarkdownRenderContext,
): BlockEntry[] {
  const entries: BlockEntry[] = []
  for (const [index, block] of blocks.entries()) {
    if (block.type === 'paragraph') {
      entries.push({ paragraph: renderChildren(block.children, context) })
    } else {
      const element = renderNode(block, index, context)
      if (element !== null) entries.push({ element })
    }
  }
  return entries
}

function renderChildren(
  nodes: readonly Md.RootContent[],
  context: MarkdownRenderContext,
): ReactNode[] {
  return nodes.map((node, index) => renderNode(node, index, context))
}

/** 软换行：把文本节点里的单个 \n 拆成 <br>（markdown 段落软换行；code/pre 不经过此分支）。 */
function renderTextValue(value: string, key: Key, context: MarkdownRenderContext): ReactNode {
  if (context.breaks !== false && value.indexOf('\n') !== -1) {
    const segments = value.split('\n')
    const children: ReactNode[] = []
    for (let i = 0; i < segments.length; i++) {
      if (i > 0) children.push(<br key={`br-${i}`} />)
      children.push(segments[i])
    }
    return <Fragment key={key}>{children}</Fragment>
  }
  return value
}

function renderNode(node: Md.RootContent, key: Key, context: MarkdownRenderContext): ReactNode {
  switch (node.type) {
    case 'text':
      return renderTextValue(node.value, key, context)
    case 'paragraph':
      return <p key={key}>{renderChildren(node.children, context)}</p>
    case 'heading':
      return createElement(`h${node.depth}`, { key }, ...renderChildren(node.children, context))
    case 'blockquote':
      return (
        <blockquote key={key}>
          {wrapBlockChildren(renderChildren(node.children, context).filter(child => child !== null), true)}
        </blockquote>
      )
    case 'thematicBreak':
      return <hr key={key} />
    case 'break':
      // 硬换行（行尾两空格 / 反斜杠）→ <br>。
      return <Fragment key={key}><br />{'\n'}</Fragment>
    case 'strong':
      return <strong key={key}>{renderChildren(node.children, context)}</strong>
    case 'emphasis':
      return <em key={key}>{renderChildren(node.children, context)}</em>
    case 'delete':
      return <del key={key}>{renderChildren(node.children, context)}</del>
    case 'inlineCode':
      // 行内代码内换行按空格折叠（对齐 mdast-util-to-hast）。
      return <code key={key}>{node.value.replace(/\r?\n|\r/g, ' ')}</code>
    case 'html':
      // 原始 HTML 一律字面量文本，绝不进入 DOM。
      return node.value
    case 'code':
      return renderCode(node, key, context)
    case 'math':
      return <Fragment key={key}>{renderTexToReact(node.value, true)}</Fragment>
    case 'inlineMath':
      return <Fragment key={key}>{renderTexToReact(node.value, false)}</Fragment>
    case 'list':
      return renderList(node, key, context)
    case 'listItem':
      return renderListItem(node, listItemLoose(node), key, context)
    case 'table':
      return renderTable(node, key, context)
    case 'link':
      return renderAnchor(node.url, renderChildren(node.children, { ...context, inLink: true }), key)
    case 'linkReference':
      return renderLinkReference(node, key, context)
    case 'image':
      return renderImage(node.url, node.alt ?? '', key)
    case 'imageReference':
      return renderImageReference(node, key, context)
    case 'footnoteReference':
      return renderFootnoteReference(node, key, context)
    case 'definition':
    case 'footnoteDefinition':
      return null
    default:
      return null
  }
}

function renderCode(node: Md.Code, key: Key, context: MarkdownRenderContext): ReactNode {
  const language = node.lang ?? undefined
  const lang = language === undefined ? undefined : /^[\w-]+/.exec(language)?.[0]
  if (!context.streaming && lang === 'math') {
    // ```math 围栏 settled 后渲成 display TeX（收尾补一个换行对齐文本提取）。
    return <Fragment key={key}>{renderTexToReact(`${node.value}\n`, true)}</Fragment>
  }
  return (
    <CodeBlock
      key={key}
      code={`${node.value}\n`}
      lang={lang}
      streaming={context.streaming}
      onRunHtml={context.onRunHtml}
    />
  )
}

function listLoose(list: Md.List): boolean {
  return (list.spread ?? false) || list.children.some(listItemLoose)
}

function listItemLoose(item: Md.ListItem): boolean {
  return item.spread ?? item.children.length > 1
}

function renderList(node: Md.List, key: Key, context: MarkdownRenderContext): ReactNode {
  const loose = listLoose(node)
  const properties: { start?: number; className?: string } = {}
  if (typeof node.start === 'number' && node.start !== 1) properties.start = node.start
  if (node.children.some(item => typeof item.checked === 'boolean')) {
    properties.className = 'contains-task-list'
  }
  return createElement(
    node.ordered === true ? 'ol' : 'ul',
    { key, ...properties },
    ...node.children.map((item, index) => renderListItem(item, loose, index, context)),
  )
}

function renderListItem(
  item: Md.ListItem,
  loose: boolean,
  key: Key,
  context: MarkdownRenderContext,
): ReactNode {
  const entries = renderBlockEntries(item.children, context)
  const task = typeof item.checked === 'boolean'
  if (task) {
    const checkbox = <input key="task-checkbox" type="checkbox" checked={item.checked === true} disabled />
    const head = entries[0]
    if (head !== undefined && 'paragraph' in head) {
      head.paragraph = head.paragraph.length > 0 ? [checkbox, ' ', ...head.paragraph] : [checkbox]
    } else {
      entries.unshift({ paragraph: [checkbox] })
    }
  }
  const parts: ReactNode[] = []
  for (const [index, entry] of entries.entries()) {
    const isParagraph = 'paragraph' in entry
    if (loose || index !== 0 || !isParagraph) parts.push('\n')
    if (!isParagraph) parts.push(entry.element)
    else if (loose) parts.push(<p key={`p-${index}`}>{entry.paragraph}</p>)
    else parts.push(<Fragment key={`p-${index}`}>{entry.paragraph}</Fragment>)
  }
  const tail = entries[entries.length - 1]
  if (tail !== undefined && (loose || !('paragraph' in tail))) parts.push('\n')
  return (
    <li key={key} className={task ? 'task-list-item' : undefined}>
      {parts}
    </li>
  )
}

function renderTable(node: Md.Table, key: Key, context: MarkdownRenderContext): ReactNode {
  const align = node.align ?? null
  const [headRow, ...bodyRows] = node.children
  return (
    <div key={key} className={css.tableScroll}>
      <table>
        {headRow !== undefined && <thead>{renderTableRow(headRow, 'th', align, 0, context)}</thead>}
        {bodyRows.length > 0 && (
          <tbody>
            {bodyRows.map((row, index) => renderTableRow(row, 'td', align, index + 1, context))}
          </tbody>
        )}
      </table>
    </div>
  )
}

function renderTableRow(
  row: Md.TableRow,
  cellTag: 'th' | 'td',
  align: readonly Md.AlignType[] | null,
  key: Key,
  context: MarkdownRenderContext,
): ReactNode {
  const length = align === null ? row.children.length : align.length
  const cells: ReactNode[] = []
  for (let index = 0; index < length; index++) {
    const cell = row.children[index]
    const alignValue = align?.[index]
    cells.push(createElement(
      cellTag,
      { key: index, style: alignValue == null ? undefined : { textAlign: alignValue } },
      ...(cell === undefined ? [] : renderChildren(cell.children, context)),
    ))
  }
  return <tr key={key}>{cells}</tr>
}

/** 链接：协议白名单放行或退化纯文本，外链补 target/rel。 */
function renderSafeLink(href: string, children: ReactNode[], key: Key): ReactNode {
  const safeHref = sanitizeUrl(href)
  if (safeHref === '') return <Fragment key={key}>{children}</Fragment>
  const external = ['http:', 'https:'].includes(new URL(safeHref).protocol)
  return (
    <a
      key={key}
      href={safeHref}
      {...(external ? { target: '_blank', rel: 'noopener noreferrer' } : {})}
    >
      {children}
    </a>
  )
}

function renderAnchor(url: string, children: ReactNode[], key: Key): ReactNode {
  return renderSafeLink(normalizeUri(url), children, key)
}

function renderImage(url: string, alt: string, key: Key): ReactNode {
  const imageSrc = remoteImageUrl(sanitizeUrl(normalizeUri(url)))
  if (imageSrc === undefined) {
    return <span key={key} className={css.imageAlt}>{alt}</span>
  }
  return (
    <img
      key={key}
      className={css.image}
      src={imageSrc}
      alt={alt}
      loading="lazy"
      decoding="async"
      referrerPolicy="no-referrer"
    />
  )
}

function referenceSuffix(node: Md.LinkReference | Md.ImageReference): string {
  if (node.referenceType === 'collapsed') return '][]'
  if (node.referenceType === 'full') return `][${node.label ?? node.identifier}]`
  return ']'
}

function renderLinkReference(
  node: Md.LinkReference,
  key: Key,
  context: MarkdownRenderContext,
): ReactNode {
  const definition = context.targets.definitions.get(node.identifier.toUpperCase())
  if (definition === undefined) {
    // 增量段可能暂缺定义：还原回括源码字面量。
    return <Fragment key={key}>{'['}{renderChildren(node.children, context)}{referenceSuffix(node)}</Fragment>
  }
  return renderAnchor(definition.url, renderChildren(node.children, { ...context, inLink: true }), key)
}

function renderImageReference(
  node: Md.ImageReference,
  key: Key,
  context: MarkdownRenderContext,
): ReactNode {
  const definition = context.targets.definitions.get(node.identifier.toUpperCase())
  if (definition === undefined) return `![${node.alt ?? ''}${referenceSuffix(node)}`
  return renderImage(definition.url, node.alt ?? '', key)
}

function renderFootnoteReference(
  node: Md.FootnoteReference,
  key: Key,
  context: MarkdownRenderContext,
): ReactNode {
  const id = node.identifier.toUpperCase()
  const seen = context.footnoteCounts.get(id)
  if (seen === undefined) context.footnoteOrder.push(id)
  context.footnoteCounts.set(id, (seen ?? 0) + 1)
  return <sup key={key}>{String(context.footnoteOrder.indexOf(id) + 1)}</sup>
}

export function renderFootnoteSection(context: MarkdownRenderContext): ReactNode | null {
  const items: ReactNode[] = []
  for (const id of context.footnoteOrder) {
    const definition = context.targets.footnotes.get(id)
    if (definition === undefined) continue
    const count = context.footnoteCounts.get(id) ?? 0
    const backrefs: ReactNode[] = []
    for (let reference = 1; reference <= count; reference++) {
      if (backrefs.length > 0) backrefs.push(' ')
      backrefs.push('↩')
      if (reference > 1) backrefs.push(<sup key={`re-${reference}`}>{String(reference)}</sup>)
    }
    const entries = renderBlockEntries(definition.children, context)
    const tail = entries[entries.length - 1]
    const body: ReactNode[] = entries.map((entry, index) => (
      'paragraph' in entry
        ? (
          <p key={`p-${index}`}>
            {entry.paragraph}
            {entry === tail && <>{' '}{backrefs}</>}
          </p>
        )
        : entry.element
    ))
    if (tail === undefined || !('paragraph' in tail)) body.push(...backrefs)
    items.push(
      <li key={id}>
        {wrapBlockChildren(body, true)}
      </li>,
    )
  }
  if (items.length === 0) return null
  return (
    <section key="footnotes" data-footnotes className="footnotes">
      <ol>{items}</ol>
    </section>
  )
}
