import { describe, expect, it, vi, beforeEach } from 'vitest'
import { sessionApi } from '../sessions'

describe('sessionApi', () => {
  beforeEach(() => { vi.restoreAllMocks() })
  it('list 调 GET /sessions', async () => {
    const spy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } }))
    await sessionApi.list()
    expect(spy).toHaveBeenCalledWith('http://localhost:3458/api/v1/sessions', expect.objectContaining({ method: 'GET' }))
  })
})
