// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ChatMessageDto } from '@/api/types'

/**
 * SendMessage 协作通知行的渲染（后端 agent_message 契约）。
 *
 * <h2>WHY（意图验证）</h2>
 * <p>用户诉求：「在子代理执行时间线里显示『收到主代理通知』，能看到摘要，点开看原文」，
 * 且明确不是工具卡、不是用户气泡。
 *
 * <p>本测试钉四条可观察不变量：
 * <ol>
 *   <li>绝不渲染成用户气泡（通知正文不得出现在 .user-bubble）；</li>
 *   <li>不是「已完成」折叠区里的系统注入行（.task-notice 不在 .turn-process-body 内）；</li>
 *   <li>文案 = 「收到主代理通知 · HH:MM · 摘要」，展开后正文等于原文；</li>
 *   <li>不开新轮（一轮只有一个 .turn-process-toggle）。</li>
 * </ol>
 *
 * <h2>RED teeth</h2>
 * <ul>
 *   <li>删掉 MessageList 的 isAgentNotice 分支 ⇒ isMeta 行按旧规则消失 ⇒ 第 2/3 条红；</li>
 *   <li>删掉 isDialogueRow 的 agent_message 放行 ⇒ 通知行被整条丢弃 ⇒ 第 3 条红；</li>
 *   <li>把 agent_message 从 NON_USER_TURN_SUBTYPES 去掉 ⇒ 通知行若 isMeta 丢失会开新轮 ⇒ 第 4 条红。</li>
 * </ul>
 */

vi.mock('@/api/socket', () => ({ onSocketConnect: () => () => {} }))

import { MessageList } from '@/components/center/MessageList'

const SID = 'sess-1'
const NOTICE_TEXT = '请收束并回报关键能力/缺口/索引发布方案及改动文件，未知标未知。'

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

async function render(messages: ChatMessageDto[]): Promise<void> {
  await act(async () => {
    root = createRoot(host)
    root.render(<MessageList sessionId={SID} messages={messages} onDelete={() => {}} />)
  })
  await flush(10)
}

function notice(partial: Partial<ChatMessageDto> = {}): ChatMessageDto {
  return baseMsg({
    id: 'n1', role: 'user', author: 'team-lead', subtype: 'agent_message',
    isMeta: true, content: NOTICE_TEXT, summary: '收束检索方案与文件清单',
    createdAt: '2026-11-19T09:42:31.000+08:00', userMessageId: null,
    ...partial,
  })
}

describe('MessageList · SendMessage 协作通知行', () => {
  it('通知条：不是用户气泡、不在折叠区、摘要+展开原文、不新轮', async () => {
    await render([
      baseMsg({ id: 'u1', role: 'user', content: '帮我审计这批接口', userMessageId: 'u1' }),
      notice(),
      baseMsg({ id: 'a1', role: 'assistant', content: '收到，继续核对。', userMessageId: 'u1' }),
    ])

    // ① 不是用户气泡
    const bubbles = Array.from(host.querySelectorAll('.msg.user .user-bubble'))
    expect(bubbles.some((b) => (b.textContent ?? '').includes('请收束并回报关键能力'))).toBe(false)

    // ② 通知条存在，且不在「已完成」折叠区
    const el = host.querySelector('.msg.task-notice.agent-notice')
    expect(el, '通知条必须渲染').not.toBeNull()
    expect(el!.closest('.turn-process-body'), '通知条是独立时间线条，不塞进折叠区').toBeNull()

    // ③ 文案：来源 + 摘要（无时间也能显示）
    const text = el!.querySelector('.tn-text')!.textContent ?? ''
    expect(text).toContain('收到主代理通知')
    expect(text).toContain('收束检索方案与文件清单')

    // ④ 展开看原文
    await act(async () => { (el!.querySelector('.tn-head') as HTMLElement).click() })
    await flush(10)
    expect(host.querySelector('.msg.task-notice.agent-notice .tn-body')!.textContent)
      .toBe(NOTICE_TEXT)

    // ⑤ 不开新轮：唯一的用户气泡仍是 u1（通知行不产生第二条用户消息/新轮次头）
    expect(host.querySelectorAll('.msg.user .user-bubble')).toHaveLength(1)
    expect(host.querySelectorAll('.msg.task-notice.agent-notice')).toHaveLength(1)
  })

  it('其他发送方按真实 agent 名显示，不冒充主代理', async () => {
    await render([
      baseMsg({ id: 'u1', role: 'user', content: '继续', userMessageId: 'u1' }),
      notice({ id: 'n2', author: 'researcher', summary: null, content: '补一条检索证据' }),
    ])
    const text = host.querySelector('.msg.task-notice.agent-notice .tn-text')!.textContent ?? ''
    expect(text).toContain('收到researcher通知')
    expect(text).not.toContain('收到主代理通知')
    // 无 summary → 回落正文首行
    expect(text).toContain('补一条检索证据')
  })
})
