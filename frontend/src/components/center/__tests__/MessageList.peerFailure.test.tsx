// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ChatMessageDto } from '@/api/types'

/**
 * 跨会话**投递失败通知条**（合并：过期 / 被拒 / 入站拒绝 / 不在线 / 限流 / 去重 / 队满 / 预算 / 循环 / 失控转发）。
 *
 * <h2>WHY（意图验证）</h2>
 * <p>后端在发送方会话落库一条 meta 行（subtype=agent_message + isMeta=true +
 * structured_output.peer_failure）。它与「跨会话来信」共用 subtype，但语义相反（我方发出未送达）。
 * 此前的错误表现：走普通代理通知文案「收到 X 通知 · <英文原文>」，用户看不懂；若误判成来信卡片，
 * 还会显示「发送方 / 来源会话」等来信语义。本测试钉住三条互斥：
 * <ol>
 *   <li>有 peer_failure ⇒ 专门失败通知条（提示原因 + 来源会话《标题》与 id）；</li>
 *   <li>⛔ 不是来信卡片（.peer-message 不存在）；⛔ 不是普通代理通知（.agent-notice 不存在）；</li>
 *   <li>⛔ 不是用户气泡、不生成用户轮、不切会话、不拉历史、标题不可点击、无「重发」。</li>
 * </ol>
 *
 * <h2>RED teeth（变异自检）</h2>
 * <ul>
 *   <li>删掉 reason→中文映射（回落普通代理通知文案）⇒ 表格用例全红（每档文案对不上）；</li>
 *   <li>把 peerFailure 分支挪到来信卡片分支之后 ⇒ 缺 peer_source 仍走来信分支? 不走；但若把它删掉
 *       ⇒ 写成「收到 X 通知」⇒ 互斥用例红；</li>
 *   <li>用 dangerouslySetInnerHTML 渲染标题 ⇒ HTML 元素被创建 ⇒ 转义用例红；</li>
 *   <li>渲染时触发会话切换/拉历史 ⇒ onOpenPeerSession / fetch spy 非零 ⇒ 对应用例红。</li>
 * </ul>
 */

vi.mock('@/api/socket', () => ({ onSocketConnect: () => () => {} }))

import { MessageList } from '@/components/center/MessageList'

const SID = 'sess-sender01'
const TARGET = 'sess-target01'
const TITLE = '审计报告'
const MSG_ID = 'm-123456'

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

/** 后端落库行合同（PeerFailureNotifier.structuredOutput）：peer_failure 四键。 */
function failureRow(reason: string, patch: Partial<ChatMessageDto> = {}): ChatMessageDto {
  return baseMsg(`peer-failure-${MSG_ID}`, {
    role: 'user',
    // 后端 author 位 = 接收方会话（失败通知的「来源会话」）
    author: TARGET,
    content: 'Your held message expired without approval and was not delivered to the recipient\'s session.',
    subtype: 'agent_message',
    isMeta: true,
    userMessageId: null,
    structuredOutput: {
      peer_failure: {
        messageId: MSG_ID,
        reason,
        receiverSessionId: TARGET,
        receiverTitle: TITLE,
      },
    },
    ...patch,
  })
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
  vi.unstubAllGlobals()
})

async function render(messages: ChatMessageDto[], onOpenPeerSession?: (sid: string) => void): Promise<void> {
  await act(async () => {
    root = createRoot(host)
    root.render(
      <MessageList sessionId={SID} messages={messages} onDelete={() => {}} onOpenPeerSession={onOpenPeerSession} />,
    )
  })
  await flush(10)
}

function bar(): HTMLElement {
  const el = host.querySelector<HTMLElement>('.msg.task-notice.peer-failure-notice')
  expect(el, '有 peer_failure 的行必须渲染成专门的投递失败通知条').not.toBeNull()
  return el!
}

