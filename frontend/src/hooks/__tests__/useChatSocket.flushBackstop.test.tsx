// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Client } from '@stomp/stompjs'
import { useChatStore } from '@/stores/chatStore'
import { useChatSocket } from '@/hooks/useChatSocket'

/**
 * ⭐ [flush-backstop 2026-11-05] rAF 被"排定但永不触发"时，打字机缓冲必须有保底 flush。
 *
 * <b>病情（用户实测）</b>：任务明明跑完了，界面却卡在渲染中间，**切换会话后才继续渲染**。
 * 切会话 = `loadTailWindow` 整表替换 ⇒ 能显示出来说明**内容早就在本地/DB 里**，
 * 只是卡在「打字机缓冲没落库」这一段没有推进。
 *
 * <b>机制</b>：`scheduleStreamFlush` 原来只排 `requestAnimationFrame`，并且用一个全局
 * `streamFlushRaf` 记住"已排定"。而 rAF 在**窗口不可见/被最小化/WebView2 后台**时会被
 * 浏览器暂停调度 —— 一旦那一帧迟迟不来，`streamFlushRaf` 仍是非 null，
 * 后续每个 chunk 都只往缓冲里累积、**再也不会排新的 flush** ⇒ 界面停在半截，
 * 直到某个显式 `flushStreamAppends()`（complete/idle/卸载/切会话重拉）。
 *
 * <b>本文件锁死的契约</b>：即使 rAF **一次都不触发**，250ms 内内容也必须出现在流式块里。
 *
 * <b>反向实验（必须红）</b>：删掉 `scheduleStreamFlush` 末尾那段 `setTimeout` 兜底 ⇒
 * {@code blockContent()} 仍是空串 ⇒ 本文件红。
 */

/** 假 STOMP client（只记录订阅，暴露 onConnect）。 */
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

const SID = 'sess-flush-backstop'
const STREAM_TOPIC = `/topic/sessions/${SID}/stream`
const BLOCK = 'msg-assistant-backstop'
const DELTA = '卡住的那段正文'

function Host() {
  useChatSocket(SID, {}, undefined, undefined, undefined, undefined, undefined, undefined, [])
  return null
}

function pushEvent(payload: Record<string, unknown>) {
  const sub = fakeClient.subscriptions.find((s) => s.destination === STREAM_TOPIC)
  if (!sub) throw new Error('前置失败：会话 stream topic 未订阅')
  act(() => { sub.cb({ body: JSON.stringify({ sessionId: SID, ...payload }) }) })
}

const blockContent = () => useChatStore.getState().streams[SID]?.[0]?.content ?? null

let container: HTMLDivElement | undefined
let root: Root | undefined
let rafSpy: ReturnType<typeof vi.spyOn> | undefined

describe('[flush-backstop] rAF 不触发时打字机缓冲仍须落库', () => {
  beforeEach(() => {
    fakeClient.subscriptions.length = 0
    useChatStore.setState({ serverRunning: {}, streamOrder: {}, streams: {}, messages: {}, agentStatus: 'idle' })
    ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    // ⭐ 关键前置：rAF「接受排定但永不回调」—— 模拟窗口不可见 / WebView2 后台暂停调度。
    //   ⛔ 不能用 vi.useFakeTimers()（它也会接管 setTimeout，把兜底一起冻住，测不出东西）。
    rafSpy = vi.spyOn(globalThis, 'requestAnimationFrame').mockImplementation(() => 1)

    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    act(() => { root!.render(<Host />) })
    act(() => { fakeClient.onConnect?.() })
  })

  afterEach(() => {
    rafSpy?.mockRestore()
    rafSpy = undefined
    if (root) act(() => root!.unmount())
    container?.remove()
    container = undefined
    root = undefined
    useChatStore.setState({ serverRunning: {}, streamOrder: {}, streams: {}, messages: {} })
  })

  it('⭐ rAF 永不回调 ⇒ 250ms 后正文仍出现在流式块（否则界面永久停在半截）', async () => {
    pushEvent({ type: 'message.chunk', assistantMessageId: BLOCK, delta: DELTA })

    // 前置（否则本用例是空的）：此刻确实只有 rAF 在等，缓冲尚未落库
    expect(blockContent()).toBe('')

    // 放过兜底窗口（250ms）+ 余量
    await act(async () => { await new Promise((r) => setTimeout(r, 400)) })

    // 反向实验：删掉 scheduleStreamFlush 的 setTimeout 兜底 ⇒ 这里仍是空串 ⇒ 红
    expect(blockContent()).toBe(DELTA)
  })

  it('rAF 正常回调时不重复 flush（兜底定时器被显式 flush 取消）', async () => {
    rafSpy?.mockRestore()
    // 让 rAF 立刻同步回调：模拟可见窗口的正常路径
    rafSpy = vi.spyOn(globalThis, 'requestAnimationFrame').mockImplementation((cb: FrameRequestCallback) => {
      cb(0)
      return 1
    })

    pushEvent({ type: 'message.chunk', assistantMessageId: BLOCK, delta: DELTA })
    // rAF 已同步落库
    expect(blockContent()).toBe(DELTA)

    // 再等过兜底窗口：不得二次追加（缓冲区已被 flush 清空，定时器也已取消）
    await act(async () => { await new Promise((r) => setTimeout(r, 400)) })
    expect(blockContent()).toBe(DELTA)
  })
})
