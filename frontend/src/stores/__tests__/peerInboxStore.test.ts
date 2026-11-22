// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { PeerHeldItemDto } from '@/api/types'
import { ApiError } from '@/api/rest'

/**
 * 待收件 store：列表归属、接受/拒绝的一次性语义、HTTP 200 ≠ 已接受。
 *
 * <h2>WHY（规则九）</h2>
 * <p>后端 approve 在「目标已退出 / 策略改为拒绝 / 投递未接线」时仍返回 200，只是 status
 * 不是 ACCEPTED。前端若把 200 当成功，用户会看到行消失了、但目标会话永远收不到消息 ——
 * 这正是「接受一次」要避免的假成功。只有 ACCEPTED 才移除行；其余必须保留并给出原因。
 * 同时：接受**不得**本地乐观把消息塞进会话正文（等后端 message.insert 整行）。
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
import { usePeerInboxStore, peerApproveFailureText, peerHeldReasonText } from '@/stores/peerInboxStore'
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

const listMock = vi.mocked(peerInboxApi.list)
const approveMock = vi.mocked(peerInboxApi.approve)
const rejectMock = vi.mocked(peerInboxApi.reject)

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
  listMock.mockReset()
  approveMock.mockReset()
  rejectMock.mockReset()
})

afterEach(() => {
  resetStore()
})

describe('peerInboxStore · 列表归属与刷新', () => {
  it('load 填充 items，未读计数 = 条数', async () => {
    listMock.mockResolvedValueOnce([item(), item({ messageId: 'peer-2' })])
    await usePeerInboxStore.getState().load(A)

    const s = usePeerInboxStore.getState()
    expect(s.sessionId).toBe(A)
    expect(s.items).toHaveLength(2)
    expect(s.loading).toBe(false)
    expect(s.error).toBeNull()
  })

  it('切换会话后，旧会话的在途响应不得覆盖新列表（错来源不显示他人项）', async () => {
    let resolveA: (v: PeerHeldItemDto[]) => void = () => {}
    listMock.mockImplementation((sid: string) => {
      if (sid === A) return new Promise<PeerHeldItemDto[]>((r) => { resolveA = r })
      return Promise.resolve([item({ messageId: 'b-1', targetSessionId: B })])
    })

    const pendingA = usePeerInboxStore.getState().load(A)
    await usePeerInboxStore.getState().load(B)
    resolveA([item({ messageId: 'a-1' })])
    await pendingA

    const s = usePeerInboxStore.getState()
    expect(s.sessionId).toBe(B)
    expect(s.items.map((i) => i.messageId)).toEqual(['b-1'])
  })

  it('列表失败显示错误态，不伪装空列表成功', async () => {
    listMock.mockRejectedValueOnce(new ApiError('boom', { status: 403, detail: 'forbidden' }))
    await usePeerInboxStore.getState().load(A)
    const s = usePeerInboxStore.getState()
    expect(s.error).toContain('forbidden')
    expect(s.items).toEqual([])
  })
})

describe('peerInboxStore · 接受一次（HTTP 200 ≠ 已接受）', () => {
  it('ACCEPTED 才移除行；且不向 chatStore 会话正文插入任何消息', async () => {
    listMock.mockResolvedValueOnce([item()])
    await usePeerInboxStore.getState().load(A)
    const before = useChatStore.getState().messages

    approveMock.mockResolvedValueOnce({ kind: 'ok', ok: true, code: 'ACCEPTED', message: '' })
    await usePeerInboxStore.getState().accept('peer-1')

    const s = usePeerInboxStore.getState()
    expect(s.items).toEqual([])
    expect(s.notice).toContain('已接受')
    expect(approveMock).toHaveBeenCalledWith(A, 'peer-1')
    // ⛔ 不本地乐观插入：会话消息桶引用都没被碰过
    expect(useChatStore.getState().messages).toBe(before)
  })

  it('NO_LIVE_SESSION / REFUSED / HELD / 投递失败 → 行保留并给出原因', async () => {
    for (const status of ['NO_LIVE_SESSION', 'REFUSED', 'HELD', 'NOT_WIRED', 'NOT_DELIVERED']) {
      resetStore()
      listMock.mockResolvedValueOnce([item()])
      await usePeerInboxStore.getState().load(A)
      approveMock.mockResolvedValueOnce({ kind: 'business', ok: false, code: status, message: 'x' })
      await usePeerInboxStore.getState().accept('peer-1')

      const s = usePeerInboxStore.getState()
      expect(s.items, `${status} 不得移除行`).toHaveLength(1)
      expect(s.notice).toBe(peerApproveFailureText(status))
      expect(s.notice).toContain('保留')
    }
  })

  it('请求异常（403 等）保留行并提示失败', async () => {
    listMock.mockResolvedValueOnce([item()])
    await usePeerInboxStore.getState().load(A)
    approveMock.mockRejectedValueOnce(new ApiError('denied', { status: 403, detail: 'denied' }))
    await usePeerInboxStore.getState().accept('peer-1')

    const s = usePeerInboxStore.getState()
    expect(s.items).toHaveLength(1)
    expect(s.notice).toContain('接受失败')
  })

  it('动作进行中重复点击不重复提交', async () => {
    listMock.mockResolvedValueOnce([item()])
    await usePeerInboxStore.getState().load(A)
    let release: (v: { kind: 'ok'; ok: true; code: string; message: string }) => void = () => {}
    approveMock.mockImplementationOnce(() => new Promise((r) => { release = r }))

    const first = usePeerInboxStore.getState().accept('peer-1')
    await usePeerInboxStore.getState().accept('peer-1')
    release({ kind: 'ok', ok: true, code: 'ACCEPTED', message: '' })
    await first

    expect(approveMock).toHaveBeenCalledTimes(1)
  })
})

describe('peerInboxStore · 拒绝', () => {
  it('成功后移除行并提示已拒绝', async () => {
    listMock.mockResolvedValueOnce([item()])
    await usePeerInboxStore.getState().load(A)
    rejectMock.mockResolvedValueOnce({ kind: 'ok', ok: true, code: 'REJECTED', message: '' })
    await usePeerInboxStore.getState().reject('peer-1')

    const s = usePeerInboxStore.getState()
    expect(s.items).toEqual([])
    expect(s.notice).toContain('已拒绝')
  })

  it('失败时保留行（不假装成功）', async () => {
    listMock.mockResolvedValueOnce([item()])
    await usePeerInboxStore.getState().load(A)
    rejectMock.mockRejectedValueOnce(new ApiError('denied', { status: 403 }))
    await usePeerInboxStore.getState().reject('peer-1')

    const s = usePeerInboxStore.getState()
    expect(s.items).toHaveLength(1)
    expect(s.notice).toContain('拒绝失败')
  })
})

describe('peerHeldReasonText · hold 原因中文映射', () => {
  it('五种已知原因 + 未知值原样带出（不吞掉）', () => {
    expect(peerHeldReasonText('EXPLICIT_HOLD')).toBe('接收方设置为需人工确认')
    expect(peerHeldReasonText('MODE_MISMATCH')).toBe('权限模式不一致')
    expect(peerHeldReasonText('MODE_UNKNOWN')).toBe('无法判定权限模式')
    expect(peerHeldReasonText('NO_MODE_ASSERTED')).toBe('发送方未声明模式')
    expect(peerHeldReasonText('INVALID_SETTING')).toBe('入站策略未配置')
    expect(peerHeldReasonText('SOMETHING_ELSE')).toContain('SOMETHING_ELSE')
  })
})
