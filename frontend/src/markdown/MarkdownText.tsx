/**
 * 对话正文 markdown 渲染入口（对齐 deepseek-harness MarkdownText 裁剪版）。
 *
 * 双态：
 * - streaming=true：**原样纯文本直出**（[stream-raw] 2026-09-18 起）—— 不调用
 *   `StreamingRenderer`、不跑任何 markdown 解析，逐字符输出后端原文
 *   （`<div class="md-stream-raw">` + globals.css 的 pre-wrap 呈现）；完整渲染交给收口臂。
 *   此前走 `StreamingRenderer` + `IncrementalMarkdownParser(parseGfm)` —— 每帧对增长文本做
 *   **增量尾窗解析**（冻结前部块缓存为 React 元素）。该路径与收口臂是**两套解析器**，实测流式期
 *   与收口结果不一致（`##标题` 流式期字面裸露、收口才成形，且掉字/整体位移）⇒ 用户裁定
 *   「流式期间不做 markdown 解析」。`StreamingRenderer` 原样保留，见其上方注释。
 * - streaming=false（缺省）：settled 一次全量 —— [rescue] 脏输入抢救（repair）→ parseGfmWithMath →
 *   引用自愈 / ```math / shiki 高亮都在这臂生效。
 *
 * [chat-switch-stream-align] deepseek 对照两项增强：
 * 1. 长单块纯文本降级（D1）：一个始终无法冻结的超长单块（未分段长 prose / 超长代码 fence）会让
 *    增量 parser 每帧对整块全文 O(n²) 重 parse → 主线程被占「字不吐」。检测到「无冻结块 + 尾窗
 *    仅 1 个长文本块」→ 退化为纯文本直出（零 parse 逐帧透传，帧率恢复）；streaming 结束
 *    settled 一次性精排恢复 markdown/高亮。deepseek 未做此扩展（其靠真实回复多段可冻结兜底）。
 *    ⚠ [stream-raw] 起生产不再走此路径（流式臂已改纯文本直出）：`STREAM_DEGRADE_CHARS` 与
 *    该判定**在新路径下不再被求值**，保留仅为支持一行回退。
 * 2. 流式渲染器跨 remount 复用（D2）：组件收到 streamKey（会话:块 id）时，StreamingRenderer 提升到
 *    模块级注册表 —— 切走会话再切回同一流式块，直接复用其增量状态（冻结前缀/冻结元素/降级态），
 *    不从零 parse 当前已长全文（治大文本回复在会话间来回切换时的整块重解析卡顿）。
 *    注册表 LRU 上限回收孤儿（finalize/clear 后块不再渲染）。
 *    ⚠ [stream-raw] 起生产不再走此路径（同上）：`MessageList` 已不再传 `streamKey`，本分支无生产
 *    消费者，保留仅为支持一行回退（`acquireStreamingRenderer` 仍被 renderer.test.tsx 直接引用）。
 *
 * 安全由 render.tsx 兜底（raw HTML 字面量、URL 白名单、图片 http(s)）。
 */
import { memo, useMemo } from 'react'
import type { ReactNode } from 'react'
import { IncrementalMarkdownParser } from './incremental.ts'
import { parseGfm, parseGfmWithMath } from './parse.ts'
import { PROD_REPAIR_OPTS, repair } from './rescue.ts'
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
  /** [chat-switch-stream-align] 流式渲染器复用键（通常 `${sessionId}:${assistantMessageId}`，块行唯一）。
   *  仅 streaming 时有效：命中则走模块级 StreamingRenderer 注册表（切走/切回不重建增量状态）。缺省回落组件 ref。
   *  ⚠ [stream-raw] 2026-09-18 起**此 prop 无生产消费者**（`MessageList` 已去掉该传参，流式臂改纯文本
   *  直出、不再需要跨 remount 复用增量状态）—— 类型保留仅为避免连带破坏与支持一行回退。 */
  streamKey?: string
}

/** 一次 settled 全量渲染：带 math 语法 → 引用解析 + 脚注区。
 *  [markdown-fix] 对齐 dsh：不再跑 applySettledPatches 文本预处理（原整文本正则会把围栏内
 *  #include/#define/#region/python #! 等代码行插空格、奇数 ``` 全剥离 → 同段代码「流式对收口错」）。
 *  [rescue] 与之相反：rescue 的判据取自真解析器（非整文本正则），且只做「还原模型本意的断行/
 *  补空格」，不碰围栏内代码 —— 两者不冲突（见 rescue.ts 的 C1/C3 约束）。 */
