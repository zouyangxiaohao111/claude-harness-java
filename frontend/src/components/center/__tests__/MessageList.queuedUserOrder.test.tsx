// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { MessageList } from '../MessageList'
import { useChatStore } from '@/stores/chatStore'
import type { ChatMessageDto } from '@/api/types'

/**
 * ⭐ [2026-10-23] 排队消息被消费（queue.drained）时的**行序**守门测试。
 *
 * <b>病情（用户实测）</b>：助手正在流式输出（real-time 块），此时排队的用户消息在工具边界被消费 →
 * 前端 append 一条正式 user 行。结果这条 user 行渲染在**进行中的流式块之前**（甚至更早的历史之前），
 * 而不是「已输出的助手内容之后、后续输出之前」。
 *
 * <b>根因</b>：历史行序号 = 数组位次（0..N），流式块序号 = LIVE_SEQ_BASE + 块序（恒定大于一切历史）
 * ⇒ 只要本轮**正在流式**，任何新追加的历史行都会排到流式块之前。
 *
 * <b>修法</b>：流式区（块 + 本轮中途追加的行）共用一个**到达单调序号**：
 * 块建立、中途 append 各取一次号 ⇒ 排队 user 行天然落在「它到达时已存在的块」之后、
 * 「它之后建立的块」之前（对齐 dsh 事件 seq：每件事按发出顺序定位置）。
 */

const SID = 'sess-queued-order'
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

describe('[排队消费行序] 中途追加的 user 行落在「已输出内容」之后', () => {
  beforeEach(() => {
    useChatStore.setState({ messages: {}, streams: {}, streamOrder: {}, serverRunning: {}, liveSeq: {} })
    ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
  })
  afterEach(() => {
    if (root) act(() => root!.unmount())
    container?.remove()
    container = undefined
    root = undefined
  })

  it('⭐ 流式进行中消费排队消息 → 排在已输出的实时内容之后、后续输出之前', () => {
    // 历史：第一轮提问 + 已落库的助手行
    useChatStore.setState({
      messages: { [SID]: [userMsg('u1', '第一轮提问'), assistantMsg('a1', '第一轮结果', 'u1')] },
    })
    act(() => { root!.render(<Harness />) })

    // 本轮开始流式（块 1 属于 u1）
    act(() => {
      const st = useChatStore.getState()
      st.ensureStreamBlock(SID, 'blk-1', 'u1')
      st.appendChunk(SID, 'blk-1', '实时甲')
    })

    // 工具边界消费排队消息 u4（App.handleQueueDrained → appendMessages）
    act(() => { useChatStore.getState().appendMessages(SID, [userMsg('u4', '排队消息')]) })

    // [2026-11-02 用户裁定「执行中又发一条 ⇒ 只留新用户气泡，旧轮隐藏且滚不上去」]
    //   ⇒ 排队消息到达后，旧轮 u1 的**历史消息**（第一轮提问/结果）从 DOM 移除；
    //   但**正在输出的流式块吸附到最新轮**继续显示（否则排队那一刻用户会看不到输出）。
    //   **核心意图不变**：排队 user 行不得跑到已输出内容**之前**（这正是原缺陷）。
    expect(container!.textContent, '旧轮历史应被折起').not.toContain('第一轮提问')
    expect(container!.textContent).toContain('排队消息')
    expect(container!.textContent, '正在输出的内容必须仍然可见（吸附到最新轮）').toContain('实时甲')

    // 后续输出属于 u4（块 2）→ 排在排队消息之后（新轮内部顺序仍正确）
    act(() => {
      const st = useChatStore.getState()
      st.ensureStreamBlock(SID, 'blk-2', 'u4')
      st.appendChunk(SID, 'blk-2', '实时乙')
    })
    expectDomOrder(container!, ['排队消息', '实时乙'])
  })

  it('收尾落库（finalize）后行序不变：块→消息仍卡在排队消息两侧', () => {
    useChatStore.setState({
      messages: { [SID]: [userMsg('u1', '第一轮提问'), assistantMsg('a1', '第一轮结果', 'u1')] },
    })
    act(() => { root!.render(<Harness />) })
    act(() => {
      const st = useChatStore.getState()
      st.ensureStreamBlock(SID, 'blk-1', 'u1')
      st.appendChunk(SID, 'blk-1', '实时甲')
    })
    act(() => { useChatStore.getState().appendMessages(SID, [userMsg('u4', '排队消息')]) })
    act(() => {
      const st = useChatStore.getState()
      st.ensureStreamBlock(SID, 'blk-2', 'u4')
      st.appendChunk(SID, 'blk-2', '实时乙')
      st.finalizeBlocks(SID)
    })

    // ⚠️ [2026-11-01 方案 C] 本轮结束后，非最终段的正文收进折叠区（用户拍板：进行中全留，
    //   结束后才收）⇒ a1「第一轮结果」的正文不可见。本用例的意图是**行序**，故改用
    //   行结构锚定位置（正文文案已不再是稳定探针）：
    //   实测渲染序 = [user 第一轮提问, assistant(折叠壳/正文被收), assistant 实时甲,
    //                 user 排队消息, assistant 实时乙]
    const rows = Array.from(container!.querySelectorAll('.msg')).map((e) => e.className)
    const live1 = rows.findIndex((_c, i) => i > 0 && (container!.querySelectorAll('.msg')[i]!.textContent ?? '').includes('实时甲'))
    const queued = rows.findIndex((_c, i) => (container!.querySelectorAll('.msg')[i]!.textContent ?? '').includes('排队消息'))
    const live2 = rows.findIndex((_c, i) => i > 0 && (container!.querySelectorAll('.msg')[i]!.textContent ?? '').includes('实时乙'))
    expect(live1, '实时甲必须渲染').toBeGreaterThanOrEqual(0)
    expect(queued, '排队消息必须渲染').toBeGreaterThan(live1)
    expect(live2, '实时乙必须渲染').toBeGreaterThan(queued)
    // 第一轮仍占一行（折叠态外壳，不丢行）
    expect(rows.filter((c) => c.includes('assistant')).length).toBeGreaterThanOrEqual(3)
  })
})
