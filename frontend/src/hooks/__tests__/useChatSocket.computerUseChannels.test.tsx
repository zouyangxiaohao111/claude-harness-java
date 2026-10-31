// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Client } from '@stomp/stompjs'
import { subscribePermTopics } from '@/hooks/useChatSocket'
import { PermissionBubble } from '@/components/center/PermissionBubble'
import { useChatStore } from '@/stores/chatStore'
import type { ComputerUseApproval } from '@/api/types'

/**
 * ⭐ [桌面控制审批面板 · 三通道一致性] message / bridge / channel **三条权限通道**都必须
 * 把后端算好的 `computerUse`（应用清单 + 授权范围文案）原样带到审批面板。
 *
 * <b>WHY 这条断言必须存在</b>：
 *   ① 归一化是**手写字段清单**（`toPermissionItem`）—— 少抄一个字段**没有任何编译期约束**
 *      （wire 类型声明了、照抄漏了也全绿）。历史上「切会话 / F5 后授权范围消失」就是这个。
 *   ② **三条通道共用同一个归一化函数** ⇒ 一旦漏抄，**三条通道一起坏**（远程表面 / 手机端
 *      收到 CU 审批请求时命中 fail-loud 错误面板 —— 安全但体验差）。
 *   ③ ⛔ fail-loud 判定**只保留一份实现**（`isComputerUseTool`）：三条通道在「授权信息缺失」
 *      时必须**一致地**只给「拒绝 / 中止」、不给「允许」—— 否则某条通道会盲授权。
 *
 * <p>本文件用**真实后端线格式**（三种 topic 的载荷形状不同）逐条驱动订阅回调，
 * 再把入队对象直接喂给 `PermissionBubble` ⇒ 断言渲染出的是**授权面板**（应用名 + 授权档位），
 * **不是** fail-loud 错误面板。
 *
 * <p>环境：jsdom —— `api/base.ts` 在模块加载期读 `location.host`（node 下会抛）。
 */

const CU_TOOL = 'mcp__computer-use__request_access'

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

/** 三条通道的 STOMP topic（后端 StompBridgePermissionCallbacks / StompChannelPermissionCallbacks 的出站 topic） */
const TOPIC = {
  message: (sid: string) => `/topic/sessions/${sid}/permission-requests`,
  bridge: (sid: string) => `/topic/sessions/${sid}/permission-bridge-requests`,
  channel: (sid: string) => `/topic/sessions/${sid}/permission-channel-requests`,
} as const

type Kind = keyof typeof TOPIC

/**
 * 按**各通道真实线格式**造一帧（字段名不同：bridge 走 displayInput、channel 走 inputPreview）。
 *
 * <p>WHY 不用同一个载荷糊弄三条通道：字段名不同正是历史上漏抄的成因 —— 归一化若按
 * 「某一通道的字段名」写死，另外两条就会静默丢字段。
 */
function frameOf(kind: Kind, requestId: string, withComputerUse: boolean): Record<string, unknown> {
  const base = {
    sessionId: 'sess-cu-3ch',
    requestId,
    toolName: CU_TOOL,
    // 后端给的 description 是内部英文（工具描述）⇒ 面板**不得**渲染它（文案纪律）
    description: 'Request user permission to control a set of applications for this session.',
    timestampMs: 1,
  }
  const computerUse = withComputerUse ? { computerUse: cuPayload } : {}
  if (kind === 'message') {
    // MessagePermissionRequestEvent：toolInput + reason + suggestions
    return {
      ...base, type: 'permission.request',
      toolUseId: requestId,
      toolInput: { apps: ['Chrome'] },
      reason: { reason: 'Computer use wants to control Chrome. Reason: 帮我看报表' },
      suggestions: [],
      ...computerUse,
    }
  }
  if (kind === 'bridge') {
    // BridgePermissionRequestEvent：displayInput（非 toolInput）
    return {
      ...base,
      toolUseId: requestId,
      displayInput: { apps: ['Chrome'] },
      suggestions: [],
      blockedPath: null,
      ...computerUse,
    }
  }
  // ChannelPermissionRequestEvent：inputPreview（截断串）· 无 toolInput / 无 displayInput
  return { ...base, inputPreview: '{"apps":["Chrome"]}', ...computerUse }
}

/** 投喂一帧 → 返回入队后的权限请求对象 */
function feed(kind: Kind, sid: string, requestId: string, withComputerUse: boolean) {
  const { client, handlers } = fakeClient()
  subscribePermTopics(client, sid)
  const cb = handlers.get(TOPIC[kind](sid))
  expect(cb, `未订阅 ${kind} topic`).toBeTruthy()
  cb!({ body: JSON.stringify(frameOf(kind, requestId, withComputerUse)) })
  const queued = useChatStore.getState().permissionQueue.find((r) => r.requestId === requestId)
  expect(queued, `${kind} 通道的权限请求未入队`).toBeTruthy()
  return queued!
}

