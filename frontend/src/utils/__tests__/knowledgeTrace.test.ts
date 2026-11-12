import { describe, expect, it } from 'vitest'
import {
  groupPostprocessGraphSpans,
  knowledgeSpansPayloadHasTrace,
  postprocessElapsedMs,
  summarizePostprocessTasks,
  type KnowledgeTraceNode,
  type SpansResponse,
} from '../knowledgeTrace'

/**
 * 用例大部分照抄源站 `utils/knowledgeTrace.test.ts`（它钉的是真值），
 * 另补派单书点名的判据：非 postprocess 返回 null、含子取最晚墙钟、
 * 无匹配保持同一对象引用、有匹配的折叠结构、叶子计数五个数。
 */

function node(overrides: Partial<KnowledgeTraceNode>): KnowledgeTraceNode {
  return { name: 'x', kind: 'subspan', status: 'done', ...overrides }
}

function graphChunk(index: number, overrides: Partial<KnowledgeTraceNode> = {}): KnowledgeTraceNode {
  return {
    span_id: `graph-${index}`,
    parent_span_id: 'postprocess',
    name: `postprocess.graph.chunk[${index}]`,
    kind: 'subspan',
    status: 'done',
    started_at: `2026-07-21T08:00:0${index}.000Z`,
    finished_at: `2026-07-21T08:00:0${index + 2}.000Z`,
    duration_ms: 2000,
    ...overrides,
  }
}

describe('knowledgeSpansPayloadHasTrace · 真 trace vs 历史占位', () => {
  it('有 span_id 或 current_attempt > 0 才算真 trace', () => {
    expect(knowledgeSpansPayloadHasTrace(null)).toBe(false)
    expect(knowledgeSpansPayloadHasTrace(undefined)).toBe(false)
    expect(knowledgeSpansPayloadHasTrace({})).toBe(false)
    expect(knowledgeSpansPayloadHasTrace({ trace: {} })).toBe(false)
    expect(knowledgeSpansPayloadHasTrace({ trace: { span_id: 'abc' } })).toBe(true)
    expect(knowledgeSpansPayloadHasTrace({ trace: {}, current_attempt: 2 })).toBe(true)
  })
})

describe('postprocessElapsedMs · 含子墙钟', () => {
  const started = '2026-10-25T00:00:00.000Z'
  const afterEnqueue = Date.parse('2026-10-25T00:02:00.000Z')

  it('非 postprocess 阶段一律返回 null（源站刻意限制）', () => {
    const stage = node({
      name: 'chunking',
      started_at: started,
      finished_at: '2026-10-25T00:00:00.032Z',
      duration_ms: 32,
      children: [node({ name: 'chunking.child', started_at: '2026-10-25T00:00:01.000Z' })],
    })
    expect(postprocessElapsedMs(stage, afterEnqueue)).toBeNull()
  })

  it('子 span 比父晚结束 ⇒ 取到最晚子的墙钟，不是父的 duration_ms', () => {
    const stage = node({
      name: 'postprocess',
      status: 'done',
      started_at: started,
      finished_at: '2026-10-25T00:00:00.032Z',
      duration_ms: 32,
      children: [node({ name: 'postprocess.wiki', status: 'running', started_at: '2026-10-25T00:00:01.000Z' })],
    })

    expect(postprocessElapsedMs(stage, afterEnqueue)).toBe(120_000)
    expect(stage.duration_ms, '落库的阶段时长仍然只测入队工作').toBe(32)

    stage.children![0].status = 'done'
    stage.children![0].finished_at = '2026-10-25T00:03:00.000Z'
    expect(postprocessElapsedMs(stage, afterEnqueue + 60_000)).toBe(180_000)
  })

  it('排队中的 worker 不能把阶段算成已完成', () => {
    const stage = node({
      name: 'postprocess',
      status: 'done',
      started_at: started,
      finished_at: '2026-10-25T00:00:00.032Z',
      duration_ms: 32,
      children: [node({ name: 'postprocess.wiki', status: 'pending' })],
    })
    expect(postprocessElapsedMs(stage, afterEnqueue)).toBe(120_000)
  })

  it('没有异步子的阶段保持自己的时长（返回 null）', () => {
    const stage = node({
      name: 'postprocess',
      status: 'done',
      started_at: started,
      finished_at: '2026-10-25T00:00:00.032Z',
      duration_ms: 32,
      children: [],
    })
    expect(postprocessElapsedMs(stage, afterEnqueue)).toBeNull()
  })
})

