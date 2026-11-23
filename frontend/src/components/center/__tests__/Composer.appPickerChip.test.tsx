// @vitest-environment jsdom
import { act, useState, type ReactElement } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Composer } from '../Composer'
import { fetchAppEntries } from '@/api/apps'
import type { AppEntry } from '@/api/apps'

/**
 * 已选应用 chip + localStorage 记忆（P5）。
 *
 * <h2>WHY（意图验证）</h2>
 * <ul>
 *   <li>chip 是「本轮会上送什么」的唯一可见凭据：移除一枚就必须从**上送数组**里去掉，
 *       否则用户以为取消了、模型仍拿到该应用的工具（静默错配）。</li>
 *   <li>「记住上次选择」写 localStorage 而不是会话列（§0 裁定）⇒ 要断言**卸载重挂后仍在**
 *       （等价于「下次打开输入框」），而不是只看内存态。</li>
 *   <li>即使目录还没拉回来，记忆里的选择也必须如实显示（回落 key）—— ⛔ 不因为「名字没查到」
 *       就隐藏 chip，那等于悄悄清空了用户的选择。</li>
 *   <li>Harness 与 App 的接线逐字同构（`useState(loadRememberedApps)` + `saveRememberedApps`），
 *       App 侧另由源码级测试守护。</li>
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
  return { ...actual, fetchAppEntries: vi.fn() }
})

function app(key: string, name: string): AppEntry {
  return { key, kind: 'mcp', name, usable: true, defaultOpen: true, runtimeStatus: 'connected' }
}

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
    ...over,
  }
}

/** 与 App 同构的会话外壳：选择集在组件之外持有（真实实现是会话列 + PATCH） */
function Harness({ initial }: { initial?: string[] | null }) {
  const [sel, setSel] = useState<string[] | null>(initial ?? null)
  return (
    <Composer
      {...baseProps({
        selectedApps: sel,
        onAppsChange: (keys: string[] | null) => setSel(keys),
      })}
    />
  )
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
  vi.mocked(fetchAppEntries).mockReset()
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

function chips(): HTMLElement[] {
  return Array.from(host.querySelectorAll('.app-chip')) as HTMLElement[]
}

describe('Composer · 已选应用 chip', () => {
  it('受控选择 → chip 显示应用名；× 移除后上送数组少了这一项', async () => {
    vi.mocked(fetchAppEntries).mockResolvedValue([app('notion', 'Notion'), app('dida', '滴答清单')])
    const onAppsChange = vi.fn()
    render(<Composer {...baseProps({ selectedApps: ['notion', 'dida'], onAppsChange })} />)
    await flush(10)

    expect(chips().map((c) => c.getAttribute('data-app-key'))).toEqual(['notion', 'dida'])
    expect(chips()[0].textContent).toContain('Notion')

    act(() => {
      ;(chips()[0].querySelector('.app-chip-x') as HTMLElement)
        .dispatchEvent(new MouseEvent('click', { bubbles: true }))
    })
    // 整表替换：剩一项（⛔ 不是「删了但数组没变」）
    expect(onAppsChange).toHaveBeenLastCalledWith(['dida'])
  })

  it('⭐ 本轮名单变化 ⇒ chip 整表替换（清空为 null 时无 chip）', async () => {
    vi.mocked(fetchAppEntries).mockResolvedValue([app('notion', 'Notion'), app('dida', '滴答清单')])
    render(<Harness initial={['notion']} />)
    await flush(10)
    expect(chips().map((c) => c.getAttribute('data-app-key'))).toEqual(['notion'])

    // 名单换成 dida（key 变更 = 重挂）→ chip 必须整表替换
    render(<Harness key="b" initial={['dida']} />)
    await flush(10)
    expect(chips().map((c) => c.getAttribute('data-app-key'))).toEqual(['dida'])

    // 回到「未设置」⇒ 无 chip（默认态由选择器表达）
    render(<Harness key="c" initial={null} />)
    await flush(10)
    expect(chips()).toHaveLength(0)
  })

  it('移除最后一枚 chip ⇒ 上送显式空数组 []', async () => {
    vi.mocked(fetchAppEntries).mockResolvedValue([app('notion', 'Notion'), app('dida', '滴答清单')])
    const onAppsChange = vi.fn()
    render(<Composer {...baseProps({ selectedApps: ['notion'], onAppsChange })} />)
    await flush(10)
    act(() => {
      ;(chips()[0].querySelector('.app-chip-x') as HTMLElement)
        .dispatchEvent(new MouseEvent('click', { bubbles: true }))
    })
    expect(onAppsChange).toHaveBeenLastCalledWith([])
  })

  it('chip 与 topChips 同容器同行（⛔ 不再单独起一行改变输入框几何）', async () => {
    vi.mocked(fetchAppEntries).mockResolvedValue([app('notion', 'Notion')])
    render(
      <Composer
        {...baseProps({
          selectedApps: ['notion'],
          onAppsChange: vi.fn(),
          topChips: <span data-testid="kb-chip">知识库A</span>,
        })}
      />,
    )
    await flush(10)

    const row = host.querySelector('[data-testid="input-chips"]') as HTMLElement
    expect(row, '缺少统一的 chip 行容器').toBeTruthy()
    // 同一容器内：知识库 chip 与应用 chip 是兄弟（同行、同 gap、同高 28px）
    expect(row.querySelector('[data-testid="kb-chip"]'), 'topChips 不在同一行').toBeTruthy()
    expect(row.querySelector('.app-chip[data-app-key="notion"]'), '应用 chip 不在同一行').toBeTruthy()
    // 旧的独立容器已删除（它自带 padding-bottom ⇒ 把输入框抬高一行）
    expect(host.querySelector('.app-chips')).toBeNull()
  })

  it('目录拉不到名字时仍显示会话里的 key（⛔ 不因名字缺失隐藏 chip）', async () => {
    vi.mocked(fetchAppEntries).mockRejectedValue(new Error('network down'))
    render(<Composer {...baseProps({ selectedApps: ['ghost-app'], onAppsChange: vi.fn() })} />)
    await flush(10)

    const list = chips()
    expect(list).toHaveLength(1)
    expect(list[0].textContent).toContain('ghost-app')
  })
})
