// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useBackgroundRunningSessionIds } from '@/hooks/useSubagentRunning'
import { tasksApi } from '@/api/tasks'

/**
 * [侧栏运行点 · 后台任务那一路]
 *
 * <b>意图</b>：主 turn / 子代理都结束后，会话仍可能有后台 bash / monitor 在跑；这些任务
 * 不进 subagentStore ⇒ 侧栏原本熄灭。判据 = /tasks/active 里「未终态 + 有会话归属」的
 * sessionId 集合。反向实验：删掉 hook 里 `t.sessionId && (status==='running'||'pending')`
 * 的过滤 ⇒ 本用例红（把 completed / 无归属也收进来）。
 */
vi.mock('@/api/tasks', () => ({ tasksApi: { active: vi.fn() } }))

let last: Set<string> = new Set()
function Host() {
  last = useBackgroundRunningSessionIds()
  return null
}

async function flush() {
  await act(async () => {
    await Promise.resolve()
    await Promise.resolve()
    await Promise.resolve()
  })
}

let container: HTMLDivElement | undefined
let root: Root | undefined

beforeEach(() => {
  ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  last = new Set()
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
})

afterEach(() => {
  if (root) act(() => root!.unmount())
  container?.remove()
  container = undefined
  root = undefined
  vi.mocked(tasksApi.active).mockReset()
})

it('只把「未终态 + 有会话归属」的后台任务会话收进运行集合', async () => {
  vi.mocked(tasksApi.active).mockResolvedValue([
    { id: 't1', type: 'local_bash', status: 'running', description: '后台跑测试', startTime: 1, isBackgrounded: true, sessionId: 's-running' },
    { id: 't2', type: 'local_bash', status: 'pending', description: '排队中', startTime: 2, isBackgrounded: true, sessionId: 's-pending' },
    { id: 't3', type: 'local_bash', status: 'completed', description: '已结束', startTime: 3, isBackgrounded: true, sessionId: 's-done' },
    { id: 't4', type: 'local_bash', status: 'running', description: '无归属', startTime: 4, isBackgrounded: true, sessionId: null },
  ])
  act(() => { root!.render(<Host />) })
  await flush()
  expect([...last].sort()).toEqual(['s-pending', 's-running'])
})

it('接口失败时保留上一轮结果（网络抖动不清空侧栏运行点）', async () => {
  vi.mocked(tasksApi.active).mockResolvedValueOnce([
    { id: 't1', type: 'local_bash', status: 'running', description: 'ok', startTime: 1, isBackgrounded: true, sessionId: 's-keep' },
  ])
  act(() => { root!.render(<Host />) })
  await flush()
  expect([...last]).toEqual(['s-keep'])

  vi.mocked(tasksApi.active).mockRejectedValueOnce(new Error('network'))
  vi.useFakeTimers()
  await act(async () => { await vi.advanceTimersByTimeAsync(2100) })
  vi.useRealTimers()
  expect([...last], '失败不清空').toEqual(['s-keep'])
})
