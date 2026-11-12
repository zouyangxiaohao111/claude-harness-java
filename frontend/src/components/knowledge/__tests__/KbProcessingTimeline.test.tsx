// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { KnowledgeTraceNode, SpansResponse } from '@/utils/knowledgeTrace'
import { buildAxisTicks, computeTraceAxis, formatAxisLabel } from '@/utils/traceAxis'
import {
  KbProcessingTimeline,
  formatParserRules,
  normalizeFileType,
  parseProcessOverrides,
  processConfigLines,
  type FetchSpansFn,
  type KbProcessingTimelineProps,
  type KnowledgeProcessOverrides,
  type ParserEngineRule,
} from '../KbProcessingTimeline'
import { isPollingStatus } from '@/utils/knowledgeProcessingStatus'

// ── 详情 / 停止解析这两个端点一律 mock（⛔ 不发真请求）──────────────────────────
const getKnowledge = vi.fn()
const cancelParse = vi.fn()

vi.mock('@/api/knowledge', () => ({
  knowledgeApi: {
    getKnowledge: (...a: unknown[]) => getKnowledge(...a),
    cancelParse: (...a: unknown[]) => cancelParse(...a),
  },
}))

/**
 * `KbProcessingTimeline` 的 12 条接线判据（派单 R2-B §3.2）。
 *
 * <h2>为什么这些用例必须真的渲染 + 真的点</h2>
 * 本组件移植的是源站 3791 行的瀑布图，最容易错的三层都不在「数据对不对」上：
 * <ol>
 *   <li><b>DOM / 类名</b>：批 5 的 CSS 按源站类名写，类名错一个字就是「有数据但白屏」；</li>
 *   <li><b>折叠 / 展开</b>：`expandedRows` 的 gate 少一处，点箭头只会转方向、树纹丝不动；</li>
 *   <li><b>轮询覆盖用户选择</b>：auto-expand 若不看 `userToggledRows`，用户收起一次、
 *       2 秒后又自己弹开 —— 这是源站注释里点名修过的旧 bug。</li>
 * </ol>
 *
 * <p>环境与写法沿用本仓既有模式（jsdom + `createRoot` + `act`，不引入
 * `@testing-library/react`，本仓也没有这个依赖）：见 `KbUploadSource.itemRetry.test.tsx`。
 * 取数一律注入假的 `fetchSpans`，⛔ 不发真请求。
 */

let container: HTMLDivElement
let root: Root

beforeEach(() => {
  ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  // jsdom 没实现 scrollIntoView；选中行时会调用它（源站是 scrollIntoView({block:'nearest'})）。
  Element.prototype.scrollIntoView = () => {}
  // 默认：详情接口成功但没有任何 process_overrides ⇒ 弹层只有「使用知识库默认配置」。
  getKnowledge.mockReset()
  cancelParse.mockReset()
  getKnowledge.mockResolvedValue({ metadata: null, file_type: 'pdf', file_name: 'a.pdf', title: 'a' })
  cancelParse.mockResolvedValue(undefined)
})

afterEach(() => {
  act(() => root.unmount())
  container.remove()
  vi.restoreAllMocks()
})

/** 让挂起的 promise 链落地（fetch → setState → 派生 → 渲染）。 */
async function flush(): Promise<void> {
  for (let i = 0; i < 4; i++) {
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0))
    })
  }
}

function node(partial: Partial<KnowledgeTraceNode> & { name: string }): KnowledgeTraceNode {
  return { kind: 'span', status: 'done', ...partial }
}

function stage(name: string, partial: Partial<KnowledgeTraceNode> = {}): KnowledgeTraceNode {
  return { kind: 'stage', status: 'done', ...partial, name }
}

/** 一份完整的 `KnowledgeSpansData`（字段名是后端契约，snake_case）。 */
function spans(over: Partial<SpansResponse> = {}): SpansResponse {
  return {
    knowledge_id: 'k1',
    attempt: 1,
    latest_attempt: 1,
    parse_status: 'completed',
    current_attempt: 1,
    current_stage: '',
    trace: null,
    ...over,
  }
}

async function mount(
  props: Partial<KbProcessingTimelineProps> = {},
  impl?: FetchSpansFn,
): Promise<ReturnType<typeof vi.fn>> {
  const fetchSpans = vi.fn(impl ?? (async () => ({ success: true, data: spans() })))
  await act(async () => {
    root.render(<KbProcessingTimeline knowledgeId="k1" fetchSpans={fetchSpans} {...props} />)
  })
  await flush()
  return fetchSpans
}

