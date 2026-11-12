/**
 * 知识处理 trace 的纯函数（源站 `utils/knowledgeTrace.ts` 逐字移植）。
 *
 * <h2>为什么这些函数值得单独立出来</h2>
 * 处理时间线要展示的是「阶段 + 异步子任务」的墙钟时间，但后端落库的是 span 树：
 * `postprocess` 阶段在入队完成时就写 `finished_at`，而它的 worker 还能再跑几分钟。
 * ⇒ 直接读 `duration_ms` 会把「跑了两分钟」显示成 32ms。这里保留三件事：
 * <ol>
 *   <li>{@link postprocessElapsedMs} 只对 `postprocess` 生效，按「最晚子 span」算墙钟；</li>
 *   <li>{@link groupPostprocessGraphSpans} 把 `postprocess.graph.chunk[i]` 折叠成一行；</li>
 *   <li>{@link summarizePostprocessTasks} 数叶子任务，区分五个主管线阶段与异步富化。</li>
 * </ol>
 *
 * ⚠️ 字段名全是 <b>snake_case</b>：后端 `KnowledgeSpansData` 就是这么返回的，
 * 改成 camelCase 会全空。
 */

/** GET /knowledge/:id/spans 返回的是否是真实 trace（而不是只有历史占位）。 */
export function knowledgeSpansPayloadHasTrace(
  data: { trace?: { span_id?: string }; current_attempt?: number } | null | undefined,
): boolean {
  if (!data?.trace) return false
  return !!(data.trace.span_id || (data.current_attempt ?? 0) > 0)
}

/** trace 树节点。字段名 = 后端契约，⛔ 不要改成 camelCase。 */
export interface KnowledgeTraceNode {
  span_id?: string
  parent_span_id?: string
  name: string
  kind: string
  status: string
  started_at?: string | null
  finished_at?: string | null
  duration_ms?: number
  error_code?: string
  error_message?: string
  input?: unknown
  output?: unknown
  metadata?: unknown
  children?: KnowledgeTraceNode[]
}

/** 异步 postprocess 叶子任务的计数。 */
export interface PostprocessTaskSummary {
  running: number
  failed: number
  completed: number
  other: number
  total: number
}

/** `last_error` 的载荷（对应后端 `KnowledgeSpanError` 的重复字段）。 */
export interface LastError {
  name: string
  error_code: string
  error_message: string
  finished_at?: string | null
}

/**
 * GET /knowledge/{id}/spans 的 `data` 对象（对应后端 `KnowledgeSpansData`，
 * `internal/handler/knowledge.go:792-807`）。
 *
 * ⚠️ 键名必须 snake_case —— 这是后端契约。
 */
export interface SpansResponse {
  knowledge_id: string
  /** trace 描述的那一次尝试；与 `current_attempt` 相等。 */
  attempt: number
  latest_attempt: number
  parse_status: string
  current_attempt: number
  /** 当前正在跑的阶段，或最后一个跑过的阶段。 */
  current_stage: string
  trace?: KnowledgeTraceNode | null
  /** 解析在途时才有值。 */
  last_activity_at?: string | null
  /** `queued` / `stalled`：在途解析久无进展时才有值。 */
  stall_state?: string
  last_error?: LastError
}

/**
 * 图分块名只认一种形状：`postprocess.graph.chunk[` + 数字 + `]`。
 * ⚠️ 锚定且只认一种形状 —— 放宽会让 `xxx.postprocess.graph.chunk[0]` 之类也被折叠。
 */
const graphChunkName = /^postprocess\.graph\.chunk\[(\d+)\]$/

/** ISO 时间串 → 毫秒时间戳；缺失或非法 ⇒ null。 */
function timestamp(value?: string | null): number | null {
  if (!value) return null
  const parsed = Date.parse(value)
  return Number.isNaN(parsed) ? null : parsed
}

/** 节点的结束时刻：先认 `finished_at`，没有再退化成 `started_at + duration_ms`。 */
function nodeEnd(node: KnowledgeTraceNode): number | null {
  const finished = timestamp(node.finished_at)
  if (finished !== null) return finished
  const started = timestamp(node.started_at)
  if (started !== null && typeof node.duration_ms === 'number' && node.duration_ms >= 0) {
    return started + node.duration_ms
  }
  return null
}

/**
 * postprocess 阶段落库的结束时刻是「入队完成」，而它的 worker 还能再跑几分钟。
 * 这里返回「到最晚异步后代」的墙钟窗口，作为该阶段的含子显示时长。
 * `duration_ms` 保持原样（详情里仍要能看到入队耗时）。
 *
 * ⚠️ 只对 `name === 'postprocess'` 生效 —— 源站刻意限制，别放宽到所有阶段。
 */
