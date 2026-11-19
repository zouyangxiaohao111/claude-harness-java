// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * 清单显示层对齐 CC TaskListV2：
 *  - owner 徽标 `(@名字)`（CC TaskListV2.tsx:264-273）
 *  - 超限重排：最近完成 → 进行中 → 未开始（未阻塞优先）→ 旧完成（:146-171）
 *  - 隐藏摘要 `… +N 个进行中，M 个未开始，K 个已完成`（:192-204）
 *
 * <h2>RED teeth</h2>
 * <ul>
 *   <li>去掉 owner 渲染 ⇒ 用例 1 红。</li>
 *   <li>退回 `rows.slice(0, 12)` ⇒ 用例 2 红（进行中任务被旧完成挤掉）。</li>
 *   <li>退回「还有 N 条…」⇒ 用例 3 红。</li>
 * </ul>
 */

const listSnapshotMock = vi.hoisted(() => vi.fn())
const resetTaskListMock = vi.hoisted(() => vi.fn())
const listMock = vi.hoisted(() => vi.fn())
vi.mock('@/api/tasks', () => ({
  tasksApi: { listSnapshot: listSnapshotMock, resetTaskList: resetTaskListMock, list: listMock },
}))

import { TaskListDock } from '@/components/center/TaskListDock'

async function flush(ms = 0): Promise<void> {
  await act(async () => { await new Promise((r) => setTimeout(r, ms)) })
}

async function openPanel(host: HTMLDivElement): Promise<HTMLElement> {
  const btn = host.querySelector('.monitor-running-hint') as HTMLButtonElement | null
  expect(btn, '胶囊应已渲染').not.toBeNull()
  await act(async () => { btn!.click() })
  const panel = document.querySelector('.task-list-pop') as HTMLElement | null
  expect(panel, '明细面板应已 portal 到 body').not.toBeNull()
  return panel!
}

describe('TaskListDock · 显示层对齐 CC', () => {
  let host: HTMLDivElement
  let root: Root | null = null

  beforeEach(() => {
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
    document.querySelectorAll('.task-list-pop').forEach((n) => n.remove())
  })

  it('显示 owner 徽标 (@名字)', async () => {
    listSnapshotMock.mockResolvedValue({
      v2Tasks: [
        { id: '1', subject: '进行中的任务', status: 'in_progress', owner: 'researcher' },
        { id: '2', subject: '待办任务', status: 'pending' },
      ],
      v1Todos: [],
    })

    await act(async () => {
      root = createRoot(host)
      root.render(<TaskListDock sessionId="sess-1" running />)
    })
    await flush(10)

    const panel = await openPanel(host)
    expect(panel.textContent).toContain('(@researcher)')
  })

  it('超 12 条时进行中任务排在最前（优先于旧完成）', async () => {
    listSnapshotMock.mockResolvedValue({
      v2Tasks: [
        ...Array.from({ length: 12 }, (_, i) => ({
          id: String(i + 1), subject: `旧任务 ${i + 1}`, status: 'completed',
        })),
        { id: '13', subject: '进行中任务', status: 'in_progress' },
      ],
      v1Todos: [],
    })

    await act(async () => {
      root = createRoot(host)
      root.render(<TaskListDock sessionId="sess-1" running />)
    })
    await flush(10)

    const panel = await openPanel(host)
    // 第一行必须是被挤到队首的进行中任务（旧实现直接 slice 会把它排在第 13 位=不显示）
    expect(panel.textContent?.indexOf('进行中任务')).toBeGreaterThanOrEqual(0)
    expect(panel.textContent).toContain('进行中任务')
    const firstRow = panel.querySelector('div[style*="align-items: flex-start"]')
    expect(firstRow?.textContent).toContain('进行中任务')
  })

  it('隐藏摘要按状态分项统计，不再是「还有 N 条」', async () => {
    listSnapshotMock.mockResolvedValue({
      v2Tasks: [
        ...Array.from({ length: 12 }, (_, i) => ({
          id: String(i + 1), subject: `旧任务 ${i + 1}`, status: 'completed',
        })),
        { id: '13', subject: '进行中任务', status: 'in_progress' },
      ],
      v1Todos: [],
    })

    await act(async () => {
      root = createRoot(host)
      root.render(<TaskListDock sessionId="sess-1" running />)
    })
    await flush(10)

    const panel = await openPanel(host)
    expect(panel.textContent).toContain('… +1 个已完成')
    expect(panel.textContent).not.toContain('还有 1 条')
  })

  it('进行中任务第二行显示 teammate 活动摘要（owner 匹配 ownerName）', async () => {
    listSnapshotMock.mockResolvedValue({
      v2Tasks: [
        { id: '1', subject: '实现登录接口', status: 'in_progress', owner: 'researcher' },
        { id: '2', subject: '待办任务', status: 'pending' },
      ],
      v1Todos: [],
    })
    listMock.mockResolvedValue([
      { id: 'a1', type: 'in_process_teammate', status: 'running',
        description: 'researcher: 实现登录', ownerName: 'researcher',
        activity: '正在读取 src/auth/login.ts' },
    ])

    await act(async () => {
      root = createRoot(host)
      root.render(<TaskListDock sessionId="sess-1" running />)
    })
    await flush(10)

    const panel = await openPanel(host)
    expect(panel.textContent, '活动摘要渲染在任务行第二行（末尾 …）')
      .toContain('正在读取 src/auth/login.ts…')
  })
})
