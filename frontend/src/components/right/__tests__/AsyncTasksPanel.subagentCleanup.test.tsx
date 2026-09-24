// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/rest'
import type { BackgroundTaskDto } from '@/api/tasks'
import { useSubagentStore } from '@/stores/subagentStore'

/**
 * 刀 1b / 2a / 2c · AsyncTasksPanel 的「子代理卡片收敛」三条出口（真实渲染）。
 *
 * <h2>WHY（意图验证 · 规则九）</h2>
 * <p>面板的 REST 兜底补录是 STOMP 丢窗后**唯一**的恢复通道；同一份轮询代码同时承担三条出口，
 * 任一出口失效都只在「丢窗 / 已结束 / 已 evict」这些**低频且无法肉眼复现**的路径上表现，
 * 因此必须真的渲染一次并观察 store 的净效果，而不是断言源码里有没有那行字符串。
 *
 * <h3>三条出口各自的意图</h3>
 * <ol>
 *   <li><b>刀 1b · 类型白名单</b>：白名单只认 `local_agent` 时，teammate/remote_agent 的子代理卡片
 *       **永远无法**从 REST 恢复（STOMP 事件在窗口外丢掉即永久空白）。本用例断言三类都进 store，
 *       且与 `useChatSocket.ts:663-665` 的 task_started 白名单**同一组字面量**（同源，不发明第三种写法）。</li>
 *   <li><b>刀 2a · 404 清理</b>：在「异步任务」区点 ⏹（走 `handleKillTask`）此前**从不碰**
 *       subagentStore ⇒ 后端已 not found、卡片仍停在"运行中"。本用例断言 404 后本地身份被清掉，
 *       且**不会被随后的 `void load()` 重新登记**（这正是「2 秒复活」那条失效面）。</li>
 *   <li><b>刀 2c · 缺席即删（宽限期 = 连续 2 次）</b>：终态事件丢窗 + REST 清单不再包含该任务时，
 *       localStorage 里持久化的 running 卡片是永久虚高。本用例断言「第 1 次缺席不删、第 2 次才删」——
 *       宽限期正是为了不误删「刚注册、REST 还没反映」的任务。</li>
 * </ol>
 *
 * <h2>RED teeth（反向实验证据见交付说明）</h2>
 * <ul>
 *   <li>白名单退回 `t.type !== 'local_agent'` ⇒ 用例 1 红（teammate/remote 条目缺失）；</li>
 *   <li>删掉 `handleKillTask` 404 分支里的 forget 调用 ⇒ 用例 2 红（卡片复活）；</li>
 *   <li>删掉 load() 里的缺席清理块 ⇒ 用例 3 红（第 2 次缺席后卡片仍在）；</li>
 *   <li><b>本批</b>把 kill 分支的状态码判据退回「仅 404」⇒ 用例 4 红（409 后卡片仍在，正是刀 3 与刀 2a 抵消的现场）；</li>
 *   <li><b>本批</b>把缺席清理白名单退回两类（去掉 remote_agent）⇒ 用例 5 红（远端幽灵卡片无出口）。</li>
 * </ul>
 */

const listMock = vi.hoisted(() => vi.fn())
const killMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/tasks', () => ({
  tasksApi: { list: listMock, killTask: killMock, stopAllTasks: vi.fn(), background: vi.fn(), backgroundAll: vi.fn() },
}))

// 注意：vi.mock 在导入前生效（vitest 会把 vi.mock 提升到 import 之上）
import { AsyncTasksPanel } from '@/components/right/AsyncTasksPanel'

const SID = 'sess-1'

function dto(over: Partial<BackgroundTaskDto> & { id: string }): BackgroundTaskDto {
  return {
    type: 'local_agent', status: 'running', description: '任务', startTime: Date.now(),
    isBackgrounded: true, ...over,
  }
}

/** 让 React 的 state 更新与 promise 落地都发生在 act() 内。 */
async function flush(ms = 0): Promise<void> {
  await act(async () => { await new Promise((r) => setTimeout(r, ms)) })
}

function click(el: Element | null): void {
  expect(el, '待点击元素必须存在（否则用例断言的是别的路径）').not.toBeNull()
  el!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
}

