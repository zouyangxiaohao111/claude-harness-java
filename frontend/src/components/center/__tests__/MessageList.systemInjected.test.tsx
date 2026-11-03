// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ChatMessageDto } from '@/api/types'

/**
 * 系统注入行的渲染（2026-11-02 用户裁定）。
 *
 * <h2>WHY（意图验证）</h2>
 * <p>用户原话：「`<system-reminder> The following skills are available…` 这种 hook 不能算用户消息，
 * 应该**融合在已完成中**、显示为**系统提示**；主会话也是一样」。
 *
 * <p>改造前的两个错误表现，本测试各钉一条：
 * <ul>
 *   <li><b>主会话</b>：`isDialogueRow` 把 isMeta 行整条丢弃 ⇒ 用户完全看不到
 *       ⇒ ① 断言「技能清单**不**渲染成用户气泡」；② 断言折叠区里能看到它（弱化行）。</li>
 *   <li><b>子代理视图</b>：transcript 无 isMeta 字段 ⇒ 被当普通提问 ⇒ 成气泡
 *       （该侧由 AgentTranscriptView 透传三件套修复，本文件覆盖主会话渲染）。</li>
 * </ul>
 *
 * <h2>RED teeth</h2>
 * <ul>
 *   <li>把 `isSystemInjectedRow` 从 `isDialogueRow` 的白名单里去掉 ⇒ 本轮只有「user + 纯正文 assistant
 *       + 注入行」时折叠区仍会出现（因为 hasIntermediate 兜底）但注入行不渲染 ⇒ 第 ② 条变红；</li>
 *   <li>删掉 `systemRows?.map(...)` ⇒ 第 ② 条变红；</li>
 *   <li>删掉 flatRows.map 里的 `if (isSystemInjectedRow(row.m)) return null` ⇒ 注入行以气泡形式
 *       独立出现 ⇒ 第 ① 条变红。</li>
 * </ul>
 */

vi.mock('@/api/socket', () => ({ onSocketConnect: () => () => {} }))

import { MessageList } from '@/components/center/MessageList'

const SID = 'sess-1'
const SKILLS_TEXT = `<system-reminder>
The following skills are available for use with the Skill tool:

- alpha-skill: 第一个技能
- beta-skill: 第二个技能
</system-reminder>`

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

describe('MessageList · 系统注入行', () => {
  it('技能清单不渲染成用户气泡，但能在该轮折叠区里看到（WHY：用户裁定融合在已完成中、显示为系统提示）', async () => {
    const messages: ChatMessageDto[] = [
      baseMsg({ id: 'u1', role: 'user', content: '帮我看看这个仓库', userMessageId: 'u1' }),
      baseMsg({
        id: 'att1', role: 'user', author: 'attachment', subtype: 'skill_listing',
        isMeta: true, content: SKILLS_TEXT, userMessageId: null,
      }),
      baseMsg({
        id: 'a1', role: 'assistant', content: '好的，我先看一下目录结构。',
        userMessageId: 'u1',
      }),
    ]
    await act(async () => {
      root = createRoot(host)
      root.render(<MessageList sessionId={SID} messages={messages} onDelete={() => {}} />)
    })
    await flush(10)

    // ① 绝不能被当成用户说的话（用户气泡里不得出现技能清单正文）
    const bubbles = Array.from(host.querySelectorAll('.msg.user .user-bubble'))
    expect(bubbles.some((b) => (b.textContent ?? '').includes('alpha-skill'))).toBe(false)

    // ② 该轮的「已完成」折叠入口必须出现，且展开后能在折叠区里看到弱化的系统提示行
    const toggle = host.querySelector('.turn-process-toggle')
    expect(toggle).not.toBeNull()
    await act(async () => { (toggle as HTMLElement).click() })
    await flush(10)

    const notice = host.querySelector('.turn-process-body .msg.task-notice')
    expect(notice).not.toBeNull()
    // 摘要按 subtype 给人话，不把整段 XML 铺在对话流里
    expect(notice!.querySelector('.tn-text')!.textContent).toContain('技能清单已注入')
    expect(notice!.querySelector('.tn-text')!.textContent).toContain('2 项')
  })
})