function rows(): HTMLElement[] {
  return Array.from(container.querySelectorAll<HTMLElement>('.kp-row'))
}

function rowByKey(key: string): HTMLElement {
  const el = container.querySelector<HTMLElement>(`.kp-row[data-span-key="${key}"]`)
  if (!el) throw new Error(`找不到 data-span-key=${key} 的行`)
  return el
}

function click(el: Element | null): void {
  if (!el) throw new Error('要点的元素不存在')
  el.dispatchEvent(new MouseEvent('click', { bubbles: true }))
}

// 固定时基：所有 ISO 串都从这里偏移，避免 Date.now() 抖动影响轴计算。
const T0 = Date.parse('2026-11-12T10:00:00.000Z')
function iso(offsetMs: number): string {
  return new Date(T0 + offsetMs).toISOString()
}

/** 10s 的轴 + 一个 1ms 的 docreader + 一个 1s 的 chunking（#4 / #5 / #6 共用）。 */
function tenSecondTrace(): SpansResponse {
  return spans({
    trace: {
      span_id: 'root',
      name: 'knowledge_processing',
      kind: 'root',
      status: 'done',
      started_at: iso(0),
      finished_at: iso(10000),
      duration_ms: 10000,
      children: [
        stage('docreader', {
          span_id: 's-doc',
          started_at: iso(0),
          finished_at: iso(1),
          duration_ms: 1,
        }),
        stage('chunking', {
          span_id: 's-chunk',
          started_at: iso(9000),
          finished_at: iso(10000),
          duration_ms: 1000,
        }),
      ],
    },
  })
}

describe('KbProcessingTimeline · 空态', () => {
  it('#1a 端点成功但 trace 里没有阶段 ⇒ 恒定的五段，且每段都是「等待中」', async () => {
    await mount({ autoPoll: false }, async () => ({
      success: true,
      data: spans({
        parse_status: 'pending',
        current_stage: 'docreader',
        trace: {
          span_id: 'root',
          name: 'knowledge_processing',
          kind: 'root',
          status: 'pending',
          started_at: iso(0),
          children: [],
        },
      }),
    }))

    const stageRows = container.querySelectorAll<HTMLElement>('.kp-row-stage')
    expect(stageRows).toHaveLength(5)
    for (const row of Array.from(stageRows)) {
      const dot = row.querySelector('.kp-status-dot')
      expect(dot?.className).toContain('kp-dot-pending')
    }
    // 头部徽标把 pending 翻成「等待中」（源站 knowledgeStages.status.pending）
    expect(container.querySelector('.kp-head-status-tag')?.textContent).toBe('等待中')
    expect(container.textContent).toContain('等待中')
  })

  it('#1b 取数返回 success=false（无原因）⇒ 「暂无解析记录」，不抛、不留空白', async () => {
    await mount({ autoPoll: false }, async () => ({ success: false }))
    const state = container.querySelector('.kp-state-empty')
    expect(state).not.toBeNull()
    expect(state?.textContent).toContain('暂无解析记录')
    expect(rows()).toHaveLength(0)
  })

  it('#1c 取数带回失败原因 ⇒ 「处理记录加载失败：{原因}」', async () => {
    await mount({ autoPoll: false }, async () => ({ success: false, message: '网关 502' }))
    const state = container.querySelector('.kp-state-empty')
    expect(state?.textContent).toContain('处理记录加载失败：网关 502')
    expect(state?.textContent).not.toContain('暂无解析记录')
  })
})

describe('KbProcessingTimeline · 五段恒定', () => {
  it('#2 后端只发两段 ⇒ 界面仍然渲染五行 stage，且顺序固定', async () => {
    await mount({ autoPoll: false }, async () => ({ success: true, data: tenSecondTrace() }))

    const labels = Array.from(container.querySelectorAll('.kp-row-stage .kp-name-text')).map(
      (el) => el.textContent,
    )
    expect(labels).toEqual(['文档解析', '分块', '向量化', '多模态识别', '后处理'])
  })
})

