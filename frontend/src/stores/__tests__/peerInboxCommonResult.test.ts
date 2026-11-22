// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { PeerHeldItemDto } from '@/api/types'
import { ApiError } from '@/api/rest'
import { normalizeActionOutcome } from '@/api/peerInboxOutcome'

/**
 * [peer-inbox-commonresult] store 按统一返回体判定：**只有 success:true && code:0 才移除行**。
 *
 * <h2>WHY（规则九）</h2>
 * <p>业务失败仍是 HTTP 200（`success:false` + 数字码），真实原因在 `data.status`。
 * 若前端把「200 / code 非空」当成功，用户会看到待收件行消失、目标会话却永远收不到消息。
 * 本文件把六组数字码（423 / 404×2 / 403×2 / 500 / 900）逐个钉住：行保留 + 行内中文原因；
 * 并钉住批量拒绝只有成功才清空、通信失败保留、旧字符串码仍可读、全程不切会话不拉历史。
 */

vi.mock('@/utils/systemNotify', () => ({ sendSystemNotification: vi.fn(async () => true) }))

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
import { usePeerInboxStore } from '@/stores/peerInboxStore'
// eslint-disable-next-line import/first
import { useChatStore } from '@/stores/chatStore'

const A = 'sess-aaaa1111'

function item(id: string): PeerHeldItemDto {
  return {
    messageId: id,
    targetSessionId: A,
    fromSessionId: 'sess-cccc3333',
    fromName: '来源会话',
    summary: '请复核方案',
    reason: 'EXPLICIT_HOLD',
    heldAt: '2026-11-19T10:00:00Z',
    body: '正文内容',
  }
}

/** 新契约的业务失败载荷（HTTP 200）。 */
function failure(code: number, status: string | undefined, msg = '服务端原因') {
  return { code, msg, success: false, data: { status } }
}

/** 新契约的成功载荷。 */
function success(data: Record<string, unknown>) {
  return { code: 0, msg: '', success: true, data }
}

const listMock = vi.mocked(peerInboxApi.list)
const approveMock = vi.mocked(peerInboxApi.approve)
const rejectMock = vi.mocked(peerInboxApi.reject)
const rejectAllMock = vi.mocked(peerInboxApi.rejectAll)

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

beforeEach(() => {
  resetStore()
  listMock.mockReset()
  approveMock.mockReset()
  rejectMock.mockReset()
  rejectAllMock.mockReset()
})

afterEach(() => {
  resetStore()
})

async function loadOne(...ids: string[]): Promise<void> {
  listMock.mockResolvedValueOnce(ids.map((id) => item(id)))
  await usePeerInboxStore.getState().load(A)
}

describe('单条接受 · 数字码失败一律保留行并显示中文原因', () => {
  const cases: Array<[number, string | undefined, string]> = [
    [423, 'HELD', '仍待确认'],
    [404, 'NO_LIVE_SESSION', '目标会话不在线'],
    [404, 'NOT_FOUND', '消息不存在'],
    [403, 'REFUSED', '策略已拒绝'],
    [403, undefined, '无权限'],
    [500, 'NOT_DELIVERED', '未送达'],
    [900, 'APPROVAL_REJECTED', '已被处理或归属不符'],
  ]

  it.each(cases)('code=%i status=%s：行保留 + 文案「%s」', async (code, status, hint) => {
    await loadOne('p1')
    approveMock.mockResolvedValueOnce(normalizeActionOutcome(failure(code, status)))

    await usePeerInboxStore.getState().accept('p1')

    const s = usePeerInboxStore.getState()
    expect(s.items.map((i) => i.messageId)).toEqual(['p1'])
    expect(s.pendingCount).toBe(1)
    expect(s.rowErrors.p1).toContain(hint)
    expect(s.notice).toContain(hint)
    expect(s.acting).toBeNull()
  })

  it('success:true + code:0（data.status=ACCEPTED）才移除行', async () => {
    await loadOne('p1', 'p2')
    const before = useChatStore.getState().messages
    approveMock.mockResolvedValueOnce(normalizeActionOutcome(success({ status: 'ACCEPTED' })))

    await usePeerInboxStore.getState().accept('p1')

    const s = usePeerInboxStore.getState()
    expect(s.items.map((i) => i.messageId)).toEqual(['p2'])
    expect(s.pendingCount).toBe(1)
    expect(s.notice).toContain('已接受')
    expect(s.rowErrors).toEqual({})
    expect(useChatStore.getState().messages).toBe(before)
  })

  it('success:true 但数字码非 0（自相矛盾载荷）⇒ 仍按未达成，不移除行', async () => {
    await loadOne('p1')
    approveMock.mockResolvedValueOnce(
      normalizeActionOutcome({ code: 423, msg: '', success: true, data: { status: 'HELD' } }),
    )
    await usePeerInboxStore.getState().accept('p1')
    expect(usePeerInboxStore.getState().items).toHaveLength(1)
    expect(usePeerInboxStore.getState().rowErrors.p1).toContain('仍待确认')
  })

  it('灰度兼容：旧扁平体 {code:"ACCEPTED"} 仍移除、{status:"HELD"} 仍保留', async () => {
    await loadOne('p1')
    approveMock.mockResolvedValueOnce(normalizeActionOutcome({ code: 'ACCEPTED' } as never))
    await usePeerInboxStore.getState().accept('p1')
    expect(usePeerInboxStore.getState().items).toEqual([])

    await loadOne('p2')
    approveMock.mockResolvedValueOnce(normalizeActionOutcome({ status: 'HELD' } as never))
    await usePeerInboxStore.getState().accept('p2')
    expect(usePeerInboxStore.getState().items).toHaveLength(1)
    expect(usePeerInboxStore.getState().rowErrors.p2).toContain('仍待确认')
  })

  it('通信失败（HTTP 500）⇒ 行保留、提示「请求失败，请重试」', async () => {
    await loadOne('p1')
    approveMock.mockRejectedValueOnce(new ApiError('boom', { status: 500, detail: 'server error' }))

    await usePeerInboxStore.getState().accept('p1')

    const s = usePeerInboxStore.getState()
    expect(s.items).toHaveLength(1)
    expect(s.pendingCount).toBe(1)
    expect(s.notice).toContain('请求失败，请重试')
    expect(s.rowErrors.p1).toContain('请求失败，请重试')
    expect(s.acting).toBeNull()
  })
})

