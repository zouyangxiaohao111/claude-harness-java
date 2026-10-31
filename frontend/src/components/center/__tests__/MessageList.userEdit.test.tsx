// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MessageList } from '../MessageList'
import { useChatStore } from '@/stores/chatStore'
import type { ChatMessageDto } from '@/api/types'

/**
 * [用户消息编辑重发] 用户气泡 hover 显示时间 + 编辑/复制；点铅笔进编辑态，底部提示「编辑后将从此处
 * 重新开始对话，已有产物不会被删除」，发送 = 裁剪该消息起全部对话 + 用新文本重发。
 *
 * <b>WHY（规则九 · 验证意图）</b>：编辑重发是破坏性操作（会删掉 pivot 之后的全部对话），
 *   所以必须锁死三件事：①只有在 hover 时才出现入口，平时不干扰阅读；②发送必须把「新文本」原样
 *   交给 App（不是旧内容），且只发一次；③失败时编辑态必须保留 —— 否则用户重写的文案被静默丢弃，
 *   而对话却已经被裁剪了一半。
 */

const SID = 'sess-user-edit'
const noop = () => {}

function userMsg(id: string, content: string): ChatMessageDto {
  return {
    id, sessionId: SID, role: 'user', author: '你', content,
    reasoning: null, toolCalls: null, finishReason: null, inputTokens: null, outputTokens: null,
    reasoningDurationMs: null, time: '刚刚', toolCallId: null, assistantMessageId: null,
    userMessageId: id, subtype: null, isMeta: false, isApiErrorMessage: false, apiError: null,
    error: null, errorDetails: null, matchedRule: null, createdAt: '2026-10-29T07:42:00Z',
  }
}

let container: HTMLDivElement
let root: Root

function renderList(messages: ChatMessageDto[], onEditResend?: (id: string, text: string, onTrimmed?: () => void) => Promise<void>) {
  root = createRoot(container)
  act(() => {
    root.render(
      <MessageList sessionId={SID} messages={messages} onDelete={noop} conversationId={null}
        onEditResend={onEditResend} onNearBottomChange={noop} />,
    )
  })
}

const msgEl = () => container.querySelector<HTMLElement>('.msg.user')!
const metaEl = () => container.querySelector<HTMLElement>('.user-msg-meta')
const editBtn = () => container.querySelector<HTMLButtonElement>('.um-act[title="编辑并从此处重新开始"]')
const copyBtn = () => container.querySelector<HTMLButtonElement>('.um-act[title="复制"]')
const textarea = () => container.querySelector<HTMLTextAreaElement>('.user-edit-input')
const cancelBtn = () => container.querySelector<HTMLButtonElement>('.ue-cancel')
const sendBtn = () => container.querySelector<HTMLButtonElement>('.ue-send')

/** hover = React onMouseEnter（mouseover 委托 · relatedTarget 为空即视为进入） */
const hover = () => act(() => {
  msgEl().dispatchEvent(new MouseEvent('mouseover', { bubbles: true }))
})

/** textarea 受控值变更：走原型 setter + input 事件（React 受控组件的标准模拟方式） */
function typeInto(el: HTMLTextAreaElement, value: string) {
  const set = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')!.set!
  act(() => {
    set.call(el, value)
    el.dispatchEvent(new Event('input', { bubbles: true }))
  })
}

