// @vitest-environment jsdom
/**
 * [流式性能 C1] 思考块「前缀冻结 + 尾段替换」的判据。
 *
 * <p><b>WHY 必须锁死</b>：思考块流式期每帧变化，原实现把整段文本放进一个文本节点 ⇒
 * 每帧替换全文（O(全文) 布局）。前缀冻结后，前部文本节点**跨帧不得变化**，
 * 只有尾节点增长 —— 否则改造只是换了写法、成本照旧。
 *
 * <p>判据用「第一个子文本节点的长度」：它代表前缀节点（冻结段）。追加文本后它必须不变，
 * 而 `textContent` 总长必须增长（内容不丢）。
 */
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { MessageList } from '../MessageList'
import { useChatStore, type StreamBlock } from '@/stores/chatStore'

const SID = 'sess-frozen-thinking'
const noop = () => {}

function blk(id: string, seq: number, opts: Partial<StreamBlock> = {}): StreamBlock {
  return {
    assistantMessageId: id, userMessageId: null, reasoning: '', content: '',
    toolCalls: [], reasoningStartedAt: null, reasoningEndedAt: null, renderSeq: seq,
    ...opts,
  }
}

/** 含换行的长思考文本（>2KB ⇒ 首帧就会切出冻结前缀）。 */
function longReasoning(tag: string): string {
  const line = `${tag}：这一段思路讨论边界条件、失败路径与可观测性，长度足以触发前缀冻结；`
    + '还要考虑并发、超时、重试与降级路径，以及日志如何留痕、指标如何归因。\n'
  return line.repeat(30)
}

let container: HTMLDivElement
let root: Root

function renderList(turnRunning: boolean) {
  act(() => {
    root.render(
      <MessageList sessionId={SID} messages={[]} onDelete={noop} conversationId={null}
        thinking={false} turnRunning={turnRunning} onNearBottomChange={noop} onLoadOlder={noop} />,
    )
  })
}

/** 已冻结段（每个 ≥1KB 的整行段；它们的 DOM 内容跨帧不得变化）。 */
const frozenSegs = (): string[] =>
  Array.from(container.querySelectorAll('.thinking-body > span')).map((el) => el.textContent ?? '')
const thinkingLen = (): number => container.querySelector('.thinking-body')?.textContent?.length ?? 0

describe('[流式性能 C1] 思考块前缀冻结', () => {
  beforeEach(() => {
    (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    useChatStore.setState({
      messages: {}, streams: {}, streamOrder: {}, streamTicks: {}, streamShape: {},
      snippedIds: {}, apiErrors: {}, changedFiles: {}, hasMore: {}, liveSeq: {},
    })
    act(() => {
      useChatStore.setState({
        streams: { [SID]: [blk('blk-think', 1, { reasoning: longReasoning('第一段') })] },
        streamOrder: { [SID]: ['blk-think'] },
      })
    })
  })

  afterEach(() => {
    act(() => { root?.unmount() })
    container.remove()
  })

  it('追加思考文本时：已冻结段内容不变，总内容增长（不丢字）', () => {
    renderList(true)
    const segsBefore = frozenSegs()
    const totalBefore = thinkingLen()
    expect(segsBefore.length).toBeGreaterThan(0)              // 已切出冻结段
    // 段长≈阈值 512（对齐到阈值附近的行边界；最后一个换行可能早于阈值）
    expect(segsBefore[0]!.length).toBeGreaterThan(200)
    expect(totalBefore).toBeGreaterThan(2000)

    act(() => {
      useChatStore.getState().appendReasoning(SID, 'blk-think', '追加的一段新思路，用来验证冻结段不被重写。\n')
    })

    const segsAfter = frozenSegs()
    // 已冻结段逐字节不变（DOM 不再重写前部文本）
    for (let i = 0; i < segsBefore.length; i++) {
      expect(segsAfter[i]).toBe(segsBefore[i])
    }
    expect(thinkingLen()).toBeGreaterThan(totalBefore)         // 内容确实增长
  })

  it('非追加（文本被整体替换）时冻结段清空并整体重建', () => {
    renderList(true)
    expect(frozenSegs().length).toBeGreaterThan(0)

    act(() => {
      useChatStore.setState((st) => ({
        streams: { ...st.streams, [SID]: [blk('blk-think', 1, { reasoning: '完全不同的新思考内容\n'.repeat(60) })] },
      }))
    })

    // 重建后总内容与新文本一致（没有把旧前缀粘在新文本前面）；cleanReasoning 会 trim 尾部换行
    expect(thinkingLen()).toBe('完全不同的新思考内容\n'.repeat(60).trim().length)
  })
})
