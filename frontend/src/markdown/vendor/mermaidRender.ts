/**
 * Mermaid 真渲染 · 照搬源站。
 *
 * 来源：WeKnora（pinned 80e8345）`frontend/src/utils/mermaidShared.ts`
 * （`MERMAID_CONFIG` / `ensureMermaidInitialized` / `renderMermaidToSvg` / `renderMermaidInContainer`）
 * 与 `frontend/src/utils/markdownEnhancements.ts`（`buildMermaidBlockHtml`）。
 *
 * <h2>⭐ 为什么必须先产出「占位块」再异步渲染</h2>
 * `renderChatMarkdown()` 是**同步**函数（返回 HTML 字符串），而 `mermaid.render()` 是**异步**的
 * （它内部要加载解析器 + 布局）。两者放不进同一次调用。
 *
 * 源站的解法是两段式：
 * <ol>
 *   <li>同步阶段：`<pre data-mermaid="false">` 里放**源码**（此时用户看到的是代码，不是图）</li>
 *   <li>挂载后：`renderMermaidInContainer(root)` 扫描这些块，异步渲染成 SVG 并替换内容</li>
 * </ol>
 * ⚠️ `data-mermaid="false"` 是「还没渲染」的标记，渲染成功后由 mermaid 自身的 DOM 结构接管。
 *
 * <h2>⚠️ 流式期间不渲染</h2>
 * 流式块每帧都在变，异步渲染会产生大量废弃任务 + 闪烁。
 * 源站也是「流式结束（answerFullyRendered）后才渲染」，本仓沿用。
 */
import { useEffect, useRef } from 'react'

/** mermaid 只加载一次（动态 import 避免打进主 chunk）。 */
let mermaidMod: { default: unknown } | null = null
let mermaidInitialized = false
let initPromise: Promise<void> | null = null

/**
 * 主题配置 · 逐字照抄源站 `MERMAID_CONFIG`。
 *
 * ⚠️ `securityLevel: 'strict'` 是安全边界：禁止 mermaid 渲染出的 HTML 执行脚本。
 * ⛔ 不要为了「支持更多语法」改成 loose。
 */
const MERMAID_CONFIG = {
  startOnLoad: false,
  theme: 'base' as const,
  securityLevel: 'strict' as const,
  fontFamily: 'PingFang SC, Microsoft YaHei, sans-serif',
  flowchart: {
    useMaxWidth: true,
    htmlLabels: true,
    curve: 'basis',
    padding: 16,
  },
  sequence: {
    useMaxWidth: true,
    diagramMarginX: 12,
    diagramMarginY: 12,
    actorMargin: 56,
    width: 156,
    height: 68,
    boxMargin: 10,
  },
  gantt: {
    useMaxWidth: true,
    leftPadding: 80,
    gridLineStartPadding: 40,
    barHeight: 22,
    barGap: 6,
    topPadding: 56,
  },
  er: {
    useMaxWidth: true,
  },
  journey: {
    useMaxWidth: true,
  },
}

/** 浅色主题变量（源站 MERMAID_LIGHT_THEME 的核心子集）。 */
const MERMAID_LIGHT_THEME = {
  darkMode: false,
  background: '#ffffff',
  primaryColor: '#e2e8f0',
  primaryTextColor: '#334155',
  primaryBorderColor: '#94a3b8',
  secondaryColor: '#f1f5f9',
  secondaryTextColor: '#475569',
  secondaryBorderColor: '#cbd5e1',
  tertiaryColor: '#f8fafc',
  tertiaryTextColor: '#64748b',
  tertiaryBorderColor: '#e2e8f0',
  lineColor: '#94a3b8',
  textColor: '#334155',
  mainBkg: '#ffffff',
  nodeBorder: '#94a3b8',
  clusterBkg: '#f8fafc',
  clusterBorder: '#cbd5e1',
  titleColor: '#1e293b',
  edgeLabelBackground: '#ffffff',
  actorBorder: '#94a3b8',
  actorBkg: '#f1f5f9',
  actorTextColor: '#334155',
  actorLineColor: '#94a3b8',
  signalColor: '#94a3b8',
  labelBoxBkgColor: '#f1f5f9',
  labelBoxBorderColor: '#cbd5e1',
  labelTextColor: '#334155',
  noteBkgColor: '#f8fafc',
  noteTextColor: '#475569',
  noteBorderColor: '#cbd5e1',
}

