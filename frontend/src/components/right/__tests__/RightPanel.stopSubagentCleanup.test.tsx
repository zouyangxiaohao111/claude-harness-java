// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/rest'
import type { Project } from '@/types'
import { useSubagentStore } from '@/stores/subagentStore'
import { RightPanel } from '@/components/right/RightPanel'

/**
 * 刀 2b · RightPanel「子代理运行状况」停止出口的收敛（真实渲染）。
 *
 * <h2>WHY（意图验证 · 规则九）</h2>
 * <p>`handleKillSubagent` 是全仓**唯一**调用 `subagentStore.forget` 的地方 —— 它一旦不收敛，
 * 「点停止、后端已 not found、前端还显示运行中」就只能等 localStorage 过期。两条失效面：
 * <ul>
 *   <li><b>刀 2b-i（会话桶）</b>：`forget(taskId)` 不传 sessionId ⇒ 落到
 *       `sessionId ?? get().sessionId ?? ''` 的**全局单槽**。多会话下该槽可能指向"上一个请求残留的
 *       别会话"（本仓已知的第三态，比 null 更坏）⇒ 清错桶：卡片所在会话没清、别的会话被误清。</li>
 *   <li><b>刀 2b-ii（409 分支）</b>：后端 `stopTask` 对 status != running 返回 `NOT_RUNNING`
 *       → TaskController 409 ⇒ 旧代码只有一条 `showToast`，**零状态变更** ⇒ 卡片继续显示"运行中"。</li>
 * </ul>
 *
 * <h2>RED teeth（反向实验证据见交付说明）</h2>
 * <ul>
 *   <li>把 404 分支的 `forget(id.taskId, activeSessionId ?? '')` 退回 `forget(id.taskId)`
 *       ⇒ 「清的是当前会话桶」用例红（清到了全局单槽指向的别会话，本会话卡片还在）；</li>
 *   <li>删掉 `if (e instanceof ApiError)` 收敛分支 ⇒ 409 用例红（卡片仍是 running）；</li>
 *   <li><b>本批</b>把收敛判据退回 `e instanceof ApiError`（不收窄）⇒ 500 / status=0 两个用例红
 *       （任务可能仍在跑却被清了本地身份 + 弹"已结束"）；</li>
 *   <li><b>本批</b>把成功分支的 `addActivity` 第三实参去掉 ⇒ 「乐观终态落在当前会话桶」用例红
 *       （写进了全局单槽指向的别会话，本会话卡片仍 running）。</li>
 * </ul>
 */

const killMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/tasks', () => ({
  tasksApi: { killTask: killMock, list: vi.fn(() => Promise.resolve([])), stopAllTasks: vi.fn() },
}))
vi.mock('@/api/workflows', () => ({
  workflowApi: { listRuns: vi.fn(() => Promise.resolve([])), killRun: vi.fn(), getRun: vi.fn() },
}))
vi.mock('@/api/projects', () => ({ projectApi: {} }))
vi.mock('@/api/subagents', () => ({ subagentApi: {} }))
vi.mock('@/hooks/useSchedules', () => ({ useSchedules: () => ({ list: [], loading: false }) }))
vi.mock('@/components/modals/SchedulesPanel', () => ({ cronToHuman: () => '' }))
// 三个邻接面板与本次改动无关 → 置空，隔离出「子代理运行状况」这一块
vi.mock('@/components/right/TeamPanel', () => ({ TeamPanel: () => null }))
vi.mock('@/components/right/TodoPanel', () => ({ TodoPanel: () => null }))
vi.mock('@/components/right/AsyncTasksPanel', () => ({ AsyncTasksPanel: () => null }))

const SID = 'sess-1'
const OTHER_SID = 'sess-2'

async function flush(ms = 0): Promise<void> {
  await act(async () => { await new Promise((r) => setTimeout(r, ms)) })
}

function click(el: Element | null): void {
  expect(el, '待点击元素必须存在（否则断言的是别的路径）').not.toBeNull()
  el!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
}

