// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Client } from '@stomp/stompjs'
import { subscribePermTopics } from '@/hooks/useChatSocket'
import { useChatStore } from '@/stores/chatStore'

/**
 * [D 未送达上抛] `/permission-delivery` 订阅 → 把「哪些渠道没送出去」贴到对应权限卡。
 *
 * <p><b>WHY</b>：微信缺 {@code context_token} 时权限通知会被 SKIPPED，用户此前只在
 * 后端日志里能看到。后端现在把失败渠道单独推来（⛔ 不是决策事件）。
 *
 * <p>锁死三件事：
 * <ol>
 *   <li>失败信息按 `toolUseId ?? requestId` 关联到**已存在**的卡片，卡片仍在队列里
 *       （pending 保留 = 未送达 ≠ 拒绝）；</li>
 *   <li>找不到卡片 ⇒ 忽略，**绝不新建**一张没有工具上下文的空卡；</li>
 *   <li>非法载荷（空 failures / 无 requestId）⇒ 队列不变。</li>
 * </ol>
 */

const TOPIC = {
  message: (sid: string) => `/topic/sessions/${sid}/permission-requests`,
  delivery: (sid: string) => `/topic/sessions/${sid}/permission-delivery`,
} as const

function fakeClient() {
  const handlers = new Map<string, (msg: { body: string }) => void>()
  const client = {
    subscribe(topic: string, cb: (msg: { body: string }) => void) {
      handlers.set(topic, cb)
      return { unsubscribe: () => { handlers.delete(topic) } }
    },
    publish: vi.fn(),
  }
  return { client: client as unknown as Client, handlers }
}

function mount(sid: string) {
  const { client, handlers } = fakeClient()
  subscribePermTopics(client, sid)
  return handlers
}

function feedMessage(handlers: Map<string, (msg: { body: string }) => void>, sid: string,
                     requestId: string, toolUseId: string) {
  const cb = handlers.get(TOPIC.message(sid))
  expect(cb, '未订阅 message topic').toBeTruthy()
  cb!({
    body: JSON.stringify({
      sessionId: sid,
      requestId,
      toolUseId,
      toolName: 'Bash',
      description: '运行命令',
      toolInput: { command: 'ls' },
      timestampMs: 1,
    }),
  })
}

function feedDelivery(handlers: Map<string, (msg: { body: string }) => void>, sid: string,
                      payload: Record<string, unknown>) {
  const cb = handlers.get(TOPIC.delivery(sid))
  expect(cb, '未订阅 permission-delivery topic').toBeTruthy()
  cb!({ body: JSON.stringify(payload) })
}

beforeEach(() => {
  useChatStore.setState({ permissionQueue: [] })
})

describe('[D] 未送达提示贴到权限卡', () => {
  it('message 先到 + delivery 后到 ⇒ 卡片带上失败渠道，且仍在队列里（pending 保留）', () => {
    const sid = 'sess-delivery-a'
    const handlers = mount(sid)
    feedMessage(handlers, sid, 'req-a', 'tu-a')

    feedDelivery(handlers, sid, {
      requestId: 'req-a',
      toolUseId: 'tu-a',
      failures: [{ channel: 'wechat', peerName: '张三', reasonCode: 'IM_REPLY_TOKEN_MISSING', reason: '缺少令牌' }],
    })

    const q = useChatStore.getState().permissionQueue
    expect(q).toHaveLength(1)
    expect(q[0].requestId).toBe('req-a')
    expect(q[0].deliveryFailures).toHaveLength(1)
    expect(q[0].deliveryFailures![0].reasonCode).toBe('IM_REPLY_TOKEN_MISSING')
    expect(q[0].deliveryFailures![0].channel).toBe('wechat')
  })

  it('卡片不存在 ⇒ 忽略（⛔ 不凭空新建一张卡）', () => {
    const sid = 'sess-delivery-b'
    const handlers = mount(sid)

    feedDelivery(handlers, sid, {
      requestId: 'req-unknown',
      toolUseId: 'req-unknown',
      failures: [{ channel: 'wechat', reasonCode: 'IM_REPLY_TOKEN_MISSING' }],
    })

    expect(useChatStore.getState().permissionQueue).toHaveLength(0)
  })

  it('空 failures / 缺 requestId ⇒ 队列不变（不刷无意义状态）', () => {
    const sid = 'sess-delivery-c'
    const handlers = mount(sid)
    feedMessage(handlers, sid, 'req-c', 'tu-c')

    feedDelivery(handlers, sid, { requestId: 'req-c', toolUseId: 'tu-c', failures: [] })
    feedDelivery(handlers, sid, { failures: [{ channel: 'wechat' }] })

    const q = useChatStore.getState().permissionQueue
    expect(q).toHaveLength(1)
    expect(q[0].deliveryFailures ?? null).toBeNull()
  })
})
