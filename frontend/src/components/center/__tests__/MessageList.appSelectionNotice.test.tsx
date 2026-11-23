// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ChatMessageDto } from '@/api/types'

/**
 * 「应用选择」尾部 meta 通知在消息流里的中文标签（P5 · UI 层只做呈现）。
 *
 * <h2>WHY（意图验证）</h2>
 * <ul>
 *   <li>后端每条通知正文是英文（`The available applications have changed; this replaces…`）。
 *       没有 subtype 分支时，系统行会**回落正文第一行** ⇒ 用户在消息流里看到英文长句，
 *       与既有中文化口径不一致（影响面文档 P2）。</li>
 *   <li>三态必须分别对上：首次 / 变化 / 清空 —— 混成一个标签就看不出「选择变了还是结束了」。</li>
 * </ul>
 */

vi.mock('@/api/socket', () => ({ onSocketConnect: () => () => {} }))

import { MessageList } from '@/components/center/MessageList'

const SID = 'sess-1'

function baseMsg(partial: Partial<ChatMessageDto> & { id: string; role: ChatMessageDto['role'] }): ChatMessageDto {
  return {
    sessionId: SID,
    author: null,
    content: null,
    reasoning: null,
    toolCalls: null,
    finishReason: null,
    inputTokens: null,
    outputTokens: null,
    reasoningDurationMs: null,
    time: null,
    toolCallId: null,
    assistantMessageId: null,
    subtype: null,
    isMeta: false,
    isApiErrorMessage: false,
    apiError: null,
    error: null,
    ...partial,
  } as ChatMessageDto
}

async function flush(ms = 0): Promise<void> {
  await act(async () => { await new Promise((r) => setTimeout(r, ms)) })
}

let host: HTMLDivElement
let root: Root | null = null

beforeEach(() => {
  host = document.createElement('div')
  document.body.appendChild(host)
})

afterEach(() => {
  act(() => { root?.unmount() })
  root = null
  host.remove()
})

describe('MessageList · 应用选择通知行', () => {
  it('三态各有中文标签（不再是英文正文首行）', async () => {
    const messages: ChatMessageDto[] = [
      baseMsg({ id: 'u1', role: 'user', content: '用 notion 查一下', userMessageId: 'u1' }),
      baseMsg({ id: 'a1', role: 'assistant', content: '好的。', userMessageId: 'u1' }),
      baseMsg({
        id: 'm1', role: 'user', author: 'attachment', subtype: 'app_selection', isMeta: true,
        content: '<system-reminder>\nSelected applications for this turn: Notion\n</system-reminder>',
        userMessageId: null,
      }),
      baseMsg({
        id: 'm2', role: 'user', author: 'attachment', subtype: 'app_selection_change', isMeta: true,
        content: '<system-reminder>\nThe available applications have changed; this replaces the earlier list.\n</system-reminder>',
        userMessageId: null,
      }),
      baseMsg({
        id: 'm3', role: 'user', author: 'attachment', subtype: 'app_selection_end', isMeta: true,
        content: '<system-reminder>\nNo application is selected for this turn.\n</system-reminder>',
        userMessageId: null,
      }),
    ]
    await act(async () => {
      root = createRoot(host)
      root.render(<MessageList sessionId={SID} messages={messages} onDelete={() => {}} />)
    })
    await flush(10)

    // 展开本轮（注入行跟随轮展开态）
    const toggle = host.querySelector('.turn-process-toggle') as HTMLElement | null
    expect(toggle, '找不到轮展开按钮').not.toBeNull()
    await act(async () => { toggle!.click() })
    await flush(10)

    const labels = Array.from(host.querySelectorAll('.msg.task-notice.sys-injected .tn-text'))
      .map((n) => (n.textContent ?? '').trim())
    expect(labels).toContain('已选择应用')
    expect(labels).toContain('可用应用已更新')
    expect(labels).toContain('已清空应用选择')
    // 不允许回落成英文正文首行
    expect(labels.some((t) => t.includes('The available applications'))).toBe(false)
  })
})
