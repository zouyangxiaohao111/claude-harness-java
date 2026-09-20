// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '@/App'
import { useChatStore } from '@/stores/chatStore'
import { chatApi } from '@/api/chat'

/**
 * ⭐ [C6 · 遗留2 · A1] **App 层**接线守门测试：服务端权威收口 → 对账必须在 App 里真的被调用。
 *
 * <b>WHY 必须有这一层（规则九 · 测试验证意图）</b>：C6 的其余用例（
 * `components/center/__tests__/Composer.stopVisibleWhenServerRunning.test.tsx` /
 * `...StreamRegistrationStale.test.tsx`）都渲染<b>测试文件里自建的 Host</b> —— Host 逐行照抄 App 的接线，
 * 于是把 **App.tsx 里那行生产调用删掉，那些用例照样全绿**（实测 621 例零红）。
 * 接线型缺陷的判据是「生产入口真的有这条线」，只有渲染真 App 才能钉住。
 *
 * <b>怎么把 App 的 mock 面做小</b>（不渲染整个后端世界，只留本批要钉的那条线）：
 * <ol>
 *   <li><b>子组件全部保留真实</b>（Composer / MessageList 等就是断言对象），只替换它们的<b>数据源</b>；</li>
 *   <li><b>传输层换成桩</b>：`@/api/socket` 给空 client；`@/api/chat` 的 sessionRunning 恒 pending
 *       （不干扰直接驱动 store 的用例）；其余 REST 模块一律返回空数据；</li>
 *   <li><b>与本次接线无关的六个业务 hook 换空实现</b>（providers/skills/mcp/databases/schedules/first-run/
 *       tier-inherit/away-summary/command-queue）：它们各自要一套后端夹具，与本批（服务端运行态对账）
 *       零交集 —— 换成空实现后 App 的<b>自身逻辑</b>（reconcile 那行、turnRunning 选择器、Composer 渲染）
 *       全部走真代码。</li>
 * </ol>
 *
 * <b>反向实验</b>：删掉 App.tsx 的 `useServerRunningReconcile(reclaimStreamRegistration)` ⇒ 本文件必红
 * （停止键不消失 + 残留流式块不定稿）。
 */

const SID = 'sess-app-reconcile'
const BLOCK = 'msg-app-residue'

vi.mock('@tauri-apps/api/core', () => ({ isTauri: () => false, invoke: vi.fn(async () => undefined) }))
vi.mock('@tauri-apps/api/webview', () => ({ getCurrentWebview: () => ({ onDragDropEvent: () => Promise.resolve(() => {}) }) }))
vi.mock('@tauri-apps/plugin-fs', () => ({ stat: vi.fn(), readFile: vi.fn(), writeTextFile: vi.fn() }))
vi.mock('@tauri-apps/api/event', () => ({ listen: vi.fn(async () => () => {}) }))

vi.mock('@/api/chat', () => ({
  uploadAttachment: vi.fn(),
  chatApi: {
    sessionRunning: vi.fn(() => new Promise(() => {})),   // 默认永不返回：本文件只驱动 store 边沿
    messageCount: vi.fn(async () => ({ total: 0 })),
    listMessagesPage: vi.fn(async () => ({ messages: [], hasMore: false, total: 0 })),
    listMessages: vi.fn(async () => []),
    fetchImagesBatch: vi.fn(async () => ({})),
    send: vi.fn(async () => ({ queued: false, userMessageId: 'msg-user-sent', streamTopic: '/topic/sessions/sess-app-reconcile/stream' })),
    cancel: vi.fn(),
  },
}))
vi.mock('@/api/sessions', () => ({
  sessionApi: {
    // mainProjectId 必填：App.sendMessage 对「未绑定项目的会话」直接拦截（不发送）
    list: vi.fn(async () => [{ id: 'sess-app-reconcile', title: '会话', modelName: 'ds-openai/deepseek-v4-flash', mainProjectId: 'proj-app' }]),
    create: vi.fn(), remove: vi.fn(), update: vi.fn(),
  },
}))
vi.mock('@/api/sessionFiles', () => ({ sessionFilesApi: { list: vi.fn(async () => []), diff: vi.fn(), revert: vi.fn() } }))
vi.mock('@/api/projects', () => ({ projectApi: { list: vi.fn(async () => []), create: vi.fn(), bind: vi.fn() } }))
vi.mock('@/api/market', () => ({ marketApi: { useExpert: vi.fn() } }))
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
// monaco 编辑器（DiffModal / FileViewModal 静态 import）在 jsdom 下 import 期即抛
//   （document.queryCommandSupported 未实现）—— 与本次接线无关，整体桩掉（它们只在点开 diff/文件时渲染）
vi.mock('@/components/modals/DiffModal', () => ({ DiffModal: () => null }))
vi.mock('@/components/modals/FileViewModal', () => ({ FileViewModal: () => null }))

