// @vitest-environment jsdom
import { act, useCallback, useState } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Composer } from '../Composer'
import { useChatStore } from '@/stores/chatStore'
import { computeTurnRunning } from '@/utils/turnRunning'
import { useServerRunningRebuild, useServerRunningReconcile, reapSessionsOnNotRunning } from '@/hooks/useServerRunning'
import { chatApi } from '@/api/chat'

/**
 * ⭐ [C6 · 遗留2] 「本地 turn 簿记必须与服务端真相对账」守门测试。
 *
 * <b>缺陷</b>：两路本地簿记的清除点**全部**挂在「本页订阅那一条通道」的事件上 ——
 * `activeStreams[sid]` 只在 complete / cancel / error 三个事件回调（App.handleSessionDone）里删；
 * `streams[sid]` / `streamOrder[sid]` 只在 complete（finalizeBlocks）与 error / cancelled（clearStream）
 * 里清。这些帧一旦在<b>无断连</b>的情况下静默丢失（帧未送达 / complete 处理中途抛错 → 其后的行不再执行），
 * 两路都<b>永不回收</b>；而 `computeTurnRunning` 是<b>或</b>语义 ⇒ 服务端随后说 idle（或 GET /running
 * 返回 false）也<b>压不住</b>恒真的这一路 ⇒ 停止键永久卡在「停止」、Esc 永久走停止分支。
 *
 * <b>本文件钉的是「对账那两路」</b>：服务端权威地转为「不在跑」（true → false 边沿）⇒ ①残留流式块定稿
 * 成正式消息 ②本地登记被回收。两条服务端通道都写同一个 store 键，故它们天然共用同一条对账路径：
 * <ol>
 *   <li><b>idle 实时事件</b>：useChatSocket 的 session.status 分支写 `serverRunning[sid]=false`
 *       —— 由下面「store 边沿」用例代表（与那条分支写的是同一个键、同一个值）。</li>
 *   <li><b>GET /running 返回 false</b>：{@link useServerRunningRebuild} / refreshServerRunning 写 store
 *       —— 由下面「GET 通道」用例真跑那条 hook。</li>
 * </ol>
 *
 * <b>反面对照（同等重要）</b>：对账<b>不得</b>把「未查过 / 过期 false」当收口 —— 发送那一刻 store 里
 * 往往还没有服务端事实（键缺省，或迟到的是登记前发出的那次 GET），若一并回收，则正常流式刚登记就被抹掉
 * ⇒ <b>比原缺陷更坏</b>（停止键该在的时候不在）；也<b>不得</b>动别的会话的流 / 登记。
 * ⚠️ 每条「反面对照」都必须能因对应的变异<b>真的红</b>：判据不写 turnRunning 的结果（那会被别的信号撑着
 * 而失去鉴别力），而写「对账回调实际收到过哪些 sid」（{@link Harness.reclaimCalls}）—— 否则本会话若
 * 不是 serverRunning 的键，它就永远进不了 reaped，「误回收本会话」的变异根本落不到它身上（假对照）。
 * ⚠️ 本 Host 的回收回调是 App 那一条的**极简版（不含轮次判据）**—— App 版多一条「登记晚于最近一次
 * true ⇒ 不收」（防上一轮迟到边沿误回收本轮登记），那条由 __tests__/App.serverRunningReconcile.test.tsx
 * 真渲染 App 钉住；本文件钉的是更底层的「回调被按会话键控地派发 + 边沿语义」。
 *
 * <b>WHY 必须真的渲染</b>：本批修的是<b>接线</b>（store 边沿 → 对账 → turnRunning / 流式块定稿 →
 * Composer 的 streaming 分支）。只测 `reapSessionsOnNotRunning` 纯函数会漏掉整条接线；把对账逻辑照抄进
 * Host（而非调用 App 调用的真实现）则更坏 —— 把真实现删掉用例照样全绿，正是本仓反复踩的「假守护」。
 * 故 Host 调用的就是 App 调用的那几处真实现：{@link useServerRunningRebuild}、
 * {@link useServerRunningReconcile}、{@link computeTurnRunning}，登记入口与 App.handleSessionDone 的
 * 清除语义逐字同款（只删登记这一份，不碰其它副作用）。
 * ⚠️ Host 与 App 的接线是「逐行同款」而非同一份代码 —— App 那一层的接线由
 * __tests__/App.serverRunningReconcile.test.tsx 真渲染 App 钉住（删掉 App 里那行调用必须变红）。
 *
 * <b>手法</b>：沿用本仓既有 jsdom 真实渲染模式（`Composer.popEditable.test.tsx`）：`createRoot` + `act`。
 */

