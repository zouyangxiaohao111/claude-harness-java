// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { MessageList } from '../MessageList'
import { useChatStore, type StreamBlock } from '@/stores/chatStore'

/**
 * [收口重渲] 流式块在「该轮已结束」时必须按**非流式**渲染。
 *
 * <b>用户原话</b>：「流式输出有问题，但输出完成后点击展开他就好了 —— 能否在输出完成时
 * 让最后一轮正文全局再渲染一次（非流式）？」
 *
 * <b>根因</b>：`StreamBlockRow` 里 `<MarkdownTextV2 streaming ...>` 曾是**恒 true** 的字面量，
 * 与「这一块还在长吗」无关 ⇒ 只要块还在 `streams` 里就永远走流式臂（不跑 rescue），
 * 粘连的 `##标题` / 表格保持裸露；而点开折叠区等操作触发一次重渲后，若此时已不在流式态，
 * 就会走收口臂（跑 rescue）⇒ 视觉上「点一下就好了」。
 *
 * <b>修法</b>：`streaming = isStreamingTail && turnRunning`，其中 `turnRunning` 是父级传入的
 * 服务端运行态 `thinking`。⛔ 不能用 `activeTurnKeys` —— 它遍历的正是 `streamIds`，
 * 只要块在，它对每一轮恒为 true，无法区分「还在长」与「已写完但块未收走」。
 *
 * <b>本用例锁死</b>：同一份粘连 Markdown，服务端 idle（thinking=false）时必须渲染出
 * `<h2>` / `<table>`（证明走了 rescue）；服务端仍在跑（thinking=true）时保持字面（流式臂）。
 */

const SID = 'sess-stream-settle-rerender'
const noop = () => {}
/** 真库 msg 38576df2 的粘连形态：`##标题` 与表格首行粘成一行。 */
const GLUED = '##最终实现的语义|专家 |工具 |\n|---|---|\n|未声明 | ✅ | 注入 |\n'

function blk(id: string, seq: number, opts: Partial<StreamBlock> = {}): StreamBlock {
  return {
    assistantMessageId: id, userMessageId: null, reasoning: '', content: '',
    toolCalls: [], reasoningStartedAt: null, reasoningEndedAt: null, renderSeq: seq,
    ...opts,
  }
}

let container: HTMLDivElement
let root: Root

function renderList(thinking: boolean) {
  if (root) act(() => { root.unmount() })
  root = createRoot(container)
  act(() => {
    root.render(
      <MessageList sessionId={SID} messages={[]} onDelete={noop} conversationId={null}
        thinking={thinking} onNearBottomChange={noop} onLoadOlder={noop} />,
    )
  })
}

const countIn = (sel: string) => container.querySelectorAll(sel).length

describe('[收口重渲] 流式块在该轮结束后按非流式渲染', () => {
  beforeEach(() => {
    (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    container = document.createElement('div')
    document.body.appendChild(container)
    useChatStore.setState({
      messages: {}, streams: {}, streamOrder: {}, streamTicks: {}, snippedIds: {},
      apiErrors: {}, changedFiles: {}, hasMore: {}, liveSeq: {},
    })
    // 一个仍在 streams 里的块，内容已写满（粘连 markdown）
    act(() => {
      useChatStore.setState({
        streams: { [SID]: [blk('blk-settle', 1, { content: GLUED })] },
        streamOrder: { [SID]: ['blk-settle'] },
      })
    })
  })

  afterEach(() => {
    act(() => { root?.unmount() })
    container.remove()
    root = undefined as unknown as Root
  })

  it('服务端已 idle（thinking=false）：块仍在 streams 也必须走收口臂 → 粘连被 rescue 修好', () => {
    renderList(false)
    expect(countIn('.md-content h2')).toBe(1)
    expect(countIn('.md-content table')).toBe(1)
    // `##` 不再以字面出现在正文里
    const text = container.querySelector('.md-content')?.textContent ?? ''
    expect(text).not.toContain('##最终实现的语义')
  })

  it('服务端仍在跑（thinking=true）且是尾块：保持流式臂（不跑 rescue）', () => {
    renderList(true)
    expect(countIn('.md-content h2')).toBe(0)
    expect(countIn('.md-content table')).toBe(0)
  })

  it('⭐ 流式结束那一刻（thinking true → false）必须完整重渲一次正文', () => {
    // 用户诉求原话：「流式结束重新完整渲染一次正文不行吗」
    // 这条用例就是在验证这件事：props 里 streaming 由 true 变 false，
    // MarkdownTextV2 的 useMemo 依赖 [text, streaming] 变化 ⇒ 整份 HTML 重新生成。
    renderList(true)
    expect(countIn('.md-content h2')).toBe(0)      // 流式中：粘连保持字面
    expect(countIn('.md-content table')).toBe(0)

    // 服务端 idle（模拟 complete 到达）—— 同一块、同一份 text，只翻 turnRunning
    renderList(false)
    expect(countIn('.md-content h2')).toBe(1)      // 已收口：完整重渲后表头/表格回来
    expect(countIn('.md-content table')).toBe(1)
  })
})
