// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Client } from '@stomp/stompjs'
import { useChatStore } from '@/stores/chatStore'
import { useChatSocket } from '@/hooks/useChatSocket'

/**
 * [retry-per-session] 重试提示的**归属**与**清除时机**守门测试。
 *
 * <b>WHY（用户实测缺陷）</b>：
 * <ol>
 *   <li>「所有页面都有正在重试 第3/10」—— 原实现 {@code retry} 是**全局单值**，A 会话触发 api_retry 后，
 *       切到任何会话（含 StreamHeader 头部）都显示 A 的计数；</li>
 *   <li>「已经重新获取成功回复了，但计数没刷新」—— 原实现只在 {@code message.complete} 清，
 *       而重试成功后到本轮结束之间可能长达数分钟，期间横幅一直挂着。</li>
 * </ol>
 *
 * <p>本文件驱动**真** {@link useChatSocket}（假 STOMP client 只替换传输层），从会话 stream topic
 * 打进真实事件载荷，断言：
 * <ul>
 *   <li>{@code api_retry} 只写**投递 topic 所属会话**的键（不污染其它会话）；</li>
 *   <li>该会话收到 chunk（= 这次尝试拿到上游内容）→ 立即清除；</li>
 *   <li>complete / error → 清除；别的会话的 complete 不得清掉本会话的重试提示。</li>
 * </ul>
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

const SID = 'sess-retry-a'
const OTHER = 'sess-retry-b'
const STREAM_TOPIC = `/topic/sessions/${SID}/stream`
const OTHER_TOPIC = `/topic/sessions/${OTHER}/stream`

/** 往某个会话的 stream topic 打一条事件（归属以投递 topic 为准）。 */
function push(sessionId: string, payload: Record<string, unknown>) {
  const topic = sessionId === SID ? STREAM_TOPIC : `/topic/sessions/${sessionId}/stream`
  const sub = fakeClient.subscriptions.find((s) => s.destination === topic)
  if (!sub) throw new Error(`前置失败：${topic} 未订阅 —— 假 client 未记录到订阅回调`)
  act(() => { sub.cb({ body: JSON.stringify({ sessionId, ...payload }) }) })
}

const retryOf = (sid: string) => useChatStore.getState().retry[sid] ?? null

let container: HTMLDivElement | undefined
let root: Root | undefined

function Host() {
  useChatSocket(SID, { [OTHER]: OTHER_TOPIC }, undefined, undefined, undefined, undefined, () => {})
  return null
}

describe('[retry-per-session] 重试提示：只归本会话 + 重试成功即消失', () => {
  beforeEach(() => {
    fakeClient.subscriptions.length = 0
    useChatStore.setState({ retry: {} })
    ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    act(() => { root!.render(<Host />) })
    act(() => { fakeClient.onConnect?.() })
  })

  afterEach(() => {
    if (root) act(() => root!.unmount())
    container?.remove()
    container = undefined
    root = undefined
    useChatStore.setState({ retry: {} })
  })

  it('前置：两个会话的 stream topic 都已订阅', () => {
    expect(fakeClient.subscriptions.some((s) => s.destination === STREAM_TOPIC)).toBe(true)
    expect(fakeClient.subscriptions.some((s) => s.destination === OTHER_TOPIC)).toBe(true)
  })

  it('⭐ api_retry 只写该会话的键：别的会话不得显示「第 3/10 次」', () => {
    push(SID, { type: 'api_retry', subtype: 'api_retry', attempt: 3, maxRetries: 10, retryDelayMs: 4000 })

    expect(retryOf(SID)).toMatchObject({ attempt: 3, maxRetries: 10, retryDelayMs: 4000 })
    expect(retryOf(OTHER)).toBeNull()
    expect(retryOf(SID)?.at).toBeTypeOf('number')
  })

  it('⭐ 尝试已发起（retryDelayMs=0）也记一条：长静默期要能看到「已等待」', () => {
    push(SID, { type: 'api_retry', subtype: 'api_retry', attempt: 2, maxRetries: 10, retryDelayMs: 0 })

    expect(retryOf(SID)).toMatchObject({ attempt: 2, maxRetries: 10, retryDelayMs: 0 })
  })

  it('⭐ 收到本轮 chunk = 重试成功 → 立即清除（不等 turn 结束）', () => {
    push(SID, { type: 'api_retry', subtype: 'api_retry', attempt: 4, maxRetries: 10, retryDelayMs: 0 })
    expect(retryOf(SID)).not.toBeNull()

    push(SID, { type: 'message.chunk', assistantMessageId: 'asst-1', delta: '好' })

    expect(retryOf(SID)).toBeNull()
  })

  it('complete / error 清除本会话提示（原有收口路径不得回退）', () => {
    push(SID, { type: 'api_retry', subtype: 'api_retry', attempt: 1, maxRetries: 10, retryDelayMs: 1000 })
    push(SID, { type: 'message.complete', assistantMessageId: 'asst-1' })
    expect(retryOf(SID)).toBeNull()

    push(SID, { type: 'api_retry', subtype: 'api_retry', attempt: 2, maxRetries: 10, retryDelayMs: 1000 })
    push(SID, { type: 'message.error', code: 'model_error', message: 'x' })
    expect(retryOf(SID)).toBeNull()
  })

  it('⭐ 别的会话 complete 不得清掉本会话的重试提示（跨会话误清）', () => {
    push(SID, { type: 'api_retry', subtype: 'api_retry', attempt: 5, maxRetries: 10, retryDelayMs: 0 })
    push(OTHER, { type: 'message.complete', assistantMessageId: 'asst-other' })

    expect(retryOf(SID)).toMatchObject({ attempt: 5 })
  })

  /**
   * ⭐ 病灶本体的正面对照：**当前会话（SID）没有在重试，重试发生在后台会话（OTHER）**。
   * 用户看到的是「会话1 没回复、会话2 显示出正在重试 3/10」。判据必须落在
   * 「键 = 投递 topic 的会话」，而不是「键 = 当前会话」或「全局单值」。
   */
  it('⭐ 后台会话重试时，提示必须记在**它自己**头上，不得落到当前会话', () => {
    push(OTHER, { type: 'api_retry', subtype: 'api_retry', attempt: 3, maxRetries: 10, retryDelayMs: 0 })

    expect(retryOf(OTHER)).toMatchObject({ attempt: 3 })
    expect(retryOf(SID)).toBeNull()
  })

  it('⭐ 后台会话重试时，它的 chunk 清它自己的提示（当前会话的键不受影响）', () => {
    push(SID, { type: 'api_retry', subtype: 'api_retry', attempt: 1, maxRetries: 10, retryDelayMs: 0 })
    push(OTHER, { type: 'api_retry', subtype: 'api_retry', attempt: 7, maxRetries: 10, retryDelayMs: 0 })
    expect(retryOf(OTHER)).toMatchObject({ attempt: 7 })

    push(OTHER, { type: 'message.chunk', assistantMessageId: 'asst-other', delta: 'x' })

    expect(retryOf(OTHER)).toBeNull()
    expect(retryOf(SID)).toMatchObject({ attempt: 1 })   // 当前会话自己的仍在（没被别会话的 chunk 清掉）
  })
})
