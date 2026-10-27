// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { MessageList } from '../MessageList'
import { useChatStore, type StreamBlock } from '@/stores/chatStore'
import type { ChatMessageDto } from '@/api/types'

/**
 * 思考中 token 数：usage 只在一条 assistant 消息**结束时**上报（reasoning delta 不带 usage），
 * 所以流式期间必须先用已收到的 reasoning/正文估算，数字才会在「思考过程中」动起来；
 * message.usage.output_tokens 到达后改用权威值（前导 "~" 消失）。
 *
 * 用户 2026-10-27 反馈：「思考完成后才显示，思考过程中也很多 delta，为什么没展示？」
 */
const SID = 'sess-thinking-tokens'
const noop = () => {}
const EMPTY: ChatMessageDto[] = []

function Harness() {
  const messages = useChatStore((s) => s.messages[SID] ?? EMPTY)
  return (
    <MessageList
      sessionId={SID}
      messages={messages}
      onDelete={noop}
      conversationId={null}
      onLoadOlder={noop}
      onNearBottomChange={noop}
    />
  )
}

let container: HTMLDivElement | undefined
let root: Root | undefined

function setup(blocks: StreamBlock[]) {
  useChatStore.setState({
    messages: { [SID]: [] },
    streams: { [SID]: blocks },
    streamOrder: { [SID]: blocks.map((b) => b.assistantMessageId) },
    serverRunning: {},
  })
  ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  act(() => { root!.render(<Harness />) })
}

function tokenText(): string | null {
  return container!.querySelector('.thinking-tokens')?.textContent ?? null
}

describe('思考中 token 数：流式估算 → usage 权威值', () => {
  beforeEach(() => {
    useChatStore.setState({ messages: {}, streams: {}, streamOrder: {}, serverRunning: {} })
  })
  afterEach(() => {
    if (root) act(() => root!.unmount())
    container?.remove()
    container = undefined
    root = undefined
  })

  it('reasoning 已到、usage 未到 → 立即显示 "~N tokens"（不等消息结束）', () => {
    setup([{
      assistantMessageId: 'a1',
      userMessageId: 'u1',
      reasoning: '我先分析一下这个问题，再给出结论。'.repeat(10),
      content: '',
      toolCalls: [],
    }])

    const txt = tokenText()
    expect(txt).toMatch(/^~\d+ tokens$/)
    expect(txt).not.toBe('~0 tokens')
  })

  it('usage.output_tokens 到达 → 去掉 "~"，显示权威值', () => {
    setup([{
      assistantMessageId: 'a1',
      userMessageId: 'u1',
      reasoning: '思考内容'.repeat(8),
      content: '',
      toolCalls: [],
    }])
    expect(tokenText()).toMatch(/^~/)

    act(() => {
      useChatStore.getState().applyMessageUsage(SID, 'a1', {
        usage: { input_tokens: 10, output_tokens: 1234 },
      })
    })

    expect(tokenText()).toBe('1.2k tokens')
  })

  it('无 reasoning 且无 usage → 不显示数字（不假装 0）', () => {
    setup([{
      assistantMessageId: 'a1',
      userMessageId: 'u1',
      reasoning: '',
      content: '',
      toolCalls: [],
    }])
    expect(container!.querySelector('.thinking-wrap')).toBeNull()
    expect(tokenText()).toBeNull()
  })
})
