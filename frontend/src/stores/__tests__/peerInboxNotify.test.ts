// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { PeerHeldItemDto } from '@/api/types'

/**
 * 新 hold 到达的两条 GUI 提醒行为（本仓适配，CC 是 CLI 没有面板）：
 * ① 提示音（Web Audio 合成，零外部资源）；② 仅「未读 0 → >0」边沿自动打开面板一次。
 *
 * <h2>WHY（规则九）</h2>
 * <p>hold 是「不入队、不唤醒」的负向语义。若没有可见提醒，跨会话消息会静默卡住；但若每条都弹
 * 或每次增加都弹，会打断用户当前操作。因此只在一轮的第一条到达时自动展开，且绝不因此切会话 /
 * 拉历史 —— 面板数据就是轮询已经拿到的那一份。
 */

vi.mock('@/api/peerInbox', () => ({
  peerInboxApi: {
    list: vi.fn(),
    approve: vi.fn(),
    reject: vi.fn(),
  },
}))

// eslint-disable-next-line import/first
import { peerInboxApi } from '@/api/peerInbox'
// eslint-disable-next-line import/first
import {
  __resetPeerHoldNotifyForTest,
  playPeerHoldChime,
  usePeerInboxStore,
} from '@/stores/peerInboxStore'
// eslint-disable-next-line import/first
import { useChatStore } from '@/stores/chatStore'

const A = 'sess-aaaa1111'

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

const listMock = vi.mocked(peerInboxApi.list)
const approveMock = vi.mocked(peerInboxApi.approve)

/** 可观测的 AudioContext 替身：只记录振荡器启动次数（一次双音提示 = 2 次）。 */
class MockAudioContext {
  static instances = 0
  static oscStarts = 0
  state: AudioContextState = 'running'
  currentTime = 0
  destination = {} as AudioDestinationNode
  constructor() {
    MockAudioContext.instances++
  }
  createGain() {
    return {
      gain: {
        setValueAtTime: vi.fn(),
        exponentialRampToValueAtTime: vi.fn(),
      },
      connect: vi.fn(),
    }
  }
  createOscillator() {
    return {
      type: 'sine',
      frequency: { setValueAtTime: vi.fn() },
      connect: vi.fn(),
      start: vi.fn(() => { MockAudioContext.oscStarts++ }),
      stop: vi.fn(),
    }
  }
  resume() {
    return Promise.resolve()
  }
  close() {
    return Promise.resolve()
  }
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
  })
}

/** 模拟首个用户手势（浏览器自动播放策略要求交互后才能创建 AudioContext）。 */
function userGesture(): void {
  window.dispatchEvent(new Event('pointerdown'))
}

beforeEach(() => {
  resetStore()
  __resetPeerHoldNotifyForTest()
  MockAudioContext.instances = 0
  MockAudioContext.oscStarts = 0
  ;(window as unknown as { AudioContext?: unknown }).AudioContext = MockAudioContext
  listMock.mockReset()
  approveMock.mockReset()
})

afterEach(() => {
  resetStore()
  __resetPeerHoldNotifyForTest()
  delete (window as unknown as { AudioContext?: unknown }).AudioContext
})