describe('单条拒绝', () => {
  it('success:true + data.status=REJECTED ⇒ 移除行', async () => {
    await loadOne('p1')
    rejectMock.mockResolvedValueOnce(normalizeActionOutcome(success({ status: 'REJECTED' })))
    await usePeerInboxStore.getState().reject('p1')
    expect(usePeerInboxStore.getState().items).toEqual([])
    expect(usePeerInboxStore.getState().notice).toContain('已拒绝')
  })

  it('业务失败（404 + NOT_FOUND）⇒ 行保留', async () => {
    await loadOne('p1')
    rejectMock.mockResolvedValueOnce(normalizeActionOutcome(failure(404, 'NOT_FOUND')))
    await usePeerInboxStore.getState().reject('p1')
    const s = usePeerInboxStore.getState()
    expect(s.items).toHaveLength(1)
    expect(s.rowErrors.p1).toContain('消息不存在')
  })
})

describe('批量拒绝 · 仅 success:true 才按 rejected 清空', () => {
  it('成功（data={status:REJECTED, rejected:2}）⇒ 移除 2 行', async () => {
    await loadOne('p1', 'p2', 'p3')
    rejectAllMock.mockResolvedValueOnce(normalizeActionOutcome(success({ status: 'REJECTED', rejected: 2 }), { inferRejectAll: true }))

    await usePeerInboxStore.getState().rejectAll()

    const s = usePeerInboxStore.getState()
    expect(s.items.map((i) => i.messageId)).toEqual(['p3'])
    expect(s.pendingCount).toBe(1)
    expect(s.notice).toContain('2')
    expect(s.actingAll).toBe(false)
  })

  it('业务失败（code=500）⇒ 列表与计数原样保留', async () => {
    await loadOne('p1', 'p2')
    rejectAllMock.mockResolvedValueOnce(normalizeActionOutcome(failure(500, 'NOT_DELIVERED')))

    await usePeerInboxStore.getState().rejectAll()

    const s = usePeerInboxStore.getState()
    expect(s.items).toHaveLength(2)
    expect(s.pendingCount).toBe(2)
    expect(s.notice).toContain('全部拒绝失败')
    expect(s.notice).toContain('未送达')
    expect(s.actingAll).toBe(false)
  })

  it('通信失败（断网）⇒ 列表原样保留、可重试', async () => {
    await loadOne('p1', 'p2')
    rejectAllMock.mockRejectedValueOnce(new ApiError('Network error', { status: 0 }))

    await usePeerInboxStore.getState().rejectAll()

    const s = usePeerInboxStore.getState()
    expect(s.items).toHaveLength(2)
    expect(s.notice).toContain('请求失败，请重试')
    expect(s.actingAll).toBe(false)
  })
})

describe('全程不切会话、不拉历史、不碰 chatStore', () => {
  it('失败保留与批量结算都不改 chatStore、不 pushState、不再拉列表', async () => {
    const chatSetState = vi.spyOn(useChatStore, 'setState')
    const pushState = vi.spyOn(window.history, 'pushState')

    await loadOne('p1', 'p2')
    approveMock.mockResolvedValueOnce(normalizeActionOutcome(failure(423, 'HELD')))
    await usePeerInboxStore.getState().accept('p1')
    rejectAllMock.mockResolvedValueOnce(normalizeActionOutcome(success({ status: 'REJECTED', rejected: 1 }), { inferRejectAll: true }))
    await usePeerInboxStore.getState().rejectAll()

    expect(usePeerInboxStore.getState().sessionId).toBe(A)
    expect(listMock.mock.calls.every((c) => c[0] === A)).toBe(true)
    expect(listMock).toHaveBeenCalledTimes(1)
    expect(chatSetState).not.toHaveBeenCalled()
    expect(pushState).not.toHaveBeenCalled()

    chatSetState.mockRestore()
    pushState.mockRestore()
  })
})