vi.mock('@tauri-apps/api/core', () => ({ isTauri: () => false, invoke: vi.fn(async () => undefined) }))
vi.mock('@tauri-apps/api/webview', () => ({ getCurrentWebview: () => ({ onDragDropEvent: () => Promise.resolve(() => {}) }) }))
vi.mock('@tauri-apps/plugin-fs', () => ({ stat: vi.fn(), readFile: vi.fn(), writeTextFile: vi.fn() }))
vi.mock('@/api/chat', () => ({ uploadAttachment: vi.fn(), chatApi: { sessionRunning: vi.fn() } }))

const SID = 'sess-c6-reconcile'
const OTHER = 'sess-c6-other'
const TOPIC = `/topic/sessions/${SID}/stream`
const BLOCK = 'msg-assistant-residue'
const sessionRunning = vi.mocked(chatApi.sessionRunning)

interface Harness {
  container: HTMLDivElement
  root: Root
  stopVisible: () => boolean
  sendVisible: () => boolean
  /** 模拟 App.sendMessage 的登记行（`setActiveStreams(prev => ({...prev, [sid]: resp.streamTopic}))`） */
  registerStream: () => void
  /** 模拟该轮产生了流式块（chunk 到达 → ensureStreamBlock + appendChunk） */
  pushChunk: (delta: string) => void
  setServerRunning: (sid: string, running: boolean) => void
  /** 该会话残留的流式块数（App 的 hasStream 来源 = streamOrder 长度） */
  blockCount: () => number
  /** 该会话已定稿消息（finalizeBlocks 把块转成的正式消息） */
  finalized: () => { id: string | null; content: string | null }[]
  /** 对账回调（reclaimStreamRegistration）**实际收到过的 sid 序列** —— 「误回收本会话」的直接判据 */
  reclaimCalls: () => string[]
}

const stopBtn = (c: HTMLElement) => c.querySelector('button.send-btn.danger')
const sendBtn = (c: HTMLElement) => c.querySelector('button.send-btn:not(.danger)')

function deferred<T>() {
  let resolve!: (v: T) => void
  const promise = new Promise<T>((r) => { resolve = r })
  return { promise, resolve }
}

/** 放行 GET 的 promise 链（含 hook effect 里的 then）——不 sleep，只让微任务队列跑完。 */
async function flushPromises() {
  await act(async () => { await Promise.resolve(); await Promise.resolve() })
}

/** Host 的登记入口（App 的 setActiveStreams 那一条；由 mountHost 换成本次渲染的 setter） */
let holder: { register: () => void } = { register: () => {} }
/** 对账回调实际收到的 sid（每次 mountHost 归零）—— 用于钉「回调是否被按会话键控地派发」 */
let reclaimLog: string[] = []