function renderSettled(
  text: string,
  onRunHtml: ((code: string) => void) | undefined,
): ReactNode[] {
  // [rescue] 脏输入抢救：把模型输出的粘连标记还原成解析器能读懂的形态。
  // 只走 settled（方案 B）——流式臂不调用，避免长度改写破坏增量解析器的「前缀原样」
  // 不变量。流式期仍会裸露，收口时一次终跳修正（MarkdownText 本就有该机制）。
  // 必须留在 renderSettled 内部（而非调用侧）：settledCache 的键是**原始正文**，
  // 在调用侧先修补会让键变成修补后的字符串，且复制/搜索/ContentGuard 会看到被改写的正文。
  const root = parseGfmWithMath(repair(text, PROD_REPAIR_OPTS).out)
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
 * [markdown-fix] 值带 onRunHtml：settled 渲染树烘焙了 CodeBlock「运行」回调，同 text 不同 handler
 * 不得复用（原纯 text 键会让同文本不同回调拿到旧树）→ 命中需 handler 引用 === 相同（App 的
 * onRunHtml = useCallback([]) 引用稳定，常态命中不变）。
 */
const SETTLED_CACHE_MAX = 240
const settledCache = new Map<string, {
  onRunHtml: ((code: string) => void) | undefined
  rendered: ReactNode[]
}>()
function renderSettledCached(
  text: string,
  onRunHtml: ((code: string) => void) | undefined,
): ReactNode[] {
  const hit = settledCache.get(text)
  if (hit !== undefined && hit.onRunHtml === onRunHtml) return hit.rendered
  const rendered = renderSettled(text, onRunHtml)
  if (settledCache.size >= SETTLED_CACHE_MAX) {
    const oldest = settledCache.keys().next().value
    if (oldest !== undefined) settledCache.delete(oldest)
  }
  settledCache.set(text, { onRunHtml, rendered })
  return rendered
}

// [D1] 超长单块纯文本降级阈值（字符）：流式文本超过该长度仍冻结不出多块（frozen 空 + 尾窗仅 1 块）
//   时退化为纯文本。避免未分段长 prose / 超长代码 fence 每帧全量 parse O(n²) 占死主线程 → 「字不吐」。
//   ⚠ [stream-raw] 2026-09-18 起生产不再走 StreamingRenderer ⇒ 本常量与其判定**不再被求值**
//   （流式臂整体就是纯文本直出，无需按长度降级）。保留仅为支持一行回退。
const STREAM_DEGRADE_CHARS = 8000
/** [D1] 降级纯文本根 class（globals.css 定义 pre-wrap 排版；视觉与正文一致）。
 *  ⚠ 同上：自 [stream-raw] 起不再被流式臂使用（流式臂改用 `STREAM_RAW_CLASS`）。 */
const STREAM_DEGRADE_CLASS = 'md-stream-degraded'
/** [stream-raw] 流式臂原样纯文本根 class（globals.css 同名规则：pre-wrap 保留换行、字体继承正文）。
 *  **必须是 `<div>` 而非 `<pre>`**：`.msg .content pre`（特异性 0,2,1）会压过本类（0,1,0）的
 *  `font`/`line-height`/`padding`，此前 D1 降级态用 `<pre>` 实际渲染成 12px 代码框（注释声称
 *  「视觉与正文一致」与事实不符）。 */
const STREAM_RAW_CLASS = 'md-stream-raw'

/**
 * 流式渲染态：增量 parser + 已冻结块缓存 + 引用/脚注状态（+ 长单块降级态）。
 * 每帧只对尾窗重解析；同文本幂等。实例可经模块级注册表跨组件 remount 复用（streamKey）。
 *
 * ⚠ [stream-raw] 2026-09-18 起**生产不再走此路径** —— 流式臂已改为原样纯文本直出
 * （`MarkdownText` 的 `streaming` 分支，见 `STREAM_RAW_CLASS`）。本类保留以支持**一行回退**
 * 与既有单测（`renderer.test.tsx` / D1/D2 用例直接引用 `acquireStreamingRenderer`）。
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
  /** [D1] 是否已退化为纯文本直通（render 零 parse，直出 <pre>）。 */
  private degraded = false

  constructor(readonly onRunHtml: ((code: string) => void) | undefined) {}

  /** [D1] 纯文本降级渲染（pre-wrap 直出 text，跳过 mdast parse 与长 React diff）。 */
  private degradedElement(text: string): ReactNode {
    return <pre className={STREAM_DEGRADE_CLASS}>{text}</pre>
  }

  render(text: string): ReactNode[] {
    if (text === this.lastText) return this.lastRendered
    if (this.degraded) {
      // 退化中零 parse 直通纯文本；仅当文本不再以前帧为前缀（同 key 换新文档的罕见非追加）才复位
      // 走正常增量（update 内 startsWith 失配会 generation++ 自愈）。
      if (this.lastText === null || text.startsWith(this.lastText)) {
        this.lastText = text
        this.lastRendered = [this.degradedElement(text)]
        return this.lastRendered
      }
      this.degraded = false
    }
    const { frozen, tail, generation } = this.parser.update(text)
    if (generation !== this.generation) {
      this.generation = generation
      this.frozenCount = 0
      this.frozenElements = []
      this.frozenTargets = createReferenceTargets()
      this.frozenFootnoteOrder = []
      this.frozenFootnoteCounts = new Map()
      this.degraded = false
    }
    // [D1] 降级判定：无冻结块 && 尾窗仅 1 块 && 累计超长 && 该块是文本承载（paragraph/未闭合 code
    //   fence）→ 退化为纯文本。进入前仅一次全量 parse 判定；此后零 parse（帧率恢复），settled 精排自愈。
    if (frozen.length === 0 && tail.length === 1 && text.length > STREAM_DEGRADE_CHARS) {
      const type = tail[0].node.type
      if (type === 'paragraph' || type === 'code') {
        this.degraded = true
        this.lastText = text
        this.lastRendered = [this.degradedElement(text)]
        return this.lastRendered
      }
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

// [D2] StreamingRenderer 跨 remount 复用注册表：key = streamKey（通常会话:块 id）。
//   切走会话再切回同一流式块 → 直接复用其增量状态（冻结前缀/冻结元素/降级态），不从零 parse 当前
//   全文 —— 与 settled LRU 缓存（renderSettledCached）互补，覆盖「仍流式中」的块。finalize/clear
//   后块不再渲染 → 条目成孤儿，由 LRU 上限淘汰回收（内存有界）。
const STREAM_RENDERER_CACHE_MAX = 128
const streamRenderers = new Map<string, StreamingRenderer>()
/** [D2] 取/建流式渲染器（key=streamKey）。export 仅供单测断言 keyed 复用与 LRU 淘汰。
 *  ⚠ [stream-raw] 2026-09-18 起**生产不再调用本函数**（流式臂已改纯文本直出、`streamKey` 无生产
 *  消费者）—— 保留以支持一行回退，且既有单测（`renderer.test.tsx` D2 段）直接 import 它，
 *  删除会让该文件整体加载失败、把爆炸半径从一条断言扩大到整个文件。 */
export function acquireStreamingRenderer(
  key: string,
  onRunHtml: ((code: string) => void) | undefined,
): StreamingRenderer {
  let r = streamRenderers.get(key)
  // 回调引用变化 → 重建（冻结元素会烘焙 onRunHtml，引用必须稳定；语义同组件内 ref 版）
  if (!r || r.onRunHtml !== onRunHtml) {
    if (r) streamRenderers.delete(key)
    r = new StreamingRenderer(onRunHtml)
    streamRenderers.set(key, r)
    if (streamRenderers.size > STREAM_RENDERER_CACHE_MAX) {
      const oldest = streamRenderers.keys().next().value
      if (oldest !== undefined) streamRenderers.delete(oldest)
    }
  }
  return r
}

export const MarkdownText = memo(function MarkdownText({
  text,
  streaming = false,
  className = 'content md',
  onRunHtml,
}: MarkdownTextProps) {
  const children = useMemo(() => {
    // [stream-raw] 流式臂：原样纯文本直出 —— 不调用 StreamingRenderer、不跑任何 markdown 解析，
    //   输出逐字符等于后端原文（契约由 streamRaw.test.tsx 钉住）。完整渲染由收口臂
    //   （streaming=false 的一次全量）承担。
    //   回退 = 删掉本行、恢复原 `streamKey`/组件 ref 两条分支（StreamingRenderer 与
    //   acquireStreamingRenderer 均原样保留）。
    if (streaming) return <div className={STREAM_RAW_CLASS}>{text}</div>
    return renderSettledCached(text, onRunHtml)
  }, [text, streaming, onRunHtml])
  return <div className={className}>{children}</div>
})