// 与本次接线无关的业务 hook → 空实现（各自需要一整套后端夹具，且不参与服务端运行态对账）
vi.mock('@/hooks/useChatSocket', () => ({ useChatSocket: () => ({ clientRef: { current: null } }) }))
vi.mock('@/hooks/useAwaySummary', () => ({ useAwaySummary: () => {} }))
vi.mock('@/hooks/useProviders', () => ({ useProviders: () => ({ list: [], loading: false, error: null, refresh: async () => {} }) }))
vi.mock('@/hooks/useSkills', () => ({ useSkills: () => ({ list: [], loading: false, error: null, refresh: async () => {} }) }))
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

const stopBtn = (c: HTMLElement) => c.querySelector('button.send-btn.danger')
const sendBtn = (c: HTMLElement) => c.querySelector('button.send-btn:not(.danger)')

async function flushPromises() {
  await act(async () => { await Promise.resolve(); await Promise.resolve(); await Promise.resolve() })
}

let container: HTMLDivElement | undefined
let root: Root | undefined

/** 渲染真 App，并把「服务端说该会话在跑 + 该轮已产生流式块」摆成事故现场。 */
async function mountApp(): Promise<HTMLDivElement> {
  ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  act(() => { root!.render(<App />) })
  await flushPromises()          // 会话列表 list() → SWITCH 到唯一会话 → activeSessionId = SID
  return container
}

/** 走真 App 的发送链：Composer 输入 → 发送键 → App.sendMessage 里的 setActiveStreams 登记。 */
async function sendViaComposer(c: HTMLElement): Promise<void> {
  const ta = await typeIntoComposer(c)
  const btn = sendBtn(c) as HTMLButtonElement
  expect(btn.disabled).toBe(false)
  act(() => { btn.dispatchEvent(new MouseEvent('click', { bubbles: true })) })
  await flushPromises()
  void ta
}

/**
 * 走 Enter 发送链（Composer keydown Enter → doSend → App.sendMessage）。
 * <b>为什么需要它</b>：本地 store 说「在跑」时发送键已换成停止键（点击路被 UI 挡），但输入框仍可回车 ——
 * 而后端是否排队由**服务端**判定：store 里是过期 true（服务端其实空闲）时，回车发出的消息被立即受理，
 * 于是「本轮登记」与「上一轮遗留的 true」同时在场（本批要钉的那个现场）。
 */
async function sendViaEnter(c: HTMLElement): Promise<void> {
  const ta = await typeIntoComposer(c)
  act(() => { ta.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true })) })
  await flushPromises()
}

async function typeIntoComposer(c: HTMLElement): Promise<HTMLTextAreaElement> {
  const ta = Array.from(c.querySelectorAll('textarea')).find((t) => t.placeholder.includes('向 nexus 提问'))!
  const setValue = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')!.set!
  act(() => { setValue.call(ta, 'ping'); ta.dispatchEvent(new Event('input', { bubbles: true })) })
  await flushPromises()
  return ta
}

