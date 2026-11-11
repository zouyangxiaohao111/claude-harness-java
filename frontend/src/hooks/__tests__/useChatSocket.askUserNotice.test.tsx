// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Client } from '@stomp/stompjs'
import { subscribePermTopics } from '@/hooks/useChatSocket'
import { useChatStore } from '@/stores/chatStore'
import { resetAskUserQuestionNotified } from '@/utils/askUserNotice'

/**
 * ⭐ [待回答提示] 权限请求入队 → **真的发系统通知**，且**只发一次**。
 *
 * <b>WHY 这组断言必须存在</b>：
 * <ol>
 *   <li>⚠️ <b>通知链路是静默的</b> —— 判定写错、tag 写错、去重漏了，都<b>不会报错</b>，
 *       只会表现为「右下角没提示」或「连弹三条」。用户只能靠肉眼发现 ⇒ 必须有断言钉住。</li>
 *   <li>⚠️ <b>同一次提问会被四条路径重复送达</b>（实时 message / bridge / channel 三通道
 *       + REST 补拉）⇒ 不去重就会连着弹 N 条一模一样的系统通知。</li>
 *   <li>⚠️ <b>tag 必须与轮次通知隔离</b>：默认 tag（{@code nexusai-turn-done}）在 Web 通道
 *       同 tag 会互相替换 ⇒ 用默认 tag 发提问通知会把「本轮已完成」顶掉。</li>
 * </ol>
 *
 * <p>环境：jsdom —— `api/base.ts` 在模块加载期读 `location.host`（node 下会抛）。
 */

const { sendSystemNotificationMock } = vi.hoisted(() => ({
  sendSystemNotificationMock: vi.fn(async (_payload: { title: string; body?: string; tag?: string }) => true),
}))
vi.mock('@/utils/systemNotify', () => ({ sendSystemNotification: sendSystemNotificationMock }))

/** 假 STOMP client：记录每个 topic 的回调，供测试直接投喂消息 */
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

const TOPIC = {
  message: (sid: string) => `/topic/sessions/${sid}/permission-requests`,
  bridge: (sid: string) => `/topic/sessions/${sid}/permission-bridge-requests`,
  channel: (sid: string) => `/topic/sessions/${sid}/permission-channel-requests`,
} as const

type Kind = keyof typeof TOPIC

/** 提问型载荷（后端 AskUserQuestion 的 toolInput 形状） */
function askFrame(requestId: string): Record<string, unknown> {
  return {
    type: 'permission.request',
    sessionId: 'sess-ask-1',
    requestId,
    toolUseId: requestId,
    toolName: 'AskUserQuestion',
    toolInput: {
      questions: [
        { question: '用哪个方案？', header: '方案', options: [{ label: 'A', description: '甲' }, { label: 'B', description: '乙' }] },
      ],
    },
    reason: { reason: 'AskUserQuestion checkPermissions ask' },
    timestampMs: 1,
  }
}

/** 普通权限请求载荷（Bash 之类，无 questions） */
function plainFrame(requestId: string): Record<string, unknown> {
  return {
    type: 'permission.request',
    sessionId: 'sess-ask-1',
    requestId,
    toolUseId: requestId,
    toolName: 'Bash',
    toolInput: { command: 'ls -la' },
    timestampMs: 1,
  }
}

function feed(kind: Kind, sid: string, frame: Record<string, unknown>) {
  const { client, handlers } = fakeClient()
  subscribePermTopics(client, sid)
  const cb = handlers.get(TOPIC[kind](sid))
  expect(cb, `未订阅 ${kind} topic`).toBeTruthy()
  cb!({ body: JSON.stringify(frame) })
}

beforeEach(() => {
  sendSystemNotificationMock.mockClear()
  resetAskUserQuestionNotified()
  useChatStore.setState({ permissionQueue: [], sessions: [] })
})

describe('权限入队 → 提问类请求发系统通知', () => {
  it('AskUserQuestion 入队 → 发一条通知，文案带问题摘要', () => {
    feed('message', 'sess-ask-1', askFrame('r-1'))
    expect(useChatStore.getState().permissionQueue).toHaveLength(1)
    expect(sendSystemNotificationMock).toHaveBeenCalledTimes(1)
    const payload = sendSystemNotificationMock.mock.calls[0]![0]
    expect(payload.title).toContain('需要你回答')
    expect(payload.body).toContain('用哪个方案？')
  })

  it('⚠️ 通知用独立 tag（不能与轮次通知共用，否则会互相顶掉）', () => {
    feed('message', 'sess-ask-1', askFrame('r-1'))
    const payload = sendSystemNotificationMock.mock.calls[0]![0]
    expect(payload.tag).toBe('nexusai-ask-user')
    expect(payload.tag).not.toBe('nexusai-turn-done')
  })

  it('⚠️ 普通权限请求（Bash）→ 不发通知（会刷屏）', () => {
    feed('message', 'sess-ask-1', plainFrame('r-plain'))
    expect(useChatStore.getState().permissionQueue).toHaveLength(1)
    expect(sendSystemNotificationMock).not.toHaveBeenCalled()
  })
})

describe('⚠️ 去重：同一次提问只通知一次', () => {
  it('同一 requestId 重复到达（watchSubs + permSubs 两条 message 通道）→ 只发一条', () => {
    feed('message', 'sess-ask-1', askFrame('r-dup'))
    feed('message', 'sess-ask-1', askFrame('r-dup'))
    expect(useChatStore.getState().permissionQueue).toHaveLength(1)
    expect(sendSystemNotificationMock).toHaveBeenCalledTimes(1)
  })

  it('同一 requestId 走三条通道（message / bridge / channel）→ 只发一条', () => {
    feed('message', 'sess-ask-1', askFrame('r-3ch'))
    feed('bridge', 'sess-ask-1', askFrame('r-3ch'))
    feed('channel', 'sess-ask-1', askFrame('r-3ch'))
    expect(sendSystemNotificationMock).toHaveBeenCalledTimes(1)
  })

  it('两个不同的提问请求 → 各自一条通知（不能误吞）', () => {
    feed('message', 'sess-ask-1', askFrame('r-a'))
    feed('message', 'sess-ask-1', askFrame('r-b'))
    expect(sendSystemNotificationMock).toHaveBeenCalledTimes(2)
  })

  it('⚠️ 已处理（出队）后同一 requestId 再到达 → 仍不重复通知', () => {
    // 这才是**第二层去重**（通知去重表）真正生效的场景：
    //   message 事件到达 → 入队 → 用户回答 → 出队；
    //   随后 bridge 事件才到达 —— 此时队列里**已无**该项，第一层（队列去重）挡不住，
    //   只有第二层（按 requestId 的通知去重表）能挡住重复通知。
    // ⛔ 若只测「重复到达时队列里还有该项」，测的是第一层 ⇒ 第二层坏了也测不出来（假绿）。
    feed('message', 'sess-ask-1', askFrame('r-handled'))
    expect(sendSystemNotificationMock).toHaveBeenCalledTimes(1)
    // 模拟用户已回答 → 出队
    useChatStore.getState().dequeuePermission('r-handled')
    expect(useChatStore.getState().permissionQueue).toHaveLength(0)
    // bridge 事件姗姗来迟 → 会重新入队，但**不应**再发通知
    feed('bridge', 'sess-ask-1', askFrame('r-handled'))
    expect(sendSystemNotificationMock).toHaveBeenCalledTimes(1)
  })
})
