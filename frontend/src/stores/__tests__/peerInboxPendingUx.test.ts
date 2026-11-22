// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { PeerHeldItemDto, PeerInboxHoldEvent } from '@/api/types'
import { ApiError } from '@/api/rest'

/**
 * [peer-pending] 待处理口径 + 启动提示 + 实时系统通知 + 批量拒绝。
 *
 * <h2>WHY（规则九）</h2>
 * <p>**未读 ≠ 待处理**：点开面板后 seenAt 会被回填（未读归零），但消息仍然 pending。
 * 若角标/提示条用未读数，用户会看到「看过了就当处理完了」的假清零。本文件的每个用例都在
 * 钉住这条口径：pendingCount 只能因**接受/拒绝**下降。
 */

const notifyMock = vi.hoisted(() => vi.fn(async (_p: unknown) => true))

const AudioMock = vi.hoisted(() => {
  const state = { oscStarts: 0 }
  class MockAudioContext {
    state: AudioContextState = 'running'
    currentTime = 0
    destination = {} as AudioDestinationNode
    createGain() {
      return {
        gain: { setValueAtTime: () => {}, exponentialRampToValueAtTime: () => {} },
        connect: () => {},
      }
    }
    createOscillator() {
      return {
        type: 'sine',
        frequency: { setValueAtTime: () => {} },
        connect: () => {},
        start: () => { state.oscStarts++ },
        stop: () => {},
      }
    }
    resume() { return Promise.resolve() }
    close() { return Promise.resolve() }
  }
  return { state, MockAudioContext }
})

vi.mock('@/utils/systemNotify', () => ({ sendSystemNotification: notifyMock }))

vi.mock('@/api/peerInbox', () => ({
  peerInboxApi: {
    list: vi.fn(),
    approve: vi.fn(),
    reject: vi.fn(),
    rejectAll: vi.fn(),
    markSeen: vi.fn(),
  },
}))

// eslint-disable-next-line import/first
import { peerInboxApi } from '@/api/peerInbox'
// eslint-disable-next-line import/first
import {
  __resetPeerInboxAlertForTest,
  __resetPeerHoldNotifyForTest,
  peerInboxPendingCount,
  peerInboxUnreadCount,
  usePeerInboxStore,
} from '@/stores/peerInboxStore'
// eslint-disable-next-line import/first
import { useChatStore } from '@/stores/chatStore'

const A = 'sess-aaaa1111'
const B = 'sess-bbbb2222'

function item(id: string, patch: Partial<PeerHeldItemDto> = {}): PeerHeldItemDto {
  return {
    messageId: id,
    targetSessionId: A,
    fromSessionId: 'sess-cccc3333',
    fromName: '来源会话',
    summary: '请复核方案',
    reason: 'EXPLICIT_HOLD',
    heldAt: '2026-11-19T10:00:00Z',
    body: '正文内容',
    ...patch,
  }
}

/** 生产载荷形态：扁平（后端 PeerInboxHoldEvent 直接序列化，无 item 包装）。 */
function holdEvent(sessionId: string, messageId: string): PeerInboxHoldEvent {
  return {
    type: 'peer.inbox.hold',
    sessionId,
    messageId,
    targetSessionId: sessionId,
    sourceSessionId: 'sess-cccc3333',
    sourceTitle: '来源会话',
    sourceKind: 'peer',
    summary: '事件摘要',
    body: '事件正文',
    reason: 'EXPLICIT_HOLD',
    status: 'pending',
    createdAt: '2026-11-19T10:00:00Z',
  }
}

const listMock = vi.mocked(peerInboxApi.list)
const markSeenMock = vi.mocked(peerInboxApi.markSeen)
const rejectAllMock = vi.mocked(peerInboxApi.rejectAll)

const flush = async () => { await Promise.resolve(); await Promise.resolve(); await Promise.resolve() }

function resetStore(): void {
  usePeerInboxStore.setState({
    sessionId: null,
    items: [],
    loading: false,
    error: null,
    acting: null,
    actingAll: false,
    notice: null,
    pendingCount: 0,
    unreadCount: 0,
    alert: null,
    open: false,
    inboxItems: [],
    inboxLoading: false,
    inboxError: null,
    inboxNotice: null,
    inboxRowErrors: {},
    inboxActing: null,
    jumpRequest: null,
  })
}

function userGesture(): void {
  window.dispatchEvent(new Event('pointerdown'))
}

beforeEach(() => {
  resetStore()
  __resetPeerInboxAlertForTest()
  __resetPeerHoldNotifyForTest()
  AudioMock.state.oscStarts = 0
  ;(window as unknown as { AudioContext?: unknown }).AudioContext = AudioMock.MockAudioContext
  userGesture()
  notifyMock.mockClear()
  notifyMock.mockImplementation(async () => true)
  listMock.mockReset()
  markSeenMock.mockReset()
  markSeenMock.mockImplementation(async (_sid: string, ids: string[]) => ({ updated: ids.length }))
  rejectAllMock.mockReset()
})

