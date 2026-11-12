// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '@/App'
import { useChatStore } from '@/stores/chatStore'
import { useSubagentStore } from '@/stores/subagentStore'
import { resetSubagentRunningReconcile, useSubagentRunningReconcile } from '@/hooks/useSubagentRunning'
import { chatApi } from '@/api/chat'
import { tasksApi } from '@/api/tasks'

/**
 * ⭐ [2026-11-12 用户原话]「如果主代理已经完成了 但是子代理还在运作 此时不应该算作已经完成」
 *
 * <b>现场</b>：主代理 turn 收口（complete 帧）→ App.handleSessionDone 把非当前会话写进
 * doneUnreadIds ⇒ 侧栏点翻成 `done`（静止绿）。但子代理走 AutonomousAgentLoop，**既不推
 * session.status 也不登记 activeStreams** ⇒ 侧栏三路信号全灭，用户看到「已完成」而子代理还在跑。
 *
 * <b>判据</b>（只看用户看到的 DOM）：`.session-item .status-dot` 的 className。
 * 反向实验：删掉 App 里 `for (const sid of subagentRunningIds) out.add(sid)` 这一行 ⇒ 第 1 个用例红。
 *
 * <b>mock 面</b>照抄 `App.serverRunningReconcile.test.tsx`（真 App + 真子组件，只桩数据源）；
 * 差别只在 `@/api/tasks` 多了 `list`（第 2 组「2s 常驻对账」用例要用它）。
 */

const SID = 'sess-dot-main'
const OTHER = 'sess-dot-sub'

vi.mock('@tauri-apps/api/core', () => ({ isTauri: () => false, invoke: vi.fn(async () => undefined) }))
vi.mock('@tauri-apps/api/webview', () => ({ getCurrentWebview: () => ({ onDragDropEvent: () => Promise.resolve(() => {}) }) }))
vi.mock('@tauri-apps/plugin-fs', () => ({ stat: vi.fn(), readFile: vi.fn(), writeTextFile: vi.fn() }))
vi.mock('@tauri-apps/api/event', () => ({ listen: vi.fn(async () => () => {}) }))

vi.mock('@/api/chat', () => ({
  uploadAttachment: vi.fn(),
  chatApi: {
    sessionRunning: vi.fn(() => new Promise(() => {})),
    messageCount: vi.fn(async () => ({ total: 0 })),
    listTurns: vi.fn(async () => ({ messages: [], hasMore: false, firstTurnId: null, lastTurnId: null })),
    listMessages: vi.fn(async () => []),
    fetchImagesBatch: vi.fn(async () => ({})),
    send: vi.fn(async () => ({ queued: false, userMessageId: 'msg-user-sent', streamTopic: `/topic/sessions/${SID}/stream` })),
    cancel: vi.fn(),
  },
}))
vi.mock('@/api/sessions', () => ({
  sessionApi: {
    // 两个会话（同一个项目 → 同一个分组，且当前会话所在组自动展开 ⇒ 两行都可见）
    list: vi.fn(async () => [
      { id: SID, title: '甲会话', modelName: 'ds-openai/deepseek-v4-flash', mainProjectId: 'proj-app' },
      { id: OTHER, title: '乙会话', modelName: 'ds-openai/deepseek-v4-flash', mainProjectId: 'proj-app' },
    ]),
    create: vi.fn(), remove: vi.fn(), update: vi.fn(),
  },
}))
vi.mock('@/api/sessionFiles', () => ({ sessionFilesApi: { list: vi.fn(async () => []), diff: vi.fn(), revert: vi.fn() } }))
vi.mock('@/api/projects', () => ({ projectApi: { list: vi.fn(async () => []), create: vi.fn(), bind: vi.fn() } }))
vi.mock('@/api/market', () => ({ marketApi: {} }))
vi.mock('@/api/command', () => ({ commandApi: { list: vi.fn(async () => []), executeBuiltin: vi.fn() } }))
vi.mock('@/api/tasks', () => ({
  tasksApi: { stopAllTasks: vi.fn(), backgroundAll: vi.fn(), list: vi.fn(async () => []) },
}))
vi.mock('@/api/settings', () => ({ settingsApi: { get: vi.fn(async () => ({ theme: 'dark', fontSize: 14, animationsEnabled: true })), update: vi.fn() } }))
vi.mock('@/api/attachment', () => ({ attachmentApi: { config: vi.fn(async () => ({ localRead: false })) } }))
vi.mock('@/api/claudeMd', () => ({
  getIncludeStatus: vi.fn(async () => ({ needsApproval: false, files: [] })),
  describeIncludeStatusProbeFailure: () => null,
}))
vi.mock('@/api/socket', () => ({ sendPermissionResponse: vi.fn(), createSocketClient: () => null }))
vi.mock('@/utils/projectFolder', () => ({ selectProjectFolder: vi.fn(async () => null) }))
vi.mock('@/utils/debugLog', () => ({ debugLog: vi.fn(async () => {}) }))
vi.mock('@/components/modals/DiffModal', () => ({ DiffModal: () => null }))
vi.mock('@/components/modals/FileViewModal', () => ({ FileViewModal: () => null }))
vi.mock('@/components/modals/ArtifactViewModal', () => ({ ArtifactViewModal: () => null }))
vi.mock('@/components/right/ArtifactDock', () => ({ ArtifactDock: () => null }))

