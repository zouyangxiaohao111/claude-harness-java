// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MessageList } from '../MessageList'
import { useChatStore } from '@/stores/chatStore'
import type { AttachmentRequest, ChatMessageDto } from '@/api/types'

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

/**
 * jsdom 的 `File`/`Blob` **没有 `arrayBuffer()`**（浏览器与 WebView2 都有）⇒ 附件投递会直接抛错。
 * 此处补一个等价实现（走 FileReader，jsdom 有），**只补测试环境缺的标准 API**，
 * ⛔ 不是为了让断言变绿而改行为 —— 实现与浏览器一致的 Promise<ArrayBuffer> 语义。
 */
if (typeof (Blob.prototype as unknown as { arrayBuffer?: () => Promise<ArrayBuffer> }).arrayBuffer !== 'function') {
  ;(Blob.prototype as unknown as { arrayBuffer: () => Promise<ArrayBuffer> }).arrayBuffer = function () {
    return new Promise<ArrayBuffer>((resolve, reject) => {
      const r = new FileReader()
      r.onload = () => resolve(r.result as ArrayBuffer)
      r.onerror = () => reject(r.error)
      r.readAsArrayBuffer(this as unknown as Blob)
    })
  }
}

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

/**
 * 在编辑框里粘贴一张图片。
 *
 * <p>React 的 `onPaste` 从 `nativeEvent.clipboardData` 读，而 jsdom 的 `ClipboardEvent`
 * 构造器不完整 ⇒ 直接造一个普通 Event 并挂 `clipboardData` 属性。
 * `items` 里给 `kind:'file' + getAsFile()`（浏览器复制位图走的那条分支）。
 */
function pasteImage(el: HTMLTextAreaElement, file: File) {
  const ev = new Event('paste', { bubbles: true, cancelable: true })
  Object.defineProperty(ev, 'clipboardData', {
    value: {
      items: [{ kind: 'file', type: file.type, getAsFile: () => file }],
      files: [] as File[],
    },
  })
  el.dispatchEvent(ev)
}

/** 等异步投递落地（读盘 + 内容 md5 去重 + setState） */
async function flush(rounds = 6) {
  for (let i = 0; i < rounds; i++) {
    await act(async () => { await new Promise((r) => setTimeout(r, 0)) })
  }
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
    // 第三个参数 = 「裁剪完成」回调（App 在裁剪 + 重拉窗口后调用 ⇒ 立刻退出编辑态）；
    // 第四个参数 = 随重发一起带上的原消息附件（本用例无附件 ⇒ undefined）
    expect(onEditResend).toHaveBeenCalledWith('u1', '改写后的问题', expect.any(Function), undefined)
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

  /**
   * [2026-11-01] 编辑重发必须**带上原消息的图片**。
   *
   * <p><b>WHY</b>：用户编辑一条带图的消息时，编辑框里能看到图（我们特意保留显示）⇒ 自然
   * 认为重发会把图带上。若只发文本，图片会被静默丢掉 —— 这是最难被发现的静默数据丢失。
   *
   * <p>变异自证：submitEdit 不构造 attachments / 不传第 4 参 → 本用例红。
   */
  it('编辑带图的消息 → 编辑框里显示原图，重发时把图片作为附件带上', async () => {
    const onEditResend = vi.fn(async () => {})
    const withImage: ChatMessageDto = {
      ...userMsg('u1', '看这张图'),
      imageData: [{ base64: 'AAAA', mediaType: 'image/png' }],
    }
    renderList([withImage], onEditResend)
    hover()
    act(() => { editBtn()!.click() })

    // 编辑态仍能看到原图（否则用户以为图没了 / 或以为还在但其实要丢）
    const thumbs = container.querySelectorAll('.user-edit-thumb')
    expect(thumbs.length).toBe(1)

    typeInto(textarea()!, '看这张图（改）')
    await act(async () => { sendBtn()!.click() })

    expect(onEditResend).toHaveBeenCalledTimes(1)
    const call = onEditResend.mock.calls[0] as unknown as [string, string, () => void, AttachmentRequest[]]
    expect(call[0]).toBe('u1')
    expect(call[1]).toBe('看这张图（改）')
    expect(call[3]).toEqual([{ type: 'image', base64: 'AAAA', mediaType: 'image/png' }])
  })

  it('⭐ 编辑态粘贴图片 → 出现待发缩略图，重发时作为附件带上（纯 base64，非 dataURL）', async () => {
    // WHY（用户 2026-11-06 报「点击编辑用户消息无法粘贴图片」）：
    //   编辑态此前只有一个 textarea —— 连 onPaste 都没有 ⇒ 用户在编辑时完全无法补图。
    //   [2026-11-06 用户裁定] 补图**只保留粘贴**（Ctrl+V），不加「+ 图片」选择器入口。
    //   本用例锁住两条：①粘贴能被接住并显示缩略图；②重发时进 attachments 的 base64 必须
    //   **不带 `data:` 前缀**（后端 Base64.decode 与图像 block 都只认纯 base64）。
    //   变异自证：去掉 textarea 的 onPaste → 缩略图不出现 → 红。
    const onEditResend = vi.fn(async () => {})
    renderList([userMsg('u1', '原问题')], onEditResend)
    hover()
    act(() => { editBtn()!.click() })

    const file = new File([new Uint8Array([137, 80, 78, 71])], 'shot.png', { type: 'image/png' })
    pasteImage(textarea()!, file)
    // 粘贴是 async（读盘 + 算内容 md5 去重）⇒ 等投递落地
    await flush()

    expect(container.querySelectorAll('.user-edit-thumb').length, '粘贴的图必须显示为待发缩略图').toBe(1)

    typeInto(textarea()!, '带图的新问题')
    await act(async () => { sendBtn()!.click() })

    expect(onEditResend).toHaveBeenCalledTimes(1)
    const call = onEditResend.mock.calls[0] as unknown as [string, string, () => void, AttachmentRequest[]]
    expect(call[1]).toBe('带图的新问题')
    expect(call[3], '新粘贴的图必须随重发带上').toHaveLength(1)
    expect(call[3][0].type).toBe('image')
    expect(call[3][0].mediaType).toBe('image/png')
    expect(call[3][0].base64, '必须带 base64 正文').toBeTruthy()
    expect(call[3][0].base64, 'base64 必须是纯串（dataURL 前缀会让后端落盘/图像 block 失败）')
      .not.toContain('data:')
  })

  it('⭐ 待发图片可单张移除 → 移除后重发不再带上它', async () => {
    // WHY：编辑态新加的图如果没有删除入口，用户贴错一张就只能取消整个编辑重来。
    const onEditResend = vi.fn(async () => {})
    renderList([userMsg('u1', '原问题')], onEditResend)
    hover()
    act(() => { editBtn()!.click() })

    const file = new File([new Uint8Array([1, 2, 3])], 'shot.png', { type: 'image/png' })
    pasteImage(textarea()!, file)
    await flush()
    expect(container.querySelectorAll('.user-edit-thumb').length).toBe(1)

    act(() => { (container.querySelector('.user-edit-added-x') as HTMLButtonElement).click() })
    expect(container.querySelectorAll('.user-edit-thumb').length, '移除后缩略图必须消失').toBe(0)

    typeInto(textarea()!, '不要图了')
    await act(async () => { sendBtn()!.click() })
    const call = onEditResend.mock.calls[0] as unknown as [string, string, () => void, AttachmentRequest[] | undefined]
    expect(call[3]).toBeUndefined()
  })
})