describe('KbProcessingTimeline · 终态占位', () => {
  it('#3 parse_status=failed 且某段没有 span_id ⇒ 显示「未执行」而不是「等待中」', async () => {
    await mount({ autoPoll: false }, async () => ({
      success: true,
      data: spans({
        parse_status: 'failed',
        trace: {
          span_id: 'root',
          name: 'knowledge_processing',
          kind: 'root',
          status: 'failed',
          started_at: iso(0),
          finished_at: iso(30),
          duration_ms: 30,
          children: [
            stage('docreader', {
              span_id: 's-doc',
              status: 'failed',
              started_at: iso(0),
              finished_at: iso(30),
              duration_ms: 30,
              error_code: 'DOCREADER_PARSE_FAILED',
              error_message: 'Unsupported file type: htm',
            }),
          ],
        },
      }),
    }))

    // 终态后仍标 pending 的合成段 = 永远没跑 ⇒ 写「未执行」（源站 isNotRun）
    expect(rowByKey('stage:chunking').querySelector('.kp-cell-dur')?.textContent).toContain('未执行')
    expect(rowByKey('stage:postprocess').querySelector('.kp-cell-dur')?.textContent).toContain(
      '未执行',
    )
    // 真正跑过并失败的那段不能被当成「未执行」
    expect(rowByKey('s-doc').querySelector('.kp-cell-dur')?.textContent).not.toContain('未执行')
    expect(rowByKey('s-doc').querySelector('.kp-status-dot')?.className).toContain('kp-dot-failed')
  })
})

describe('KbProcessingTimeline · 条形几何', () => {
  it('#4 极短 span 的宽度下限是 0.4%（不是按真实比例的 0.01%）', async () => {
    await mount({ autoPoll: false }, async () => ({ success: true, data: tenSecondTrace() }))

    const bar = rowByKey('s-doc').querySelector<HTMLElement>('.kp-bar')
    expect(bar).not.toBeNull()
    // 1ms / 10000ms = 0.01% ⇒ 下限 0.4%
    expect(bar?.style.width).toBe('0.4%')
    expect(bar?.style.left).toBe('0%')
  })

  it('#5 任何条形的 left + width 都不超过 100%', async () => {
    await mount({ autoPoll: false }, async () => ({ success: true, data: tenSecondTrace() }))

    const bars = Array.from(container.querySelectorAll<HTMLElement>('.kp-bar'))
    expect(bars.length).toBeGreaterThan(0)
    let visible = 0
    for (const bar of bars) {
      if (bar.style.display === 'none' || !bar.style.width) continue
      visible++
      const left = Number.parseFloat(bar.style.left || '0')
      const width = Number.parseFloat(bar.style.width)
      expect(left + width).toBeLessThanOrEqual(100.0001)
    }
    expect(visible).toBeGreaterThan(0)
  })
})

describe('KbProcessingTimeline · 刻度尺', () => {
  it('#6 刻度数 = buildAxisTicks 的结果，末刻度 label = formatAxisLabel(maxMs, maxMs)', async () => {
    await mount({ autoPoll: false }, async () => ({ success: true, data: tenSecondTrace() }))

    const axis = computeTraceAxis(10000)
    const expected = buildAxisTicks(axis)
    const ticks = container.querySelectorAll('.kp-tick')
    expect(ticks).toHaveLength(expected.length)

    const labels = Array.from(container.querySelectorAll('.kp-tick-label')).map(
      (el) => el.textContent,
    )
    expect(labels[labels.length - 1]).toBe(formatAxisLabel(axis.maxMs, axis.maxMs))
    expect(labels[labels.length - 1]).toBe('10s')
  })

  it('#7 totalMs < 50 ⇒ 不画标尺，轨道去掉网格', async () => {
    await mount({ autoPoll: false }, async () => ({
      success: true,
      data: spans({
        trace: {
          span_id: 'root',
          name: 'knowledge_processing',
          kind: 'root',
          status: 'done',
          started_at: iso(0),
          finished_at: iso(20),
          duration_ms: 20,
          children: [
            stage('docreader', {
              span_id: 's-doc',
              started_at: iso(0),
              finished_at: iso(10),
              duration_ms: 10,
            }),
          ],
        },
      }),
    }))

    expect(container.querySelector('.kp-ruler')).toBeNull()
    expect(container.querySelector('.kp-rows')?.className).toContain('kp-rows-gridless')
  })
})

