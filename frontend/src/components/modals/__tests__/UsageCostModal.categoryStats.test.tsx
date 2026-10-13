// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { UsageCostModal } from '../UsageCostModal'
import { useChatStore } from '@/stores/chatStore'
import { useSubagentStore } from '@/stores/subagentStore'
import type { StatsByAgent, StatsByCategory, StatsResponse } from '@/api/types'

/**
 * [usage-gateway] 用量弹窗「统计」标签页 · 按调用类型 / 按代理层级 两维（**只新增**，既有区块一字不动）。
 *
 * <h2>WHY（规则九 · 意图而非行为）</h2>
 * 后端把记账前置到请求网关后，`llm_usage_calls` 明细表能回答两个旧统计答不了的问题：
 * 「**哪类调用**烧钱」（子代理/压缩/后台/旁路各占多少）与「**谁**烧的钱」（父子层级花费树）。
 * 前端接上这两维才有意义 —— 否则后端算得再准，用户只看到汇总金额。因此本组用例的意图是：
 * ①两维**真的渲染出来且数值对**（类型名映射中文、金额/次数/token、估算/中断/失败标志）；
 * ②**版本偏差不炸**（老后端不返回该字段 → 区块仍在且显示空态，不是白屏、不是假 0）；
 * ③**不伪造比例**（金额全 0 时不画条）。
 *
 * <h2>RED（改坏哪条红）</h2>
 * <ul>
 *   <li>删掉任一区块（或只在有数据时渲染）→ 「区块 + 空态」两用例红；</li>
 *   <li>把 `CATEGORY_LABEL[r.category] ?? r.category` 换成直接输出枚举名 → 「中文标签」红；</li>
 *   <li>把 `usageNodeName` 换回「直接输出 label」→ 「slug → 中文」用例红（`general-purpose` 又变成读不懂的 slug）；</li>
 *   <li>把 agentId/短 id 加回名称或悬停标题 → 「⛔ 不显示任何 agentId/UUID」用例红；</li>
 *   <li>去掉序号后缀 → 「同名多节点加序号」用例红（两行看起来完全一样，用户无法区分）；</li>
 *   <li>去掉 `?? []` 兜底（直接 `data.byCategory.reduce`）→ 「老后端不返回字段」用例红（渲染抛错）；</li>
 *   <li>把条形比例从「金额」误换成「固定 100% / token」→ 「金额全 0 不画条」红；</li>
 *   <li>改动既有统计区块（总览/按天/按模型）→ 「既有区块仍在」红。</li>
 * </ul>
 */

const hoisted = vi.hoisted(() => ({ payload: null as StatsResponse | null }))

vi.mock('@/api/sessions', () => ({ sessionApi: {
  list: vi.fn(() => Promise.resolve([])),
  // [usage-gateway] 弹窗新增会话级用量读取；默认无记账数据（各行回退 task 事件统计）
  usage: vi.fn(() => Promise.resolve(null)),
} }))
vi.mock('@/api/stats', () => ({ statsApi: { get: () => Promise.resolve(hoisted.payload) } }))

const SID = 'sess-cat'

/** 明细行工厂（只覆盖用例关心的字段，其余按后端最小合法值）。 */
function catRow(over: Partial<StatsByCategory> & { category: string }): StatsByCategory {
  return {
    calls: 1, inputTokens: 0, outputTokens: 0, cacheReadTokens: 0, cacheWriteTokens: 0, costYuan: 0,
    estimatedCalls: 0, abortedCalls: 0, failedCalls: 0, streamCalls: 1, nonStreamCalls: 0,
    ...over,
  }
}

/** agent 行工厂（同上）。 */
function agentRow(over: Partial<StatsByAgent> & { agentId: string; category: string }): StatsByAgent {
  return {
    parentAgentId: null, depth: 0, label: null, calls: 1, inputTokens: 0, outputTokens: 0,
    cacheReadTokens: 0, cacheWriteTokens: 0, costYuan: 0, estimatedCalls: 0, abortedCalls: 0,
    failedCalls: 0, streamCalls: 1, nonStreamCalls: 0,
    ...over,
  }
}

/** 基线响应：totals/byDay/byModel 有值（用于「只加不删」断言），两维按用例注入。 */
function statsPayload(over: Partial<StatsResponse>): StatsResponse {
  return {
    totals: { sessionCount: 3, tokenCount: 9000, costYuan: 4.5 },
    byDay: [{ date: '2026-10-10', tokenCount: 9000, costYuan: 4.5 }],
    byModel: [{
      model: 'anthropic/claude-sonnet-4', inputTokens: 100, outputTokens: 20,
      cacheReadInputTokens: 0, cacheCreationInputTokens: 0, costUSD: 0.3, anthropic: true,
    }],
    ...over,
  }
}

