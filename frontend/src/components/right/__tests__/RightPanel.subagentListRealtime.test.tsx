// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Project } from '@/types'
import { useSubagentStore } from '@/stores/subagentStore'
import { RightPanel } from '@/components/right/RightPanel'

/**
 * 右栏「子代理运行状况」列表的状态必须与真实执行一致。
 *
 * <h2>WHY（用户 2026-11-19 报的症状）</h2>
 * <p>「点进去已经完成，上面还是未知 / 未完成，不是实时展示」。
 *
 * <p>两个源键不同源是承重原因：实时身份来自任务事件，键是 {@code taskId}（UUID）；
 * 历史项来自磁盘 transcript，键是 {@code agentId}（a+16hex）。原实现只按 taskId 做去重
 * ⇒ 同一子代理出现两行，历史那行的状态是「transcript 末行推断」（运行中/无收尾文本 ⇒
 * 未收尾/未知）；而且历史列表只在切会话时拉一次，任务收尾后也不会刷新。
 *
 * <h2>RED teeth</h2>
 * <ul>
 *   <li>删掉 {@code transcriptKeyOf} 归一 ⇒ 第 1 个用例红（两行：进行中 + 未知）；</li>
 *   <li>删掉 5s 轮询（只首拉）⇒ 第 2 个用例红（永远停在「未收尾」）。</li>
 * </ul>
 */

const transcriptMock = vi.hoisted(() => vi.fn(async (_sessionId: string, _agentId: string) => [] as unknown[]))
const listMock = vi.hoisted(() => vi.fn(async (_sessionId: string, _limit?: number) => [] as unknown[]))

vi.mock('@/api/subagents', () => ({
  subagentApi: { transcript: transcriptMock, list: listMock },
}))
vi.mock('@/api/socket', () => ({ onSocketConnect: () => () => { /* 测试不模拟重连 */ } }))
vi.mock('@/api/tasks', () => ({
  tasksApi: { killTask: vi.fn(), list: vi.fn(() => Promise.resolve([])), stopAllTasks: vi.fn() },
}))
vi.mock('@/api/workflows', () => ({
  workflowApi: { listRuns: vi.fn(() => Promise.resolve([])), killRun: vi.fn(), getRun: vi.fn() },
}))
vi.mock('@/api/projects', () => ({ projectApi: {} }))
vi.mock('@/hooks/useSchedules', () => ({ useSchedules: () => ({ list: [], loading: false }) }))
vi.mock('@/components/modals/SchedulesPanel', () => ({ cronToHuman: () => '' }))
vi.mock('@/components/right/TeamPanel', () => ({ TeamPanel: () => null }))
vi.mock('@/components/right/TodoPanel', () => ({ TodoPanel: () => null }))
vi.mock('@/components/right/AsyncTasksPanel', () => ({ AsyncTasksPanel: () => null }))

const SID = 'sess-1'
/** 实时 taskId（BackgroundTask.id / 任务事件 task_id · UUID，SubagentTool.executeAsync） */
const TASK_ID = '01234567-89ab-cdef-0123-456789abcdef'
/** 磁盘 transcript 键（AgentContext.unpackAgentId(taskId) · 'a' + UUID 前 16 hex） */
const AGENT_HEX = 'a0123456789abcdef'

describe('右栏子代理列表 · 状态实时', () => {
  let container: HTMLDivElement
  let root: Root

  beforeEach(() => {
    ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    localStorage.clear()
    useSubagentStore.setState({ sessionId: null, bySession: {} })
    transcriptMock.mockClear()
    listMock.mockReset()
    listMock.mockResolvedValue([])
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
  })

  afterEach(() => {
    act(() => { root.unmount() })
    container.remove()
  })

  function render(sessionId: string): void {
    act(() => {
      root.render(
        <RightPanel
          activeSessionId={sessionId}
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
  }

  it('同一子代理不得出现两行：实时 UUID 与历史 a+16hex 必须归一到同一个键', async () => {
    useSubagentStore.getState().register(null, TASK_ID, 'alice', 'local_agent', SID)
    listMock.mockResolvedValue([
      { agentId: AGENT_HEX, agentType: 'local_agent', description: 'alice', status: 'unknown', createdAt: 1 },
    ])

    render(SID)
    await act(async () => { await new Promise((r) => setTimeout(r, 0)) })

    const rows = container.querySelectorAll('.todo-row')
    expect(rows.length, '同一子代理不得出两行（实时 UUID + 历史 a+16hex）').toBe(1)
    expect(rows[0].querySelector('.sub-status')?.textContent, '有实时身份时状态必须用实时值，不是磁盘推断')
      .toBe('进行中')
  })

  it('只有历史行时，状态随后端推断刷新（不再停在切会话那一刻）', async () => {
    vi.useFakeTimers()
    try {
      listMock.mockResolvedValueOnce([
        { agentId: AGENT_HEX, agentType: 'local_agent', description: 'alice', status: 'incomplete', createdAt: 1 },
      ])
      listMock.mockResolvedValue([
        { agentId: AGENT_HEX, agentType: 'local_agent', description: 'alice', status: 'completed', createdAt: 1 },
      ])

      render(SID)
      await act(async () => { await vi.advanceTimersByTimeAsync(0) })
      expect(container.querySelector('.sub-status')?.textContent).toBe('未收尾')
      const rowBefore = container.querySelector('.todo-row')

      // 只推到「定时器到点、请求刚发出」这一刻：响应还没回来时列表必须保持原样。
      // 若轮询前清空 state（旧列表 → 空 → 新列表），这里会先渲染「暂无子代理」= 闪一帧。
      act(() => { vi.advanceTimersByTime(5000) })
      expect(container.querySelectorAll('.todo-row').length, '请求在途时不得清空列表（会闪）').toBe(1)

      await act(async () => { await vi.advanceTimersByTimeAsync(0) })
      expect(container.querySelector('.sub-status')?.textContent, '轮询后必须跟随磁盘状态翻面').toBe('已完成')
      // 原地更新数据（key 稳定 ⇒ React 复用同一个 DOM 节点），不得重建行节点。
      expect(container.querySelector('.todo-row'), '轮询不得重建行节点（会闪）').toBe(rowBefore)
    } finally {
      vi.useRealTimers()
    }
  })
})
