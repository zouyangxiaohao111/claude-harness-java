// @vitest-environment jsdom
import { act, type ReactNode } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ChatMessageDto, PeerHeldItemDto } from '@/api/types'
import { MessageList } from '@/components/center/MessageList'

/**
 * 待确认（hold）来信 · **对话流内联卡片**。
 *
 * <h2>WHY（规则九）</h2>
 * <p>hold 消息不进 messages 表（用户 2026-11-20 改判）：数据源只能是**待收件列表接口**
 * （`peer_held_message` 已持久化）。若改回「从消息行 heldState 取」，刷新/resume 后卡片
 * 必然消失 —— 用户会以为消息丢了，或反过来以为已投递。本文件的每个用例都钉住这条。
 *
 * <p>其余硬约束：①警示虚线边（与已投递实线卡区分）；②处理前一直「未投递给 Claude」；
 * ③拒绝告知对方、投递并继续；④标题/正文是他人数据，纯文本；⑤标题只有**显式点击**才跳；
 * ⑥已拒绝卡只读、不可再投递。
 */

let host: HTMLDivElement
let root: Root | null = null

function item(patch: Partial<PeerHeldItemDto> = {}): PeerHeldItemDto {
  return {
    messageId: 'peer-h1',
    targetSessionId: 'sess-owner',
    fromSessionId: 'sess-source1',
    sourceTitle: '审计报告',
    fromName: 'researcher',
    fromMode: 'default',
    summary: '请复核这次方案',
    reason: 'EXPLICIT_HOLD',
    heldAt: '2026-11-19T10:00:00Z',
    body: '正文原文',
    status: 'pending',
    bodyLines: 3,
    bodyChars: 42,
    ...patch,
  }
}

function msg(id: string, patch: Partial<ChatMessageDto> = {}): ChatMessageDto {
  return {
    id,
    sessionId: 'sess-owner',
    role: 'user',
    author: null,
    content: '用户提问',
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
    ...patch,
  } as ChatMessageDto
}

async function flush(ms = 0): Promise<void> {
  await act(async () => { await new Promise((r) => setTimeout(r, ms)) })
}

async function render(node: ReactNode): Promise<void> {
  await act(async () => {
    root = createRoot(host)
    root.render(node)
  })
  await flush(10)
}

function cards(): HTMLElement[] {
  return Array.from(host.querySelectorAll<HTMLElement>('.msg.peer-hold'))
}

beforeEach(() => {
  host = document.createElement('div')
  document.body.appendChild(host)
})

afterEach(() => {
  act(() => { root?.unmount() })
  root = null
  host.remove()
  vi.unstubAllGlobals()
})

