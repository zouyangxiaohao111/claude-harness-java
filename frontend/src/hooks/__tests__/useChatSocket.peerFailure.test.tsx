// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Client } from '@stomp/stompjs'
import { useChatStore } from '@/stores/chatStore'
import { useChatSocket } from '@/hooks/useChatSocket'
import { isUserTurnRow, readPeerFailure, readPeerSource } from '@/stores/messageTurns'

/**
 * ⭐ [peer-failure] 实时事件 `peer.message.failed` / `peer.message.rejected` 的**接线**守门。
 *
 * <h2>WHY（规则九 · 测试验证意图）</h2>
 * <p>失败通知的权威来源是发送方会话里落库的 meta 行（刷新后仍在）；STOMP 事件只负责「立即出现」
 * （用户不必等 F5 才知道消息没送到）。两条通道必须收敛到**同一个 id**（`peer-failure-<messageId>`），
 * 否则同一条失败会出现两行。本文件走真 hook + 假 STOMP client，验证：
 * <ol>
 *   <li>两类事件到达 ⇒ 对话流尾部立刻多一条失败通知行（structured_output.peer_failure 齐备）；</li>
 *   <li>同 messageId 重复到达 ⇒ 只有一行（幂等，且与持久行同 id）；</li>
 *   <li>⛔ 不生成用户轮、不触发历史重拉（onReconnectReload 不被调）、不当成来信（无 peer_source）；</li>
 *   <li>缺 messageId 的脏载荷 ⇒ 不插行、不崩。</li>
 * </ol>
 *
 * <h2>RED teeth</h2>
 * <ul>
 *   <li>删掉 useChatSocket 里那两行分支 ⇒ 第 1 条红（列表为空）；</li>
 *   <li>把合成 id 改成随机/uuid ⇒ 第 2 条红（重复事件两行）；</li>
 *   <li>把 row.isMeta 置 false ⇒ 第 3 条 isUserTurnRow 变 true ⇒ 红。</li>
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

const SID = 'sess-sender02'
const STREAM_TOPIC = `/topic/sessions/${SID}/stream`

const onReconnectReload = vi.fn()

function pushEvent(payload: Record<string, unknown>) {
  const sub = fakeClient.subscriptions.find((s) => s.destination === STREAM_TOPIC)
  if (!sub) throw new Error('前置失败：会话 stream topic 未订阅')
  act(() => { sub.cb({ body: JSON.stringify({ sessionId: SID, ...payload }) }) })
}

const messagesOf = () => useChatStore.getState().messages[SID] ?? []

let container: HTMLDivElement | undefined
let root: Root | undefined

function Host() {
  useChatSocket(SID, {}, undefined, undefined, undefined, undefined, onReconnectReload)
  return null
}

describe('[peer-failure] 实时事件 → 对话流尾部失败通知行（幂等、不拉历史）', () => {
  beforeEach(() => {
    fakeClient.subscriptions.length = 0
    onReconnectReload.mockClear()
    useChatStore.setState({ messages: {}, streams: {}, streamOrder: {}, serverRunning: {} })
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
    useChatStore.setState({ messages: {}, streams: {}, streamOrder: {}, serverRunning: {} })
  })

  it('peer.message.failed → 尾部插一条失败通知行；同 messageId 重复到达只有一行', () => {
    // onConnect 自身会做一次「重连补齐」重拉（既有行为）⇒ 清掉基线，只钉「事件不触发重拉」
    onReconnectReload.mockClear()
    pushEvent({
      type: 'peer.message.failed',
      messageId: 'm-aaa',
      targetSessionId: 'sess-target',
      targetTitle: '审计报告',
      reason: 'expired',
      text: 'Your held message expired without approval …',
    })

    let msgs = messagesOf()
    expect(msgs).toHaveLength(1)
    const row = msgs[0]!
    expect(row.id).toBe('peer-failure-m-aaa')
    expect(row.subtype).toBe('agent_message')
    expect(row.isMeta).toBe(true)
    expect(readPeerSource(row)).toBeNull()          // ⛔ 不是来信（无 peer_source）
    expect(readPeerFailure(row)).toMatchObject({
      messageId: 'm-aaa', reason: 'expired',
      receiverSessionId: 'sess-target', receiverTitle: '审计报告',
    })
    // 不是用户轮（isMeta + agent_message）
    expect(isUserTurnRow(row)).toBe(false)
    expect(onReconnectReload).not.toHaveBeenCalled()   // 不拉历史

    pushEvent({
      type: 'peer.message.failed',
      messageId: 'm-aaa',
      targetSessionId: 'sess-target',
      targetTitle: '审计报告',
      reason: 'expired',
    })
    msgs = messagesOf()
    expect(msgs).toHaveLength(1)
  })

  it('peer.message.rejected → 失败通知行（reason 原值保留 user-declined）', () => {
    onReconnectReload.mockClear()
    pushEvent({
      type: 'peer.message.rejected',
      messageId: 'm-bbb',
      targetSessionId: 'sess-target',
      targetTitle: null,
      reason: 'user-declined',
      text: '你发送给「sess-target」的消息 m-bbb 已被拒绝（用户拒绝）。',
    })
    const msgs = messagesOf()
    expect(msgs).toHaveLength(1)
    expect(readPeerFailure(msgs[0]!)?.reason).toBe('user-declined')
    expect(readPeerFailure(msgs[0]!)?.receiverTitle).toBeNull()
    expect(onReconnectReload).not.toHaveBeenCalled()
  })

  it('缺 messageId 的脏载荷 → 不插行、不崩', () => {
    pushEvent({ type: 'peer.message.failed', reason: 'expired' })
    pushEvent({ type: 'peer.message.rejected', messageId: '' })
    expect(messagesOf()).toHaveLength(0)
  })
})
