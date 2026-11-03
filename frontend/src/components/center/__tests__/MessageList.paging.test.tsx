// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { MessageList } from '../MessageList'
import { useChatStore } from '@/stores/chatStore'
import { FOLD_DEFAULT_TURNS } from '@/stores/messageTurns'
import type { ChatMessageDto } from '@/api/types'

/**
 * [轮分页 · 累积式窗口] 分页单位 = 轮，由**后端** GET /messages/turns 决定（整轮返回、不切断）：
 * 首屏 App 拉最近 3 轮，顶部「加载更多」再拉 2 轮**追加到前面**（用户 2026-10-29 裁定）。
 *
 * <b>WHY（规则九 · 测试验证意图）</b>：用户否定过「替换式滑窗」（点加载更多丢弃中间轮），要的是
 * **累积** —— 每次拉 2 轮 prepend，之前出现过的轮一个都不能少。四条不变量：
 *   ① 首屏 = store 里那 3 轮（App 只拉 3 轮；本组件⛔不做任何轮数裁剪）；
 *   ② 点「加载更多」→ 3 轮 + 2 轮 = 5 轮，再点 → 7 轮；**最早一轮的 id 始终还在 DOM 里**；
 *   ③ 按钮只在 hasMore 时出现，加载中禁用、重复点击不重复请求；
 *   ④ 挂载/滚动不自动加载。
 */

const SID = 'sess-paging'
const noop = () => {}

function baseMsg(id: string, role: 'user' | 'assistant', content: string): ChatMessageDto {
  return {
    id, sessionId: SID, role, author: role === 'user' ? '你' : 'nexus', content,
    reasoning: null, toolCalls: null, finishReason: null, inputTokens: null, outputTokens: null,
    reasoningDurationMs: null, time: null, toolCallId: null, assistantMessageId: null,
    userMessageId: null, subtype: null, isMeta: false, isApiErrorMessage: false, apiError: null,
    error: null, errorDetails: null, matchedRule: null,
  }
}

/** 一轮 = 一条用户提问 + 一条助手回复（assistant 的 userMessageId 指向该轮）。 */
function turn(userId: string): ChatMessageDto[] {
  return [
    { ...baseMsg(userId, 'user', `问-${userId}`), userMessageId: userId },
    { ...baseMsg(`a-${userId}`, 'assistant', `答-${userId}`), userMessageId: userId },
  ]
}

const userRowCount = (c: HTMLElement) => c.querySelectorAll('.msg.user').length
const renderedUserIds = (c: HTMLElement) =>
  Array.from(c.querySelectorAll('.msg.user')).map((n) => n.getAttribute('data-msg-id'))
const loadBtn = (c: HTMLElement) => c.querySelector('.ml-load-older') as HTMLButtonElement | null
/** [折叠显示] 折叠行（「已折叠 N 轮对话（点击展开）」） */
const foldRow = (c: HTMLElement) => c.querySelector('.ml-fold-row') as HTMLButtonElement | null

function Harness({ onLoadOlder }: { onLoadOlder: (sid: string) => Promise<void> | void }) {
  const messages = useChatStore((s) => s.messages[SID] ?? [])
  return (
    <MessageList sessionId={SID} messages={messages} onDelete={noop} conversationId={null}
      onLoadOlder={onLoadOlder} onNearBottomChange={noop} />
  )
}

