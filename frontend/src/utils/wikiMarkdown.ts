import { marked } from 'marked'

/**
 * Wiki Markdown 渲染 · W3。
 *
 * <h2>⭐⭐ 核心问题：`[[slug|名]]` 不是标准 Markdown</h2>
 * 后端用 `[[entity/acme|Acme]]` 表示页间链接（源侧同款）。`marked` 不认识这个语法，
 * 直接渲染会原样显示 `[[entity/acme|Acme]]` —— 用户看到的是一串噪音。
 *
 * <p>⇒ 必须<b>在交给 marked 之前</b>把它替换成合法的 `<a data-wiki-slug="...">`。
 *
 * <h2>⚠️ 四个必须处理的边界</h2>
 * <ol>
 *   <li><b>代码块/行内代码里不能替换</b> —— 那是示例代码，替换会破坏它；</li>
 *   <li><b>slug 里有特殊字符</b> —— 必须转义，否则会拼出坏 HTML（XSS 面）；</li>
 *   <li><b>`[[slug]]` 没有显示名</b> —— 用 slug 的末段；</li>
 *   <li><b>不是合法 slug 形态</b>（没有 `<type>/`）—— 当普通文本，⛔ 不猜。</li>
 * </ol>
 *
 * <h2>⭐⭐ XSS：marked 的 `sanitize` 已在 v8+ 移除</h2>
 * 现代 `marked` <b>不做 HTML 消毒</b>。页面内容来自模型生成，
 * 可能包含 `<script>` / `<img onerror=...>`。⇒ 本模块<b>先对原文做 HTML 转义</b>，
 * 再把换行/代码块等标记还原 —— 只允许我们自己生成的 `<a>` / `<code>` 等标签。
 * ⛔ 不要把模型输出直接塞进 `dangerouslySetInnerHTML`。
 */

/** 允许出现的标签（白名单）。 */
const ALLOWED_TAGS = new Set([
  'p', 'br', 'strong', 'em', 'del', 'code', 'pre',
  'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
  'ul', 'ol', 'li', 'blockquote', 'hr',
  'table', 'thead', 'tbody', 'tr', 'th', 'td',
  'a',
])

/** Wiki 链接在 HTML 里的标记属性名。 */
export const WIKI_LINK_ATTR = 'data-wiki-slug'

/**
 * HTML 转义（防 XSS 的第一道门）。
 *
 * @param s 原始文本
 */