describe('groupPostprocessGraphSpans · 折叠 graph 分块', () => {
  it('无 postprocess.graph.chunk[i] ⇒ 原样返回同一对象引用', () => {
    const stage = node({
      span_id: 'postprocess',
      name: 'postprocess',
      kind: 'stage',
      children: [node({ name: 'postprocess.summary' })],
    })
    expect(groupPostprocessGraphSpans(stage)).toBe(stage)
  })

  it('有匹配 ⇒ 插一个 group，chunk_count = 原 chunk 数，原 chunk 变它的 children', () => {
    const summary = node({ span_id: 'summary', name: 'postprocess.summary' })
    const chunk0 = graphChunk(0)
    const chunk1 = graphChunk(1)
    const stage = node({
      span_id: 'postprocess',
      name: 'postprocess',
      kind: 'stage',
      children: [summary, chunk0, chunk1],
    })

    const grouped = groupPostprocessGraphSpans(stage)
    expect(grouped).not.toBe(stage)
    expect(grouped.children?.length).toBe(2)
    expect(grouped.children?.[0]).toBe(summary)

    const graph = grouped.children?.[1]
    expect(graph?.name).toBe('postprocess.graph')
    expect(graph?.kind).toBe('group')
    expect(graph?.status).toBe('done')
    // 首个 worker 开始 → 末个 worker 结束
    expect(graph?.duration_ms).toBe(3000)
    expect(graph?.input).toEqual({ chunk_count: 2 })
    expect(graph?.children?.length).toBe(2)
    expect(graph?.children?.[0]).toBe(chunk0)
    expect(graph?.children?.[1]).toBe(chunk1)
    expect(graph?.output).toEqual({ chunk_count: 2, status_counts: { done: 2 } })
  })

  it('形状不匹配的名字不折叠（只认 ^postprocess\\.graph\\.chunk\\[i\\]$）', () => {
    const stage = node({
      span_id: 'postprocess',
      name: 'postprocess',
      kind: 'stage',
      children: [
        node({ name: 'postprocess.graph.chunk' }),
        node({ name: 'postprocess.graph.chunk[x]' }),
        node({ name: 'xx.postprocess.graph.chunk[0]' }),
        node({ name: 'postprocess.graph.chunk[0].extra' }),
      ],
    })
    expect(groupPostprocessGraphSpans(stage)).toBe(stage)
  })

  it('任一 chunk 在跑 ⇒ group 保持 running，且不给 duration', () => {
    const stage = node({
      span_id: 'postprocess',
      name: 'postprocess',
      kind: 'stage',
      children: [
        graphChunk(0),
        graphChunk(1, { status: 'running', finished_at: null, duration_ms: undefined }),
      ],
    })
    const graph = groupPostprocessGraphSpans(stage).children?.[0]
    expect(graph?.status).toBe('running')
    expect(graph?.finished_at).toBeNull()
    expect(graph?.duration_ms).toBeUndefined()
  })

  it('失败的 chunk 会冒泡到聚合行', () => {
    const stage = node({
      span_id: 'postprocess',
      name: 'postprocess',
      kind: 'stage',
      children: [graphChunk(0), graphChunk(1, { status: 'failed' })],
    })
    const graph = groupPostprocessGraphSpans(stage).children?.[0]
    expect(graph?.status).toBe('failed')
    expect(graph?.duration_ms).toBe(3000)
  })
})

describe('summarizePostprocessTasks · 叶子五数', () => {
  it('叶子计数：running / failed / completed / other / total', () => {
    const trace = node({
      name: 'root',
      kind: 'root',
      children: [
        node({
          name: 'postprocess',
          kind: 'stage',
          children: [
            node({ name: 'postprocess.summary', status: 'done' }),
            node({ name: 'postprocess.question', status: 'running' }),
            node({
              name: 'postprocess.graph',
              kind: 'group',
              status: 'failed',
              children: [
                node({ name: 'postprocess.graph.chunk[0]', status: 'failed' }),
                node({ name: 'postprocess.graph.chunk[1]', status: 'pending' }),
              ],
            }),
            node({ name: 'postprocess.weird', status: 'skipped' }),
          ],
        }),
      ],
    })

    // 只数叶子：summary(done) / question(running) / chunk0(failed) / chunk1(pending) / weird(skipped)
    expect(summarizePostprocessTasks(trace)).toEqual({
      running: 2,
      failed: 1,
      completed: 1,
      other: 1,
      total: 5,
    })
  })

  it('五个主管线阶段的状态词表与 span 词表对齐', () => {
    const trace = node({
      name: 'root',
      kind: 'root',
      children: [
        node({
          name: 'postprocess',
          children: [
            node({ name: 'a', status: 'processing' }),
            node({ name: 'b', status: 'finalizing' }),
            node({ name: 'c', status: 'completed' }),
            node({ name: 'd', status: 'cancelled' }),
          ],
        }),
      ],
    })
    expect(summarizePostprocessTasks(trace)).toEqual({
      running: 2,
      failed: 0,
      completed: 1,
      other: 1,
      total: 4,
    })
  })

  it('没有 trace / 没有 postprocess ⇒ 全零', () => {
    const empty = { running: 0, failed: 0, completed: 0, other: 0, total: 0 }
    expect(summarizePostprocessTasks(undefined)).toEqual(empty)
    expect(summarizePostprocessTasks(node({ name: 'root', kind: 'root', children: [] }))).toEqual(empty)
  })

  it('trace 本身就是 postprocess 时直接数（不必再往下找一层）', () => {
    const postprocess = node({
      name: 'postprocess',
      kind: 'stage',
      children: [node({ name: 'a', status: 'done' })],
    })
    expect(summarizePostprocessTasks(postprocess).total).toBe(1)
  })
})

describe('SpansResponse · 后端契约键名（编译期钉住 snake_case）', () => {
  it('snake_case 字段齐全，last_error 复用 name/error_code/error_message', () => {
    const sample: SpansResponse = {
      knowledge_id: 'k1',
      attempt: 2,
      latest_attempt: 2,
      parse_status: 'processing',
      current_attempt: 2,
      current_stage: 'postprocess',
      trace: node({ span_id: 'root', name: 'root', kind: 'root' }),
      last_activity_at: '2026-11-12T00:00:00Z',
      stall_state: 'stalled',
      last_error: {
        name: 'postprocess',
        error_code: 'E_PARSE',
        error_message: 'boom',
        finished_at: '2026-11-12T00:01:00Z',
      },
    }
    expect(sample.knowledge_id).toBe('k1')
    expect(sample.last_error?.error_code).toBe('E_PARSE')
  })
})
