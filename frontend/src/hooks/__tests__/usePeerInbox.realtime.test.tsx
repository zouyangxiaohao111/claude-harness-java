// @vitest-environment jsdom
import { act, useRef } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Client } from '@stomp/stompjs'
import type { PeerHeldItemDto } from '@/api/types'
import { usePeerInboxFeed, __resetPeerInboxAggregateForTest } from '@/hooks/usePeerInbox'
import {
  __resetPeerInboxAlertForTest,
  peerInboxPendingCount,
  usePeerInboxStore,
} from '@/stores/peerInboxStore'

/**
 * [peer-inbox-aggregate] 订阅编排：**全局一条**订阅 `/topic/peer-inbox`，不随会话重订；
 * 当前会话的事件立即合并、别的会话只更新计数（不串列表）；重连补拉；已无周期轮询。
 *
 * <h2>WHY（规则九）</h2>
 * <p>旧实现按会话 topic 订阅 + 60s 轮询：切会话要退订重订，丢帧只能等轮询。
 * 现改为一条聚合流（targetSessionId 归属 + seq 连续），本文件钉住：
 * ① 订阅数与会话数无关（切换不新增订阅）；② 非当前会话事件绝不进本会话列表；
 * ③ 重连重新 attach 并补拉一次；④ 时间流逝不再触发任何请求（轮询已删）。
 */

const socket = vi.hoisted(() => {
  const listeners = new Set<() => void>()
  const subs: Array<{ topic: string; cb: (e: unknown) => void; unsubscribed: boolean }> = []
  return {
    listeners,
    subs,
    fakeClient: { connected: true },
    onSocketConnect: (cb: () => void) => {
      listeners.add(cb)
      return () => { listeners.delete(cb) }
    },
    subscribeStream: (_client: unknown, topic: string, cb: (e: unknown) => void) => {
      const entry = { topic, cb, unsubscribed: false }
      subs.push(entry)
      return { unsubscribe: () => { entry.unsubscribed = true } }
    },
    fireConnect: () => { for (const cb of [...listeners]) cb() },
  }
})

vi.mock('@/api/socket', () => ({
  onSocketConnect: socket.onSocketConnect,
  subscribeStream: socket.subscribeStream,
}))

vi.mock('@/api/peerInbox', () => ({
  peerInboxApi: {
    list: vi.fn(),
    approve: vi.fn(),
    reject: vi.fn(),
    markSeen: vi.fn(),
    counts: vi.fn(),
  },
}))

// 启动/实时新 hold 会发系统通知（jsdom 无权限）→ 用替身，避免噪声与真实 API。
vi.mock('@/utils/systemNotify', () => ({ sendSystemNotification: vi.fn(async () => true) }))

// eslint-disable-next-line import/first
import { peerInboxApi } from '@/api/peerInbox'

const A = 'sess-aaaa1111'
const B = 'sess-bbbb2222'
const AGG = '/topic/peer-inbox'

const listMock = vi.mocked(peerInboxApi.list)
const countsMock = vi.mocked(peerInboxApi.counts)
const markSeenMock = vi.mocked(peerInboxApi.markSeen)

function held(messageId: string, targetSessionId: string): PeerHeldItemDto {
  return {
    messageId,
    targetSessionId,
    fromSessionId: 'sess-cccc3333',
    fromSessionTitle: '来源',
    summary: '摘要',
    reason: 'EXPLICIT_HOLD',
    heldAt: '2026-11-19T10:00:00Z',
    body: '正文',
  }
}

/** 生产载荷形态：聚合事件（seq 单调 + targetSessionId 归属 + 可选 item）。 */
function aggregateEvent(
  targetSessionId: string,
  seq: number,
  messageId: string,
  pendingCount = 1,
) {
  return {
    type: 'peer.inbox.hold',
    seq,
    targetSessionId,
    pendingCount,
    item: {
      messageId,
      targetSessionId,
      sourceSessionId: 'sess-cccc3333',
      sourceTitle: '来源',
      sourceKind: 'peer',
      fromMode: 'default',
      summary: '事件里的摘要',
      body: '事件正文',
      reason: 'EXPLICIT_HOLD',
      status: 'pending',
      createdAt: '2026-11-19T10:00:00Z',
    },
  }
}