function escapeHtml(s: string): string {
  return (s ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/**
 * 解析 `[[...]]` 的内层。
 *
 * @param inner `[[` 与 `]]` 之间的内容
 * @returns slug + 显示名；非法返回 null
 */
export function parseWikiLink(inner: string): { slug: string; label: string } | null {
  const raw = (inner ?? '').trim()
  if (!raw) return null
  const pipe = raw.indexOf('|')
  const slug = (pipe >= 0 ? raw.slice(0, pipe) : raw).trim()
  const label = (pipe >= 0 ? raw.slice(pipe + 1) : '').trim()
  // ⭐ 必须是 `<type>/<name>` 两段 —— 否则当普通文本（⛔ 不猜）
  const slash = slug.indexOf('/')
  if (slash <= 0 || slash === slug.length - 1) return null
  return { slug, label: label || slug.slice(slash + 1) }
}

/**
 * 把 `[[slug|名]]` 换成 `<a data-wiki-slug="slug">名</a>`。
 *
 * <p>⚠️ <b>跳过行内代码与围栏代码块</b>：那里的 `[[...]]` 是示例文本，
 * 替换会破坏代码内容（而且用户复制走样）。
 *
 * @param text 已经过 HTML 转义的文本
 */
function replaceWikiLinks(text: string): string {
  let out = ''
  let i = 0
  const n = text.length
  let inFence = false
  let fenceMarker = ''

  while (i < n) {
    // ── 围栏代码块：检测行首 ``` 或 ~~~
    if (atLineStart(text, i)) {
      const run = fenceRunAt(text, i)
      if (run >= 3) {
        const ch = text[i]
        if (!inFence) {
          inFence = true
          fenceMarker = ch.repeat(run)
          out += text.slice(i, i + run)
          i += run
          continue
        }
        if (ch === fenceMarker[0] && run >= fenceMarker.length) {
          inFence = false
          fenceMarker = ''
        }
        out += text.slice(i, i + run)
        i += run
        continue
      }
    }
    if (inFence) {
      out += text[i]
      i++
      continue
    }

    // ── 行内代码：整段跳过
    if (text[i] === '`') {
      const run = fenceRunAt(text, i)
      const close = findInlineCodeClose(text, i + run, run)
      if (close < 0) {
        out += text.slice(i, i + run)
        i += run
        continue
      }
      out += text.slice(i, close + run)
      i = close + run
      continue
    }

    // ── [[slug|名]]
    if (text[i] === '[' && text[i + 1] === '[') {
      const close = text.indexOf(']]', i + 2)
      if (close >= 0) {
        const parsed = parseWikiLink(text.slice(i + 2, close))
        if (parsed) {
          // ⚠️ slug 与 label 都必须转义 —— 它们来自模型输出
          // ⭐ 带 href="#"：与源站 WeKnora 一致（缺 href 时浏览器 :link 默认色规则不适用）
          out += `<a href="#" ${WIKI_LINK_ATTR}="${escapeHtml(parsed.slug)}">${escapeHtml(parsed.label)}</a>`
        } else {
          // 非法形态 ⇒ 原样保留
          out += text.slice(i, close + 2)
        }
        i = close + 2
        continue
      }
    }

    out += text[i]
    i++
  }
  return out
}

function atLineStart(s: string, i: number): boolean {
  if (i === 0) return true
  const prev = s.lastIndexOf('\n', i - 1)
  const lineStart = prev + 1
  // 行首到 i 之间只能是空格
  for (let p = lineStart; p < i; p++) {
    if (s[p] !== ' ' && s[p] !== '\t') return false
  }
  return true
}

function fenceRunAt(s: string, i: number): number {
  const ch = s[i]
  if (ch !== '`' && ch !== '~') return 0
  let run = 0
  while (i + run < s.length && s[i + run] === ch) run++
  return run
}

function findInlineCodeClose(s: string, from: number, runLen: number): number {
  let i = from
  while (i < s.length) {
    if (s[i] === '`') {
      const run = fenceRunAt(s, i)
      if (run === runLen) return i
      i += run
      continue
    }
    i++
  }
  return -1
}

/**
 * 清洗 marked 输出的 HTML：只保留白名单标签与安全属性。
 *
 * <h2>⭐⭐ 为什么必须有这一步</h2>
 * `marked` v8+ <b>不再做 HTML 消毒</b>，而且我们输入的是<b>模型生成的 Markdown</b>。
 * 模型完全可能输出 `<img src=x onerror=alert(1)>`。
 * <p>⇒ 两道门：① 原文先 HTML 转义；② 输出再过一遍标签白名单。
 *
 * @param html marked 输出
 */
function sanitizeHtml(html: string): string {
  // 逐个标签检查：不在白名单的直接去掉（保留内部文本）
  return html.replace(/<\/?([a-zA-Z][a-zA-Z0-9-]*)([^>]*)>/g, (whole, tagName: string, attrs: string) => {
    const tag = tagName.toLowerCase()
    if (!ALLOWED_TAGS.has(tag)) {
      return ''   // 去标签，保留文本
    }
    const closing = whole.startsWith('</')
    if (closing) {
      return `</${tag}>`
    }
    // 只保留安全属性
    const safeAttrs: string[] = []
    // wiki 链接
    const wiki = attrs.match(new RegExp(`${WIKI_LINK_ATTR}="([^"]*)"`))
    if (wiki && tag === 'a') {
      // ⭐ wiki 内链固定 href="#"（与源站 WeKnora 一致）：下面的普通链接分支只放行 http/https，
      //    不在这里显式补 href="#" 的话，href 会被白名单剥掉 ⇒ <a> 缺 href 语义上不是链接
      safeAttrs.push('href="#"')
      safeAttrs.push(`${WIKI_LINK_ATTR}="${wiki[1]}"`)
      // ⭐ 用源站的 wiki-content-link：本仓 kb-shared.css 已有该类定义（品牌绿 + 虚线下划线），
      //    原来的 wiki-link 在 styles/ 下零定义 ⇒ 链接会继承正文纯黑色
      safeAttrs.push('class="wiki-content-link"')
    }
    // 普通链接：只允许 http/https + 加 noopener
    if (tag === 'a') {
      const href = attrs.match(/href="([^"]*)"/)
      if (href && /^https?:\/\//i.test(href[1])) {
        safeAttrs.push(`href="${href[1]}"`)
        safeAttrs.push('target="_blank"')
        safeAttrs.push('rel="noopener noreferrer"')
      }
    }
    // 图片：只允许 http/https 的 src（且必须带 alt）
    if (tag === 'img') {
      // ⛔ img 不在白名单（会被上面的 ALLOWED_TAGS 判掉），这里不处理
    }
    // class（marked 给代码块加的 language-xxx）
    const cls = attrs.match(/class="([^"]*)"/)
    if (cls && (tag === 'code' || tag === 'pre')) {
      safeAttrs.push(`class="${cls[1].replace(/[^a-zA-Z0-9_\- ]/g, '')}"`)
    }
    return `<${tag}${safeAttrs.length ? ' ' + safeAttrs.join(' ') : ''}>`
  })
}

