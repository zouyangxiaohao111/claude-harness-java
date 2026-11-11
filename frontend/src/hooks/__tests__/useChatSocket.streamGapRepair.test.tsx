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

  it('⭐ [gap-repair-deadlock] 重拉永不返回 ⇒ 超时后必须恢复派发（否则实时通道永久停摆）', async () => {
    // WHY（用户实测「明明跑完了却卡在渲染，切换会话才继续」）：
    //   修复期间 stitchingRef=true ⇒ 每一帧都只进缓冲、不派发；复位只在 await 之后。
    //   而 api() 用裸 fetch（无超时）⇒ 重拉一旦挂住，finally 永不执行 ⇒
    //   之后**所有帧（含 complete）永不被派发** ⇒ 界面彻底静止，只能靠切会话整表替换才「突然全出现」。
    vi.useFakeTimers()
    /**
     * 推进假时钟并把微任务链跑完。
     *
     * ⚠️ 必须"推进 → 让出微任务 → 再推进"两段：超时 resolve 是微任务，`finally` 里的
     *   `dispatchEvent → scheduleStreamFlush` 会在微任务里**新排一个 rAF**，
     *   只推进一次的话那个 rAF 不会被触发，正文仍停在旧值（假红）。
     */
    const tick = async (ms: number) => {
      await act(async () => { vi.advanceTimersByTime(ms) })
      await act(async () => { await Promise.resolve() })
      await act(async () => { vi.advanceTimersByTime(ms) })
    }
    try {
      const onReload = vi.fn(() => new Promise<void>(() => { /* 永不 resolve：模拟请求挂住 */ }))
      act(() => { root!.render(<Host onReload={onReload} />) })
      act(() => { fakeClient.onConnect?.() })
      await tick(50)
      onReload.mockClear()

      push({ type: 'message.chunk', seq: 1, assistantMessageId: 'b1', delta: 'a' })
      await tick(50)
      expect(blockContent()).toBe('a')

      // 触发缺口修复（重拉永不返回）
      push({ type: 'message.chunk', seq: 3, assistantMessageId: 'b1', delta: 'c' })
      await tick(100)
      expect(onReload, '缺口必须触发一次重拉').toHaveBeenCalledTimes(1)
      expect(blockContent(), '修复期间不派发').toBe('a')

      // ⭐ 超过修复超时窗口 ⇒ 必须放弃对账并把缓冲帧派发出去
      await tick(8_500)
      expect(blockContent(), '超时后必须恢复派发（否则此后所有帧永久卡死）').toBe('ac')

      // 反向验证：后续帧也必须继续正常派发（stitchingRef 已复位）
      push({ type: 'message.chunk', seq: 4, assistantMessageId: 'b1', delta: 'd' })
      await tick(50)
      expect(blockContent()).toBe('acd')
    } finally {
      vi.useRealTimers()
    }
  })

  it('⭐ seq 回退中间段（last−seq ∈ [1,100] 且 seq≠1）→ 必须放行（旧判据会静默丢弃整轮）', async () => {
    // WHY（用户实测「后台子代理完成 → 代跑一轮 → 前端没有任何记录，F5 才有」）：
    //   旧判据 `if (seq <= last) { if ((seq === 1 && last > 1) || last - seq > 100) reset; else return }`
    //   只兜住了 seq=1 与大跨度（>100）两种形态；中间那一段（差值 1..100 且 seq≠1）被当「重复帧」
    //   静默丢弃。STOMP 不重传 ⇒ 该轮 chunk / tool / complete 全部丢失，且无任何补偿 ⇒ 只能 F5。
    const onReload = vi.fn()
    await mount(onReload)
    push({ type: 'message.chunk', seq: 1, assistantMessageId: 'b1', delta: 'a' })
    await flush()

    // 先把游标推到 50（等价「旧进程已跑到 50」）：seq=50 相对 1 是缺口 → 走一次 repair，
    //   修复完成后缓冲帧按序派发，lastSeq 推进到 50。
    push({ type: 'message.chunk', seq: 50, assistantMessageId: 'b1', delta: 'z' })
    await flush()
    expect(onReload, '缺口应触发一次重拉').toHaveBeenCalledTimes(1)
    expect(blockContent(), 'repair 完成后缓冲帧必须派发').toBe('az')

    // 真重复仍必须丢弃（seq === 已见最大）
    push({ type: 'message.chunk', seq: 50, assistantMessageId: 'b1', delta: 'R' })
    await flush()
    expect(blockContent(), 'seq === 已见最大 ⇒ 真重复，必须丢弃').toBe('az')

    // ⭐ 核心：seq=30 落在 [1,100] 盲区（50-30=20，且 seq≠1）⇒ 必须放行
    push({ type: 'message.chunk', seq: 30, assistantMessageId: 'b2', delta: 'B' })
    await flush()
    expect(
      blockContent('b2'),
      'seq 回退必须重置游标并派发本帧（旧判据当重复丢弃 ⇒ 该轮永久失明）',
    ).toBe('B')

    // 游标已复位到 29 ⇒ 后续 seq=31 连续，正常追加
    push({ type: 'message.chunk', seq: 31, assistantMessageId: 'b2', delta: 'C' })
    await flush()
    expect(blockContent('b2'), '复位后必须恢复连续派发').toBe('BC')
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
