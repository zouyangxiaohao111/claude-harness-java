// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SubagentRunningHint } from '@/components/center/SubagentRunningHint'
import { useSubagentStore } from '@/stores/subagentStore'

/**
 * 「子代理运行中」胶囊的行为契约。
 *
 * <b>WHY</b>：子代理跑在独立 loop，主会话对话流看不见它 —— 这个胶囊是主区唯一的可见信号，
 * 所以「有运行中的才出现、数量对、点了能打开右栏」三条都是功能本身，不是装饰。
 */

let container: HTMLDivElement
let root: Root

beforeEach(() => {
  (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  useSubagentStore.setState({ sessionId: null, bySession: {} })
})

afterEach(() => {
  act(() => root.unmount())
  container.remove()
})

const renderEl = (el: React.ReactElement) => act(() => { root.render(el) })

describe('SubagentRunningHint', () => {
  it('没有运行中的子代理 → 灰显常驻入口（不消失）· WHY：用户要随时知道入口在哪', () => {
    renderEl(<SubagentRunningHint sessionId="s1" onOpen={() => {}} />)
    const btn = container.querySelector('.subagent-running-hint')
    expect(btn).toBeTruthy()
    expect(btn!.classList.contains('idle')).toBe(true)
    expect(btn!.textContent).toContain('运行')
    expect(container.querySelector('.srh-count')).toBeNull()
  })

  it('tool_use_id / task_id 双键指向同一对象只算 1 个', () => {
    act(() => {
      useSubagentStore.getState().register('tu-1', 't-1', 'agent-a', 'local_bash', 's1')
    })
    renderEl(<SubagentRunningHint sessionId="s1" onOpen={() => {}} />)
    expect(container.querySelector('.srh-count')?.textContent).toBe('1')
  })

  it('多个子代理按 taskId 计数', () => {
    act(() => {
      useSubagentStore.getState().register('tu-1', 't-1', 'agent-a', 'local_bash', 's1')
      useSubagentStore.getState().register('tu-2', 't-2', 'agent-b', 'local_task', 's1')
    })
    renderEl(<SubagentRunningHint sessionId="s1" onOpen={() => {}} />)
    expect(container.querySelector('.srh-count')?.textContent).toBe('2')
  })

  it('终态（done）后不再计数 · WHY：跑完了还提示「运行中」会误导', () => {
    act(() => {
      useSubagentStore.getState().register('tu-1', 't-1', 'agent-a', 'local_bash', 's1')
      useSubagentStore.getState().addActivity('t-1', { type: 'done', text: 'done', ts: Date.now() }, 's1')
    })
    renderEl(<SubagentRunningHint sessionId="s1" onOpen={() => {}} />)
    expect(container.querySelector('.srh-count')).toBeNull()
    expect(container.querySelector('.subagent-running-hint')!.classList.contains('idle')).toBe(true)
  })

  it('只统计本会话 · WHY：多会话并行时不能把别的会话的子代理算进来', () => {
    act(() => {
      useSubagentStore.getState().register('tu-1', 't-1', 'agent-a', 'local_bash', 's2')
    })
    renderEl(<SubagentRunningHint sessionId="s1" onOpen={() => {}} />)
    expect(container.querySelector('.srh-count')).toBeNull()
    expect(container.querySelector('.subagent-running-hint')!.classList.contains('idle')).toBe(true)
  })

  it('点击 → 调 onOpen（App 负责打开右栏「运行」tab）', () => {
    const onOpen = vi.fn()
    act(() => {
      useSubagentStore.getState().register('tu-1', 't-1', 'agent-a', 'local_bash', 's1')
    })
    renderEl(<SubagentRunningHint sessionId="s1" onOpen={onOpen} />)
    const btn = container.querySelector('.subagent-running-hint')
    expect(btn).toBeTruthy()
    act(() => { btn!.dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    expect(onOpen).toHaveBeenCalledTimes(1)
  })
})
