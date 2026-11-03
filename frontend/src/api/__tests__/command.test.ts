import { describe, expect, it, vi, beforeEach } from 'vitest'
import { commandApi } from '../command'
import { API_BASE } from '../base'

describe('commandApi', () => {
  beforeEach(() => { vi.restoreAllMocks() })
  it('list 走 /api/command 前缀（非 /api/v1）· 根地址取自单一来源 base.ts', async () => {
    const spy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('[]', { status: 200 }))
    await commandApi.list()
    expect(spy).toHaveBeenCalledWith(`${API_BASE}/command`, expect.objectContaining({ method: 'GET' }))
  })

  /**
   * <b>WHY（2026-11-02 用户实测「/compact 报 JSON 解析异常」）</b>：
   * `POST /builtins/compact/execute` 的字面端点在 Spring 侧声明
   * `produces="text/plain;charset=UTF-8"`，且字面路由**优先于** `{name}` 路径变量 ⇒
   * /compact 实际命中纯文本端点。此前本 API 硬编码 `Accept: application/json`
   * ⇒ 内容协商失败 406 ⇒ 错误体是 HTML ⇒ 前端 `JSON.parse` 抛 `Unexpected token 'W'`。
   * 这条断言锁住「Accept 必须放宽」这一契约，防止日后又被收紧回 json。
   */
  it('executeBuiltin：Accept 必须放宽（/compact 端点返回 text/plain，写死 json 会 406）', async () => {
    const spy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response('Compacted 12k -> 3k', { status: 200, headers: { 'Content-Type': 'text/plain;charset=UTF-8' } }),
    )
    const out = await commandApi.executeBuiltin('compact', 'sess-1')
    const init = spy.mock.calls[0]![1] as RequestInit
    const headers = init.headers as Record<string, string>
    expect(headers.Accept).toBe('*/*')
    expect(headers.Accept).not.toBe('application/json')
    // 且纯文本响应必须原样返回，不能被当成 JSON 解析
    expect(out).toBe('Compacted 12k -> 3k')
  })
})
