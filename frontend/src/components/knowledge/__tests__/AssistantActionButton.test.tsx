// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { StreamEvent } from '@/api/types'

/**
 * 派单 B7-1：`AssistantActionButton` 行为 + 面板 `initialQuestion` 一次性消费。
 *
 * 环境与写法沿用本仓既有模式（jsdom + `createRoot` + `act`，本仓没有 @testing-library/react）：
 * 见 `__tests__/KbProcessingTimeline.test.tsx`。
 */

// ── 面板依赖全部 mock（⛔ 不发真请求、不连 STOMP）────────────────────────────
const askQa = vi.fn()
const workspaceProject = vi.fn()
const createSession = vi.fn()
/** 捕获面板订阅的流回调，用于模拟 message.complete（结束一轮）。 */
let streamHandler: ((ev: StreamEvent) => void) | null = null
const deactivate = vi.fn()

vi.mock('@/api/knowledge', () => ({
  knowledgeApi: {
    workspaceProject: (...a: unknown[]) => workspaceProject(...a),
    askQa: (...a: unknown[]) => askQa(...a),
  },
}))

vi.mock('@/api/sessions', () => ({
  sessionApi: { create: (...a: unknown[]) => createSession(...a) },
}))

vi.mock('@/api/socket', () => ({
  createSocketClient: () => {
    const client: {
      onConnect?: () => void
      activate: () => void
      deactivate: () => void
    } = {
      activate() {
        // 模拟 STOMP 连上：面板此时才订阅 topic
        client.onConnect?.()
      },
      deactivate,
    }
    return client
  },
  subscribeStream: (_c: unknown, _t: string, cb: (ev: StreamEvent) => void) => {
    streamHandler = cb
    return { unsubscribe: vi.fn() }
  },
}))

vi.mock('@/markdown/vendor/renderer', () => ({
  MarkdownTextV2: ({ text }: { text: string }) => <span data-md>{text}</span>,
}))

import { AssistantActionButton, resolveAssistantContext } from '../AssistantActionButton'
import { KbAssistantPanel } from '../KbAssistantPanel'

describe('resolveAssistantContext · 选区归一化', () => {
  it('没有选区 ⇒ undefined', () => {
    expect(resolveAssistantContext(undefined)).toBeUndefined()
    expect(resolveAssistantContext(null)).toBeUndefined()
  })

  it('text 为空串 / 纯空白 ⇒ undefined（等价于「没有上下文」）', () => {
    expect(resolveAssistantContext({ field: 'body', text: '' })).toBeUndefined()
    expect(resolveAssistantContext({ field: 'body', text: '   ' })).toBeUndefined()
    expect(resolveAssistantContext({ field: 'body', text: '\n\t ' })).toBeUndefined()
  })

  it('有值 ⇒ trim 后返回', () => {
    expect(resolveAssistantContext({ field: 'body', text: '  这段诊断有异常  ' })).toBe('这段诊断有异常')
  })
})

