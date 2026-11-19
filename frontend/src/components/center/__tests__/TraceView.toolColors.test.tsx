// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { TraceView } from '../TraceView'
import type { ChatMessageDto } from '@/api/types'

/**
 * 轨迹视图：不同工具必须带各自的类型类（tool-bash/edit/glob/read/task/other），
 * 由 CSS 按类着色。此前 `toolClass` 算了但没挂到 DOM ⇒ 全灰。
 */
const SID = 'sess-trace-colors'

const msg = (id: string, role: 'user' | 'assistant', extra: Partial<ChatMessageDto> = {}): ChatMessageDto => ({
  id, sessionId: SID, role, author: role === 'user' ? '你' : 'nexus', content: role === 'user' ? '跑一下' : '好的',
  reasoning: null, toolCalls: null, finishReason: null, inputTokens: null, outputTokens: null,
  reasoningDurationMs: null, time: null, createdAt: undefined, toolCallId: null, assistantMessageId: null,
  userMessageId: 'u1', subtype: null, isMeta: false, isApiErrorMessage: false, apiError: null,
  error: null, errorDetails: null, matchedRule: null, ...extra,
})

let container: HTMLDivElement
let root: Root

beforeEach(() => {
  ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
})

afterEach(() => {
  act(() => root.unmount())
  container.remove()
})

function render(messages: ChatMessageDto[]) {
  act(() => { root.render(<TraceView messages={messages} />) })
}

describe('轨迹工具类型类', () => {
  it('Bash/Edit/Grep/Read/Task/WebFetch 各自带 tool-* 类', () => {
    const names = ['Bash', 'Edit', 'Grep', 'Read', 'Task', 'WebFetch']
    render([
      msg('u1', 'user'),
      msg('m1', 'assistant', {
        toolCalls: names.map((n, i) => ({ id: `t${i}`, name: n, arguments: '{}', result: 'ok', isError: false, durationMs: 12 })),
      }),
    ])
    const expected: Record<string, string> = {
      Bash: 'tool-bash', Edit: 'tool-edit', Grep: 'tool-glob', Read: 'tool-read', Task: 'tool-task', WebFetch: 'tool-other',
    }
    for (const [name, cls] of Object.entries(expected)) {
      const el = container.querySelector(`.kp-name-text.${cls}`)
      expect(el, `${name} 应带 ${cls}`).toBeTruthy()
      expect(el!.textContent).toContain(name)
    }
  })
})