function Host({ sid }: { sid: string | null }) {
  const clientRef = useRef<Client | null>(socket.fakeClient as unknown as Client)
  usePeerInboxFeed(sid, clientRef)
  return null
}

let container: HTMLDivElement
let root: Root

const flush = async () => {
  await act(async () => {
    for (let i = 0; i < 5; i++) await Promise.resolve()
  })
}

function resetStore(): void {
  usePeerInboxStore.setState({
    sessionId: null,
    items: [],
    loading: false,
    error: null,
    acting: null,
    notice: null,
    open: false,
    inboxItems: [],
    inboxLoading: false,
    inboxError: null,
    inboxNotice: null,
    inboxRowErrors: {},
    inboxActing: null,
    jumpRequest: null,
    pendingCount: 0,
    unreadCount: 0,
    lastSeq: null,
    countsBySession: {},
    totalPending: 0,
  })
}

function pushAgg(payload: unknown): void {
  const sub = socket.subs.filter((s) => s.topic === AGG && !s.unsubscribed).pop()
  if (!sub) throw new Error('前置失败：聚合 topic 未订阅')
  act(() => { sub.cb(payload) })
}

beforeEach(() => {
  resetStore()
  __resetPeerInboxAlertForTest()
  __resetPeerInboxAggregateForTest()
  socket.listeners.clear()
  socket.subs.length = 0
  socket.fakeClient.connected = true
  listMock.mockReset()
  listMock.mockResolvedValue([])
  countsMock.mockReset()
  countsMock.mockResolvedValue({ seq: 0, counts: {} })
  markSeenMock.mockReset()
  markSeenMock.mockResolvedValue({ updated: 0 })
  ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
})

afterEach(() => {
  act(() => { root.unmount() })
  container.remove()
  vi.useRealTimers()
  resetStore()
})

