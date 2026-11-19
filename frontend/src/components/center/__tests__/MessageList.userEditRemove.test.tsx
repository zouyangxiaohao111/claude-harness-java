// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MessageList } from '../MessageList'
import type { AttachmentRequest, ChatMessageDto } from '@/api/types'

/**
 * 编辑态：**原消息的图片与文件都要能删除**（删除 = 重发时不带上）。
 * 之前只显示不能删 ⇒ 用户没法在编辑时去掉错传的附件。
 */
const SID = 'sess-user-edit-remove'
const noop = () => {}

const msg: ChatMessageDto = {
  id: 'u1', sessionId: SID, role: 'user', author: '你', content: '看这个报告',
  reasoning: null, toolCalls: null, finishReason: null, inputTokens: null, outputTokens: null,
  reasoningDurationMs: null, time: '刚刚', toolCallId: null, assistantMessageId: null,
  userMessageId: 'u1', subtype: null, isMeta: false, isApiErrorMessage: false, apiError: null,
  error: null, errorDetails: null, matchedRule: null, createdAt: '2026-10-29T07:42:00Z',
  imageData: [{ mediaType: 'image/png', base64: 'AAAA' }],
  userAttachments: [
    { type: 'pdf', filename: 'report.pdf', mediaType: 'application/pdf', contentId: '12', url: null, base64: null, path: null },
  ],
}

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

const hover = () => act(() => {
  container.querySelector('.msg.user')!.dispatchEvent(new MouseEvent('mouseover', { bubbles: true }))
})
const editBtn = () => container.querySelector<HTMLButtonElement>('.um-act[title="编辑并从此处重新开始"]')
const sendBtn = () => container.querySelector<HTMLButtonElement>('.ue-send')
const removes = () => Array.from(container.querySelectorAll<HTMLButtonElement>('.user-edit-added-x'))

describe('编辑态删除原附件', () => {
  it('图片与文件各有一个 ×；全部删除后重发不带附件', async () => {
    const onEditResend = vi.fn(async (
      _id: string, _text: string, onTrimmed?: () => void, _att?: AttachmentRequest[],
    ) => { onTrimmed?.() })
    act(() => {
      root.render(
        <MessageList
          sessionId={SID}
          messages={[msg]}
          onDelete={noop}
          conversationId={null}
          onNearBottomChange={noop}
          onEditResend={onEditResend}
        />,
      )
    })
    hover()
    act(() => editBtn()!.click())
    expect(container.querySelector('.user-edit'), '进入编辑态').toBeTruthy()
    expect(container.querySelector('.user-edit-thumb'), '原图片可见').toBeTruthy()
    expect(container.querySelector('.user-edit-file'), '原文件可见').toBeTruthy()
    expect(removes().length, '图片 + 文件 两个移除按钮').toBe(2)

    // 删图片 → 再删文件
    act(() => removes()[0]!.click())
    expect(container.querySelector('.user-edit-thumb'), '图片已移除').toBeNull()
    act(() => removes()[0]!.click())
    expect(container.querySelector('.user-edit-file'), '文件已移除').toBeNull()

    act(() => sendBtn()!.click())
    await act(async () => { await new Promise((r) => setTimeout(r, 0)) })
    expect(onEditResend).toHaveBeenCalledTimes(1)
    const att = onEditResend.mock.calls[0][3]
    expect(att, '全部删除后不传附件').toBeUndefined()
  })
})
