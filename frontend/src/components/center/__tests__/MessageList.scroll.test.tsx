// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { MessageList } from '../MessageList'
import { useChatStore } from '@/stores/chatStore'
import type { ChatMessageDto } from '@/api/types'

/** 复刻 App 的取数方式：messages 必须来自 store 订阅（App 传 storeMessages）——否则 prepend 后
 *  messages 首条 id 不变化，测不出「首条 id 变化触发重置」这条根因路径。 */
const EMPTY: ChatMessageDto[] = []
function Harness({ sid, onLoadOlder }: { sid: string; onLoadOlder: (s: string) => void }) {
  const messages = useChatStore((s) => s.messages[sid] ?? EMPTY)
  return (
    <MessageList sessionId={sid} messages={messages} onDelete={() => {}} conversationId={null}
      onLoadOlder={onLoadOlder} onNearBottomChange={() => {}} />
  )
}

/**
 * [滚动回归·可变异验证] 「点『加载更早』后视图直接跳到底部」的根因：
 *   MessageList 原有一个 useEffect 依赖 [messages[0]?.id]（注释称「首条 id 变化 = 切换会话 → 滚到底」），
 *   而 prependMessages 把更早页 unshift 到数组头部 → messages[0].id 必然变化 → 该 effect 误触发，
 *   setTimeout(0) 的 stickBottom()（scrollTop = scrollHeight）在 loadOlderClick 的 rAF 高度补偿之后执行
 *   （宏任务晚于动画帧；即便反过来，已到底部再 += Δ 也被钳在底部）→ scrollTop 被写成 scrollHeight = 跳底。
 *
 *   本用例锁死修复后的不变量：点「加载更早」→ 视图【锚定】在原阅读位置（scrollTop 只按新增高度补偿，
 *   而不是跳到 scrollHeight）。若有人把依赖改回 [messages[0]?.id]，断言收到的会是 scrollHeight（跳底）→ 失败。
 *
 * jsdom 无布局引擎：手动给滚动容器定义 scrollTop/scrollHeight/clientHeight（jsdom 原生值恒 0）。
 */

/** 最小 ChatMessageDto（避免每例重复造全字段）。 */
function baseMsg(id: string, role: 'user' | 'assistant', sessionId: string): ChatMessageDto {
  return {
    id, sessionId, role, author: role === 'user' ? '你' : 'nexus', content: `内容-${id}`,
    reasoning: null, toolCalls: null, finishReason: null, inputTokens: null, outputTokens: null,
    reasoningDurationMs: null, time: null, toolCallId: null, assistantMessageId: null,
    userMessageId: null, subtype: null, isMeta: false, isApiErrorMessage: false, apiError: null,
    error: null, errorDetails: null, matchedRule: null,
  }
}

describe('MessageList 「加载更早」不跳底（根因回归）', () => {
  let container: HTMLDivElement
  let root: Root
  let scrollTop = 0
  const SID = 'sess-scroll'
  const PER_ROW = 100
  const BASE = 800
  const rowCount = () => useChatStore.getState().messages[SID]?.length ?? 0

  beforeEach(() => {
    (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    container = document.createElement('div')
    document.body.appendChild(container)
    // MessageList 的滚动容器 = streamWrapRef.current.parentElement = 本 container。
    // scrollHeight 随消息条数增长（模拟 prepend 后内容变高）；clientHeight 固定 400 → 上滚后离底。
    Object.defineProperty(container, 'scrollTop', { get: () => scrollTop, set: (v: number) => { scrollTop = v }, configurable: true })
    Object.defineProperty(container, 'scrollHeight', { get: () => BASE + rowCount() * PER_ROW, configurable: true })
    Object.defineProperty(container, 'clientHeight', { get: () => 400, configurable: true })
    scrollTop = 0
    root = createRoot(container)
  })

  afterEach(() => {
    act(() => { root.unmount() })
    container.remove()
  })

  it('点「加载更早」后 scrollTop = 原位置 + 新增高度（锚定），而非跳到 scrollHeight（跳底）', async () => {
    const head = [baseMsg('a1', 'user', SID), baseMsg('a2', 'assistant', SID)]
    useChatStore.setState({
      messages: { [SID]: head }, hasMore: { [SID]: true },
      streams: {}, streamOrder: {}, streamTicks: {}, extendedWindow: {},
    })
    const older = [baseMsg('h1', 'user', SID), baseMsg('h2', 'assistant', SID)]
    const onLoadOlder = (sid: string) => { useChatStore.getState().prependMessages(sid, older, false) }

    await act(async () => {
      root.render(<Harness sid={SID} onLoadOlder={onLoadOlder} />)
    })
    // 冲掉挂载期 effect 的 setTimeout(stickBottom)（挂载即贴底，符合预期）
    await act(async () => { await new Promise((r) => setTimeout(r, 20)) })

    // 模拟用户上滚到历史位置：scrollTop=100，且派发 scroll 事件让 nearBottomRef=false（离底 500px > 阈值 60）
    scrollTop = 100
    await act(async () => { container.dispatchEvent(new Event('scroll')) })
    const prevH = container.scrollHeight // = 800 + 2*100 = 1000

    // 点顶部「加载更早」→ loadOlderClick：记录 prevH → onLoadOlder(prepend) → rAF 补 scrollTop += Δ
    const btn = container.querySelector('.ml-load-older') as HTMLButtonElement | null
    expect(btn).not.toBeNull()
    await act(async () => { btn!.click(); await new Promise((r) => setTimeout(r, 40)) })

    // 不变量：锚定在原阅读位置（100 + 新增高度 2*100 = 300），而【不是】跳到底部 scrollHeight = 1200
    expect(scrollTop).toBe(100 + (container.scrollHeight - prevH))
    expect(scrollTop).toBe(300)
    expect(scrollTop).not.toBe(container.scrollHeight)
    // 更早页确实并入窗口（修复不得以牺牲「加载更早」功能为代价）
    expect(useChatStore.getState().messages[SID]?.some((m) => m.id === 'h1')).toBe(true)
  })
})
