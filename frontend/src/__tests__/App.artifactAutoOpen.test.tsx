// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '@/App'
import { useChatStore } from '@/stores/chatStore'
import type { SessionArtifacts } from '@/api/artifacts'

/**
 * ⭐ [2026-11-04] **App 层**接线守门测试：产物推送载荷 ⇒ 侧栏打开**哪一条**。
 *
 * <b>WHY 必须有这一层（规则九 · 测试验证意图）</b>：
 * 用户实测「如果之前轮次有个产物，现在新的轮次就会默认打开之前的产物」。
 * 根因链有三条，前两条在后端（已修：旧产物不重推 + 不顶时间戳），
 * 但**第三条在前端**：自动打开取的是 `list[0]`（按 updated_at 倒序的第一条），
 * 而推送载荷**被整个丢弃**（`useChatSocket` 的 artifact 订阅回调不读 `msg.body`）⇒
 * 前端根本不知道「这次推的是哪一条、是不是新建」⇒ 只能取列表第一条 ⇒
 * 旧产物被顶到第一位时，弹出来���就是上一轮的产物。
 *
 * <p>⇒ 修法：订阅回调解析载荷，把「artifactId / kind / created」透出；
 * App 只打开**按 id 精确匹配到的那一条**，匹配不到就不开（宁可不开，不开错的那条）。
 *
 * <b>反向实验</b>：把 App 里 `list.find(a => a.id === artifactId)` 改回 `list[0]` ⇒
 * 本文件第 ② 条必红。
 */

const SID = 'sess-artifact-autoopen'

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
    list: vi.fn(async () => [{ id: SID, title: '会话', modelName: 'ds-openai/deepseek-v4-flash', mainProjectId: 'proj-app' }]),
    create: vi.fn(), remove: vi.fn(), update: vi.fn(),
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
vi.mock('@/api/socket', () => ({ sendPermissionResponse: vi.fn(), createSocketClient: () => null }))
vi.mock('@/utils/projectFolder', () => ({ selectProjectFolder: vi.fn(async () => null) }))
vi.mock('@/utils/debugLog', () => ({ debugLog: vi.fn(async () => {}) }))

// monaco 相关（与本批无关，jsdom 下 import 期即抛）
vi.mock('@/components/modals/DiffModal', () => ({ DiffModal: () => null }))
vi.mock('@/components/modals/FileViewModal', () => ({ FileViewModal: () => null }))
vi.mock('@/components/modals/ArtifactViewModal', () => ({ ArtifactViewModal: () => null }))

/**
 * 产物侧栏：把「当前打开的是哪一条」写进 DOM，便于断言。
 * （原本整块桩成 `() => null` ⇒ 无法断言打开的是哪一条）
 */
vi.mock('@/components/right/ArtifactDock', () => ({
  ArtifactDock: ({ artifact }: { artifact: { id: string; title: string } | null }) =>
    artifact ? <div data-testid="artifact-dock" data-artifact-id={artifact.id}>{artifact.title}</div> : null,
}))

/** 产物列表接口：测试里控制「后端重拉回来的列表内容与顺序」。 */
const artifactsList = vi.fn(async (): Promise<SessionArtifacts> => ({ deliverables: [], sources: [] }))
vi.mock('@/api/artifacts', () => ({
  artifactsApi: {
    list: (...args: unknown[]) => artifactsList(...(args as [])),
    content: vi.fn(),
    raw: vi.fn(),
    saveContent: vi.fn(),
  },
}))

/** useChatSocket 的 mock：捕获 App 传进来的 onArtifactPush 回调（**末尾位置参数**）。 */
let capturedOnArtifactPush: ((sid: string, payload: {
  artifactId: string | null
  kind: 'deliverable' | 'source' | null
  created: boolean
}) => void) | null = null

vi.mock('@/hooks/useChatSocket', () => ({
  useChatSocket: (...args: unknown[]) => {
    // App 的调用：useChatSocket(activeSessionId, activeStreams, showToast, handleSessionDone,
    //   handleQueueDrained, handleQueueChanged, handleReconnectReload, handleReconnectReload,
    //   knownSessionIds, handleArtifactPush) ⇒ onArtifactPush 是**第 10 个位置参数**
    capturedOnArtifactPush = args[9] as typeof capturedOnArtifactPush
    return { clientRef: { current: null } }
  },
}))

// 与本批无关的业务 hook → 空实现
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

/** 产物 DTO 构造（字段与 @/api/artifacts 的 SessionArtifactDto 对齐）。 */
function deliverable(id: string, title: string, updatedAt: string) {
  return {
    id, kind: 'deliverable' as const, title,
    localPath: `/abs/${title}`, url: null, sizeBytes: 10,
    summary: null, sourceMessageId: null, updatedAt, directory: false,
  }
}