describe('AssistantActionButton · DOM 与点击', () => {
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
    vi.clearAllMocks()
  })

  function render(props: Parameters<typeof AssistantActionButton>[0]) {
    act(() => root.render(<AssistantActionButton {...props} />))
  }

  it('类名与抽屉同源；iconOnly 时只出星形图标、不出 label', () => {
    render({ label: '问问助手', query: 'q', iconOnly: true, onOpenAssistant: () => {} })
    const btn = container.querySelector('button')!
    expect(btn.className).toBe('assistant-button is-icon-only')
    expect(btn.getAttribute('data-assistant-ignore')).toBe('')
    expect(btn.getAttribute('aria-label')).toBe('问问助手')
    expect(btn.getAttribute('title')).toBe('问问助手')
    expect(btn.querySelector('span.resource-icon.assistant-resource-icon')).not.toBeNull()
    expect(btn.querySelector('.assistant-button__label')).toBeNull()
  })

  it('非 iconOnly 时出 label，且 title 不给（源站是 title={iconOnly ? label : undefined}）', () => {
    render({ label: '问问助手', query: 'q', onOpenAssistant: () => {} })
    const btn = container.querySelector('button')!
    expect(btn.className).toBe('assistant-button')
    expect(btn.getAttribute('title')).toBeNull()
    expect(btn.getAttribute('aria-label')).toBe('问问助手')
    expect(btn.querySelector('.assistant-button__label')?.textContent).toBe('问问助手')
  })

  it('点击传出 query + trim 后的选区文本', () => {
    const onOpenAssistant = vi.fn()
    render({
      label: '问问助手',
      query: '诊断这页',
      selection: { field: 'body', text: '  正文片段  ' },
      onOpenAssistant,
    })
    act(() => container.querySelector('button')!.click())
    expect(onOpenAssistant).toHaveBeenCalledWith('诊断这页', '正文片段')
  })

  it('选区为空 ⇒ 第二个参数是 undefined（不是空串）', () => {
    const onOpenAssistant = vi.fn()
    render({
      label: '问问助手',
      query: '诊断这页',
      selection: { field: 'body', text: '   ' },
      onOpenAssistant,
    })
    act(() => container.querySelector('button')!.click())
    expect(onOpenAssistant).toHaveBeenCalledWith('诊断这页', undefined)
  })

  it('disabled 时按钮不可用', () => {
    render({ label: '问问助手', query: 'q', disabled: true, onOpenAssistant: () => {} })
    expect((container.querySelector('button') as HTMLButtonElement).disabled).toBe(true)
  })
})

describe('KbAssistantPanel · initialQuestion 一次性消费', () => {
  let container: HTMLDivElement
  let root: Root

  beforeEach(() => {
    ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    streamHandler = null
    askQa.mockResolvedValue(undefined)
    workspaceProject.mockResolvedValue({ id: 'kb-project' })
    createSession.mockResolvedValue({ id: 's1' })
    Element.prototype.scrollIntoView = () => {}
  })

  afterEach(() => {
    act(() => root.unmount())
    container.remove()
    vi.clearAllMocks()
  })

  async function flush(): Promise<void> {
    for (let i = 0; i < 4; i++) {
      await act(async () => {
        await new Promise((resolve) => setTimeout(resolve, 0))
      })
    }
  }

  const baseProps = {
    baseId: 'b1',
    docTitle: '某文档',
    knowledgeId: 'k1',
    onClose: () => {},
    initialQuestion: '诊断这页',
  }

  it('挂载后自动问一次，问题即 initialQuestion', async () => {
    await act(async () => root.render(<KbAssistantPanel {...baseProps} showToast={() => {}} />))
    await flush()
    expect(askQa).toHaveBeenCalledTimes(1)
    expect(askQa.mock.calls[0][1]).toMatchObject({ content: '诊断这页', knowledgeBaseIds: ['b1'] })
  })

  it('空白 initialQuestion 不发送', async () => {
    await act(async () =>
      root.render(<KbAssistantPanel {...baseProps} initialQuestion="   " showToast={() => {}} />))
    await flush()
    expect(askQa).not.toHaveBeenCalled()
  })

  it('⭐ 一轮结束后 effect 重跑（ask 换身份）也不会重发同一个问题', async () => {
    await act(async () => root.render(<KbAssistantPanel {...baseProps} showToast={() => {}} />))
    await flush()
    expect(askQa).toHaveBeenCalledTimes(1)

    // 结束当前轮（activeTurnRef 清空 ⇒ in-flight 闸门失效）
    act(() => streamHandler?.({ type: 'message.complete', content: '完成' } as StreamEvent))
    await flush()

    // 父组件重渲染，showToast 换身份 ⇒ ask 换身份 ⇒ effect 依赖变化
    await act(async () => root.render(<KbAssistantPanel {...baseProps} showToast={() => {}} />))
    await flush()

    expect(askQa).toHaveBeenCalledTimes(1)
  })
})
