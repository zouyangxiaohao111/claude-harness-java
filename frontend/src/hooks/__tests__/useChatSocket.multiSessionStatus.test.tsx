// @vitest-environment jsdom
import { act, useMemo } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Client } from '@stomp/stompjs'
import { useChatStore } from '@/stores/chatStore'
import { useChatSocket } from '@/hooks/useChatSocket'
import { SessionList } from '@/components/left/SessionList'
import { chatApi } from '@/api/chat'
import type { SessionDto } from '@/api/types'

/**
 * ⭐ [2026-11-12 用户原话]「重新启动时 后端还在跑 左侧没有反应 闪烁」
 *
 * <b>病灶</b>：主代理运行态的三条既有通道（useServerRunningRebuild / refreshServerRunning /
 * status topic 订阅）**都只覆盖当前会话**。F5/重启后后台 run 仍在跑，但侧栏非当前会话的行没有
 * 任何信号源 ⇒ 不闪。本文件钉三件事：
 * <ol>
 *   <li>已知会话的 status topic 全部订阅 ⇒ 非当前会话的 thinking/idle 也能写 store 并点亮侧栏点；</li>
 *   <li>非当前会话的事件不得污染当前会话的全局单值（agentStatus）；</li>
 *   <li>连接建立时按 sid 一次性重建全部已知会话运行态（并发上限 6），列表刷新只补查**新增**的 sid。</li>
 * </ol>
 *
 * <b>手法</b>：照 {@code useChatSocket.sessionStatus.test.tsx} 的假 STOMP client（只 mock 传输层）。
 * 宿主 = 真 {@link useChatSocket} + 真 {@link SessionList}（判据只看用户能看到的 DOM：
 * `.session-item .status-dot` 的 className）。
 */

const fakeClient = vi.hoisted(() => {
  const subscriptions: Array<{ destination: string; cb: (msg: { body: string }) => void }> = []
  return {
    subscriptions,
    connected: true,
    onConnect: null as null | (() => void),
    onDisconnect: null as null | (() => void),
    onWebSocketClose: null as null | (() => void),
    activate: () => {},
    deactivate: async () => {},
    publish: () => {},
    subscribe: (destination: string, cb: (msg: { body: string }) => void) => {
      subscriptions.push({ destination, cb })
      return { id: String(subscriptions.length), unsubscribe: () => {} }
    },
  }
})

vi.mock('@/api/socket', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/socket')>()
  return { ...actual, createSocketClient: () => fakeClient as unknown as Client }
})

// 重建通道的数据源（refreshServerRunning → chatApi.sessionRunning）：只替换这一个函数，其余保持真实现
vi.mock('@/api/chat', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/chat')>()
  return {
    ...actual,
    chatApi: {
      ...actual.chatApi,
      sessionRunning: vi.fn(async () => ({ running: false })),
    },
  }
})

const SID = 'sess-multi-current'
const OTHER = 'sess-multi-bg'

function dto(id: string, title: string): SessionDto {
  return {
    id, model: null, modelName: null, title, time: null, group: null, tabId: null,
    mainProjectId: null, effortLevel: null, ultracodeEnabled: null, bareMode: null, messageCount: 0,
  }
}

const SESSIONS = [dto(SID, '甲会话'), dto(OTHER, '乙会话')]

/** 打进一条 session.status 到该会话的 **status** topic（本修法扩订阅面的正是这条通道）。 */
function pushStatus(status: string, sid: string) {
  const topic = `/topic/sessions/${sid}/status`
  const sub = fakeClient.subscriptions.find((s) => s.destination === topic)
  if (!sub) throw new Error(`前置失败：${topic} 未订阅 —— 非当前会话的 status topic 没有被补订`)
  act(() => { sub.cb({ body: JSON.stringify({ type: 'session.status', status, sessionId: sid }) }) })
}

/** 侧栏里某个会话行的状态点（按标题定位）。 */
function dotOf(c: HTMLElement, title: string): HTMLElement | null {
  const item = Array.from(c.querySelectorAll('.session-item')).find(
    (el) => el.querySelector('.title')?.textContent === title,
  )
  return (item?.querySelector('.status-dot') as HTMLElement | null) ?? null
}

/** 真 useChatSocket + 真 SessionList（点数 derive 自 store，与 App 的 runningSessionIds 同构）。 */
function Host({ known }: { known: string[] }) {
  useChatSocket(SID, {}, undefined, undefined, undefined, undefined, undefined, undefined, known)
  const runningMap = useChatStore((s) => s.serverRunning)
  const runningIds = useMemo(
    () => new Set(Object.entries(runningMap).filter(([, on]) => on).map(([sid]) => sid)),
    [runningMap],
  )
  return (
    <SessionList
      sessions={SESSIONS}
      activeSession={SID}
      switchSession={() => {}}
      runningSessionIds={runningIds}
      flatHistory
    />
  )
}