describe('新 hold 提醒 · 不自动展开', () => {
  it('同一会话未读 0 → 1：不自动展开列表，提示音只响一次', async () => {
    userGesture()
    listMock.mockResolvedValueOnce([])
    await usePeerInboxStore.getState().load(A)
    expect(usePeerInboxStore.getState().open).toBe(false)

    listMock.mockResolvedValueOnce([item('peer-1')])
    await usePeerInboxStore.getState().load(A)

    const s = usePeerInboxStore.getState()
    expect(s.open).toBe(false)
    expect(s.items.map((i) => i.messageId)).toEqual(['peer-1'])
    // 一次双音提示 = 2 个振荡器；同一批只响一次，不叠加。
    expect(MockAudioContext.instances).toBe(1)
    expect(MockAudioContext.oscStarts).toBe(2)
  })

  it('任何到达都不自动展开（1 → 2）', async () => {
    userGesture()
    listMock.mockResolvedValueOnce([])
    await usePeerInboxStore.getState().load(A)
    listMock.mockResolvedValueOnce([item('peer-1')])
    await usePeerInboxStore.getState().load(A)
    expect(usePeerInboxStore.getState().open).toBe(false)

    listMock.mockResolvedValueOnce([item('peer-1'), item('peer-2')])
    await usePeerInboxStore.getState().load(A)

    const s = usePeerInboxStore.getState()
    expect(s.items).toHaveLength(2)
    expect(s.open).toBe(false)
  })

  it('未读归零后再来新消息仍不自动展开', async () => {
    userGesture()
    listMock.mockResolvedValueOnce([])
    await usePeerInboxStore.getState().load(A)
    listMock.mockResolvedValueOnce([item('peer-1')])
    await usePeerInboxStore.getState().load(A)

    approveMock.mockResolvedValueOnce({ kind: 'ok', ok: true, code: 'ACCEPTED', message: '' })
    await usePeerInboxStore.getState().accept('peer-1')
    expect(usePeerInboxStore.getState().items).toHaveLength(0)

    listMock.mockResolvedValueOnce([item('peer-2')])
    await usePeerInboxStore.getState().load(A)
    expect(usePeerInboxStore.getState().open).toBe(false)
  })

  it('到达不触发会话切换、路由跳转或历史消息请求', async () => {
    userGesture()
    const chatSetState = vi.spyOn(useChatStore, 'setState')
    const pushState = vi.spyOn(window.history, 'pushState')

    listMock.mockResolvedValueOnce([])
    await usePeerInboxStore.getState().load(A)
    listMock.mockResolvedValueOnce([item('peer-1')])
    await usePeerInboxStore.getState().load(A)

    expect(usePeerInboxStore.getState().open).toBe(false)
    // 自动打开本身不额外拉列表：恰好是两次轮询（基线 + 新到达）。
    expect(listMock).toHaveBeenCalledTimes(2)
    expect(listMock.mock.calls.every((c) => c[0] === A)).toBe(true)
    expect(chatSetState).not.toHaveBeenCalled()
    expect(pushState).not.toHaveBeenCalled()

    chatSetState.mockRestore()
    pushState.mockRestore()
  })

  it('会话首屏已有积压不算新到达：不响也不自动弹', async () => {
    userGesture()
    listMock.mockResolvedValueOnce([item('peer-1'), item('peer-2')])
    await usePeerInboxStore.getState().load(A)

    const s = usePeerInboxStore.getState()
    expect(s.items).toHaveLength(2)
    expect(s.open).toBe(false)
    expect(MockAudioContext.oscStarts).toBe(0)
  })
})

describe('新 hold 提醒 · 提示音与列表合并', () => {
  it('同一批到达多条只响一次', async () => {
    userGesture()
    listMock.mockResolvedValueOnce([])
    await usePeerInboxStore.getState().load(A)

    listMock.mockResolvedValueOnce([item('peer-1'), item('peer-2'), item('peer-3')])
    await usePeerInboxStore.getState().load(A)

    expect(usePeerInboxStore.getState().items).toHaveLength(3)
    expect(MockAudioContext.oscStarts).toBe(2)
  })

  it('新到达只更新列表与角标，不重复响、不重排已加载项', async () => {
    userGesture()
    listMock.mockResolvedValueOnce([])
    await usePeerInboxStore.getState().load(A)
    listMock.mockResolvedValueOnce([item('peer-1'), item('peer-2')])
    await usePeerInboxStore.getState().load(A)
    expect(MockAudioContext.oscStarts).toBe(2)

    // 服务端把 peer-2 排到前面并改摘要；前端必须保持已加载顺序（peer-1, peer-2），新项追加到末尾。
    listMock.mockResolvedValueOnce([
      item('peer-2', { summary: '更新后的摘要' }),
      item('peer-1'),
      item('peer-3'),
    ])
    await usePeerInboxStore.getState().load(A)

    const s = usePeerInboxStore.getState()
    expect(s.open).toBe(false)
    expect(s.items.map((i) => i.messageId)).toEqual(['peer-1', 'peer-2', 'peer-3'])
    expect(s.items[1].summary).toBe('更新后的摘要')
    // 第二次到达是新 hold（peer-3）⇒ 再响一次；同一批内仍只响一次。
    expect(MockAudioContext.oscStarts).toBe(4)
  })

  it('AudioContext 不可用：静默降级且列表仍更新', async () => {
    userGesture()
    delete (window as unknown as { AudioContext?: unknown }).AudioContext
    ;(window as unknown as { webkitAudioContext?: unknown }).webkitAudioContext = undefined

    listMock.mockResolvedValueOnce([])
    await usePeerInboxStore.getState().load(A)
    listMock.mockResolvedValueOnce([item('peer-1')])
    await expect(usePeerInboxStore.getState().load(A)).resolves.toBeUndefined()

    const s = usePeerInboxStore.getState()
    expect(s.items.map((i) => i.messageId)).toEqual(['peer-1'])
    expect(s.open).toBe(false)
  })

  it('未收到用户手势前不创建 AudioContext（策略拦截下静默）', () => {
    playPeerHoldChime()
    expect(MockAudioContext.instances).toBe(0)
  })
})
