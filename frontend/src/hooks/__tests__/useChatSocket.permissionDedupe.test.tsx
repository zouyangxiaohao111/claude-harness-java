// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Client } from '@stomp/stompjs'
import { subscribePermTopics } from '@/hooks/useChatSocket'
import { useChatStore, type PermissionRequestItem } from '@/stores/chatStore'

/**
 * [P0] 一次权限请求会向 message / bridge / channel 三条 topic 各推一份事件，
 * 三份的 **requestId 各不相同**（message=工具调用 id、bridge=随机 UUID、channel=5 位短 ID）。
 *
 * <p><b>WHY 必须按 toolUseId 关联</b>（真机实测失败）：前端若按 requestId 去重/结算，
 * 三条事件会堆三张卡；用户点一次「允许」只删掉 owner 那张，channel/bridge 副本永久残留
 * ⇒ 卡片不消失、输入框一直锁着（要连点两次「允许」）。
 *
 * <p>本文件锁死三件事：
 * <ol>
 *   <li><b>入队去重</b>：三条事件（同一 toolUseId）进队列后 = **1 条**（message 优先）；</li>
 *   <li><b>结算清全部</b>：permission-resolved 按 `toolUseId ?? requestId` 清掉**全部副本**
 *       （桌面 / 渠道 / Stop 三路径都走同一个 resolved 事件）；</li>
 *   <li><b>会话隔离</b>：清 A 会话不误伤 B 会话中同 toolUseId 的副本。</li>
 * </ol>
 *
 * <p>⚠️ 变异判据：把 `enqueuePermission` 的关联键退回 requestId ⇒ 用例 1 立刻变红（长度 3）；
 * 把 resolved 退回 `dequeuePermission(requestId)` ⇒ 用例 2 变红（残留 2 条）。
 */

const TOPIC = {
  message: (sid: string) => `/topic/sessions/${sid}/permission-requests`,
  bridge: (sid: string) => `/topic/sessions/${sid}/permission-bridge-requests`,
  channel: (sid: string) => `/topic/sessions/${sid}/permission-channel-requests`,
  resolved: (sid: string) => `/topic/sessions/${sid}/permission-resolved`,
} as const

type Kind = 'message' | 'bridge' | 'channel'

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

/** 按各通道真实线格式造帧（字段名不同：bridge=displayInput / channel=inputPreview）。 */
function frameOf(kind: Kind, sid: string, requestId: string, toolUseId: string | null) {
  const base: Record<string, unknown> = {
    sessionId: sid,
    requestId,
    toolName: 'Bash',
    description: '运行命令',
    timestampMs: 1,
  }
  if (toolUseId != null) base.toolUseId = toolUseId
  if (kind === 'message') return { ...base, toolInput: { command: 'mkdir -p /tmp/x' } }
  if (kind === 'bridge') return { ...base, displayInput: { command: 'mkdir -p /tmp/x' } }
  return { ...base, inputPreview: '{"command":"mkdir -p /tmp/x"}' }
}

function feed(
  handlers: Map<string, (msg: { body: string }) => void>,
  kind: Kind,
  sid: string,
  requestId: string,
  toolUseId: string | null,
) {
  const cb = handlers.get(TOPIC[kind](sid))
  expect(cb, `未订阅 ${kind} topic`).toBeTruthy()
  cb!({ body: JSON.stringify(frameOf(kind, sid, requestId, toolUseId)) })
}

function feedResolved(
  handlers: Map<string, (msg: { body: string }) => void>,
  sid: string,
  requestId: string,
  toolUseId: string | null,
  source = 'user',
) {
  const cb = handlers.get(TOPIC.resolved(sid))
  expect(cb, '未订阅 permission-resolved topic').toBeTruthy()
  cb!({ body: JSON.stringify({ requestId, toolUseId, source }) })
}

/** 一次请求的三份副本（模拟旧行为：三条事件都进过队列）。 */
function threeCopies(sid: string, toolUseId: string): PermissionRequestItem[] {
  return [
    { kind: 'message', sessionId: sid, requestId: 'call_relay_1', toolUseId, toolName: 'Bash' },
    { kind: 'bridge', sessionId: sid, requestId: 'uuid-bridge-1', toolUseId, toolName: 'Bash' },
    { kind: 'channel', sessionId: sid, requestId: 'jvcsa', toolUseId, toolName: 'Bash' },
  ]
}

function mount(sid: string) {
  const { client, handlers } = fakeClient()
  subscribePermTopics(client, sid)
  return handlers
}

beforeEach(() => {
  useChatStore.setState({ permissionQueue: [] })
})

