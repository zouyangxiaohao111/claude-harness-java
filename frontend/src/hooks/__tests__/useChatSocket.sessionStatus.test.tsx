// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Client } from '@stomp/stompjs'
import { useChatStore } from '@/stores/chatStore'
import { useChatSocket } from '@/hooks/useChatSocket'

/**
 * ⭐ [C6] {@code session.status} 事件 → 「服务端运行态」的**方向**守门测试。
 *
 * <b>WHY（用户需求「能够 UI 中手动终止会话循环」· 规则九 测试验证意图）</b>：停止键可见性有两条独立通道：
 * <ol>
 *   <li><b>实时通道（本文件）</b>：drain（排队命令 / cron / 任务通知）起的 run 起轮推
 *       {@code session.status=thinking} → 前端立刻把该会话标为「运行中」→ 显示停止键；收口推 {@code idle}
 *       → 停止键消失。这是<b>唯一</b>能让后台 run 在 UI 上「看得见」的实时信号。</li>
 *   <li><b>重建通道</b>：GET /sessions/{id}/running（见
 *       components/center/__tests__/Composer.stopVisibleWhenServerRunning.test.tsx）。</li>
 * </ol>
 * ⛔ <b>方向反了 = 病灶本身</b>：把 thinking 映射成 false、idle 映射成 true，恰是本批要修的缺陷形态
 * （后台 drain 起轮时不显示停止键 / 收口后停止键永不消失）。这类「接线方向」缺陷纯函数测不到、
 * 只测 store 也测不到 —— 必须真的过一遍 socket 事件分派。故本文件驱动<b>真</b> {@link useChatSocket}
 * （假 STOMP client 只替换传输层），从订阅回调打进真实事件载荷。
 *
 * <b>手法</b>：只 mock {@code createSocketClient}（其余 socket 工具函数用真实现），用假 client 记录
 * 每个 subscribe 的回调 → 手动触发 onConnect → 从会话 stream topic 的回调打进 JSON 载荷。
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

const SID = 'sess-c6-status'
const OTHER = 'sess-c6-other'
const STREAM_TOPIC = `/topic/sessions/${SID}/stream`

/** 打进一条 session.status（载荷形状 = 后端 SessionStatusEvent）。 */
function pushSessionStatus(status: string, sessionId: string = SID) {
  const sub = fakeClient.subscriptions.find((s) => s.destination === STREAM_TOPIC)
  if (!sub) throw new Error('前置失败：会话 stream topic 未订阅 —— 假 client 未记录到订阅回调')
  act(() => { sub.cb({ body: JSON.stringify({ type: 'session.status', status, sessionId }) }) })
}

/** 该会话当前是否被标为「服务端在跑」（键缺失 = 未查过/未在跑 ⇒ false）。 */
const serverRunningOf = (sid: string) => !!useChatStore.getState().serverRunning[sid]

let container: HTMLDivElement | undefined
let root: Root | undefined

function Host() {
  // 真 hook（只换了传输层）：sessionId = 当前会话，events 从它的订阅回调进
  useChatSocket(SID, {}, undefined, undefined, undefined, undefined, () => {})
  return null
}

describe('[C6] session.status → 服务端运行态（停止键的实时信号 · 方向必须对）', () => {
  beforeEach(() => {
    fakeClient.subscriptions.length = 0
    useChatStore.setState({ serverRunning: {} })
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
    useChatStore.setState({ serverRunning: {} })
  })

  it('前置：会话 stream topic 已被订阅（否则下面的用例会在错误的地方打事件）', () => {
    expect(fakeClient.subscriptions.some((s) => s.destination === STREAM_TOPIC)).toBe(true)
  })

  it('⭐ thinking / streaming ⇒ 该会话 = 运行中（后台 drain 起轮时停止键必须出现）', () => {
    expect(serverRunningOf(SID)).toBe(false)

    pushSessionStatus('thinking')
    expect(serverRunningOf(SID)).toBe(true)

    // 收口后再起一轮（流式）同样算运行中
    pushSessionStatus('idle')
    expect(serverRunningOf(SID)).toBe(false)
    pushSessionStatus('streaming')
    expect(serverRunningOf(SID)).toBe(true)
  })

  it('⭐ idle ⇒ 该会话 = 空闲（停止键必须消失，否则 UI 永久停在「运行中」）', () => {
    pushSessionStatus('thinking')
    expect(serverRunningOf(SID)).toBe(true)

    pushSessionStatus('idle')

    expect(serverRunningOf(SID)).toBe(false)
  })

  it('非法 / 未知 status ⇒ 按空闲处理（后端脏数据不得把前端永久钉在「运行中」）', () => {
    pushSessionStatus('thinking')
    expect(serverRunningOf(SID)).toBe(true)

    pushSessionStatus('weird-value-from-older-backend')

    expect(serverRunningOf(SID)).toBe(false)
  })

  it('按会话键控：别的会话的 status 不得改动本会话的运行态（多会话并行不串台）', () => {
    pushSessionStatus('thinking', OTHER)

    expect(serverRunningOf(OTHER)).toBe(true)
    expect(serverRunningOf(SID)).toBe(false)
  })
})
