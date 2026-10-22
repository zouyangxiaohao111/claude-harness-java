// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { MessageList } from '../MessageList'
import { useChatStore, type StreamBlock } from '@/stores/chatStore'
import type { ChatMessageDto } from '@/api/types'

/**
 * ⭐ [2026-10-22 · 结构对齐 dsh anchorSeq] 渲染行序守门测试。
 *
 * <b>病情</b>：流式块冻结的 `userMessageId` 一旦指向**上一轮**（本轮 user 尚未落历史 / 后台通知唤醒），
 * 旧实现按「组首次出现位置」排列 ⇒ 实时打字被插进历史中间，后面又冒出更晚的历史消息。
 * 用户实测顺序：`历史1 历史2 工具1 工具2 实时1 实时2 历史工具1 历史工具2`。
 *
 * <b>修法</b>（本测试锁死）：每行带单调 anchorSeq，渲染前统一排序 ——
 * 历史 = 递增位次；流式块 = LIVE_SEQ_BASE + 块序（恒大于一切历史）⇒ **进行中的输出永远在末尾**，
 * 与块的 userMessageId 指向哪一轮无关（对齐 dsh chat-snapshot-builder.ts:135 orderedVisible）。
 *
 * <b>反向实验（必须红）</b>：把 LIVE_SEQ_BASE 改回按 userMessageId 组长序（即删除排序）⇒ 本用例
 * 断言「实时内容在最后」失败（块落回 u1 轮内）。
 */

const SID = 'sess-anchor-seq'
const noop = () => {}
const EMPTY_MESSAGES: ChatMessageDto[] = []

function baseMsg(id: string, role: 'user' | 'assistant', content: string): ChatMessageDto {
  return {
    id, sessionId: SID, role, author: role === 'user' ? '你' : 'nexus', content,
    reasoning: null, toolCalls: null, finishReason: null, inputTokens: null, outputTokens: null,
    reasoningDurationMs: null, time: null, toolCallId: null, assistantMessageId: null,
    userMessageId: null, subtype: null, isMeta: false, isApiErrorMessage: false, apiError: null,
    error: null, errorDetails: null, matchedRule: null,
  }
}

const userMsg = (id: string, content: string): ChatMessageDto =>
  ({ ...baseMsg(id, 'user', content), userMessageId: id })
const assistantMsg = (id: string, content: string, uid: string): ChatMessageDto =>
  ({ ...baseMsg(id, 'assistant', content), userMessageId: uid })

function expectDomOrder(container: HTMLElement, needles: string[]) {
  const text = container.textContent ?? ''
  const idx = needles.map((n) => text.indexOf(n))
  needles.forEach((n, i) => expect(idx[i], `未渲染：「${n}」`).toBeGreaterThanOrEqual(0))
  for (let i = 1; i < idx.length; i++) {
    expect(idx[i], `「${needles[i]}」应渲染在「${needles[i - 1]}」之后（实际序不符）`).toBeGreaterThan(idx[i - 1])
  }
}

function Harness() {
  const messages = useChatStore((s) => s.messages[SID] ?? EMPTY_MESSAGES)
  return (
    <MessageList sessionId={SID} messages={messages} onDelete={noop} conversationId={null}
      onLoadOlder={noop} onNearBottomChange={noop} />
  )
}

let container: HTMLDivElement | undefined
let root: Root | undefined

function setup(messages: ChatMessageDto[], blocks: StreamBlock[]) {
  useChatStore.setState({
    messages: { [SID]: messages },
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

describe('[anchorSeq] 流式块恒渲染在末尾（不按 userMessageId 回插历史）', () => {
  beforeEach(() => { useChatStore.setState({ messages: {}, streams: {}, streamOrder: {}, serverRunning: {} }) })
  afterEach(() => {
    if (root) act(() => root!.unmount())
    container?.remove()
    container = undefined
    root = undefined
  })

  it('⭐ 块的 userMessageId 指向旧轮 ⇒ 实时内容仍在最末（不得插进历史中间）', () => {
    setup(
      [
        userMsg('u1', '第一轮提问'),
        assistantMsg('a1', '第一轮工具结果', 'u1'),
        userMsg('u2', '第二轮提问'),
        assistantMsg('a2', '第二轮工具结果', 'u2'),
      ],
      [{
        assistantMessageId: 'blk-live', userMessageId: 'u1',   // ← 旧轮（故障现场）
        reasoning: '', content: '当前实时内容', toolCalls: [],
      }],
    )

    // 关键断言：实时内容必须排在【所有历史】之后（旧实现会插到 u1 组内 = a1 与 u2 之间）
    expectDomOrder(container!, ['第一轮提问', '第一轮工具结果', '第二轮提问', '第二轮工具结果', '当前实时内容'])
  })

  it('正常归属（块属于最后一轮）→ 仍渲染在末尾（行为不变）', () => {
    setup(
      [userMsg('u1', '提问'), assistantMsg('a1', '结果', 'u1')],
      [{ assistantMessageId: 'blk-ok', userMessageId: 'u1', reasoning: '', content: '实时输出', toolCalls: [] }],
    )
    expectDomOrder(container!, ['提问', '结果', '实时输出'])
  })

  it('多个流式块按 streamOrder 顺序渲染在末尾', () => {
    setup(
      [userMsg('u1', '提问'), assistantMsg('a1', '结果', 'u1')],
      [
        { assistantMessageId: 'blk-1', userMessageId: 'u1', reasoning: '', content: '实时甲', toolCalls: [] },
        { assistantMessageId: 'blk-2', userMessageId: 'u1', reasoning: '', content: '实时乙', toolCalls: [] },
      ],
    )
    expectDomOrder(container!, ['提问', '结果', '实时甲', '实时乙'])
  })
})