/**
 * 渲染 Markdown 为安全 HTML。
 *
 * @param markdown 原始 Markdown（模型生成）
 * @returns 可放进 `dangerouslySetInnerHTML` 的 HTML
 */
export function renderWikiMarkdown(markdown: string): string {
  const src = markdown ?? ''
  // ① 先转义：把模型可能注入的原始 HTML 变成文本
  const escaped = escapeHtml(src)
  // ② 还原 Markdown 需要的最小字符集（& < > 已转义，但 markdown 标记本身没变）
  //    ⚠️ 不需要还原：marked 把 `#`、`*`、`-` 当标记，`&lt;` 会正确显示为 `<`
  // ③ 把 [[wiki]] 换成合法 <a>
  const withLinks = replaceWikiLinks(escaped)
  // ④ marked 渲染
  const html = marked.parse(withLinks, { async: false }) as string
  // ⑤ 标签白名单
  return sanitizeHtml(html)
}

/**
 * 从渲染后的 HTML 里提取全部 wiki 链接（供「反向链接」面板用）。
 *
 * @param markdown 原始 Markdown
 * @returns 去重后的 slug 列表（保序）
 */
export function extractWikiLinks(markdown: string): string[] {
  const out: string[] = []
  const seen = new Set<string>()
  const re = /\[\[([^\]]+)\]\]/g
  let m: RegExpExecArray | null
  while ((m = re.exec(markdown ?? '')) !== null) {
    const parsed = parseWikiLink(m[1])
    if (parsed && !seen.has(parsed.slug)) {
      seen.add(parsed.slug)
      out.push(parsed.slug)
    }
  }
  return out
}

/**
 * 把正文按标题切成目录项（供右侧大纲）。
 *
 * @param markdown 原始 Markdown
 * @returns 标题层级 + 文本 + 锚点
 */
export function extractHeadings(markdown: string): { level: number; text: string; anchor: string }[] {
  const out: { level: number; text: string; anchor: string }[] = []
  const lines = (markdown ?? '').split('\n')
  let inFence = false
  for (const line of lines) {
    const trimmed = line.trim()
    if (/^(```|~~~)/.test(trimmed)) {
      inFence = !inFence
      continue
    }
    if (inFence) continue
    const m = trimmed.match(/^(#{1,6})\s+(.+)$/)
    if (!m) continue
    const text = m[2].trim()
    out.push({
      level: m[1].length,
      text,
      anchor: text.toLowerCase().replace(/[^\w\u4e00-\u9fa5]+/g, '-').replace(/^-|-$/g, '') || 'section',
    })
  }
  return out
}
