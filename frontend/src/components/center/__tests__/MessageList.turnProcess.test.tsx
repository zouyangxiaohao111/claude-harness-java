// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { MessageList } from '../MessageList'
import { useChatStore } from '@/stores/chatStore'
import type { ChatMessageDto, ToolCallDto } from '@/api/types'

/**
 * [轮次中间过程折叠] 折叠粒度 = 轮次（turnKey = 发起该轮的 userMessageId），不是单条 assistant 消息。
 *
 * <b>WHY（规则九 · 用户补充裁定）</b>：
 *   · 轮次进行中（流式/running）→ 中间过程必须正常展示（用户要看到正在做什么）；
 *   · 轮次结束后 → 整轮思考 / 工具调用 / 技能加载默认收起，只留最终答复 + 产物入口，顶部一行
 *     「已完成 · 时长 ⌄」；
 *   · 最后一轮在跑时，历史轮次仍保持收起。
 * 若按「单条 assistant 消息是否非流式」判断，运行中已落库的中间段会被误收起 —— 这正是本用例锁死的点。
 */

const SID = 'sess-turn-process'
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

const userMsg = (id: string, createdAt?: string): ChatMessageDto =>
  baseMsg(id, 'user', { content: `问题-${id}`, userMessageId: id, createdAt })

const tool = (id: string): ToolCallDto => ({ id, name: 'Bash', arguments: '{"cmd":"ls"}', result: 'ok', isError: false })

/** 工具轮 assistant：带 reasoning + 普通工具卡（中间过程）。 */
const intermediateMsg = (id: string, turnId: string, createdAt?: string): ChatMessageDto =>
  baseMsg(id, 'assistant', {
    reasoning: `思考-${id}`, toolCalls: [tool(`t-${id}`)], finishReason: 'tool_calls',
    userMessageId: turnId, createdAt,
  })

const finalMsg = (id: string, turnId: string, createdAt?: string): ChatMessageDto =>
  baseMsg(id, 'assistant', { content: `最终答复-${id}`, finishReason: 'stop', userMessageId: turnId, createdAt })

let container: HTMLDivElement
let root: Root

function renderList(messages: ChatMessageDto[], opts: { thinking?: boolean } = {}) {
  root = createRoot(container)
  act(() => {
    root.render(
      <MessageList sessionId={SID} messages={messages} onDelete={noop} conversationId={null}
        thinking={opts.thinking} onNearBottomChange={noop} onLoadOlder={noop} />,
    )
  })
}

const toggles = () => Array.from(container.querySelectorAll<HTMLButtonElement>('.turn-process-toggle'))

describe('[轮次中间过程折叠]', () => {
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

  it('轮次已结束：默认收起整轮中间过程，顶部唯一「已完成」行，最终答复仍显示', () => {
    renderList([
      userMsg('u1', '2026-10-28T10:00:00Z'),
      intermediateMsg('a1', 'u1', '2026-10-28T10:05:00Z'),
      finalMsg('a2', 'u1', '2026-10-28T10:16:34Z'),
    ])

    // 同一轮两条 assistant 段 → 只有一个「已完成」开关（轮次粒度，不是每条消息一个）
    expect(toggles()).toHaveLength(1)
    expect(toggles()[0]!.textContent).toContain('已完成')
    // 时长 = 发起用户消息 → 本轮最后一条 assistant 的时间差
    expect(toggles()[0]!.textContent).toContain('16m34s')
    // 中间过程收起：无工具卡、无思考正文
    expect(container.querySelectorAll('.tool-card')).toHaveLength(0)
    expect(container.querySelector('.thinking-body')).toBeNull()
    // 最终答复保持显示
    expect(container.textContent).toContain('最终答复-a2')
  })

  it('点击「已完成」展开整轮中间过程，再点收起（轮次内多条消息共享态）', () => {
    renderList([
      userMsg('u1', '2026-10-28T10:00:00Z'),
      intermediateMsg('a1', 'u1', '2026-10-28T10:01:00Z'),
      intermediateMsg('a2', 'u1', '2026-10-28T10:02:00Z'),
      finalMsg('a3', 'u1', '2026-10-28T10:03:00Z'),
    ])
    expect(toggles()).toHaveLength(1)
    expect(container.querySelectorAll('.tool-card')).toHaveLength(0)

    act(() => { toggles()[0]!.click() })
    // 同轮两段中间过程同时展开
    expect(container.querySelectorAll('.tool-card')).toHaveLength(2)
    // [2026-10-28 用户裁定] 思考块自身默认收起（「完成后收起、点开才显示完整」）
    //   ⇒ 展开轮次先露出 2 个思考摘要行；正文 .thinking-body 需再点开每个思考块。
    expect(container.querySelectorAll('.thinking-toggle')).toHaveLength(2)
    expect(container.querySelectorAll('.thinking-body')).toHaveLength(0)
    expect(toggles()[0]!.getAttribute('aria-expanded')).toBe('true')

    act(() => { toggles()[0]!.click() })
    expect(container.querySelectorAll('.tool-card')).toHaveLength(0)
    expect(container.querySelectorAll('.thinking-toggle')).toHaveLength(0)
    expect(container.querySelectorAll('.thinking-body')).toHaveLength(0)
  })

  it('轮次进行中（streaming）：不显示「已完成」，已落库的中间过程保持展开', () => {
    act(() => {
      useChatStore.getState().ensureStreamBlock(SID, 'blk-live', 'u1')
      useChatStore.getState().appendReasoning(SID, 'blk-live', '正在思考')
      useChatStore.getState().appendChunk(SID, 'blk-live', '流式正文')
    })
    renderList([
      userMsg('u1'),
      intermediateMsg('a1', 'u1'),
    ])

    // 本轮未结束：不得出现「已完成」收起行
    expect(toggles()).toHaveLength(0)
    // 已落库的中间过程仍正常展示（工具卡可见）
    expect(container.querySelectorAll('.tool-card')).toHaveLength(1)
    // 流式行自己的思考块保持展开（StreamBlockRow 尾块行为不变）
    const thinkingBodies = Array.from(container.querySelectorAll('.thinking-body')).map((el) => el.textContent ?? '')
    expect(thinkingBodies.some((t) => t.includes('正在思考'))).toBe(true)
  })

  it('最后一轮在跑时，历史轮次仍默认收起', () => {
    renderList([
      userMsg('u0'),
      intermediateMsg('a0', 'u0'),
      finalMsg('a0-final', 'u0'),
      userMsg('u1'),
    ], { thinking: true })

    // 历史轮次有且只有一个「已完成」开关；当前轮（u1）无中间过程 → 无开关
    expect(toggles()).toHaveLength(1)
    expect(container.querySelectorAll('.tool-card')).toHaveLength(0)
  })

  it('无中间过程（纯文本回复）不渲染「已完成」行 —— 没有可折叠内容时不应增加噪音', () => {
    renderList([userMsg('u1'), finalMsg('a1', 'u1')])
    expect(toggles()).toHaveLength(0)
    expect(container.textContent).toContain('最终答复-a1')
  })
})