describe('MessageList 轮窗口（加载更多 = 累积式追加，不丢中间轮）', () => {
  let container: HTMLDivElement
  let root: Root

  const render = async (onLoadOlder: (sid: string) => Promise<void> | void) => {
    await act(async () => { root.render(<Harness onLoadOlder={onLoadOlder} />) })
    // 冲掉挂载期 setTimeout(stickBottom) / 贴底收敛
    await act(async () => { await new Promise((r) => setTimeout(r, 20)) })
  }

  beforeEach(() => {
    (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
  })

  afterEach(() => {
    act(() => { root.unmount() })
    container.remove()
    useChatStore.setState({ messages: {}, hasMore: {}, streams: {}, streamOrder: {}, streamTicks: {}, foldedTurnCounts: {} })
  })

  it('首屏渲染 store 里已加载的全部轮（App 只放最近 FOLD_DEFAULT_TURNS 轮）；挂载不自动加载', async () => {
    // [折叠 = 真删 · 2026-11-02] App 首屏 listTurns({turns:FOLD_DEFAULT_TURNS}) → store 里恰好这么多轮
    const ids = Array.from({ length: FOLD_DEFAULT_TURNS }, (_, i) => `u${i + 1}`)
    useChatStore.setState({
      messages: { [SID]: ids.flatMap(turn) }, hasMore: { [SID]: true },
      streams: {}, streamOrder: {}, streamTicks: {},
    })
    let fetchCalls = 0
    await render(() => { fetchCalls++ })

    expect(userRowCount(container)).toBe(FOLD_DEFAULT_TURNS)
    expect(renderedUserIds(container)).toEqual(ids)
    // 挂载 ≠ 自动加载
    expect(fetchCalls).toBe(0)
  })

  it('比默认窗口更早的轮【折叠成一行】而不是丢弃：折叠态无 DOM，点击展开后全渲染', async () => {
    // [2026-11-02] 默认窗口 = FOLD_DEFAULT_TURNS（2）；store 里若多于它（如「加载更多」拉回来的），
    //   更早的轮在**渲染层**仍折叠成一行（内存裁剪只发生在追加路径，prepend 的不裁）。
    useChatStore.setState({
      messages: { [SID]: ['u1', 'u2', 'u3', 'u4', 'u5', 'u6', 'u7'].flatMap(turn) },
      hasMore: { [SID]: true }, streams: {}, streamOrder: {}, streamTicks: {},
    })
    await render(noop)
    expect(renderedUserIds(container)).toEqual(['u7', 'u8'].filter(() => false).concat(['u6', 'u7']))
    const fold = foldRow(container)
    expect(fold, '应出现折叠行').not.toBeNull()
    expect(fold!.textContent).toContain('已折叠 5 轮对话')
    // 折叠区内的轮**没有 DOM**
    expect(container.querySelector('[data-msg-id="u1"]')).toBeNull()
    expect(container.querySelector('[data-msg-id="u5"]')).toBeNull()

    // 点击折叠行 → 展开（本地 state，不发请求）
    await act(async () => { fold!.click(); await new Promise((r) => setTimeout(r, 20)) })
    expect(renderedUserIds(container)).toEqual(['u1', 'u2', 'u3', 'u4', 'u5', 'u6', 'u7'])
    expect(foldRow(container), '展开后折叠行消失').toBeNull()
  })

  it('折叠行在「加载更多」按钮之下：按钮仍是滚动容器的第一个可见元素（⛔ 不被折叠行顶掉）', async () => {
    useChatStore.setState({
      messages: { [SID]: ['u1', 'u2', 'u3', 'u4', 'u5'].flatMap(turn) },
      hasMore: { [SID]: true }, streams: {}, streamOrder: {}, streamTicks: {},
    })
    await render(noop)
    const btn = loadBtn(container)!
    const fold = foldRow(container)!
    expect(btn, '有更早历史 → 按钮在').not.toBeNull()
    expect(fold, '有折叠轮 → 折叠行在').not.toBeNull()
    // 文档序：按钮 → 折叠行 → 消息行；且按钮之前不得有任何消息行
    const follows = (a: Node, b: Node) => (a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0
    expect(follows(btn, fold), '按钮必须在折叠行之前').toBe(true)
    const firstMsg = container.querySelector('[data-msg-id]')!
    expect(follows(fold, firstMsg), '折叠行必须在第一条消息之前').toBe(true)
  })

  it('[2026-11-02 用户裁定] 执行中只显示**最新一轮**，跑在旧轮上的流式块不把旧轮拉回视野', async () => {
    // 真实场景：执行中又发了一条（后端排队），流式块仍归属**旧轮** u5，
    //   而最新提问是 u6 —— 用户要看到的是 u6，旧轮（含它的「正在执行」）应被折起。
    useChatStore.setState({
      messages: { [SID]: ['u1', 'u2', 'u3', 'u4', 'u5', 'u6'].flatMap(turn) },
      hasMore: { [SID]: true }, streams: {}, streamOrder: {}, streamTicks: {},
    })
    useChatStore.getState().ensureStreamBlock(SID, 'blk-live', 'u5')
    await render(noop)
    expect(renderedUserIds(container), '只留最新一轮').toEqual(['u6'])
    useChatStore.getState().clearStream(SID)
  })

  it('加载更多两次：新拉到的轮直接展开，最早的 id 始终留在 DOM（累积，不丢中间轮）', async () => {
    // [2026-11-02] 首屏 = FOLD_DEFAULT_TURNS 轮；此后每次「加载更多」拉回的轮由
    //   MessageList 的 prepend 收口直接记进 revealedFoldKeys（用户主动要的）⇒ 全部可见。
    useChatStore.setState({
      messages: { [SID]: ['t3', 't4'].slice(0, FOLD_DEFAULT_TURNS).flatMap(turn) }, hasMore: { [SID]: true },
      streams: {}, streamOrder: {}, streamTicks: {},
    })
    // 模拟后端返回更早 2 轮 → App prepend 进 store（与真实 handleLoadOlder 同形）
    let page = 0
    const pages = [['t1', 't2'], ['m1', 'm2']]
    const onLoadOlder = (sid: string) => {
      const older = (pages[page++] ?? []).flatMap(turn)
      useChatStore.getState().prependMessages(sid, older, page < pages.length)
    }
    await render(onLoadOlder)

    expect(renderedUserIds(container)).toEqual(['t3', 't4'])
    const firstEverId = renderedUserIds(container)[0]!

    // 第 1 次：+2 轮 → 4 轮全可见（新拉到的直接展开）
    await act(async () => { loadBtn(container)!.click(); await new Promise((r) => setTimeout(r, 30)) })
    expect(renderedUserIds(container)).toEqual(['t1', 't2', 't3', 't4'])
    expect(renderedUserIds(container)).toContain(firstEverId)

    // 第 2 次：+2 轮 → 6 轮全可见（中间轮 t1..t4 全部保留）
    await act(async () => { loadBtn(container)!.click(); await new Promise((r) => setTimeout(r, 30)) })
    expect(renderedUserIds(container)).toEqual(['m1', 'm2', 't1', 't2', 't3', 't4'])
    expect(renderedUserIds(container)).toContain(firstEverId)
  })

  it('加载中禁用按钮，重复点击不重复请求（一次点击 = 一次网络页）', async () => {
    useChatStore.setState({
      messages: { [SID]: ['u1', 'u2', 'u3'].flatMap(turn) }, hasMore: { [SID]: true },
      streams: {}, streamOrder: {}, streamTicks: {},
    })
    let fetchCalls = 0
    let release: () => void = () => {}
    const pending = new Promise<void>((res) => { release = res })
    await render(() => { fetchCalls++; return pending })

    await act(async () => { loadBtn(container)!.click() })
    expect(fetchCalls).toBe(1)
    expect(loadBtn(container)!.disabled).toBe(true)
    expect(loadBtn(container)!.textContent).toBe('加载中…')

    // 加载中再点：被 loadingOlder 守卫挡住
    await act(async () => { loadBtn(container)!.click() })
    expect(fetchCalls).toBe(1)

    await act(async () => { release(); await new Promise((r) => setTimeout(r, 20)) })
    expect(loadBtn(container)!.disabled).toBe(false)
    expect(loadBtn(container)!.textContent).toContain('再显示 2 轮')
  })

  it('hasMore=false（已到底）→ 按钮隐藏、显示「已加载到会话开始」，轮照常渲染', async () => {
    const two = ['u1', 'u2'].flatMap(turn)
    useChatStore.setState({
      messages: { [SID]: two }, hasMore: { [SID]: false },
      streams: {}, streamOrder: {}, streamTicks: {},
    })
    await render(noop)
    expect(userRowCount(container)).toBe(2)
    expect(loadBtn(container)).toBeNull()
    expect(container.querySelector('.ml-top-boundary')?.textContent).toContain('已加载到会话开始')
  })
})