/** 模拟后端推了一条 artifact 事件（走 App 真实接线的那个回调）。 */
async function pushArtifact(payload: { artifactId: string | null; kind: 'deliverable' | 'source' | null; created: boolean }) {
  expect(capturedOnArtifactPush).toBeTruthy()
  await act(async () => {
    capturedOnArtifactPush!(SID, payload)
    await Promise.resolve()
    await Promise.resolve()
    await Promise.resolve()
  })
  await flushPromises()
}

const dockEl = (c: HTMLElement) => c.querySelector('[data-testid="artifact-dock"]')

describe('[2026-11-04] 产物推送载荷 ⇒ 侧栏只打开「本次推送的那一条」', () => {
  beforeEach(() => {
    useChatStore.setState({
      sessions: [], serverRunning: {}, streamOrder: {}, streams: {}, messages: {},
      agentStatus: 'idle',
    })
    artifactsList.mockReset()
    artifactsList.mockResolvedValue({ deliverables: [], sources: [] })
    capturedOnArtifactPush = null
  })

  afterEach(() => {
    if (root) act(() => root!.unmount())
    container?.remove()
    container = undefined
    root = undefined
    useChatStore.setState({ sessions: [], serverRunning: {}, streamOrder: {}, streams: {}, messages: {} })
  })

  it('前置：真 App 渲染后已切到真实会话、且捕获到 onArtifactPush 接线', async () => {
    await mountApp()
    expect(capturedOnArtifactPush).toBeTruthy()
  })

  it('⭐ 推送的是 B ⇒ 打开 B（不是列表第一条 A）—— 旧产物被顶到第一位时不能弹错的那条', async () => {
    // 列表顺序：A 在前（updated_at 更晚 —— 旧产物被后端顶到了第一位），B 是本次真正生成的
    artifactsList.mockResolvedValue({
      deliverables: [
        deliverable('art-A', '上一轮的产物.html', '2026-11-04T10:00:00+08:00'),
        deliverable('art-B', '本轮的产物.html', '2026-11-03T09:00:00+08:00'),
      ],
      sources: [],
    })
    const c = await mountApp()
    // 本轮在跑（自动展开的门控：turnRunning）
    act(() => { useChatStore.getState().setServerRunning(SID, true) })

    await pushArtifact({ artifactId: 'art-B', kind: 'deliverable', created: true })

    const dock = dockEl(c)
    expect(dock).toBeTruthy()
    expect(dock!.getAttribute('data-artifact-id')).toBe('art-B')
    // 反向实验：把 App 里的 find(...) 改回 list[0] ⇒ 这里会拿到 art-A ⇒ 红
  })

  it('⭐ 推送的 id 在列表里找不到 ⇒ 不打开（宁可不开，也不开错的那条）', async () => {
    artifactsList.mockResolvedValue({
      deliverables: [deliverable('art-A', '上一轮的产物.html', '2026-11-04T10:00:00+08:00')],
      sources: [],
    })
    const c = await mountApp()
    act(() => { useChatStore.getState().setServerRunning(SID, true) })

    await pushArtifact({ artifactId: 'art-UNKNOWN', kind: 'deliverable', created: true })

    expect(dockEl(c)).toBeFalsy()
  })

  it('⭐ 载荷没有 artifactId（旧后端 / 解析失败）⇒ 不打开', async () => {
    artifactsList.mockResolvedValue({
      deliverables: [deliverable('art-A', '某产物.html', '2026-11-04T10:00:00+08:00')],
      sources: [],
    })
    const c = await mountApp()
    act(() => { useChatStore.getState().setServerRunning(SID, true) })

    await pushArtifact({ artifactId: null, kind: 'deliverable', created: true })

    expect(dockEl(c)).toBeFalsy()
  })

  it('来源（source）推送 ⇒ 不自动展开（来源是一批链接，不是刚生成的交付物）', async () => {
    artifactsList.mockResolvedValue({
      deliverables: [deliverable('art-A', '本轮的产物.html', '2026-11-04T10:00:00+08:00')],
      sources: [],
    })
    const c = await mountApp()
    act(() => { useChatStore.getState().setServerRunning(SID, true) })

    await pushArtifact({ artifactId: 'art-A', kind: 'source', created: true })

    expect(dockEl(c)).toBeFalsy()
  })

  it('轮次已结束（不在跑）⇒ 不自动展开（既有裁定：结束后由用户点正文下方的产物卡）', async () => {
    artifactsList.mockResolvedValue({
      deliverables: [deliverable('art-A', '本轮的产物.html', '2026-11-04T10:00:00+08:00')],
      sources: [],
    })
    const c = await mountApp()
    // 不 setServerRunning ⇒ turnRunning 恒假

    await pushArtifact({ artifactId: 'art-A', kind: 'deliverable', created: true })

    expect(dockEl(c)).toBeFalsy()
  })
})