describe('KbProcessingTimeline · postprocess 含子任务', () => {
  it('#8 子 span 比父晚结束 ⇒ 显示含子任务时长，并带「含子任务」提示', async () => {
    await mount({ autoPoll: false }, async () => ({
      success: true,
      data: spans({
        trace: {
          span_id: 'root',
          name: 'knowledge_processing',
          kind: 'root',
          status: 'done',
          started_at: iso(0),
          finished_at: iso(1000),
          duration_ms: 1000,
          children: [
            stage('postprocess', {
              span_id: 's-post',
              started_at: iso(100),
              finished_at: iso(109),
              duration_ms: 9,
              children: [
                node({
                  span_id: 's-sum',
                  name: 'postprocess.summary',
                  started_at: iso(150),
                  finished_at: iso(900),
                  duration_ms: 750,
                }),
              ],
            }),
          ],
        },
      }),
    }))

    const dur = rowByKey('s-post').querySelector<HTMLElement>('.kp-cell-dur span')
    // 父段 9ms 就收口，子任务跑到 900ms ⇒ 含子显示 800ms（900 - 100）
    expect(dur?.textContent).toBe('800ms')
    expect(dur?.title).toBe('含子任务')
  })
})

describe('KbProcessingTimeline · 详情面板', () => {
  it('#9 点行 ⇒ 详情面板出现，且标题就是那一行', async () => {
    await mount({ autoPoll: false }, async () => ({ success: true, data: tenSecondTrace() }))

    // 打开即自动选中：没有失败行 ⇒ 选 root（源站 autoSelectSpan）
    expect(container.querySelector('.kp-detail-name')?.textContent).toBe('知识处理')

    await act(async () => {
      click(rowByKey('s-chunk'))
      await new Promise((resolve) => setTimeout(resolve, 0))
    })
    await flush()

    expect(container.querySelector('.kp-detail')?.className).toContain('kp-detail-open')
    expect(container.querySelector('.kp-detail-name')?.textContent).toBe('分块')
    expect(container.querySelector('.kp-detail-head')?.textContent).toContain('已完成')
  })
})

/** docreader 带一个子 span 的树（#10 / #11 共用）。 */
function traceWithChild(): SpansResponse {
  return spans({
    trace: {
      span_id: 'root',
      name: 'knowledge_processing',
      kind: 'root',
      status: 'done',
      started_at: iso(0),
      finished_at: iso(1000),
      duration_ms: 1000,
      children: [
        stage('docreader', {
          span_id: 's-doc',
          started_at: iso(0),
          finished_at: iso(800),
          duration_ms: 800,
          children: [
            node({
              span_id: 's-doc-step',
              name: 'docreader.step',
              started_at: iso(10),
              finished_at: iso(700),
              duration_ms: 690,
            }),
          ],
        }),
      ],
    },
  })
}

describe('KbProcessingTimeline · 折叠 / 展开', () => {
  it('#10 点 .kp-tree-toggle ⇒ 子行消失，再点回来', async () => {
    await mount({ autoPoll: false }, async () => ({ success: true, data: traceWithChild() }))

    // 有子节点 ⇒ auto-expand 展开（root + 5 stage + 1 子 span）
    expect(rows()).toHaveLength(7)
    expect(container.querySelector('[data-span-key="s-doc-step"]')).not.toBeNull()

    await act(async () => {
      click(rowByKey('s-doc').querySelector('.kp-tree-toggle'))
      await new Promise((resolve) => setTimeout(resolve, 0))
    })
    expect(rows()).toHaveLength(6)
    expect(container.querySelector('[data-span-key="s-doc-step"]')).toBeNull()

    await act(async () => {
      click(rowByKey('s-doc').querySelector('.kp-tree-toggle'))
      await new Promise((resolve) => setTimeout(resolve, 0))
    })
    expect(rows()).toHaveLength(7)
    expect(container.querySelector('[data-span-key="s-doc-step"]')).not.toBeNull()
  })

  it('#11 用户收起后，下一次取数不许把它弹开', async () => {
    const fetchSpans = await mount({ autoPoll: false }, async () => ({
      success: true,
      data: traceWithChild(),
    }))

    await act(async () => {
      click(rowByKey('s-doc').querySelector('.kp-tree-toggle'))
      await new Promise((resolve) => setTimeout(resolve, 0))
    })
    expect(rows()).toHaveLength(6)

    // 手动刷新 = 再取一次数（轮询的 auto-expand 走的是同一段逻辑）
    await act(async () => {
      click(container.querySelector('button[aria-label="立即刷新"]'))
      await new Promise((resolve) => setTimeout(resolve, 0))
    })
    await flush()

    expect(fetchSpans).toHaveBeenCalledTimes(2)
    expect(rows()).toHaveLength(6)
    expect(container.querySelector('[data-span-key="s-doc-step"]')).toBeNull()
  })
})

