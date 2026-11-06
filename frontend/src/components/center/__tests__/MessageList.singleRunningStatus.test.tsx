// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { MessageList } from '../MessageList'
import { useChatStore } from '@/stores/chatStore'
import type { ChatMessageDto } from '@/api/types'

/**
 * 同一轮**只能有一条**「正在执行」状态行（+ 一条扫光条）。
 *
 * <b>WHY（规则九 · 用户实测 2026-11-03）</b>：
 *   用户原话：「现在会渲染两个 正在执行 下面一个上面一个……等模型回复了 不能重复的呀」。
 *
 *   成因：`thinking`（= turnRunning && !hasStream）在本轮**已有落库 assistant、模型正在憋下一段**
 *   时同样为 true。此时那道落库行自己会渲染状态行（本轮进行中 ⇒ `activeTurnKeys` 命中），
 *   而「思考中」占位也画了一条 ⇒ 同一轮两条、时长完全相同。
 *
 *   ⇒ 占位的状态行/扫光条必须与「本轮已有 assistant 行」**互斥**：有行就让位，
 *     只在「刚发送、还什么都没有」时由占位承载。
 *
 * <p>⚠️ 判据是**数量恒为 1**（不是「占位有没有画」）：无论最终由谁承载，
 * 用户看到的必须且只能是一条 —— 这才是用户的诉求。
 */
const SID = 'sess-single-running'
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
  baseMsg(id, 'user', { content: '问题', userMessageId: id, createdAt: new Date().toISOString() })

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

describe('[状态行] 同一轮只允许一条「正在执行」', () => {
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

  it('刚发送、本轮还没有任何 assistant 行 → 占位承载，恰好一条', () => {
    renderList([userMsg('u1')], true)
    expect(runningToggles().length, '状态行恒一条').toBe(1)
    expect(scanLines().length, '扫光条恒一条').toBe(1)
    // [2026-11-04 用户裁定] 占位里那句**静态**「思考中…」去掉 —— 用户原话：
    //   「发送消息 还没收到流 此时 正在执行下面还有个思考中… 这个静态的 不应该有的」。
    // 判据用「占位节点内有没有 .thinking-wrap」：⛔ 不比对整篇 textContent
    //   （组件内联 <style> 里含「思考中…」注释字样，会假红）。
    const placeholder = container.querySelector('.msg.assistant:not([data-msg-id])')
    expect(placeholder, '刚发送时占位必须存在（正在执行 + 扫光条）').not.toBeNull()
    expect(placeholder!.querySelector('.thinking-wrap'),
      '占位里的静态「思考中…」不该再有').toBeNull()
  })

  it('本轮已有落库 assistant、模型在憋下一段（thinking=true）→ 也只有一条', () => {
    // 这是用户截图里的形态：下面一个（落库行）、上面一个（占位）→ 重复。
    renderList([userMsg('u1'), assistantMsg('a1', 'u1')], true)
    expect(runningToggles().length, '落库行已承载状态行 ⇒ 占位不得再画').toBe(1)
    expect(scanLines().length, '扫光条同样恒一条').toBe(1)
  })

  it('运行中不重复，且「思考中…」仍可见（让位的是状态行，不是整个占位）', () => {
    renderList([userMsg('u1'), assistantMsg('a1', 'u1')], true)
    const thinking = Array.from(container.querySelectorAll('.thinking-wrap'))
    // 「思考中…」只在占位里出现（落库行追的是「思考过程」，条件不同）⇒ 仍应有这条线索
    expect(thinking.length, '思考中线索不得被一起删掉').toBeGreaterThan(0)
  })
})
