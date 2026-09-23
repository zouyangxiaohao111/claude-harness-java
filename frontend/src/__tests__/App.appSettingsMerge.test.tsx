// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '@/App'
import { useChatStore } from '@/stores/chatStore'
import { settingsApi } from '@/api/settings'
import { TIER_INHERIT_DISMISSED_KEY } from '@/hooks/useTierInheritGuide'
import type { AppSettings, PermissionUpdate } from '@/api/types'

/**
 * ⭐ [appName 通道 追加批 · F1] **App 层接线守门测试**：写 appSettings 的每条路径都必须经唯一写入口
 * （`App.applyAppSettings` → `mergeSettings`）—— 只读 `appName` 不许被 PUT 回流抹掉。
 *
 * <b>WHY 必须渲染真 App（规则九 · 测试验证意图）</b>：
 *   F1 是**接线型**缺陷：`useTierInheritGuide.apply()` 的 PUT 响应不含 `appName`，而
 *   `App.tsx` 的 `onSettingsUpdated: (updated) => setAppSettings(updated)` 直接裸写 ⇒
 *   点一次「一键套用各档位」就把 `appName` 抹成 undefined ⇒ `PermissionBubble.selfDirName` 变 null ⇒
 *   「编辑配置目录」档位文案**静默**退化成规则原文（场景发行 `nexusai-scene` 下文案是错的，无任何报错）。
 *
 *   若只测 `mergeSettings` 纯函数，或渲染一个「逐行照抄 App 接线」的 Host，**把 App 里那行写回
 *   改回裸 setAppSettings，测试照绿**（本仓在 `App.serverRunningReconcile.test.tsx` 已实证过这条教训）。
 *   故这里渲染真 App、走真 `useTierInheritGuide` + 真 `TierInheritModal` + 真 `PermissionBubble`，
 *   断言的落点是**用户看得见的按钮文案**。
 *
 * <b>反向实验（本批步骤 4）</b>：把 `mergeSettings` 改回 `return { ...incoming }` ⇒ 本文件必红；
 *   还原后转绿。
 */

const SID = 'sess-appname-merge'

vi.mock('@tauri-apps/api/core', () => ({ isTauri: () => false, invoke: vi.fn(async () => undefined) }))
vi.mock('@tauri-apps/api/webview', () => ({ getCurrentWebview: () => ({ onDragDropEvent: () => Promise.resolve(() => {}) }) }))
vi.mock('@tauri-apps/plugin-fs', () => ({ stat: vi.fn(), readFile: vi.fn(), writeTextFile: vi.fn() }))
vi.mock('@tauri-apps/api/event', () => ({ listen: vi.fn(async () => () => {}) }))

vi.mock('@/api/chat', () => ({
  uploadAttachment: vi.fn(),
  chatApi: {
    sessionRunning: vi.fn(() => new Promise(() => {})),
    messageCount: vi.fn(async () => ({ total: 0 })),
    listMessagesPage: vi.fn(async () => ({ messages: [], hasMore: false, total: 0 })),
    listMessages: vi.fn(async () => []),
    fetchImagesBatch: vi.fn(async () => ({})),
    send: vi.fn(),
    cancel: vi.fn(),
  },
}))
vi.mock('@/api/sessions', () => ({
  sessionApi: {
    // mainProjectId 必填：App.sendMessage 对「未绑定项目的会话」直接拦截
    list: vi.fn(async () => [{ id: 'sess-appname-merge', title: '会话', modelName: 'ds-openai/deepseek-v4-flash', mainProjectId: 'proj-app' }]),
    create: vi.fn(), remove: vi.fn(), update: vi.fn(),
  },
}))
vi.mock('@/api/sessionFiles', () => ({ sessionFilesApi: { list: vi.fn(async () => []), diff: vi.fn(), revert: vi.fn() } }))
vi.mock('@/api/projects', () => ({ projectApi: { list: vi.fn(async () => []), create: vi.fn(), bind: vi.fn() } }))
vi.mock('@/api/market', () => ({ marketApi: { useExpert: vi.fn() } }))
vi.mock('@/api/command', () => ({ commandApi: { list: vi.fn(async () => []), executeBuiltin: vi.fn() } }))
vi.mock('@/api/tasks', () => ({ tasksApi: { stopAllTasks: vi.fn(), backgroundAll: vi.fn() } }))
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

