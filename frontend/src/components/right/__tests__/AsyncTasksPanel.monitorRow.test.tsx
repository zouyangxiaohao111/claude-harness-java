// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { BackgroundTaskDto } from '@/api/tasks'

/**
 * Monitor 任务在「异步任务」面板里的可读性（真实渲染）。
 *
 * <h2>WHY（意图验证 · 规则九）</h2>
 * <p>Monitor 任务的底层 type 在 CC 是 local_bash + kind=monitor；本仓用独立 type=monitor。
 * 若前端不认这个 type，用户会看到一条「命令 · 运行中」的普通任务，无法理解它在盯日志/连接。
 * 本用例断言三件事：副标题是「监控」、行有专属 class（视觉色条）、详情里有「监控方式」说明。
 */

const listMock = vi.hoisted(() => vi.fn())
const killMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/tasks', () => ({
  tasksApi: { list: listMock, killTask: killMock, stopAllTasks: vi.fn(), background: vi.fn(), backgroundAll: vi.fn() },
}))

import { AsyncTasksPanel } from '@/components/right/AsyncTasksPanel'

const SID = 'sess-1'

function monitorDto(): BackgroundTaskDto {
  return {
    id: 'o1', type: 'monitor', status: 'running',
    description: '盯 app.log 的 ERROR', startTime: Date.now(), isBackgrounded: true,
  }
}

async function flush(ms = 0): Promise<void> {
  await act(async () => { await new Promise((r) => setTimeout(r, ms)) })
}

function click(el: Element | null): void {
  expect(el, '待点击元素必须存在').not.toBeNull()
  el!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
}

describe('[monitor-cc] AsyncTasksPanel 监控任务显示', () => {
  let container: HTMLDivElement
  let root: Root

  beforeEach(() => {
    ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    localStorage.clear()
    listMock.mockReset()
    killMock.mockReset()
    listMock.mockImplementation(() => Promise.resolve([monitorDto()]))
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

  it('行副标题显示「监控」，并带专属 monitor class（视觉色条）', async () => {
    await mount()

    const row = container.querySelector('.todo-row.monitor')
    expect(row, 'monitor 类型的行必须命中 .todo-row.monitor').not.toBeNull()
    expect(row!.textContent).toContain('盯 app.log 的 ERROR')
    expect(row!.textContent).toContain('监控')
    expect(row!.textContent).not.toContain('命令')
  })

  it('点开详情显示「监控方式」说明', async () => {
    await mount()

    click(container.querySelector('.todo-row.monitor'))
    await flush()

    const modal = container.querySelector('.task-detail')
    expect(modal).not.toBeNull()
    expect(modal!.textContent).toContain('监控方式')
    expect(modal!.textContent).toContain('每一行输出')
  })
})