describe('MessageList · 跨会话投递失败通知条', () => {
  it('reason → 中文文案映射（每档都带来源会话《标题》与来源 id）', async () => {
    const cases: Array<[string, string]> = [
      ['expired', `你发给《${TITLE}》（${TARGET}）的消息已过期，未获批准，未投递给对方。`],
      ['rejected', `你发给《${TITLE}》（${TARGET}）的消息被对方拒绝。`],
      ['refused', `你发给《${TITLE}》（${TARGET}）的消息被对方入站策略拒绝。`],
      ['no_live_session', `你发给《${TITLE}》（${TARGET}）的消息未送达：目标会话不在线。`],
      ['rate_limited', `你发给《${TITLE}》（${TARGET}）的消息被限流丢弃：发送过于频繁。`],
      ['duplicate', `你发给《${TITLE}》（${TARGET}）的消息被去重丢弃：短时间内重复内容。`],
      ['queue_full', `你发给《${TITLE}》（${TARGET}）的消息被拒绝：对方待收件已满。`],
      ['relay_budget', `你发给《${TITLE}》（${TARGET}）的消息未送达：转发预算耗尽。`],
      ['hop-loop', `你发给《${TITLE}》（${TARGET}）的消息在会话之间反复往返，已被判定为循环并拦截。`],
      ['hop_loop', `你发给《${TITLE}》（${TARGET}）的消息在会话之间反复往返，已被判定为循环并拦截。`],
      ['hop-runaway', `你发给《${TITLE}》（${TARGET}）的消息转发链过长，已被判定为失控转发并拦截。`],
      ['hop_runaway', `你发给《${TITLE}》（${TARGET}）的消息转发链过长，已被判定为失控转发并拦截。`],
      ['whatever', `投递到《${TITLE}》（${TARGET}）失败（未知原因）。`],
    ]
    for (const [reason, expected] of cases) {
      await render([
        baseMsg('u1', { role: 'user', content: '继续', userMessageId: 'u1' }),
        failureRow(reason),
      ])
      const el = bar()
      expect(el.querySelector('.tn-text')!.textContent, `reason=${reason} 文案`).toBe(expected)
      expect(el.textContent).toContain(TARGET)
      act(() => { root?.unmount() })
      root = null
      host.replaceChildren()
    }
  })

  it('不误判成来信卡片、也不走普通代理通知文案', async () => {
    await render([
      baseMsg('u1', { role: 'user', content: '继续', userMessageId: 'u1' }),
      failureRow('expired'),
    ])
    const el = bar()
    // ⛔ 不是来信卡片（没有 peer_source）
    expect(host.querySelector('.msg.peer-message')).toBeNull()
    expect(el.classList.contains('peer-message')).toBe(false)
    // ⛔ 不是普通代理通知（旧文案「收到 X 通知」不得出现）
    expect(el.classList.contains('agent-notice')).toBe(false)
    expect(el.textContent).not.toContain('收到')
    expect(el.textContent).not.toContain('通知 ·')
  })

  it('标题缺失显示「未命名会话」+ 仍显示来源 id', async () => {
    await render([
      baseMsg('u1', { role: 'user', content: '继续', userMessageId: 'u1' }),
      failureRow('expired', { structuredOutput: {
        peer_failure: { messageId: MSG_ID, reason: 'expired', receiverSessionId: TARGET },
      } }),
    ])
    const text = bar().querySelector('.tn-text')!.textContent ?? ''
    expect(text).toContain('未命名会话')
    expect(text).toContain(TARGET)
    expect(text).not.toContain('《》')
  })

  it('展开看原始原因与 messageId；不提供「重发」按钮', async () => {
    await render([
      baseMsg('u1', { role: 'user', content: '继续', userMessageId: 'u1' }),
      failureRow('user-declined'),
    ])
    const el = bar()
    expect(el.querySelector('.pfn-body')).toBeNull()
    const head = el.querySelector<HTMLButtonElement>('.tn-head')!
    await act(async () => { head.click() })
    await flush(10)
    const detail = el.querySelector('.pfn-body')!.textContent ?? ''
    expect(detail).toContain('原始原因：user-declined')
    expect(detail).toContain(`消息 ID：${MSG_ID}`)
    expect(Array.from(el.querySelectorAll('button')).map((b) => b.textContent))
      .not.toContain('重发')
  })

  it('刷新后仍渲染（持久行 = GET /messages 同形，不依赖实时通道）', async () => {
    // 模拟 F5：同一份后端行重新整表加载，渲染路径与首屏一致
    await render([
      baseMsg('u1', { role: 'user', content: '继续', userMessageId: 'u1' }),
      failureRow('queue_full'),
    ])
    expect(bar().querySelector('.tn-text')!.textContent)
      .toBe(`你发给《${TITLE}》（${TARGET}）的消息被拒绝：对方待收件已满。`)

    act(() => { root?.unmount() })
    root = null
    host.replaceChildren()
    await render([failureRow('queue_full')])
    expect(host.querySelectorAll('.peer-failure-notice')).toHaveLength(1)
  })

  it('不生成用户轮、不切会话、不拉历史、标题不可点击', async () => {
    const fetchSpy = vi.fn(() => Promise.resolve(new Response('{}')))
    vi.stubGlobal('fetch', fetchSpy)
    const onJump = vi.fn()
    await render([
      baseMsg('u1', { role: 'user', content: '继续', userMessageId: 'u1' }),
      failureRow('expired'),
    ], onJump)

    const el = bar()
    // 只有提示条本身的展开按钮；标题没有可点击入口
    expect(el.querySelectorAll('button')).toHaveLength(1)
    expect(el.querySelector('.pm-jump')).toBeNull()
    expect(el.querySelectorAll('a')).toHaveLength(0)
    expect(onJump).not.toHaveBeenCalled()
    expect(fetchSpy).not.toHaveBeenCalled()

    // 不生成用户轮：用户气泡只有 u1 一条
    const bubbles = Array.from(host.querySelectorAll('.msg.user .user-bubble'))
    expect(bubbles).toHaveLength(1)
    expect(bubbles[0]!.textContent).toContain('继续')
  })

  it('标题/正文按纯文本渲染，不执行 HTML', async () => {
    const raw = '<img src=x onerror="alert(1)"><script>alert(2)</script>'
    await render([
      baseMsg('u1', { role: 'user', content: '继续', userMessageId: 'u1' }),
      failureRow('expired', { structuredOutput: {
        peer_failure: { messageId: MSG_ID, reason: 'expired', receiverSessionId: TARGET, receiverTitle: raw },
      } }),
    ])
    const text = bar().querySelector('.tn-text')!.textContent ?? ''
    expect(text).toContain(raw)
    expect(host.querySelector('img')).toBeNull()
    expect(host.querySelector('script')).toBeNull()
  })
})