function Host() {
  useServerRunningRebuild(SID, true)                                   // App: useServerRunningRebuild(activeSessionId, isRealActive)
  const [activeStreams, setActiveStreams] = useState<Record<string, string>>({})   // App: useState<Record<string,string>>({})
  // App 同款回收入口：只删登记这一份（不触发 handleSessionDone 的「完成未读绿点」等副作用）。
  // ⚠️ 与 App 的唯一差异：App 的该回调还带「轮次判据」（登记晚于最近一次 true ⇒ 不收，见 App.tsx）；
  //   本 Host 保持极简（无判据）—— 这样「回调被派发了哪些 sid」这件事本身可被断言，
  //   轮次判据那一条由 __tests__/App.serverRunningReconcile.test.tsx 真渲染 App 钉住。
  const reclaimStreamRegistration = useCallback((sid: string) => {
    reclaimLog.push(sid)
    setActiveStreams((prev) => {
      if (!prev[sid]) return prev
      const next = { ...prev }
      delete next[sid]
      return next
    })
  }, [])
  useServerRunningReconcile(reclaimStreamRegistration)                 // App: useServerRunningReconcile(reclaimStreamRegistration)
  holder.register = () => { act(() => setActiveStreams((p) => ({ ...p, [SID]: TOPIC }))) }

  const serverRunning = useChatStore((s) => !!s.serverRunning[SID])    // App 同款选择器
  // App 同款 hasStream：块增/删才变化的 streamOrder 长度（不订阅块内容）
  const streamBlockCount = useChatStore((s) => (s.streamOrder[SID]?.length ?? 0))
  const turnRunning = computeTurnRunning({
    streamRegistered: !!activeStreams[SID],   // App: !!activeStreams[activeSessionId]
    hasStream: streamBlockCount > 0,          // App: streamBlockCount > 0
    serverRunning,
  })
  return (
    <Composer
      composerText=""
      setComposerText={() => {}}
      sendMessage={() => {}}
      showToast={() => {}}
      streaming={turnRunning}
      onStop={() => {}}
      queuedCommands={[]}
      popEditable={async () => null}
      boundProjectName={null}
      onSelectProject={() => {}}
      currentModel="ds-openai/deepseek-v4-flash"
      permissionMode="default"
      empty={false}
      sessionId={SID}
      localRead={false}
    />
  )
}

function mountHost(): Harness {
  ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  holder = { register: () => {} }
  reclaimLog = []
  const container = document.createElement('div')
  document.body.appendChild(container)
  const root = createRoot(container)
  act(() => { root.render(<Host />) })

  return {
    container,
    root,
    stopVisible: () => !!stopBtn(container),
    sendVisible: () => !!sendBtn(container),
    registerStream: () => holder.register(),
    pushChunk: (delta) => {
      act(() => {
        const st = useChatStore.getState()
        st.ensureStreamBlock(SID, BLOCK, 'msg-user-1')
        st.appendChunk(SID, BLOCK, delta)
      })
    },
    setServerRunning: (sid, running) => {
      act(() => { useChatStore.getState().setServerRunning(sid, running) })
    },
    blockCount: () => useChatStore.getState().streamOrder[SID]?.length ?? 0,
    finalized: () => (useChatStore.getState().messages[SID] ?? [])
      .filter((m) => m.id === BLOCK)
      .map((m) => ({ id: m.id ?? null, content: m.content ?? null })),
    reclaimCalls: () => [...reclaimLog],
  }
}

