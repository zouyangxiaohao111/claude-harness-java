// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { GoalHead } from '../GoalHead'
import { useChatStore } from '@/stores/chatStore'
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
    tokensAtStart: 12400,
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

  it('× 按钮清除该会话目标（不碰其它会话）', () => {
    useChatStore.setState({
      goal: { [SID]: goal(), 'sess-other': goal({ condition: '别的会话' }) },
    })
    renderHead(SID)

    act(() => { (container.querySelector('.gh-x') as HTMLElement).click() })
    expect(useChatStore.getState().goal[SID]).toBeUndefined()
    expect(useChatStore.getState().goal['sess-other']).toBeDefined()
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