afterEach(() => {
  resetStore()
  __resetPeerInboxAlertForTest()
  __resetPeerHoldNotifyForTest()
  delete (window as unknown as { AudioContext?: unknown }).AudioContext
})

describe('pending 口径：未读归零 ≠ 处理完', () => {
  it('内联卡可见上报后未读归零，pendingCount 不变（提示条/角标仍在）', async () => {
    listMock.mockResolvedValueOnce([item('p1'), item('p2')])
    await usePeerInboxStore.getState().load(A)
    expect(usePeerInboxStore.getState().pendingCount).toBe(2)
    expect(usePeerInboxStore.getState().unreadCount).toBe(2)

    await usePeerInboxStore.getState().markSeenForVisible()

    const s = usePeerInboxStore.getState()
    expect(s.unreadCount).toBe(0) // 已读回填
    expect(s.pendingCount).toBe(2) // 仍未处理
    expect(peerInboxPendingCount(s.items)).toBe(2)
    expect(peerInboxUnreadCount(s.items)).toBe(0)
    expect(s.items).toHaveLength(2)
  })

  it('关闭面板不清 pending、不清提示条；只有处理掉才归零', async () => {
    listMock.mockResolvedValueOnce([item('p1')])
    await usePeerInboxStore.getState().load(A)
    usePeerInboxStore.getState().openPanel()

    usePeerInboxStore.getState().closePanel()
    expect(usePeerInboxStore.getState().open).toBe(false)
    expect(usePeerInboxStore.getState().pendingCount).toBe(1)

    vi.mocked(peerInboxApi.reject).mockResolvedValueOnce({ kind: 'ok', ok: true, code: 'REJECTED', message: '' })
    await usePeerInboxStore.getState().reject('p1')
    expect(usePeerInboxStore.getState().pendingCount).toBe(0)
    expect(usePeerInboxStore.getState().items).toEqual([])
  })
})

describe('启动 / 切会话后首次加载', () => {
  it('pending>0 且有未读：不展开列表 + 系统通知一次 + 不播放提示音', async () => {
    listMock.mockResolvedValueOnce([item('p1'), item('p2')])
    await usePeerInboxStore.getState().load(A, { startup: true })
    await flush()

    const s = usePeerInboxStore.getState()
    expect(s.open).toBe(false)
    expect(s.pendingCount).toBe(2)
    expect(s.alert?.source).toBe('startup')
    expect(s.alert?.count).toBe(2)
    expect(s.alert?.notified).toBe(true)
    expect(notifyMock).toHaveBeenCalledTimes(1)
    const payload = notifyMock.mock.calls[0]![0] as {
      tag?: string; extra?: Record<string, unknown>; body?: string
    }
    expect(payload.tag).toBe(`nexusai-peer-hold:${A}`)
    expect(payload.extra).toMatchObject({ sessionId: A, kind: 'peer-hold' })
    // 用户裁定：系统通知必须显示《来源标题》
    expect(payload.body).toContain('《来源会话》')
    // 启动积压不是「新消息」：⛔ 不响提示音
    expect(AudioMock.state.oscStarts).toBe(0)
  })

  it('pending=0：不弹面板、不发通知、不响', async () => {
    listMock.mockResolvedValueOnce([])
    await usePeerInboxStore.getState().load(A, { startup: true })
    await flush()

    const s = usePeerInboxStore.getState()
    expect(s.open).toBe(false)
    expect(s.pendingCount).toBe(0)
    expect(s.alert).toBeNull()
    expect(notifyMock).not.toHaveBeenCalled()
    expect(AudioMock.state.oscStarts).toBe(0)
  })

  it('同一会话重复 startup 加载只提示一次（轮询/重连不重复弹）', async () => {
    listMock.mockResolvedValueOnce([item('p1')])
    await usePeerInboxStore.getState().load(A, { startup: true })
    await flush()
    expect(notifyMock).toHaveBeenCalledTimes(1)

    listMock.mockResolvedValueOnce([item('p1')])
    await usePeerInboxStore.getState().load(A, { startup: true })
    await flush()
    expect(notifyMock).toHaveBeenCalledTimes(1)
  })

  it('切到新会话的首次加载：按新会话再提示一次', async () => {
    listMock.mockResolvedValueOnce([])
    await usePeerInboxStore.getState().load(A, { startup: true })
    await flush()
    expect(notifyMock).not.toHaveBeenCalled()

    listMock.mockResolvedValueOnce([item('b1', { targetSessionId: B })])
    await usePeerInboxStore.getState().load(B, { startup: true })
    await flush()

    expect(usePeerInboxStore.getState().sessionId).toBe(B)
    expect(usePeerInboxStore.getState().open).toBe(false)
    expect(notifyMock).toHaveBeenCalledTimes(1)
    const payload = notifyMock.mock.calls[0]![0] as { extra?: Record<string, unknown> }
    expect(payload.extra).toMatchObject({ sessionId: B })
  })

  it('通知点击发跳转请求（不直接改 store.sessionId / 不碰 chatStore）', async () => {
    const chatSetState = vi.spyOn(useChatStore, 'setState')
    listMock.mockResolvedValueOnce([item('p1')])
    await usePeerInboxStore.getState().load(A, { startup: true })
    await flush()
    const payload = notifyMock.mock.calls[0]![0] as { onClick?: () => void }

    payload.onClick?.()
    const req = usePeerInboxStore.getState().jumpRequest
    expect(req?.sessionId).toBe(A)
    // 启动通知没有具体 messageId ⇒ App 跳会话后聚焦首条 pending 卡。
    expect(req?.messageId).toBeNull()
    expect(usePeerInboxStore.getState().open).toBe(false)
    expect(usePeerInboxStore.getState().sessionId).toBe(A)
    expect(chatSetState).not.toHaveBeenCalled()
    chatSetState.mockRestore()
  })

  it('系统通知不可用：alert.notified=false（UI 用 toast 兜底，不静默）', async () => {
    notifyMock.mockImplementation(async () => false)
    listMock.mockResolvedValueOnce([item('p1')])
    await usePeerInboxStore.getState().load(A, { startup: true })
    await flush()

    expect(usePeerInboxStore.getState().alert?.notified).toBe(false)
    expect(usePeerInboxStore.getState().pendingCount).toBe(1)
  })
})

