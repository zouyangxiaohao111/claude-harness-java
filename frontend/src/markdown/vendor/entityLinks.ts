/**
 * 实体链接渲染：把知识库图谱实体的自定义 URI 变成可点的实体 chip。
 *
 * 来源：WeKnora（pinned 80e8345）frontend/src/utils/weknoraEntityLinks.ts，逻辑逐行照搬。
 * 品牌名已中性化，仅两处命名变化，语义不变：
 *   - URI scheme：源站为 `weknora://`，本仓改用 `kbentity://`；
 *   - CSS class：源站为 `weknora-entity-link`，本仓改为 `kb-entity-link`。
 *
 * 为什么需要：图谱里的实体（知识库 / 文档 / 片段）用自定义 URI 互相引用。
 * 这个模块把 URI 解析成 {kind, id}，渲染成可点的行内元素，并打上固定 class 便于 CSS。
 * 产出的不是 `<a href>`，而是 `<span role="link" tabindex="0">`：自定义 scheme 的 href
 * 会被 DOMPurify 的 URI 白名单剥掉，所以这里不产 href，点击行为交给外层按 data-entity-* 处理。
 */

export type EntityKind = 'kb' | 'doc' | 'chunk'

export interface EntityRef {
  kind: EntityKind
  id: string
}

export type EntityLinkLabels = Record<EntityKind, string>

export const ENTITY_LINK_CLASS = 'kb-entity-link'

/** Entity URIs use this scheme. Source site used `weknora://`; semantics are unchanged. */
export const ENTITY_URI_SCHEME = 'kbentity'

const URI_BODY = '(kb|doc|chunk)/([0-9A-Za-z][0-9A-Za-z_-]*)'
const URI_EXACT_RE = new RegExp(`^${ENTITY_URI_SCHEME}://${URI_BODY}$`, 'i')
const URI_IN_TEXT_RE = new RegExp(`${ENTITY_URI_SCHEME}://${URI_BODY}`, 'gi')
const URI_PRESENT_RE = new RegExp(`${ENTITY_URI_SCHEME}://`, 'i')
const HREF_RE = /\bhref\s*=\s*(?:"([^"]*)"|'([^']*)')/i
const SKIPPED_TAGS = new Set(['code', 'pre', 'a', 'button', 'svg', 'math'])

/** Parses a `kbentity://kb|doc|chunk/<id>` durable URI emitted by the model-context codec. */
export function parseEntityURI(value: string | null | undefined): EntityRef | null {
  const match = URI_EXACT_RE.exec((value || '').trim())
  if (!match) return null
  return { kind: match[1].toLowerCase() as EntityKind, id: match[2] }
}

function escapeHtml(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

function entityOpenTag(ref: EntityRef, labels: EntityLinkLabels): string {
  return `<span class="${ENTITY_LINK_CLASS}" role="link" tabindex="0" ` +
    `title="${escapeHtml(labels[ref.kind])}" data-entity-kind="${ref.kind}" data-entity-id="${ref.id}">`
}

/**
 * Turns `kbentity://` URIs in rendered chat HTML into clickable entity chips.
 * Markdown links keep their text; bare URIs in prose show the real ID, since
 * the model writes them where an ID is meant. The kind label is the tooltip.
 * Text inside code, pre and ordinary links is left alone. Must run before
 * DOMPurify, whose URI allowlist would otherwise strip the custom scheme.
 */
export function renderEntityLinks(html: string, labels: EntityLinkLabels): string {
  if (!html || !URI_PRESENT_RE.test(html)) return html
  const parts = html.split(/(<[^>]*>)/)
  const skipDepth = new Map<string, number>()
  let skipping = 0
  let entityAnchorOpen = false

  for (let i = 0; i < parts.length; i++) {
    const part = parts[i]
    if (!part) continue
    if (part.startsWith('<')) {
      const tag = /^<\s*(\/?)\s*([a-zA-Z][\w-]*)/.exec(part)
      if (!tag) continue
      const closing = tag[1] === '/'
      const name = tag[2].toLowerCase()
      if (name === 'a' && closing && entityAnchorOpen) {
        parts[i] = '</span>'
        entityAnchorOpen = false
        continue
      }
      if (name === 'a' && !closing && skipping === 0) {
        const hrefMatch = HREF_RE.exec(part)
        const ref = parseEntityURI(hrefMatch?.[1] ?? hrefMatch?.[2])
        if (ref) {
          parts[i] = entityOpenTag(ref, labels)
          entityAnchorOpen = true
          continue
        }
      }
      if (!SKIPPED_TAGS.has(name) || part.endsWith('/>')) continue
      const depth = skipDepth.get(name) || 0
      if (closing) {
        if (depth > 0) {
          skipDepth.set(name, depth - 1)
          skipping--
        }
      } else {
        skipDepth.set(name, depth + 1)
        skipping++
      }
      continue
    }
    if (skipping > 0 || entityAnchorOpen) continue
    parts[i] = part.replace(URI_IN_TEXT_RE, (_m, kind: string, id: string) => {
      const ref = { kind: kind.toLowerCase() as EntityKind, id }
      return `${entityOpenTag(ref, labels)}${id}</span>`
    })
  }
  return parts.join('')
}
