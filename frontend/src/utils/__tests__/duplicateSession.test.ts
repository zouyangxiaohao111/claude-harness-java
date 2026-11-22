import { beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * 会话「复制」= 以**最后一条消息**为分叉点分叉出新会话。
 *
 * <b>WHY 单独测</b>：pivot 取法（尾页的最后一条）是这个功能的全部语义 ——
 * 取错一条，用户复出来的会话就少一轮对话，而且**不报错**（后端照常分叉成功）。
 * 纯函数/组件测试全绿也证明不了 pivot 选对了 ⇒ 这里对 API 边界做断言。
 */

vi.mock('@/api/chat', () => ({
  chatApi: { listMessagesPage: vi.fn() },
}))
vi.mock('@/api/sessions', () => ({
  sessionApi: { fork: vi.fn() },
}))

import { chatApi } from '@/api/chat'
import { sessionApi } from '@/api/sessions'
import { duplicateSession } from '../duplicateSession'

const listMessagesPage = vi.mocked(chatApi.listMessagesPage)
const fork = vi.mocked(sessionApi.fork)

const msg = (id: string, createdAt: string) => ({
  id,
  sessionId: 's1',
  role: 'assistant' as const,
  author: null,
  content: 'hi',
  reasoning: null,
  toolCalls: null,
  finishReason: null,
  inputTokens: null,
  outputTokens: null,
  reasoningDurationMs: null,
  time: null,
  createdAt,
  toolCallId: null,
  assistantMessageId: null,
  subtype: null,
  isMeta: false,
  isApiErrorMessage: false,
  apiError: null,
  error: null,
  errorDetails: null,
  matchedRule: null,
})

beforeEach(() => {
  vi.clearAllMocks()
})

describe('duplicateSession · 会话复制（= 以最后一条为分叉点）', () => {
  it('⭐ 分叉点 = 尾页返回的最后一条消息（不是第一条）', async () => {
    listMessagesPage.mockResolvedValue({
      messages: [msg('m-1', '2026-11-18T10:00:00Z'), msg('m-2', '2026-11-18T10:00:01Z'), msg('m-3', '2026-11-18T10:00:02Z')],
      hasMore: true,
      total: 3,
    })
    fork.mockResolvedValue({ session: { id: 's2' } as never, copiedMessages: 3 })

    const out = await duplicateSession('s1')

    expect(out.ok).toBe(true)
    expect(fork).toHaveBeenCalledTimes(1)
    expect(fork).toHaveBeenCalledWith('s1', 'm-3')
  })

  it('只拉尾页一条（limit=1）—— 不全量拉几千条消息', async () => {
    listMessagesPage.mockResolvedValue({ messages: [msg('m-1', '2026-11-18T10:00:00Z')], hasMore: true, total: 8439 })
    fork.mockResolvedValue({ session: { id: 's2' } as never, copiedMessages: 1 })

    await duplicateSession('s1')

    expect(listMessagesPage).toHaveBeenCalledWith('s1', { limit: 1 })
  })

  it('空会话 ⇒ 不发 fork（后端强制要求 pivot 存在），返回 empty', async () => {
    listMessagesPage.mockResolvedValue({ messages: [], hasMore: false, total: 0 })

    const out = await duplicateSession('s1')

    expect(out).toEqual({ ok: false, reason: 'empty' })
    expect(fork).not.toHaveBeenCalled()
  })

  it('后端错误原样抛出（调用方据此弹提示，不静默）', async () => {
    listMessagesPage.mockResolvedValue({ messages: [msg('m-1', '2026-11-18T10:00:00Z')], hasMore: false, total: 1 })
    fork.mockRejectedValue(new Error('boom'))

    await expect(duplicateSession('s1')).rejects.toThrow('boom')
  })
})
