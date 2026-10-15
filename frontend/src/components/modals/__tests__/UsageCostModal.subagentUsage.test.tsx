// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { UsageCostModal } from '../UsageCostModal'
import { useChatStore } from '@/stores/chatStore'
import { useSubagentStore } from '@/stores/subagentStore'
import type { SessionAgentUsage, SessionUsageResponse } from '@/api/types'

/**
 * [usage-gateway] 用量弹窗「用量」页 · 子代理行的**记账侧权威统计**（关联 + 回退 + 仅记账行）。
 *
 * <h2>WHY（规则九 · 这条守的是用户实测的那个故障）</h2>
 * 用户实测：子代理行长期显示「统计不可用」。原因是该行的数字只有一个来源 —— `/topic/tasks` 的
 * task 事件 usage（进度估算 / 终态值），而失败、中断、后台化等路径的 task 事件常常没有 usage。
 * 记账前置到请求网关后，每次 LLM 调用都在 `llm_usage_calls` 留了一行 ⇒ 该行有了权威数据源
 * （token 四通道 + 费用 + 模型调用次数）。本组用例守住三件事：
 * <ol>
 *   <li><b>关联命中即用权威值</b>：store 身份（`task_id`）与记账节点（后端换算出的 `taskId`）
 *       对齐后，行内必须显示「¥ · tokens · 次模型调用」，不得再显示「统计不可用」；</li>
 *   <li><b>拿不到记账数据不得退化体验</b>：无关联节点 / 老后端（端点 404）→ 照旧回退 task 事件统计，
 *       两者都没有才显示「统计不可用」（⛔ 不臆造 0）；</li>
 *   <li><b>仅记账可得的节点也要列出</b>（刷新/换端后 localStorage 无实时身份），且不得伪造
 *       「运行中/已完成」状态、不得重复列出已在实时身份里的节点、不得混入主 agent（depth 0）。</li>
 * </ol>
 *
 * <h2>RED（改坏哪条红）</h2>
 * <ul>
 *   <li>把关联逻辑去掉（恒用 task 事件统计）→ 「关联命中」用例红（仍显示「统计不可用」）；</li>
 *   <li>把回退分支删掉（恒用记账文本，无数据时渲染空/0）→ 「无关联回退」与「老后端」用例红；</li>
 *   <li>`ledgerOnlyAgents` 丢掉 `depth >= 1` 过滤 → 「主 agent 不得出现」红；</li>
 *   <li>不做去重 → 「已在实时身份里的节点不得重复」红；</li>
 *   <li>把仅记账行的名称改成「类别 · 用途」（即删掉 `AGENT_NAME_FIRST` 名称优先分支）→
 *       「回退子代理名称」用例红（行首又变成一排「子代理 ·」）。</li>
 * </ul>
 */

const hoisted = vi.hoisted(() => ({
  usage: null as SessionUsageResponse | null,
  usageFails: false,
  tasks: [] as { id: string; description: string }[],
}))

vi.mock('@/api/sessions', () => ({
  sessionApi: {
    list: vi.fn(() => Promise.resolve([])),
    usage: vi.fn(() => hoisted.usageFails
      ? Promise.reject(new Error('404'))
      : Promise.resolve(hoisted.usage)),
  },
}))
vi.mock('@/api/stats', () => ({ statsApi: { get: vi.fn(() => Promise.resolve(null)) } }))
// 任务注册表：仅记账行据此显示「子代理名称」（description）
vi.mock('@/api/tasks', () => ({ tasksApi: { list: vi.fn(() => Promise.resolve(hoisted.tasks)) } }))

const SID = 'sess-sub'

/** 记账节点工厂（只覆盖用例关心的字段）。 */
function agentNode(over: Partial<SessionAgentUsage> & { agentId: string }): SessionAgentUsage {
  return {
    taskId: null, category: 'SUBAGENT', parentAgentId: SID, parentTaskId: null, depth: 1, label: 'alice',
    calls: 1, inputTokens: 0, outputTokens: 0, cacheReadTokens: 0, cacheWriteTokens: 0, costYuan: 0,
    elapsedMs: 0, estimatedCalls: 0, abortedCalls: 0, failedCalls: 0, streamCalls: 1, nonStreamCalls: 0,
    ...over,
  }
}

