// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Project } from '@/types'
import { useSubagentStore } from '@/stores/subagentStore'
import { RightPanel } from '@/components/right/RightPanel'

/**
 * [cross-session] 右栏子代理详情必须属于当前会话。
 *
 * <h2>WHY</h2>
 * <p>{@code AgentTranscriptView} 用 {@code activeSessionId} 拼
 * {@code GET /sessions/{sessionId}/subagents/{agentId}/transcript}，后端按 sessionDir 定位。
 * 切换会话后若旧的 {@code viewing} 不退出，就会用**新会话 ID** 查**旧会话的子代理** → 必 404，
 * 且运行态会每 1.2s 重试 20 次（真机日志实测）。用户诉求：切会话就回本会话的右栏。
 *
 * <h2>RED teeth</h2>
 * <ul>
 *   <li>删掉 RightPanel 的 {@code useEffect(() => setViewing(null), [activeSessionId])}
 *       ⇒ 本用例红（.agent-transcript 仍存在且 transcript 用新 sessionId 被调用）；</li>
 *   <li>只清 viewing 不清 viewingHere 条件 ⇒ 一帧内仍可能用新 sessionId 发请求（本用例按最终态断言）。</li>
 * </ul>
 */

const transcriptMock = vi.hoisted(() =>
  vi.fn(async (_sessionId: string, _agentId: string) => [] as unknown[]),
)

vi.mock('@/api/subagents', () => ({
  subagentApi: { transcript: transcriptMock, list: vi.fn(async () => []) },
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
const OTHER_SID = 'sess-2'

async function flush(ms = 0): Promise<void> {
  await act(async () => { await new Promise((r) => setTimeout(r, ms)) })
}

describe('[cross-session] 右栏子代理详情不跨会话残留', () => {
  let container: HTMLDivElement
  let root: Root

  beforeEach(() => {
    ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    localStorage.clear()
    useSubagentStore.setState({ sessionId: null, bySession: {} })
    transcriptMock.mockClear()
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    useSubagentStore.getState().register(null, 't-1', 'alice', 'local_agent', SID)
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

  it('切会话 → 详情退出，且不再用新 sessionId 请求旧 agent', async () => {
    render(SID)
    await flush()

    const row = container.querySelector('.todo-row')
    expect(row, '子代理行必须存在（否则点的是别的路径）').not.toBeNull()
    await act(async () => { row!.dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    await flush()

    expect(container.querySelector('.agent-transcript')).not.toBeNull()
    expect(transcriptMock.mock.calls.some(([s]) => s === SID)).toBe(true)

    transcriptMock.mockClear()
    render(OTHER_SID)
    await flush()

    expect(container.querySelector('.agent-transcript'), '切会话后旧子代理详情必须退出').toBeNull()
    expect(
      transcriptMock.mock.calls.some(([s]) => s === OTHER_SID),
      '不得用新会话 ID 请求旧 agent 的 transcript（后端必 404 且会重试）',
    ).toBe(false)
  })
})
