// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Message, MessageList } from '../MessageList'
import { useChatStore } from '@/stores/chatStore'
import type { ChatMessageDto, SessionDto } from '@/api/types'

/**
 * 轮末操作条（分叉 / 复制）。
 *
 * <b>WHY（规则九）</b>：这两个动作都作用在「一条回答」上，最容易错的是绑错对象——
 * 分叉必须传**该轮最后一条 assistant 行**的 id（后端按 seq 截断），复制必须只取该条
 * 回答的 Markdown 原文（不能把工具卡/思考/时间戳带进剪贴板）。本测试钉死这两个契约。
 */

const SID = 'sess-fork-actions'
const noop = () => {}

function baseMsg(id: string, role: 'user' | 'assistant', content: string): ChatMessageDto {
  return {
    id, sessionId: SID, role, author: role === 'user' ? '你' : 'nexus', content,
    reasoning: role === 'assistant' ? '这是思考过程' : null,
    toolCalls: null, finishReason: null, inputTokens: null, outputTokens: null,
    reasoningDurationMs: null, time: null, createdAt: undefined, toolCallId: null, assistantMessageId: null,
    userMessageId: role === 'assistant' ? 'u1' : id, subtype: null, isMeta: false, isApiErrorMessage: false,
    apiError: null, error: null, errorDetails: null, matchedRule: null,
  }
}

const userMsg = (id: string): ChatMessageDto => ({ ...baseMsg(id, 'user', '用户消息'), userMessageId: id })
const assistantMsg = (id: string, content: string): ChatMessageDto => baseMsg(id, 'assistant', content)

let container: HTMLDivElement
let root: Root

function renderList(
  messages: ChatMessageDto[],
  onForkTurn?: (s: string, id: string) => Promise<void>,
  artifacts?: Parameters<typeof MessageList>[0]['artifacts'],
) {
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
        onForkTurn={onForkTurn}
        artifacts={artifacts}
      />,
    )
  })
}

const click = (el: Element) => act(() => {
  el.dispatchEvent(new MouseEvent('click', { bubbles: true }))
})

describe('[轮末操作条] 分叉 / 复制', () => {
  beforeEach(() => {
    (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    useChatStore.setState({
      messages: {}, streams: {}, streamOrder: {}, streamTicks: {}, snippedIds: {}, apiErrors: {}, changedFiles: {},
      sessions: [{ id: SID, modelName: 'GLM-5.3-Flash' } as unknown as SessionDto],
    })
    container = document.createElement('div')
    document.body.appendChild(container)
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: vi.fn().mockResolvedValue(undefined) },
    })
  })

  afterEach(() => {
    act(() => { root?.unmount() })
    container.remove()
    root = undefined as unknown as Root
  })

  it('最终 assistant 行下方出现操作条；点分叉传该行 id（= 分叉点）', async () => {
    const onFork = vi.fn().mockResolvedValue(undefined)
    renderList([userMsg('u1'), assistantMsg('m1', '回答正文')], onFork)

    const actions = container.querySelector('.msg-turn-actions')
    expect(actions).toBeTruthy()
    // 只有助手回答行有操作条：`isFinalOfTurnFor` 对 user 行也返回 true，必须在组件侧排除
    expect(container.querySelectorAll('.msg-turn-actions')).toHaveLength(1)
    expect(container.querySelector('.msg.user .msg-turn-actions')).toBeNull()
    const fork = actions!.querySelector('button[aria-label="从这条回答继续分叉"]')
    expect(fork).toBeTruthy()

    click(fork!)
    await act(async () => {})
    expect(onFork).toHaveBeenCalledWith(SID, 'm1')
  })

  it('复制只写该条回答的 Markdown 原文（不含思考/工具/时间戳）', async () => {
    renderList([userMsg('u1'), assistantMsg('m1', '回答正文')])

    const copy = container.querySelector('button[aria-label="复制回答"]')
    expect(copy).toBeTruthy()
    click(copy!)
    await act(async () => {})

    expect(navigator.clipboard.writeText).toHaveBeenCalledWith('回答正文')
    expect(navigator.clipboard.writeText).toHaveBeenCalledTimes(1)
  })

  it('系统注入行（role=system）即使 isFinalOfTurn 也不渲染操作条', () => {
    const sys = { ...baseMsg('s1', 'assistant', 'hook 日志'), role: 'system' as const }
    root = createRoot(container)
    act(() => {
      root.render(
        <Message msg={sys} onDelete={noop} isFinalOfTurn turnKey="u1"
          onForkTurn={vi.fn().mockResolvedValue(undefined)} />,
      )
    })
    expect(container.querySelector('.msg-turn-actions')).toBeNull()
  })

  it('纯工具轮（最终 assistant 无正文）：隐藏复制，仍保留分叉', () => {
    renderList([userMsg('u1'), assistantMsg('m1', '')], vi.fn().mockResolvedValue(undefined))

    expect(container.querySelector('button[aria-label="从这条回答继续分叉"]')).toBeTruthy()
    expect(container.querySelector('button[aria-label="复制回答"]')).toBeNull()
  })
})
