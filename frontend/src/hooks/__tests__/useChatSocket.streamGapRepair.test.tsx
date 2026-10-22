// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Client } from '@stomp/stompjs'
import { useChatStore } from '@/stores/chatStore'
import { useChatSocket } from '@/hooks/useChatSocket'

/**
 * ⭐ [2026-10-22 · 对齐 dsh acceptLiveEvent / repairGap] 流事件 seq 缺口修复。
 *
 * <b>病情</b>：会话流是「一次性推送 + 非持久 topic」——丢一帧前端无从得知，
 * 表现为「切走/断线后内容缺段，只能 F5」。dsh 用 `seq` 做缺口检测：
 * `seq > 已见最大 + 1` ⇒ 缓冲后续帧 + 重拉尾页 + 按 seq 拼回。
 *
 * <b>验收条件</b>：
 *   ① 连续 seq 正常落地；
 *   ② 缺口 → 缓冲并触发**一次**重拉（onReconnectReload）；修复完成前不追加带洞内容，完成后缓冲帧按序拼回；
 *   ③ 重复 seq 丢弃（不重复追加文本）；
 *   ④ 后端重启（seq 大幅回退）→ 重置窗口继续处理，不把新内容当重复丢掉。
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

const SID = 'sess-gap'
const TOPIC = `/topic/sessions/${SID}/stream`

/** 第 7 个参数 = onReconnectReload（缺口修复重拉入口） */
function Host({ onReload }: { onReload: (sid: string) => void }) {
  useChatSocket(SID, { [SID]: TOPIC }, undefined, undefined, undefined, undefined, onReload)
  return null
}

function push(payload: Record<string, unknown>) {
  const sub = fakeClient.subscriptions.find((s) => s.destination === TOPIC)
  if (!sub) throw new Error('前置失败：stream topic 未订阅')
  act(() => { sub.cb({ body: JSON.stringify({ sessionId: SID, ...payload }) }) })
}

const flush = async () => { await act(async () => { await new Promise((r) => setTimeout(r, 40)) }) }
const blockContent = (blockId = 'b1') =>
  (useChatStore.getState().streams[SID] ?? []).find((b) => b.assistantMessageId === blockId)?.content ?? null

let container: HTMLDivElement | undefined
let root: Root | undefined

describe('[seq 缺口修复]', () => {
  beforeEach(() => {
    fakeClient.subscriptions.length = 0
    useChatStore.setState({ streams: {}, streamOrder: {}, messages: {}, serverRunning: {}, streamsBySession: undefined } as never)
    ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
  })
  afterEach(() => {
    if (root) act(() => root!.unmount())
    container?.remove()
    container = undefined
    root = undefined
  })

  async function mount(onReload: (sid: string) => void = vi.fn()) {
    act(() => { root!.render(<Host onReload={onReload} />) })
    act(() => { fakeClient.onConnect?.() })
    await flush()
    // 挂载期本就存在两个合法的重拉入口（onConnect 断档补齐 / 切回运行中会话）——
    // 本文件只关心「缺口修复」这一次，故清表后再计数。
    ;(onReload as unknown as { mockClear: () => void }).mockClear()
  }

  it('连续 seq → 正常追加', async () => {
    await mount()
    push({ type: 'message.chunk', seq: 1, assistantMessageId: 'b1', delta: 'a' })
    await flush()
    push({ type: 'message.chunk', seq: 2, assistantMessageId: 'b1', delta: 'b' })
    await flush()
    expect(blockContent()).toBe('ab')
  })

  it('⭐ 缺口 → 缓冲 + 触发一次重拉；重拉期间不追加带洞内容，完成后按 seq 拼回', async () => {
    let resolveReload: (() => void) | undefined
    const onReload = vi.fn(() => new Promise<void>((r) => { resolveReload = r }))
    await mount(onReload)

    push({ type: 'message.chunk', seq: 1, assistantMessageId: 'b1', delta: 'a' })
    await flush()

    push({ type: 'message.chunk', seq: 3, assistantMessageId: 'b1', delta: 'c' })  // 缺 seq=2
    push({ type: 'message.chunk', seq: 4, assistantMessageId: 'b1', delta: 'd' })  // 修复期间继续进缓冲
    await flush()

    expect(onReload, '缺口必须触发尾页重拉（repairGap）').toHaveBeenCalledTimes(1)
    expect(onReload).toHaveBeenCalledWith(SID)
    expect(blockContent(), '修复完成前不得把带洞内容追加进去').toBe('a')

    await act(async () => { resolveReload?.() })
    await flush()
    expect(blockContent(), '缓冲帧必须按 seq 顺序拼回').toBe('acd')
  })

  it('重复 seq → 丢弃（不重复追加文本）', async () => {
    await mount()
    push({ type: 'message.chunk', seq: 1, assistantMessageId: 'b1', delta: 'a' })
    await flush()
    push({ type: 'message.chunk', seq: 1, assistantMessageId: 'b1', delta: 'a' })  // 重复帧
    await flush()
    expect(blockContent()).toBe('a')
  })

  it('后端重启（seq 大幅回退）→ 重置窗口继续处理', async () => {
    const onReload = vi.fn()
    await mount(onReload)
    push({ type: 'message.chunk', seq: 1, assistantMessageId: 'b1', delta: 'a' })
    await flush()

    // 跳到 seq=102：缺口中继，靠重拉把窗口推到 102（模拟旧进程跑过很久）
    push({ type: 'message.chunk', seq: 102, assistantMessageId: 'b1', delta: 'z' })
    await flush()
    expect(onReload).toHaveBeenCalledTimes(1)

    // 后端重启：序号从 1 重来 —— 必须放行，否则新内容永久丢弃
    push({ type: 'message.chunk', seq: 1, assistantMessageId: 'b2', delta: 'B' })
    await flush()
    expect(blockContent('b2'), '重启后的新序号必须被重置放行').toBe('B')
  })
})