// 捕获 App 传进来的 onSessionDone（第 4 个实参）⇒ 模拟「主代理终态帧到达」
vi.mock('@/hooks/useChatSocket', () => ({
  useChatSocket: (
    _sid: unknown, _streams: unknown, _toast: unknown,
    onSessionDone?: (sid: string, topic?: string) => void,
  ) => {
    ;(globalThis as unknown as { __onSessionDone?: unknown }).__onSessionDone = onSessionDone
    return { clientRef: { current: null } }
  },
}))
vi.mock('@/hooks/useAwaySummary', () => ({ useAwaySummary: () => {} }))
vi.mock('@/hooks/useProviders', () => ({ useProviders: () => ({ list: [], loading: false, error: null, refresh: async () => {} }) }))
vi.mock('@/hooks/useMcp', () => ({ useMcp: () => ({ list: [], loading: false, error: null, refresh: async () => {} }) }))
vi.mock('@/hooks/useDatabases', () => ({ useDatabases: () => ({ list: [], loading: false, error: null, refresh: async () => {} }) }))
vi.mock('@/hooks/useSchedules', () => ({ useSchedules: () => ({ list: [], loading: false, error: null, refresh: async () => {}, canCreate: false }) }))
vi.mock('@/hooks/useFirstRunGuide', () => ({
  useFirstRunGuide: () => ({ active: false, done: true, stepIndex: 0, steps: [], onNext: () => {}, onSkip: () => {} }),
}))
vi.mock('@/hooks/useTierInheritGuide', () => ({
  useTierInheritGuide: () => ({ active: false, mainModelName: null, extrasAllUnset: false, apply: async () => {}, dismiss: () => {} }),
}))
vi.mock('@/hooks/useCommandQueue', () => ({
  useCommandQueue: () => ({ queuedCommands: [], setQueued: () => {}, refresh: async () => {}, popEditable: async () => null }),
}))

async function flushPromises() {
  await act(async () => { await Promise.resolve(); await Promise.resolve(); await Promise.resolve() })
}

let container: HTMLDivElement | undefined
let root: Root | undefined

async function mountApp(): Promise<HTMLDivElement> {
  ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  act(() => { root!.render(<App />) })
  await flushPromises()
  return container
}

/** 侧栏里某个会话行的状态点（按标题定位，避免「第一个 .session-item」在分组序里漂移）。 */
function dotOfSession(c: HTMLElement, title: string): HTMLElement | null {
  const item = Array.from(c.querySelectorAll('.session-item')).find(
    (el) => el.querySelector('.title')?.textContent === title,
  )
  return item?.querySelector('.status-dot') as HTMLElement | null ?? null
}

