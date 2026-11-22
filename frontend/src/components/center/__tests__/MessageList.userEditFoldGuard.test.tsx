// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { MessageList } from '../MessageList'
import { useChatStore } from '@/stores/chatStore'
import type { ChatMessageDto } from '@/api/types'

/**
 * [编辑保护] 编辑用户气泡期间，该行**不能因为轮折叠被卸载**。
 *
 * <b>WHY（规则九 · 验证意图）</b>：编辑态（editing / 草稿 / 已加附件）是 `Message` 的行内局部 state，
 * 而渲染层的折叠是**整轮过滤**（`visibleRows` 过滤掉 foldedSet 里的 turnKey）。两者相遇时：
 * <ul>
 *   <li>运行态抖动（turnEnded=false）→ 窗口收窄到「最后 1 轮」；</li>
 *   <li>会话里出现新的轮起点（IM 入站 / 派单行 / 队列消息）→ 「最新轮」换人，正在编辑的那轮变成更早的轮；</li>
 * </ul>
 * 任一发生时，正在编辑的行会被卸载 ⇒ 编辑框消失、用户已经打的内容一起丢（用户实测「编辑用户气泡时
 * 总是被刷新导致编辑的失败」）。
 *
 * 修复 = 折叠计算保证「编辑中的轮」不被折（`editingMsgId`）。本用例把两条触发路径都钉住：
 * 变异（删掉编辑保护）⇒ 两条同时红。
 */
const SID = 'sess-edit-fold-guard'
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
const userMsg = (id: string) => baseMsg(id, 'user', { content: `问题-${id}`, userMessageId: id })
const finalMsg = (id: string, turnId: string) =>
  baseMsg(id, 'assistant', { content: `答复-${id}`, finishReason: 'stop', userMessageId: turnId })

let container: HTMLDivElement
let root: Root

function render(messages: ChatMessageDto[], thinking = false) {
  root = createRoot(container)
  rerender(messages, thinking)
}
function rerender(messages: ChatMessageDto[], thinking = false) {
  act(() => {
    root.render(
      <MessageList sessionId={SID} messages={messages} onDelete={noop} conversationId={null}
        thinking={thinking} onNearBottomChange={noop} onLoadOlder={noop}
        onEditResend={async () => {}} />,
    )
  })
}

const editBtns = () => Array.from(container.querySelectorAll<HTMLButtonElement>('.um-act[title="编辑并从此处重新开始"]'))
const textarea = () => container.querySelector<HTMLTextAreaElement>('.user-edit-input')

function openEditOf(index: number) {
  const btn = editBtns()[index]
  if (!btn) throw new Error(`no edit btn #${index}, count=${editBtns().length}`)
  act(() => { btn.dispatchEvent(new MouseEvent('mouseover', { bubbles: true })) })
  act(() => { btn.click() })
}

describe('[编辑保护] 轮折叠不得卸载正在编辑的行', () => {
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

  const threeTurns = () => [
    userMsg('u1'), finalMsg('a1', 'u1'),
    userMsg('u2'), finalMsg('a2', 'u2'),
    userMsg('u3'), finalMsg('a3', 'u3'),
  ]

  it('编辑较早的一般轮（u2）：运行态抖动 → 窗口收窄到 1 轮，编辑框仍在', () => {
    render(threeTurns(), false)
    // 空闲时窗口 = 最近 2 轮（u2/u3），u2 可点
    openEditOf(editBtns().length - 2)
    expect(textarea()).not.toBeNull()

    // 运行态抖动：turnEnded=false ⇒ 窗口收窄到「最后 1 轮」，正常会把 u2 折掉
    rerender(threeTurns(), true)
    expect(textarea()).not.toBeNull()
  })

  it('编辑最新提问（u3）时插入新的轮起点：最新轮换人，编辑框仍在', () => {
    render(threeTurns(), false)
    openEditOf(editBtns().length - 1)
    expect(textarea()).not.toBeNull()

    // 后到的一条被判成轮起点的 user 行（IM 入站 / 派单 / 队列消息）⇒ u3 不再是「最新轮」
    const extra: ChatMessageDto = baseMsg('u4', 'user', { content: '后台消息', userMessageId: 'u4' })
    rerender([...threeTurns(), extra], true)
    expect(textarea()).not.toBeNull()
  })

  it('退出编辑后折叠语义恢复：同一状态下编辑框消失，历史照常折叠', () => {
    // WHY：保护只作用于「编辑中」；取消/提交后必须立即回到既有折叠行为，不能永久撑开窗口。
    render(threeTurns(), false)
    openEditOf(editBtns().length - 2)
    expect(textarea()).not.toBeNull()

    const cancel = container.querySelector<HTMLButtonElement>('.ue-cancel')
    act(() => { cancel!.click() })
    expect(textarea()).toBeNull()

    rerender(threeTurns(), true)
    // 恢复折叠后 u2 那一轮被折掉 ⇒ 只剩最后 1 轮的可编辑按钮
    expect(editBtns().length).toBe(1)
  })
})
