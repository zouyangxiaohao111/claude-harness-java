// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { GoalSetPanel } from '../GoalSetPanel'

/**
 * [goal-set 2026-11-04] 设定目标面板契约。
 *
 * <p>WHY: 面板是「+ → 目标」的落地，用户在这里输入条件后等价于发一条
 * `/goal <条件>`。三条不变量：
 * <ol>
 *   <li><b>空输入 / 全空白 ⇒ 主按钮置灰</b>（不能让用户提交一个空目标 —— 后端也会拒，但
 *       前端先挡住，避免用户点了没反应不知道为什么）；</li>
 *   <li><b>提交传的是 trim 后的文本</b>（行尾空格不该进 sessions.goal）；</li>
 *   <li><b>取消不回传</b>（取消 ≠ 提交空目标）。</li>
 * </ol>
 *
 * <p>⚠️ React 状态更新是异步的：派发 input 事件后必须等一帧再查 DOM，同一同步块里
 * querySelector 恒拿到旧值（本仓踩过多次的假阴性）。
 */
describe('[goal-set] GoalSetPanel', () => {
  let container: HTMLDivElement
  let root: Root

  beforeEach(() => {
    (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    container = document.createElement('div')
    document.body.appendChild(container)
  })

  afterEach(() => {
    act(() => { root?.unmount() })
    container.remove()
  })

  function render(props: {
    onSubmit?: (c: string) => Promise<void>
    onCancel?: () => void
    initialCondition?: string
  } = {}) {
    root = createRoot(container)
    act(() => {
      root.render(
        <GoalSetPanel
          onSubmit={props.onSubmit ?? (async () => undefined)}
          onCancel={props.onCancel ?? (() => undefined)}
          initialCondition={props.initialCondition}
        />,
      )
    })
  }

  const textarea = () => container.querySelector('.gs-input') as HTMLTextAreaElement
  const primary = () => container.querySelector('.gs-btn-primary') as HTMLButtonElement
  const ghost = () => container.querySelector('.gs-btn-ghost') as HTMLButtonElement

  /** 给受控 textarea 赋值：必须走 React 的原生 setter，否则 onChange 不触发。 */
  async function type(value: string) {
    const el = textarea()
    const setter = Object.getOwnPropertyDescriptor(
      window.HTMLTextAreaElement.prototype, 'value')!.set!
    await act(async () => {
      setter.call(el, value)
      el.dispatchEvent(new Event('input', { bubbles: true }))
    })
  }

  it('空输入 ⇒ 主按钮置灰', () => {
    render()
    expect(primary()).not.toBeNull()
    expect(primary().disabled).toBe(true)
  })

  it('只有空白 ⇒ 仍然置灰（不能提交一个空目标）', async () => {
    render()
    await type('   \n  ')
    expect(primary().disabled).toBe(true)
  })

  it('填了条件 ⇒ 可提交，且提交的是 trim 后的文本', async () => {
    const onSubmit = vi.fn(async () => undefined)
    render({ onSubmit })
    await type('  e2e 测试全部通过  ')

    expect(primary().disabled).toBe(false)
    await act(async () => { primary().click() })

    expect(onSubmit).toHaveBeenCalledTimes(1)
    expect(onSubmit).toHaveBeenCalledWith('e2e 测试全部通过')
  })

  it('取消 ⇒ 只调 onCancel，绝不提交（取消不等于提交空目标）', async () => {
    const onSubmit = vi.fn(async () => undefined)
    const onCancel = vi.fn()
    render({ onSubmit, onCancel })
    await type('条件')

    await act(async () => { ghost().click() })

    expect(onCancel).toHaveBeenCalledTimes(1)
    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('超出 4000 字符 ⇒ 置灰并给出计数提示（后端也拦，前端先挡）', async () => {
    render()
    await type('x'.repeat(4001))
    expect(primary().disabled).toBe(true)
    expect(container.textContent).toContain('4001 / 4000')
  })

  it('initialCondition 预填（已有目标时改写）', () => {
    render({ initialCondition: '旧条件' })
    expect(textarea().value).toBe('旧条件')
  })
})