describe('[侧栏运行点] 子代理还在跑 ⇒ 主代理完成也不得显示「已完成」', () => {
  beforeEach(() => {
    localStorage.clear()
    useChatStore.setState({
      sessions: [], serverRunning: {}, streamOrder: {}, streams: {}, messages: {},
      agentStatus: 'idle',
    })
    useSubagentStore.setState({ sessionId: null, bySession: {} })
    resetSubagentRunningReconcile()
    vi.mocked(tasksApi.list).mockReset()
    vi.mocked(tasksApi.list).mockResolvedValue([])
    vi.mocked(chatApi.sessionRunning).mockReset()
    vi.mocked(chatApi.sessionRunning).mockReturnValue(new Promise(() => {}))
  })

  afterEach(() => {
    if (root) act(() => root!.unmount())
    container?.remove()
    container = undefined
    root = undefined
    useChatStore.setState({ sessions: [], serverRunning: {}, streamOrder: {}, streams: {}, messages: {} })
    useSubagentStore.setState({ sessionId: null, bySession: {} })
  })

  it('⭐ 主代理已完成（done 未读）+ 子代理仍在跑 ⇒ 侧栏必须是 running 点，不是 done 点', async () => {
    const c = await mountApp()

    // 前置：乙会话还没点（无运行、无未读）
    expect(dotOfSession(c, '乙会话')).toBeNull()

    // 主代理终态帧到达（乙会话非当前会话 ⇒ 写 doneUnreadIds ⇒ 原语义 = 绿 done 点）
    const onDone = (globalThis as unknown as { __onSessionDone?: (sid: string, topic?: string) => void }).__onSessionDone
    expect(onDone, 'must capture App 传入的 onSessionDone').toBeTypeOf('function')
    act(() => { onDone!(OTHER) })
    await flushPromises()
    expect(dotOfSession(c, '乙会话')?.className, '只有主代理完成时 = done').toContain('done')

    // 子代理仍在跑（task_started 已登记、还没终态）⇒ 侧栏必须回到 running（橙点闪烁）
    act(() => { useSubagentStore.getState().register(null, 't-1', 'alice', 'local_agent', OTHER) })
    const cls = dotOfSession(c, '乙会话')?.className ?? ''
    // 反向实验：删掉 App 里 `for (const sid of subagentRunningIds) out.add(sid)` ⇒ 本行红（只剩 done）
    expect(cls, '子代理在跑 ⇒ 必须是 running').toContain('running')
    expect(cls, '不得被 done 覆盖').not.toContain('done')
  })

  it('⭐ 子代理终态（addActivity done）⇒ 回落原语义：绿 done 点', async () => {
    const c = await mountApp()
    const onDone = (globalThis as unknown as { __onSessionDone?: (sid: string, topic?: string) => void }).__onSessionDone
    act(() => { onDone!(OTHER) })
    await flushPromises()
    act(() => { useSubagentStore.getState().register(null, 't-1', 'alice', 'local_agent', OTHER) })
    expect(dotOfSession(c, '乙会话')?.className ?? '').toContain('running')

    act(() => {
      useSubagentStore.getState().addActivity('t-1', { type: 'done', text: 'ok', ts: Date.now() }, OTHER)
    })
    const cls = dotOfSession(c, '乙会话')?.className ?? ''
    expect(cls, '子代理终态 ⇒ 不再 running').not.toContain('running')
    expect(cls, '主代理已完成 + 非当前会话 ⇒ done').toContain('done')
  })
})

/** 只挂对账 hook 的宿主（不渲染整棵 App：本组用例只验 2s 对账语义）。 */
function ReconcileHost() {
  useSubagentRunningReconcile()
  return null
}

