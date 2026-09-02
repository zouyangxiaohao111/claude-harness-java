import { describe, expect, it, vi, beforeEach } from 'vitest'
import { commandApi } from '../command'

describe('commandApi', () => {
  beforeEach(() => { vi.restoreAllMocks() })
  it('list 走 /api/command 前缀（非 /api/v1）', async () => {
    const spy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('[]', { status: 200 }))
    await commandApi.list()
    expect(spy).toHaveBeenCalledWith('http://localhost:3458/api/command', expect.objectContaining({ method: 'GET' }))
  })
})
