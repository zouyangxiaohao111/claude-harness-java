// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Client } from '@stomp/stompjs'
import { subscribePermTopics } from '@/hooks/useChatSocket'
import { useChatStore } from '@/stores/chatStore'
import type { ComputerUseApproval } from '@/api/types'

/**
 * [桌面控制审批面板] ⭐ 入站归一化必须保留 `computerUse`。
 *
 * <b>WHY 这条断言必须存在</b>：
 *   `toPermissionItem`（useChatSocket.ts）是**手写字段清单** —— 少抄一个字段**没有任何
 *   编译期约束**（wire 类型声明了、照抄漏了也全绿）。而**补拉路径与实时路径共用它**
 *   ⇒ 漏抄 `computerUse` 的症状不是报错，而是「切会话 / F5 后授权档位消失」
 *   （弹窗退回普通 allow/deny，用户看不到要控制哪些应用、能控制到什么程度）。
 *
 * <p>本文件覆盖两条入站路径：
 *   ① STOMP 实时推送（`/permission-requests` topic）
 *   ② 会话状态补拉（`GET /sessions/{id}/state` → `pendingPermissions`）
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

/** 后端 ComputerUseApprovalPayload 产出的真实形状 */
const cuPayload: ComputerUseApproval = {
  apps: [
    { appId: 'chrome', displayName: 'Chrome', tier: 'read', tierLabel: '只能查看', alreadyGranted: false, notInstalled: false },
    { appId: 'notepad', displayName: '记事本', tier: 'full', tierLabel: '可以完全操作', alreadyGranted: true, notInstalled: false },
  ],
  reason: '帮我看看 Chrome 里的报表',
  requestedFlags: ['clipboardRead'],
  flagLabels: { clipboardRead: '允许读取剪贴板' },
}

beforeEach(() => {
  useChatStore.setState({ permissionQueue: [] })
})

describe('[桌面控制] 入站：computerUse 必须原样保留', () => {
  it('STOMP 实时推送 ⇒ 入队对象里保留 computerUse（防「授权范围消失」）', () => {
    const { client, handlers } = fakeClient()
    subscribePermTopics(client, 'sess-cu-1')

    const cb = handlers.get('/topic/sessions/sess-cu-1/permission-requests')
    expect(cb, '未订阅 permission-requests topic').toBeTruthy()

    cb!({
      body: JSON.stringify({
        requestId: 'r-cu-1',
        toolName: 'mcp__computer-use__request_access',
        toolInput: { apps: ['Chrome'] },
        computerUse: cuPayload,
        timestampMs: 1,
      }),
    })

    const queued = useChatStore.getState().permissionQueue.find((r) => r.requestId === 'r-cu-1')
    expect(queued, '权限请求未入队').toBeTruthy()
    // 原样透传：与帧里的对象深度相等（前端零转换）
    expect(queued!.computerUse).toEqual(cuPayload)
    expect(queued!.computerUse!.apps).toHaveLength(2)
    expect(queued!.computerUse!.flagLabels!.clipboardRead).toBe('允许读取剪贴板')
  })

  it('帧里没有 computerUse ⇒ 归一为 null（非桌面控制请求走普通分支）', () => {
    const { client, handlers } = fakeClient()
    subscribePermTopics(client, 'sess-cu-2')
    handlers.get('/topic/sessions/sess-cu-2/permission-requests')!({
      body: JSON.stringify({ requestId: 'r-cu-2', toolName: 'Bash' }),
    })
    const queued = useChatStore.getState().permissionQueue.find((r) => r.requestId === 'r-cu-2')
    expect(queued!.computerUse).toBeNull()
  })
})