describe('isPollingStatus · 五值真值表（源站 :225-230）', () => {
  it('pending / processing / finalizing ⇒ true', () => {
    expect(isPollingStatus('pending')).toBe(true)
    expect(isPollingStatus('processing')).toBe(true)
    expect(isPollingStatus('finalizing')).toBe(true)
  })

  it('completed / failed ⇒ false；空 / undefined ⇒ false', () => {
    expect(isPollingStatus('completed')).toBe(false)
    expect(isPollingStatus('failed')).toBe(false)
    expect(isPollingStatus('cancelled')).toBe(false)
    expect(isPollingStatus('')).toBe(false)
    expect(isPollingStatus(undefined)).toBe(false)
  })
})

describe('parseProcessOverrides · metadata 容错五档', () => {
  it('null / 空串 ⇒ null', () => {
    expect(parseProcessOverrides(null)).toBeNull()
    expect(parseProcessOverrides(undefined)).toBeNull()
    expect(parseProcessOverrides('')).toBeNull()
  })

  it("'{}' ⇒ null（没有 process_overrides）", () => {
    expect(parseProcessOverrides('{}')).toBeNull()
    expect(parseProcessOverrides('{"process_overrides":null}')).toBeNull()
  })

  it('坏 JSON ⇒ null（不抛）', () => {
    expect(parseProcessOverrides('{not json')).toBeNull()
    expect(parseProcessOverrides('[]')).toBeNull()
    expect(parseProcessOverrides('"str"')).toBeNull()
  })

  it('正常对象 ⇒ 取出 process_overrides', () => {
    const parsed = parseProcessOverrides(
      JSON.stringify({ process_overrides: { summary_enabled: false } }),
    )
    expect(parsed).toEqual({ summary_enabled: false })
  })

  it('process_overrides 是空对象 ⇒ 返回空对象（后续落回 kbDefault）', () => {
    expect(parseProcessOverrides('{"process_overrides":{}}')).toEqual({})
  })
})

describe('formatParserRules · 当前类型命中 / 未命中 / 空', () => {
  const rules: ParserEngineRule[] = [
    { file_types: ['pdf', '.PDF'], engine: 'mineru' },
    { file_types: ['docx', 'doc'], engine: 'anydoc' },
  ]

  it('命中当前类型 ⇒ `type→engine`（类型先归一）', () => {
    expect(formatParserRules(rules, 'pdf')).toBe('pdf→mineru')
    expect(formatParserRules(rules, '.PDF')).toBe('pdf→mineru')
    expect(formatParserRules(rules, 'DOCX')).toBe('docx→anydoc')
  })

  it('未命中 ⇒ 全列表 `a/b→engine, ...`', () => {
    // 未命中的回退分支按**原始** `file_types` 拼接（不归一），与源站 `join('/')` 一致
    expect(formatParserRules(rules, 'xlsx')).toBe('pdf/.PDF→mineru, docx/doc→anydoc')
  })

  it('空数组 ⇒ 空串', () => {
    expect(formatParserRules([], 'pdf')).toBe('')
  })
})

describe('normalizeFileType · 去点 / 去空白 / 小写', () => {
  it('三种写法归一到同一值', () => {
    expect(normalizeFileType('.PDF')).toBe('pdf')
    expect(normalizeFileType('  Pdf ')).toBe('pdf')
    expect(normalizeFileType('')).toBe('')
  })
})

