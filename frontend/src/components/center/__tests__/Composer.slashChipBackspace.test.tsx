// @vitest-environment jsdom
import { act, useState } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Composer } from '../Composer'

/**
 * [2026-10-29 用户裁定] 召唤/选择的技能 chip 是**真实文本前缀**（`/命令 ` 或「中文别名 + 尾随空格」），
 * 所以退格默认会先吃掉尾随空格（chip 高亮失配 = 视觉上「先掉阴影」）再逐字删名称。
 *
 * <b>WHY 必须真渲染</b>：判定用的是 textarea 的真实 `selectionStart/End` 与受控 `composerText`，
 * 纯函数测不到「拦截后是否真的没走浏览器默认删除」。
 */

const tauriMock = vi.hoisted(() => ({ isTauri: () => false }))
vi.mock('@tauri-apps/api/core', () => ({ isTauri: tauriMock.isTauri, invoke: vi.fn(async () => undefined) }))
vi.mock('@tauri-apps/api/webview', () => ({ getCurrentWebview: () => ({ onDragDropEvent: () => Promise.resolve(() => {}) }) }))
vi.mock('@tauri-apps/plugin-fs', () => ({ stat: vi.fn(), readFile: vi.fn(), writeTextFile: vi.fn() }))
vi.mock('@/api/chat', () => ({ uploadAttachment: vi.fn(), chatApi: {} }))

interface Harness {
  container: HTMLDivElement
  root: Root
  textarea: () => HTMLTextAreaElement
  /** 当前受控文本（Host 持有 state，断言它才知道「真的整块删了」） */
  text: () => string
  unmount: () => void
}

const CHIP = '/review   '
const TAIL = '这段是正文'

function mountHost(initial: string): Harness {
  ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  const container = document.createElement('div')
  document.body.appendChild(container)
  const root = createRoot(container)
  let latest = initial

  function Host() {
    const [value, setValue] = useState(initial)
    latest = value
    return (
      <Composer
        composerText={value}
        setComposerText={(v: string | ((prev: string) => string)) => {
          setValue((prev) => (typeof v === 'function' ? v(prev) : v))
        }}
        sendMessage={() => {}}
        showToast={() => {}}
        streaming={false}
        onStop={() => {}}
        queuedCommands={[]}
        popEditable={async () => null}
        boundProjectName={null}
        onSelectProject={() => {}}
        currentModel="ds-openai/deepseek-v4-flash"
        permissionMode="default"
        empty={false}
        sessionId="sess-chip"
        localRead={false}
      />
    )
  }
  act(() => { root.render(<Host />) })

  return {
    container,
    root,
    textarea: () => container.querySelector('textarea') as HTMLTextAreaElement,
    text: () => latest,
    unmount: () => act(() => root.unmount()),
  }
}

function press(h: Harness, key: 'Backspace' | 'Delete', pos: number): void {
  const ta = h.textarea()
  ta.setSelectionRange(pos, pos)
  act(() => {
    ta.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }))
  })
}

describe('[技能 chip] 退格 / Delete 整块删除', () => {
  let h: Harness

  afterEach(() => {
    h?.unmount()
    h?.container.remove()
  })

  it('光标紧贴 chip 末尾（chip + 尾随空格之后）→ Backspace 一次删掉整块，保留正文', () => {
    h = mountHost(CHIP + TAIL)
    press(h, 'Backspace', CHIP.length)
    expect(h.text()).toBe(TAIL)
  })

  it('光标落在 chip 中间 → 同样整块删除（不会只删半个名字）', () => {
    h = mountHost(CHIP + TAIL)
    press(h, 'Backspace', 3)
    expect(h.text()).toBe(TAIL)
  })

  it('光标在开头按 Delete → 整块删除', () => {
    h = mountHost(CHIP + TAIL)
    press(h, 'Delete', 0)
    expect(h.text()).toBe(TAIL)
  })

  it('光标已在正文里 → 不拦截（保持浏览器默认逐字删除行为）', () => {
    h = mountHost(CHIP + TAIL)
    const before = h.text()
    press(h, 'Backspace', CHIP.length + 2)
    expect(h.text()).toBe(before)
  })

  it('没有 chip 时不拦截', () => {
    h = mountHost('普通文本')
    press(h, 'Backspace', 2)
    expect(h.text()).toBe('普通文本')
  })
})
