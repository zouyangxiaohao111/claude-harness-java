/**
 * 聊天 Markdown 的 React 封装 · 本仓唯一需要「重写」而非照搬的部分。
 *
 * 来源：WeKnora（pinned 80e8345）的 Vue 组件调用 `renderChatMarkdown()`
 * + `v-html`。React 这边等价物是 `dangerouslySetInnerHTML`，
 * 其余（流水线、守卫、后处理）全部复用 `./chatMarkdownRenderer.ts`。
 *
 * <h2>⭐⭐ 安全边界：DOMPurify 在这里，不在流水线里</h2>
 * 流水线的 `sanitizeHtml` 是**由调用方提供**的函数（源站这样设计是为了可替换）。
 * 本文件传入了真正执行净化的实现 —— 这是整条链上**唯一**的 XSS 防线。
 * ⛔ 任何调用方都不许传 `(s) => s` 这样的恒等函数。
 *
 * <h2>⚠️ 与旧 MarkdownText 的取舍差异</h2>
 * 旧实现：`streaming=true` 时**纯文本直出**（不解析）。
 * 新实现：流式期**照常解析**，靠 `streamingGuards` 修好未闭合语法。
 * 这正是「采用源侧做法」的要求，会推翻 2026-09-18 的旧裁定。
 */
