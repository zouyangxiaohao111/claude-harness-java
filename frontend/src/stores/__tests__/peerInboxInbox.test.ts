import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { PeerHeldItemDto } from '@/api/types'

vi.mock('@/utils/systemNotify', () => ({ sendSystemNotification: vi.fn(async () => true) }))
vi.mock('@/api/peerInbox', () => ({
  peerInboxApi: {
    list: vi.fn(),
    approve: vi.fn(),
    reject: vi.fn(),
    rejectAll: vi.fn(),
    markSeen: vi.fn(),
    counts: vi.fn(),
  },
}))

// eslint-disable-next-line import/first
import { sendSystemNotification } from '@/utils/systemNotify'
// eslint-disable-next-line import/first
import { peerInboxApi } from '@/api/peerInbox'
// eslint-disable-next-line import/first
import {
  pickPeerFocusTarget,
  usePeerInboxStore,
  __resetPeerInboxAlertForTest,
  __resetPeerHoldNotifyForTest,
} from '@/stores/peerInboxStore'

const A = 'sess-aaaa1111'
const B = 'sess-bbbb2222'

const notifyMock = vi.mocked(sendSystemNotification)
const listMock = vi.mocked(peerInboxApi.list)
const approveMock = vi.mocked(peerInboxApi.approve)
const countsMock = vi.mocked(peerInboxApi.counts)

function item(id: string, target = A, patch: Partial<PeerHeldItemDto> = {}): PeerHeldItemDto {
  return {
    messageId: id,
    targetSessionId: target,
    fromSessionId: 'sess-src',
    fromName: '来源',
    summary: '请确认',
    reason: 'EXPLICIT_HOLD',
    heldAt: '2026-11-19T10:00:00Z',
    body: '正文',
    status: 'pending',
    ...patch,
  }
}

function resetStore(): void {
  usePeerInboxStore.setState({
    sessionId: null,
    items: [],
    loading: false,
    error: null,
    acting: null,
    actingAll: false,
    notice: null,
    rowErrors: {},
    pendingCount: 0,
    unreadCount: 0,
    lastSeq: null,
    countsBySession: {},
    totalPending: 0,
    inboxItems: [],
    inboxLoading: false,
    inboxError: null,
    inboxNotice: null,
    inboxRowErrors: {},
    inboxActing: null,
    alert: null,
    jumpRequest: null,
    open: false,
  })
}

beforeEach(() => {
  resetStore()
  __resetPeerInboxAlertForTest()
  __resetPeerHoldNotifyForTest()
  notifyMock.mockReset()
  notifyMock.mockResolvedValue(true)
  listMock.mockReset()
  approveMock.mockReset()
  countsMock.mockReset()
  countsMock.mockResolvedValue({ seq: 1, counts: {} })
})

afterEach(() => {
  resetStore()
  __resetPeerInboxAlertForTest()
})

async function loadInbox(): Promise<void> {
  await usePeerInboxStore.getState().loadInbox()
}

describe('loadInbox · 跨会话合并', () => {
  it('按 counts 会话并行拉取；只留 pending；FIFO 最早在前', async () => {
    usePeerInboxStore.setState({
      sessionId: A,
      pendingCount: 2,
      countsBySession: { [A]: 2, [B]: 1 },
      totalPending: 3,
      open: true,
    })
    listMock.mockImplementation(async (sid: string) => (
      sid === A
        ? [
            item('a2', A, { heldAt: '2026-11-19T11:02:00Z' }),
            item('a1', A, { heldAt: '2026-11-19T11:05:00Z' }),
            item('r1', A, { status: 'rejected' }),
          ]
        : [item('b1', B, { heldAt: '2026-11-19T10:40:00Z' })]
    ))

    await loadInbox()

    expect(listMock.mock.calls.map((c) => c[0]).sort()).toEqual([A, B].sort())
    const s = usePeerInboxStore.getState()
    expect(s.inboxItems.map((i) => i.messageId)).toEqual(['b1', 'a2', 'a1'])
    expect(s.inboxItems.map((i) => i.targetSessionId)).toEqual([B, A, A])
    expect(s.inboxLoading).toBe(false)
    expect(s.inboxError).toBeNull()
  })

  it('部分会话失败：保留成功会话的行并提示，不把失败当空', async () => {
    usePeerInboxStore.setState({
      sessionId: A,
      countsBySession: { [A]: 1, [B]: 1 },
      totalPending: 2,
      open: true,
    })
    listMock.mockImplementation(async (sid: string) => {
      if (sid === A) throw new Error('boom')
      return [item('b1', B)]
    })

    await loadInbox()

    const s = usePeerInboxStore.getState()
    expect(s.inboxItems.map((i) => i.messageId)).toEqual(['b1'])
    expect(s.inboxNotice).toContain('部分会话加载失败')
    expect(s.inboxError).toBeNull()
  })
})