describe('processConfigLines · 源站 :1509-1548', () => {
  it('null / 空对象 ⇒ 单行「使用知识库默认配置」', () => {
    expect(processConfigLines(null)).toEqual(['使用知识库默认配置'])
    expect(processConfigLines({})).toEqual(['使用知识库默认配置'])
  })

  it('只有 chunk_size ⇒ 1 行', () => {
    expect(processConfigLines({ chunking_config: { chunk_size: 800 } })).toEqual([
      '分块: 800',
    ])
  })

  it('全字段 ⇒ 7 行，顺序与源站一致', () => {
    const overrides: KnowledgeProcessOverrides = {
      chunking_config: { chunk_size: 800, enable_parent_child: true },
      parser_engine_rules: [{ file_types: ['pdf'], engine: 'mineru' }],
      vlm_config: { enabled: true },
      asr_config: { enabled: false },
      summary_enabled: false,
      question_generation_config: { enabled: true, question_count: 5 },
      graph_enabled: true,
    }
    expect(processConfigLines(overrides, 'pdf')).toEqual([
      '分块: 800 · 父子分块 已开启',
      '解析引擎: pdf→mineru',
      '多模态: 已开启',
      '语音识别: 未开启',
      '文档摘要: 未开启',
      '问答: 5 个',
      '知识图谱: 已开启',
    ])
  })

  it('question 开但没给 count ⇒ 默认 3 条', () => {
    const lines = processConfigLines({ question_generation_config: { enabled: true } })
    expect(lines).toEqual(['问答: 3 个'])
  })

  it('vlm_config 缺省时用 enable_multimodel；graph 缺省时用 extract_config.enabled', () => {
    const lines = processConfigLines({ enable_multimodel: true, extract_config: { enabled: false } })
    expect(lines).toEqual(['多模态: 已开启', '知识图谱: 未开启'])
  })
})

describe('KbProcessingTimeline · compact 模式', () => {
  it('#12 只渲染五个状态点 + 一行摘要，没有标尺也没有行，并 emit onHasSpans', async () => {
    const onHasSpans = vi.fn()
    await mount({ autoPoll: false, compact: true, onHasSpans }, async () => ({
      success: true,
      data: tenSecondTrace(),
    }))

    expect(container.querySelectorAll('.kp-dot')).toHaveLength(5)
    expect(container.querySelector('.kp-compact-caption')?.textContent).toContain('总耗时：')
    expect(container.querySelector('.kp-compact-caption')?.textContent).toContain('10.00s')
    expect(container.querySelector('.kp-ruler')).toBeNull()
    expect(rows()).toHaveLength(0)
    expect(container.querySelector('.kp-shell')).toBeNull()
    // 源站靠这个事件控制抽屉头部「查看 Trace」按钮显隐
    expect(onHasSpans).toHaveBeenCalledWith(true)
  })
})

/** parse_status 可控的简单响应（停止解析 / 诊断用例共用）。 */
function statusSpans(status: string, over: Partial<SpansResponse> = {}): SpansResponse {
  return spans({ parse_status: status, ...over })
}

describe('KbProcessingTimeline · 停止解析（源站 :523-542 / :1619-1634）', () => {
  it('#13 parse_status=processing ⇒ 工具栏出现关闭圆圈按钮；completed ⇒ 不出现', async () => {
    await mount({ autoPoll: false }, async () => ({
      success: true,
      data: statusSpans('processing'),
    }))
    expect(container.querySelector('button[aria-label="停止解析"]')).not.toBeNull()

    await act(async () => root.unmount())
    container.remove()
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    await mount({ autoPoll: false }, async () => ({
      success: true,
      data: statusSpans('completed'),
    }))
    expect(container.querySelector('button[aria-label="停止解析"]')).toBeNull()
  })

  it('#14 点按钮 ⇒ 弹出手写 popconfirm，正文逐字；确认 ⇒ cancelParse + 成功提示 + 重取 spans', async () => {
    const showToast = vi.fn()
    const fetchSpans = await mount(
      { autoPoll: false, showToast, docTitle: '规范.pdf' },
      async () => ({ success: true, data: statusSpans('processing') }),
    )

    await act(async () => {
      click(container.querySelector('button[aria-label="停止解析"]'))
      await new Promise((resolve) => setTimeout(resolve, 0))
    })

    const pop = container.querySelector('.wiki-popconfirm')
    expect(pop).not.toBeNull()
    expect(pop?.querySelector('.t-popconfirm__inner')?.textContent).toBe(
      '确认停止解析"规范.pdf"？已写入的分块会保留，可稍后通过"重建"重新触发；优化阶段（摘要 / 问答 / 知识图谱）的待执行任务会被立即丢弃。',
    )
    expect(pop?.querySelector('.t-button--theme-default')?.textContent).toBe('取消')
    expect(pop?.querySelector('.t-button--theme-danger')?.textContent).toBe('停止解析')

    const before = fetchSpans.mock.calls.length
    await act(async () => {
      click(pop?.querySelector('.t-button--theme-danger') ?? null)
      await new Promise((resolve) => setTimeout(resolve, 0))
    })
    await flush()

    expect(cancelParse).toHaveBeenCalledWith('k1')
    expect(showToast).toHaveBeenCalledWith('已停止解析', 'success')
    expect(fetchSpans.mock.calls.length).toBe(before + 1)
    expect(container.querySelector('.wiki-popconfirm')).toBeNull()
  })

  it('#15 取消解析失败 ⇒ 提示用错误信息（Error.message），不吞异常', async () => {
    cancelParse.mockRejectedValueOnce(new Error('网络中断'))
    const showToast = vi.fn()
    await mount({ autoPoll: false, showToast }, async () => ({
      success: true,
      data: statusSpans('processing'),
    }))

    await act(async () => {
      click(container.querySelector('button[aria-label="停止解析"]'))
      await new Promise((resolve) => setTimeout(resolve, 0))
    })
    await act(async () => {
      click(container.querySelector('.wiki-popconfirm .t-button--theme-danger'))
      await new Promise((resolve) => setTimeout(resolve, 0))
    })
    await flush()

    expect(showToast).toHaveBeenCalledWith('网络中断', 'info')
  })
})