describe('[P0] 三条权限事件按 toolUseId 收敛为一条', () => {
  it('message → bridge → channel（同一 toolUseId、不同 requestId）⇒ 队列长度 1，且保留 message', () => {
    const sid = 'sess-perm-dedupe-order1'
    const handlers = mount(sid)
    const T = 'call_relay_1700000000000_1_0'

    feed(handlers, 'message', sid, T, T)
    feed(handlers, 'bridge', sid, 'uuid-b-1', T)
    feed(handlers, 'channel', sid, 'abcde', T)

    const q = useChatStore.getState().permissionQueue
    expect(q).toHaveLength(1)
    expect(q[0].kind).toBe('message')
    expect(q[0].toolUseId).toBe(T)
  })

  it('bridge → channel → message（乱序）⇒ 仍为 1 条，message 后到也能顶掉非 message 副本', () => {
    const sid = 'sess-perm-dedupe-order2'
    const handlers = mount(sid)
    const T = 'call_relay_1700000000000_2_0'

    feed(handlers, 'bridge', sid, 'uuid-b-2', T)
    feed(handlers, 'channel', sid, 'fghij', T)
    // 无 message 时两条非 message 副本也应只有 1 条
    expect(useChatStore.getState().permissionQueue).toHaveLength(1)
    expect(useChatStore.getState().permissionQueue[0].kind).toBe('channel')

    feed(handlers, 'message', sid, T, T)
    const q = useChatStore.getState().permissionQueue
    expect(q).toHaveLength(1)
    expect(q[0].kind).toBe('message')
  })

  it('缺 toolUseId（老后端）⇒ 退回 requestId 去重（同上 requestId 的两条 message 仍只留 1 条）', () => {
    const sid = 'sess-perm-dedupe-old'
    const handlers = mount(sid)
    feed(handlers, 'message', sid, 'r-old', null)
    feed(handlers, 'message', sid, 'r-old', null)
    expect(useChatStore.getState().permissionQueue).toHaveLength(1)
  })
})

describe('[P0] permission-resolved 按同一关联键清全部副本', () => {
  it.each(['user', 'channel', 'abort'] as const)(
    'source=%s ⇒ 队列里 3 份副本全部清空（卡片消失、输入框解锁）',
    (source) => {
      const sid = `sess-perm-resolve-${source}`
      const handlers = mount(sid)
      const T = 'call_relay_1700000000000_3_0'
      useChatStore.setState({ permissionQueue: threeCopies(sid, T) })

      feedResolved(handlers, sid, T, T, source)

      const q = useChatStore.getState().permissionQueue
      expect(q).toHaveLength(0)
      // App 的 pendingSessionIds / Composer 封锁都由该会话的队列长度派生 ⇒ 0 即解锁
      expect(q.filter((r) => r.sessionId === sid)).toHaveLength(0)
    },
  )

  it('重复 resolved（网络 dup）⇒ 仍然为 0，不会把卡片复活', () => {
    const sid = 'sess-perm-resolve-dup'
    const handlers = mount(sid)
    const T = 'call_relay_1700000000000_4_0'
    useChatStore.setState({ permissionQueue: threeCopies(sid, T) })

    feedResolved(handlers, sid, T, T)
    feedResolved(handlers, sid, T, T)

    expect(useChatStore.getState().permissionQueue).toHaveLength(0)
  })

  it('缺 toolUseId 的 resolved ⇒ 仍能按 requestId 清掉 owner 项（老后端兼容）', () => {
    const sid = 'sess-perm-resolve-old'
    const handlers = mount(sid)
    feed(handlers, 'message', sid, 'r-old-2', null)

    feedResolved(handlers, sid, 'r-old-2', null)

    expect(useChatStore.getState().permissionQueue).toHaveLength(0)
  })
})

describe('[P0] 切会话不解锁错会话', () => {
  it('清 A 会话（toolUseId 相同）⇒ B 会话的副本原样保留', () => {
    const A = 'sess-perm-iso-a'
    const B = 'sess-perm-iso-b'
    const T = 'call_relay_1700000000000_5_0'
    const handlers = mount(A)
    // 两个会话各自订阅（B 的 resolved topic 用同一个 fake handlers 流也能收到）
    const handlersB = fakeClient()
    subscribePermTopics(handlersB.client, B)

    useChatStore.setState({
      permissionQueue: [
        { kind: 'message', sessionId: A, requestId: 'a-1', toolUseId: T, toolName: 'Bash' },
        { kind: 'message', sessionId: B, requestId: 'b-1', toolUseId: T, toolName: 'Bash' },
      ],
    })

    feedResolved(handlers, A, 'a-1', T)

    const q = useChatStore.getState().permissionQueue
    expect(q.filter((r) => r.sessionId === A)).toHaveLength(0)
    expect(q.filter((r) => r.sessionId === B)).toHaveLength(1)
  })
})
