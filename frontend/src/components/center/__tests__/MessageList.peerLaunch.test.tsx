// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ChatMessageDto } from '@/api/types'
import { MessageList } from '@/components/center/MessageList'

/**
 * [peer-launch] 派单消息（SessionCreate 首条任务）的渲染口径（用户 2026-11-22 裁定 · 视觉 A）：
 * 来信卡片放在**用户气泡位置**（外包 `.msg.user` + `.user-bubble`，气泡透明仅作右对齐容器），
 * 卡片自身的来源头 / 正文保持原样。
 *
 * ⚠️ 只对 `id=peer-launch-*` 的派单行生效；SendMessage 来信（`id=msg-*` 或 `peer-*`，
 * 同 subtype=agent_message）仍是整行 `.msg.peer-message` 卡片、不开新轮。
 */

const SID = 'sess-child'
const SOURCE = 'sess-parent'
const LAUNCH_ID = 'peer-launch-7af547eb-e7da-4cee-9370-c86cc8ee1b37'
const CREATED_AT = '2026-11-22T16:15:55.000+08:00'

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

/** SessionCreateTool 落库契约：id=peer-launch-*、isMeta=true、subtype=agent_message、带 peer_source。 */
function launch(patch: Partial<ChatMessageDto> = {}): ChatMessageDto {
  return baseMsg(LAUNCH_ID, {
    role: 'user',
    author: SOURCE,
    subtype: 'agent_message',
    isMeta: true,
    content: '你接手一个已规划好的小批次：微信渠道 A+D',
    userMessageId: LAUNCH_ID,
    createdAt: CREATED_AT,
    structuredOutput: {
      peer_source: {
        sourceSessionId: SOURCE,
        sourceTitle: '探索页面规划清单为最终方案',
        sourceKind: 'peer-session',
        messageId: LAUNCH_ID,
        endpointGeneration: 2,
        fromMode: 'bypassPermissions',
        from: 'sess-parent [295fdd]',
        hopChain: [SOURCE],
        admission: 'auto',
        admissionReason: 'mode-match',
      },
    },
    ...patch,
  })
}

/** SendMessage 普通来信：同 subtype / isMeta，但 id 不是派单前缀。 */
function mail(patch: Partial<ChatMessageDto> = {}): ChatMessageDto {
  return baseMsg('peer-1', {
    role: 'user',
    author: SOURCE,
    subtype: 'agent_message',
    isMeta: true,
    content: '请补充审计结论',
    userMessageId: null,
    createdAt: CREATED_AT,
    structuredOutput: {
      peer_source: {
        sourceSessionId: SOURCE,
        sourceTitle: '审计报告',
        sourceKind: 'session',
        messageId: 'src-msg-9',
        fromMode: 'default',
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

async function render(messages: ChatMessageDto[], opts: { expandTurns?: boolean } = {}): Promise<void> {
  await act(async () => {
    root = createRoot(host)
    root.render(<MessageList sessionId={SID} messages={messages} onDelete={() => {}} />)
  })
  await flush(10)
  // 普通来信卡跟随轮收起态（默认收起）⇒ 需要时先展开承载它的轮。
  // ⚠️ 派单行**不受**收起态影响（轮起点常显），所以派单用例不传 expandTurns。
  if (!opts.expandTurns) return
  const toggle = host.querySelector<HTMLButtonElement>('.turn-process-toggle')
  if (toggle) {
    await act(async () => { toggle.click() })
    await flush(10)
  }
}

describe('[peer-launch] 派单消息：来信卡片放在用户气泡位置', () => {
  it('派单行：卡片在 .msg.user .user-bubble 内，来源头保留，且收起态下仍常显', async () => {
    await render([
      launch(),
      baseMsg('a1', { role: 'assistant', content: '开始执行', userMessageId: LAUNCH_ID }),
    ])

    const bubble = host.querySelector('.msg.user .user-bubble.peer-launch-bubble')
    expect(bubble, '派单卡片必须挂在用户气泡位置').not.toBeNull()
    const card = bubble!.querySelector('.pm-card')
    expect(card, '气泡里必须是来信卡片本体').not.toBeNull()
    expect(card!.querySelector('.pm-dir')!.textContent).toBe('收到消息')
    expect(card!.querySelector('.pm-title')!.textContent).toBe('《探索页面规划清单为最终方案》')

    // 普通来信的整行外壳不得出现（同一个 DOM 只能走一条分支）
    expect(host.querySelector('.msg.peer-message')).toBeNull()
    // 只借用外壳：不得出现用户气泡的编辑/重发动作区
    expect(host.querySelector('.msg.user .user-msg-meta')).toBeNull()

    // 卡片样式必须同时命中「整行来信」与「派单气泡」两个容器：
    // 此前 `.pm-*` 全部写成 `.msg.peer-message .pm-*`，派单搬到 .msg.user 后样式全丢（只剩纯文本）。
    const styleText = Array.from(host.querySelectorAll('style')).map((s) => s.textContent ?? '').join('\n')
    expect(styleText).toContain(':is(.msg.peer-message, .msg.user .peer-launch-bubble) .pm-card')
  })

  it('普通来信（同 subtype，id 非派单前缀）：仍是整行 .msg.peer-message，不进用户气泡', async () => {
    await render([mail()], { expandTurns: true })

    expect(host.querySelector('.msg.peer-message .pm-card')).not.toBeNull()
    expect(host.querySelector('.msg.user .peer-launch-bubble')).toBeNull()
  })
})
