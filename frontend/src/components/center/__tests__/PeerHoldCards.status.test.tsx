// @vitest-environment jsdom
import { act, type ReactNode } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ChatMessageDto, PeerHeldItemDto } from '@/api/types'
import { PeerHoldCards } from '@/components/center/PeerHoldCards'
import { MessageList } from '@/components/center/MessageList'

vi.mock('@/api/socket', () => ({ onSocketConnect: () => () => {} }))

/**
 * 「批准即结算」后的前端状态映射（严格对齐 CC）。
 *
 * <h2>WHY（规则九）</h2>
 * <p>后端已裁定：批准成功即 accepted（普通来信卡），失败即终态 + 给发送方失败通知。
 * 不再有 approved 中间态，`approve` 端点对 approved 行只会 404。前端若继续为 approved
 * 渲染「拒绝 / 投递」按钮，用户点到的必然是一个不存在的动作；历史 approved 行只能只读展示。
 * 同时终态（accepted / dropped / refused / …）也必须只读，且 hold 卡不能被误判成普通来信卡。
 */

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
    queuedOrigin: null,
    structuredOutput: null,
    createdAt: null,
    userMessageId: null,
    ...patch,
  } as ChatMessageDto
}

/** 普通已投递来信卡：`@发送方名` 是另一会话刚加的文案，本次改造 ⛔ 不得改动。 */
function peerNotice(): ChatMessageDto {
  return msg('peer-delivered', {
    role: 'user',
    author: 'sess-source1',
    content: '已投递的正文',
    subtype: 'agent_message',
    isMeta: true,
    queuedOrigin: 'peer',
    structuredOutput: {
      peer_source: {
        sourceSessionId: 'sess-source1',
        sourceTitle: '审计报告',
        sourceKind: 'session',
        messageId: 'src-msg-9',
        fromMode: 'default',
      },
    },
  })
}

let host: HTMLDivElement
let root: Root | null = null

async function render(node: ReactNode): Promise<void> {
  await act(async () => {
    root = createRoot(host)
    root.render(node)
  })
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

function renderCards(
  items: PeerHeldItemDto[],
  onAccept = vi.fn(),
  onReject = vi.fn(),
  focusedId?: string | null,
): Promise<void> {
  return render(
    <PeerHoldCards items={items} onAccept={onAccept} onReject={onReject} focusedId={focusedId} />,
  )
}

describe('PeerHoldCards · 状态映射（批准即结算）', () => {
  it('a. pending：渲染「拒绝 / 投递并继续」两个操作，回调带 messageId', async () => {
    const onAccept = vi.fn()
    const onReject = vi.fn()
    await renderCards([item({ messageId: 'peer-pending', status: 'pending' })], onAccept, onReject)

    const card = cards()[0]!
    const accept = card.querySelector<HTMLButtonElement>('.ph-accept')!
    const reject = card.querySelector<HTMLButtonElement>('.ph-reject')!
    expect(accept.textContent).toBe('投递并继续')
    expect(reject.textContent).toBe('拒绝')

    act(() => { accept.click() })
    expect(onAccept).toHaveBeenCalledWith('peer-pending')
    act(() => { reject.click() })
    expect(onReject).toHaveBeenCalledWith('peer-pending')
  })

  it('b. approved（迁移前历史数据）：只读「已批准（待迁移）」，无任何操作按钮', async () => {
    await renderCards([item({ messageId: 'peer-approved', status: 'approved' })])

    const card = cards()[0]!
    expect(card.classList.contains('declined')).toBe(true)
    expect(card.querySelector('.ph-flag')!.textContent).toBe('已批准（待迁移）')
    expect(card.querySelector('.ph-accept')).toBeNull()
    expect(card.querySelector('.ph-reject')).toBeNull()
    expect(card.querySelector('.ph-actions')).toBeNull()
    expect(card.querySelectorAll('button')).toHaveLength(0)
    // 正文仍可查看
    expect(card.querySelector('.ph-body')!.textContent).toBe('正文原文')
  })

  it('c. accepted / dropped / refused 等终态：只读展示，不渲染操作', async () => {
    await renderCards([
      item({ messageId: 'peer-accepted', status: 'accepted' }),
      item({ messageId: 'peer-dropped', status: 'dropped' }),
      item({ messageId: 'peer-refused', status: 'refused' }),
      item({ messageId: 'peer-expired', status: 'expired' }),
    ])

    const all = cards()
    expect(all).toHaveLength(4)
    expect(all[0]!.querySelector('.ph-flag')!.textContent).toBe('已投递')
    expect(all[1]!.querySelector('.ph-flag')!.textContent).toBe('已丢弃（未投递）')
    expect(all[2]!.querySelector('.ph-flag')!.textContent).toBe('已拒绝（未投递给 Claude）')
    expect(all[3]!.querySelector('.ph-flag')!.textContent).toBe('已过期（未投递）')
    for (const card of all) {
      expect(card.classList.contains('declined'), '终态行必须弱化为只读').toBe(true)
      expect(card.querySelector('.ph-accept')).toBeNull()
      expect(card.querySelector('.ph-reject')).toBeNull()
      expect(card.querySelector('.ph-actions')).toBeNull()
      expect(card.querySelector('.ph-body')!.textContent).toBe('正文原文')
    }
  })

  it('d. hold 卡不误判成普通来信卡：类名/卡片结构仍是 peer-hold', async () => {
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

    const hold = cards()[0]!
    expect(hold.classList.contains('peer-message')).toBe(false)
    expect(hold.querySelector('.ph-card')).not.toBeNull()
    expect(hold.querySelector('.pm-card')).toBeNull()
    expect(host.querySelectorAll('.msg.peer-message')).toHaveLength(0)
  })

  it('d2. focusedId 命中时给卡片加 ph-focus（跳转后 1.5s 高亮）', async () => {
    await renderCards([
      item({ messageId: 'peer-a' }),
      item({ messageId: 'peer-b' }),
    ], vi.fn(), vi.fn(), 'peer-b')

    const all = cards()
    expect(all[0]!.classList.contains('ph-focus')).toBe(false)
    expect(all[1]!.classList.contains('ph-focus')).toBe(true)
  })

  it('e. 标题仍是纯文本《…》，普通来信卡头部为「收到消息 · 来自会话《标题》」', async () => {
    await render(
      <MessageList
        sessionId="sess-owner"
        messages={[peerNotice()]}
        onDelete={() => {}}
        peerHeldItems={[item()]}
        onPeerAccept={() => {}}
        onPeerReject={() => {}}
      />,
    )

    // hold 卡标题：纯文本《来源标题》
    expect(cards()[0]!.querySelector('.ph-title')!.textContent).toBe('《审计报告》')
    // 普通已投递来信卡：固定文案「收到消息 · 来自会话《标题》」（2026-11-21 终稿）
    const delivered = host.querySelector<HTMLElement>('.msg.peer-message')!
    expect(delivered).not.toBeNull()
    expect(delivered.querySelector('.pm-dir')!.textContent).toBe('收到消息')
    expect(delivered.querySelector('.pm-title')!.textContent).toBe('《审计报告》')
    expect(delivered.querySelector('img')).toBeNull()
  })
})
