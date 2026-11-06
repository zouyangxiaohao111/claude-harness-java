// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { GoalHead } from '../GoalHead'
import { useChatStore } from '@/stores/chatStore'

// [goal V253] 后端清列端点必须被 mock：× 按钮走「先 DELETE 后端、再清前端 store」
vi.mock('@/api/goal', () => ({
  goalApi: {
    get: vi.fn(async () => ({ goal: null })),
    clear: vi.fn(async () => ({ cleared: true })),
  },
}))
import type { GoalUiState } from '@/utils/goalState'

/**
 * [goal V253] GoalHead 渲染契约测试。
 *
 * <p>WHY: 目标头是「目标 = 输入框的头」这一形态裁定的落地。核心不变量三条：
 * <ol>
 *   <li><b>扫光条只在「追之中」态</b> —— 绿（已达成）/ 红（无法达成）不加
 *       （「已完成还在扫」自相矛盾）</li>
 *   <li><b>无目标 ⇒ 零高度</b>（不渲染任何节点）</li>
 *   <li>多会话隔离：只显示当前会话那一份</li>
 * </ol>
 */
const SID = 'sess-1'

describe('[goal V253] GoalHead', () => {
  let container: HTMLDivElement
  let root: Root

  const goal = (over: Partial<GoalUiState> = {}): GoalUiState => ({
    condition: 'e2e/login.spec.ts 全部通过',
    phase: 'active',
    iterations: 1,
    setAt: Date.now() - 5000,
    // 基线快照与真实消耗刻意取**不同**数值：任一断言若读错字段立刻能看出来
    //   （真机前科：条上显示 0 tok 而同期明细表已跑掉 134k output token）。
    tokensAtStart: 12400,
    tokensUsed: 5730,
    lastReason: null,
    ...over,
  })

  function renderHead(sessionId: string | null) {
    root = createRoot(container)
    act(() => {
      root.render(<GoalHead sessionId={sessionId} />)
    })
  }

  const head = () => container.querySelector('.goalhead')
  const scan = () => container.querySelector('.gh-scan')

  beforeEach(() => {
    (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    container = document.createElement('div')
    document.body.appendChild(container)
    useChatStore.setState({ goal: {} })
  })

  afterEach(() => {
    act(() => { root?.unmount() })
    container.remove()
    useChatStore.setState({ goal: {} })
    vi.useRealTimers()
  })

  it('无目标 ⇒ 不渲染（零高度，与今天像素级一致）', () => {
    renderHead(SID)
    expect(head()).toBeNull()
    expect(container.firstChild).toBeNull()
  })

  it('sessionId=null ⇒ 不渲染', () => {
    useChatStore.setState({ goal: { [SID]: goal() } })
    renderHead(null)
    expect(head()).toBeNull()
  })

  it('追之中：渲染蓝条 + 条件文字 + 轮数 + 扫光条', () => {
    useChatStore.setState({ goal: { [SID]: goal() } })
    renderHead(SID)

    expect(head()).not.toBeNull()
    expect(head()!.className).not.toContain('achieved')
    expect(head()!.className).not.toContain('failed')
    expect(container.textContent).toContain('e2e/login.spec.ts 全部通过')
    // 第 N 轮（已评估 1 轮 ⇒ 下一个是第 2 轮）
    expect(container.textContent).toContain('第 2 轮')
    // ⚠️ 扫光条：只在进行中态
    expect(scan()).not.toBeNull()
  })

  it('[goal tokens] 条上显示**真实消耗**（tokensUsed），不是基线快照 tokensAtStart', () => {
    // WHY: 前科 —— 条上读的是 tokensAtStart（设定时的基线），真机目标是 0 时恒显示「0 tok」，
    //   而同一时段明细表里已跑掉 13 万 output token。夹具里两个数刻意不同（12400 vs 5730）：
    //   若实现读错字段，下面的 5.7k 会变成 12.4k。
    useChatStore.setState({ goal: { [SID]: goal() } })
    renderHead(SID)

    expect(container.textContent).toContain('5.7k tok')
    expect(container.textContent).not.toContain('12.4k')
  })

  it('[goal tokens] tokensUsed 为 null ⇒ 不渲染 tok 段（⛔ 不显示假的 0）', () => {
    // WHY: 后端「取不到」（统计未接线/查询异常）与「确实是 0」（新设目标还没跑）必须可分。
    //   混成 0 会让用户以为目标白跑了一圈。
    useChatStore.setState({ goal: { [SID]: goal({ tokensUsed: null }) } })
    renderHead(SID)

    expect(head()).not.toBeNull()
    expect(container.textContent).not.toContain('tok')
    expect(container.textContent).not.toContain('0 tok')
  })

  it('[goal tokens] tokensUsed = 0 ⇒ 正常显示 0 tok（真的是 0 要显示得出来）', () => {
    useChatStore.setState({ goal: { [SID]: goal({ tokensUsed: 0 }) } })
    renderHead(SID)

    expect(container.textContent).toContain('0 tok')
  })

  it('[goal tokens] 详情浮层「输出 token」同样读真实消耗', () => {
    useChatStore.setState({ goal: { [SID]: goal() } })
    renderHead(SID)
    act(() => { (head() as HTMLElement).click() })

    const pop = container.querySelector('.goal-pop')!
    expect(pop.textContent).toContain('输出 token')
    expect(pop.textContent).toContain('5.7k')
    expect(pop.textContent).not.toContain('12.4k')
  })

  it('[goal tokens] tokensUsed 为 null ⇒ 详情浮层不渲染「输出 token」整格', () => {
    useChatStore.setState({ goal: { [SID]: goal({ tokensUsed: null }) } })
    renderHead(SID)
    act(() => { (head() as HTMLElement).click() })

    const pop = container.querySelector('.goal-pop')!
    expect(pop.textContent).not.toContain('输出 token')
    // 阳性对照：同网格里的「已运行」「已评估」仍在（证明不是整块没渲染）
    expect(pop.textContent).toContain('已运行')
    expect(pop.textContent).toContain('已评估')
  })

  it('已达成：绿条 + 无扫光条', () => {
    useChatStore.setState({ goal: { [SID]: goal({ phase: 'achieved', iterations: 4 }) } })
    renderHead(SID)

    expect(head()!.className).toContain('achieved')
    expect(scan()).toBeNull()
    expect(container.textContent).toContain('已达成')
  })

  it('无法达成：红条 + 无扫光条', () => {
    useChatStore.setState({ goal: { [SID]: goal({ phase: 'impossible' }) } })
    renderHead(SID)

    expect(head()!.className).toContain('failed')
    expect(scan()).toBeNull()
    expect(container.textContent).toContain('无法达成')
  })

  it('多会话隔离：只显示当前会话那一份', () => {
    useChatStore.setState({
      goal: {
        'sess-a': goal({ condition: 'A 的目标' }),
        'sess-b': goal({ condition: 'B 的目标' }),
      },
    })
    renderHead('sess-a')
    expect(container.textContent).toContain('A 的目标')
    expect(container.textContent).not.toContain('B 的目标')
  })

  it('点击条身展开详情浮层（显示完整条件 + 最近一次判定）', () => {
    useChatStore.setState({
      goal: { [SID]: goal({ lastReason: '尚未满足 — 只有单测通过' }) },
    })
    renderHead(SID)

    expect(container.querySelector('.goal-pop')).toBeNull()
    act(() => { (head() as HTMLElement).click() })
    expect(container.querySelector('.goal-pop')).not.toBeNull()
    expect(container.textContent).toContain('尚未满足 — 只有单测通过')
  })

  it('× 按钮清除该会话目标（走后端 DELETE + 清前端，不碰其它会话）', async () => {
    // [2026-11-04] × 现在先弹确认框：用户点头才真清（防误点）。
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    useChatStore.setState({
      goal: { [SID]: goal(), 'sess-other': goal({ condition: '别的会话' }) },
    })
    renderHead(SID)

    await act(async () => {
      ;(container.querySelector('.gh-x') as HTMLElement).click()
      // 等 clearGoal 的 await 链（goalApi.clear → setGoal）走完
      await Promise.resolve()
      await Promise.resolve()
    })

    expect(confirmSpy).toHaveBeenCalled()
    expect(useChatStore.getState().goal[SID]).toBeUndefined()
    expect(useChatStore.getState().goal['sess-other']).toBeDefined()
    confirmSpy.mockRestore()
  })

  it('× 确认框点「取消」⇒ 目标原样保留（不发 DELETE）', async () => {
    // WHY: 确认框的意义就是「点错了能退回去」。若取消也照清，这道确认形同虚设。
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    useChatStore.setState({ goal: { [SID]: goal() } })
    renderHead(SID)

    await act(async () => {
      ;(container.querySelector('.gh-x') as HTMLElement).click()
      await Promise.resolve()
      await Promise.resolve()
    })

    expect(confirmSpy).toHaveBeenCalled()
    expect(useChatStore.getState().goal[SID]).toBeDefined()
    confirmSpy.mockRestore()
  })

  it('达成后 6 秒自动清除该会话目标', () => {
    vi.useFakeTimers()
    useChatStore.setState({ goal: { [SID]: goal({ phase: 'achieved' }) } })
    renderHead(SID)

    act(() => { vi.advanceTimersByTime(5900) })
    expect(useChatStore.getState().goal[SID]).toBeDefined()
    act(() => { vi.advanceTimersByTime(200) })
    expect(useChatStore.getState().goal[SID]).toBeUndefined()
  })
})
