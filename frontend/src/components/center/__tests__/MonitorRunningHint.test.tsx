// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { BackgroundTaskDto } from '@/api/tasks'

/**
 * 「正在监听」胶囊（主会话可见）。
 *
 * <h2>WHY（意图验证）</h2>
 * Monitor 在后台跑时，主会话只看到一条工具回执；用户在工作时不知道「现在在盯什么」。
 * 本用例断言：只统计 monitor 类型的运行中任务、直接显示监听描述、点击能打开运行面板。
 */

const listMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/tasks', () => ({
  tasksApi: { list: listMock, killTask: vi.fn(), stopAllTasks: vi.fn(), background: vi.fn(), backgroundAll: vi.fn() },
}))

import { MonitorRunningHint } from '@/components/center/MonitorRunningHint'

const SID = 'sess-1'

function dto(over: Partial<BackgroundTaskDto> & { id: string; type: string }): BackgroundTaskDto {
  return {
    status: 'running', description: '任务', startTime: Date.now(), isBackgrounded: true, ...over,
  }
}

async function flush(ms = 0): Promise<void> {
  await act(async () => { await new Promise((r) => setTimeout(r, ms)) })
}

describe('[monitor-cc] MonitorRunningHint', () => {
  let container: HTMLDivElement
  let root: Root

  beforeEach(() => {
    ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    listMock.mockReset()
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
  })

  afterEach(() => {
    act(() => { root.unmount() })
    container.remove()
  })

  async function mount(onOpen = () => {}) {
    await act(async () => {
      root.render(<MonitorRunningHint sessionId={SID} onOpen={onOpen} />)
    })
    await flush()
  }

  it('只显示 monitor 运行中任务，并直接给出监听描述', async () => {
    listMock.mockResolvedValue([
      dto({ id: 'o1', type: 'monitor', description: '盯 app.log 的 ERROR' }),
      dto({ id: 'b1', type: 'local_bash', description: 'npm build' }),
      dto({ id: 'o2', type: 'monitor', status: 'completed', description: '已结束的监控' }),
    ])

    await mount()

    const btn = container.querySelector('.monitor-running-hint')
    expect(btn).not.toBeNull()
    expect(btn!.textContent).toContain('正在监听：盯 app.log 的 ERROR')
    expect(btn!.textContent).not.toContain('npm build')
    expect(btn!.textContent).not.toContain('已结束的监控')
  })

  it('没有 monitor 运行中 ⇒ 不渲染', async () => {
    listMock.mockResolvedValue([
      dto({ id: 'b1', type: 'local_bash', description: 'npm build' }),
    ])

    await mount()

    expect(container.querySelector('.monitor-running-hint')).toBeNull()
  })

  it('点击胶囊触发 onOpen', async () => {
    listMock.mockResolvedValue([dto({ id: 'o1', type: 'monitor', description: '盯日志' })])
    const onOpen = vi.fn()

    await mount(onOpen)

    container.querySelector('.monitor-running-hint')!
      .dispatchEvent(new MouseEvent('click', { bubbles: true }))
    expect(onOpen).toHaveBeenCalledTimes(1)
  })
})