let container: HTMLDivElement
let root: Root

beforeEach(() => {
  ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  useChatStore.setState({ permissionQueue: [] })
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
})

afterEach(() => {
  act(() => root.unmount())
  container.remove()
})

function renderBubble(request: Parameters<typeof PermissionBubble>[0]['request']) {
  act(() => root.render(<PermissionBubble request={request} onDecision={vi.fn()} />))
  return container.textContent ?? ''
}

function buttonLabels(): string[] {
  return Array.from(container.querySelectorAll('button')).map((b) => b.textContent ?? '')
}

describe('[桌面控制] 三条权限通道都必须带 computerUse ⇒ 渲染授权面板（非 fail-loud）', () => {
  it('bridge 通道 ⇒ computerUse 原样保留 + 渲染授权面板（应用名 / 授权档位 / 额外能力）', () => {
    const queued = feed('bridge', 'sess-cu-3ch', 'r-cu-bridge', true)

    // ① 归一化保留（深度相等 = 前端零转换）
    expect(queued.kind).toBe('bridge')
    expect(queued.computerUse).toEqual(cuPayload)
    expect(queued.computerUse!.apps).toHaveLength(2)
    expect(queued.computerUse!.flagLabels!.clipboardRead).toBe('允许读取剪贴板')

    // ② 渲染出的是**授权面板**，不是 fail-loud 错误面板
    const text = renderBubble(queued)
    expect(text).toContain('Chrome')
    expect(text).toContain('记事本')
    expect(text).toContain('只能查看')      // 后端给的 tierLabel 原样渲染
    expect(text).toContain('可以完全操作')
    expect(text).toContain('允许读取剪贴板')
    expect(text).toContain('帮我看看 Chrome 里的报表')
    expect(container.querySelector('.pb-cu-error'), '不应命中 fail-loud 错误面板').toBeNull()
    expect(buttonLabels()).toContain('允许')
    expect(buttonLabels()).toContain('拒绝')

    // ③ 文案纪律：内部术语 / 英文描述 / 归因串不得外泄
    expect(text).not.toContain(CU_TOOL)
    expect(text).not.toContain('Request user permission to control')
    expect(text).not.toContain('Computer use wants to control')
    expect(text).not.toMatch(/\b(read|click|full)\b/)
  })

  it('channel 通道 ⇒ computerUse 原样保留 + 渲染授权面板（应用名 / 授权档位 / 额外能力）', () => {
    const queued = feed('channel', 'sess-cu-3ch', 'r-cu-channel', true)

    expect(queued.kind).toBe('channel')
    expect(queued.computerUse).toEqual(cuPayload)
    expect(queued.computerUse!.apps).toHaveLength(2)
    expect(queued.computerUse!.apps![0].tierLabel).toBe('只能查看')

    const text = renderBubble(queued)
    expect(text).toContain('Chrome')
    expect(text).toContain('记事本')
    expect(text).toContain('只能查看')
    expect(text).toContain('可以完全操作')
    expect(text).toContain('允许读取剪贴板')
    expect(container.querySelector('.pb-cu-error'), '不应命中 fail-loud 错误面板').toBeNull()
    expect(buttonLabels()).toContain('允许')
    expect(buttonLabels()).toContain('拒绝')

    expect(text).not.toContain(CU_TOOL)
    expect(text).not.toContain('Request user permission to control')
    expect(text).not.toMatch(/\b(read|click|full)\b/)
  })

  it('message 通道（对照）⇒ 同样保留 computerUse + 渲染授权面板', () => {
    const queued = feed('message', 'sess-cu-3ch', 'r-cu-message', true)
    expect(queued.kind).toBe('message')
    expect(queued.computerUse).toEqual(cuPayload)
    const text = renderBubble(queued)
    expect(text).toContain('只能查看')
    expect(container.querySelector('.pb-cu-error')).toBeNull()
    expect(buttonLabels()).toContain('允许')
  })
})

describe('[桌面控制] ⛔ 三条通道的 fail-loud 判定一致（授权信息缺失 ⇒ 一律不给「允许」）', () => {
  const kinds: Kind[] = ['message', 'bridge', 'channel']

  it.each(kinds)('%s 通道缺 computerUse ⇒ 命中 fail-loud：无「允许」按钮 + 有错误提示', (kind) => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const queued = feed(kind, 'sess-cu-3ch', `r-cu-nocu-${kind}`, false)

    // 归一为 null ⇒ 不带授权信息
    expect(queued.computerUse).toBeNull()

    renderBubble(queued)
    expect(buttonLabels(), `${kind} 通道不得出现「允许」按钮`).not.toContain('允许')
    expect(buttonLabels()).toContain('拒绝')
    expect(container.querySelector('.pb-cu-error')).toBeTruthy()
    expect(container.textContent).toContain('无法确认这次要授权的范围')

    warn.mockRestore()
  })
})
