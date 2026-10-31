// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { MessageList } from '../MessageList'
import { useChatStore } from '@/stores/chatStore'
import type { ChatMessageDto } from '@/api/types'

/** 复刻 App 的取数方式：messages 必须来自 store 订阅（App 传 storeMessages）——否则 prepend 后
 *  列表不重渲，测不出「加载更多后视图仍在原处」。 */
const EMPTY: ChatMessageDto[] = []
function Harness({ sid, onLoadOlder }: { sid: string; onLoadOlder: (s: string) => void }) {
  const messages = useChatStore((s) => s.messages[sid] ?? EMPTY)
  return (
    <MessageList sessionId={sid} messages={messages} onDelete={() => {}} conversationId={null}
      onLoadOlder={onLoadOlder} onNearBottomChange={() => {}} />
  )
}

/**
 * [滚动回归·累积式窗口] 两条互相抵消的老问题一起钉住（用户 2026-10-29 裁定）：
 *   ① 「点加载更早 → 跳到底」：reset 判据曾是 `[messages[0]?.id]`，而 prepend 必然改首条 id ⇒
 *      被当成「切会话」写 `scrollTop = scrollHeight`。现在 reset 判据是【会话身份】，prepend 不触发。
 *   ② 「替换式滑窗把中间轮丢掉」：现在 prepend 是**累积**，已加载轮全部保留；
 *      视口位置由「锚点行 id + rect.top 差值」补偿（⛔ 不用 scrollHeight 差值）。
 *
 *   不变量：点「加载更多」后 scrollTop **保持不变**（既不是 0，也不是 scrollHeight），
 *   且新旧行同时留在 DOM 里。
 *
 * jsdom 无布局引擎：手动给滚动容器定义 scrollTop/scrollHeight/clientHeight（jsdom 原生值恒 0）；
 * rect 全为 0 ⇒ 锚点补偿差值 = 0（等价于「没有真实位移」，正是本测试要钉的「不归零、不跳底」）。
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

describe('MessageList 「加载更多」= 累积 prepend：不跳底、不归零、不丢已加载轮', () => {
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
    // scrollHeight 随消息条数增长（模拟 prepend 后内容变高）；clientHeight 固定 400。
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

  it('点「加载更多」后 scrollTop 保持用户位置（不归零、不跳底），新旧行同时存在', async () => {
    const head = [baseMsg('a1', 'user', SID), baseMsg('a2', 'assistant', SID)]
    useChatStore.setState({
      messages: { [SID]: head }, hasMore: { [SID]: true },
      streams: {}, streamOrder: {}, streamTicks: {},
    })
    const older = [baseMsg('h1', 'user', SID), baseMsg('h2', 'assistant', SID)]
    const onLoadOlder = (sid: string) => { useChatStore.getState().prependMessages(sid, older, false) }

    await act(async () => {
      root.render(<Harness sid={SID} onLoadOlder={onLoadOlder} />)
    })
    // 冲掉挂载期 effect 的 setTimeout(贴底收敛)（挂载即贴底，符合预期）
    await act(async () => { await new Promise((r) => setTimeout(r, 20)) })

    // 模拟用户上滚：scrollTop=100，派发 scroll 让 nearBottomRef=false（离底 500px > 阈值 60）
    scrollTop = 100
    await act(async () => { container.dispatchEvent(new Event('scroll')) })

    const btn = container.querySelector('.ml-load-older') as HTMLButtonElement | null
    expect(btn).not.toBeNull()
    await act(async () => { btn!.click(); await new Promise((r) => setTimeout(r, 40)) })

    // 不变量：用户位置保持 100；既不是跳到底（scrollHeight=800+4*100=1200），也不是被归零（替换式旧行为）
    expect(scrollTop).toBe(100)
    expect(scrollTop).not.toBe(container.scrollHeight)
    expect(scrollTop).not.toBe(0)
    // 累积断言：更早页并入 store，且旧行仍留在 DOM（没有被替换式窗口卸载）
    expect(useChatStore.getState().messages[SID]?.some((m) => m.id === 'h1')).toBe(true)
    expect(container.querySelector('[data-msg-id="h1"]')).not.toBeNull()
    expect(container.querySelector('[data-msg-id="a1"]')).not.toBeNull()
  })
})