describe('[侧栏运行点 · 2s 常驻对账] running 身份与 REST 任务清单对齐', () => {
  let hContainer: HTMLDivElement | undefined
  let hRoot: Root | undefined

  /** 推 n 个 2s tick（fake timers；每 tick 后把 hook 里那条对账 promise 也跑完）。 */
  const tick = async (n = 1) => {
    for (let i = 0; i < n; i++) {
      await act(async () => { await vi.advanceTimersByTimeAsync(2000) })
    }
  }
  const idOf = (sid: string, taskId: string) => useSubagentStore.getState().bySession[sid]?.[taskId]

  beforeEach(() => {
    ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    localStorage.clear()
    useSubagentStore.setState({ sessionId: null, bySession: {} })
    resetSubagentRunningReconcile()
    vi.mocked(tasksApi.list).mockReset()
    vi.mocked(tasksApi.list).mockResolvedValue([])
    vi.useFakeTimers()
    hContainer = document.createElement('div')
    document.body.appendChild(hContainer)
    hRoot = createRoot(hContainer)
    act(() => { hRoot!.render(<ReconcileHost />) })
  })

  afterEach(() => {
    if (hRoot) act(() => hRoot!.unmount())
    hContainer?.remove()
    hContainer = undefined
    hRoot = undefined
    vi.useRealTimers()
    resetSubagentRunningReconcile()
    useSubagentStore.setState({ sessionId: null, bySession: {} })
  })

  it('⭐ 宽限期：REST 连续 2 次查不到才 forget（第 1 次只记数）', async () => {
    vi.mocked(tasksApi.list).mockResolvedValue([])
    act(() => { useSubagentStore.getState().register(null, 't-1', 'alice', 'local_agent', SID) })

    await tick()
    expect(tasksApi.list).toHaveBeenCalledTimes(1)
    expect(tasksApi.list).toHaveBeenCalledWith(SID)
    expect(idOf(SID, 't-1')?.status, '第 1 次缺席仍在宽限期内').toBe('running')

    await tick()
    expect(idOf(SID, 't-1'), '连续 2 次缺席 ⇒ forget').toBeUndefined()
  })

  it('⭐ 终态补录：REST completed ⇒ 身份补 done（侧栏不再 running）', async () => {
    vi.mocked(tasksApi.list).mockResolvedValue([
      { id: 't-1', type: 'local_agent', status: 'completed', description: 'x', startTime: 0 },
    ])
    act(() => { useSubagentStore.getState().register(null, 't-1', 'alice', 'local_agent', SID) })

    await tick()
    expect(idOf(SID, 't-1')?.status).toBe('done')
  })

  it('⭐ 在场不清：REST running ⇒ 推 5 个 tick 仍 running（缺席计数被清）', async () => {
    vi.mocked(tasksApi.list).mockResolvedValue([
      { id: 't-1', type: 'local_agent', status: 'running', description: 'x', startTime: 0 },
    ])
    act(() => { useSubagentStore.getState().register(null, 't-1', 'alice', 'local_agent', SID) })

    await tick(5)
    expect(tasksApi.list).toHaveBeenCalledTimes(5)
    expect(idOf(SID, 't-1')?.status, '在场就不算缺席，不得误删').toBe('running')
  })

  it('⭐ 失败静默：list 连抛 3 次不计数（不误删）；恢复后仍要满 2 次缺席才删', async () => {
    vi.mocked(tasksApi.list).mockRejectedValue(new Error('backend down'))
    act(() => { useSubagentStore.getState().register(null, 't-1', 'alice', 'local_agent', SID) })

    await tick(3)
    expect(tasksApi.list).toHaveBeenCalledTimes(3)
    expect(idOf(SID, 't-1')?.status, '失败轮次被完全跳过').toBe('running')

    vi.mocked(tasksApi.list).mockResolvedValue([])
    await tick()
    expect(idOf(SID, 't-1')?.status, '失败没有污染计数 ⇒ 第 1 次真缺席仍只记数').toBe('running')

    await tick()
    expect(idOf(SID, 't-1'), '第 2 次真缺席 ⇒ forget').toBeUndefined()
  })

  it('⭐ 空闲零请求：没有任何 running 身份 ⇒ 推 5 个 tick 一次 HTTP 都不发', async () => {
    await tick(5)
    expect(tasksApi.list).not.toHaveBeenCalled()
  })

  it('测试桩兼容：tasksApi.list 不存在 ⇒ 不挂定时器、不抛（既有 mock 形态）', async () => {
    const orig = tasksApi.list
    act(() => { hRoot!.unmount() })
    hRoot = undefined
    hContainer?.remove()
    hContainer = undefined
    resetSubagentRunningReconcile()
    // @ts-expect-error 模拟既有测试里「mock 了 @/api/tasks 但没有 list」的形态
    tasksApi.list = undefined
    const c2 = document.createElement('div')
    document.body.appendChild(c2)
    const r2 = createRoot(c2)
    try {
      act(() => { r2.render(<ReconcileHost />) })
      await act(async () => { await vi.advanceTimersByTimeAsync(2000 * 5) })
      expect(vi.getTimerCount(), '不许留下永不清理的 2s 定时器').toBe(0)
    } finally {
      act(() => r2.unmount())
      c2.remove()
      tasksApi.list = orig
    }
  })
})
