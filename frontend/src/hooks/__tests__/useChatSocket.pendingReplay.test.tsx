// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Client } from '@stomp/stompjs'
import { useChatStore } from '@/stores/chatStore'
import { useChatSocket } from '@/hooks/useChatSocket'

/**
 * ⭐ [2026-10-22 会话状态同步] 切会话/重连补拉「未回答的权限/提问」+ 已知会话实时订阅。
 *
 * <b>病情</b>：权限请求只推 `/topic/sessions/{sid}/permission-requests`，前端原实现只订阅
 * 「当前会话 + 有活跃流的会话」。其它会话的 AskUserQuestion 到达时**无订阅者**、事件被丢弃 →
 * 侧栏没有黄点、切回该会话也不再弹（实测 sess-df8b40c2 等 12 分钟后被 Deny）。
 *
 * <b>修法</b>（本文件锁死）：
 *   ① 切会话 / 重连 → `GET /sessions/{sid}/state` 补拉 pendingPermissions 并入队（按 requestId 去重）；
 *   ② knownSessionIds 名单内每个会话补订一个 permission-requests topic（实时黄点）。
 *   对齐 dsh「宿主在每次打开/切换会话时重放未回答的 pending」（stable rpcId replay）。
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

const stateMock = vi.hoisted(() => vi.fn())
vi.mock('@/api/sessions', () => ({ sessionApi: { state: stateMock } }))

const SID = 'sess-replay'
const OTHER = 'sess-other'

function Host({ ids }: { ids: string[] }) {
  useChatSocket(SID, {}, undefined, undefined, undefined, undefined, undefined, undefined, ids)
  return null
}

let container: HTMLDivElement | undefined
let root: Root | undefined

async function mount(ids: string[] = []) {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  act(() => { root!.render(<Host ids={ids} />) })
  act(() => { fakeClient.onConnect?.() })
  // replay 是异步 promise（sessionApi.state.then）→ 让出微任务
  await act(async () => { await Promise.resolve(); await Promise.resolve() })
}

describe('[会话状态同步] pending 补拉 + 已知会话订阅', () => {
  beforeEach(() => {
    fakeClient.subscriptions.length = 0
    stateMock.mockReset()
    useChatStore.setState({ permissionQueue: [] })
    ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  })
  afterEach(() => {
    if (root) act(() => root!.unmount())
    container?.remove()
    container = undefined
    root = undefined
    useChatStore.setState({ permissionQueue: [] })
  })

  it('⭐ 重连/切会话时补拉 pending → 入队（原本丢失的 AskUserQuestion 能找回）', async () => {
    stateMock.mockResolvedValue({
      sessionId: SID,
      pendingPermissions: [{
        requestId: 'r-ask-1', toolName: 'AskUserQuestion',
        toolInput: { questions: [{ question: '字号按哪套？', header: '视觉' }] },
        timestampMs: 1,
      }],
    })

    await mount()

    const queue = useChatStore.getState().permissionQueue
    expect(stateMock).toHaveBeenCalledWith(SID)
    expect(queue).toHaveLength(1)
    expect(queue[0].requestId).toBe('r-ask-1')
    expect(queue[0].toolName).toBe('AskUserQuestion')
    // 归一化：toolInput 透传（AskUserForm 靠 toolInput.questions 渲染选择题）
    expect((queue[0].toolInput as { questions?: unknown[] }).questions).toHaveLength(1)
  })

  it('重复补拉按 requestId 去重（不产生第二个弹窗）', async () => {
    stateMock.mockResolvedValue({
      sessionId: SID,
      pendingPermissions: [{ requestId: 'r-ask-1', toolName: 'AskUserQuestion', toolInput: {} }],
    })
    await mount()
    // 再触发一次重连
    act(() => { fakeClient.onConnect?.() })
    await act(async () => { await Promise.resolve(); await Promise.resolve() })

    expect(useChatStore.getState().permissionQueue).toHaveLength(1)
  })

  it('会话无 pending → 不产生任何入队（没有 ask 时状态自然为空）', async () => {
    stateMock.mockResolvedValue({ sessionId: SID, pendingPermissions: [] })
    await mount()

    expect(useChatStore.getState().permissionQueue).toHaveLength(0)
  })

  it('knownSessionIds 内的非当前会话 → 补订其 permission-requests（实时黄点依赖）', async () => {
    stateMock.mockResolvedValue({ sessionId: SID, pendingPermissions: [] })
    await mount([OTHER])

    const dests = fakeClient.subscriptions.map((s) => s.destination)
    expect(dests).toContain(`/topic/sessions/${OTHER}/permission-requests`)

    // 该 topic 收到事件 → 入队（即使不是当前会话）
    const sub = fakeClient.subscriptions.find((s) => s.destination === `/topic/sessions/${OTHER}/permission-requests`)!
    act(() => { sub.cb({ body: JSON.stringify({ requestId: 'r-other', toolName: 'AskUserQuestion', toolInput: {} }) }) })
    const q = useChatStore.getState().permissionQueue
    expect(q).toHaveLength(1)
    expect(q[0].sessionId).toBe(OTHER)
  })
})
