// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Composer } from '../Composer'

/**
 * 输入法组合态（Mac 中文输入法）Enter 守卫。
 *
 * <h2>WHY</h2>
 * 拼音候选里按 Enter 是「确认候选」，⛔ 不能当发送：否则用户选词即发出半句话（Mac 实测）。
 * `isComposing` / keyCode 229 是标准信号；Safari/WKWebView 的 compositionend 可能**早于**
 * Enter 的 keydown ⇒ 再加一个短时间窗兜底（否则 Mac 上仍会偶发误发）。
 */

vi.mock('@tauri-apps/api/core', () => ({ isTauri: () => true, invoke: vi.fn(async () => undefined) }))
vi.mock('@tauri-apps/api/webview', () => ({
  getCurrentWebview: () => ({ onDragDropEvent: () => Promise.resolve(() => {}) }),
}))
vi.mock('@tauri-apps/plugin-fs', () => ({ stat: vi.fn(), readFile: vi.fn(), writeTextFile: vi.fn(async () => undefined) }))
vi.mock('@/api/chat', () => ({ uploadAttachment: vi.fn() }))
vi.mock('@/api/apps', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/apps')>()
  return { ...actual, fetchAppEntries: vi.fn(async () => []) }
})

function baseProps(over: Record<string, unknown> = {}) {
  return {
    composerText: 'ni hao',
    setComposerText: () => {},
    sendMessage: () => {},
    showToast: () => {},
    streaming: false,
    onStop: () => {},
    queuedCommands: [],
    popEditable: async () => null,
    boundProjectName: null,
    onSelectProject: () => {},
    currentModel: 'ds-openai/deepseek-v4-flash',
    permissionMode: 'default' as const,
    empty: false,
    sessionId: 'sess-1',
    localRead: false,
    ...over,
  }
}

/** 造一个可指定 isComposing 的 keydown（jsdom 的 KeyboardEventInit 不一定认 isComposing） */
function keydown(key: string, opts: { isComposing?: boolean; shiftKey?: boolean } = {}): KeyboardEvent {
  const ev = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, shiftKey: !!opts.shiftKey })
  Object.defineProperty(ev, 'isComposing', { value: !!opts.isComposing })
  return ev
}

async function flush(ms = 0): Promise<void> {
  await act(async () => { await new Promise((r) => setTimeout(r, ms)) })
}

let host: HTMLDivElement
let root: Root | null = null

beforeEach(() => {
  ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  host = document.createElement('div')
  document.body.appendChild(host)
  root = createRoot(host)
})

afterEach(() => {
  act(() => { root?.unmount() })
  root = null
  host.remove()
})

async function renderComposer(sendMessage: (attachments?: unknown[]) => void) {
  act(() => {
    root!.render(<Composer {...baseProps({ sendMessage })} />)
  })
  await flush(0)
  return host.querySelector('textarea') as HTMLTextAreaElement
}

describe('Composer · IME Enter 守卫', () => {
  it('⭐ isComposing=true 的 Enter 不发送（只确认候选）', async () => {
    const sendMessage = vi.fn()
    const ta = await renderComposer(sendMessage)

    act(() => { ta.dispatchEvent(keydown('Enter', { isComposing: true })) })
    expect(sendMessage).not.toHaveBeenCalled()
  })

  it('⭐ composition 组合期间 Enter 不发送；结束后（超过短窗口）Enter 正常发送', async () => {
    const sendMessage = vi.fn()
    const ta = await renderComposer(sendMessage)

    act(() => { ta.dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true })) })
    act(() => { ta.dispatchEvent(keydown('Enter')) })
    expect(sendMessage, '组合期间不应发送').not.toHaveBeenCalled()

    act(() => { ta.dispatchEvent(new CompositionEvent('compositionend', { bubbles: true })) })
    // 结束后立刻的 Enter 落在短窗口内（Safari 的 compositionend 早于 keydown）⇒ 仍吞掉
    act(() => { ta.dispatchEvent(keydown('Enter')) })
    expect(sendMessage, 'compositionend 后的瞬时 Enter 不应发送').not.toHaveBeenCalled()

    // 窗口过去后正常发送
    await flush(80)
    act(() => { ta.dispatchEvent(keydown('Enter')) })
    expect(sendMessage).toHaveBeenCalled()
  })

  it('Shift+Enter 换行（不发送）不变', async () => {
    const sendMessage = vi.fn()
    const ta = await renderComposer(sendMessage)

    act(() => { ta.dispatchEvent(keydown('Enter', { shiftKey: true })) })
    expect(sendMessage).not.toHaveBeenCalled()
  })
})
