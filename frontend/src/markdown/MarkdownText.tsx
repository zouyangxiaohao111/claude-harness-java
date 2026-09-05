/**
 * 对话正文 markdown 渲染入口（对齐 deepseek-harness MarkdownText 裁剪版）。
 *
 * 双态：
 * - streaming=true：`StreamingRenderer` + `IncrementalMarkdownParser(parseGfm)` —— 每帧对
 *   增长文本做**增量尾窗解析**（冻结前部块缓存为 React 元素），无整段 innerHTML 重写，
 *   根治「打字到代码块/长内容卡顿」。**不跑** applySettledPatches（全局长度改写会破坏
 *   尾窗 verbatim-slice 不变量）；代码块由真实语法树渐进成形（未闭合 fence 也产 code 节点）。
 * - streaming=false（缺省）：settled 一次全量 —— applySettledPatches → parseGfmWithMath →
 *   引用自愈 / ```math / shiki 高亮都在这臂生效。
 *
 * 安全由 render.tsx 兜底（raw HTML 字面量、URL 白名单、图片 http(s)）。
 */
import { memo, useMemo, useRef } from 'react'
import type { ReactNode } from 'react'
import { IncrementalMarkdownParser } from './incremental.ts'
import { parseGfm, parseGfmWithMath } from './parse.ts'
import { applySettledPatches } from './patches.ts'
import {
  collectReferenceTargets,
  createReferenceTargets,
  renderBlocks,
  renderFootnoteSection,
  wrapBlockChildren,
} from './render.tsx'
import type { ReferenceTargets } from './render.tsx'
import 'katex/dist/katex.min.css'

export interface MarkdownTextProps {
  /** markdown 源码。 */
  text: string
  /** true=流式增量（无 patch、不高亮）；false/缺省=settled 全量 + patch + 高亮/math。 */
  streaming?: boolean
  /** 根 div class（对话正文传 'content md' / 用户气泡 'user-text md'）。缺省 'content md'。 */
  className?: string
  /** html 代码块「运行」回调（透传给 CodeBlock）。须引用稳定（冻结元素会烘焙它）。 */
  onRunHtml?: ((code: string) => void) | undefined
}

/** 一次 settled 全量渲染：patch → 带 math 语法 → 引用解析 + 脚注区。 */
function renderSettled(
  text: string,
  onRunHtml: ((code: string) => void) | undefined,
): ReactNode[] {
  const root = parseGfmWithMath(applySettledPatches(text))
  const targets = createReferenceTargets()
  collectReferenceTargets(root.children, targets)
  const context = {
    streaming: false,
    onRunHtml,
    targets,
    footnoteOrder: [] as string[],
    footnoteCounts: new Map<string, number>(),
  }
  const blocks = wrapBlockChildren(
    renderBlocks(root.children.map((node, index) => ({ node, key: index })), context),
    false,
  )
  const section = renderFootnoteSection(context)
  return section === null ? blocks : [...blocks, '\n', section]
}

/**
 * settled 结果 LRU 缓存：内容不变的消息跨会话重开不再重新 mdast 解析 + KaTeX/shiki。
 * 快速在几个历史会话间反复切换时，整列表曾每次全量重排 → 主线程积压「卡死」；命中缓存近乎零成本。
 * key = 消息 content 字符串（同一 content 对象引用可复用）。上限 SETTLED_CACHE_MAX 条防内存无界。
 */
const SETTLED_CACHE_MAX = 240
const settledCache = new Map<string, ReactNode[]>()
function renderSettledCached(
  text: string,
  onRunHtml: ((code: string) => void) | undefined,
): ReactNode[] {
  const hit = settledCache.get(text)
  if (hit !== undefined) return hit
  const rendered = renderSettled(text, onRunHtml)
  if (settledCache.size >= SETTLED_CACHE_MAX) {
    const oldest = settledCache.keys().next().value
    if (oldest !== undefined) settledCache.delete(oldest)
  }
  settledCache.set(text, rendered)
  return rendered
}

/**
 * 流式渲染态（ref 持有）：增量 parser + 已冻结块缓存 + 引用/脚注状态。
 * 每帧只对尾窗重解析；同文本幂等。
 */
class StreamingRenderer {
  private readonly parser = new IncrementalMarkdownParser(parseGfm)
  private generation = -1
  private frozenCount = 0
  private frozenElements: ReactNode[] = []
  private frozenTargets: ReferenceTargets = createReferenceTargets()
  private frozenFootnoteOrder: string[] = []
  private frozenFootnoteCounts = new Map<string, number>()
  private lastText: string | null = null
  private lastRendered: ReactNode[] = []

  constructor(private readonly onRunHtml: ((code: string) => void) | undefined) {}

  render(text: string): ReactNode[] {
    if (text === this.lastText) return this.lastRendered
    const { frozen, tail, generation } = this.parser.update(text)
    if (generation !== this.generation) {
      this.generation = generation
      this.frozenCount = 0
      this.frozenElements = []
      this.frozenTargets = createReferenceTargets()
      this.frozenFootnoteOrder = []
      this.frozenFootnoteCounts = new Map()
    }
    const newlyFrozen = frozen.slice(this.frozenCount)
    collectReferenceTargets(newlyFrozen.map(block => block.node), this.frozenTargets)
    const frameTargets: ReferenceTargets = {
      definitions: new Map(this.frozenTargets.definitions),
      footnotes: new Map(this.frozenTargets.footnotes),
    }
    collectReferenceTargets(tail.map(block => block.node), frameTargets)
    if (newlyFrozen.length > 0) {
      const frozenContext = {
        streaming: true,
        onRunHtml: this.onRunHtml,
        targets: frameTargets,
        footnoteOrder: this.frozenFootnoteOrder,
        footnoteCounts: this.frozenFootnoteCounts,
      }
      const batch = [...this.frozenElements]
      for (const element of renderBlocks(newlyFrozen, frozenContext)) {
        if (batch.length > 0) batch.push('\n')
        batch.push(element)
      }
      this.frozenElements = batch
      this.frozenCount = frozen.length
    }
    const tailContext = {
      streaming: true,
      onRunHtml: this.onRunHtml,
      targets: frameTargets,
      footnoteOrder: [...this.frozenFootnoteOrder],
      footnoteCounts: new Map(this.frozenFootnoteCounts),
    }
    const children = [...this.frozenElements]
    for (const element of renderBlocks(tail, tailContext)) {
      if (children.length > 0) children.push('\n')
      children.push(element)
    }
    const section = renderFootnoteSection(tailContext)
    if (section !== null) children.push('\n', section)
    this.lastText = text
    this.lastRendered = children
    return this.lastRendered
  }
}

export const MarkdownText = memo(function MarkdownText({
  text,
  streaming = false,
  className = 'content md',
  onRunHtml,
}: MarkdownTextProps) {
  const streamRef = useRef<StreamingRenderer | null>(null)
  const onRunRef = useRef(onRunHtml)
  const children = useMemo(() => {
    if (!streaming) {
      streamRef.current = null
      return renderSettledCached(text, onRunHtml)
    }
    // 非追加 / 回调变化 → 重建渲染器（冻结元素会烘焙 onRunHtml，引用必须稳定）。
    if (streamRef.current === null || onRunRef.current !== onRunHtml) {
      onRunRef.current = onRunHtml
      streamRef.current = new StreamingRenderer(onRunHtml)
    }
    return streamRef.current.render(text)
  }, [text, streaming, onRunHtml])
  return <div className={className}>{children}</div>
})