async function flushPromises() {
  await act(async () => { await Promise.resolve(); await Promise.resolve(); await Promise.resolve() })
}

let container: HTMLDivElement | undefined
let root: Root | undefined
const KNOWN = [SID, OTHER]

function renderHost(known: string[]) {
  act(() => { root!.render(<Host known={known} />) })
}

describe('[多会话运行点] 已知会话 status topic 订阅 + 一次性重建', () => {
  beforeEach(async () => {
    fakeClient.subscriptions.length = 0
    useChatStore.setState({ serverRunning: {}, agentStatus: 'idle' })
    vi.mocked(chatApi.sessionRunning).mockReset()
    vi.mocked(chatApi.sessionRunning).mockImplementation(async (sid: string) => ({ running: sid === OTHER }))
    ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    renderHost(KNOWN)
    act(() => { fakeClient.onConnect?.() })
    await flushPromises()
  })

  afterEach(() => {
    if (root) act(() => root!.unmount())
    container?.remove()
    container = undefined
    root = undefined
    useChatStore.setState({ serverRunning: {}, agentStatus: 'idle' })
  })

  it('前置：非当前会话的 status topic 已被补订（否则下面用例在错误的地方打事件）', () => {
    expect(fakeClient.subscriptions.some((s) => s.destination === `/topic/sessions/${OTHER}/status`)).toBe(true)
  })

  it('⭐ 非当前会话 thinking ⇒ 该会话运行点出现（后端还在跑，左侧必须有反应）', () => {
    act(() => { useChatStore.getState().setServerRunning(OTHER, false) })
    expect(useChatStore.getState().serverRunning[OTHER]).toBe(false)

    pushStatus('thinking', OTHER)

    expect(useChatStore.getState().serverRunning[OTHER]).toBe(true)
    expect(dotOf(container!, '乙会话')?.className ?? '', '乙会话必须是 running 点').toContain('running')
  })

  it('⭐ 非当前会话 idle ⇒ 运行点消失', () => {
    pushStatus('thinking', OTHER)
    expect(dotOf(container!, '乙会话')?.className ?? '').toContain('running')

    pushStatus('idle', OTHER)

    expect(useChatStore.getState().serverRunning[OTHER]).toBe(false)
    expect(dotOf(container!, '乙会话')).toBeNull()
  })

  it('⭐ 不污染当前会话头部：后台会话 thinking 不得改 agentStatus', () => {
    // 反向实验：去掉 dispatchEvent 里 `sid === sessionIdRef.current || !sid` 守卫 ⇒ 本断言红
    // （全局单值被后台会话的 thinking 写成 'thinking'，当前会话头部无故闪烁）
    pushStatus('thinking', OTHER)

    expect(useChatStore.getState().agentStatus).toBe('idle')
  })

  it('⭐ 连接建立 ⇒ 按 sid 重建全部已知会话运行态（两个 sid 各查一次）', () => {
    // 反向实验：删掉 onConnect 里的 rebuildKnownRunning 调用 ⇒ 本用例红（0 次调用、store 无键）
    expect(chatApi.sessionRunning).toHaveBeenCalledWith(SID)
    expect(chatApi.sessionRunning).toHaveBeenCalledWith(OTHER)
    expect(vi.mocked(chatApi.sessionRunning).mock.calls.filter(([s]) => s === OTHER).length).toBe(1)
    expect(useChatStore.getState().serverRunning[OTHER]).toBe(true)
  })

  it('⭐ 列表刷新（内容相同、引用不同）⇒ 不重复重建', async () => {
    const before = vi.mocked(chatApi.sessionRunning).mock.calls.length
    expect(before).toBe(2)

    renderHost([SID, OTHER])   // 新数组引用、内容相同 = storeSessions 每 5s 刷新的形态
    await flushPromises()

    expect(vi.mocked(chatApi.sessionRunning).mock.calls.length, '差集为空 ⇒ 零额外 GET').toBe(before)
  })

  it('⭐ 断线重连 ⇒ 断线清空的 status 订阅由 onConnect 重建（不是只靠列表 effect 那一次）', () => {
    act(() => { fakeClient.onDisconnect?.() })
    fakeClient.subscriptions.length = 0   // 断线后所有 STOMP 订阅随连接失效（hook 内 statusSubsRef 已清空）
    // 反向实验：删掉 onConnect 里的 syncKnownStatusSubscriptions ⇒ 本断言红
    act(() => { fakeClient.onConnect?.() })

    expect(fakeClient.subscriptions.some((s) => s.destination === `/topic/sessions/${OTHER}/status`)).toBe(true)
  })
})
