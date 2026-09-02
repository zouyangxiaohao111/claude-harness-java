import { describe, expect, it } from 'vitest'
import { parseStreamEvent, isChunk, isComplete, isPermission } from '../socket'

describe('socket 事件解析', () => {
  it('chunk 类型守卫', () => {
    expect(isChunk(parseStreamEvent({ type: 'message.chunk', delta: 'x' }))).toBe(true)
    expect(isComplete(parseStreamEvent({ type: 'message.chunk', delta: 'x' }))).toBe(false)
  })
  it('complete 类型守卫', () => {
    expect(isComplete(parseStreamEvent({ type: 'message.complete', content: 'x' }))).toBe(true)
  })
  it('permission 类型守卫', () => {
    expect(isPermission(parseStreamEvent({ type: 'permission.request', requestId: 'r' }))).toBe(true)
  })
})
