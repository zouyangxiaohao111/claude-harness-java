// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Client } from '@stomp/stompjs'
import { useChatStore } from '@/stores/chatStore'
import { useChatSocket } from '@/hooks/useChatSocket'
import { MessageList } from '@/components/center/MessageList'
import type { ChatMessageDto } from '@/api/types'

/**
 * ⭐ [2026-10-22] 切走会话后流式内容继续递增；切回必须**直接看到**最新内容（不得 F5 才见）。
 *
 * <b>病情（用户实测）</b>：会话 A 正在流式输出时切到会话 B；A 的输出继续在跑，
 * 但切回 A 时只看到切走那一刻的内容，必须 F5（从 REST 重拉）才能看到后续。
 *
 * <b>机制</b>：stream 订阅按 sid 常驻（切走不退订）→ 事件持续写入 `streams[A]`；
 * 切回时 MessageList 直接从 store 读同一份块 ⇒ 渲染即最新。
 * 本用例锁死这条链路：切走期间推送的 chunk 必须在切回后可见，且 A 的 stream 订阅未被退订。
 */

const fakeClient = vi.hoisted(() => {
  const subscriptions: Array<{
    destination: string
    cb: (msg: { body: string }) => void
    unsubscribed: boolean
  }> = []
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
      const rec = { destination, cb, unsubscribed: false }
      subscriptions.push(rec)
      return { id: String(subscriptions.length), unsubscribe: () => { rec.unsubscribed = true } }
    },
  }
})

vi.mock('@/api/socket', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/socket')>()
  return { ...actual, createSocketClient: () => fakeClient as unknown as Client }
})

const A = 'sess-stream-a'
const B = 'sess-stream-b'
const noop = () => {}
const EMPTY: ChatMessageDto[] = []

function Host({ sid }: { sid: string }) {
  // 模拟运行中：A 始终登记在 activeStreams（切走不移除）
  const messages = useChatStore((s) => s.messages[sid] ?? EMPTY)
  useChatSocket(sid, { [A]: `/topic/sessions/${A}/stream` }, undefined, undefined, undefined, undefined, undefined, undefined, [A, B])
  return (
    <MessageList sessionId={sid} messages={messages} onDelete={noop} conversationId={null}
      onLoadOlder={noop} onNearBottomChange={noop} />
  )
}

function pushChunk(sid: string, assistantMessageId: string, delta: string) {
  const sub = fakeClient.subscriptions.find((s) => s.destination === `/topic/sessions/${sid}/stream`)
  if (!sub) throw new Error(`前置失败：${sid} 的 stream topic 未订阅`)
  act(() => { sub.cb({ body: JSON.stringify({ type: 'message.chunk', sessionId: sid, assistantMessageId, delta }) }) })
}

let container: HTMLDivElement | undefined
let root: Root | undefined

describe('[跨会话流式] 切走期间的内容，切回立即可见', () => {
  beforeEach(() => {
    fakeClient.subscriptions.length = 0
    useChatStore.setState({ messages: {}, streams: {}, streamOrder: {}, serverRunning: {}, streamsBySession: undefined } as never)
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

  it('⭐ A 流式中切到 B → 继续推送 → 切回 A 时新内容已渲染（无需 F5）', async () => {
    act(() => { root!.render(<Host sid={A} />) })
    act(() => { fakeClient.onConnect?.() })
    // 让 rAF 缓冲 flush（jsdom 无 rAF → queueMicrotask 回落）
    await act(async () => { await new Promise((r) => setTimeout(r, 40)) })

    pushChunk(A, 'blk-a', '前半段')
    await act(async () => { await new Promise((r) => setTimeout(r, 40)) })
    expect(container!.textContent).toContain('前半段')

    // ---- 切到 B ----
    act(() => { root!.render(<Host sid={B} />) })
    await act(async () => { await new Promise((r) => setTimeout(r, 40)) })

    // 切走期间 A 仍在推流
    pushChunk(A, 'blk-a', '后半段')
    await act(async () => { await new Promise((r) => setTimeout(r, 40)) })

    // A 的 stream 订阅不得被退订（否则后续 chunk 永久丢失）
    const subA = fakeClient.subscriptions.find((s) => s.destination === `/topic/sessions/${A}/stream`)
    expect(subA?.unsubscribed, '切走后 A 的 stream 订阅被退订 ⇒ 后续内容必丢').toBe(false)

    // ---- 切回 A：store 已是最新 ⇒ 渲染立即包含两段 ----
    act(() => { root!.render(<Host sid={A} />) })
    await act(async () => { await new Promise((r) => setTimeout(r, 40)) })

    expect(container!.textContent).toContain('前半段')
    expect(container!.textContent).toContain('后半段')
  })
})
