// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * 任务清单「全部完成 5 秒后清空」· 对齐 CC useTasksV2.ts:157-176。
 *
 * <h2>WHY</h2>
 * <p>原先全部完成只是不渲染，任务文件永远留在磁盘；下一批任务一出现，旧批已完成的
 * 也一起计入总数（如 12 旧 + 1 新 = 1/13）。CC 的做法是 5 秒后调 resetTaskList：
 * 提升 high water mark 并删除任务目录下所有 *.json。
 *
 * <h2>RED teeth</h2>
 * <ul>
 *   <li>去掉 5 秒定时 reset ⇒ 用例 1 红（resetTaskList 未被调用、胶囊不消失）。</li>
 *   <li>不取消计时 / 不复核 ⇒ 用例 2 红（任务变回未完成仍被 reset）。</li>
 * </ul>
 */

const listSnapshotMock = vi.hoisted(() => vi.fn())
const resetTaskListMock = vi.hoisted(() => vi.fn())
const listMock = vi.hoisted(() => vi.fn())
vi.mock('@/api/tasks', () => ({
  tasksApi: { listSnapshot: listSnapshotMock, resetTaskList: resetTaskListMock, list: listMock },
}))

import { TaskListDock } from '@/components/center/TaskListDock'

const ALL_DONE = {
  v2Tasks: [
    { id: '1', subject: '任务一', status: 'completed' },
    { id: '2', subject: '任务二', status: 'completed' },
  ],
  v1Todos: [],
}

const HAS_PENDING = {
  v2Tasks: [
    { id: '1', subject: '任务一', status: 'completed' },
    { id: '2', subject: '任务二', status: 'in_progress' },
  ],
  v1Todos: [],
}

describe('TaskListDock · 全部完成 5 秒后 resetTaskList', () => {
  let host: HTMLDivElement
  let root: Root | null = null

  beforeEach(() => {
    vi.useFakeTimers()
    host = document.createElement('div')
    document.body.appendChild(host)
    listSnapshotMock.mockReset()
    resetTaskListMock.mockReset()
    listMock.mockReset()
    listMock.mockResolvedValue([])
  })

  afterEach(() => {
    act(() => { root?.unmount() })
    root = null
    host.remove()
    vi.useRealTimers()
  })

  it('全部完成 → 保留 5 秒 → 调 resetTaskList 并隐藏胶囊', async () => {
    listSnapshotMock.mockResolvedValue(ALL_DONE)
    resetTaskListMock.mockResolvedValue({ success: true })

    await act(async () => {
      root = createRoot(host)
      root.render(<TaskListDock sessionId="sess-1" running={false} />)
    })
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })

    expect(host.querySelector('.mrh-label')?.textContent, '全部完成仍需先显示').toBe('任务 2/2')
    expect(resetTaskListMock).not.toHaveBeenCalled()

    await act(async () => { await vi.advanceTimersByTimeAsync(5000) })

    expect(resetTaskListMock, '5 秒后必须删任务文件').toHaveBeenCalledWith('sess-1')
    expect(host.querySelector('.mrh-label'), 'reset 后胶囊隐藏').toBeNull()
  })

  it('5 秒窗口内出现未完成任务 → 取消计时，不 reset', async () => {
    listSnapshotMock
      .mockResolvedValueOnce(ALL_DONE)
      .mockResolvedValue(HAS_PENDING)

    await act(async () => {
      root = createRoot(host)
      root.render(<TaskListDock sessionId="sess-1" running={false} />)
    })
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    // 2 秒轮询把状态改成未完成 → allDone 变 false，计时器取消
    await act(async () => { await vi.advanceTimersByTimeAsync(2000) })
    await act(async () => { await vi.advanceTimersByTimeAsync(3000) })

    expect(resetTaskListMock).not.toHaveBeenCalled()
    expect(host.querySelector('.mrh-label')?.textContent).toBe('任务 1/2')
  })

  it('metadata._internal 的任务不参与显示与完成判断（对齐 CC 过滤）', async () => {
    listSnapshotMock.mockResolvedValue({
      v2Tasks: [
        { id: '1', subject: '公开任务', status: 'completed' },
        { id: '2', subject: '内部任务', status: 'in_progress', metadata: { _internal: true } },
      ],
      v1Todos: [],
    })

    await act(async () => {
      root = createRoot(host)
      root.render(<TaskListDock sessionId="sess-1" running={false} />)
    })
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })

    expect(host.querySelector('.mrh-label')?.textContent, '内部任务不计数').toBe('任务 1/1')
  })

  it('V1 todos 全部完成 → 直接隐藏，不调 reset（V1 无任务文件可删）', async () => {
    listSnapshotMock.mockResolvedValue({
      v2Tasks: [],
      v1Todos: [
        { content: 'todo 一', status: 'completed' },
        { content: 'todo 二', status: 'completed' },
      ],
    })

    await act(async () => {
      root = createRoot(host)
      root.render(<TaskListDock sessionId="sess-1" running={false} />)
    })
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    await act(async () => { await vi.advanceTimersByTimeAsync(5000) })

    expect(host.querySelector('.mrh-label'), 'V1 全完成保持原语义直接隐藏').toBeNull()
    expect(resetTaskListMock, 'V1 不触发 reset').not.toHaveBeenCalled()
  })
})
