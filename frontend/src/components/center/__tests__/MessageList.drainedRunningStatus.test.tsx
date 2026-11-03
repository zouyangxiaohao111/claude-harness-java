// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { MessageList } from '../MessageList'
import { useChatStore } from '@/stores/chatStore'
import type { ChatMessageDto } from '@/api/types'

/**
 * 中途发送的消息被消费（queue.drained）后，「正在执行」必须**立刻**出现在**新那一轮**下面。
 *
 * <b>WHY（规则九 · 用户实测 2026-11-03）</b>：
 *   用户原话：「当前轮次 发送用户消息 被消费后 正在执行 不立即展示……之前全新发送没问题的」。
 *
 * <b>成因</b>：占位原先的判据是**会话级**的 `thinking && streamIds.length === 0`。
 *   · 全新发送：还没有任何块 ⇒ `thinking=true` 且 `streamIds` 为空 ⇒ 占位出现（正常）；
 *   · 中途消费：**旧轮还在跑、它的块还在 streams** ⇒ `streamIds.length > 0` 且
 *     `thinking=false`（因为 `hasStream` 看的是整个会话）⇒ **整个占位不渲染**；
 *     而新轮此刻只有 user 行、没有任何 assistant 行 ⇒ 状态行没有承载位置
 *     ⇒ 新轮下面一条「正在执行」都没有。
 *
 * <b>判据</b>：会话仍在跑（有流式块 ⇒ turnEnded=false）且**最新一轮没有 assistant 行**
 *   ⇒ 必须有且仅有一条状态行 + 一条扫光条。
 */
const SID = 'sess-drained-status'
const noop = () => {}

function baseMsg(id: string, role: 'user' | 'assistant', opts: Partial<ChatMessageDto> = {}): ChatMessageDto {
  return {
    id, sessionId: SID, role, author: role === 'user' ? '你' : 'nexus', content: '',
    reasoning: null, toolCalls: null, finishReason: null, inputTokens: null, outputTokens: null,
    reasoningDurationMs: null, time: null, toolCallId: null, assistantMessageId: null,
    userMessageId: null, subtype: null, isMeta: false, isApiErrorMessage: false, apiError: null,
    error: null, errorDetails: null, matchedRule: null,
    ...opts,
  }
}

const userMsg = (id: string): ChatMessageDto =>
  baseMsg(id, 'user', { content: `问题-${id}`, userMessageId: id, createdAt: new Date().toISOString() })

const assistantMsg = (id: string, turnId: string): ChatMessageDto =>
  baseMsg(id, 'assistant', { reasoning: '思考', finishReason: 'tool_calls', userMessageId: turnId, createdAt: new Date().toISOString() })

let container: HTMLDivElement
let root: Root

function renderList(messages: ChatMessageDto[], thinking?: boolean) {
  root = createRoot(container)
  act(() => {
    root.render(
      <MessageList sessionId={SID} messages={messages} onDelete={noop} conversationId={null}
        thinking={thinking} onNearBottomChange={noop} onLoadOlder={noop} />,
    )
  })
}

const runningToggles = () => container.querySelectorAll('.turn-process-toggle.running')
const scanLines = () => container.querySelectorAll('.exec-scan-line')

describe('[queue.drained] 消费后新轮立刻有「正在执行」', () => {
  beforeEach(() => {
    (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    container = document.createElement('div')
    document.body.appendChild(container)
    useChatStore.setState({
      messages: {}, streams: {}, streamOrder: {}, streamTicks: {}, snippedIds: {}, apiErrors: {},
      changedFiles: {}, hasMore: {}, liveSeq: {},
    })
  })

  afterEach(() => {
    if (root) act(() => root.unmount())
    container.remove()
  })

  it('消费后：旧轮的块仍在 streams（thinking=false）→ 新轮下面仍要有「正在执行」', () => {
    // 旧轮：u1 + 已落库 assistant；新轮：u2 刚被 drained 追加，还没有任何 assistant 行。
    // 旧轮的块还挂在 streams ⇒ App 算出的 thinking = turnRunning && !hasStream = **false**。
    useChatStore.setState({
      streams: { [SID]: [{ assistantMessageId: 'blk-old', userMessageId: 'u1', reasoning: '', content: '', toolCalls: [] }] },
      streamOrder: { [SID]: ['blk-old'] },
    })

    renderList([userMsg('u1'), assistantMsg('a1', 'u1'), userMsg('u2')], false)

    expect(runningToggles().length, '新轮必须有「正在执行」').toBe(1)
    expect(scanLines().length, '扫光条同样要在').toBe(1)
  })

  it('消费后只渲染一条状态行（旧轮的行与占位不得同时出声）', () => {
    useChatStore.setState({
      streams: { [SID]: [{ assistantMessageId: 'blk-old', userMessageId: 'u1', reasoning: '', content: '', toolCalls: [] }] },
      streamOrder: { [SID]: ['blk-old'] },
    })

    renderList([userMsg('u1'), assistantMsg('a1', 'u1'), userMsg('u2')], false)

    expect(runningToggles().length, '状态行恒一条').toBe(1)
    expect(scanLines().length, '扫光条恒一条').toBe(1)
  })

  it('全新发送（无任何块、thinking=true）→ 仍然恰好一条', () => {
    renderList([userMsg('u1')], true)
    expect(runningToggles().length).toBe(1)
    expect(scanLines().length).toBe(1)
  })

  it('会话空闲（无块、thinking=false）→ 不显示状态行', () => {
    renderList([userMsg('u1'), assistantMsg('a1', 'u1')], false)
    expect(runningToggles().length, '空闲时不该有「正在执行」').toBe(0)
  })
})
