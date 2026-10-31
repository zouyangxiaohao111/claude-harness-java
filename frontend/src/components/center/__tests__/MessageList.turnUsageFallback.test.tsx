// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { MessageList } from '../MessageList'
import { useChatStore } from '@/stores/chatStore'
import type { ChatMessageDto, MessageUsageDto, SessionDto } from '@/api/types'
import type { TurnUsageDto } from '@/api/usageTurns'

/**
 * [历史轮用量 · 后备] 历史会话重开后，旧消息在 DB 里没有 token（真实用量按轮聚合在 llm_usage_calls），
 * 轮底部用量行用后端按轮接口补缺。
 *
 * <b>WHY（规则九 · 测试验证意图）</b>：补缺只该发生在「消息级完全没有 token」的轮上 ——
 * 否则实时 complete 事件已经写了真值的轮会被后端聚合值覆盖，出现「刚发完的消息数字突然变了」。
 * 故断言：①无 token 的轮用 fallback 的 tokens/模型；②有 token 的轮忽略 fallback；
 * ③fallback 没有该轮 → 保持原文案（只有模型/时间，不臆造消耗）。
 */

const SID = 'sess-usage-fallback'
const noop = () => {}
const SESSION_MODEL = 'ds-openai/GLM-5.3-Flash'

function baseMsg(id: string, role: 'user' | 'assistant', content: string, createdAt?: string): ChatMessageDto {
  return {
    id, sessionId: SID, role, author: role === 'user' ? '你' : 'nexus', content,
    reasoning: null, toolCalls: null, finishReason: null, inputTokens: null, outputTokens: null,
    reasoningDurationMs: null, time: null, createdAt, toolCallId: null, assistantMessageId: null,
    userMessageId: null, subtype: null, isMeta: false, isApiErrorMessage: false, apiError: null,
    error: null, errorDetails: null, matchedRule: null,
  }
}

const userMsg = (id: string): ChatMessageDto => ({ ...baseMsg(id, 'user', '用户消息'), userMessageId: id })

const assistantMsg = (id: string, uid: string, opts?: {
  usage?: MessageUsageDto
  outputTokens?: number
  createdAt?: string
}): ChatMessageDto => ({
  ...baseMsg(id, 'assistant', `助手回复 ${id}`, opts?.createdAt),
  userMessageId: uid,
  usage: opts?.usage ?? null,
  outputTokens: opts?.outputTokens ?? null,
})

const turnUsageTexts = (container: HTMLElement): string[] =>
  Array.from(container.querySelectorAll('.turn-usage')).map((n) => (n.textContent ?? '').trim())

let container: HTMLDivElement
let root: Root

function renderList(messages: ChatMessageDto[], fallback?: Map<string, TurnUsageDto>) {
  root = createRoot(container)
  act(() => {
    root.render(
      <MessageList
        sessionId={SID}
        messages={messages}
        onDelete={noop}
        conversationId={null}
        onNearBottomChange={noop}
        onLoadOlder={noop}
        onOpenRightTab={noop}
        turnUsageFallback={fallback}
      />,
    )
  })
}

describe('[历史轮用量 · fallback] 消息级无 token 的轮用后端按轮聚合值补缺', () => {
  beforeEach(() => {
    (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    useChatStore.setState({
      messages: {}, streams: {}, streamOrder: {}, streamTicks: {}, snippedIds: {}, apiErrors: {}, changedFiles: {},
      sessions: [{ id: SID, modelName: SESSION_MODEL } as unknown as SessionDto],
    })
    container = document.createElement('div')
    document.body.appendChild(container)
  })

  afterEach(() => {
    act(() => { root?.unmount() })
    container.remove()
    root = undefined as unknown as Root
  })

  it('消息级没有 token → 用 fallback 的 tokens，模型剥掉 provider 前缀', () => {
    const fallback = new Map<string, TurnUsageDto>([
      ['u1', { userMessageId: 'u1', inputTokens: 453_762, outputTokens: 940, costYuan: null, model: 'deepseek/deepseek-flash' }],
    ])
    renderList([
      userMsg('u1'),
      assistantMsg('a1', 'u1', { createdAt: '2026-09-03T13:01:00' }),
    ], fallback)
    expect(turnUsageTexts(container)).toEqual([
      '共消耗 ↑453.8k ↓940 · deepseek-flash · 9月3日 13:01',
    ])
  })

  it('消息级已有 token → 不使用 fallback（实时值优先）', () => {
    const fallback = new Map<string, TurnUsageDto>([
      ['u1', { userMessageId: 'u1', inputTokens: 999_999, outputTokens: 888, costYuan: null, model: 'deepseek/deepseek-flash' }],
    ])
    renderList([
      userMsg('u1'),
      assistantMsg('a1', 'u1', { usage: { input_tokens: 100, output_tokens: 10 }, createdAt: '2026-09-03T13:01:00' }),
    ], fallback)
    expect(turnUsageTexts(container)).toEqual([
      '共消耗 ↑100 ↓10 · GLM-5.3-Flash · 9月3日 13:01',
    ])
  })

  it('fallback 里没有该轮 → 保持原文案（只有模型/时间，不臆造消耗）', () => {
    renderList([
      userMsg('u1'),
      assistantMsg('a1', 'u1', { createdAt: '2026-09-03T13:01:00' }),
    ], new Map())
    expect(turnUsageTexts(container)).toEqual([
      'GLM-5.3-Flash · 9月3日 13:01',
    ])
  })
})
