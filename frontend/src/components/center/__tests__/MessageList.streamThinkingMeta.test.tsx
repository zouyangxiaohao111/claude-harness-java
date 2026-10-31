// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { MessageList } from '../MessageList'
import { useChatStore, type StreamBlock } from '@/stores/chatStore'

/**
 * [流式思考块完成态] 思考结束后（块不再是尾块、但仍在流式列表里）也必须显示「耗时 + tokens」。
 *
 * <b>WHY（规则九）</b>：用户实测「思考过程收起后没有 xx秒 / token，刷新页面后才有」。
 *   刷新前这条走 StreamBlockRow（流式块），刷新后走 DB 消息路径 —— 两条路径文案必须同源同格式，
 *   否则用户在「刚答完」和「刷新后」看到两种不同的思考摘要，属于同一份数据的两种呈现。
 *   本用例锁死：非尾块（thinkingRunning=false）的流式思考标题同时带秒数与 tokens。
 */

const SID = 'sess-stream-thinking-meta'
const noop = () => {}

function blk(id: string, seq: number, opts: Partial<StreamBlock> = {}): StreamBlock {
  return {
    assistantMessageId: id, userMessageId: null, reasoning: '', content: '', toolCalls: [],
    reasoningStartedAt: null, reasoningEndedAt: null, renderSeq: seq,
    ...opts,
  }
}

let container: HTMLDivElement
let root: Root

function renderList() {
  root = createRoot(container)
  act(() => {
    root.render(
      <MessageList sessionId={SID} messages={[]} onDelete={noop} conversationId={null}
        thinking={false} onNearBottomChange={noop} onLoadOlder={noop} />,
    )
  })
}

const toggles = () => Array.from(container.querySelectorAll<HTMLButtonElement>('.thinking-toggle'))

describe('[流式思考块完成态] 耗时与 tokens', () => {
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
    act(() => { root?.unmount() })
    container.remove()
    root = undefined as unknown as Root
  })

  it('非尾块（思考已结束）：标题含秒数与 tokens，且不再显示「思考中」', () => {
    // 第一块 = 已结束思考（是尾块之前的块 ⇒ isStreamingTail=false）；第二块占尾位
    act(() => {
      useChatStore.setState({
        streams: {
          [SID]: [
            blk('blk-done', 1_000_000_001, {
              reasoning: '思'.repeat(400),   // 400 字符 ⇒ 100 tokens（ceil(len/4)）
              content: '',
              reasoningStartedAt: 1_000_000,
              reasoningEndedAt: 1_003_500,   // 3.5s
            }),
            blk('blk-tail', 1_000_000_002, { reasoning: '仍在思考' }),
          ],
        },
        streamOrder: { [SID]: ['blk-done', 'blk-tail'] },
      })
    })
    renderList()

    expect(toggles()).toHaveLength(2)
    const done = toggles()[0]!.textContent ?? ''
    expect(done).toContain('思考过程')
    expect(done).toContain('3.5s')
    expect(done).toContain('100 tokens')
    expect(done).not.toContain('思考中')
    // 尾块保持原样（进行中）：思考中 + 不显示耗时/完成态 tokens
    expect(toggles()[1]!.textContent).toContain('思考中')
  })
})

describe('[流式思考块完成态] store 侧耗时打点', () => {
  it('appendReasoning 记起止；正文到达补终点且不覆盖；新建块两字段为 null', () => {
    const s = useChatStore
    s.setState({ streams: {}, streamOrder: {}, streamTicks: {}, liveSeq: {} })
    act(() => {
      s.getState().ensureStreamBlock(SID, 'turn-x', null)
      s.getState().appendReasoning(SID, 'turn-x', '思考一')
    })
    const afterReasoning = s.getState().streams[SID]![0]!
    expect(afterReasoning.reasoningStartedAt).not.toBeNull()
    expect(afterReasoning.reasoningEndedAt).not.toBeNull()

    act(() => { s.getState().appendChunk(SID, 'turn-x', '正文') })
    const afterContent = s.getState().streams[SID]![0]!
    // 已有终点 → 不覆盖（正文逐片追加不会把终点推后）
    expect(afterContent.reasoningEndedAt).toBe(afterReasoning.reasoningEndedAt)
    expect(afterContent.reasoningStartedAt).toBe(afterReasoning.reasoningStartedAt)

    // 新块（无思考）→ 两字段初始为 null
    act(() => { s.getState().ensureStreamBlock(SID, 'turn-y', null) })
    const fresh = s.getState().streams[SID]![1]!
    expect(fresh.reasoningStartedAt).toBeNull()
    expect(fresh.reasoningEndedAt).toBeNull()
  })
})
