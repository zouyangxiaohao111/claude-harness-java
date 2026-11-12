// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '@/App'
import { useChatStore } from '@/stores/chatStore'
import { sessionApi } from '@/api/sessions'

/**
 * ⭐ 回归锁：「新建会话」遇到同工作组已有空会话时，必须**切到那个空会话**。
 *
 * <b>WHY（规则九 · 测试验证意图）</b>：用户点「+ 新会话」的意图是「拿到一个可以开始对话的
 * 空会话」。旧实现在发现同组已有空会话时只弹一条 toast 就 return —— 会话列表没新增、
 * 左栏高亮没动、主区也没换，用户看到的就是「点了没反应」。修复后判据是用户可见的两件事：
 *   ① 左栏高亮（`.session-item.active`）落到那个空会话上；
 *   ② 没有发第二次 `sessionApi.create`（同一工作组只保留一个空会话的既有约束不变）。
 *
 * <b>反向实验</b>：把 createSession 的空会话分支改回「只 showToast 就 return」⇒ 本用例必红。
 *
 * <p>mock 面与 `App.serverRunningReconcile.test.tsx` 同一套：子组件保留真实，只替换数据源；
 * 与本用例无关的业务 hook 换空实现。
 */

const BUSY_SID = 'sess-busy'
const EMPTY_SID = 'sess-empty'
const PROJ = 'proj-app'

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
    send: vi.fn(),
    cancel: vi.fn(),
  },
}))
vi.mock('@/api/sessions', () => ({
  sessionApi: {
    // 同组两个会话：有对话的（列表首位 ⇒ App 启动后激活它）+ 一个空会话
    list: vi.fn(async () => [
      {
        id: BUSY_SID, title: '有对话的会话', model: 'DS', modelName: 'ds-openai/deepseek-v4-flash',
        mainProjectId: PROJ, messageCount: 3, updatedAt: '2026-11-12T10:00:00Z',
      },
      {
        id: EMPTY_SID, title: '空会话', model: 'DS', modelName: 'ds-openai/deepseek-v4-flash',
        mainProjectId: PROJ, messageCount: 0, updatedAt: '2026-11-12T09:00:00Z',
      },
    ]),
    create: vi.fn(async () => { throw new Error('同组已有空会话时不应再创建') }),
    remove: vi.fn(), update: vi.fn(),
  },
}))
vi.mock('@/api/sessionFiles', () => ({ sessionFilesApi: { list: vi.fn(async () => []), diff: vi.fn(), revert: vi.fn() } }))
vi.mock('@/api/projects', () => ({ projectApi: { list: vi.fn(async () => []), create: vi.fn(), bind: vi.fn() } }))
vi.mock('@/api/market', () => ({ marketApi: {} }))
vi.mock('@/api/command', () => ({ commandApi: { list: vi.fn(async () => []), executeBuiltin: vi.fn() } }))
vi.mock('@/api/tasks', () => ({ tasksApi: { stopAllTasks: vi.fn(), backgroundAll: vi.fn() } }))
vi.mock('@/api/settings', () => ({ settingsApi: { get: vi.fn(async () => ({ theme: 'dark', fontSize: 14, animationsEnabled: true })), update: vi.fn() } }))
vi.mock('@/api/attachment', () => ({ attachmentApi: { config: vi.fn(async () => ({ localRead: false })) } }))
vi.mock('@/api/claudeMd', () => ({
  getIncludeStatus: vi.fn(async () => ({ needsApproval: false, files: [] })),
  describeIncludeStatusProbeFailure: () => null,
}))
vi.mock('@/api/socket', () => ({
  sendPermissionResponse: vi.fn(),
  createSocketClient: () => null,
}))
vi.mock('@/utils/projectFolder', () => ({ selectProjectFolder: vi.fn(async () => null) }))
vi.mock('@/utils/debugLog', () => ({ debugLog: vi.fn(async () => {}) }))
vi.mock('@/components/modals/DiffModal', () => ({ DiffModal: () => null }))
vi.mock('@/components/modals/FileViewModal', () => ({ FileViewModal: () => null }))
vi.mock('@/components/modals/ArtifactViewModal', () => ({ ArtifactViewModal: () => null }))
vi.mock('@/components/right/ArtifactDock', () => ({ ArtifactDock: () => null }))

vi.mock('@/hooks/useChatSocket', () => ({ useChatSocket: () => ({ clientRef: { current: null } }) }))
vi.mock('@/hooks/useAwaySummary', () => ({ useAwaySummary: () => {} }))
vi.mock('@/hooks/useProviders', () => ({ useProviders: () => ({ list: [], loading: false, error: null, refresh: async () => {} }) }))
vi.mock('@/hooks/useMcp', () => ({ useMcp: () => ({ list: [], loading: false, error: null, refresh: async () => {} }) }))
vi.mock('@/hooks/useDatabases', () => ({ useDatabases: () => ({ list: [], loading: false, error: null, refresh: async () => {} }) }))
vi.mock('@/hooks/useSchedules', () => ({ useSchedules: () => ({ list: [], loading: false, error: null, refresh: async () => {}, canCreate: false }) }))
vi.mock('@/hooks/useFirstRunGuide', () => ({
  useFirstRunGuide: () => ({ active: false, done: true, stepIndex: 0, steps: [], onNext: () => {}, onSkip: () => {} }),
}))
vi.mock('@/hooks/useTierInheritGuide', () => ({
  useTierInheritGuide: () => ({ active: false, mainModelName: null, extrasAllUnset: true, apply: async () => {}, dismiss: async () => {} }),
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

/** 左栏当前高亮会话的标题（无高亮 = 空串） */
const activeTitle = (c: HTMLElement) => c.querySelector('.session-item.active .title')?.textContent ?? ''

describe('新建会话：同工作组已有空会话 ⇒ 切到它，而不是只弹提示', () => {
  beforeEach(() => {
    useChatStore.setState({
      sessions: [], serverRunning: {}, streamOrder: {}, streams: {}, messages: {},
      agentStatus: 'idle',
    })
    vi.mocked(sessionApi.create).mockClear()
  })

  afterEach(() => {
    if (root) act(() => root!.unmount())
    container?.remove()
    container = undefined
    root = undefined
    useChatStore.setState({ sessions: [], serverRunning: {}, streamOrder: {}, streams: {}, messages: {} })
  })

  it('⭐ 点「+ 新会话」⇒ 左栏高亮切到同组空会话，且不新建', async () => {
    const c = await mountApp()

    // 前置：启动后激活的是列表首位的「有对话的会话」
    expect(activeTitle(c), '启动后应激活列表首位会话').toBe('有对话的会话')

    const btn = c.querySelector('button.new-session-btn') as HTMLButtonElement
    expect(btn, '左栏「新建会话」按钮必须存在').toBeTruthy()
    act(() => { btn.dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    await flushPromises()

    // 判据①：不新建（同组空会话约束仍在）
    expect(sessionApi.create).not.toHaveBeenCalled()
    // 判据②：左栏高亮已经切到那个空会话（用户可见的「切过去了」）
    expect(activeTitle(c), '必须切到同组的空会话').toBe('空会话')
  })
})