export function postprocessElapsedMs(stage: KnowledgeTraceNode, now: number): number | null {
  if (stage.name !== 'postprocess') return null
  const start = timestamp(stage.started_at)
  if (start === null || !stage.children?.length) return null

  let latest = nodeEnd(stage) ?? start
  let hasTimedChild = false
  const visit = (node: KnowledgeTraceNode) => {
    const childStart = timestamp(node.started_at)
    const end = node.status === 'running' || node.status === 'pending'
      ? now
      : nodeEnd(node)
    if (end !== null && (childStart !== null || node.status === 'pending')) {
      latest = Math.max(latest, end)
      hasTimedChild = true
    }
    node.children?.forEach(visit)
  }
  stage.children.forEach(visit)
  return hasTimedChild ? Math.max(0, latest - start) : null
}

/** 聚合状态判定的<b>顺序</b>是语义的一部分，⛔ 不要调换。 */
function aggregateStatus(nodes: KnowledgeTraceNode[]): string {
  if (nodes.some(node => node.status === 'running' || node.status === 'pending')) return 'running'
  if (nodes.some(node => node.status === 'failed')) return 'failed'
  if (nodes.every(node => node.status === 'skipped')) return 'skipped'
  if (nodes.some(node => node.status === 'cancelled')) return 'cancelled'
  return 'done'
}

/**
 * 把落库的 `postprocess.graph.chunk[i]` span 折叠成一个派生的 graph 节点。
 * 派生的 duration 是「首个 graph worker 开始 → 最后一个 worker 结束」的墙钟；
 * 子节点保留每块的细节。
 *
 * ⚠️ 没有匹配 ⇒ 返回<b>同一个对象引用</b>（调用方靠引用相等判「没变」）。
 */
export function groupPostprocessGraphSpans(
  stage: KnowledgeTraceNode,
): KnowledgeTraceNode {
  const children = stage.children || []
  const graphChildren = children.filter(child => graphChunkName.test(child.name))
  if (graphChildren.length === 0) return stage

  const starts = graphChildren
    .map(child => timestamp(child.started_at))
    .filter((value): value is number => value !== null)
  const ends = graphChildren
    .map(nodeEnd)
    .filter((value): value is number => value !== null)
  const status = aggregateStatus(graphChildren)
  const start = starts.length > 0 ? Math.min(...starts) : null
  const terminal = status !== 'running'
  const end = terminal && ends.length > 0 ? Math.max(...ends) : null
  const counts = graphChildren.reduce<Record<string, number>>((result, child) => {
    result[child.status] = (result[child.status] || 0) + 1
    return result
  }, {})

  const group: KnowledgeTraceNode = {
    span_id: `virtual:postprocess.graph:${stage.span_id || 'stage'}`,
    parent_span_id: stage.span_id,
    name: 'postprocess.graph',
    kind: 'group',
    status,
    started_at: start === null ? null : new Date(start).toISOString(),
    finished_at: end === null ? null : new Date(end).toISOString(),
    duration_ms: start !== null && end !== null ? Math.max(0, end - start) : undefined,
    input: { chunk_count: graphChildren.length },
    output: { chunk_count: graphChildren.length, status_counts: counts },
    children: graphChildren,
  }

  let inserted = false
  const groupedChildren: KnowledgeTraceNode[] = []
  for (const child of children) {
    if (graphChunkName.test(child.name)) {
      if (!inserted) {
        groupedChildren.push(group)
        inserted = true
      }
      continue
    }
    groupedChildren.push(child)
  }

  return { ...stage, children: groupedChildren }
}

/**
 * 数 postprocess 的<b>叶子</b> span，让界面能把五个主管线阶段与异步富化工作分开。
 */
export function summarizePostprocessTasks(
  trace?: KnowledgeTraceNode,
): PostprocessTaskSummary {
  const summary: PostprocessTaskSummary = {
    running: 0,
    failed: 0,
    completed: 0,
    other: 0,
    total: 0,
  }
  if (!trace) return summary

  const postprocess = trace.name === 'postprocess'
    ? trace
    : (trace.children || []).find(child => child.name === 'postprocess')
  if (!postprocess) return summary

  const countLeaves = (node: KnowledgeTraceNode) => {
    const children = node.children || []
    if (children.length > 0) {
      children.forEach(countLeaves)
      return
    }

    summary.total++
    switch (node.status) {
      case 'running':
      case 'pending':
      case 'processing':
      case 'finalizing':
        summary.running++
        break
      case 'failed':
        summary.failed++
        break
      case 'done':
      case 'completed':
        summary.completed++
        break
      default:
        summary.other++
    }
  }

  ;(postprocess.children || []).forEach(countLeaves)
  return summary
}
