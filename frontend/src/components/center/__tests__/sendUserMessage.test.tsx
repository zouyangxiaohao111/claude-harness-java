// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { MessageList } from '../MessageList'
import { useChatStore } from '@/stores/chatStore'
import type { ChatMessageDto, ToolCallDto } from '@/api/types'

/**
 * SendUserMessage（CC BriefTool）前端渲染回归。
 *
 * <b>WHY 这组断言必须存在</b>：CC 里 SendUserMessage 是「模型的可见输出通道」——
 * 系统提示逐字：{@code the answer lives here}；CC 的 UI 把它渲染成<b>无工具边框的正文</b>
 * （tools/BriefTool/UI.tsx:55-68 默认分支），并把该轮其它助手正文丢掉
 * （components/Messages.tsx:169-206 dropTextInBriefTurns，因为那些正文是重复的工作笔记）。
 *
 * 本仓改造前：SendUserMessage 零特殊渲染 ⇒ 落成一张<b>无名折叠卡</b>（{@code userFacingName()=''}
 * 接不住 ⇒ 标题空），模型的答案被埋进折叠区、且同轮正文仍照样显示。故本文件的断言必须钉住
 * 「答案可见」与「重复正文消失」两件事 —— 只断言「某文字出现过」会漏掉「它仍在折叠卡里」这一退化。
 *
 * 双载荷源：结构化 output（tool_calls.result JSON，本轮新增）+ 回落 arguments（历史行）。
 */

const SID = 'sess-sum'

/** 最小 ChatMessageDto（同目录既有写法，避免每例重复造全字段）。 */
function baseMsg(id: string, role: 'user' | 'assistant', content: string): ChatMessageDto {
  return {
    id, sessionId: SID, role, author: role === 'user' ? '你' : 'nexus', content,
    reasoning: null, toolCalls: null, finishReason: null, inputTokens: null, outputTokens: null,
    reasoningDurationMs: null, time: null, toolCallId: null, assistantMessageId: null,
    userMessageId: null, subtype: null, isMeta: false, isApiErrorMessage: false, apiError: null,
    error: null, errorDetails: null, matchedRule: null,
  }
}

function sumCall(message: string, opts: {
  result?: string | null
  attachments?: { path: string; size?: number; isImage?: boolean }[]
  argPaths?: string[]
} = {}): ToolCallDto {
  return {
    id: 't-sum',
    name: 'SendUserMessage',
    arguments: JSON.stringify({
      message,
      ...(opts.argPaths ? { attachments: opts.argPaths } : {}),
      status: 'normal',
    }),
    result: opts.result === undefined
      ? JSON.stringify({
          message,
          sentAt: '2026-09-20T00:00:00.000Z',
          ...(opts.attachments ? { attachments: opts.attachments } : {}),
        })
      : opts.result,
    isError: false,
  }
}

const ANSWER = '验收结果：全部通过'