describe('[刀 1b/2a/2c] AsyncTasksPanel 子代理卡片三条收敛出口', () => {
  let container: HTMLDivElement
  let root: Root
  /** 后端返回的会话级清单（可变：模拟「任务已不在后端」的时点切换） */
  let currentList: BackgroundTaskDto[]

  beforeEach(() => {
    ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    localStorage.clear()
    useSubagentStore.setState({ sessionId: null, bySession: {} })
    listMock.mockReset()
    killMock.mockReset()
    listMock.mockImplementation(() => Promise.resolve(currentList))
    currentList = []
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
      root.render(<AsyncTasksPanel activeSessionId={SID} showToast={() => {}} />)
    })
    await flush()
  }

  function bucket(): Record<string, unknown> {
    return (useSubagentStore.getState().bySession[SID] ?? {}) as Record<string, unknown>
  }

  it('刀 1b：local_agent / in_process_teammate / remote_agent 三类都从 REST 恢复（与 useChatSocket 白名单同源）', async () => {
    currentList = [
      dto({ id: 'a-1', type: 'local_agent' }),
      dto({ id: 't-1', type: 'in_process_teammate', description: 'alice: 调研' }),
      dto({ id: 'r-1', type: 'remote_agent' }),
      dto({ id: 'b-1', type: 'local_bash', description: 'sleep 30' }),
    ]
    await mount()

    // 三类子代理均入表；local_bash 不入「子代理运行状况」（与 useChatSocket 同款白名单）
    expect(Object.keys(bucket()).sort()).toEqual(['a-1', 'r-1', 't-1'])
    expect((bucket()['t-1'] as { taskType?: string }).taskType).toBe('in_process_teammate')
  })

  it('刀 2a：异步任务区点 ⏹ 遇 404 → 清掉本地子代理身份，且不被随后的 load() 复活', async () => {
    currentList = [dto({ id: 'a-1', type: 'local_agent' })]
    killMock.mockRejectedValue(new ApiError('Task not found: a-1', { status: 404 }))
    await mount()
    expect(bucket()['a-1'], '前置：REST 兜底补录应已登记该子代理').toBeTruthy()

    // 打开「进行中」弹窗 → 行内 ⏹
    click(container.querySelector('.sa-stat.running'))
    await flush()
    const killBtn = container.querySelector('.at-kill')
    // 关键：点 ⏹ 的同一时点，后端清单已不含该任务（404 的语义 = 任务确实没了）
    currentList = []
    await act(async () => { click(killBtn) })
    await flush()

    expect(
      useSubagentStore.getState().bySession[SID]?.['a-1'],
      '404 后本地子代理身份必须被清掉（否则卡片永远停在"运行中"）',
    ).toBeUndefined()
  })

  it('刀 2c：连续 2 次缺席才 forget（宽限期防「刚注册、REST 未反映」误删）', async () => {
    currentList = [dto({ id: 'a-1', type: 'local_agent' })]
    await mount()
    expect(bucket()['a-1'], '前置：首次轮询登记该子代理').toBeTruthy()

    // 后端清单不再包含它（终态事件也丢了）
    currentList = []
    await flush(2200) // 第 1 次缺席（2s 轮询第 1 跳）
    expect(bucket()['a-1'], '第 1 次缺席必须仍在（宽限期=连续两次）').toBeTruthy()

    await flush(2200) // 第 2 次缺席 → 触发 forget
    expect(bucket()['a-1'], '第 2 次连续缺席应清掉本地身份（否则永久幽灵卡片）').toBeUndefined()
  })

  it('本批第 1 步：409（后端 NOT_RUNNING = 任务已结束）也必须清卡片，且不被随后的 load() 复活', async () => {
    // WHY：后端 stopTask 对 store-only running local_agent 返回 NOT_RUNNING → 409。只认 404 时
    //   这条路径拿不到任何清理 ⇒ 卡片继续显示"运行中"（用户报的症状）。卡片的清理由 store 净效果
    //   观测（不是断言源码字符串）：409 后 bySession[SID] 里不得再存在该 taskId。
    currentList = [dto({ id: 'a-1', type: 'local_agent' })]
    killMock.mockRejectedValue(new ApiError('Task not running: a-1', { status: 409 }))
    await mount()
    expect(bucket()['a-1'], '前置：REST 兜底补录应已登记该子代理').toBeTruthy()

    click(container.querySelector('.sa-stat.running'))
    await flush()
    const killBtn = container.querySelector('.at-kill')
    currentList = [] // 点 ⏹ 的同一时点，后端清单已不含该任务（409 的语义 = 已非运行态）
    await act(async () => { click(killBtn) })
    await flush()

    expect(
      useSubagentStore.getState().bySession[SID]?.['a-1'],
      '409 = 后端已无该运行任务（NOT_RUNNING）⇒ 本地身份必须被清掉，否则刀 3（404→409）与刀 2a 互相抵消',
    ).toBeUndefined()
  })

  it('本批第 3 步：remote_agent 连续两次缺席也清（类型白名单三方一致，无残余幽灵）', async () => {
    // WHY：上一批让 remote_agent「进得来（REST 恢复放行）」却「出不去（缺席清理排除）」⇒ 自相矛盾：
    //   远端任务已结束、终态事件又丢窗时，该卡片无任何出口可清。本用例断言它与另两类同款收敛。
    currentList = [dto({ id: 'r-1', type: 'remote_agent', description: '远端部署' })]
    await mount()
    expect(bucket()['r-1'], '前置：remote_agent 由 REST 恢复通道登记（类型白名单已放行）').toBeTruthy()

    currentList = []
    await flush(2200)
    expect(bucket()['r-1'], '第 1 次缺席必须仍在（宽限期=连续两次）').toBeTruthy()

    await flush(2200)
    expect(
      bucket()['r-1'],
      '第 2 次连续缺席应清掉 remote_agent（否则远端幽灵卡片永久虚高）',
    ).toBeUndefined()
  })
})