describe('[C6·遗留2·A1] App 层接线：服务端收口 → 对账（删除 App 里那行调用必须变红）', () => {
  beforeEach(() => {
    useChatStore.setState({
      sessions: [], serverRunning: {}, streamOrder: {}, streams: {}, messages: {},
      agentStatus: 'idle',
    })
    vi.mocked(chatApi.sessionRunning).mockReset()
    vi.mocked(chatApi.sessionRunning).mockReturnValue(new Promise(() => {}))
  })

  afterEach(() => {
    if (root) act(() => root!.unmount())
    container?.remove()
    container = undefined
    root = undefined
    useChatStore.setState({ sessions: [], serverRunning: {}, streamOrder: {}, streams: {}, messages: {} })
  })

  it('前置：真 App 渲染后已切到真实会话、Composer 用的是发送键', async () => {
    const c = await mountApp()
    expect(sendBtn(c)).toBeTruthy()
    expect(stopBtn(c)).toBeFalsy()
  })

  it('⭐ 服务端收口（true→false）⇒ App 必须回收 turn 且定稿残留流式块（反向实验：删 App 那行调用 ⇒ 本用例红）', async () => {
    const c = await mountApp()

    // 事故现场：服务端说该会话在跑（thinking / GET）+ 该轮已产生流式块（chunk 到达）
    act(() => {
      const st = useChatStore.getState()
      st.setServerRunning(SID, true)
      st.ensureStreamBlock(SID, BLOCK, 'msg-user-app')
      st.appendChunk(SID, BLOCK, '残留的正文')
    })
    expect(stopBtn(c)).toBeTruthy()                                  // 停止键可见（服务端在跑）
    expect(useChatStore.getState().streamOrder[SID]?.length).toBe(1)

    // complete 帧静默丢失；服务端权威收口 → App 里那行 useServerRunningReconcile 必须触发对账
    act(() => { useChatStore.getState().setServerRunning(SID, false) })

    // 反向实验：删掉 App.tsx 的 useServerRunningReconcile(reclaimStreamRegistration)
    //   ⇒ streams 仍残留（hasStream 恒真 ⇒ 停止键永久卡）+ 该行永久直出原文
    expect(useChatStore.getState().streamOrder[SID]?.length ?? 0).toBe(0)
    expect((useChatStore.getState().messages[SID] ?? []).some((m) => m.id === BLOCK && m.content === '残留的正文')).toBe(true)
    expect(stopBtn(c)).toBeFalsy()
    expect(sendBtn(c)).toBeTruthy()
  })

  it('⭐ 真发送路径：App 登记 activeStreams ⇒ 服务端收口必须回收该登记（覆盖回调那一半）', async () => {
    const c = await mountApp()

    // 走真 App 的发送链（Composer 输入 → 发送键 → App.sendMessage 里的 setActiveStreams 登记）
    await sendViaComposer(c)

    // 登记已生效：此刻服务端尚未说话，停止键的唯一支撑就是这条 activeStreams 登记
    expect(chatApi.send).toHaveBeenCalled()
    expect(stopBtn(c)).toBeTruthy()

    // complete 帧静默丢失；服务端权威收口 → 登记必须被收回（turnRunning 三路全假）
    act(() => { useChatStore.getState().setServerRunning(SID, true) })
    act(() => { useChatStore.getState().setServerRunning(SID, false) })
    expect(stopBtn(c)).toBeFalsy()
    expect(sendBtn(c)).toBeTruthy()
  })

  it('⭐ 轮次判据：上一轮的迟到收口边沿不得回收本轮刚登记的 activeStreams（反向实验：去掉轮次判据 ⇒ 本用例红）', async () => {
    const c = await mountApp()

    // 事故现场 = 本批缺陷族（帧静默丢失 / GET 迟到）留下的**过期 true**：store 说该会话在跑，
    //   而服务端其实已经空闲 —— 发送键此时是「停止」（点击路被 UI 挡），但回车仍可发；服务端不排队、
    //   立即受理（stub 的 send 恒返回 queued:false）⇒ 本轮由此登记，而上一轮的 true 还在 store 里。
    act(() => { useChatStore.getState().setServerRunning(SID, true) })
    await sendViaEnter(c)
    expect(chatApi.send).toHaveBeenCalled()
    expect(stopBtn(c)).toBeTruthy()

    // 上一轮那条迟到的收口（本轮的起轮 true 还没到）→ 边沿关的是**上一轮**，不是本轮
    act(() => { useChatStore.getState().setServerRunning(SID, false) })

    // 反向实验：删掉 reclaimStreamRegistration 里的轮次判据 ⇒ 本轮登记被删 ⇒ 三路全假、停止键闪没（本行红）
    //   保留登记 ⇒ 停止键仍在（本轮真在跑；登记是此刻唯一支撑，另两路都是 false）
    expect(stopBtn(c)).toBeTruthy()
    expect(sendBtn(c)).toBeFalsy()

    // 本轮起轮 true 到达（服务端确认这一轮）→ 本轮自己的收口边沿才可回收它
    act(() => { useChatStore.getState().setServerRunning(SID, true) })
    act(() => { useChatStore.getState().setServerRunning(SID, false) })
    expect(stopBtn(c)).toBeFalsy()
    expect(sendBtn(c)).toBeTruthy()
  })
})
