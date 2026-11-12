// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { MessageList } from '../MessageList'
import { useChatStore } from '@/stores/chatStore'
import type { ChatMessageDto } from '@/api/types'

/**
 * [滚动跟随回归] 用户 2026-11-12 实测：「工具都到底了，然后又增加了思考，不自动滚动；我已经在底部，不是在看历史」。
 *
 * <p>两条互相独立的缺口，任一都会让「贴底跟随」永久断掉：
 * <ol>
 *   <li><b>自己的贴底写入被误判成用户上滚</b>：流式内容在写 scrollTop 与 scroll 事件派发之间还在长高，
 *       scrollHeight 变大而 scrollTop 仍是程序写的值 ⇒ 按「距底部 &gt; 60px」判 ⇒ nearBottom 翻假
 *       ⇒ 之后所有增量都被 `if (!nearBottomRef.current) return` 挡掉，再也不跟随。</li>
 *   <li><b>不推进 streamTicks 的高度增长没有触发点</b>：addToolCall / ensureStreamBlock 都不推进节拍，
 *       工具卡新增、思考块首次出现把内容撑高，但按 tick 与 lastMsgId 的两条滚底路径都不触发。</li>
 * </ol>
 *
 * <p>不变量：①用户上滚必须仍然停跟随（⛔ 不能为了自动滚动把人拽回来）；
 * ②贴底时的任何高度增长都要跟随。jsdom 无布局引擎 ⇒ 手动给容器定义
 * scrollTop(带 clamp)/scrollHeight/clientHeight。
 */

const SID = 'sess-scroll-follow'
const BASE = 800
const PER_ROW = 100
const CLIENT_H = 400
const EMPTY: ChatMessageDto[] = []

/** ResizeObserver 桩：jsdom 没有；记下回调供测试手动触发（等价于「内容刚被撑高」）。 */
let roCallback: (() => void) | null = null
class FakeResizeObserver {
  constructor(cb: ResizeObserverCallback) {
    roCallback = () => cb([], this as unknown as ResizeObserver)
  }
  observe() {}
  unobserve() {}
  disconnect() { roCallback = null }
}

function baseMsg(id: string, role: 'user' | 'assistant'): ChatMessageDto {
  return {
    id, sessionId: SID, role, author: role === 'user' ? '你' : 'nexus', content: `内容-${id}`,
    reasoning: null, toolCalls: null, finishReason: null, inputTokens: null, outputTokens: null,
    reasoningDurationMs: null, time: null, toolCallId: null, assistantMessageId: null,
    userMessageId: null, subtype: null, isMeta: false, isApiErrorMessage: false, apiError: null,
    error: null, errorDetails: null, matchedRule: null,
  }
}

function Harness({ onNearBottom }: { onNearBottom: (atBottom: boolean) => void }) {
  const messages = useChatStore((s) => s.messages[SID] ?? EMPTY)
  return (
    <MessageList sessionId={SID} messages={messages} onDelete={() => {}} conversationId={null}
      onLoadOlder={() => {}} onNearBottomChange={onNearBottom} />
  )
}

describe('MessageList 贴底跟随（自己的写入 ⊕ 高度增长）', () => {
  let container: HTMLDivElement
  let root: Root
  let rawTop = 0
  let rowCount = 0
  let bottomCalls: boolean[] = []
  const contentH = () => BASE + rowCount * PER_ROW
  const maxTop = () => Math.max(0, contentH() - CLIENT_H)

  beforeEach(() => {
    (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    ;(globalThis as unknown as { ResizeObserver: unknown }).ResizeObserver = FakeResizeObserver
    roCallback = null
    container = document.createElement('div')
    document.body.appendChild(container)
    // MessageList 的滚动容器 = streamWrapRef.current.parentElement = 本 container
    Object.defineProperty(container, 'scrollTop', {
      get: () => rawTop,
      // 对齐浏览器：写入超过最大可滚动量时 clamp 到 scrollHeight - clientHeight
      set: (v: number) => { rawTop = Math.max(0, Math.min(v, maxTop())) },
      configurable: true,
    })
    Object.defineProperty(container, 'scrollHeight', { get: contentH, configurable: true })
    Object.defineProperty(container, 'clientHeight', { get: () => CLIENT_H, configurable: true })
    rawTop = 0
    rowCount = 3
    bottomCalls = []
    useChatStore.setState({
      messages: { [SID]: [baseMsg('u1', 'user'), baseMsg('a1', 'assistant'), baseMsg('a2', 'assistant')] },
      hasMore: { [SID]: false }, streams: {}, streamOrder: {}, streamTicks: {},
    })
    root = createRoot(container)
  })

  afterEach(() => {
    act(() => { root.unmount() })
    container.remove()
    delete (globalThis as unknown as { ResizeObserver?: unknown }).ResizeObserver
  })

  /** 挂载并冲掉贴底收敛（首帧 + 4 帧 rAF + 120ms 定时器），返回后应停在最底部。 */
  const mountStuck = async () => {
    await act(async () => {
      root.render(<Harness onNearBottom={(v) => bottomCalls.push(v)} />)
    })
    await act(async () => { await new Promise((r) => setTimeout(r, 200)) })
    expect(rawTop).toBe(maxTop())
    bottomCalls = []
  }

  it('贴底后内容长高：自己写入触发的 scroll 不被误判成用户上滚（nearBottom 保持 true）', async () => {
    await mountStuck()

    // 模拟「写 scrollTop 之后、scroll 事件派发之前」内容又长高一截（流式高频增量）
    rowCount = 4
    act(() => { container.dispatchEvent(new Event('scroll')) })

    // 距离已 > 60px，但 scrollTop 仍是我们写的值 ⇒ 必须仍算贴底
    expect(contentH() - rawTop - CLIENT_H).toBeGreaterThan(60)
    expect(bottomCalls[bottomCalls.length - 1]).toBe(true)
  })

  it('用户上滚查历史：仍然停跟随（不因上面的兼容判据被拽回底部）', async () => {
    await mountStuck()

    rawTop = Math.max(0, rawTop - 300)
    act(() => { container.dispatchEvent(new Event('scroll')) })

    expect(bottomCalls[bottomCalls.length - 1]).toBe(false)
  })

  it('高度增长（工具卡/思考块出现）：贴底时自动跟随到新底部', async () => {
    await mountStuck()

    // 新增工具卡把内容撑高：无新消息、无流式正文增量 ⇒ 不推进 streamTicks
    rowCount = 5
    act(() => { roCallback?.() })

    expect(rawTop).toBe(maxTop())
  })

  it('高度增长但用户在翻历史：不打扰', async () => {
    await mountStuck()

    rawTop = 0
    act(() => { container.dispatchEvent(new Event('scroll')) })
    expect(bottomCalls[bottomCalls.length - 1]).toBe(false)

    rowCount = 6
    act(() => { roCallback?.() })

    expect(rawTop).toBe(0)
  })
})
