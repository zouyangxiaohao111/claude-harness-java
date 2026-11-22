// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ChatMessageDto } from '@/api/types'

/**
 * 系统注入行的渲染（2026-11-02 用户裁定；2026-11-20 用户两次改判）。
 *
 * <h2>WHY（意图验证）</h2>
 * <ul>
 *   <li>2026-11-02：注入行「不能算用户消息，应该显示为系统提示」⇒ 不得渲染成用户气泡。</li>
 *   <li>2026-11-20 第一次：用户实测这些行看起来被「插到消息列表前端」⇒ 改判按自身位置**独立渲染**。</li>
 *   <li>2026-11-20 第二次（本次，用户截图）：注入行跑到「已完成 · 2m16s」**上方**仍不对 ⇒
 *       改判挂在「已完成 / 正在执行」状态行**下方**，跟随轮展开态（收起时与折叠区一起隐藏）。</li>
 * </ul>
 *
 * <h2>RED teeth</h2>
 * <ul>
 *   <li>把 `isSystemInjectedRow` 从 `isDialogueRow` 的白名单里去掉 ⇒ 注入行整条消失 ⇒ 第 ① 条变红；</li>
 *   <li>把渲染循环里的 `<SystemInjectedRow>` 改回 `return null` ⇒ 第 ① / ② 条变红；</li>
 *   <li>把注入行放回轮头（不挂状态行落点行）⇒ 第 ③ 条（必须位于 .turn-process-toggle 之后）变红。</li>
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
  it('收起态不显示注入行；展开后按自身位置渲染（不是用户气泡、不在轮内折叠区）', async () => {
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

    // ① 收起态（默认）不露注入行 —— 用户裁定「收起状态怎么还有」
    expect(host.querySelector('.msg.task-notice')).toBeNull()

    // 展开该轮
    const toggle = host.querySelector('.turn-process-toggle') as HTMLElement | null
    expect(toggle).not.toBeNull()
    await act(async () => { toggle!.click() })
    await flush(10)

    // ② 绝不能被当成用户说的话（用户气泡里不得出现技能清单正文）
    const bubbles = Array.from(host.querySelectorAll('.msg.user .user-bubble'))
    expect(bubbles.some((b) => (b.textContent ?? '').includes('alpha-skill'))).toBe(false)

    // ③ 展开后弱化的系统提示行出现在对话流里
    const notice = host.querySelector('.msg.task-notice') as HTMLElement | null
    expect(notice).not.toBeNull()
    expect(notice!.querySelector('.tn-text')!.textContent).toContain('技能清单已注入')
    expect(notice!.querySelector('.tn-text')!.textContent).toContain('2 项')

    // ④ [2026-11-20 第二次改判] 位于「已完成 / 正在执行」状态行**下方**（不是轮头），
    //    也不进折叠内容（.turn-process-body）。
    const toggleEl = host.querySelector('.turn-process-toggle') as HTMLElement | null
    expect(toggleEl).not.toBeNull()
    expect(toggleEl!.compareDocumentPosition(notice!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(host.querySelector('.turn-process-body .msg.task-notice')).toBeNull()
  })

  it('注入行渲染在「已完成」状态行下方（同一轮内，不再跑到轮头）', async () => {
    const messages: ChatMessageDto[] = [
      baseMsg({ id: 'u1', role: 'user', content: '改一下这个文件', userMessageId: 'u1' }),
      baseMsg({ id: 'a1', role: 'assistant', content: '改好了。', userMessageId: 'u1' }),
      baseMsg({
        id: 'att1', role: 'user', author: 'attachment', subtype: 'edited_text_file',
        isMeta: true, content: '<system-reminder>Note: x was modified</system-reminder>',
        userMessageId: null,
      }),
    ]
    await act(async () => {
      root = createRoot(host)
      root.render(<MessageList sessionId={SID} messages={messages} onDelete={() => {}} />)
    })
    await flush(10)

    // 收起态不可见；展开后按序出现
    expect(host.querySelector('.msg.task-notice')).toBeNull()
    const toggle = host.querySelector('.turn-process-toggle') as HTMLElement | null
    expect(toggle).not.toBeNull()
    await act(async () => { toggle!.click() })
    await flush(10)

    const notice = host.querySelector('.msg.task-notice') as HTMLElement | null
    expect(notice).not.toBeNull()
    expect(notice!.querySelector('.tn-text')!.textContent).toContain('文件被外部修改')

    // DOM 顺序：注入行在状态行之后（= 「已完成」下面），不是被提到轮头
    const toggleAfter = host.querySelector('.turn-process-toggle') as HTMLElement | null
    expect(toggleAfter).not.toBeNull()
    expect(toggleAfter!.compareDocumentPosition(notice!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })
})
