// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Client } from '@stomp/stompjs'
import { useChatStore } from '@/stores/chatStore'
import { useChatSocket } from '@/hooks/useChatSocket'
import type { ChatMessageDto } from '@/api/types'

/**
 * ⭐ [sm-boundary-reload] message.insert / complete.compacted 两条**接线**的守门测试。
 *
 * <b>WHY（规则九 · 测试验证意图）</b>：本批前端分两件，两件都是**纯接线**（纯函数/纯 store 层测不到）：
 * <ol>
 *   <li><b>即时层</b>：压缩落库即推的 {@code message.insert}（整行 = compact boundary 行）→ 必须落进
 *       消息列表尾（分割线立刻显示，不等 turn 收尾 / F5）。守卫判据（{@code type === 'message.insert'}）
 *       与空载荷防御只在这条分派链上，store 单测覆盖不到。</li>
 *   <li><b>对账层</b>：{@code message.complete.compacted=true} → finalize 之后触发收尾重拉（补 summary
 *       等其余新行）。「带与不带」方向反了 = 无压缩轮也重拉（假信号）或压缩轮永不补 —— 只有真过一遍
 *       分派才能守住。</li>
 * </ol>
 *
 * <b>手法</b>：沿用 hooks/__tests__/useChatSocket.sessionStatus.test.tsx 的假 STOMP client 模式
 * （只把 createSocketClient 换成假 client → 记录 subscribe 回调 → 手动 onConnect → 从会话 stream topic
 * 的回调打进 JSON 载荷），其余 socket 工具函数（含新守卫 isMessageInsert）用真实现。
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

const SID = 'sess-smboundary'
const STREAM_TOPIC = `/topic/sessions/${SID}/stream`
const BLOCK = 'blk-finalize-order'

/** 收尾重拉回调（App 生产注册的是 handleReconnectReload；本文件验证「被调/不被调」+ 调用时刻的状态）。 */
const onCompactedReload = vi.fn()
/** 回调触发那一刻的会话消息快照（钉「重拉必须在 finalizeBlocks 之后」）。 */
const snapshotAtReload: { msgs: ChatMessageDto[] } = { msgs: [] }

/** 从会话 stream topic 的订阅回调打进一条事件（与后端载荷同形）。 */
function pushEvent(payload: Record<string, unknown>) {
  const sub = fakeClient.subscriptions.find((s) => s.destination === STREAM_TOPIC)
  if (!sub) throw new Error('前置失败：会话 stream topic 未订阅 —— 假 client 未记录到订阅回调')
  act(() => { sub.cb({ body: JSON.stringify({ sessionId: SID, ...payload }) }) })
}

/** 压缩 boundary 行（与后端 MessageInsertEvent.messages 元素同形：role=system + subtype=compact_boundary + isMeta=false）。 */
const boundaryRow = (id = 'b1') => ({
  id, sessionId: SID, role: 'system', author: 'system', subtype: 'compact_boundary',
  content: 'Conversation compacted', isMeta: false,
})

const messagesOf = () => useChatStore.getState().messages[SID] ?? []

let container: HTMLDivElement | undefined
let root: Root | undefined

function Host() {
  // 真 hook（只换传输层）；第 8 参 = onCompactedReload（第 7 参 onReconnectReload 保持未注册，
  //   证明重拉回调确实来自 compacted 分支而非重连补偿分支）
  useChatSocket(SID, {}, undefined, undefined, undefined, undefined, undefined, onCompactedReload)
  return null
}

describe('[sm-boundary-reload] 前端接线：message.insert 即时插行 + complete.compacted 收尾重拉', () => {
  beforeEach(() => {
    fakeClient.subscriptions.length = 0
    onCompactedReload.mockClear()
    snapshotAtReload.msgs = []
    onCompactedReload.mockImplementation(() => {
      // 回调时刻读 store 快照（重拉是 async，这里读到的是触发点的状态 = 上游顺序的真实证据）
      snapshotAtReload.msgs = useChatStore.getState().messages[SID] ?? []
    })
    useChatStore.setState({ messages: {}, streams: {}, streamOrder: {}, serverRunning: {} })
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
    useChatStore.setState({ messages: {}, streams: {}, streamOrder: {}, serverRunning: {} })
  })

  it('⭐ message.insert → boundary 行落进消息列表尾（分割线即时显示）；同 id 重复到达不产生重复行', () => {
    pushEvent({ type: 'message.insert', messages: [boundaryRow()] })

    // 反向实验：把 useChatSocket 的 isMessageInsert 分支删掉 ⇒ 列表为空 ⇒ 本行红
    let msgs = messagesOf()
    expect(msgs).toHaveLength(1)
    expect(msgs[msgs.length - 1]?.id).toBe('b1')

    // 幂等（即时层 + 重拉/重放双通道）：同 id 再来一条不产生重复行
    pushEvent({ type: 'message.insert', messages: [boundaryRow()] })
    msgs = messagesOf()
    expect(msgs.filter((m) => m.id === 'b1')).toHaveLength(1)
    expect(msgs).toHaveLength(1)
  })

  it('⭐ complete.compacted=true → finalize 之后触发收尾重拉（回调时刻块已定稿）；不带 / false → 不得触发', () => {
    // 不带 compacted（旧后端 / 无压缩轮）：绝不重拉（假重拉 = 无谓请求 + 错误信号）
    pushEvent({ type: 'message.complete', content: 'ok' })
    expect(onCompactedReload).not.toHaveBeenCalled()

    // ⚠️ 显式 false（恒出站 primitive 的常见值）同样不得触发
    pushEvent({ type: 'message.complete', content: 'ok', compacted: false })
    expect(onCompactedReload).not.toHaveBeenCalled()

    // 本轮先有流式块 → complete 到达（压缩落库成功）：收尾重拉按事件 sid 触发
    pushEvent({ type: 'message.chunk', assistantMessageId: BLOCK, delta: '正文' })
    pushEvent({ type: 'message.complete', content: '正文', compacted: true })
    expect(onCompactedReload).toHaveBeenCalledTimes(1)
    expect(onCompactedReload).toHaveBeenCalledWith(SID)
    // 反向实验：把 `if (evt.compacted) …` 那行挪到 finalizeBlocks 之前 ⇒ 快照里还没有 assistant 行 ⇒ 本行红
    expect(snapshotAtReload.msgs.some((m) => m.id === BLOCK)).toBe(true)
    expect(useChatStore.getState().streams[SID] ?? []).toHaveLength(0)   // 流式块已收口（不是半成品态）
  })

  it('message.insert 载荷缺失 / 空数组 → 不插行、不崩（旧后端 / 脏载荷容错 · 覆盖 evt.messages?.length 守卫）', () => {
    pushEvent({ type: 'message.insert' })                 // 旧后端无 messages 字段
    pushEvent({ type: 'message.insert', messages: [] })   // 空数组
    expect(messagesOf()).toHaveLength(0)
  })
})
