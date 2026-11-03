// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { MessageList } from '../MessageList'
import { useChatStore } from '@/stores/chatStore'
import type { ChatMessageDto, ToolCallDto } from '@/api/types'

/**
 * [并行工具组] 组头**不得复述**组内首个工具。
 *
 * <b>WHY（规则九 · 用户实测 2026-11-03）</b>：
 *   用户报「切换会话后同一个工具显示两遍」，截图形态是：外层组头一行
 *   `✓ Bash: find . -name "*.java"`，紧跟其下一行又是一条 `✓ Bash find . -name "*.java"`。
 *
 *   根因：组头的兜底标题取「首个工具名 + 参数摘要」，而组体里又渲染了**全部**工具
 *   （含第一个）⇒ 第一个工具在视觉上出现两遍。参数在子项里本来就能看到，
 *   组头复述它纯属冗余。
 *
 *   ⇒ 组头兜底标题必须是**概括**表述（工具名 + 并行 N 个），不能带首个工具的参数。
 *
 * <p>⚠️ 判据是「组头文本 ≠ 首个子项的标识」——**不是**「组头不含参数」：
 * 有 `tool_use_summary` 文本时组头用那份摘要，它可能与首项描述不同（那是后端给的独立信息）。
 */
const SID = 'sess-tool-group-dup'
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

const bash = (id: string, cmd: string): ToolCallDto =>
  ({ id, name: 'Bash', arguments: JSON.stringify({ cmd }), result: 'ok', isError: false })

const grep = (id: string, pattern: string): ToolCallDto =>
  ({ id, name: 'Grep', arguments: JSON.stringify({ pattern }), result: 'ok', isError: false })

let container: HTMLDivElement
let root: Root

function renderList(messages: ChatMessageDto[]) {
  root = createRoot(container)
  act(() => {
    root.render(
      <MessageList sessionId={SID} messages={messages} onDelete={noop} conversationId={null}
        onNearBottomChange={noop} onLoadOlder={noop} />,
    )
  })
}

describe('[并行工具组] 组头不重复首个工具', () => {
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

  it('组头不含首个工具的参数（不与首个子项重复）', () => {
    const turnId = 'u1'
    // 轮次**运行中** ⇒ 折叠区默认展开（turnOpen = processOpenByTurn ?? isRunningTurn），
    // 否则工具卡在收起的折叠区里，DOM 中根本不存在（测不到东西）。
    useChatStore.setState({
      streams: { [SID]: [{ assistantMessageId: 'blk1', userMessageId: turnId, reasoning: '', content: '', toolCalls: [] }] },
      streamOrder: { [SID]: ['blk1'] },
    })
    renderList([
      baseMsg('u1', 'user', { content: '跑两个命令', userMessageId: turnId }),
      baseMsg('a1', 'assistant', {
        userMessageId: turnId,
        finishReason: 'tool_calls',
        toolCalls: [
          bash('t1', 'find . -name "*.java"'),
          grep('t2', 'embedding|vector'),
        ],
      }),
    ])

    const group = container.querySelector('.tool-group')
    expect(group, '两个连续工具应成组').not.toBeNull()

    const headText = (group!.querySelector('.tg-title')?.textContent ?? '').trim()
    const firstChildText = (group!.querySelector('.tg-body .tool-card')?.textContent ?? '').trim()

    // 组头复述了首个工具的参数 ⇒ 用户看到同一个工具两遍
    expect(headText, '组头不得含首个工具的命令参数').not.toContain('find . -name')
    expect(headText, '组头不得含首个工具的参数摘要').not.toContain('*.java')
    // 组体里首个工具仍完整存在（不能为了不重复而丢掉内容）
    expect(firstChildText, '首个子项仍须完整渲染').toContain('find')
    expect(container.querySelectorAll('.tg-body .tool-card').length, '组内两个工具都在').toBe(2)
  })

  it('组头仍标明是哪个工具 + 并行数量（不是空标题）', () => {
    const turnId = 'u1'
    useChatStore.setState({
      streams: { [SID]: [{ assistantMessageId: 'blk1', userMessageId: turnId, reasoning: '', content: '', toolCalls: [] }] },
      streamOrder: { [SID]: ['blk1'] },
    })
    renderList([
      baseMsg('u1', 'user', { content: 'q', userMessageId: turnId }),
      baseMsg('a1', 'assistant', {
        userMessageId: turnId,
        finishReason: 'tool_calls',
        toolCalls: [bash('t1', 'ls'), grep('t2', 'foo')],
      }),
    ])

    const headText = (container.querySelector('.tg-title')?.textContent ?? '').trim()
    expect(headText).toContain('Bash')
    expect(headText).toContain('2')
  })

  it('单个工具不成组（无组头 ⇒ 天然不会重复）', () => {
    const turnId = 'u1'
    useChatStore.setState({
      streams: { [SID]: [{ assistantMessageId: 'blk1', userMessageId: turnId, reasoning: '', content: '', toolCalls: [] }] },
      streamOrder: { [SID]: ['blk1'] },
    })
    renderList([
      baseMsg('u1', 'user', { content: 'q', userMessageId: turnId }),
      baseMsg('a1', 'assistant', {
        userMessageId: turnId,
        finishReason: 'tool_calls',
        toolCalls: [bash('t1', 'ls')],
      }),
    ])

    expect(container.querySelector('.tool-group')).toBeNull()
    expect(container.querySelectorAll('.tool-card').length).toBe(1)
  })
})
