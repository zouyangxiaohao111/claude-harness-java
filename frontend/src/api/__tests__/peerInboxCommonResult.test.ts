import { beforeEach, describe, expect, it, vi } from 'vitest'
import { peerInboxApi } from '../peerInbox'
import { businessCodeOfNumeric, normalizeActionOutcome } from '../peerInboxOutcome'
import { ApiError } from '../rest'
import { API_V1_BASE } from '../base'

/**
 * [peer-inbox-commonresult] 统一返回体 `{code:number,msg,data,success}` 的判读与拆包。
 *
 * <h2>WHY（规则九）</h2>
 * <p>后端 2026-11-20 起所有 JSON 都包 CommonResult：成功 = `code:0 && success:true`；
 * 业务失败仍是 HTTP 200（`success:false` + 数字码），原因在 `data.status` —— 因为 404 与 403
 * 各承载两种业务结果（404: NO_LIVE_SESSION / NOT_FOUND；403: REFUSED / 其他=无权限）。
 * 若前端按「HTTP 200 就成功」或「code 非空就成功」判读，用户会看到行消失而目标会话收不到消息。
 */

const SID = 'sess-aaaa1111'
const MID = 'peer-1'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('normalizeActionOutcome · 数字码 + success', () => {
  const failures: Array<[number, string | undefined, string]> = [
    [423, 'HELD', 'HELD'],
    [404, 'NO_LIVE_SESSION', 'NO_LIVE_SESSION'],
    [404, 'NOT_FOUND', 'NOT_FOUND'],
    [403, 'REFUSED', 'REFUSED'],
    [403, undefined, 'FORBIDDEN'],
    [500, 'NOT_DELIVERED', 'NOT_DELIVERED'],
    [900, 'APPROVAL_REJECTED', 'APPROVAL_REJECTED'],
  ]

  it.each(failures)('code=%i status=%s ⇒ 业务未达成，符号码 %s', (code, status, expected) => {
    const outcome = normalizeActionOutcome({
      code,
      msg: '服务端原因',
      success: false,
      data: { status },
    })
    expect(outcome).toMatchObject({
      kind: 'business', ok: false, code: expected, message: '服务端原因', numericCode: code,
    })
  })

  it('成功 = success:true 且 code:0；data.status 保留业务结果（拒绝成功 REJECTED）', () => {
    expect(normalizeActionOutcome({
      code: 0, msg: '', success: true, data: { status: 'ACCEPTED' },
    })).toMatchObject({ kind: 'ok', ok: true, code: 'ACCEPTED', numericCode: 0 })
    expect(normalizeActionOutcome({
      code: 0, msg: '', success: true, data: { status: 'REJECTED' },
    })).toMatchObject({ kind: 'ok', ok: true, code: 'REJECTED' })
    // 批量拒绝成功：rejected 从 data 里读
    expect(normalizeActionOutcome({
      code: 0, msg: '', success: true, data: { status: 'REJECTED', rejected: 3 },
    }, { inferRejectAll: true })).toMatchObject({ kind: 'ok', ok: true, rejected: 3 })
  })

  it('success 与数字码不一致时以「未达成」为准（⛔ 不能码非空就成功）', () => {
    expect(normalizeActionOutcome({
      code: 423, msg: '', success: true, data: { status: 'HELD' },
    })).toMatchObject({ ok: false, code: 'HELD' })
    expect(normalizeActionOutcome({
      code: 0, msg: '', success: false, data: null,
    })).toMatchObject({ ok: false, code: 'UNKNOWN' })
  })

  it('404/403 缺 status 时有确定的回落（404→消息不存在；403→无权限）', () => {
    expect(businessCodeOfNumeric(404)).toBe('NOT_FOUND')
    expect(businessCodeOfNumeric(403)).toBe('FORBIDDEN')
  })

  it('灰度兼容：旧扁平体字符串 code / status / rejected 仍可判读', () => {
    expect(normalizeActionOutcome({ code: 'ACCEPTED', status: 'HELD' }))
      .toMatchObject({ ok: true, code: 'ACCEPTED' })
    expect(normalizeActionOutcome({ messageId: 'p1', status: 'ACCEPTED' }))
      .toMatchObject({ ok: true, code: 'ACCEPTED' })
    expect(normalizeActionOutcome({ messageId: 'p1', status: 'HELD' }))
      .toMatchObject({ ok: false, code: 'HELD' })
    expect(normalizeActionOutcome({ code: 'REJECTED', rejected: 2 }, { inferRejectAll: true }))
      .toMatchObject({ ok: true, code: 'REJECTED', rejected: 2 })
    expect(normalizeActionOutcome({ rejected: 2 }, { inferRejectAll: true }))
      .toMatchObject({ ok: true, code: 'REJECTED', rejected: 2 })
    // 无任何业务信息 ⇒ 未达成
    expect(normalizeActionOutcome({} as never)).toMatchObject({ ok: false, code: 'UNKNOWN' })
    expect(normalizeActionOutcome(null)).toMatchObject({ ok: false, code: 'UNKNOWN' })
  })
})