function usagePayload(agents: SessionAgentUsage[]): SessionUsageResponse {
  return {
    sessionId: SID,
    totals: {
      calls: 0, inputTokens: 0, outputTokens: 0, cacheReadTokens: 0, cacheWriteTokens: 0, costYuan: 0,
      elapsedMs: 0, estimatedCalls: 0, abortedCalls: 0, failedCalls: 0, streamCalls: 0, nonStreamCalls: 0,
    },
    byCategory: [],
    agents,
  }
}

async function flush(): Promise<void> {
  await act(async () => { await Promise.resolve(); await Promise.resolve() })
}

describe('[usage-gateway] 用量页 · 子代理行接记账数据', () => {
  let container: HTMLDivElement
  let root: Root

  beforeEach(() => {
    const g = globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }
    g.IS_REACT_ACT_ENVIRONMENT = true
    localStorage.clear()
    hoisted.usage = null
    hoisted.usageFails = false
    hoisted.tasks = []
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

  async function mount(): Promise<void> {
    await act(async () => {
      root.render(<UsageCostModal activeSessionId={SID} onSwitch={() => {}} onClose={() => {}} />)
    })
    await flush()
  }

  it('关联命中（task_id === 记账节点 taskId）→ 行内显示 ¥ · tokens · 次模型调用（不再是「统计不可用」）', async () => {
    useSubagentStore.getState().register(null, 'task-1', 'alice', 'local_agent', SID)
    useSubagentStore.getState().addActivity('task-1', { type: 'done', text: '完成', ts: 1 }, SID)
    hoisted.usage = usagePayload([
      agentNode({ agentId: 'a0123456789abcdef', taskId: 'task-1', costYuan: 0.3, inputTokens: 400, outputTokens: 60, calls: 3 }),
    ])
    await mount()

    const text = container.textContent ?? ''
    expect(text).toContain('@alice')
    expect(text).toContain('已完成 · ¥0.30 · 460 tokens · 3 次模型调用')
    expect(text).not.toContain('统计不可用')
  })

  it('关联命中且带估算标志 → 行内标注「估算 N 次」（估算值不可当账单）', async () => {
    useSubagentStore.getState().register(null, 'task-1', 'alice', 'local_agent', SID)
    hoisted.usage = usagePayload([
      agentNode({ agentId: 'a0123456789abcdef', taskId: 'task-1', costYuan: 0.1, inputTokens: 100, outputTokens: 10, estimatedCalls: 2 }),
    ])
    await mount()

    expect(container.textContent).toContain('估算 2 次')
  })

  it('后端无该节点 → 回退 task 事件统计（不得因接记账而丢掉既有数据源）', async () => {
    useSubagentStore.getState().register(null, 'task-1', 'alice', 'local_agent', SID)
    useSubagentStore.getState().setUsage('task-1', { totalTokens: 33600, toolUses: 5, durationMs: 39500 }, SID)
    hoisted.usage = usagePayload([])
    await mount()

    expect(container.textContent).toContain('33.6k tokens · 5 次调用 · 39.5s')
  })

  it('两源皆无 → 「统计不可用」（不臆造 0）', async () => {
    useSubagentStore.getState().register(null, 'task-1', 'alice', 'local_agent', SID)
    hoisted.usage = usagePayload([])
    await mount()

    expect(container.textContent).toContain('统计不可用')
  })

  it('老后端无该端点（请求失败）→ 弹窗不炸 · 行回退 task 事件统计', async () => {
    useSubagentStore.getState().register(null, 'task-1', 'alice', 'local_agent', SID)
    useSubagentStore.getState().setUsage('task-1', { totalTokens: 1200, toolUses: 2, durationMs: 3000 }, SID)
    hoisted.usageFails = true
    await mount()

    expect(container.textContent).toContain('子代理（本会话）')
    expect(container.textContent).toContain('1.2k tokens · 2 次调用 · 3.0s')
  })

  it('仅记账行显示**子代理名称**（任务注册表 description，与任务面板同口径），而非类型名', async () => {
    hoisted.usage = usagePayload([
      agentNode({ agentId: 'a48afc67b6604cf17', taskId: '48afc67b-6604-cf17-0000-000000000000',
        category: 'SUBAGENT', label: 'general-purpose', costYuan: 0.3, inputTokens: 300, outputTokens: 30 }),
    ])
    hoisted.tasks = [{ id: '48afc67b-6604-cf17-0000-000000000000', description: '分析可用编译构建环境' }]
    await mount()

    const text = container.textContent ?? ''
    expect(text).toContain('@分析可用编译构建环境')
    expect(text).not.toContain('子代理 · 通用型')
    expect(text).toContain('¥0.30 · 330 tokens · 1 次模型调用')
  })

  it('任务已出注册表（重启/淘汰）→ 回退**子代理名称**（label 中文），不得显示空白或 id', async () => {
    hoisted.usage = usagePayload([
      agentNode({ agentId: 'a48afc67b6604cf17', taskId: '48afc67b-6604-cf17-0000-000000000000',
        category: 'SUBAGENT', label: 'general-purpose' }),
    ])
    hoisted.tasks = []   // 注册表里没有该任务
    await mount()

    const text = container.textContent ?? ''
    // [修复] 子代理行名 = 子代理名称（不再以「子代理 ·」开头 —— 一排同前缀读不出是谁）
    expect(text).toContain('通用型')
    expect(text).not.toContain('子代理 · 通用型')
    expect(text).not.toMatch(/[0-9a-f]{8}-[0-9a-f]{4}/)
  })

  it('仅记账节点：首行是旁路调用（类别 SIDE_CALL）但带 taskId → 仍列出（不得按首行类别误滤）', async () => {
    // 归一后真实形态：子代理节点与它的旁路调用并成一个节点，首行可能是旁路 ⇒ 类别显示为 SIDE_CALL，
    //   但 taskId 非空（真 agent 任务）→ 必须列出，否则真子代理被漏掉
    hoisted.usage = usagePayload([
      agentNode({ agentId: 'a48afc67b6604cf17', taskId: '48afc67b-6604-cf17-0000-000000000000',
        category: 'SIDE_CALL', label: 'skill-catalog-summary', costYuan: 0.3, inputTokens: 300, outputTokens: 30, calls: 3 }),
      // 会话根自身发起的旁路（无 taskId、depth 1、类别 SIDE_CALL）→ 不属于子代理区
      agentNode({ agentId: 'sess-sub', taskId: null, category: 'SIDE_CALL', label: 'title-generation', costYuan: 9.9 }),
    ])
    await mount()

    const text = container.textContent ?? ''
    expect(text).toContain('旁路调用 · 技能目录摘要')
    expect(text).toContain('¥0.30 · 330 tokens · 3 次模型调用')
    expect(text).not.toContain('¥9.90')
    expect(text).not.toContain('会话标题生成')
  })

  it('仅记账可得的子代理节点：列出用量 · 不伪造状态 · 不混入主 agent · 不重复已列节点', async () => {
    // 实时身份：task-1（记账里也有 → 不得重复列出）；记账里另有 task-2（本端无实时身份）与主 agent（depth 0）
    useSubagentStore.getState().register(null, 'task-1', 'alice', 'local_agent', SID)
    hoisted.usage = usagePayload([
      agentNode({ agentId: SID, taskId: null, category: 'MAIN', depth: 0, label: null, costYuan: 9.99 }),
      agentNode({ agentId: 'a0123456789abcdef', taskId: 'task-1', label: 'alice', costYuan: 0.2 }),
      agentNode({ agentId: 'afedcba9876543210', taskId: 'task-2', label: 'bob', costYuan: 0.5, inputTokens: 200, outputTokens: 20, calls: 2 }),
    ])
    await mount()

    const text = container.textContent ?? ''
    // 仅记账节点列出：名称 = **子代理名称**（label 未映射 → 原样），用量紧随其后；
    // ⛔ 不显示「运行中/已完成」（本端不知道实时状态）
    expect(text).toContain('bob')
    expect(text).not.toContain('子代理 · bob')
    expect(text).toContain('¥0.50 · 220 tokens · 2 次模型调用')
    expect(text).not.toContain('已完成')
    // 已关联的节点不重复：@alice 只出现一次
    expect((text.match(/@alice/g) ?? []).length).toBe(1)
    // 主 agent（depth 0）不得出现在子代理区
    expect(text).not.toContain('¥9.99')
  })
})
