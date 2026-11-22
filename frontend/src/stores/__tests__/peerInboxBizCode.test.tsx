// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { PeerHeldItemDto } from '@/api/types'
import { ApiError } from '@/api/rest'
import { normalizeActionOutcome } from '@/api/peerInboxOutcome'

/**
 * [peer-inbox-bizcode] 审批/拒绝按**业务码**判结果（HTTP 200 ≠ 成功）。
 *
 * <h2>WHY（规则九）</h2>
 * <p>后端审批/拒绝一律回 200：接受成功 `code:'ACCEPTED'`，未达成 `code:'HELD' /
 * 'NO_LIVE_SESSION' / 'REFUSED' / 'NOT_DELIVERED' / 'APPROVAL_REJECTED' / 'NOT_FOUND'`。
 * 若前端把 200 当成功，用户会看到行消失、目标会话却永远收不到消息（假成功）。本文件钉住：
 * **只有 ACCEPTED 才移除行**，其余业务码保留行并把中文原因挂到该行；通信失败（断网/5xx）
 * 保留列表并提示「请求失败，请重试」；批量拒绝只有 `code:'REJECTED'` 才结算。
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
import { peerApproveFailureText, usePeerInboxStore } from '@/stores/peerInboxStore'
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
const rejectMock = vi.mocked(peerInboxApi.reject)
const rejectAllMock = vi.mocked(peerInboxApi.rejectAll)

const accepted = { kind: 'ok' as const, ok: true, code: 'ACCEPTED', message: '' }
const business = (code: string, message = '') =>
  ({ kind: 'business' as const, ok: false, code, message })

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

describe('业务码判读（normalizeActionOutcome）', () => {
  it('code 优先于旧字段 status；两者都缺 ⇒ UNKNOWN 且不算成功（⛔ 不因 200 当成功）', () => {
    expect(normalizeActionOutcome({ code: 'ACCEPTED', status: 'HELD' })).toMatchObject({ ok: true, code: 'ACCEPTED' })
    // 旧后端只有 status：灰度期照常判读
    expect(normalizeActionOutcome({ messageId: 'p1', status: 'ACCEPTED' })).toMatchObject({ ok: true, code: 'ACCEPTED' })
    expect(normalizeActionOutcome({ messageId: 'p1', status: 'HELD' })).toMatchObject({ ok: false, code: 'HELD' })
    // 无业务码（例如空 body / 200 但无字段）⇒ 一律未达成
    expect(normalizeActionOutcome({} as never)).toMatchObject({ ok: false, code: 'UNKNOWN' })
    expect(normalizeActionOutcome(null)).toMatchObject({ ok: false, code: 'UNKNOWN' })
  })

  it('批量拒绝：code=REJECTED 成功；旧回执仅带 rejected:N 也认定为成功（且只在该场景）', () => {
    expect(normalizeActionOutcome({ code: 'REJECTED', rejected: 3 }, { inferRejectAll: true }))
      .toMatchObject({ ok: true, code: 'REJECTED', rejected: 3 })
    expect(normalizeActionOutcome({ rejected: 2 }, { inferRejectAll: true }))
      .toMatchObject({ ok: true, code: 'REJECTED', rejected: 2 })
    // 非批量拒绝场景不从 rejected 推断（审批不能凭一个数字当成功）
    expect(normalizeActionOutcome({ rejected: 2 } as never)).toMatchObject({ ok: false, code: 'UNKNOWN' })
  })
})

describe('单条接受 · 只有 ACCEPTED 才移除', () => {
  it('① code=ACCEPTED：移除该行、pendingCount 减 1、不往会话正文插入消息', async () => {
    await loadOne('p1', 'p2')
    const before = useChatStore.getState().messages
    approveMock.mockResolvedValueOnce(accepted)

    await usePeerInboxStore.getState().accept('p1')

    const s = usePeerInboxStore.getState()
    expect(s.items.map((i) => i.messageId)).toEqual(['p2'])
    expect(s.pendingCount).toBe(1)
    expect(s.notice).toContain('已接受')
    expect(s.rowErrors).toEqual({})
    expect(useChatStore.getState().messages).toBe(before)
  })

  it('② code=HELD：行保留、原因「仍待确认」挂在该行', async () => {
    await loadOne('p1')
    approveMock.mockResolvedValueOnce(business('HELD', 'EXPLICIT_HOLD: explicit hold'))

    await usePeerInboxStore.getState().accept('p1')

    const s = usePeerInboxStore.getState()
    expect(s.items).toHaveLength(1)
    expect(s.pendingCount).toBe(1)
    expect(s.rowErrors.p1).toContain('仍待确认')
    expect(s.notice).toBe(peerApproveFailureText('HELD'))
    // 按钮恢复可点：动作态已清（面板 disabled 只看 acting）
    expect(s.acting).toBeNull()
  })

  it('③ code=NO_LIVE_SESSION：行保留并说明目标会话不在线（服务端 message 不覆盖中文原因）', async () => {
    await loadOne('p1')
    approveMock.mockResolvedValueOnce(business('NO_LIVE_SESSION', 'target is no longer live: sess-x'))

    await usePeerInboxStore.getState().accept('p1')

    const s = usePeerInboxStore.getState()
    expect(s.items).toHaveLength(1)
    expect(s.rowErrors.p1).toContain('不在线')
    expect(s.notice).toContain('不在线')
  })

  it('④ code=APPROVAL_REJECTED / NOT_FOUND / REFUSED：一律保留行并各自给出原因', async () => {
    const cases: Array<[string, string]> = [
      ['APPROVAL_REJECTED', '已被处理或归属不符'],
      ['NOT_FOUND', '消息不存在'],
      ['REFUSED', '策略已拒绝'],
    ]
    for (const [code, hint] of cases) {
      resetStore()
      await loadOne('p1')
      approveMock.mockResolvedValueOnce(business(code))
      await usePeerInboxStore.getState().accept('p1')

      const s = usePeerInboxStore.getState()
      expect(s.items, `${code} 不得移除行`).toHaveLength(1)
      expect(s.pendingCount, `${code} 不得减少待处理数`).toBe(1)
      expect(s.rowErrors.p1, `${code} 行内原因`).toContain(hint)
    }
  })

  it('⑤ 网络错误 / 500：保留列表并提示「请求失败，请重试」', async () => {
    // a) 抛异常（4xx/5xx、断网在 api 层归一化前）
    await loadOne('p1')
    approveMock.mockRejectedValueOnce(new ApiError('boom', { status: 500, detail: 'server error' }))
    await usePeerInboxStore.getState().accept('p1')

    let s = usePeerInboxStore.getState()
    expect(s.items).toHaveLength(1)
    expect(s.notice).toContain('请求失败，请重试')
    expect(s.rowErrors.p1).toContain('请求失败，请重试')
    expect(s.acting).toBeNull()

    // b) api 层归一化的 transport 结果（不抛）
    approveMock.mockResolvedValueOnce({
      kind: 'transport', ok: false, code: 'TRANSPORT_ERROR', message: '401 Unauthorized',
    })
    await usePeerInboxStore.getState().accept('p1')

    s = usePeerInboxStore.getState()
    expect(s.items).toHaveLength(1)
    expect(s.notice).toContain('请求失败，请重试')
    expect(s.acting).toBeNull()
  })

  it('重试成功后清掉该行的失败原因', async () => {
    await loadOne('p1')
    approveMock.mockResolvedValueOnce(business('HELD'))
    await usePeerInboxStore.getState().accept('p1')
    expect(usePeerInboxStore.getState().rowErrors.p1).toBeTruthy()

    approveMock.mockResolvedValueOnce(accepted)
    await usePeerInboxStore.getState().accept('p1')
    expect(usePeerInboxStore.getState().items).toEqual([])
    expect(usePeerInboxStore.getState().rowErrors).toEqual({})
  })
})

describe('批量拒绝 · 只有 REJECTED 才结算', () => {
  it('⑥ code=REJECTED + rejected=3：清空 3 行、pendingCount 归零', async () => {
    await loadOne('p1', 'p2', 'p3')
    rejectAllMock.mockResolvedValueOnce({ kind: 'ok', ok: true, code: 'REJECTED', message: '', rejected: 3 })

    await usePeerInboxStore.getState().rejectAll()

    const s = usePeerInboxStore.getState()
    expect(s.items).toEqual([])
    expect(s.pendingCount).toBe(0)
    expect(s.notice).toContain('3')
    expect(s.actingAll).toBe(false)
  })

  it('⑦ 业务未达成 / 通信失败：列表与计数原样保留（⛔ 不假装清空）', async () => {
    // a) 业务码非 REJECTED
    await loadOne('p1', 'p2')
    rejectAllMock.mockResolvedValueOnce(business('HELD'))
    await usePeerInboxStore.getState().rejectAll()

    let s = usePeerInboxStore.getState()
    expect(s.items).toHaveLength(2)
    expect(s.pendingCount).toBe(2)
    expect(s.notice).toContain('全部拒绝失败')

    // b) 500
    rejectAllMock.mockRejectedValueOnce(new ApiError('boom', { status: 500, detail: 'server error' }))
    await usePeerInboxStore.getState().rejectAll()

    s = usePeerInboxStore.getState()
    expect(s.items).toHaveLength(2)
    expect(s.pendingCount).toBe(2)
    expect(s.notice).toContain('请求失败，请重试')
    expect(s.actingAll).toBe(false)
  })

  it('按 rejected 数结算（rejected < 列表长度时只清对应条数）', async () => {
    await loadOne('p1', 'p2', 'p3')
    rejectAllMock.mockResolvedValueOnce({ kind: 'ok', ok: true, code: 'REJECTED', message: '', rejected: 2 })

    await usePeerInboxStore.getState().rejectAll()

    const s = usePeerInboxStore.getState()
    expect(s.items).toHaveLength(1)
    expect(s.pendingCount).toBe(1)
  })
})

describe('⑧ 全程不切会话、不拉历史', () => {
  it('失败保留 / 批量结算都不改 chatStore、不 pushState、不换 list 的会话', async () => {
    const chatSetState = vi.spyOn(useChatStore, 'setState')
    const pushState = vi.spyOn(window.history, 'pushState')

    await loadOne('p1', 'p2')
    approveMock.mockResolvedValueOnce(business('HELD'))
    await usePeerInboxStore.getState().accept('p1')
    rejectAllMock.mockResolvedValueOnce({ kind: 'ok', ok: true, code: 'REJECTED', message: '', rejected: 1 })
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