describe('peerInboxApi · 拆包', () => {
  beforeEach(() => { vi.restoreAllMocks() })

  it('list 拆 CommonResult：data 即列表；旧数组原样通过', async () => {
    const spy = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      jsonResponse({ code: 0, msg: '', success: true, data: [{ messageId: 'p1' }] }),
    )
    await expect(peerInboxApi.list(SID)).resolves.toEqual([{ messageId: 'p1' }])
    expect(spy).toHaveBeenCalledWith(`${API_V1_BASE}/sessions/${SID}/peer-inbox`, expect.objectContaining({ method: 'GET' }))

    vi.mocked(globalThis.fetch).mockResolvedValueOnce(jsonResponse([{ messageId: 'p2' }]))
    await expect(peerInboxApi.list(SID)).resolves.toEqual([{ messageId: 'p2' }])
  })

  it('list 业务失败（HTTP 200 + success:false）⇒ 抛错，⛔ 不伪装空列表', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      jsonResponse({ code: 403, msg: '主体不可信', success: false, data: null }),
    )
    await expect(peerInboxApi.list(SID)).rejects.toBeInstanceOf(ApiError)
  })

  it('approve 业务失败仍是结构化结果（不抛），原因取 msg / data.status', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      jsonResponse({ code: 423, msg: '仍待确认', success: false, data: { status: 'HELD' } }),
    )
    await expect(peerInboxApi.approve(SID, MID)).resolves.toMatchObject({
      kind: 'business', ok: false, code: 'HELD', message: '仍待确认', numericCode: 423,
    })
  })

  it('approve 成功（success:true code:0 data.status=ACCEPTED）⇒ ok', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      jsonResponse({ code: 0, msg: '', success: true, data: { messageId: MID, status: 'ACCEPTED' } }),
    )
    await expect(peerInboxApi.approve(SID, MID)).resolves.toMatchObject({ kind: 'ok', ok: true, code: 'ACCEPTED' })
  })

  it('HTTP 403（审批主体不可信，body 也是 CommonResult）⇒ 通信层失败，文案「无权限」', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      jsonResponse({ code: 403, msg: '主体不可信', success: false, data: null }, 403),
    )
    await expect(peerInboxApi.approve(SID, MID)).resolves.toMatchObject({
      kind: 'transport', ok: false, code: 'TRANSPORT_ERROR', message: '无权限',
    })
  })

  it('rejectAll / markSeen 拆包：rejected、updated 从 data 读；空数组不发请求', async () => {
    const spy = vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(jsonResponse({ code: 0, msg: '', success: true, data: { status: 'REJECTED', rejected: 2 } }))
      .mockResolvedValueOnce(jsonResponse({ code: 0, msg: '', success: true, data: { updated: 2 } }))
    await expect(peerInboxApi.rejectAll(SID)).resolves.toMatchObject({ ok: true, rejected: 2 })
    await expect(peerInboxApi.markSeen(SID, ['p1', 'p2'])).resolves.toEqual({ updated: 2 })

    const before = spy.mock.calls.length
    await expect(peerInboxApi.markSeen(SID, [])).resolves.toEqual({ updated: 0 })
    expect(spy.mock.calls.length).toBe(before)
  })
})