describe('MessageList · 待确认（hold）内联卡片', () => {
  it('从待收件列表渲染：标题/来源 id/未投递/原因/正文默认可见/行数字数', async () => {
    await render(
      <MessageList
        sessionId="sess-owner"
        messages={[msg('u1')]}
        onDelete={() => {}}
        peerHeldItems={[item()]}
        onPeerAccept={() => {}}
        onPeerReject={() => {}}
      />,
    )
    const card = cards()[0]
    expect(card).toBeDefined()
    const head = card.querySelector('.ph-head')!.textContent ?? ''
    expect(head).toContain('来自')
    expect(head).toContain('《审计报告》')
    expect(head).toContain('sess-source1')
    expect(head).toContain('未投递给 Claude')
    expect(head).toContain('接收方设置为需人工确认')
    expect(card.querySelector('.ph-body')!.textContent).toBe('正文原文')
    expect(card.querySelector('.ph-body')!.classList.contains('clamped')).toBe(false)
    expect(card.querySelector('.ph-scale')!.textContent).toBe('共 3 行 · 42 字')
    // 警示虚线边：卡片类与已投递卡（.msg.peer-message）不同
    expect(card.classList.contains('peer-message')).toBe(false)
  })

  it('刷新/resume 后仍在：消息列表为空也照样渲染（数据不依赖消息表 heldState）', async () => {
    await render(
      <MessageList
        sessionId="sess-owner"
        messages={[]}
        onDelete={() => {}}
        peerHeldItems={[item()]}
        onPeerAccept={() => {}}
        onPeerReject={() => {}}
      />,
    )
    expect(cards()).toHaveLength(1)

    // 模拟刷新：整组件重挂载（store 重新拉列表接口后 items 仍在）
    act(() => { root?.unmount() })
    root = null
    host.replaceChildren()
    await render(
      <MessageList
        sessionId="sess-owner"
        messages={[]}
        onDelete={() => {}}
        peerHeldItems={[item()]}
        onPeerAccept={() => {}}
        onPeerReject={() => {}}
      />,
    )
    expect(cards()).toHaveLength(1)
    expect(host.textContent).toContain('《审计报告》')
  })

  it('动作：拒绝 / 投递并继续都带 messageId；处理中禁用；失败原因保留', async () => {
    const onAccept = vi.fn()
    const onReject = vi.fn()
    await render(
      <MessageList
        sessionId="sess-owner"
        messages={[]}
        onDelete={() => {}}
        peerHeldItems={[item()]}
        peerHeldRowErrors={{ 'peer-h1': '目标会话不在线，本条已保留' }}
        onPeerAccept={onAccept}
        onPeerReject={onReject}
      />,
    )
    const card = cards()[0]
    act(() => { card.querySelector<HTMLButtonElement>('.ph-accept')!.click() })
    expect(onAccept).toHaveBeenCalledWith('peer-h1')
    act(() => { card.querySelector<HTMLButtonElement>('.ph-reject')!.click() })
    expect(onReject).toHaveBeenCalledWith('peer-h1')
    expect(card.querySelector('.ph-error')!.textContent).toContain('本条已保留')

    // 处理中：两按钮都禁用（防重复提交）
    act(() => { root?.unmount() })
    root = null
    host.replaceChildren()
    await render(
      <MessageList
        sessionId="sess-owner"
        messages={[]}
        onDelete={() => {}}
        peerHeldItems={[item()]}
        peerHeldActing="peer-h1"
        onPeerAccept={() => {}}
        onPeerReject={() => {}}
      />,
    )
    expect(cards()[0].querySelector<HTMLButtonElement>('.ph-accept')!.disabled).toBe(true)
    expect(cards()[0].querySelector<HTMLButtonElement>('.ph-reject')!.disabled).toBe(true)
  })

  it('已拒绝（status=rejected）：只读「已拒绝（未投递给 Claude）」，不可再投递', async () => {
    await render(
      <MessageList
        sessionId="sess-owner"
        messages={[]}
        onDelete={() => {}}
        peerHeldItems={[item({ status: 'rejected' })]}
        onPeerAccept={() => {}}
        onPeerReject={() => {}}
      />,
    )
    const card = cards()[0]
    expect(card.classList.contains('declined')).toBe(true)
    expect(card.querySelector('.ph-flag')!.textContent).toBe('已拒绝（未投递给 Claude）')
    expect(card.querySelector('.ph-accept')).toBeNull()
    expect(card.querySelector('.ph-reject')).toBeNull()
    // 正文可查看
    expect(card.querySelector('.ph-body')!.textContent).toBe('正文原文')
  })

  it('准入徽标：自动通过（模式一致/自发）/ 手动批准；缺失 → 准入未知；模式未声明 ≠ default', async () => {
    await render(
      <MessageList
        sessionId="sess-owner"
        messages={[]}
        onDelete={() => {}}
        peerHeldItems={[
          item({ messageId: 'a', admission: 'auto', admissionReason: 'mode-match' }),
          item({ messageId: 'b', admission: 'auto', admissionReason: 'self-sent' }),
          item({ messageId: 'c', admission: 'approved', admissionReason: 'user-approved' }),
          item({ messageId: 'd' }),
          item({ messageId: 'e', fromMode: null }),
        ]}
        onPeerAccept={() => {}}
        onPeerReject={() => {}}
      />,
    )
    const all = cards()
    expect(all[0].querySelector('.ph-admission')!.textContent).toBe('自动通过 · 模式一致')
    expect(all[1].querySelector('.ph-admission')!.textContent).toBe('自动通过 · 自发')
    expect(all[2].querySelector('.ph-admission')!.textContent).toBe('手动批准')
    expect(all[3].querySelector('.ph-admission')!.textContent).toBe('准入未知')
    expect(all[3].querySelector('.ph-mode')!.textContent).toBe('default')
    expect(all[4].querySelector('.ph-mode')!.textContent).toBe('未声明')
  })

  it('标题只有显式点击才跳转（键盘可达）；渲染/自动行为不跳', async () => {
    const onJump = vi.fn()
    await render(
      <MessageList
        sessionId="sess-owner"
        messages={[]}
        onDelete={() => {}}
        peerHeldItems={[item()]}
        onPeerAccept={() => {}}
        onPeerReject={() => {}}
        onOpenPeerSession={onJump}
      />,
    )
    // 仅渲染不跳
    expect(onJump).not.toHaveBeenCalled()
    const title = cards()[0].querySelector<HTMLButtonElement>('.ph-title.ph-jump')!
    expect(title).not.toBeNull()
    expect(title.tagName).toBe('BUTTON')
    act(() => { title.focus(); title.click() })
    expect(onJump).toHaveBeenCalledWith('sess-source1')
  })

  it('标题/正文按纯文本渲染（不执行 HTML）', async () => {
    const rawTitle = '<b>审计</b><img src=x onerror="alert(1)">'
    const rawBody = '<script>alert(2)</script>正文'
    await render(
      <MessageList
        sessionId="sess-owner"
        messages={[]}
        onDelete={() => {}}
        peerHeldItems={[item({ sourceTitle: rawTitle, body: rawBody })]}
        onPeerAccept={() => {}}
        onPeerReject={() => {}}
      />,
    )
    const card = cards()[0]
    expect(card.querySelector('.ph-title')!.textContent).toBe(`《${rawTitle}》`)
    expect(card.querySelector('.ph-body')!.textContent).toBe(rawBody)
    expect(card.querySelector('img')).toBeNull()
    expect(card.querySelector('script')).toBeNull()
    expect(card.querySelector('b')).toBeNull()
  })

  it('长文按行数折叠 → 展开全文 → 收起', async () => {
    const body = Array.from({ length: 14 }, (_, i) => `第 ${i + 1} 行`).join('\n')
    await render(
      <MessageList
        sessionId="sess-owner"
        messages={[]}
        onDelete={() => {}}
        peerHeldItems={[item({ body, bodyLines: 14 })]}
        onPeerAccept={() => {}}
        onPeerReject={() => {}}
      />,
    )
    const card = cards()[0]
    expect(card.querySelector('.ph-body')!.classList.contains('clamped')).toBe(true)
    const more = card.querySelector<HTMLButtonElement>('.ph-more')!
    expect(more.textContent).toBe('展开全文')
    act(() => { more.click() })
    expect(card.querySelector('.ph-body')!.classList.contains('clamped')).toBe(false)
    expect(card.querySelector('.ph-more')!.textContent).toBe('收起')
  })

  it('不传 items 或空数组 ⇒ 不渲染任何 hold 卡（旧宿主零改动）', async () => {
    await render(<MessageList sessionId="sess-owner" messages={[msg('u1')]} onDelete={() => {}} />)
    expect(cards()).toHaveLength(0)
    expect(host.textContent).toContain('用户提问')
  })

  it('focusPeer：滚到指定卡并加 ph-focus（指定 messageId 优先于首条）', async () => {
    const original = Element.prototype.scrollIntoView
    const scrolled: Array<string | null> = []
    Element.prototype.scrollIntoView = vi.fn(function (this: Element) {
      scrolled.push(this.getAttribute('data-peer-message-id'))
    }) as never
    try {
      await render(
        <MessageList
          sessionId="sess-owner"
          messages={[]}
          onDelete={() => {}}
          peerHeldItems={[item({ messageId: 'peer-a' }), item({ messageId: 'peer-b' })]}
          onPeerAccept={() => {}}
          onPeerReject={() => {}}
          focusPeer={{ sessionId: 'sess-owner', messageId: 'peer-b', nonce: 1 }}
        />,
      )
      await flush(80)

      const target = host.querySelector('[data-peer-message-id="peer-b"]')
      const other = host.querySelector('[data-peer-message-id="peer-a"]')
      expect(target?.classList.contains('ph-focus')).toBe(true)
      expect(other?.classList.contains('ph-focus')).toBe(false)
      expect(scrolled).toContain('peer-b')
    } finally {
      if (original) Element.prototype.scrollIntoView = original
      else delete (Element.prototype as { scrollIntoView?: unknown }).scrollIntoView
    }
  })
})
