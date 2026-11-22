// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ChatMessageDto, PeerHeldItemDto, ToolCallDto } from '@/api/types'

/**
 * 跨会话来信卡（`.msg.peer-message`）与轮次折叠的交互。
 *
 * <h2>WHY（2026-11-22 用户实测）</h2>
 * <p>用户原话：「前端收到 peer 会话消息，在正在执行收起时为什么一直展示（自动通过的），
 * 导致误以为这两个消息是连续发送的；我展开后看到不是，中间隔了几个工具调用；
 * 并且不会被默认显示三个去顶掉 —— 我认为应该是顶掉的，不必要特殊。
 * 触发是审批他就会卡住整个运转；其他的默认按照之前定好的，展示最新三个就行。」
 *
 * <p>根因：来信卡是**独立渲染行**（不参与 assistant 的折叠区），收起态它永远露在外面；
 * 中间的思考/工具被折叠后，两条来信在视觉上挨在一起。
 *
 * <p>裁定：来信卡算「中间过程」1 段，参与收起态的「最新 3 段」窗口；跟随轮收起态
 * （收起隐藏、展开显示）。hold（审批）卡不在消息流里，保持常显。
 */

vi.mock('@/api/socket', () => ({ onSocketConnect: () => () => {} }))

import { MessageList } from '@/components/center/MessageList'

const SID = 'sess-peer-fold'
const SOURCE = 'sess-source-fold'
const noop = () => {}

function baseMsg(id: string, patch: Partial<ChatMessageDto> & { role: ChatMessageDto['role'] }): ChatMessageDto {
  return {
    id,
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
    ...patch,
  } as ChatMessageDto
}

const userMsg = (id: string): ChatMessageDto =>
  baseMsg(id, { role: 'user', author: '你', content: '问题', userMessageId: id })

/** 跨会话来信：agent_message + structuredOutput.peer_source（与后端落库行同形） */
function peerNotice(id = 'peer-1'): ChatMessageDto {
  return baseMsg(id, {
    role: 'user',
    author: SOURCE,
    content: '来信正文',
    subtype: 'agent_message',
    isMeta: true,
    queuedOrigin: 'peer',
    structuredOutput: {
      peer_source: {
        sourceSessionId: SOURCE,
        sourceTitle: '审计报告',
        sourceKind: 'session',
        admission: 'auto',
        admissionReason: 'mode-match',
      },
      agent_summary: '请复核',
    },
    userMessageId: null,
  })
}

/** assistant 段：思考 1 段 + 工具整行 1 段 = 2 段 */
function assistantWithTool(id: string, turnId: string, reasoning: string): ChatMessageDto {
  const tool: ToolCallDto = { id: `t-${id}`, name: 'Bash', arguments: '{}', result: 'ok', isError: false }
  return baseMsg(id, {
    role: 'assistant',
    author: 'nexus',
    userMessageId: turnId,
    reasoning,
    toolCalls: [tool],
    finishReason: 'tool_calls',
  })
}

function heldItem(): PeerHeldItemDto {
  return {
    messageId: 'peer-h1',
    targetSessionId: SID,
    fromSessionId: SOURCE,
    sourceTitle: '审计报告',
    fromName: 'researcher',
    fromMode: 'default',
    summary: '请复核',
    reason: 'EXPLICIT_HOLD',
    heldAt: '2026-11-19T10:00:00Z',
    body: '待确认正文',
    status: 'pending',
    bodyLines: 1,
    bodyChars: 5,
  }
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

async function render(
  messages: ChatMessageDto[],
  props: Partial<Parameters<typeof MessageList>[0]> = {},
): Promise<void> {
  await act(async () => {
    root = createRoot(host)
    root.render(<MessageList sessionId={SID} messages={messages} onDelete={noop} {...props} />)
  })
  await act(async () => { await new Promise((r) => setTimeout(r, 10)) })
}

const peerCards = () => host.querySelectorAll('.msg.peer-message')

describe('[peer-message] 来信卡参与「最新 3 段」并跟随轮收起态', () => {
  it('执行中收起：来信卡不在最新 3 段内 → 隐藏，且计入「隐藏了 N 项」', async () => {
    // 段序（共 5 段）：peer(1) → a1 思考+工具(2) → a2 思考+工具(2)
    // 尾部窗口 3 段只覆盖 a2(2) + a1(1) ⇒ peer 被顶掉。
    await render(
      [userMsg('u1'), peerNotice(), assistantWithTool('a1', 'u1', '思考一'), assistantWithTool('a2', 'u1', '思考二')],
      { thinking: true },
    )

    expect(peerCards(), '来信卡应被最新 3 段顶掉').toHaveLength(0)
    const more = host.querySelector('.turn-process-more')
    expect(more, '收起态必须提示还有内容被隐藏').not.toBeNull()
    expect(more!.textContent).toContain('隐藏了 2 项')
  })

  it('执行中收起：来信卡落在最新 3 段内 → 照常显示', async () => {
    // 段序（共 3 段）：a1 思考+工具(2) → peer(1) ⇒ 尾部窗口正好覆盖到最后一条来信。
    await render(
      [userMsg('u1'), assistantWithTool('a1', 'u1', '思考一'), peerNotice()],
      { thinking: true },
    )

    expect(peerCards(), '窗口内的来信卡必须可见').toHaveLength(1)
    expect(peerCards()[0]!.textContent).toContain('收到消息')
  })

  it('已完成轮收起：来信卡隐藏；点开「已完成」后按真实位置出现', async () => {
    await render([userMsg('u1'), assistantWithTool('a1', 'u1', '思考一'), peerNotice()])

    expect(peerCards(), '已完成轮收起时不露来信卡').toHaveLength(0)
    const toggle = host.querySelector<HTMLButtonElement>('.turn-process-toggle')
    expect(toggle, '该轮必须有折叠入口（否则来信永久不可见）').not.toBeNull()
    expect(toggle!.textContent).toContain('已完成')

    await act(async () => { toggle!.click() })
    await act(async () => { await new Promise((r) => setTimeout(r, 10)) })
    expect(peerCards(), '展开后来信卡出现').toHaveLength(1)
  })

  it('纯正文轮（无思考/工具）：来信卡仍算折叠入口，展开后可见', async () => {
    // 该轮没有任何思考/工具 ⇒ 若来信卡不算「有中间过程」，整轮就没有折叠入口
    // ⇒ 来信被隐藏且无从展开 = 静默丢消息。
    const plain = baseMsg('a1', {
      role: 'assistant', author: 'nexus', userMessageId: 'u1', content: '最终答复',
    })
    await render([userMsg('u1'), plain, peerNotice()])

    expect(peerCards(), '已完成轮收起时不露来信卡').toHaveLength(0)
    const toggle = host.querySelector<HTMLButtonElement>('.turn-process-toggle')
    expect(toggle, '仅有来信卡的轮必须有折叠入口').not.toBeNull()

    await act(async () => { toggle!.click() })
    await act(async () => { await new Promise((r) => setTimeout(r, 10)) })
    expect(peerCards()).toHaveLength(1)
  })

  it('对照：hold（审批）卡不受轮折叠影响，始终常显', async () => {
    await render([userMsg('u1')], {
      peerHeldItems: [heldItem()],
      onPeerAccept: noop,
      onPeerReject: noop,
    })
    expect(host.querySelectorAll('.msg.peer-hold')).toHaveLength(1)
  })
})
