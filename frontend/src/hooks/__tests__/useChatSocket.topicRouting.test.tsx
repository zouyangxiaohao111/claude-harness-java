// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Client } from '@stomp/stompjs'
import { useChatStore } from '@/stores/chatStore'
import { useChatSocket } from '@/hooks/useChatSocket'

/**
 * ⭐ [2026-10-23 跨会话串台] 事件归属会话 = **投递 topic**（不是载荷 sessionId、更不是当前活动会话）。
 *
 * <b>病情（用户实测）</b>：两个会话同时活跃时，A 窗口里出现了 B 会话的助手内容，F5 之后仍在
 * （F5 只重拉 messages、不清 streams ⇒ 说明是 streams[A] 里被写进了一条别会话的流式块）。
 *
 * <b>根因</b>：`dispatchEvent` 取 `evt.sessionId ?? 当前会话` —— 任一**缺 sessionId 的帧**
 * （后台/旁路/旧后端）都会落到"当前正在看的会话"名下；topic 里明明有权威 sid 却被忽略。
 *
 * <b>修法（本测试锁死）</b>：会话级 topic 解析出的 sid 优先；载荷缺失/不一致都不得写进当前会话。
 */

const fakeClient = vi.hoisted(() => {
  const subscriptions: Array<{ destination: string; cb: (msg: { body: string }) => void }> = []
  return {
    subscriptions,
    connected: true,
    onConnect: null as null | (() => void),
    onDisconnect: null as null | (() => void),
    onWebSocketClose: null as null | (() => void),
    activate: () => {},
    deactivate: async () => {},
    publish: () => {},
    subscribe: (destination: string, cb: (msg: { body: string }) => void) => {
      subscriptions.push({ destination, cb })
      return { id: String(subscriptions.length), unsubscribe: () => {} }
    },
  }
})

vi.mock('@/api/socket', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/socket')>()
  return { ...actual, createSocketClient: () => fakeClient as unknown as Client }
})

const A = 'sess-active-a'
const B = 'sess-background-b'
const TOPIC_A = `/topic/sessions/${A}/stream`
const TOPIC_B = `/topic/sessions/${B}/stream`

function Host() {
  // 当前看 A；B 也在活跃集合里（多会话并行）
  useChatSocket(A, { [A]: TOPIC_A, [B]: TOPIC_B })
  return null
}

function push(topic: string, payload: Record<string, unknown>) {
  const sub = fakeClient.subscriptions.find((s) => s.destination === topic)
  if (!sub) throw new Error(`前置失败：${topic} 未订阅`)
  act(() => { sub.cb({ body: JSON.stringify(payload) }) })
}

const flush = async () => { await act(async () => { await new Promise((r) => setTimeout(r, 40)) }) }
const blockIds = (sid: string) =>
  (useChatStore.getState().streams[sid] ?? []).map((b) => b.assistantMessageId)
const contentOf = (sid: string, blockId: string) =>
  (useChatStore.getState().streams[sid] ?? []).find((b) => b.assistantMessageId === blockId)?.content ?? null

let container: HTMLDivElement | undefined
let root: Root | undefined

describe('[topic 优先] 事件归属不得回落到当前会话', () => {
  beforeEach(() => {
    fakeClient.subscriptions.length = 0
    useChatStore.setState({ messages: {}, streams: {}, streamOrder: {}, serverRunning: {}, liveSeq: {} } as never)
    ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
  })
  afterEach(() => {
    if (root) act(() => root!.unmount())
    container?.remove()
    container = undefined
    root = undefined
  })

  async function mount() {
    act(() => { root!.render(<Host />) })
    act(() => { fakeClient.onConnect?.() })
    await flush()
  }

  it('⭐ 载荷缺 sessionId 的帧 → 按 topic 落到 B（绝不写进当前会话 A）', async () => {
    await mount()
    push(TOPIC_B, { type: 'message.chunk', assistantMessageId: 'blk-b', delta: 'B 的内容' })
    await flush()

    expect(contentOf(B, 'blk-b'), 'B 的帧必须落 B').toBe('B 的内容')
    expect(blockIds(A), 'A 一侧不得出现 B 的块（旧实现会落到当前会话）').toHaveLength(0)
  })

  it('⭐ 载荷 sessionId 与 topic 不一致 → 以 topic 为准（不一致时留痕）', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    await mount()
    push(TOPIC_B, { type: 'message.chunk', sessionId: A, assistantMessageId: 'blk-x', delta: '错标的帧' })
    await flush()

    expect(contentOf(B, 'blk-x'), '以通道为准').toBe('错标的帧')
    expect(blockIds(A)).toHaveLength(0)
    expect(warn).toHaveBeenCalled()
    warn.mockRestore()
  })

  it('对照：载荷与 topic 一致 → 正常落各自会话', async () => {
    await mount()
    push(TOPIC_A, { type: 'message.chunk', sessionId: A, assistantMessageId: 'blk-a', delta: 'A 的内容' })
    push(TOPIC_B, { type: 'message.chunk', sessionId: B, assistantMessageId: 'blk-b2', delta: 'B 的内容' })
    await flush()

    expect(contentOf(A, 'blk-a')).toBe('A 的内容')
    expect(contentOf(B, 'blk-b2')).toBe('B 的内容')
  })
})