describe('实时新 hold', () => {
  it('新事件到达：系统通知一次；重复推送同一 messageId 不再通知', async () => {
    listMock.mockResolvedValueOnce([])
    await usePeerInboxStore.getState().load(A)

    usePeerInboxStore.getState().applyRealtimeEvent(holdEvent(A, 'peer-rt-1'))
    await flush()
    expect(notifyMock).toHaveBeenCalledTimes(1)
    expect(usePeerInboxStore.getState().alert?.source).toBe('realtime')
    expect(usePeerInboxStore.getState().pendingCount).toBe(1)

    usePeerInboxStore.getState().applyRealtimeEvent(holdEvent(A, 'peer-rt-1'))
    await flush()
    expect(notifyMock).toHaveBeenCalledTimes(1)
    expect(usePeerInboxStore.getState().pendingCount).toBe(1)
  })

  it('已读项的旧快照事件不通知（不复活提醒）', async () => {
    listMock.mockResolvedValueOnce([item('p1', { seenAt: '2026-11-19T10:05:00Z' })])
    await usePeerInboxStore.getState().load(A)

    usePeerInboxStore.getState().applyRealtimeEvent(holdEvent(A, 'p1'))
    await flush()
    expect(notifyMock).not.toHaveBeenCalled()
  })
})

describe('批量拒绝', () => {
  it('成功：清空列表、pendingCount 归零（角标与提示条随之消失）', async () => {
    listMock.mockResolvedValueOnce([item('p1'), item('p2')])
    await usePeerInboxStore.getState().load(A)
    rejectAllMock.mockResolvedValueOnce({ kind: 'ok', ok: true, code: 'REJECTED', message: '', rejected: 2 })

    await usePeerInboxStore.getState().rejectAll()

    const s = usePeerInboxStore.getState()
    expect(rejectAllMock).toHaveBeenCalledWith(A)
    expect(s.items).toEqual([])
    expect(s.pendingCount).toBe(0)
    expect(s.unreadCount).toBe(0)
    expect(s.actingAll).toBe(false)
    expect(s.notice).toContain('2')
  })

  it('失败：列表与计数不变（⛔ 不假装清空），给出可重试提示', async () => {
    listMock.mockResolvedValueOnce([item('p1'), item('p2')])
    await usePeerInboxStore.getState().load(A)
    rejectAllMock.mockRejectedValueOnce(new ApiError('boom', { status: 500, detail: 'server error' }))

    await usePeerInboxStore.getState().rejectAll()

    const s = usePeerInboxStore.getState()
    expect(s.items).toHaveLength(2)
    expect(s.pendingCount).toBe(2)
    expect(s.actingAll).toBe(false)
    expect(s.notice).toContain('全部拒绝失败')
  })
})

describe('全程不切会话、不拉历史', () => {
  it('启动提示 / 实时事件 / 批量拒绝都不改 chatStore、不跳路由、不额外拉列表', async () => {
    const chatSetState = vi.spyOn(useChatStore, 'setState')
    const pushState = vi.spyOn(window.history, 'pushState')

    listMock.mockResolvedValueOnce([item('p1')])
    await usePeerInboxStore.getState().load(A, { startup: true })
    await flush()

    listMock.mockResolvedValueOnce([item('p1'), item('p2')])
    await usePeerInboxStore.getState().load(A)

    usePeerInboxStore.getState().applyRealtimeEvent(holdEvent(A, 'peer-rt-1'))
    await flush()

    rejectAllMock.mockResolvedValueOnce({ kind: 'ok', ok: true, code: 'REJECTED', message: '', rejected: 3 })
    await usePeerInboxStore.getState().rejectAll()

    expect(listMock.mock.calls.every((c) => c[0] === A)).toBe(true)
    expect(chatSetState).not.toHaveBeenCalled()
    expect(pushState).not.toHaveBeenCalled()

    chatSetState.mockRestore()
    pushState.mockRestore()
  })
})