describe('acceptInbox / rejectInbox · 跨会话就地处理', () => {
  it('非当前会话：按该行 target 调 approve；移除行、计数归零、自动收起', async () => {
    usePeerInboxStore.setState({
      sessionId: A,
      items: [],
      pendingCount: 0,
      countsBySession: { [B]: 1 },
      totalPending: 1,
      inboxItems: [item('b1', B)],
      open: true,
    })
    approveMock.mockResolvedValue({ kind: 'ok', ok: true, code: 'ACCEPTED', message: '' })

    await usePeerInboxStore.getState().acceptInbox('b1')

    expect(approveMock).toHaveBeenCalledWith(B, 'b1')
    const s = usePeerInboxStore.getState()
    expect(s.inboxItems).toEqual([])
    expect(s.countsBySession[B]).toBeUndefined()
    expect(s.totalPending).toBe(0)
    expect(s.open).toBe(false)
    expect(s.items).toEqual([]) // 当前会话不受影响
  })

  it('当前会话：同时从内联卡 items / pendingCount / unreadCount 移除', async () => {
    usePeerInboxStore.setState({
      sessionId: A,
      items: [item('a1', A)],
      pendingCount: 1,
      unreadCount: 1,
      countsBySession: { [A]: 1 },
      totalPending: 1,
      inboxItems: [item('a1', A)],
      open: true,
    })
    approveMock.mockResolvedValue({ kind: 'ok', ok: true, code: 'ACCEPTED', message: '' })

    await usePeerInboxStore.getState().acceptInbox('a1')

    const s = usePeerInboxStore.getState()
    expect(s.items).toEqual([])
    expect(s.pendingCount).toBe(0)
    expect(s.unreadCount).toBe(0)
    expect(s.inboxItems).toEqual([])
    expect(s.open).toBe(false)
  })

  it('业务未达成：行保留，原因挂在该行，不假成功', async () => {
    usePeerInboxStore.setState({
      sessionId: A,
      countsBySession: { [B]: 1 },
      totalPending: 1,
      inboxItems: [item('b1', B)],
      open: true,
    })
    approveMock.mockResolvedValue({ kind: 'business', ok: false, code: 'HELD', message: '' })

    await usePeerInboxStore.getState().acceptInbox('b1')

    const s = usePeerInboxStore.getState()
    expect(s.inboxItems).toHaveLength(1)
    expect(s.inboxRowErrors.b1).toContain('仍待确认')
    expect(s.inboxActing).toBeNull()
    expect(s.open).toBe(true)
  })
})

describe('pickPeerFocusTarget · 跳转聚焦目标', () => {
  it('优先指定 messageId；否则取首条 pending；终态不参与', () => {
    const items = [item('p1'), item('p2'), item('p3', A, { status: 'rejected' })]
    expect(pickPeerFocusTarget(items, 'p2')?.messageId).toBe('p2')
    expect(pickPeerFocusTarget(items)?.messageId).toBe('p1')
    expect(pickPeerFocusTarget([item('r', A, { status: 'rejected' })])).toBeNull()
  })
})

function flush(): Promise<void> {
  return Promise.resolve().then(() => Promise.resolve())
}

describe('非当前会话的新 hold · 系统通知 + 跳转请求', () => {
  it('目标是别的会话也通知；extra 带 targetSessionId/messageId；点击发 jumpRequest', async () => {
    usePeerInboxStore.setState({ sessionId: A, countsBySession: {}, totalPending: 0 })
    usePeerInboxStore.getState().applyAggregateEvent({
      type: 'peer.inbox.hold',
      seq: 1,
      targetSessionId: B,
      pendingCount: 1,
      item: {
        messageId: 'p-b',
        targetSessionId: B,
        sourceSessionId: 'sess-src',
        sourceTitle: '来源 B',
        createdAt: '2026-11-19T10:00:00Z',
      },
    } as never)
    await flush()

    const s = usePeerInboxStore.getState()
    expect(s.countsBySession[B]).toBe(1)
    expect(s.open).toBe(false)
    expect(notifyMock).toHaveBeenCalledTimes(1)
    const payload = notifyMock.mock.calls[0]![0] as { extra?: Record<string, unknown>; onClick?: () => void }
    expect(payload.extra).toMatchObject({ sessionId: B, kind: 'peer-hold', messageId: 'p-b' })

    payload.onClick?.()
    expect(usePeerInboxStore.getState().jumpRequest).toMatchObject({ sessionId: B, messageId: 'p-b' })
  })

  it('同一 messageId 重复事件只通知一次', async () => {
    usePeerInboxStore.setState({ sessionId: A })
    const evt = {
      type: 'peer.inbox.hold',
      seq: 1,
      targetSessionId: B,
      pendingCount: 1,
      item: { messageId: 'p-b', targetSessionId: B, createdAt: '2026-11-19T10:00:00Z' },
    }
    usePeerInboxStore.getState().applyAggregateEvent(evt as never)
    usePeerInboxStore.getState().applyAggregateEvent({ ...evt, seq: 2 } as never)
    await flush()
    expect(notifyMock).toHaveBeenCalledTimes(1)
  })
})

describe('启动通知 · 只看未读', () => {
  it('pending 但全部已读：不发系统通知', async () => {
    listMock.mockResolvedValueOnce([item('p1', A, { seenAt: '2026-11-19T10:05:00Z' })])
    await usePeerInboxStore.getState().load(A, { startup: true })
    await flush()

    const s = usePeerInboxStore.getState()
    expect(s.pendingCount).toBe(1)
    expect(s.unreadCount).toBe(0)
    expect(notifyMock).not.toHaveBeenCalled()
  })
})
