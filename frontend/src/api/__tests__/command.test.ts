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
})