describe('[刀 2b] RightPanel 停止子代理：会话桶正确 + 409 也收敛', () => {
  let container: HTMLDivElement
  let root: Root

  beforeEach(() => {
    ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    localStorage.clear()
    useSubagentStore.setState({ sessionId: null, bySession: {} })
    killMock.mockReset()
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    // 登记一个「本会话」的 running 子代理（卡片来源 = bySession[activeSessionId]）
    useSubagentStore.getState().register(null, 't-1', 'alice', 'local_agent', SID)
  })

  afterEach(() => {
    act(() => { root.unmount() })
    container.remove()
  })

  async function mountAndOpenKillButton(): Promise<Element | null> {
    await act(async () => {
      root.render(
        <RightPanel
          activeSessionId={SID}
          mainProject={{} as Project}
          rightTab="tasks"
          setRightTab={() => {}}
          onOpenFileRow={() => {}}
          onOpenFile={() => {}}
          onQuoteFile={() => {}}
          showToast={() => {}}
          openSettingsAt={() => {}}
          flashingProject={null}
          onRollbackFile={() => {}}
        />,
      )
    })
    await flush()
    // 打开「进行中」三态弹窗 → 卡片行内的 ⏹
    click(container.querySelector('.sa-stat.running'))
    await flush()
    return container.querySelector('.subagent-kill')
  }

  it('409（后端 NOT_RUNNING=任务已结束）→ 本地身份收敛，卡片不再停在"运行中"', async () => {
    killMock.mockRejectedValue(new ApiError('Task not running: t-1', { status: 409 }))
    const killBtn = await mountAndOpenKillButton()
    expect(useSubagentStore.getState().bySession[SID]?.['t-1']).toBeTruthy()

    await act(async () => { click(killBtn) })
    await flush()

    expect(
      useSubagentStore.getState().bySession[SID]?.['t-1'],
      '409 = 后端已无该运行任务 ⇒ 本地卡片不得继续显示"运行中"',
    ).toBeUndefined()
  })

  it('404 → 本地身份收敛（既有出口的回归）', async () => {
    killMock.mockRejectedValue(new ApiError('Task not found: t-1', { status: 404 }))
    const killBtn = await mountAndOpenKillButton()

    await act(async () => { click(killBtn) })
    await flush()

    expect(useSubagentStore.getState().bySession[SID]?.['t-1']).toBeUndefined()
  })

  it('显式传 sessionId：清的是**当前会话**桶，不落到全局单槽指向的别会话', async () => {
    // 全局单槽被设为别会话（RequestContext 残留态的等价物），并在别会话桶里放同名 taskId
    useSubagentStore.getState().register(null, 't-1', 'bob', 'local_agent', OTHER_SID)
    useSubagentStore.setState({ sessionId: OTHER_SID })
    // 用 404（不是 409）以便与「409 收敛分支」解耦：本用例只断言**传到哪个桶**
    killMock.mockRejectedValue(new ApiError('Task not found: t-1', { status: 404 }))

    const killBtn = await mountAndOpenKillButton()
    await act(async () => { click(killBtn) })
    await flush()

    expect(
      useSubagentStore.getState().bySession[SID]?.['t-1'],
      '必须清掉卡片所在会话（sess-1）的桶',
    ).toBeUndefined()
    expect(
      useSubagentStore.getState().bySession[OTHER_SID]?.['t-1'],
      '不得清到全局单槽指向的别会话（会话态不得经全局单槽读）',
    ).toBeTruthy()
  })

  it('本批第 2 步：500（服务端故障）**不清**本地身份 —— 任务可能仍在跑，清掉+弹"已结束"是误导', async () => {
    // WHY：api/rest.ts 把一切 !res.ok 包成 ApiError（含 500/401/403…）。这些状态码下任务**可能仍在运行**，
    //   旧判据 `e instanceof ApiError` 会把卡片清掉（活动时间线被重置），而 2s 后轮询又把它登记回来 = 闪一下消失。
    killMock.mockRejectedValue(new ApiError('Internal error', { status: 500, title: 'Server Error' }))
    const killBtn = await mountAndOpenKillButton()

    await act(async () => { click(killBtn) })
    await flush()

    expect(
      useSubagentStore.getState().bySession[SID]?.['t-1'],
      '500 不代表后端已无该运行任务 ⇒ 本地身份必须保留（判据只能收窄到 404/409）',
    ).toBeTruthy()
  })

  it('本批第 2 步：网络层错（ApiError status=0）同样不清 —— 断网 ≠ 任务已结束', async () => {
    // WHY：rest.ts:97-103 把 fetch 抛出的网络错包成 ApiError(status=0)。断网/DNS/CORS 时任务在服务端照跑。
    killMock.mockRejectedValue(new ApiError('Network error: Failed to fetch', { status: 0, title: 'Network Error' }))
    const killBtn = await mountAndOpenKillButton()

    await act(async () => { click(killBtn) })
    await flush()

    expect(
      useSubagentStore.getState().bySession[SID]?.['t-1'],
      '网络层错（status=0）不得被当成"任务已结束" ⇒ 本地身份必须保留',
    ).toBeTruthy()
  })

  it('本批第 4 步：**成功**分支的乐观终态写进卡片所在会话（不落全局单槽）', async () => {
    // WHY：成功分支原 `addActivity(id.taskId, {...})` 不传 sessionId ⇒ 落到 `sessionId ?? get().sessionId`
    //   的全局单槽。并发会话下单槽可能指向"上一个请求残留的别会话"⇒ 乐观终态写进别会话桶：卡片所在桶
    //   仍停"运行中"（killed 终态事件再丢窗就无法收敛），别会话反被误标"已停止"。
    useSubagentStore.getState().register(null, 't-1', 'bob', 'local_agent', OTHER_SID)
    useSubagentStore.setState({ sessionId: OTHER_SID })
    killMock.mockResolvedValue({ success: true })

    const killBtn = await mountAndOpenKillButton()
    await act(async () => { click(killBtn) })
    await flush()

    expect(
      useSubagentStore.getState().bySession[SID]?.['t-1']?.status,
      '乐观终态必须落在卡片所在会话（sess-1）的桶，否则卡片仍停"运行中"',
    ).toBe('stopped')
    expect(
      useSubagentStore.getState().bySession[OTHER_SID]?.['t-1']?.status,
      '不得把乐观终态写到全局单槽指向的别会话（会话态不得经全局单槽读）',
    ).toBe('running')
  })
})
