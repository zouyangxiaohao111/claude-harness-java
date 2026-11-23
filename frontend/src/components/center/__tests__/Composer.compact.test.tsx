// @vitest-environment jsdom
import { act, type ReactElement } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Composer } from '../Composer'

/**
 * 输入框 compact 模式（右栏抽屉展开 ⇒ 主区变窄，只留图标）。
 *
 * <h2>WHY（意图验证）</h2>
 * <ul>
 *   <li>右栏展开时输入框工具区若还带文字，会把工具栏挤到换行/溢出；用户要求「icon-only」，
 *       但 ⛔ 不能靠「窄到某个像素」的容器查询来猜测 —— 宽屏开抽屉时容器未必 <700px，
 *       所以必须由**布局状态**驱动（`.composer.compact`，App 传 rightDrawerOpen）。</li>
 *   <li>缩的是文字，不是信息：文字节点仍在 DOM 里、title/aria 仍在 ⇒ 悬浮/读屏还能读到；
 *       只看 class 不够，故同时钉住「文字节点未删除 + title 存在」。</li>
 *   <li>chip 不能被 compact 波及（它是「本轮带什么」的凭据，收起来就看不见了）。</li>
 * </ul>
 */

vi.mock('@tauri-apps/api/core', () => ({ isTauri: () => true, invoke: vi.fn(async () => undefined) }))
vi.mock('@tauri-apps/api/webview', () => ({
  getCurrentWebview: () => ({ onDragDropEvent: () => Promise.resolve(() => {}) }),
}))
vi.mock('@tauri-apps/plugin-fs', () => ({ stat: vi.fn(), readFile: vi.fn(), writeTextFile: vi.fn(async () => undefined) }))
vi.mock('@/api/chat', () => ({ uploadAttachment: vi.fn() }))
vi.mock('@/api/apps', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/apps')>()
  return { ...actual, fetchAppOptions: vi.fn(async () => []) }
})

function baseProps(over: Record<string, unknown> = {}) {
  return {
    composerText: '',
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
    onCoordinatorModeChange: () => {},
    ...over,
  }
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
  localStorage.clear()
})

afterEach(() => {
  act(() => { root?.unmount() })
  root = null
  host.remove()
})

function render(el: ReactElement) {
  act(() => {
    if (!root) root = createRoot(host)
    root.render(el)
  })
}

describe('Composer · compact 模式', () => {
  it('右栏展开（compact）⇒ 根节点带 compact 类；文字节点仍在 + title 仍在（只收字不丢信息）', async () => {
    render(<Composer {...baseProps({ compact: true })} />)
    await flush(0)

    const rootEl = host.querySelector('.composer') as HTMLElement
    expect(rootEl.classList.contains('compact')).toBe(true)

    const coordinator = host.querySelector('.tool-chip.coordinator-chip') as HTMLElement
    expect(coordinator, '找不到协调者 chip').toBeTruthy()
    // 文字在 DOM 里（CSS 才负责 display:none）⇒ 悬浮/读屏仍可读
    expect(coordinator.querySelector('span')!.textContent).toBe('协调者')
    expect(coordinator.getAttribute('title')).toContain('协调者模式')
    // 图标容器存在（icon-only 后仍有东西可看）
    expect(coordinator.querySelector('svg')).toBeTruthy()
  })

  it('移除 compact（右栏收起）⇒ 类消失，恢复原样', async () => {
    render(<Composer {...baseProps({ compact: true })} />)
    await flush(0)
    expect((host.querySelector('.composer') as HTMLElement).classList.contains('compact')).toBe(true)

    render(<Composer {...baseProps({ compact: false })} />)
    await flush(0)
    expect((host.querySelector('.composer') as HTMLElement).classList.contains('compact')).toBe(false)
  })

  it('compact 不影响已选应用 chip（chip 仍显示）', async () => {
    render(<Composer {...baseProps({ compact: true, selectedApps: ['notion'], onAppsChange: () => {} })} />)
    await flush(10)
    const chips = host.querySelectorAll('[data-testid="input-chips"] .app-chip')
    expect(chips.length).toBe(1)
    expect((chips[0] as HTMLElement).textContent).toContain('notion')
  })
})