describe('KbProcessingTimeline · 诊断按钮（源站 :1711-1715）', () => {
  it('#16 onOpenAssistant 不传 ⇒ 不渲染；传了 ⇒ 带 query + 错误选区打开助手', async () => {
    const failed = () =>
      statusSpans('failed', {
        last_error: {
          name: 'docreader',
          error_code: 'DOCREADER_PARSE_FAILED',
          error_message: 'Unsupported file type: htm',
        },
      })

    await mount({ autoPoll: false }, async () => ({ success: true, data: failed() }))
    expect(container.querySelector('.assistant-button')).toBeNull()

    // 换一棵新树（同一 root 直接重渲染会走更新而不是重挂载，旧 data 仍在）
    await act(async () => root.unmount())
    container.remove()
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    const onOpenAssistant = vi.fn()
    await mount(
      { autoPoll: false, onOpenAssistant, docTitle: '坏文档.pdf' },
      async () => ({ success: true, data: failed() }),
    )

    const btn = container.querySelector('.kp-err-actions .assistant-button')
    expect(btn?.textContent).toBe('分析原因')
    await act(async () => {
      click(btn)
      await new Promise((resolve) => setTimeout(resolve, 0))
    })

    expect(onOpenAssistant).toHaveBeenCalledTimes(1)
    const [question, context] = onOpenAssistant.mock.calls[0]
    expect(question).toBe(
      '请检查文档「坏文档.pdf」（ID：k1）的处理情况，结合附带的错误说明原因并建议下一步。先不要重试、重建或修改文档。',
    )
    expect(context).toBe('DOCREADER_PARSE_FAILED\nUnsupported file type: htm')
  })
})

describe('KbProcessingTimeline · 本次解析配置多行（源站 :1509-1548）', () => {
  it('#17 详情接口带 process_overrides ⇒ 弹层渲染多行；无覆盖 ⇒ 只有 kbDefault', async () => {
    getKnowledge.mockResolvedValueOnce({
      metadata: JSON.stringify({
        process_overrides: {
          chunking_config: { chunk_size: 800, enable_parent_child: true },
          parser_engine_rules: [{ file_types: ['pdf'], engine: 'mineru' }],
          vlm_config: { enabled: true },
          asr_config: { enabled: false },
          summary_enabled: false,
          question_generation_config: { enabled: true, question_count: 5 },
          graph_enabled: true,
        },
      }),
      file_type: 'pdf',
      file_name: 'a.pdf',
      title: 'a',
    })

    await mount({ autoPoll: false }, async () => ({ success: true, data: spans() }))

    const infoBtn = container.querySelector<HTMLButtonElement>('button[aria-label="本次解析配置"]')
    await act(async () => {
      infoBtn?.focus()
      await new Promise((resolve) => setTimeout(resolve, 0))
    })

    const lines = Array.from(container.querySelectorAll('.kp-proccfg-line')).map(
      (el) => el.textContent,
    )
    expect(lines).toEqual([
      '分块: 800 · 父子分块 已开启',
      '解析引擎: pdf→mineru',
      '多模态: 已开启',
      '语音识别: 未开启',
      '文档摘要: 未开启',
      '问答: 5 个',
      '知识图谱: 已开启',
    ])
  })
})


