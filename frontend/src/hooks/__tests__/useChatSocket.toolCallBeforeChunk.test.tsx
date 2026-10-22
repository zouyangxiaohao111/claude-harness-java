// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Client } from '@stomp/stompjs'
import { useChatStore } from '@/stores/chatStore'
import { useChatSocket } from '@/hooks/useChatSocket'

/**
 * ⭐ [2026-10-22] 工具卡片静默丢失：`tool_call` 先于本轮首个 chunk 到达时必须仍能挂上。
 *
 * <b>缺陷</b>：流式块只由 chunk 分支的 `ensureStreamBlock` 建立，而 `chatStore.addToolCall`
 * 在「找不到目标块」时直接 `return st`（防挂错轮的既有语义）。当模型直接调工具（纯工具轮 /
 * 首帧就是 tool_call / chunk 仍在 rAF 缓冲未 flush）时，块尚不存在 ⇒ 卡片被丢弃且**无重放**，
 * 界面缺这张工具卡，直到 F5 从 REST 拉历史才出现（用户实测：「Bash 在跑，但前端没有那张卡」）。
 *
 * <b>修法</b>（本文件锁死）：tool_call 分支先 `flushStreamAppends()`（让已到达的 chunk 落块），
 * 再 `ensureStreamBlock(sid, 事件自身 assistantMessageId)`（幂等；不存在则按事件 id 建块 =
 * 正确轮次），最后 `addToolCall`。
 *
 * <b>反向实验（必须红）</b>：删掉这两行（flush + ensureStreamBlock）⇒ 首个用例 `toolCalls` 为空。
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

const SID = 'sess-toolcall-first'
const STREAM_TOPIC = `/topic/sessions/${SID}/stream`
const BLOCK = 'msg-assistant-toolcall'

function Host() {
  useChatSocket(SID, {}, undefined, undefined, undefined, undefined, () => {})
  return null
}

function pushEvent(payload: Record<string, unknown>) {
  const sub = fakeClient.subscriptions.find((s) => s.destination === STREAM_TOPIC)
  if (!sub) throw new Error('前置失败：会话 stream topic 未订阅')
  act(() => { sub.cb({ body: JSON.stringify({ sessionId: SID, ...payload }) }) })
}

const blocks = () => useChatStore.getState().streams[SID] ?? []

let container: HTMLDivElement | undefined
let root: Root | undefined

describe('[2026-10-22] tool_call 先于 chunk 到达 → 工具卡片不得丢失', () => {
  beforeEach(() => {
    fakeClient.subscriptions.length = 0
    useChatStore.setState({ serverRunning: {}, streamOrder: {}, streams: {}, messages: {}, agentStatus: 'idle' })
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
    useChatStore.setState({ serverRunning: {}, streamOrder: {}, streams: {}, messages: {} })
  })

  it('⭐ 本轮首帧即 tool_call（尚无任何 chunk）→ 按事件 id 建块并挂上 Bash 卡片', () => {
    expect(blocks().length, '前置：本轮尚无流式块').toBe(0)

    pushEvent({
      type: 'message.tool_call',
      assistantMessageId: BLOCK,
      toolCallId: 'call-1',
      toolName: 'Bash',
      arguments: { command: 'ls -la' },
    })

    const list = blocks()
    expect(list.length, 'tool_call 必须建立目标块（否则卡片无处可挂）').toBe(1)
    expect(list[0].assistantMessageId).toBe(BLOCK)
    expect(list[0].toolCalls).toHaveLength(1)
    expect(list[0].toolCalls[0].name).toBe('Bash')
    expect(list[0].toolCalls[0].arguments).toContain('ls -la')
  })

  it('chunk 先到（仍在 rAF 缓冲）→ tool_call 到达时先 flush，卡片与正文落在同一块', () => {
    pushEvent({ type: 'message.chunk', assistantMessageId: BLOCK, delta: '准备执行命令' })
    // 前置：正文此刻仍在缓冲（块已建、content 为空）
    expect(blocks()[0]?.content).toBe('')

    pushEvent({
      type: 'message.tool_call',
      assistantMessageId: BLOCK,
      toolCallId: 'call-2',
      toolName: 'Bash',
      arguments: { command: 'pwd' },
    })

    const list = blocks()
    expect(list).toHaveLength(1)                       // 不重复建块
    expect(list[0].content).toBe('准备执行命令')        // flush 生效：正文已落块
    expect(list[0].toolCalls[0].name).toBe('Bash')
  })

  it('tool_result 正常回填到已挂的卡片', () => {
    pushEvent({ type: 'message.tool_call', assistantMessageId: BLOCK, toolCallId: 'call-3', toolName: 'Bash', arguments: {} })
    pushEvent({ type: 'message.tool_result', toolCallId: 'call-3', result: 'ok', isError: false })

    const call = blocks()[0]?.toolCalls[0]
    expect(call?.result).toBe('ok')
    expect(call?.isError).toBe(false)
  })
})
