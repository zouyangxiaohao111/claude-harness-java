import { describe, expect, it } from 'vitest'
import { parseStreamEvent, isChunk, isComplete, isMessageUsage, isPermission } from '../socket'

describe('socket 事件解析', () => {
  it('chunk 类型守卫', () => {
    expect(isChunk(parseStreamEvent({ type: 'message.chunk', delta: 'x' }))).toBe(true)
    expect(isComplete(parseStreamEvent({ type: 'message.chunk', delta: 'x' }))).toBe(false)
  })
  it('complete 类型守卫', () => {
    expect(isComplete(parseStreamEvent({ type: 'message.complete', content: 'x' }))).toBe(true)
  })
  it('message.usage 类型守卫：命中且不透传 extra 字段丢失', () => {
    const evt = parseStreamEvent({ type: 'message.usage', assistantMessageId: 'a1', contextWindow: 200000, contextTokensUsed: 1234, percentLeft: 99 })
    expect(isMessageUsage(evt)).toBe(true)
    expect((evt as { contextTokensUsed?: number }).contextTokensUsed).toBe(1234)
  })
  it("isComplete('message.usage') === false（不提前退订锚点：消息级完成 ≠ turn 终态，退订只在 message.complete）", () => {
    // 若 isComplete 误判 message.usage 为 turn 终态 → useChatSocket 会调 onSessionDone → 提前退订
    //   中断后续轮次流式。此断言守护该回归。
    expect(isComplete(parseStreamEvent({ type: 'message.usage', assistantMessageId: 'a1' }))).toBe(false)
    expect(isMessageUsage(parseStreamEvent({ type: 'message.complete' }))).toBe(false)
  })
  it('permission 类型守卫', () => {
    expect(isPermission(parseStreamEvent({ type: 'permission.request', requestId: 'r' }))).toBe(true)
  })
})