describe('[用户消息编辑重发]', () => {
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

  it('hover 才出现时间 + 编辑/复制入口（未 hover 时 meta 行不可交互）', () => {
    renderList([userMsg('u1', '原问题')], vi.fn(async () => {}))

    // 未 hover：meta 存在但不带 show（opacity:0 + pointer-events:none 由 CSS 落实）
    expect(metaEl()).not.toBeNull()
    expect(metaEl()!.classList.contains('show')).toBe(false)
    // [2026-10-29 用户裁定] hover 显示「日期 + 时分」绝对时间（解析失败才回落相对时间）
    expect(metaEl()!.textContent).toMatch(/\d{1,2}月\d{1,2}日 \d{2}:\d{2}/)
    expect(editBtn()).not.toBeNull()
    expect(copyBtn()).not.toBeNull()

    hover()
    expect(metaEl()!.classList.contains('show')).toBe(true)
  })

  it('点铅笔 → 气泡换成编辑框（值 = 原消息文本）+ 提示语 + 取消/发送', () => {
    renderList([userMsg('u1', '原问题')], vi.fn(async () => {}))
    hover()
    act(() => { editBtn()!.click() })

    expect(textarea()).not.toBeNull()
    expect(textarea()!.value).toBe('原问题')
    expect(container.querySelector('.user-bubble')).toBeNull()
    expect(container.querySelector('.user-edit-hint')!.textContent).toContain('编辑后将从此处重新开始对话，已有产物不会被删除')
    expect(cancelBtn()!.textContent).toContain('取消')
    expect(sendBtn()!.textContent).toContain('发送')
    expect(sendBtn()!.disabled).toBe(false)
  })

  it('改文本后点发送 → onEditResend 以 (消息 id, 新文本, 裁剪完成回调) 恰好调用一次，成功后退出编辑态', async () => {
    const onEditResend = vi.fn(async () => {})
    renderList([userMsg('u1', '原问题')], onEditResend)
    hover()
    act(() => { editBtn()!.click() })
    typeInto(textarea()!, '  改写后的问题  ')

    await act(async () => { sendBtn()!.click() })

    expect(onEditResend).toHaveBeenCalledTimes(1)
    // 第三个参数 = 「裁剪完成」回调（App 在裁剪 + 重拉窗口后调用 ⇒ 立刻退出编辑态）
    expect(onEditResend).toHaveBeenCalledWith('u1', '改写后的问题', expect.any(Function))
    // 成功 → 回到普通气泡
    expect(textarea()).toBeNull()
    expect(container.querySelector('.user-bubble')).not.toBeNull()
  })

  it('裁剪完成即退出编辑态（不等发送结束）—— 编辑态只覆盖「裁剪」这一小段', async () => {
    // WHY（规则九 · 验证意图）：用户点「发送」后期望立刻像普通发送一样（消息出现 + 「思考中…」），
    //   而不是卡在「发送中…」。裁剪（后端区间删除，毫秒级）完成后编辑态必须立刻退出，
    //   随后的发送走普通路径。变异点：不传 onTrimmed 回调 / 回调不退出编辑态 → 本用例红。
    let trimDone: (() => void) | undefined
    let release = () => {}
    const gate = new Promise<void>((resolve) => { release = resolve })
    const onEditResend = vi.fn(async (_id: string, _text: string, onTrimmed?: () => void) => {
      trimDone = onTrimmed
      await gate   // 发送侧迟迟不结束（模拟后端慢）
    })
    renderList([userMsg('u1', '原问题')], onEditResend)
    hover()
    act(() => { editBtn()!.click() })
    typeInto(textarea()!, '改写后的问题')

    await act(async () => { sendBtn()!.click() })
    // 裁剪未完成 → 仍在编辑态，按钮显示「发送中…」
    expect(textarea()).not.toBeNull()
    expect(sendBtn()!.textContent).toContain('发送中')

    // 裁剪完成 → 立刻退出编辑态（不等发送）
    await act(async () => { trimDone!() })
    expect(textarea()).toBeNull()
    expect(container.querySelector('.user-bubble')).not.toBeNull()

    await act(async () => { release() })
  })

  it('空文本（含纯空白）时发送按钮禁用', () => {
    renderList([userMsg('u1', '原问题')], vi.fn(async () => {}))
    hover()
    act(() => { editBtn()!.click() })

    typeInto(textarea()!, '   ')
    expect(sendBtn()!.disabled).toBe(true)
  })

  it('裁剪超过 1s → 按钮文案切到「正在裁剪并重新发送…」（防用户以为卡死）', async () => {
    // WHY（规则九 · 验证意图）：裁剪若真慢（大会话 / 磁盘慢），用户看到的必须是一个「在动」的提示，
    //   而不是干等着「发送中…」。正常路径（毫秒级）不显示长文案。
    vi.useFakeTimers()
    try {
      let release = () => {}
      const gate = new Promise<void>((resolve) => { release = resolve })
      const onEditResend = vi.fn(async () => { await gate })
      renderList([userMsg('u1', '原问题')], onEditResend)
      hover()
      act(() => { editBtn()!.click() })
      typeInto(textarea()!, '改写后的问题')

      await act(async () => { sendBtn()!.click() })
      expect(sendBtn()!.textContent).toContain('发送中')

      await act(async () => { vi.advanceTimersByTime(1000) })
      expect(sendBtn()!.textContent).toContain('正在裁剪并重新发送')

      await act(async () => { release() })
    } finally {
      vi.useRealTimers()
    }
  })

  it('onEditResend 失败 → 保持编辑态（草稿不丢）并显示重试提示', async () => {    const onEditResend = vi.fn(async () => { throw new Error('boom') })
    renderList([userMsg('u1', '原问题')], onEditResend)
    hover()
    act(() => { editBtn()!.click() })
    typeInto(textarea()!, '会失败的问题')

    await act(async () => { sendBtn()!.click() })

    expect(onEditResend).toHaveBeenCalledTimes(1)
    expect(textarea()).not.toBeNull()
    expect(textarea()!.value).toBe('会失败的问题')
    expect(container.querySelector('.user-edit-error')!.textContent).toContain('发送失败，请重试')
  })

  it('Esc 取消 → 退出编辑态，原气泡与原文保持不变', () => {
    renderList([userMsg('u1', '原问题')], vi.fn(async () => {}))
    hover()
    act(() => { editBtn()!.click() })
    typeInto(textarea()!, '半途打的想法')

    act(() => {
      textarea()!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    })

    expect(textarea()).toBeNull()
    expect(container.querySelector('.user-bubble')!.textContent).toContain('原问题')
  })
})