async function flush(): Promise<void> {
  await act(async () => { await Promise.resolve(); await Promise.resolve() })
}

describe('[usage-gateway] UsageCostModal 统计维度 · 按调用类型 / 按代理层级', () => {
  let container: HTMLDivElement
  let root: Root

  beforeEach(() => {
    // React 19 的 act 环境标志（仓库既有测试同款意图；此处用具名引用读值，避免内联断言式访问）
    const g = globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }
    g.IS_REACT_ACT_ENVIRONMENT = true
    localStorage.clear()
    hoisted.payload = null
    useSubagentStore.setState({ sessionId: null, bySession: {} })
    useChatStore.setState({ messages: {}, streams: {}, sessions: [] })
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
  })

  afterEach(() => {
    act(() => { root.unmount() })
    container.remove()
  })

  /** 挂载弹窗并切到「统计」标签页（默认在「用量」页）。 */
  async function mountStatsTab(): Promise<void> {
    await act(async () => {
      root.render(<UsageCostModal activeSessionId={SID} onSwitch={() => {}} onClose={() => {}} />)
    })
    await flush()
    const tabBtn = [...container.querySelectorAll('.uc-tab')].find((el) => el.textContent === '统计')
    if (!tabBtn) throw new Error('「统计」标签按钮未渲染')
    act(() => { tabBtn.dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    await flush()
  }

  it('有明细 → 两区块渲染：类型名映射中文 · 金额/次数/token · 估算与中断标志', async () => {
    hoisted.payload = statsPayload({
      byCategory: [
        catRow({ category: 'MAIN', calls: 12, inputTokens: 1500, outputTokens: 300, costYuan: 1.5, abortedCalls: 1 }),
        catRow({ category: 'SIDE_CALL', calls: 8, inputTokens: 200, outputTokens: 40, costYuan: 0.9, estimatedCalls: 2 }),
        catRow({ category: 'BRAND_NEW_KIND', calls: 1, costYuan: 0.1 }),   // 后端新增枚举值 · 未映射应原样显示
      ],
      byAgent: [
        agentRow({ agentId: SID, category: 'MAIN', costYuan: 2.0, calls: 5, inputTokens: 900, outputTokens: 100 }),
        agentRow({ agentId: 'agent-child', category: 'SUBAGENT', parentAgentId: SID, depth: 1, label: 'alice', costYuan: 0.5, calls: 3, failedCalls: 1 }),
      ],
    })
    await mountStatsTab()

    const text = container.textContent ?? ''
    // 两区块存在
    expect(text).toContain('按调用类型')
    expect(text).toContain('按代理层级')
    // 类型名 → 中文（含未知枚举原样显示）
    expect(text).toContain('主对话')
    expect(text).toContain('旁路调用')
    expect(text).toContain('BRAND_NEW_KIND')
    // 每行 = 金额 · 调用次数 · token 合计（input+output）
    expect(text).toContain('¥1.50 · 12 次 · 1.8k tok')
    expect(text).toContain('¥0.90 · 8 次 · 240 tok')
    // 质量标志：估算/中断/失败 只在 >0 的行出现
    expect(text).toContain('其中 中断 1 次')
    expect(text).toContain('其中 估算 2 次')
    // 层级维度：名称 = 「类别 · 用途」中文（label 未映射时原样），子行按 depth 缩进（父 0 / 子 10px）
    expect(text).toContain('子代理 · alice')
    const sections = [...container.querySelectorAll('.uc-stats > .uc-section')]
    const agentSection = sections[sections.length - 1]
    expect(agentSection.textContent).toContain('按代理层级')
    const agentLabels = [...agentSection.querySelectorAll('.uc-stats-tree-label')]
    expect(agentLabels.find((el) => el.textContent === '子代理 · alice')?.getAttribute('style'))
      .toContain('padding-left: 10px')
    expect(agentLabels.find((el) => el.textContent === '主对话')?.getAttribute('style'))
      .toContain('padding-left: 0px')
    // 既有区块仍在（只加不删）
    expect(text).toContain('总览')
    expect(text).toContain('按天')
    expect(text).toContain('按模型')
  })

  it('按代理层级：后端自述 slug 显示为中文「类别 · 用途」· ⛔ 不显示任何 agentId/UUID', async () => {
    hoisted.payload = statsPayload({
      byAgent: [
        agentRow({ agentId: 'a5d4426c6bb3c62f9', category: 'SUBAGENT', label: 'general-purpose', costYuan: 0.5 }),
        agentRow({ agentId: 'aae86a8b146413c4f', category: 'COMPACT', label: 'compact-fork', costYuan: 0.2 }),
        agentRow({ agentId: 'sess-x', category: 'MAIN', label: null, depth: 0, costYuan: 0.1 }),
        agentRow({ agentId: 'a0123456789abcdef', category: 'SIDE_CALL', label: 'title-generation', costYuan: 0.05 }),
      ],
    })
    await mountStatsTab()

    const text = container.textContent ?? ''
    // slug → 中文（类别 · 用途）；label 为 null → 只给类别名；单节点不加序号
    expect(text).toContain('子代理 · 通用型')
    expect(text).toContain('压缩 · fork 摘要')
    expect(text).toContain('主对话')
    expect(text).toContain('旁路调用 · 会话标题生成')
    // ⛔ UUID / 短 id 对用户无意义 → 一律不显示（含悬停标题）
    expect(text).not.toContain('#')
    expect(text).not.toMatch(/[0-9a-f]{8}-[0-9a-f]{4}/)
    const labels = [...container.querySelectorAll('.uc-stats-tree-label')]
    for (const el of labels) {
      expect(el.getAttribute('title')).not.toMatch(/[0-9a-f]{8}-[0-9a-f]{4}/)
    }
    expect(labels.find((el) => el.textContent === '子代理 · 通用型')?.getAttribute('title'))
      .toContain('general-purpose')
    // ⛔ 不得把未映射的新 slug 吞掉（原样显示，不猜含义）
    expect(text).not.toContain('undefined')
  })

  it('按代理层级：同类同用途多个节点 → 加序号（1）（2）区分（无序号则两行看起来完全一样）', async () => {
    hoisted.payload = statsPayload({
      byAgent: [
        agentRow({ agentId: 'a5d4426c6bb3c62f9', category: 'SUBAGENT', label: 'general-purpose', costYuan: 0.5 }),
        agentRow({ agentId: 'aae86a8b146413c4f', category: 'SUBAGENT', label: 'general-purpose', costYuan: 0.2 }),
        agentRow({ agentId: 'sess-x', category: 'MAIN', label: null, depth: 0, costYuan: 0.1 }),
      ],
    })
    await mountStatsTab()

    const text = container.textContent ?? ''
    expect(text).toContain('子代理 · 通用型（1）')
    expect(text).toContain('子代理 · 通用型（2）')
    expect(text).toContain('主对话')
    expect(text).not.toContain('主对话（1）')
    expect(text).not.toContain('#')
  })

  it('按代理层级：未映射的 label 原样显示（新增后端标签时不显示错误含义）', async () => {
    hoisted.payload = statsPayload({
      byAgent: [agentRow({ agentId: 'a0123456789abcdef', category: 'SIDE_CALL', label: 'brand-new-purpose' })],
    })
    await mountStatsTab()
    expect(container.textContent).toContain('旁路调用 · brand-new-purpose')
  })

  it('明细表无记录（两维空数组）→ 两区块显示空态「暂无明细数据」，既有维度照常', async () => {
    hoisted.payload = statsPayload({ byCategory: [], byAgent: [] })
    await mountStatsTab()

    const text = container.textContent ?? ''
    expect(text).toContain('按调用类型')
    expect(text).toContain('按代理层级')
    expect((text.match(/暂无明细数据/g) ?? []).length).toBe(2)
    // 既有维度不受影响（不能因为新维度为空就整体空态）
    expect(text).toContain('anthropic/claude-sonnet-4')
    expect(text).toContain('2026-10-10')
  })

  it('老后端不返回该字段（undefined）→ 区块仍在 + 空态，页面不炸（版本偏差 fail-soft）', async () => {
    hoisted.payload = statsPayload({})   // 无 byCategory / byAgent（后端未升级）
    await mountStatsTab()

    const text = container.textContent ?? ''
    expect(text).toContain('按调用类型')
    expect(text).toContain('按代理层级')
    expect((text.match(/暂无明细数据/g) ?? []).length).toBe(2)
    expect(text).toContain('总览')
  })

  it('金额全 0（未配价）→ 不画条（width 0%）· 不得伪造比例', async () => {
    hoisted.payload = statsPayload({
      byDay: [], byModel: [],   // 隔离：只让两个新维度的条留在 DOM 里
      byCategory: [catRow({ category: 'MAIN', calls: 4, inputTokens: 400, outputTokens: 100, costYuan: 0 })],
      byAgent: [agentRow({ agentId: SID, category: 'MAIN', calls: 4, inputTokens: 400, outputTokens: 100, costYuan: 0 })],
    })
    await mountStatsTab()

    const fills = [...container.querySelectorAll('.uc-stats-bar-fill')]
    expect(fills.length).toBe(2)
    for (const f of fills) {
      expect(f.getAttribute('style')).toContain('width: 0%')
    }
    // token 仍然展示（不因未配价而隐藏用量）
    expect(container.textContent).toContain('500 tok')
  })
})
