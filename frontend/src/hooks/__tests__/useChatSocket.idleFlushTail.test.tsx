// @vitest-environment jsdom
import { act, useCallback } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Client } from '@stomp/stompjs'
import { useChatStore } from '@/stores/chatStore'
import { useChatSocket } from '@/hooks/useChatSocket'
import { useServerRunningReconcile } from '@/hooks/useServerRunning'

/**
 * ⭐ [C6 · 遗留2 · T1] 服务端收口（session.status=idle）落地前，打字机缓冲**必须**先 flush。
 *
 * <b>缺陷（本轮复验独立发现的唯一可达缺口）</b>：pendingStreamAppends 按 rAF 攒增量 delta（合并 setState）。
 * 收口对账（useServerRunning 的 useServerRunningReconcile）**直接**调 chatStore.finalizeBlocks，**不冲缓冲**；
 * 而 complete 分支（useChatSocket :781）是「先 flush 再 finalize」。
 * 机制：idle 边沿 → 对账 finalizeBlocks 把块转消息并清空块表 → 随后那一帧 rAF flush 的 appendChunk
 * **找不到块**（chatStore.appendChunk idx<0 **静默** return）⇒ 尾段 delta 无声丢失。
 *
 * <b>可达场景恰好是本批要治的「complete 帧丢失」</b>：complete 没到、idle 到了 ⇒ 收口边沿成立 ⇒ 对账跑。
 * 量级：≤1 帧（~16ms；~120 chunk/秒 ≈ 1-2 个 chunk 的尾字）。⛔ 它不是回归（改前是整块永久残留，改后最坏
 * 丢 1-2 个字），本文件把它补到「一个字都不丢」。
 *
 * <b>WHY 必须真的过一遍 socket 分派</b>（规则九 · 测试验证意图）：本缺陷的两个主角都在**接线**上 ——
 * ①增量进缓冲 vs 直接落块 ②idle 边沿那一行 flush 的位置（必须在 setServerRunning **之前**，因为对账是
 * zustand subscribe 在 set 内**同步**回调的）。只测 flushStreamAppends 纯函数、或自己往 store 里 appendChunk
 * 都碰不到这条线（现存的 Composer.stopVisibleWhenStreamRegistrationStale.test.tsx 的 pushChunk 就是直接
 * appendChunk，绕开了缓冲 —— 所以它全绿也证明不了本条）。故本文件驱动**真** useChatSocket（假 STOMP client
 * 只替换传输层），并挂上**真** useServerRunningReconcile（finalizeBlocks 就发生在那里）。
 *
 * <b>手法</b>：沿用 hooks/__tests__/useChatSocket.sessionStatus.test.tsx 的假 client 模式
 * （记录 subscribe 回调 → 手动 onConnect → 从 stream topic 回调打进 JSON 载荷）。
 *
 * <b>反向实验（都必须红）</b>：
 * <ul>
 *   <li>T1：删掉 useChatSocket idle 分支里那行 flushStreamAppends() ⇒ 定稿消息 content 为 null ⇒ 本文件红；</li>
 *   <li>T1b：把那行 flush 挪到 setServerRunning 之后（= finalizeBlocks 之后）⇒ 同样红（位置承重）。</li>
 * </ul>
 */

/** 假 STOMP client：记录订阅（destination + 回调），暴露 onConnect 供测试触发。 */
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

const SID = 'sess-c6-idle-tail'
const STREAM_TOPIC = `/topic/sessions/${SID}/stream`
const BLOCK = 'msg-assistant-tail'
const TAIL = '尾段 delta'

/** 真 hook（只换传输层）+ 真收口对账（finalizeBlocks 所在）。 */
function Host() {
  useChatSocket(SID, {}, undefined, undefined, undefined, undefined, () => {})
  useServerRunningReconcile(useCallback(() => {}, []))
  return null
}

/** 从会话 stream topic 的订阅回调打进一条事件（与后端载荷同形）。 */
function pushEvent(payload: Record<string, unknown>) {
  const sub = fakeClient.subscriptions.find((s) => s.destination === STREAM_TOPIC)
  if (!sub) throw new Error('前置失败：会话 stream topic 未订阅 —— 假 client 未记录到订阅回调')
  act(() => { sub.cb({ body: JSON.stringify({ sessionId: SID, ...payload }) }) })
}

/** 该会话流式块的正文（无块 → null）。 */
const blockContent = () => useChatStore.getState().streams[SID]?.[0]?.content ?? null
/** 该会话已定稿消息里本块那一条（finalizeBlocks 把块转成的正式消息）。 */
const finalized = () => (useChatStore.getState().messages[SID] ?? []).find((m) => m.id === BLOCK)

let container: HTMLDivElement | undefined
let root: Root | undefined

describe('[C6·遗留2·T1] idle 收口前的打字机缓冲 flush（尾段 delta 不得被 finalizeBlocks 静默丢弃）', () => {
  beforeEach(() => {
    fakeClient.subscriptions.length = 0
    useChatStore.setState({ serverRunning: {}, streamOrder: {}, streams: {}, messages: {}, agentStatus: 'idle' })
    ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    act(() => { root!.render(<Host />) })
    act(() => { fakeClient.onConnect?.() })   // 模拟连上（onConnect 里重建订阅：现行会话 stream topic）
  })

  afterEach(() => {
    if (root) act(() => root!.unmount())
    container?.remove()
    container = undefined
    root = undefined
    useChatStore.setState({ serverRunning: {}, streamOrder: {}, streams: {}, messages: {} })
  })

  it('⭐ 收口边沿（idle）到达 ⇒ 缓冲里的尾段必须先落块，再被对账定稿（不得静默丢）', async () => {
    // 事故现场本批要治的那一个：complete 帧静默丢失，但服务端权威收口（idle）到达
    pushEvent({ type: 'session.status', status: 'thinking' })     // 起轮：服务端说该会话在跑（键 = true）
    pushEvent({ type: 'message.chunk', assistantMessageId: BLOCK, delta: TAIL })

    // 前置（否则本用例是空的）：delta 此刻**仍在 rAF 缓冲里** —— 块已建，正文还是空
    expect(blockContent()).toBe('')
    expect(useChatStore.getState().streamOrder[SID]?.length).toBe(1)
    expect(useChatStore.getState().serverRunning[SID]).toBe(true)

    pushEvent({ type: 'session.status', status: 'idle' })         // 收口边沿 → 对账 finalizeBlocks

    // 反向实验 T1：删掉 idle 分支那行 flushStreamAppends() ⇒ 块被空定稿、尾段在之后的 rAF 帧里被丢弃
    //   ⇒ finalized().content 为 null ⇒ 本行红。
    // 反向实验 T1b：把那行 flush 挪到 setServerRunning 之后（= finalizeBlocks 之后）⇒ 同样红。
    expect(blockContent()).toBe(null)                              // 块已被对账定稿（不再残留）
    expect(finalized()?.content).toBe(TAIL)                        // ⭐ 尾段必须还在
    expect(useChatStore.getState().serverRunning[SID]).toBe(false)

    // 再放过至少一帧：证明那次被吞掉的 rAF flush 不会再回来（flush 已取消待定帧 + 缓冲已清）
    await act(async () => { await new Promise((r) => setTimeout(r, 32)) })
    expect(finalized()?.content).toBe(TAIL)
    expect(blockContent()).toBe(null)
  })
})