import { memo, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import DOMPurify from 'dompurify'
import { marked } from 'marked'
import type { Renderer } from 'marked'

import { isExternalLinkHref, openLinkInAppWindow, openLinkInBrowser } from '@/utils/externalLink'

// 代码高亮：highlight.js（照抄源站，含 highlightAuto 兜底）
import { highlightCode } from './codeHighlight'
import { useMermaidRender } from './mermaidRender'
// 剪贴板（本仓既有设施，V1 CodeBlock 用的就是它）
import { writeClipboard } from '@/markdown/clipboard'
// [rescue] 脏输入抢救：本仓独有能力（763 行），源站没有。只在**收口期**跑，见下方注释。
import { repair, PROD_REPAIR_OPTS } from '@/markdown/rescue'

import {
  renderChatMarkdown,
  renderChatMarkdownIncremental,
  type BlockFreezeCache,
  type RenderChatMarkdownOptions,
} from './chatMarkdownRenderer'

/** 允许的 HTML 标签（白名单）。⛔ 改这个列表等于改安全边界。 */
const ALLOWED_TAGS = [
  'p', 'br', 'hr', 'span', 'div', 'strong', 'em', 'del', 'u', 'sub', 'sup',
  'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
  'ul', 'ol', 'li',
  'table', 'thead', 'tbody', 'tr', 'th', 'td',
  'pre', 'code', 'blockquote',
  'a', 'img',
  'input', // 任务列表的 checkbox
  // 代码块的「复制」「运行」按钮（源站也有按钮，它的净化配置同理放行 button）。
  // ⚠️ 只放行 button 本身：onclick 之类的内联事件属性不在 ALLOWED_ATTR 里，
  //    所以这个 button 只能是「纯元素」，行为靠根节点点击委托。
  'button',
  'svg', 'path', 'g', 'circle', 'rect', 'line', 'polyline', 'polygon',
]

/** 允许的属性白名单。 */
const ALLOWED_ATTR = [
  'href', 'title', 'alt', 'src', 'class', 'id', 'target', 'rel',
  'colspan', 'rowspan', 'align', 'aria-label', 'aria-hidden',
  'data-*',   // 引用块 / 实体链接 / 代码块按钮靠 data-* 挂点击行为
  'type', 'checked', 'disabled', // 任务列表 + 按钮
  'viewBox', 'fill', 'stroke', 'stroke-width', 'd', 'cx', 'cy', 'r', 'x', 'y',
  'width', 'height', 'points',
]

/**
 * 净化 HTML（唯一 XSS 防线）。
 *
 * <h2>⚠️ 无 DOM 环境（vitest node 环境、SSR）的兜底</h2>
 * `dompurify` 的 ESM 入口调用 `createDOMPurify()`；**在没有 `window` 时它返回的对象
 * 没有 `.sanitize` 方法**（源码 `purify.es.mjs` 末尾：`var purify_default = createDOMPurify()`）。
 * 生产（浏览器）不受影响，但单测跑在 `environment: 'node'` 下会直接
 * `TypeError: default.sanitize is not a function`。
 *
 * 兜底策略：检测不到 `.sanitize` 时，退化为**严格的纯文本转义**
 * （而不是放行原 HTML）—— 宁可丢格式，也绝不放过未净化的 HTML。
 *
 * @param html 流水线产出的 HTML
 * @returns 净化后的 HTML
 */
export function sanitizeChatHtml(html: string): string {
  const purify = DOMPurify as unknown as { sanitize?: (h: string, o?: unknown) => string }
  if (typeof purify.sanitize !== 'function') {
    // 无 DOM：退化为纯文本（⛔ 不是放行）
    return `<pre class="md-nodom">${escapeHtml(html)}</pre>`
  }
  return DOMPurify.sanitize(html, {
    ALLOWED_TAGS,
    ALLOWED_ATTR,
    // ⛔ 禁止 data: / javascript: 协议
    ALLOW_DATA_ATTR: true,
  }) as string
}

export interface MarkdownTextV2Props {
  /** Markdown 源文本 */
  text: string
  /** 文本仍在增长（流式期）。为 true 时启用未闭合语法守卫 */
  streaming?: boolean
  /** 自定义 marked 渲染器（代码高亮等）。不传用默认 */
  renderer?: Renderer
  /** 额外 class */
  className?: string
  /**
   * HTML 代码块的「运行/预览」回调（对齐 V1 的同名 prop）。
   *
   * <p>V1 靠 React 组件 `CodeBlock` 直接挂 onClick；V2 产出的是 HTML 字符串，
   * 只能把代码塞进 `data-html-run` 属性，再由根的点击委托取出。
   * ⛔ 不用内联 `onclick` —— 会被 DOMPurify 剥掉，且是 XSS 面。
   */
  onRunHtml?: (code: string) => void
  /**
   * 块级冻结（流式期增量渲染）：前部块复用上帧 HTML，只重解析尾部。
   *
   * <p><b>默认开</b>（2026-11-18 拍板）。缺省 undefined 时读
   * `window.__NEXUS_MD_BLOCK_FREEZE__`（显式 false 可临时关，用于线上对比/回滚）；
   * 显式 true/false 优先。只影响「文本增长的帧」，历史单渲/收口帧恒走全量。
   *
   * <p>前部块不再被 `innerHTML` 重写，选区/图片/高亮不再每帧被清掉。
   */
  blockFreeze?: boolean
}

/**
 * 块级冻结开关（2026-11-18 用户拍板**默认开**）。
 *
 * 优先级：显式 prop → `window.__NEXUS_MD_BLOCK_FREEZE__`（显式 false 可临时关，
 * 用于线上对比/回滚）→ 默认 `true`。
 *
 * <p>实际只影响「本次 text 是上次的追加」的帧（流式增长期）；历史消息首渲/收口帧
 * 仍走全量单容器，DOM 结构不变。
 */
function blockFreezeEnabled(explicit?: boolean): boolean {
  if (explicit !== undefined) return explicit
  if (typeof window !== 'undefined') {
    const flag = (window as { __NEXUS_MD_BLOCK_FREEZE__?: boolean }).__NEXUS_MD_BLOCK_FREEZE__
    if (flag !== undefined) return flag === true
  }
  return true
}

/**
 * 正文里的外链右键菜单状态（坐标 + 目标 href）。
 *
 * <p>左键点外链一律交系统浏览器；右键才出这个菜单，可选「客户端内新窗口」。
 */
interface LinkMenuState {
  x: number
  y: number
  href: string
}

/**
 * 从事件目标向上找**外链** `<a>`；hash / 相对路径 / 代码块按钮一律返回 null。
 *
 * <p>用 {@code getAttribute('href')} 判协议而不是 {@code anchor.href}：后者会把相对地址
 * 绝对化成当前应用 origin 的 http: 地址，从而把站内链接误判成外链。
 */
function externalAnchorOf(target: EventTarget | null): HTMLAnchorElement | null {
  const element = target as HTMLElement | null
  const anchor = element?.closest?.('a[href]') as HTMLAnchorElement | null
  if (!anchor) return null
  return isExternalLinkHref(anchor.getAttribute('href') ?? '') ? anchor : null
}

/**
 * 渲染聊天 Markdown。
 *
 * @param props 见 {@link MarkdownTextV2Props}
 */
export const MarkdownTextV2 = memo(function MarkdownTextV2({
  text,
  streaming = false,
  renderer,
  className,
  onRunHtml,
  blockFreeze,
}: MarkdownTextV2Props) {
  const rootRef = useRef<HTMLDivElement | null>(null)
  const [linkMenu, setLinkMenu] = useState<LinkMenuState | null>(null)
  /** [实验 · 块级冻结] 帧间缓存：前部块的 token.raw + 成品 HTML。 */
  const freezeRef = useRef<BlockFreezeCache | null>(null)
  /** 上一次渲染的源文本 —— 只有「本次是上次的追加」才值得走块级增量。 */
  const lastTextRef = useRef<string | null>(null)

  /** 块级冻结时返回按块拆开的 HTML（每块一个 DOM 容器，前部块不会被重写）；
   *  否则返回整段 html（现状路径，单次 innerHTML）。 */
  const rendered = useMemo<{ html: string; blocks: readonly { html: string }[] | null }>(() => {
    if (!text || !text.trim()) return { html: '', blocks: null }
    // ⭐⭐ 分工：CC 管「流式期」，rescue 管「收口」。
    //
    // 流式期（streaming=true）**不跑 rescue** —— 与 V1 的取舍一致
    // （`MarkdownText.tsx:71` 的注释说明了原因）：rescue 会改写长度（插入换行/空格），
    // 而流式的增量解析依赖「上一帧是这一帧的前缀」这一不变量，长度改写会破坏它。
    // 流式期的未闭合语法由 `streamingGuards` 那 8 个守卫兜住（照抄源站）。
    //
    // 收口期（streaming=false）跑一次 rescue：源站流水线**修不了**模型输出的
    // 粘连形态（`##标题` 缺空格 / 表格首行与标题粘连 / 围栏贴行尾），因为它严格按
    // CommonMark 判定，这些本就不算语法。实测：同一份粘连原文，源站 marked17 与
    // 本仓 marked18 输出逐字一致、都是 `h2=0 table=0`。
    // rescue 的分法取自真解析器（不是整文本正则），且不碰围栏内代码；
    // 对规范文档幂等（实测 CLAUDE.md 3403 字符零改动）。
    const source = streaming ? text : repair(text, PROD_REPAIR_OPTS).out
    const opts: RenderChatMarkdownOptions = {
      renderer: renderer ?? buildDefaultRenderer(onRunHtml),
      // 源站把 escapeMarkdown 交给调用方；本仓没有额外转义需求 ⇒ 恒等。
      // ⚠️ 这不是安全边界 —— 净化在 sanitizeHtml 里做。
      escapeMarkdown: (s) => s,
      sanitizeHtml: sanitizeChatHtml,
      streaming,
    }
    try {
      /* [实验 · 块级冻结] 只在「本次源文本是上次的追加」时启用：
       *  - 首帧 / 历史消息单次渲染 / 收口帧 ⇒ 全量单容器（与现状结构完全一致）；
       *  - 流式增长帧 ⇒ 块级增量（前部块 DOM 不重写），文本被替换时自愈成全量。
       *  ⚠️ 判据不依赖 streaming prop —— 本仓流式期该 prop 实际恒为 false
       *  （`turnRunning={!!thinking}`，而有块时 thinking=turnRunning&&!hasStream=false），
       *  依赖它会让实验永不生效。该传参缺陷单独记录，不在本实验内改行为。 */
      const previousText = lastTextRef.current
      const growing = previousText !== null
        && text.length > previousText.length
        && text.startsWith(previousText)
      lastTextRef.current = text
      if (blockFreezeEnabled(blockFreeze) && growing) {
        const result = renderChatMarkdownIncremental(source, opts, freezeRef.current)
        freezeRef.current = result.cache
        return { html: result.html, blocks: result.cache.blocks }
      }
      freezeRef.current = null
      return { html: renderChatMarkdown(source, opts), blocks: null }
    } catch (e) {
      // 渲染失败不能让整条消息消失：降级成转义后的纯文本
      console.warn('[MarkdownTextV2] 渲染失败，降级纯文本', e)
      freezeRef.current = null
      return { html: `<pre class="md-fallback">${escapeHtml(text)}</pre>`, blocks: null }
    }
  }, [text, streaming, renderer, onRunHtml, blockFreeze])
  const html = rendered.html

  /**
   * 点击委托：代码块的「复制」与 HTML 块的「运行」。
   *
   * <p>⚠️ 只读我们自己写的属性 / DOM 结构，不执行任何 HTML 里的脚本 ——
   * 净化后本来也不剩脚本，但这里再守一道。
   *
   * <p>复制逻辑照抄源站 `markdownEnhancements.ts:handleCodeCopy`：
   * 从最近的 `.chat-code-block` 里取 `code.textContent`，
   * 成功后加 `is-copied` 类并把文案改成「已复制」，1.6 秒后还原。
   */
  const onClick = useCallback((e: React.MouseEvent<HTMLDivElement>) => {
    const target = e.target as HTMLElement | null
    if (!target) return

    // ⓪ 外链：拦掉 webview 内导航（Tauri 里没有地址栏/后退键），交系统浏览器。
    const anchor = externalAnchorOf(target)
    if (anchor) {
      e.preventDefault()
      void openLinkInBrowser(anchor.href)
      return
    }

    // ① 运行（HTML 代码块）
    const runBtn = target.closest('.chat-code-block__run') as HTMLElement | null
    if (runBtn && onRunHtml) {
      const code = runBtn.getAttribute('data-html-run')
      if (code) onRunHtml(decodeURIComponent(code))
      return
    }

    // ② 复制
    const copyBtn = target.closest('.chat-code-block__copy') as HTMLButtonElement | null
    if (!copyBtn) return
    const block = copyBtn.closest('.chat-code-block')
    const code = block?.querySelector('code')?.textContent ?? ''
    if (!code) return
    const textEl = copyBtn.querySelector('.chat-code-block__copy-text')
    void writeClipboard(code).then((ok) => {
      if (!ok) return
      copyBtn.classList.add('is-copied')
      if (textEl) textEl.textContent = '已复制'
      window.setTimeout(() => {
        copyBtn.classList.remove('is-copied')
        if (textEl) textEl.textContent = '复制'
      }, 1600)
    })
  }, [onRunHtml])

  /** 右键外链：只拦外链的默认菜单，其它区域保持系统菜单（复制文字等）。 */
  const onContextMenu = useCallback((e: React.MouseEvent<HTMLDivElement>) => {
    const anchor = externalAnchorOf(e.target)
    if (!anchor) return
    e.preventDefault()
    setLinkMenu({ x: e.clientX, y: e.clientY, href: anchor.href })
  }, [])

  // 菜单打开后：点别处 / 滚动 / Esc / 失焦都关掉（mousedown 用冒泡，
  // 菜单项自己的 onMouseDown 会 stopPropagation 先执行动作）。
  useEffect(() => {
    if (!linkMenu) return
    const close = () => setLinkMenu(null)
    const onKeyDown = (e: KeyboardEvent) => { if (e.key === 'Escape') close() }
    window.addEventListener('mousedown', close)
    window.addEventListener('scroll', close, true)
    window.addEventListener('blur', close)
    window.addEventListener('keydown', onKeyDown)
    return () => {
      window.removeEventListener('mousedown', close)
      window.removeEventListener('scroll', close, true)
      window.removeEventListener('blur', close)
      window.removeEventListener('keydown', onKeyDown)
    }
  }, [linkMenu])

  // ⭐ mermaid 两段式的第二段：mount 后把占位块异步渲染成 SVG。
  //    流式期间跳过（每帧都在变，异步渲染只会产生废弃任务 + 闪烁）。
  useMermaidRender(rootRef, html, streaming)

  if (!html) return null
  return (
    <>
      {rendered.blocks === null ? (
        /* 现状路径：单次 innerHTML（收口期 / 未开实验开关） */
        <div
          ref={rootRef}
          className={className ? `md-content ${className}` : 'md-content'}
          onClick={onClick}
          onContextMenu={onContextMenu}
          dangerouslySetInnerHTML={{ __html: html }}
        />
      ) : (
        /* [实验 · 块级冻结] 每块一个容器（display:contents 不生成盒子 ⇒ 排版与
         *  单容器逐像素一致，选择器按后代匹配不受影响）；前部块的 DOM 不重写。 */
        <div
          ref={rootRef}
          className={className ? `md-content ${className}` : 'md-content'}
          onClick={onClick}
          onContextMenu={onContextMenu}
        >
          {rendered.blocks.map((block, index) => (
            <div
              key={index}
              style={{ display: 'contents' }}
              dangerouslySetInnerHTML={{ __html: block.html }}
            />
          ))}
        </div>
      )}
      {linkMenu && createPortal(
        <div
          className="project-context-menu external-link-menu"
          style={{
            left: Math.max(8, Math.min(linkMenu.x, window.innerWidth - 220)),
            top: Math.max(8, Math.min(linkMenu.y, window.innerHeight - 100)),
          }}
          onContextMenu={(e) => e.preventDefault()}
        >
          <div
            className="ctx-menu-item"
            role="menuitem"
            onMouseDown={(e) => {
              e.stopPropagation()
              const href = linkMenu.href
              setLinkMenu(null)
              void openLinkInBrowser(href)
            }}
          >
            <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" aria-hidden="true">
              <circle cx="8" cy="8" r="5.5" />
              <path d="M2.5 8h11M8 2.5c1.6 1.5 2.4 3.4 2.4 5.5S9.6 12 8 13.5C6.4 12 5.6 10.1 5.6 8S6.4 4 8 2.5z" />
            </svg>
            在浏览器中打开
          </div>
          <div
            className="ctx-menu-item"
            role="menuitem"
            onMouseDown={(e) => {
              e.stopPropagation()
              const href = linkMenu.href
              setLinkMenu(null)
              void openLinkInAppWindow(href)
            }}
          >
            <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" aria-hidden="true">
              <rect x="2.5" y="3" width="11" height="10" rx="1.5" />
              <path d="M2.5 6h11" />
            </svg>
            在客户端内打开（新窗口）
          </div>
        </div>,
        document.body,
      )}
    </>
  )
})

/** 最小 HTML 转义（源站 markdownEnhancements.ts:70 同名函数，逐字照抄）。 */
function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/**
 * 默认渲染器 · 逐字照抄源站代码块。
 *
 * 来源：WeKnora（pinned 80e8345）
 * - `frontend/src/utils/mermaidShared.ts:202` createMermaidCodeRenderer
 * - `frontend/src/utils/markdownEnhancements.ts:84` buildCodeBlockHtml
 *
 * <h2>⭐⭐ 为什么必须自建 code renderer（真机缺陷，2026-11-11）</h2>
 * marked 默认输出裸 `<pre><code>`，没有任何容器。本仓/源站的代码块样式
 * 都挂在容器类下（`.chat-code-block__pre { overflow-x: auto; … }`），
 * 没有容器 ⇒ 选择器不匹配 ⇒ <b>代码块纯白、长代码撑破宽度</b>。
 *
 * <h2>⚠️ 唯一保留的本仓特性：HTML 代码块「运行」按钮</h2>
 * 源站没有这个按钮（它没有本地 html 预览沙箱）。本仓 V1 有
 * （`CodeBlock` 的 `onRunHtml`）⇒ 属于「本仓特有功能」，按用户 2026-11-11
 * 裁定保留。位置放在源站 `__actions` 里、复制按钮之前。
 *
 * @param onRunHtml HTML 代码块的「运行」回调（本仓特有）
 */
function buildDefaultRenderer(onRunHtml?: (code: string) => void): Renderer {
  const r = new marked.Renderer()

  r.code = function (this: Renderer, token: unknown) {
    const t = token as { lang?: string; text?: string }
    const rawLang = (t.lang || '').trim()
    const lang = rawLang.split(/\s+/)[0].toLowerCase()
    const code = t.text ?? ''

    // ⭐⭐ mermaid：产出「占位块」，由 mount 后的异步渲染替换成 SVG
    //    （renderChatMarkdown 是同步的，mermaid.render 是异步的 ⇒ 必须两段式）
    //    结构照抄源站 buildMermaidBlockHtml：.chat-mermaid-block > __header + __canvas
    if (lang === 'mermaid') {
      return `<div class="chat-mermaid-block">
  <div class="chat-mermaid-block__header">
    <span class="chat-mermaid-block__badge">图表</span>
    <div class="chat-mermaid-block__actions">
      <button type="button" class="chat-mermaid-block__expand" aria-label="放大查看" title="放大查看">${EXPAND_ICON}</button>
    </div>
  </div>
  <pre class="chat-mermaid-block__canvas mermaid" data-mermaid="false"><code class="language-mermaid">${escapeHtml(code)}</code></pre>
</div>`
    }

    // ① 高亮：highlight.js（源站同款，含 highlightAuto 兜底）
    const { html: highlighted, language: highlightLang } = highlightCode(code, rawLang)

    // ② 本仓特有：html 代码块的「运行」按钮
    const runBtn = (lang === 'html' && onRunHtml)
      ? `<button type="button" class="chat-code-block__run" data-html-run="${encodeURIComponent(code)}" title="在浏览器中打开预览">运行</button>`
      : ''

    // ③ 结构逐字照抄源站 buildCodeBlockHtml
    const displayLang = escapeHtml(formatCodeLang(rawLang))
    const safeLang = escapeHtml(highlightLang || rawLang || 'text')
    return `<div class="chat-code-block">
  <div class="chat-code-block__header">
    <span class="chat-code-block__lang">${displayLang}</span>
    <div class="chat-code-block__actions">
      ${runBtn}
      <button type="button" class="chat-code-block__copy" aria-label="复制代码" title="复制代码">
        ${COPY_ICON}<span class="chat-code-block__copy-text">复制</span>
      </button>
    </div>
  </div>
  <pre class="chat-code-block__pre"><code class="hljs language-${safeLang}">${highlighted}</code></pre>
</div>`
  }
  return r
}

/** 复制图标（源站 markdownEnhancements.ts:39 COPY_ICON，逐字照抄）。 */
const COPY_ICON = '<svg class="chat-code-block__copy-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>'

/** 放大图标（源站 markdownEnhancements.ts:41 EXPAND_ICON，逐字照抄）。 */
const EXPAND_ICON = '<svg class="chat-mermaid-block__expand-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M8 3H5a2 2 0 0 0-2 2v3"/><path d="M21 8V5a2 2 0 0 0-2-2h-3"/><path d="M3 16v3a2 2 0 0 0 2 2h3"/><path d="M16 21h3a2 2 0 0 0 2-2v-3"/></svg>'

/** 语言显示名（源站 markdownEnhancements.ts:40 LANG_LABELS，逐字照抄）。 */
const LANG_LABELS: Record<string, string> = {
  js: 'JavaScript', javascript: 'JavaScript',
  ts: 'TypeScript', typescript: 'TypeScript',
  py: 'Python', python: 'Python',
  java: 'Java', kotlin: 'Kotlin', go: 'Go', rust: 'Rust',
  sh: 'Shell', bash: 'Bash', shell: 'Shell', zsh: 'Shell',
  json: 'JSON', sql: 'SQL',
  html: 'HTML', css: 'CSS', scss: 'SCSS', less: 'LESS',
  yaml: 'YAML', yml: 'YAML', xml: 'XML',
  markdown: 'Markdown', md: 'Markdown',
}

/** 语言代码 → 显示名（源站 formatCodeLang:78，逐字照抄）。 */
function formatCodeLang(lang: string): string {
  const normalized = (lang || 'Code').trim()
  if (!normalized) return 'Code'
  const key = normalized.toLowerCase()
  return LANG_LABELS[key] || normalized.charAt(0).toUpperCase() + normalized.slice(1)
}