describe('usePeerInboxFeed · 全局聚合订阅', () => {
  it('只订一条聚合 topic；重复渲染/切会话都不新增订阅', async () => {
    act(() => { root.render(<Host sid={A} />) })
    await flush()
    expect(socket.subs.filter((s) => s.topic === AGG && !s.unsubscribed)).toHaveLength(1)

    act(() => { root.render(<Host sid={B} />) })
    await flush()
    act(() => { root.render(<Host sid={A} />) })
    await flush()

    expect(socket.subs.filter((s) => s.topic === AGG && !s.unsubscribed),
      '切会话不得重订/新增订阅').toHaveLength(1)
    expect(socket.subs.every((s) => s.topic === AGG), '不再按会话订阅').toBe(true)
  })

  it('当前会话事件立即更新列表与角标（不等任何轮询）', async () => {
    act(() => { root.render(<Host sid={A} />) })
    await flush()
    const callsBefore = listMock.mock.calls.length

    pushAgg(aggregateEvent(A, 1, 'peer-rt-1'))

    const s = usePeerInboxStore.getState()
    expect(s.items.map((i) => i.messageId)).toEqual(['peer-rt-1'])
    expect(peerInboxPendingCount(s.items)).toBe(1)
    expect(s.countsBySession[A]).toBe(1)
    expect(listMock.mock.calls.length, '事件自带行体，不额外拉列表').toBe(callsBefore)

    // 重复推送同一 messageId（seq 连续）：不重复计数
    pushAgg(aggregateEvent(A, 2, 'peer-rt-1'))
    expect(usePeerInboxStore.getState().items).toHaveLength(1)
  })

  it('别的会话事件只计数：不进当前列表、不切会话', async () => {
    act(() => { root.render(<Host sid={A} />) })
    await flush()
    const sidBefore = usePeerInboxStore.getState().sessionId
    const callsBefore = listMock.mock.calls.length

    pushAgg(aggregateEvent(B, 1, 'peer-b-1', 3))

    const s = usePeerInboxStore.getState()
    expect(s.items, 'B 的待收件不得显示在 A 的列表').toEqual([])
    expect(s.countsBySession[B], '每会话计数 map 更新').toBe(3)
    expect(s.totalPending).toBe(3)
    expect(s.sessionId).toBe(sidBefore)
    expect(listMock.mock.calls.length, '非当前会话不刷新列表').toBe(callsBefore)
  })

  it('切会话不重置全局计数 map；切回来 pending 仍按新会话取回', async () => {
    listMock.mockResolvedValue([held('a1', A), held('a2', A)])
    act(() => { root.render(<Host sid={A} />) })
    await flush()
    expect(usePeerInboxStore.getState().pendingCount).toBe(2)
    pushAgg(aggregateEvent(B, 1, 'peer-b-seed', 2))
    expect(usePeerInboxStore.getState().countsBySession[B]).toBe(2)
    expect(usePeerInboxStore.getState().totalPending).toBe(4)

    listMock.mockResolvedValue([held('b1', B)])
    act(() => { root.render(<Host sid={B} />) })
    await flush()
    const sB = usePeerInboxStore.getState()
    expect(sB.pendingCount, '切到 B 后按 B 的 pending 计').toBe(1)
    expect(sB.countsBySession[B], 'B 的计数被列表刷新同步').toBe(1)
    expect(sB.countsBySession[A], 'A 的计数保留在全局 map').toBe(2)
    expect(sB.totalPending).toBe(3)

    listMock.mockResolvedValue([held('a1', A), held('a2', A)])
    act(() => { root.render(<Host sid={A} />) })
    await flush()
    const sA = usePeerInboxStore.getState()
    expect(sA.items.map((i) => i.messageId)).toEqual(['a1', 'a2'])
    expect(sA.pendingCount, '切回来待处理仍在').toBe(2)
    expect(sA.totalPending, 'A=2 + B=1（B 被其列表刷新订正）').toBe(3)
  })

  it('事件不触发会话切换、不拉历史（只改计数/本会话列表）', async () => {
    act(() => { root.render(<Host sid={A} />) })
    await flush()
    const callsBefore = listMock.mock.calls.length
    const sidBefore = usePeerInboxStore.getState().sessionId

    pushAgg(aggregateEvent(A, 1, 'peer-rt-2'))

    expect(listMock.mock.calls.length, 'hold 自带行体不拉列表/历史').toBe(callsBefore)
    expect(usePeerInboxStore.getState().sessionId).toBe(sidBefore)
    expect(usePeerInboxStore.getState().items.map((i) => i.messageId)).toEqual(['peer-rt-2'])
  })

  it('重连后重新 attach（当前连接只订一条）并补拉一次列表与计数', async () => {
    act(() => { root.render(<Host sid={A} />) })
    await flush()
    const subsBefore = socket.subs.length
    const listBefore = listMock.mock.calls.length
    const countsBefore = countsMock.mock.calls.length

    // 断线：onConnect 回调里不得再订阅（client.connected=false）
    socket.fakeClient.connected = false
    act(() => { socket.fireConnect() })
    expect(socket.subs.length).toBe(subsBefore)

    // 重连成功：重订聚合 topic（仍只有一条），补拉列表 + 计数各一次
    socket.fakeClient.connected = true
    act(() => { socket.fireConnect() })
    await flush()
    expect(socket.subs.filter((s) => s.topic === AGG && !s.unsubscribed)).toHaveLength(1)
    expect(listMock.mock.calls.length).toBe(listBefore + 1)
    expect(countsMock.mock.calls.length).toBe(countsBefore + 1)
  })

  it('已无周期轮询：时间流逝不再发起任何列表/计数请求', async () => {
    vi.useFakeTimers()
    act(() => { root.render(<Host sid={A} />) })
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    const listBefore = listMock.mock.calls.length
    const countsBefore = countsMock.mock.calls.length

    await act(async () => { await vi.advanceTimersByTimeAsync(5 * 60_000) })

    expect(listMock.mock.calls.length, '60s 轮询已移除').toBe(listBefore)
    expect(countsMock.mock.calls.length).toBe(countsBefore)
  })
})
