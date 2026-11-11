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

/**
 * 可控 activeStreams 的 Host：用于构造「后台代跑轮」的真实形态 ——
 * 该会话**从未登记** activeStreams（后台/子代理/cron 起的 run 不经本页 sendMessage）。
 */
function HostWithStreams({ sid, streams }: { sid: string; streams: Record<string, string> }) {
  const messages = useChatStore((s) => s.messages[sid] ?? EMPTY)
  useChatSocket(sid, streams, undefined, undefined, undefined, undefined, undefined, undefined, [A, B])
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

  it('⭐ 后台代跑轮：会话不在 activeStreams 且非当前会话 ⇒ stream 订阅也不得被退订', async () => {
    // WHY（用户实测「后台子代理完成 → 代跑一轮跑完 → 前端没有任何记录，F5 才有」）：
    //   activeStreams 只登记本页 sendMessage / queue.drained 发起的轮次；
    //   后台 / 子代理 / cron 代跑的轮次**从不登记** ⇒ 旧退订判据
    //   `if (!active[sid] && sid !== sessionIdRef.current) unsubscribe()` 必然把它误判成「空闲」，
    //   用户一切走就摘掉订阅。simple broker 不持久、不重放 ⇒ 该轮 chunk / complete 永久丢，
    //   且没有任何路径重订 ⇒ 只能 F5。
    // ⚠️ 注意本用例与上一条的区别：上一条把 A 恒放在 activeStreams 里，旧代码对 A 会短路、
    //   根本走不到退订分支（那条用例改动前后都绿，无判别力）。这里必须让 A **不在** activeStreams。
    act(() => { root!.render(<HostWithStreams sid={A} streams={{}} />) })
    act(() => { fakeClient.onConnect?.() })
    await act(async () => { await new Promise((r) => setTimeout(r, 40)) })

    pushChunk(A, 'blk-a', '开始')
    await act(async () => { await new Promise((r) => setTimeout(r, 40)) })
    expect(container!.textContent).toContain('开始')

    // 切到 B，且 activeStreams 仍为空（模拟后台轮次从未登记）—— effect 依赖变化会触发 syncAllStreams
    act(() => { root!.render(<HostWithStreams sid={B} streams={{}} />) })
    await act(async () => { await new Promise((r) => setTimeout(r, 40)) })

    const subA = fakeClient.subscriptions.find((s) => s.destination === `/topic/sessions/${A}/stream`)
    expect(subA, '前置失败：A 的 stream 订阅应已建立').toBeTruthy()
    expect(
      subA?.unsubscribed,
      'A 不在 activeStreams（后台代跑轮）且已切走 ⇒ 旧判据会退订；订阅一摘，该轮帧永久丢失',
    ).toBe(false)

    // 行为级验证：切走后 A 的后台内容仍能进入 store
    pushChunk(A, 'blk-a', '后台追加')
    await act(async () => { await new Promise((r) => setTimeout(r, 40)) })
    expect(container!.textContent).not.toContain('后台追加')  // 当前渲染的是 B
    act(() => { root!.render(<HostWithStreams sid={A} streams={{}} />) })
    await act(async () => { await new Promise((r) => setTimeout(r, 40)) })
    expect(container!.textContent, '切回 A 必须看到后台轮次期间推来的内容').toContain('后台追加')
  })
})
