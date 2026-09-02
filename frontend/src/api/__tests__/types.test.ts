import { describe, expect, it } from 'vitest'
import { parseStreamEvent } from '../socket'

describe('parseStreamEvent', () => {
  it('解析 message.chunk', () => {
    const evt = parseStreamEvent({ type: 'message.chunk', sessionId: 's', userMessageId: 'u', assistantMessageId: 'a', delta: 'hi', ts: 1 })
    expect(evt.type).toBe('message.chunk')
  })
  it('解析 session.status（status 字段）', () => {
    const evt = parseStreamEvent({ type: 'session.status', sessionId: 's', userMessageId: 'u', status: 'thinking', ts: 1 })
    expect(evt.type).toBe('session.status')
  })
  it('解析 api_retry', () => {
    const evt = parseStreamEvent({ type: 'api_retry', sessionId: 's', userMessageId: 'u', attempt: 1, maxRetries: 3, retryDelayMs: 1000, ts: 1 })
    expect(evt.type).toBe('api_retry')
  })
})
