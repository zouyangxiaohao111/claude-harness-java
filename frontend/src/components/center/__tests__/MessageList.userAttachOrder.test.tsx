// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { MessageList } from '../MessageList'
import type { ChatMessageDto } from '@/api/types'

/**
 * 用户消息附件行的**位置**：chat-demo `UserMessage.files` 在气泡**上方**（文字之前）。
 * 这条锁住 DOM 顺序，防止有人把附件块挪回气泡内下方。
 */
const SID = 'sess-attach-order'
const noop = () => {}

const base = (): ChatMessageDto => ({
  id: 'u1', sessionId: SID, role: 'user', author: '你', content: '看这个报告',
  reasoning: null, toolCalls: null, finishReason: null, inputTokens: null, outputTokens: null,
  reasoningDurationMs: null, time: null, createdAt: undefined, toolCallId: null,
  assistantMessageId: null, userMessageId: 'u1', subtype: null, isMeta: false, isApiErrorMessage: false,
  apiError: null, error: null, errorDetails: null, matchedRule: null,
})

let container: HTMLDivElement
let root: Root

beforeEach(() => {
  ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
})

afterEach(() => {
  act(() => root.unmount())
  container.remove()
})

describe('用户消息附件在气泡上方', () => {
  it('附件行 .user-files 出现在 .user-bubble 之前（DOM 顺序）', () => {
    const msg = base()
    msg.userAttachments = [
      { type: 'pdf', filename: 'report.pdf', mediaType: 'application/pdf', contentId: '1', url: '/attachments/content/x/1', base64: null, path: null },
    ]
    act(() => {
      root.render(
        <MessageList
          sessionId={SID}
          messages={[msg]}
          onDelete={noop}
          conversationId={null}
          onNearBottomChange={noop}
          onLoadOlder={noop}
          onOpenRightTab={noop}
        />,
      )
    })
    const files = container.querySelector('.msg.user .user-files')
    const bubble = container.querySelector('.msg.user .user-bubble')
    expect(files, '应渲染附件行').toBeTruthy()
    expect(bubble, '应渲染气泡').toBeTruthy()
    expect(
      files!.compareDocumentPosition(bubble!) & Node.DOCUMENT_POSITION_FOLLOWING,
      '附件行必须在气泡之前（上方）',
    ).toBeTruthy()
    expect(files!.querySelector('.attach-file-card'), '文件卡在附件行内').toBeTruthy()
  })
})
