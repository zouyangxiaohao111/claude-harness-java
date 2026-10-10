// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Project } from '@/types'
import { useSubagentStore } from '@/stores/subagentStore'
import { RightPanel } from '@/components/right/RightPanel'

/**
 * D3 · 子代理任务卡片新增统计行（`33.6k tokens · 5 次调用 · 39.5s`）。
 *
 * <h2>WHY（规则九 · 意图而非行为）</h2>
 * <p>卡片此前只有「名字 + 任务类型 + 状态」，用户看不到某个子代理烧了多少 token / 调了几次工具 /
 * 跑了多久 —— 失败中止时更无从判断「已消耗」。统计行必须：运行中实时（数据随 task 事件更新）、
 * 失败/中止同样显示已消耗、**数据缺失时不渲染空白**而是「统计不可用」。
 *
 * <h2>RED（改坏哪条红）</h2>
 * <ul>
 *   <li>删掉统计行 / 只在 running 时渲染 → 「失败卡片同样显示已消耗」红；</li>
 *   <li>无 usage 时渲染空串 → 「统计不可用」红；</li>
 *   <li>把 durationMs 直接透传（不打格式化）→ 「39.5s」红。</li>
 * </ul>
 *
 * <h2>手法</h2>
 * 沿用 {@code RightPanel.stopSubagentCleanup.test.tsx} 的既有 jsdom 真实渲染 + 邻接面板置空模式；
 * 卡片在「进行中」三态弹窗内（点 .sa-stat 打开）。
 */

vi.mock('@tauri-apps/api/core', () => ({ isTauri: () => false, invoke: vi.fn(async () => undefined) }))
vi.mock('@/api/tasks', () => ({
  tasksApi: { killTask: vi.fn(), list: vi.fn(() => Promise.resolve([])), stopAllTasks: vi.fn() },
}))
vi.mock('@/api/workflows', () => ({
  workflowApi: { listRuns: vi.fn(() => Promise.resolve([])), killRun: vi.fn(), getRun: vi.fn() },
}))
vi.mock('@/api/projects', () => ({ projectApi: {} }))
vi.mock('@/api/subagents', () => ({ subagentApi: {} }))
vi.mock('@/hooks/useSchedules', () => ({ useSchedules: () => ({ list: [], loading: false }) }))
vi.mock('@/components/modals/SchedulesPanel', () => ({ cronToHuman: () => '' }))
vi.mock('@/components/right/TeamPanel', () => ({ TeamPanel: () => null }))
vi.mock('@/components/right/TodoPanel', () => ({ TodoPanel: () => null }))
vi.mock('@/components/right/AsyncTasksPanel', () => ({ AsyncTasksPanel: () => null }))

const SID = 'sess-1'

async function flush(): Promise<void> {
  await act(async () => { await new Promise((r) => setTimeout(r, 0)) })
}

function click(el: Element | null): void {
  expect(el, '待点击元素必须存在（否则断言的是别的路径）').not.toBeNull()
  el!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
}

describe('D3 · 子代理卡片统计行', () => {
  let container: HTMLDivElement
  let root: Root

  beforeEach(() => {
    ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    localStorage.clear()
    useSubagentStore.setState({ sessionId: null, bySession: {} })
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
  })

  afterEach(() => {
    act(() => { root.unmount() })
    container.remove()
  })

  /** 打开三态弹窗（running=进行中 / done=已完成 / stopped=已停止桶，后者含 failed —— 见 RightPanel 分组）。 */
  async function openModal(bucket: 'running' | 'done' | 'stopped'): Promise<void> {
    await act(async () => {
      root.render(
        <RightPanel
          activeSessionId={SID}
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
    await flush()
    click(container.querySelector(`.sa-stat.${bucket}`))
    await flush()
  }

  it('运行中：显示 tokens · 次数 · 时长（数据来自 task 事件 usage）', async () => {
    useSubagentStore.getState().register('tu-1', 't-1', 'alice', 'local_agent', SID)
    useSubagentStore.getState().setUsage('t-1', { totalTokens: 33600, toolUses: 5, durationMs: 39500 }, SID)
    await openModal('running')

    const stats = container.querySelector('.subagent-card .sc-stats')
    expect(stats, '卡片统计行必须存在').not.toBeNull()
    expect(stats?.textContent).toContain('33.6k tokens')
    expect(stats?.textContent).toContain('5 次调用')
    // 运行中：时长按「卡片本地实时已耗时」渲染（事件 duration_ms 会停在事件到达那一刻），故只断言格式
    expect(stats?.textContent).toMatch(/· \d+(\.\d+)?(s|m\d+s)$/)
  })

  it('失败/中止同样显示已消耗（终态事件 usage 照常渲染，不隐藏）', async () => {
    useSubagentStore.getState().register(null, 't-1', 'alice', 'local_agent', SID)
    useSubagentStore.getState().setUsage('t-1', { totalTokens: 33600, toolUses: 5, durationMs: 39500 }, SID)
    useSubagentStore.getState().addActivity('t-1', { type: 'failed', text: 'boom', ts: 1 }, SID)
    // failed 落在「已停止」桶（RightPanel: stopped = failed || stopped）
    await openModal('stopped')

    const stats = container.querySelector('.subagent-card .sc-stats')
    expect(stats?.textContent).toBe('33.6k tokens · 5 次调用 · 39.5s')
  })

  it('运行中且无 usage（task_progress 默认被 sdkAgentProgressSummariesEnabled 门掉）→ 仍显示本地实时已耗时，而非「统计不可用」', async () => {
    useSubagentStore.getState().register(null, 't-1', 'alice', 'local_agent', SID)
    await openModal('running')

    const stats = container.querySelector('.subagent-card .sc-stats')
    expect(stats?.textContent).toMatch(/^已运行 \d+(\.\d+)?s$/)
  })

  it('终态且无 usage → 「统计不可用」（真无任何信息；不得显示假时长）', async () => {
    useSubagentStore.getState().register(null, 't-1', 'alice', 'local_agent', SID)
    useSubagentStore.getState().addActivity('t-1', { type: 'done', text: 'ok', ts: 1 }, SID)
    await openModal('done')

    const stats = container.querySelector('.subagent-card .sc-stats')
    expect(stats?.textContent).toBe('统计不可用')
  })

  it('[R3-3] 冷启动恢复的 running（restored）不启用本地计时 → 用事件里的时长，而不是假的「已运行 3 小时」', async () => {
    // 冷启动恢复态：loadSaved 会把 running 标 restored（此处直接构造同等状态）
    useSubagentStore.setState({
      sessionId: null,
      bySession: {
        [SID]: {
          't-stale': {
            name: 'stale', color: '#000', taskType: 'local_agent', taskId: 't-stale',
            status: 'running', currentTool: null, restored: true,
            usage: { totalTokens: 100, toolUses: 2, durationMs: 5000 },
            activities: [{ type: 'start', text: 'stale', ts: Date.now() - 3 * 60 * 60 * 1000 }],
          },
        },
      },
    })
    await openModal('running')

    const stats = container.querySelector('.subagent-card .sc-stats')
    // 判别力：若本地计时被误启用（忽略 restored），此处会显示「… · 10800.xs」→ 断言红
    expect(stats?.textContent).toBe('100 tokens · 2 次调用 · 5.0s')
  })
})