/** 动态加载 mermaid（只 import 一次）。 */
async function getMermaid(): Promise<any> {
  if (!mermaidMod) {
    mermaidMod = (await import('mermaid')) as unknown as { default: unknown }
  }
  return (mermaidMod as { default: any }).default
}

/**
 * 初始化 mermaid（幂等）。
 *
 * <p>⚠️ 它与 {@code renderMermaidToSvg} 共用同一个 promise：
 * 并发的多次渲染不会重复 initialize。
 */
export function ensureMermaidInitialized(): Promise<void> {
  if (!initPromise) {
    initPromise = (async () => {
      const mermaid = await getMermaid()
      if (!mermaidInitialized) {
        mermaid.initialize({
          ...MERMAID_CONFIG,
          themeVariables: MERMAID_LIGHT_THEME,
        })
        mermaidInitialized = true
      }
    })()
  }
  return initPromise
}

let mermaidCount = 0

/**
 * 把 mermaid 源码渲染成 SVG 字符串。
 *
 * @param code mermaid 源码（不含 ```mermaid 围栏）
 * @param id   可选 id 前缀
 * @returns SVG 字符串；失败/空 ⇒ {@code null}（调用方保留源码，不吞错）
 */
export async function renderMermaidToSvg(code: string, id?: string): Promise<string | null> {
  if (!code.trim()) return null
  try {
    const mermaid = await getMermaid()
    await ensureMermaidInitialized()
    // 先 parse：语法错误在 render 里只会 return 一个半成品，parse 会抛真实错误
    await mermaid.parse(code)
    // mermaid 会把 render id 用作 SVG 根 id ⇒ 每次都必须新 id，否则与页面上已有的冲突
    const renderId = `${id || 'mermaid'}-${++mermaidCount}`
    const { svg } = await mermaid.render(renderId, code)
    return svg as string
  } catch (e) {
    console.warn('[mermaid] 渲染失败（保留源码）', e)
    return null
  }
}

/** 从容器里的一个 mermaid 块取源码。 */
function mermaidSourceFromElement(el: HTMLElement): string {
  const codeEl = el.querySelector('code')
  return (codeEl?.textContent ?? el.textContent ?? '').trim()
}

/**
 * 扫描容器内所有「待渲染」的 mermaid 块，异步渲染成 SVG。
 *
 * <h2>⚠️ 幂等</h2>
 * 已渲染的块里会有 `<svg>` ⇒ 跳过。这样重复调用（React 重渲染 / StrictMode 双挂载）
 * 不会重复插入。
 *
 * @param root 渲染根（通常是 MarkdownTextV2 那个 div）
 */
export async function renderMermaidInContainer(root: HTMLElement | null | undefined): Promise<void> {
  if (!root) return
  // 只处理带 data-mermaid="false" 的（未渲染标记）
  const blocks = root.querySelectorAll<HTMLElement>('pre[data-mermaid="false"]')
  if (!blocks.length) return

  await ensureMermaidInitialized()

  for (const el of Array.from(blocks)) {
    // 幂等：已有 svg 就不重复渲染
    if (el.querySelector('svg')) {
      el.setAttribute('data-mermaid', 'true')
      continue
    }
    const code = mermaidSourceFromElement(el)
    if (!code) continue
    const svg = await renderMermaidToSvg(code, 'md')
    if (svg) {
      el.innerHTML = svg
      el.setAttribute('data-mermaid', 'true')
    }
    // 失败：保持源码可见（⛔ 不 hide —— 用户至少能看到内容）
  }
}

/**
 * React 副作用：挂载/文本变化后渲染容器内的 mermaid。
 *
 * @param ref        渲染根 ref
 * @param deps       触发重跑的值（通常是渲染出的 html 字符串）
 * @param streaming  流式期间不渲染（源站同款策略）
 */
export function useMermaidRender(
  ref: React.RefObject<HTMLElement | null>,
  deps: unknown,
  streaming: boolean,
): void {
  const renderedRef = useRef<unknown>(null)
  useEffect(() => {
    if (streaming) return
    if (!ref.current) return
    if (renderedRef.current === deps) return
    renderedRef.current = deps
    void renderMermaidInContainer(ref.current)
  }, [ref, deps, streaming])
}
