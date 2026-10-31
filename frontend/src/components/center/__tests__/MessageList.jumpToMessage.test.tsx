// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MessageList } from '../MessageList'
import { useChatStore } from '@/stores/chatStore'
import type { ChatMessageDto } from '@/api/types'

/**
 * [历史提问跳转] 行点击后：滚动到对应消息 + 短暂高亮；分页外先补拉更早页，仍找不到则明确提示。
 *
 * <b>WHY（规则九）</b>：历史提问列表来自全量 user 消息，而对话区只渲染有界窗口。
 * 若目标在窗口外就静默不动，用户会以为点击坏了；若只跳转不高亮，用户无法确认「到了哪一条」。
 */

const SID = 'sess-jump'
const EMPTY: ChatMessageDto[] = []

function baseMsg(id: string, role: 'user' | 'assistant'): ChatMessageDto {
  return {
    id, sessionId: SID, role, author: role === 'user' ? '你' : 'nexus', content: `内容-${id}`,
    reasoning: null, toolCalls: null, finishReason: null, inputTokens: null, outputTokens: null,
    reasoningDurationMs: null, time: null, toolCallId: null, assistantMessageId: null,
    userMessageId: role === 'user' ? id : null, subtype: null, isMeta: false,
    isApiErrorMessage: false, apiError: null, error: null, errorDetails: null, matchedRule: null,
  }
}

/** 复刻 App：messages 必须来自 store 订阅（补拉 prepend 后组件才能看到新行）。 */
function Harness({ jump, onLoadOlder }: {
  jump: { id: string; nonce: number } | null
  onLoadOlder: (sid: string) => void | Promise<void>
}) {
  const messages = useChatStore((s) => s.messages[SID] ?? EMPTY)
  return (
    <MessageList sessionId={SID} messages={messages} onDelete={() => {}} conversationId={null}
      onNearBottomChange={() => {}} onLoadOlder={onLoadOlder} jumpToMessage={jump} />
  )
}

let container: HTMLDivElement
let root: Root
let scrollSpy: ReturnType<typeof vi.fn>

function renderHarness(jump: { id: string; nonce: number } | null, onLoadOlder: (sid: string) => void | Promise<void>) {
  root = createRoot(container)
  act(() => { root.render(<Harness jump={jump} onLoadOlder={onLoadOlder} />) })
}

const wait = (ms: number) => act(async () => { await new Promise((r) => setTimeout(r, ms)) })

describe('[历史提问跳转] 定位 + 高亮', () => {
  beforeEach(() => {
    (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    container = document.createElement('div')
    document.body.appendChild(container)
    scrollSpy = vi.fn()
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { value: scrollSpy, configurable: true, writable: true })
    useChatStore.setState({
      messages: {}, streams: {}, streamOrder: {}, streamTicks: {}, snippedIds: {}, apiErrors: {},
      changedFiles: {}, hasMore: {}, liveSeq: {},
    })
  })

  afterEach(() => {
    act(() => { root?.unmount() })
    container.remove()
    root = undefined as unknown as Root
    delete (HTMLElement.prototype as unknown as { scrollIntoView?: unknown }).scrollIntoView
  })

  it('目标在当前窗口 → 滚动到该行并加短暂高亮 class', async () => {
    useChatStore.setState({ messages: { [SID]: [baseMsg('u1', 'user'), baseMsg('a1', 'assistant')] } })
    renderHarness({ id: 'u1', nonce: 1 }, () => {})
    await wait(80)

    const row = container.querySelector<HTMLElement>('[data-msg-id="u1"]')
    expect(row).not.toBeNull()
    expect(row!.className).toContain('jump-highlight')
    expect(scrollSpy).toHaveBeenCalledTimes(1)
  })

  it('目标在分页外 → 复用 onLoadOlder 补拉更早页后再定位（不静默失败）', async () => {
    useChatStore.setState({ messages: { [SID]: [baseMsg('u2', 'user')] }, hasMore: { [SID]: true } })
    const onLoadOlder = vi.fn((sid: string) => {
      useChatStore.getState().prependMessages(sid, [baseMsg('u1', 'user'), baseMsg('a1', 'assistant')], false)
    })
    renderHarness({ id: 'u1', nonce: 1 }, onLoadOlder)
    await wait(150)

    expect(onLoadOlder).toHaveBeenCalledWith(SID)
    const row = container.querySelector<HTMLElement>('[data-msg-id="u1"]')
    expect(row).not.toBeNull()
    expect(row!.className).toContain('jump-highlight')
  })

  it('补拉不到 / 已无更早页 → 显示「该提问不在当前窗口」', async () => {
    useChatStore.setState({ messages: { [SID]: [baseMsg('u2', 'user')] }, hasMore: { [SID]: false } })
    renderHarness({ id: 'missing', nonce: 1 }, () => {})
    await wait(80)

    const miss = container.querySelector('.ml-jump-miss')
    expect(miss).not.toBeNull()
    expect(miss!.textContent).toContain('该提问不在当前窗口')
  })
})
