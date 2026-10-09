// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { MessageList } from '../MessageList'
import { useChatStore } from '@/stores/chatStore'
import type { ChatMessageDto } from '@/api/types'

/**
 * ⭐ [sm-boundary-reload] 渲染**归组序**守门测试（真 MessageList + 真 store，无 mock）。
 *
 * <b>WHY（规则九 · 测试验证意图）</b>：即时层（message.insert 插 boundary 行）与对账层（round 收尾重拉）
 * 都只保证「boundary 行在 messages 数组里」；而用户看到的是**渲染序**。boundary 行 userMessageId=null，
 * 旧 turnKeyOf（{@code userMessageId ?? id}）让它自成一键、且被插在列表尾 ⇒ 本轮流式块并回「更靠前的
 * user 组」后，boundary 被排到后面 = 用户看到「回复开始了，分割线还没出现/跑到回复后面」。
 * 这类「归组键」缺陷纯 store 层测不到（数组序是对的），必须真渲染 + 真 DOM 序才能钉住。
 *
 * <b>断言手法</b>：textContent 按**文档序**拼接 ⇒ 子串 indexOf 的先后 = 渲染先后（比「某文字出现过」强，
 * 后者在错序时照样通过）。
 */

const SID = 'sess-boundary-order'
const BOUNDARY_LABEL = '已压缩 · 对话历史已总结'   // MessageList 的 compact_boundary 分界线文案
const noop = () => {}

/** 稳定空引用（store selector 每次新 [] 会触发无谓重渲）。 */
const EMPTY_MESSAGES: ChatMessageDto[] = []

/** 最小 ChatMessageDto（同目录既有写法）。 */
function baseMsg(id: string, role: 'user' | 'assistant' | 'system', content: string): ChatMessageDto {
  return {
    id, sessionId: SID, role, author: role === 'user' ? '你' : role === 'system' ? 'system' : 'nexus', content,
    reasoning: null, toolCalls: null, finishReason: null, inputTokens: null, outputTokens: null,
    reasoningDurationMs: null, time: null, toolCallId: null, assistantMessageId: null,
    userMessageId: null, subtype: null, isMeta: false, isApiErrorMessage: false, apiError: null,
    error: null, errorDetails: null, matchedRule: null,
  }
}

/** user 行：wire 契约 = userMessageId 指向自身。 */
const userMsg = (id: string, content: string): ChatMessageDto =>
  ({ ...baseMsg(id, 'user', content), userMessageId: id })

/** assistant 行：归属其 user 的 flow。 */
const assistantMsg = (id: string, content: string, uid: string): ChatMessageDto =>
  ({ ...baseMsg(id, 'assistant', content), userMessageId: uid })

/** compact boundary 行：与后端 MessageInsertEvent.messages 元素同形（⚠️ 无 userMessageId = 本病的根因）。 */
const boundaryMsg = (id: string): ChatMessageDto =>
  ({ ...baseMsg(id, 'system', 'Conversation compacted'), author: 'system', subtype: 'compact_boundary' })

/** DOM 序判据：全部命中，且位置按给定顺序递增（textContent 按文档序拼接 → indexOf 序 = 渲染序）。 */
function expectDomOrder(container: HTMLElement, needles: string[]) {
  const text = container.textContent ?? ''
  const idx = needles.map((n) => text.indexOf(n))
  needles.forEach((n, i) => {
    expect(idx[i], `未渲染：「${n}」`).toBeGreaterThanOrEqual(0)
  })
  for (let i = 1; i < idx.length; i++) {
    expect(idx[i], `「${needles[i]}」应渲染在「${needles[i - 1]}」之后（实际序不符）`).toBeGreaterThan(idx[i - 1])
  }
}

/** 真实 App 形态：messages 从 store 订阅（insertServerMessages / 流式块注入后自动重渲）+ 真 MessageList。 */
function Harness() {
  const messages = useChatStore((s) => s.messages[SID] ?? EMPTY_MESSAGES)
  return (
    <MessageList sessionId={SID} messages={messages} onDelete={noop} conversationId={null}
      onLoadOlder={noop} onNearBottomChange={noop} />
  )
}

let container: HTMLDivElement
let root: Root

describe('[sm-boundary-reload] boundary 行渲染归组序（分割线必须在流式回复之前）', () => {
  beforeEach(() => {
    (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    useChatStore.setState({ messages: {}, streams: {}, streamOrder: {}, streamTicks: {}, snippedIds: {}, apiErrors: {} })
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    act(() => { root.render(<Harness />) })
  })

  afterEach(() => {
    act(() => { root.unmount() })
    container.remove()
  })

  it('A：message.insert 插入 boundary 行 → DOM 序 = USER → BOUNDARY（不等收尾即可见）', () => {
    act(() => { useChatStore.getState().setMessages(SID, [userMsg('u1', '第一条用户消息')]) })
    expectDomOrder(container, ['第一条用户消息'])

    act(() => { useChatStore.getState().insertServerMessages(SID, [boundaryMsg('b1')]) })
    expectDomOrder(container, ['第一条用户消息', BOUNDARY_LABEL])
  })

  it('⭐ B：随后本轮开始流式（streams 有块）→ DOM 序仍 = USER → BOUNDARY → STREAM（回复开始之前）', () => {
    act(() => { useChatStore.getState().setMessages(SID, [userMsg('u1', '第一条用户消息')]) })
    act(() => { useChatStore.getState().insertServerMessages(SID, [boundaryMsg('b1')]) })

    // 本轮开始流式：块归属 u1（首 chunk 的 userMessageId）→ 与 user 同组；旧归组规则下它会插到 boundary 之前
    act(() => {
      useChatStore.getState().ensureStreamBlock(SID, 'blk-1', 'u1')
      useChatStore.getState().appendChunk(SID, 'blk-1', '流式正文')
    })

    expectDomOrder(container, ['第一条用户消息', BOUNDARY_LABEL, '流式正文'])
  })

  it('C：中途轮（旧轮已定稿 + 新 user + boundary + 流式块）→ boundary 贴在本轮 user 组内、回复之前', () => {
    act(() => {
      useChatStore.getState().setMessages(SID, [
        userMsg('u0', '旧用户消息'),
        assistantMsg('a0', '旧回复正文', 'u0'),
        userMsg('u1', '新用户消息'),
      ])
    })
    act(() => { useChatStore.getState().insertServerMessages(SID, [boundaryMsg('b1')]) })
    act(() => {
      useChatStore.getState().ensureStreamBlock(SID, 'blk-1', 'u1')
      useChatStore.getState().appendChunk(SID, 'blk-1', '流式正文')
    })

    expectDomOrder(container, ['旧用户消息', '旧回复正文', '新用户消息', BOUNDARY_LABEL, '流式正文'])
  })
})
