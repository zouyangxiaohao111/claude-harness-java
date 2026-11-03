// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AgentTranscriptMessage } from '@/api/types'

/**
 * 右栏子代理对话视图 · 复用主对话渲染 + 实时增量（真实渲染）。
 *
 * <h2>WHY（意图验证 · 规则九）</h2>
 * <p>用户两条诉求：
 * <ol>
 *   <li><b>「渲染太丑，按主会话的方式渲染」</b>：此前逐条平铺 `<Message>`，没有轮次分组、没有
 *       一轮一个消息头、没有「已完成」状态行与中间过程折叠。⇒ 本测试断言 transcript 交给
 *       **同一个 MessageList** 渲染（轮次状态行 `.turn-process-toggle` 出现）。</li>
 *   <li><b>「现在不是实时的」</b>：此前只在打开时 REST 拉一次。⇒ 本测试断言订阅 topic 后收到的
 *       增量会出现在视图里，且重复 uuid 不会重复渲染（后端「订阅后补拉 + 实时增量」必然重叠）。</li>
 * </ol>
 *
 * <h2>RED teeth</h2>
 * <ul>
 *   <li>把渲染退回逐条 `<Message>` ⇒ 「同一轮只出现一个消息头 / 状态行」用例红（此前每条都带头）；</li>
 *   <li>删掉 STOMP 订阅或不做 uuid 去重 ⇒ 实时用例 / 去重用例红。</li>
 * </ul>
 */

const transcriptMock = vi.hoisted(() => vi.fn())
vi.mock('@/api/subagents', () => ({
  subagentApi: { transcript: transcriptMock, list: vi.fn(async () => []) },
}))
vi.mock('@/api/socket', () => ({
  onSocketConnect: () => () => { /* 测试里不模拟重连 */ },
}))

import { AgentTranscriptView } from '@/components/right/AgentTranscriptView'

const SID = 'sess-1'
const AGENT_HEX = 'a0123456789abcdef'

/** 后端 REST / STOMP 出站的 transcript 形状（AgentMessage record 序列化） */
function msg(partial: Partial<AgentTranscriptMessage> & { role: AgentTranscriptMessage['role']; content: string }): AgentTranscriptMessage {
  return {
    isApiError: false,
    agentId: AGENT_HEX,
    isSidechain: true,
    uuid: `u-${Math.random().toString(36).slice(2)}`,
    parentUuid: null,
    toolCallId: null,
    ...partial,
  }
}

/** 最小 STOMP client：记录订阅回调，供测试注入一帧增量 */
function fakeClient() {
  const handlers = new Map<string, (frame: { body: string }) => void>()
  const client = {
    connected: true,
    subscribe: (topic: string, cb: (frame: { body: string }) => void) => {
      handlers.set(topic, cb)
      return { unsubscribe: () => { handlers.delete(topic) } }
    },
  }
  return { client, handlers }
}

async function flush(ms = 0): Promise<void> {
  await act(async () => { await new Promise((r) => setTimeout(r, ms)) })
}

let host: HTMLDivElement
let root: Root | null = null

beforeEach(() => {
  host = document.createElement('div')
  document.body.appendChild(host)
  transcriptMock.mockReset()
})

afterEach(() => {
  act(() => { root?.unmount() })
  root = null
  host.remove()
})

describe('AgentTranscriptView · 复用主对话渲染 + 实时', () => {
  it('transcript 交给主对话 MessageList 渲染：一轮只一个消息头，且有「已完成」状态行', async () => {
    transcriptMock.mockResolvedValue([
      msg({ role: 'user', content: '请统计仓库里的 TODO', uuid: 'u-user' }),
      msg({ role: 'assistant', content: '我先看一下目录结构', reasoning: '需要先了解仓库', uuid: 'u-asst-1' }),
      msg({ role: 'assistant', content: '统计完成：3 处 TODO', uuid: 'u-asst-2' }),
    ])
    const { client } = fakeClient()
    await act(async () => {
      root = createRoot(host)
      root.render(
        <AgentTranscriptView
          sessionId={SID}
          agentId="task-1"
          name="探索子代理"
          active
          onBack={() => {}}
          showToast={() => {}}
          clientRef={{ current: client as never }}
        />,
      )
    })
    await flush(10)

    // 主对话渲染的三条判据（逐条 Message 的旧实现里，每条都会有独立消息头）
    expect(host.querySelectorAll('.msg .author').length).toBeLessThanOrEqual(1)
    expect(host.querySelector('.turn-process-toggle')).not.toBeNull()
    expect(host.textContent).toContain('统计完成：3 处 TODO')
  })

  it('订阅 agent 的 transcript topic，收到的增量立即出现；重复 uuid 不重复渲染', async () => {
    transcriptMock.mockResolvedValue([
      msg({ role: 'user', content: '请统计仓库里的 TODO', uuid: 'u-user' }),
      msg({ role: 'assistant', content: '我先看一下目录结构', uuid: 'u-asst-1' }),
    ])
    const { client, handlers } = fakeClient()
    await act(async () => {
      root = createRoot(host)
      root.render(
        <AgentTranscriptView
          sessionId={SID}
          agentId="task-1"
          name="探索子代理"
          active
          onBack={() => {}}
          showToast={() => {}}
          clientRef={{ current: client as never }}
        />,
      )
    })
    await flush(10)

    // topic 用 transcript 里的真实键（agentId = a+16hex），不是调用方传的 taskId
    const topic = `/topic/sessions/${SID}/subagents/${AGENT_HEX}`
    expect(handlers.has(topic)).toBe(true)

    const push = (messages: AgentTranscriptMessage[]) => {
      const h = handlers.get(topic)!
      act(() => { h({ body: JSON.stringify({ agentId: AGENT_HEX, messages }) }) })
    }
    push([msg({ role: 'assistant', content: '新追加的实时输出', uuid: 'u-live-1' })])
    await flush(10)
    expect(host.textContent).toContain('新追加的实时输出')

    // 同一 uuid 再推一次（订阅后补拉 + 实时增量重叠的真实场景）→ 不得重复
    push([msg({ role: 'assistant', content: '新追加的实时输出', uuid: 'u-live-1' })])
    await flush(10)
    const occurrences = host.textContent!.split('新追加的实时输出').length - 1
    expect(occurrences).toBe(1)
  })
})
