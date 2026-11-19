// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * 任务清单胶囊的进度口径 = **已完成/总数**。
 *
 * <h2>WHY（用户 2026-11-19）</h2>
 * <p>原实现有进行中任务时显示「进行中数/总数」（如 1/12），看起来像「还剩多少」，不是进度。
 * 用户要求显示 11/12 这种「已完成/总数」才直观。
 *
 * <h2>RED teeth</h2>
 * <ul>
 *   <li>把标签退回 {@code runningRow > 0 ? runningRow : done} ⇒ 本用例红（显示 1/12）。</li>
 * </ul>
 */

const listSnapshotMock = vi.hoisted(() => vi.fn())
const listMock = vi.hoisted(() => vi.fn())
vi.mock('@/api/tasks', () => ({
  tasksApi: { listSnapshot: listSnapshotMock, list: listMock },
}))

import { TaskListDock } from '@/components/center/TaskListDock'

async function flush(ms = 0): Promise<void> {
  await act(async () => { await new Promise((r) => setTimeout(r, ms)) })
}

describe('TaskListDock · 进度口径', () => {
  let host: HTMLDivElement
  let root: Root | null = null

  beforeEach(() => {
    host = document.createElement('div')
    document.body.appendChild(host)
    listSnapshotMock.mockReset()
    listMock.mockReset()
    listMock.mockResolvedValue([])
  })

  afterEach(() => {
    act(() => { root?.unmount() })
    root = null
    host.remove()
  })

  it('有进行中任务时也显示 已完成/总数（11/12），不是进行中数/总数', async () => {
    listSnapshotMock.mockResolvedValue({
      v2Tasks: [
        ...Array.from({ length: 11 }, (_, i) => ({
          id: `t${i}`, subject: `任务 ${i}`, status: 'completed',
        })),
        { id: 't11', subject: '最后一个任务', status: 'in_progress' },
      ],
      v1Todos: [],
    })

    await act(async () => {
      root = createRoot(host)
      root.render(<TaskListDock sessionId="sess-1" running />)
    })
    await flush(10)

    const label = host.querySelector('.mrh-label')?.textContent ?? ''
    expect(label, '胶囊必须是 已完成/总数').toBe('任务 11/12')
    expect(label, '不得显示进行中数（旧口径 1/12）').not.toBe('任务 1/12')
  })
})