describe('SendUserMessage 正文渲染（CC BriefTool 对齐）', () => {
  let container: HTMLDivElement
  let root: Root

  beforeEach(() => {
    (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
  })

  afterEach(() => {
    act(() => { root.unmount() })
    container.remove()
  })

  async function mount(messages: ChatMessageDto[]) {
    useChatStore.setState({
      messages: { [SID]: messages }, hasMore: {},
      streams: {}, streamOrder: {}, streamTicks: {}, extendedWindow: {},
    })
    await act(async () => {
      root.render(
        <MessageList sessionId={SID} messages={messages} onDelete={() => {}} conversationId={null}
          onLoadOlder={() => {}} onNearBottomChange={() => {}} />,
      )
    })
  }

  it('该 message 渲染为正文（无工具边框/无工具卡）：结构化 output 源', async () => {
    const user = { ...baseMsg('u1', 'user', '跑完了吗'), userMessageId: 'u1' }
    const assistant = {
      ...baseMsg('a1', 'assistant', ''),
      userMessageId: 'u1',
      toolCalls: [sumCall(ANSWER)],
    }
    await mount([user, assistant])

    // 答案必须作为正文出现在 DOM 里
    expect(container.textContent).toContain(ANSWER)
    // 且【不得】是工具卡（无 chrome、无折叠、无「已完成」状态徽标）——
    // 这是本条的判别点：改造前答案藏在 .tool-card 的无名折叠区里，textContent 断言照样通过
    expect(container.querySelector('.tool-card')).toBeNull()
    expect(container.textContent).not.toContain('已完成')
    // 正文块在场（无工具边框的 Claude 话语块）
    expect(container.querySelector('.sum-body')).not.toBeNull()
  })

  it('附件列表对齐 CC AttachmentList：[image]/[file] + 路径 + 大小', async () => {
    const user = { ...baseMsg('u1', 'user', '看下'), userMessageId: 'u1' }
    const assistant = {
      ...baseMsg('a1', 'assistant', ''),
      userMessageId: 'u1',
      toolCalls: [sumCall('给你两张图', {
        attachments: [
          { path: '/tmp/photo.png', size: 4096, isImage: true },
          { path: '/tmp/report.log', size: 1024 * 1024, isImage: false },
        ],
      })],
    }
    await mount([user, assistant])

    const rows = Array.from(container.querySelectorAll('.sum-att')).map((el) => el.textContent ?? '')
    expect(rows).toEqual([
      '[image]/tmp/photo.png (4KB)',
      '[file]/tmp/report.log (1MB)',
    ])
  })

  it('旧数据（result 是人类文案）→ 回落 arguments 渲染正文，不空白', async () => {
    const user = { ...baseMsg('u1', 'user', '跑完了吗'), userMessageId: 'u1' }
    const assistant = {
      ...baseMsg('a1', 'assistant', ''),
      userMessageId: 'u1',
      // 改造前落库形态：result 逐字是模型面文案
      toolCalls: [sumCall(ANSWER, { result: 'Message delivered to user.' })],
    }
    await mount([user, assistant])

    expect(container.textContent).toContain(ANSWER)
    expect(container.querySelector('.tool-card')).toBeNull()
  })

  it('[F2] 失败态不渲染成投递成功：正文/附件行都不出，改出错误文案', async () => {
    // WHY：附件 TOCTOU（validate 通过、execute 时文件已被移走）⇒ 后端 ToolResult.error。
    //   此时 CC 的调度点 UserToolResultMessage.tsx:71-86 走 UserToolErrorMessage，**从不**调用
    //   renderToolResultMessage；若本仓仍按 arguments 兜底渲染正文，用户会看到一条「投递成功」的
    //   消息、附件退化成无大小的 [file] 路径 —— 失败被彻底伪装成成功。
    const user = { ...baseMsg('u1', 'user', '看下'), userMessageId: 'u1' }
    const failed: ToolCallDto = {
      ...sumCall(ANSWER, { argPaths: ['/tmp/gone.png'] }),
      result: 'Error: Attachment resolution failed: ENOENT: no such file or directory',
      isError: true,
    }
    await mount([user, { ...baseMsg('a1', 'assistant', ''), userMessageId: 'u1', toolCalls: [failed] }])

    // 判别点：失败态绝不渲染正文与附件列表
    expect(container.querySelector('.sum-body')).toBeNull()
    expect(container.querySelector('.sum-att')).toBeNull()
    expect(container.textContent).not.toContain(ANSWER)
    // 但失败必须可见（不静默消失）：对齐 CC FallbackToolUseErrorMessage 的错误文案
    const err = container.querySelector('.sum-error')
    expect(err).not.toBeNull()
    expect(err!.textContent).toContain('Attachment resolution failed')
  })

  it('[F2] 失败且无错误文本（result=null）→ 仍给失败占位，不空白', async () => {
    // 对齐 CC FallbackToolUseErrorMessage.tsx:32-33：非 string 的 result → 'Tool execution failed'。
    const user = { ...baseMsg('u1', 'user', '看下'), userMessageId: 'u1' }
    const failed: ToolCallDto = { ...sumCall(ANSWER), result: null, isError: true }
    await mount([user, { ...baseMsg('a1', 'assistant', ''), userMessageId: 'u1', toolCalls: [failed] }])

    expect(container.textContent).not.toContain(ANSWER)
    expect(container.querySelector('.sum-error')?.textContent).toContain('Tool execution failed')
  })

  it('含 SendUserMessage 的轮次：同轮其它助手正文被丢掉，工具卡保留', async () => {
    const user = { ...baseMsg('u1', 'user', '跑完了吗'), userMessageId: 'u1' }
    // 同一轮里助手先说了一段工作笔记（CC：与 SendUserMessage 内容重复的冗余正文），又调了 Bash
    const notes = { ...baseMsg('a1', 'assistant', '让我先看看输出……这是一段工作笔记'), userMessageId: 'u1' }
    const bash = {
      ...baseMsg('a2', 'assistant', ''),
      userMessageId: 'u1',
      toolCalls: [
        { id: 't-bash', name: 'Bash', arguments: '{"command":"ls"}', result: 'line-0', isError: false },
        sumCall(ANSWER),
      ] as ToolCallDto[],
    }
    await mount([user, notes, bash])

    // 冗余正文消失
    expect(container.textContent).not.toContain('工作笔记')
    // 答案仍在
    expect(container.textContent).toContain(ANSWER)
    // 别的工具卡不受影响（正文丢了、工具卡保留）
    expect(container.querySelector('.tool-card')).not.toBeNull()
    expect(container.textContent).toContain('Bash')
  })
})