// 与本次接线无关的业务 hook → 空实现。
// ⚠️ **不 mock `@/hooks/useTierInheritGuide`**：本批要钉的正是它的 apply() → onSettingsUpdated 那条线。
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
vi.mock('@/hooks/useCommandQueue', () => ({
  useCommandQueue: () => ({ queuedCommands: [], setQueued: () => {}, refresh: async () => {}, popEditable: async () => null }),
}))

/**
 * 传输桩：只换 `settingsApi` 的 get/update。
 * 合并助手是**独立模块** `@/api/settingsMerge`（纯函数 · 不被 mock）⇒ 本测试跑的是真合并逻辑。
 */
vi.mock('@/api/settings', () => ({ settingsApi: { get: vi.fn(), update: vi.fn() } }))

/** AppSettings 的 18 个必填字段基底（测试夹具 · 与 SettingsModal.headersToggle.test.tsx 的写法一致） */
const base = (over: Partial<AppSettings> = {}): AppSettings => ({
  theme: 'dark',
  fontSize: 'medium',
  accent: null,
  animationsEnabled: true,
  mainModelName: null,
  fastModelName: null,
  weakModelName: null,
  mediumModelName: null,
  strongModelName: null,
  subagentModelName: null,
  autoCompactWindow: null,
  maxOutputTokens: null,
  fallbackModelName: null,
  multimodalModelName: null,
  ttsModelName: null,
  asrModelName: null,
  autoMemoryEnabled: null,
  autoMemoryDirectory: null,
  ...over,
}) as AppSettings

/** 一次真实 GET /settings 响应：场景中台线发行（appName=nexusai-scene）+ 主模型已配 + 六档全空。 */
const GET_RESPONSE: AppSettings = base({
  appName: 'nexusai-scene',
  configHome: 'C:\\Users\\x\\.nexusai-scene',
  mainModelName: 'ds-openai/deepseek-v4-flash',
  // 六档（fast/subagent/weak/medium/strong/classifier）**全部不设** ⇒ 档位向导的条件成立
})

/** 一次真实 PUT /settings 响应（后端 SettingsDto）—— ⛔ 故意**不含** appName / configHome。 */
const PUT_RESPONSE: AppSettings = base({
  mainModelName: 'ds-openai/deepseek-v4-flash',
  fastModelName: 'ds-openai/deepseek-v4-flash',
  subagentModelName: 'ds-openai/deepseek-v4-flash',
  weakModelName: 'ds-openai/deepseek-v4-flash',
  mediumModelName: 'ds-openai/deepseek-v4-flash',
  strongModelName: 'ds-openai/deepseek-v4-flash',
  classifierModel: 'ds-openai/deepseek-v4-flash',
})

/** 后端「编辑自有设置（本会话）」档产出的规则形态（PermissionUpdates.selfConfigRootRuleSuggestion） */
const SCENE_SELF_ROOT_RULE: PermissionUpdate = {
  type: 'addRules',
  rules: [{ toolName: 'Edit', ruleContent: '~/.nexusai-scene/**' }],
  behavior: 'allow',
  destination: 'session',
}

async function flushPromises() {
  await act(async () => { await Promise.resolve(); await Promise.resolve(); await Promise.resolve() })
}

let container: HTMLDivElement | undefined
let root: Root | undefined