describe('[C6·遗留2] 本地 turn 簿记与服务端真相对账', () => {
  let h: Harness | undefined

  beforeEach(() => {
    useChatStore.setState({ serverRunning: {}, streamOrder: {}, streams: {}, messages: {} })   // 单例 store：每个用例显式归零，防跨用例污染
    sessionRunning.mockReset()
    // 默认「GET 永不返回」：不干扰直接驱动 store 的用例（那些用例不关心重建通道）
    sessionRunning.mockReturnValue(new Promise(() => {}))
  })

  afterEach(() => {
    if (h) {
      const cur = h
      act(() => cur.root.unmount())
      cur.container.remove()
      h = undefined
    }
    useChatStore.setState({ serverRunning: {}, streamOrder: {}, streams: {}, messages: {} })
  })

  it('⭐ 核心：服务端收口（idle 事件写 false）⇒ 即便 activeStreams 残留，停止键也必须消失', () => {
    h = mountHost()
    h.setServerRunning(SID, true)     // run 起轮：thinking 事件（或 GET 说 true）
    h.registerStream()                // 本页发送登记（App.sendMessage 那一行）
    expect(h.stopVisible()).toBe(true)

    // complete 帧静默丢失 ⇒ 该登记未走 handleSessionDone，仍残留；
    // 服务端权威收口（idle 事件 → useChatSocket 写 serverRunning=false；与 GET 返回 false 同一个键）
    h.setServerRunning(SID, false)

    // 或语义下残留的登记会把停止键永久钉住 ⇒ 对账必须回收它（反向实验：删掉对账这一路 ⇒ 本行为 true）
    expect(h.stopVisible()).toBe(false)
    expect(h.sendVisible()).toBe(true)

    // 回收不得破坏「本页发送」这条登记路：服务端起新一轮 ⇒ 再发送照常登记、停止键照常出现
    h.setServerRunning(SID, true)
    h.registerStream()
    expect(h.stopVisible()).toBe(true)
  })

  it('⭐ [R2] 服务端收口 ⇒ 残留的流式块必须定稿成正式消息（不再「hasStream 恒真 + 该行永久直出原文」）', () => {
    h = mountHost()
    h.setServerRunning(SID, true)
    h.registerStream()
    h.pushChunk('## 已经渲染过一半的正文')      // 该轮产生了 chunk ⇒ streams/streamOrder 有块
    expect(h.blockCount()).toBe(1)
    expect(h.stopVisible()).toBe(true)

    // complete 帧静默丢失（无断连）⇒ finalizeBlocks 不跑 ⇒ 该块永不转为消息、hasStream 恒真；
    // 服务端权威收口（idle / GET 说不在跑）——**必须在这一点把残留块定稿**
    h.setServerRunning(SID, false)

    // 反向实验：删掉 useServerRunningReconcile 里的 finalizeBlocks ⇒ blockCount 仍为 1、本行红
    expect(h.blockCount()).toBe(0)
    expect(h.finalized()).toEqual([{ id: BLOCK, content: '## 已经渲染过一半的正文' }])
    expect(h.stopVisible()).toBe(false)
    expect(h.sendVisible()).toBe(true)
  })

  it('⭐ [R2] 后台 drain 起的 run（从未登记 activeStreams）⇒ 残留块同样必须定稿', () => {
    h = mountHost()
    // 排队命令 / cron / 任务通知起的 run：不经「本页发送」⇒ activeStreams 里没有它（无登记）
    h.setServerRunning(SID, true)
    h.pushChunk('后台 run 的正文')
    expect(h.blockCount()).toBe(1)
    expect(h.stopVisible()).toBe(true)      // 停止键靠服务端那一路撑着

    h.setServerRunning(SID, false)

    // 反向实验：删掉 finalizeBlocks ⇒ blockCount 仍为 1、停止键被残留块永久钉住、该行永走流式臂
    expect(h.blockCount()).toBe(0)
    expect(h.finalized()).toEqual([{ id: BLOCK, content: '后台 run 的正文' }])
    expect(h.stopVisible()).toBe(false)
    expect(h.sendVisible()).toBe(true)
  })

  it('⭐ GET /running 返回 false（重连 / 切回会话的恢复通道）⇒ 同样回收残留登记', async () => {
    const d = deferred<{ running: boolean }>()
    sessionRunning.mockReturnValue(d.promise)
    h = mountHost()
    h.setServerRunning(SID, true)
    h.registerStream()
    expect(h.stopVisible()).toBe(true)

    d.resolve({ running: false })     // 真跑 useServerRunningRebuild 那条 GET 回填
    await flushPromises()

    expect(sessionRunning).toHaveBeenCalledWith(SID)
    expect(h.stopVisible()).toBe(false)
    expect(h.sendVisible()).toBe(true)
  })

  it('反面对照：登记后服务端事实尚未到达（键缺省）⇒ 不得回收登记、停止键必须仍在', () => {
    h = mountHost()
    h.registerStream()                // 发送那一刻 store 里还没有任何服务端事实（键缺省 = 未查过）
    expect(h.stopVisible()).toBe(true)

    // 缺省/未查过不是「收口」（把它当收口 = 每次发送后立刻抹掉刚登记的 activeStreams ⇒ 正常流式被打断）
    expect(useChatStore.getState().serverRunning[SID]).toBeUndefined()
    expect(h.stopVisible()).toBe(true)
  })

  it('反面对照：登记前发出的 GET 迟到返回 false ⇒ 过期信息不得当收口', async () => {
    const d = deferred<{ running: boolean }>()
    sessionRunning.mockReturnValue(d.promise)
    h = mountHost()
    h.registerStream()                // 发送登记（GET 在此之前已发出）
    expect(h.stopVisible()).toBe(true)

    d.resolve({ running: false })     // 对「刚登记的这一轮」而言这是登记前的过期答案
    await flushPromises()

    expect(h.stopVisible()).toBe(true)   // ⭐ 回收只认 true→false 边沿：过期 false 不得抹掉活登记
    expect(h.sendVisible()).toBe(false)

    // [加宽] 本条原先只挡「把缺省/首查 false 当收口」那一族变异（过期 false 本身不产生边沿 ⇒ 更激进的
    //   「任何边沿收全部会话」变异它照绿）。叠上「别的会话的收口边沿」后，那一族变异也会落到本会话 ⇒ 本行红。
    h.setServerRunning(OTHER, true)
    h.setServerRunning(OTHER, false)
    expect(h.reclaimCalls()).toEqual([OTHER])
    expect(h.stopVisible()).toBe(true)
  })

  it('反面对照：别的会话收口 ⇒ 对账回调只应带被收口的那个会话，绝不带本会话', () => {
    h = mountHost()
    // ⚠️ 本会话必须在 serverRunning 里【有键】—— 否则 SID 永远进不了 reaped（reaped 恒是 map 键的子集），
    //   「误回收本会话」这类变异根本落不到本会话上 ⇒ 用例在任何变异下都绿（形同虚设 · 实测 M6 亦然）。
    //   取值让它**不构成边沿**（true → true），于是收口边沿只有一个（别的会话的）。
    h.setServerRunning(SID, true)
    h.registerStream()
    h.setServerRunning(OTHER, true)
    expect(h.stopVisible()).toBe(true)

    h.setServerRunning(OTHER, false)   // 收口边沿必须按会话键控

    // 反向实验：把回调派发改成「不看 sid、收任何边沿就回收 prev/next 全部会话」⇒ reclaimCalls 里出现 SID ⇒ 本行红
    expect(h.reclaimCalls()).toEqual([OTHER])
    // 行为面（弱）：本会话的登记不得被别的会话的边沿抹掉。注意本行**不具鉴别力** —— 本会话此刻由
    //   serverRunning[SID]=true 撑着，误回收登记也仍是「停止」；有鉴别力的是上面 reclaimCalls 那行。
    expect(h.stopVisible()).toBe(true)
    expect(h.sendVisible()).toBe(false)
  })

  it('反面对照：本会话流式在推（turn 真在跑）⇒ 别的会话收口不得打断本会话的流', () => {
    h = mountHost()
    h.setServerRunning(SID, true)
    h.pushChunk('正在推的正文')
    h.setServerRunning(OTHER, true)
    expect(h.blockCount()).toBe(1)
    expect(h.stopVisible()).toBe(true)

    h.setServerRunning(OTHER, false)   // 别的会话收口

    // 反向实验：把定稿/回收做成「任何边沿都对全部会话执行」⇒ 本会话流式块被提前定稿（blockCount 变 0）→ 本行红
    expect(h.blockCount()).toBe(1)
    expect(useChatStore.getState().streams[SID]?.[0]?.content).toBe('正在推的正文')
    expect(h.stopVisible()).toBe(true)
  })
})

describe('[C6·遗留2] reapSessionsOnNotRunning：只认 true→false 边沿', () => {
  it('true → false = 收口边沿', () => {
    expect(reapSessionsOnNotRunning({ a: true, b: true }, { a: false, b: true })).toEqual(['a'])
  })
  it('键缺省 / 首次查到 false 都不是收口（未查过 ≠ 收口）', () => {
    expect(reapSessionsOnNotRunning({}, { a: false })).toEqual([])
    expect(reapSessionsOnNotRunning({ a: false }, { a: false })).toEqual([])
  })
  it('键被删除（删会话）不是收口', () => {
    expect(reapSessionsOnNotRunning({ a: true }, {})).toEqual([])
  })
  it('false → true（起轮）与 true → true（重复上报）都不是收口', () => {
    expect(reapSessionsOnNotRunning({ a: false }, { a: true })).toEqual([])
    expect(reapSessionsOnNotRunning({ a: true }, { a: true })).toEqual([])
  })
})
