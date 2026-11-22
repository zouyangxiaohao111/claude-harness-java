// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { PeerHeldItemDto, PeerInboxHoldEvent } from '@/api/types'
import { ApiError } from '@/api/rest'

/**
 * [peer-inbox-realtime/seen] 实时事件增量 + 已读语义。
 *
 * <h2>WHY（规则九）</h2>
 * <p>① 事件是「多快」不是「有没有」：它只做增量合并，所以必须**幂等**（同一 messageId 重复
 * 推送不重复计数、不重复响铃），否则重连/重发会把角标刷成假数字。② 已读项（seenAt 非空）
 * 仍留在列表里（还没接受/拒绝），但**不再提醒** —— 否则重启后会把看过的项再响一遍。
 * ③ 自动打开必须沿用「未读 0→>0 边沿 + 用户关过就撤防」的既有规则，事件路径不得绕过。
 * ④ 事件到达绝不允许切会话/跳路由/拉历史（数据已在事件里，别顺手做多余的事）。
 */

const AudioMock = vi.hoisted(() => {
  const state = { oscStarts: 0, instances: 0 }
  class MockAudioContext {
    state: AudioContextState = 'running'
    currentTime = 0
    destination = {} as AudioDestinationNode
    constructor() { state.instances++ }
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

vi.mock('@/api/peerInbox', () => ({
  peerInboxApi: {
    list: vi.fn(),
    approve: vi.fn(),
    reject: vi.fn(),
    markSeen: vi.fn(),
  },
}))

// eslint-disable-next-line import/first
import { peerInboxApi } from '@/api/peerInbox'
// eslint-disable-next-line import/first
import {
  __resetPeerHoldNotifyForTest,
  peerInboxUnreadCount,
  usePeerInboxStore,
} from '@/stores/peerInboxStore'
// eslint-disable-next-line import/first
import { useChatStore } from '@/stores/chatStore'

const A = 'sess-aaaa1111'
const B = 'sess-bbbb2222'

function item(patch: Partial<PeerHeldItemDto> = {}): PeerHeldItemDto {
  return {
    messageId: 'peer-1',
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

/**
 * 生产载荷形态：**扁平**（后端 PeerInboxHoldEvent 直接序列化，无 item 包装），字段名走实施契约
 * （sourceSessionId/sourceTitle/createdAt）—— 夹具必须与线上帧同形，否则「事件全被丢弃」也能照绿。
 */
function event(patch: Partial<PeerInboxHoldEvent> = {}): PeerInboxHoldEvent {
  return {
    type: 'peer.inbox.hold',
    sessionId: A,
    messageId: 'peer-1',
    targetSessionId: A,
    sourceSessionId: 'sess-cccc3333',
    sourceTitle: '来源会话',
    sourceKind: 'peer',
    summary: '请复核方案',
    body: '正文内容',
    reason: 'EXPLICIT_HOLD',
    createdAt: '2026-11-19T10:00:00Z',
    ...patch,
  }
}

const listMock = vi.mocked(peerInboxApi.list)
const markSeenMock = vi.mocked(peerInboxApi.markSeen)

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
  })
}

beforeEach(() => {
  resetStore()
  __resetPeerHoldNotifyForTest()
  AudioMock.state.oscStarts = 0
  AudioMock.state.instances = 0
  ;(window as unknown as { AudioContext?: unknown }).AudioContext = AudioMock.MockAudioContext
  window.dispatchEvent(new Event('pointerdown'))
  listMock.mockReset()
  markSeenMock.mockReset()
})

afterEach(() => {
  resetStore()
  __resetPeerHoldNotifyForTest()
  delete (window as unknown as { AudioContext?: unknown }).AudioContext
})

describe('applyRealtimeEvent · 到达即增量合并', () => {
  it('事件到达：立即出现项、未读+1、响一次、不自动展开；不切会话/不跳路由/不拉历史', async () => {
    const chatSetState = vi.spyOn(useChatStore, 'setState')
    const pushState = vi.spyOn(window.history, 'pushState')

    listMock.mockResolvedValueOnce([])
    await usePeerInboxStore.getState().load(A)

    usePeerInboxStore.getState().applyRealtimeEvent(event())

    const s = usePeerInboxStore.getState()
    expect(s.sessionId).toBe(A)
    expect(s.items.map((i) => i.messageId)).toEqual(['peer-1'])
    // 两套字段名都归一化：sourceSessionId → fromSessionId、sourceTitle → fromSessionTitle、createdAt → heldAt
    expect(s.items[0].fromSessionId).toBe('sess-cccc3333')
    expect(s.items[0].fromSessionTitle).toBe('来源会话')
    expect(s.items[0].heldAt).toBe('2026-11-19T10:00:00Z')
    expect(peerInboxUnreadCount(s.items)).toBe(1)
    expect(s.open).toBe(false)
    expect(AudioMock.state.oscStarts).toBe(2) // 一次双音提示
    // 事件自带 DTO：不额外拉列表（只有基线那一次）、不伪造会话消息、不跳路由。
    expect(listMock).toHaveBeenCalledTimes(1)
    expect(chatSetState).not.toHaveBeenCalled()
    expect(pushState).not.toHaveBeenCalled()

    chatSetState.mockRestore()
    pushState.mockRestore()
  })

  it('旧形态（item 包装）仍兼容 —— 灰度期间两种载荷都不丢件', async () => {
    listMock.mockResolvedValueOnce([])
    await usePeerInboxStore.getState().load(A)

    usePeerInboxStore.getState().applyRealtimeEvent({
      type: 'peer.inbox.hold',
      sessionId: A,
      item: { messageId: 'peer-legacy', targetSessionId: A, summary: '旧形态', body: '正文' },
    } as PeerInboxHoldEvent)

    expect(usePeerInboxStore.getState().items.map((i) => i.messageId)).toEqual(['peer-legacy'])
  })

  it('同一事件重复推送：不重复计数、不重复响、不重排', async () => {
    listMock.mockResolvedValueOnce([])
    await usePeerInboxStore.getState().load(A)

    usePeerInboxStore.getState().applyRealtimeEvent(event())
    expect(AudioMock.state.oscStarts).toBe(2)

    // 关掉面板（用户看过），再重复推送一次同一事件
    usePeerInboxStore.getState().closePanel()
    usePeerInboxStore.getState().applyRealtimeEvent(event())
    usePeerInboxStore.getState().applyRealtimeEvent(event())

    const s = usePeerInboxStore.getState()
    expect(s.items.map((i) => i.messageId)).toEqual(['peer-1'])
    expect(peerInboxUnreadCount(s.items)).toBe(1)
    expect(AudioMock.state.oscStarts).toBe(2) // 重复推送不再响
    // 任何事件都不会自动展开列表
    expect(s.open).toBe(false)
  })

  it('已读项（seenAt 非空）不计未读、不响、不自动打开，但仍在列表里', async () => {
    listMock.mockResolvedValueOnce([])
    await usePeerInboxStore.getState().load(A)

    usePeerInboxStore.getState().applyRealtimeEvent(
      event({ seenAt: '2026-11-19T10:05:00Z' }))

    const s = usePeerInboxStore.getState()
    expect(s.items).toHaveLength(1)
    expect(peerInboxUnreadCount(s.items)).toBe(0)
    expect(s.open).toBe(false)
    expect(AudioMock.state.oscStarts).toBe(0)
  })

  it('已读后再收到同一项的旧快照（seenAt:null）不复活为未读', async () => {
    listMock.mockResolvedValueOnce([item({ seenAt: '2026-11-19T10:05:00Z' })])
    await usePeerInboxStore.getState().load(A)
    expect(peerInboxUnreadCount(usePeerInboxStore.getState().items)).toBe(0)

    usePeerInboxStore.getState().applyRealtimeEvent(event()) // 旧快照 seenAt 缺失

    const s = usePeerInboxStore.getState()
    expect(peerInboxUnreadCount(s.items)).toBe(0)
    expect(AudioMock.state.oscStarts).toBe(0)
  })

  it('归属校验：切会话后旧会话/别的会话事件一律丢弃', async () => {
    listMock.mockResolvedValueOnce([])
    await usePeerInboxStore.getState().load(A)
    listMock.mockResolvedValueOnce([])
    await usePeerInboxStore.getState().load(B)

    usePeerInboxStore.getState().applyRealtimeEvent(event()) // sessionId=A，但当前归属 B
    usePeerInboxStore.getState().applyRealtimeEvent(event({ sessionId: null, targetSessionId: null }))
    // 上一行：载荷无归属 → 无法证明是 B 的，也不认（topic 已切，严谨起见按 sessionId 缺失丢弃）
    usePeerInboxStore.getState().applyRealtimeEvent(event({ sessionId: B, targetSessionId: A }))

    const s = usePeerInboxStore.getState()
    expect(s.sessionId).toBe(B)
    expect(s.items).toEqual([])
    expect(AudioMock.state.oscStarts).toBe(0)
  })

  it('非 peer.inbox.hold 类型 / 缺 messageId 的载荷丢弃（不造幽灵行）', async () => {
    listMock.mockResolvedValueOnce([])
    await usePeerInboxStore.getState().load(A)

    usePeerInboxStore.getState().applyRealtimeEvent({ type: 'session.title', sessionId: A } as never)
    usePeerInboxStore.getState().applyRealtimeEvent(event({ messageId: '' }))

    expect(usePeerInboxStore.getState().items).toEqual([])
  })
})

describe('markSeenForVisible · 内联卡可见即已读', () => {
  it('可见时批量上报未读 id，成功回填 seenAt', async () => {
    listMock.mockResolvedValueOnce([item(), item({ messageId: 'peer-2' })])
    await usePeerInboxStore.getState().load(A)
    expect(peerInboxUnreadCount(usePeerInboxStore.getState().items)).toBe(2)

    markSeenMock.mockResolvedValueOnce({ updated: 2 })
    await usePeerInboxStore.getState().markSeenForVisible()

    expect(markSeenMock).toHaveBeenCalledTimes(1)
    expect(markSeenMock).toHaveBeenCalledWith(A, ['peer-1', 'peer-2'])
    const s = usePeerInboxStore.getState()
    expect(s.items).toHaveLength(2) // 已读仍在列表（还要处理）
    expect(peerInboxUnreadCount(s.items)).toBe(0)
    expect(s.items.every((i) => !!i.seenAt)).toBe(true)
  })

  it('上报失败：不回填 seenAt；重试成功；已读后不再重复上报', async () => {
    listMock.mockResolvedValueOnce([item()])
    await usePeerInboxStore.getState().load(A)

    markSeenMock.mockRejectedValueOnce(new ApiError('boom', { status: 500 }))
    await usePeerInboxStore.getState().markSeenForVisible()

    expect(markSeenMock).toHaveBeenCalledTimes(1)
    let s = usePeerInboxStore.getState()
    expect(s.items[0].seenAt ?? null).toBeNull() // ⛔ 失败不假绿
    expect(peerInboxUnreadCount(s.items)).toBe(1)

    // 下次可见/轮询重试：同一 id 再发一次，成功后置 seenAt
    markSeenMock.mockResolvedValueOnce({ updated: 1 })
    await usePeerInboxStore.getState().markSeenForVisible()
    expect(markSeenMock).toHaveBeenCalledTimes(2)
    expect(markSeenMock).toHaveBeenLastCalledWith(A, ['peer-1'])
    s = usePeerInboxStore.getState()
    expect(s.items[0].seenAt).toBeTruthy()
    expect(peerInboxUnreadCount(s.items)).toBe(0)

    // 已读项不再重复上报
    await usePeerInboxStore.getState().markSeenForVisible()
    expect(markSeenMock).toHaveBeenCalledTimes(2)
  })

  it('没有未读项时不发请求（已读不重复上报）', async () => {
    listMock.mockResolvedValueOnce([item({ seenAt: '2026-11-19T10:05:00Z' })])
    await usePeerInboxStore.getState().load(A)

    await usePeerInboxStore.getState().markSeenForVisible()
    expect(markSeenMock).not.toHaveBeenCalled()
    expect(peerInboxUnreadCount(usePeerInboxStore.getState().items)).toBe(0)
  })

  it('请求期间切会话：旧会话的已读结果不写到新列表', async () => {
    listMock.mockResolvedValueOnce([item()])
    await usePeerInboxStore.getState().load(A)

    let resolveSeen: (v: { updated: number }) => void = () => {}
    markSeenMock.mockImplementationOnce(() => new Promise((r) => { resolveSeen = r }))
    const pending = usePeerInboxStore.getState().markSeenForVisible()

    listMock.mockResolvedValueOnce([item({ messageId: 'b-1', targetSessionId: B })])
    await usePeerInboxStore.getState().load(B)
    resolveSeen({ updated: 1 })
    await pending

    const s = usePeerInboxStore.getState()
    expect(s.sessionId).toBe(B)
    expect(s.items.map((i) => i.messageId)).toEqual(['b-1'])
    expect(s.items[0].seenAt ?? null).toBeNull()
  })
})

describe('未读计数口径', () => {
  it('peerInboxUnreadCount 只数 seenAt 为空的项', () => {
    expect(peerInboxUnreadCount([])).toBe(0)
    expect(peerInboxUnreadCount([item(), item({ messageId: 'p2', seenAt: '2026-11-19T10:00:00Z' })])).toBe(1)
    expect(peerInboxUnreadCount([item({ seenAt: '2026-11-19T10:00:00Z' })])).toBe(0)
  })
})
