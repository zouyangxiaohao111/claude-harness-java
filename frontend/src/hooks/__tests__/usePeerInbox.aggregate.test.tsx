// @vitest-environment jsdom
import { act, useRef } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Client } from '@stomp/stompjs'
import type { PeerInboxAggregateEvent } from '@/api/peerInbox'
import { usePeerInboxFeed, __resetPeerInboxAggregateForTest } from '@/hooks/usePeerInbox'
import { PeerPendingHint } from '@/components/center/PeerPendingHint'
import {
  __resetPeerInboxAlertForTest,
  usePeerInboxStore,
} from '@/stores/peerInboxStore'

/**
 * [peer-inbox-aggregate] seq 连续性 + 对账 + 补拉时机的判别测试（需求 6a–g）。
 *
 * <h2>WHY（规则九）</h2>
 * <p>`seq` 是「有没有丢帧」的唯一信号：连续才能增量应用；跳号/回退说明中间帧丢了，
 * 此时若仍按本条事件加减，计数会永久错位（用户看到的角标 ≠ 真实待处理数）。
 * 所以本文件把「连续 → 应用」「缺口 → 全量对账覆盖」钉成对立的一组用例：
 * 去掉 seq 校验时，用例 b（跳号）必然变红 —— 这就是它的判别力。
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

vi.mock('@/utils/systemNotify', () => ({ sendSystemNotification: vi.fn(async () => true) }))

// eslint-disable-next-line import/first
import { peerInboxApi } from '@/api/peerInbox'

const A = 'sess-aaaa1111'
const B = 'sess-bbbb2222'
const AGG = '/topic/peer-inbox'

const listMock = vi.mocked(peerInboxApi.list)
const countsMock = vi.mocked(peerInboxApi.counts)
const markSeenMock = vi.mocked(peerInboxApi.markSeen)

function evt(
  targetSessionId: string,
  seq: number,
  type: PeerInboxAggregateEvent['type'],
  pendingCount?: number,
  item?: Record<string, unknown>,
): PeerInboxAggregateEvent {
  return {
    type,
    seq,
    targetSessionId,
    ...(pendingCount === undefined ? {} : { pendingCount }),
    ...(item ? { item } : {}),
  }
}

function holdItem(messageId: string, targetSessionId: string) {
  return {
    messageId,
    targetSessionId,
    sourceSessionId: 'sess-cccc3333',
    sourceTitle: '来源会话',
    summary: '事件摘要',
    status: 'pending',
    createdAt: '2026-11-19T10:00:00Z',
  }
}

function Host({ sid }: { sid: string | null }) {
  const clientRef = useRef<Client | null>(socket.fakeClient as unknown as Client)
  usePeerInboxFeed(sid, clientRef)
  return null
}

function push(payload: PeerInboxAggregateEvent): void {
  const sub = socket.subs.filter((s) => s.topic === AGG && !s.unsubscribed).pop()
  if (!sub) throw new Error('前置失败：聚合 topic 未订阅')
  act(() => { sub.cb(payload) })
}

let container: HTMLDivElement
let root: Root
const flush = async () => {
  // 对账/补拉是多段 promise 链（fetch → set → 可能 load）⇒ 多让几轮微任务。
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

describe('聚合事件 · seq 连续（a）', () => {
  it('连续 seq：逐条更新目标会话计数，不触发对账', async () => {
    act(() => { root.render(<Host sid={A} />) })
    await flush()
    countsMock.mockClear()

    push(evt(B, 1, 'peer.inbox.hold', 1, holdItem('b-1', B)))
    push(evt(B, 2, 'peer.inbox.updated', 4))
    push(evt(A, 3, 'peer.inbox.hold', 1, holdItem('a-1', A)))

    const s = usePeerInboxStore.getState()
    expect(s.lastSeq).toBe(3)
    expect(s.countsBySession[B]).toBe(4)
    expect(s.countsBySession[A]).toBe(1)
    expect(s.totalPending).toBe(5)
    expect(countsMock).not.toHaveBeenCalled()
  })
})

describe('聚合事件 · 丢帧对账（b/c）', () => {
  it('b) 跳号：不应用该帧，改调 counts 全量对账并覆盖本地状态', async () => {
    act(() => { root.render(<Host sid={A} />) })
    await flush()
    push(evt(B, 1, 'peer.inbox.hold', 2, holdItem('b-1', B)))
    expect(usePeerInboxStore.getState().countsBySession[B]).toBe(2)
    countsMock.mockClear()
    // 对账真值：B 实际 9（中间丢了 1 帧），事件自身声称 4 —— 必须采信对账结果
    countsMock.mockResolvedValue({ seq: 3, counts: { [A]: 0, [B]: 9 } })

    push(evt(B, 3, 'peer.inbox.updated', 4))
    await flush()

    expect(countsMock).toHaveBeenCalledTimes(1)
    const s = usePeerInboxStore.getState()
    expect(s.countsBySession[B], '以 counts 覆盖，不用事件里的 4').toBe(9)
    expect(s.totalPending).toBe(9)
    expect(s.lastSeq, '基线推进到对账返回的 seq').toBe(3)
  })

  it('c) 回退：同样触发对账，且不把旧 seq 应用到计数', async () => {
    act(() => { root.render(<Host sid={A} />) })
    await flush()
    push(evt(B, 1, 'peer.inbox.hold', 1, holdItem('b-1', B)))
    push(evt(B, 2, 'peer.inbox.updated', 3))
    expect(usePeerInboxStore.getState().countsBySession[B]).toBe(3)
    countsMock.mockClear()
    countsMock.mockResolvedValue({ seq: 2, counts: { [B]: 3 } })

    push(evt(B, 1, 'peer.inbox.removed', 0))

    expect(countsMock).toHaveBeenCalledTimes(1)
    expect(usePeerInboxStore.getState().countsBySession[B], '回退帧不得把计数清零').toBe(3)
    expect(usePeerInboxStore.getState().lastSeq).toBe(2)
  })
})

describe('补拉时机（d/e/f）', () => {
  it('d) socket 重连：列表 + 计数各补拉一次', async () => {
    act(() => { root.render(<Host sid={A} />) })
    await flush()
    const listBefore = listMock.mock.calls.length
    const countsBefore = countsMock.mock.calls.length

    socket.fakeClient.connected = false
    act(() => { socket.fireConnect() })
    expect(listMock.mock.calls.length).toBe(listBefore)

    socket.fakeClient.connected = true
    act(() => { socket.fireConnect() })
    await flush()

    expect(listMock.mock.calls.length).toBe(listBefore + 1)
    expect(countsMock.mock.calls.length).toBe(countsBefore + 1)
  })

  it('e) 页面回到前台：列表 + 计数各补拉一次', async () => {
    act(() => { root.render(<Host sid={A} />) })
    await flush()
    const listBefore = listMock.mock.calls.length

    push(evt(B, 1, 'peer.inbox.updated', 1)) // 建立 seq 基线，排除首帧对账干扰
    countsMock.mockClear()
    const listMid = listMock.mock.calls.length
    expect(listMid).toBe(listBefore)

    const stateSpy = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
    act(() => { document.dispatchEvent(new Event('visibilitychange')) })
    await flush()
    stateSpy.mockRestore()

    expect(listMock.mock.calls.length).toBe(listMid + 1)
    expect(countsMock.mock.calls.length).toBe(1)
  })

  it('f) 轮询已移除：长时间流逝不再请求列表/计数', async () => {
    vi.useFakeTimers()
    act(() => { root.render(<Host sid={A} />) })
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    const listBefore = listMock.mock.calls.length
    const countsBefore = countsMock.mock.calls.length

    await act(async () => { await vi.advanceTimersByTimeAsync(10 * 60_000) })

    expect(listMock.mock.calls.length).toBe(listBefore)
    expect(countsMock.mock.calls.length).toBe(countsBefore)
  })
})

describe('当前会话联动（g）', () => {
  it('当前会话的列表、角标与提示条随聚合事件更新（不切会话/不拉历史）', async () => {
    // 用真实组件读取 store：提示条只吃 pendingCount（与角标同一口径）
    function Fixture() {
      const clientRef = useRef<Client | null>(socket.fakeClient as unknown as Client)
      usePeerInboxFeed(A, clientRef)
      const pending = usePeerInboxStore((s) => (s.sessionId === A ? s.pendingCount : 0))
      // 选择器必须返回稳定原始值（返回新数组会触发无限重渲染）
      const ids = usePeerInboxStore((s) => s.items.map((i) => i.messageId).join(','))
      return (
        <div>
          {pending > 0 && <PeerPendingHint count={pending} onOpen={() => {}} onRejectAll={() => {}} />}
          <span data-testid="ids">{ids}</span>
        </div>
      )
    }
    act(() => { root.render(<Fixture />) })
    await flush()
    expect(container.querySelector('.peer-pending-hint')).toBeNull()

    push(evt(A, 1, 'peer.inbox.hold', 1, holdItem('a-1', A)))

    expect(container.querySelector('[data-testid="ids"]')?.textContent).toBe('a-1')
    expect(container.querySelector('.peer-pending-hint')?.textContent)
      .toContain('有 1 条待确认消息')
    const s = usePeerInboxStore.getState()
    expect(s.pendingCount).toBe(1)
    expect(s.totalPending).toBe(1)
    // 终态行不计（declined/expired）
    push(evt(A, 2, 'peer.inbox.updated', 1, {
      ...holdItem('a-1', A), status: 'declined',
    }))
    expect(usePeerInboxStore.getState().totalPending).toBe(1)
  })
})
