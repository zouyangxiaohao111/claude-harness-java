// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Client } from '@stomp/stompjs'
import { sendPermissionResponse } from '@/api/socket'
import { subscribePermTopics } from '@/hooks/useChatSocket'
import { useChatStore } from '@/stores/chatStore'
import type { PermissionUpdate } from '@/api/types'

/**
 * [批 A3] 权限更新的**两端**：入站归一化（suggestions 能否到达弹窗）+ 出站回传
 * （updatedPermissions 是否原样发出）。
 *
 * <b>WHY 这组断言必须存在</b>：
 *   1. 归一化是**手写字段清单**（`enqueuePermission({...})`）—— 少抄一个字段<b>没有任何
 *      编译期约束</b>（后端推了、wire 类型也声明了，照样静默丢）。历史上的「始终允许
 *      永远不出现」就是这个漏字段造成的。
 *   2. 出站若自行**转换** PermissionUpdate 结构，等于引入第二份实现、必与后端漂移。
 *      断言「发出去的对象与收到的是同一个引用」才能钉死「原样透传」。
 *
 * 环境：jsdom —— `api/base.ts` 在模块加载期读 `location.host`（node 下会抛）。
 */

/** 假 STOMP client：记录每个 topic 的回调，供测试直接投喂消息 */
function fakeClient() {
  const handlers = new Map<string, (msg: { body: string }) => void>()
  const publish = vi.fn()
  const client = {
    subscribe(topic: string, cb: (msg: { body: string }) => void) {
      handlers.set(topic, cb)
      return { unsubscribe: () => { handlers.delete(topic) } }
    },
    publish,
  }
  return { client: client as unknown as Client, handlers, publish }
}

/** 后端真实的 suggestion 形态（AddRules/localSettings，ruleContent = 前缀） */
const bashSuggestion: PermissionUpdate = {
  type: 'addRules',
  rules: [{ toolName: 'Bash', ruleContent: 'npm run:*' }],
  behavior: 'allow',
  destination: 'localSettings',
}

beforeEach(() => {
  useChatStore.setState({ permissionQueue: [] })
})

describe('[批 A3] 入站：suggestions 从 STOMP 帧到达弹窗数据源', () => {
  it('permission-requests 帧携带 suggestions ⇒ 入队对象里原样保留（不再被字段清单漏掉）', () => {
    const { client, handlers } = fakeClient()
    subscribePermTopics(client, 'sess-1')

    const cb = handlers.get('/topic/sessions/sess-1/permission-requests')
    expect(cb, '未订阅 permission-requests topic').toBeTruthy()

    cb!({
      body: JSON.stringify({
        requestId: 'r1', toolName: 'Bash', description: 'd',
        toolInput: { command: 'npm run build' },
        suggestions: [bashSuggestion],
        timestampMs: 1,
      }),
    })

    const queued = useChatStore.getState().permissionQueue.find((r) => r.requestId === 'r1')
    expect(queued, '权限请求未入队').toBeTruthy()
    // 原样透传：与帧里的对象深度相等（后端已按 CC 形状序列化，前端零转换）
    expect(queued!.suggestions).toEqual([bashSuggestion])
  })

  it('帧里没有 suggestions ⇒ 归一为 null（弹窗据此不渲染第三档）', () => {
    const { client, handlers } = fakeClient()
    subscribePermTopics(client, 'sess-2')
    handlers.get('/topic/sessions/sess-2/permission-requests')!({
      body: JSON.stringify({ requestId: 'r2', toolName: 'Bash' }),
    })
    const queued = useChatStore.getState().permissionQueue.find((r) => r.requestId === 'r2')
    expect(queued!.suggestions).toBeNull()
  })

  it('suggestions 为空数组 ⇒ 归一为 null（空建议不产生空按钮）', () => {
    const { client, handlers } = fakeClient()
    subscribePermTopics(client, 'sess-3')
    handlers.get('/topic/sessions/sess-3/permission-requests')!({
      body: JSON.stringify({ requestId: 'r3', toolName: 'Bash', suggestions: [] }),
    })
    expect(useChatStore.getState().permissionQueue.find((r) => r.requestId === 'r3')!.suggestions).toBeNull()
  })
})

describe('[批 A3] 出站：updatedPermissions 原样回传', () => {
  it('带 permissionUpdates ⇒ payload 含 updatedPermissions，且结构与对象引用原样', () => {
    const { client, publish } = fakeClient()
    sendPermissionResponse(client, 'sess-1', 'message', 'r1', 'allow', { permissionUpdates: [bashSuggestion] })

    expect(publish).toHaveBeenCalledTimes(1)
    const frame = publish.mock.calls[0][0] as { destination: string; body: string }
    expect(frame.destination).toBe('/app/sessions/sess-1/permission-response')

    const body = JSON.parse(frame.body)
    expect(body.requestId).toBe('r1')
    expect(body.decision).toBe('allow')
    expect(body.updatedPermissions).toEqual([bashSuggestion])
    // 逐字钉住 destination 字面量：若被"好心"改写成大写枚举名，本条立即红
    expect(body.updatedPermissions[0].destination).toBe('localSettings')
    expect(body.updatedPermissions[0].type).toBe('addRules')
  })

  it('不带 permissionUpdates ⇒ payload 没有该键（普通允许/拒绝不产生任何规则）', () => {
    const { client, publish } = fakeClient()
    sendPermissionResponse(client, 'sess-1', 'message', 'r1', 'allow', { answers: undefined, annotations: undefined })
    const body = JSON.parse((publish.mock.calls[0][0] as { body: string }).body)
    expect('updatedPermissions' in body).toBe(false)
  })

  it('permissionUpdates 为空数组 ⇒ 同样不发该键（与后端 NON_NULL 语义对齐）', () => {
    const { client, publish } = fakeClient()
    sendPermissionResponse(client, 'sess-1', 'message', 'r1', 'allow', { permissionUpdates: [] })
    const body = JSON.parse((publish.mock.calls[0][0] as { body: string }).body)
    expect('updatedPermissions' in body).toBe(false)
  })
})
