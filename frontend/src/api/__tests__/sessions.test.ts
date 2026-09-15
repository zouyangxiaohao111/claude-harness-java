import { describe, expect, it, vi, beforeEach } from 'vitest'
import { sessionApi } from '../sessions'
import { API_V1_BASE } from '../base'

describe('sessionApi', () => {
  beforeEach(() => { vi.restoreAllMocks() })
  it('list 调 GET /sessions（根地址取自单一来源 base.ts，⛔ 不在测试里再写死一遍）', async () => {
    const spy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } }))
    await sessionApi.list()
    expect(spy).toHaveBeenCalledWith(`${API_V1_BASE}/sessions`, expect.objectContaining({ method: 'GET' }))
  })
})