/** 渲染真 App：GET 已回（appName=nexusai-scene）→ 已切到真会话 → 权限弹窗已在场 → 档位向导已弹。 */
async function mountApp(): Promise<HTMLDivElement> {
  ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  act(() => { root!.render(<App />) })
  await flushPromises()                                   // settings GET + 会话列表 GET
  act(() => {
    useChatStore.getState().enqueuePermission({
      kind: 'message',
      sessionId: SID,
      requestId: 'req-appname-1',
      toolName: 'Edit',
      suggestions: [SCENE_SELF_ROOT_RULE],
    })
  })
  await flushPromises()
  return container
}

/** 权限弹窗「一键授权」第三档的文案（自有根档位）。不在场 ⇒ null。 */
const suggestionLabel = (c: HTMLElement): string | null =>
  c.querySelector('.pb-suggestions button')?.textContent ?? null

const applyBtn = (c: HTMLElement): HTMLButtonElement | null =>
  c.querySelector('.ti-btn-primary')

describe('[appName 通道 追加批 · F1] 点「一键套用各档位」后，自有根文案不得退化', () => {
  beforeEach(() => {
    useChatStore.setState({
      sessions: [], serverRunning: {}, streamOrder: {}, streams: {}, messages: {},
      permissionQueue: [], agentStatus: 'idle',
    })
    // 档位向导的去重键：残留会让向导不弹 ⇒ 本文件恒绿（假绿）
    try { localStorage.clear() } catch { /* ignore */ }
    vi.mocked(settingsApi.get).mockReset()
    vi.mocked(settingsApi.update).mockReset()
    vi.mocked(settingsApi.get).mockResolvedValue(GET_RESPONSE)
    vi.mocked(settingsApi.update).mockResolvedValue(PUT_RESPONSE)
  })

  afterEach(() => {
    if (root) act(() => root!.unmount())
    container?.remove()
    container = undefined
    root = undefined
    useChatStore.setState({ sessions: [], permissionQueue: [], serverRunning: {}, streamOrder: {}, streams: {}, messages: {} })
  })

  it('前置：GET 的 appName 已进弹窗（第三档是「编辑配置目录 .nexusai-scene/」，不是规则原文）', async () => {
    const c = await mountApp()
    expect(localStorage.getItem(TIER_INHERIT_DISMISSED_KEY)).not.toBe('1')   // 向导未被去重压掉
    expect(applyBtn(c), '档位向导未弹出 ⇒ 后半段无从验证').toBeTruthy()      // 向导模板在场
    const label = suggestionLabel(c)
    expect(label).toContain('编辑配置目录')
    expect(label).toContain('.nexusai-scene/')
  })

  it('⭐ F1 回归：点「一键套用」后 appName 仍在 ⇒ 文案仍是「编辑配置目录 .nexusai-scene/」', async () => {
    const c = await mountApp()
    const before = suggestionLabel(c)
    expect(before).toContain('编辑配置目录 .nexusai-scene/')

    const btn = applyBtn(c)
    expect(btn).toBeTruthy()
    act(() => { btn!.dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    await flushPromises()

    // 前置：PUT 真的发出去了、且响应**不含** appName（证明这条链真的被走了一遍）
    expect(settingsApi.update).toHaveBeenCalledTimes(1)
    const putArg = vi.mocked(settingsApi.update).mock.calls[0][0] as Record<string, unknown>
    expect(putArg.fastModelName).toBe('ds-openai/deepseek-v4-flash')
    expect('appName' in putArg).toBe(false)
    expect(applyBtn(c), '套用成功后向导应关闭（PUT 未走通 ⇒ 本用例结论不可信）').toBeFalsy()

    // ⭐ 反向实验：把 mergeSettings 改回 `return { ...incoming }` ⇒ 此行红
    //   （appName 被抹 ⇒ selfDirName=null ⇒ 文案退化成规则原文，无「编辑配置目录」）
    const after = suggestionLabel(c)
    expect(after, 'F1 复现：PUT 回流把只读 appName 抹掉了，自有根文案静默退化').toContain('编辑配置目录')
    expect(after).toContain('.nexusai-scene/')
    expect(after).toBe(before)
  })
})
