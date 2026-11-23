// @vitest-environment jsdom
import { act, useState, type ReactElement } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Composer } from '../Composer'
import { fetchAppEntries, startApp } from '@/api/apps'
import type { AppEntry } from '@/api/apps'

/**
 * 输入框工具区「应用多选」面板（P5）。
 *
 * <h2>WHY（意图验证）</h2>
 * <ul>
 *   <li>「应用」= 本轮随消息携带的 `selectedApps`（不是会话级禁用表）。所以断言落在
 *       **回调收到的完整数组**与**localStorage 记忆**上，⛔ 不看视觉 only。</li>
 *   <li>没有连接器时不许做假按钮：目录已确认空 ⇒ 入口置灰，点了只给一句可行动的说明
 *       （「先去 + → 连接器接入」），⛔ 不开一个空面板装样子。</li>
 *   <li>不可用的行（未连接/需授权）仍要显示并给原因 —— 入口常驻、用状态表达。</li>
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
  return { ...actual, fetchAppEntries: vi.fn(), startApp: vi.fn() }
})

function app(key: string, name: string, usable = true): AppEntry {
  return usable
    ? { key, kind: 'mcp', name, usable: true, defaultOpen: true, runtimeStatus: 'connected' }
    : { key, kind: 'mcp', name, usable: false, defaultOpen: false, unusableReason: '未连接（启动后可用）', runtimeStatus: 'stopped' }
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
  vi.mocked(startApp).mockReset()
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

function trigger(): HTMLElement {
  const el = host.querySelector('[data-testid="app-picker-trigger"]') as HTMLElement | null
  expect(el, '找不到应用选择入口').toBeTruthy()
  return el!
}

function rows(): HTMLElement[] {
  return Array.from(host.querySelectorAll('.app-picker-row')) as HTMLElement[]
}

/** 与 App 同构的受控壳：选中集在被测组件之外持有（Composer ⛔ 不存第二份） */
function Harness({ onEnabledAppsChange }: { onEnabledAppsChange?: (keys: string[] | null) => void }) {
  const [sel, setSel] = useState<string[] | null>(null)
  return (
    <Composer
      {...baseProps({
        enabledApps: sel,
        onEnabledAppsChange: (keys: string[] | null) => { setSel(keys); onEnabledAppsChange?.(keys) },
      })}
    />
  )
}

describe('Composer · 应用多选面板', () => {
  it('无连接器（目录为空）→ 入口置灰，点击只给说明，不开空面板', async () => {
    vi.mocked(fetchAppEntries).mockResolvedValue([])
    const showToast = vi.fn()
    const onEnabledAppsChange = vi.fn()
    render(<Composer {...baseProps({ showToast, onEnabledAppsChange })} />)

    // 首次打开：面板会真的拉一次目录（否则无法区分「没有」和「没拉」）
    act(() => { trigger().dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    await flush(10)
    expect(host.querySelector('[data-testid="app-picker-panel"]')).not.toBeNull()
    expect(host.textContent).toContain('还没有可用的应用')
    await flush(10)

    // 目录确认空 ⇒ 入口置灰
    expect(trigger().classList.contains('disabled')).toBe(true)
    expect(trigger().getAttribute('aria-disabled')).toBe('true')

    // 关掉面板（入口永远能关自己的面板）
    act(() => { trigger().dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    await flush(0)
    expect(host.querySelector('[data-testid="app-picker-panel"]')).toBeNull()

    // 再点：只给可行动的说明，⛔ 不再弹空面板
    act(() => { trigger().dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    expect(host.querySelector('[data-testid="app-picker-panel"]')).toBeNull()
    expect(showToast).toHaveBeenCalledWith(
      '还没有可用的应用：请先在「+ → 连接器」里接入一个连接器',
      'info',
    )
    expect(onEnabledAppsChange).not.toHaveBeenCalled()
  })

  it('⭐ 初始：启动集按 defaultOpen（开关 ON），强调集为空（行不选中）', async () => {
    vi.mocked(fetchAppEntries).mockResolvedValue([app('codegraph', 'CodeGraph'), app('dida', '滴答清单')])
    render(<Harness />)

    act(() => { trigger().dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    await flush(10)

    for (const r of rows()) {
      // 开关（启动）默认 ON；行（强调）默认不选中 —— 两个动作状态互不代表
      expect(r.querySelector('.app-picker-sw')!.getAttribute('aria-checked')).toBe('true')
      expect(r.getAttribute('aria-checked')).toBe('false')
    }
  })

  it('⭐ 独立开关 = 启动/停止：关一个 ⇒ 发显式启动集；恢复 ⇒ 回到 null', async () => {
    vi.mocked(fetchAppEntries).mockResolvedValue([app('notion', 'Notion'), app('dida', '滴答清单')])
    const onEnabledAppsChange = vi.fn()
    render(<Harness onEnabledAppsChange={onEnabledAppsChange} />)

    act(() => { trigger().dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    await flush(10)

    const sw = (k: string) => rows().find((r) => r.getAttribute('data-app-key') === k)!
      .querySelector('.app-picker-sw') as HTMLElement

    act(() => { sw('notion').dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    expect(onEnabledAppsChange).toHaveBeenLastCalledWith(['dida'])
    act(() => { sw('notion').dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    expect(onEnabledAppsChange).toHaveBeenLastCalledWith(null)
  })

  it('⭐ 从 null 直接关掉全部开关 ⇒ 显式空数组 []', async () => {
    vi.mocked(fetchAppEntries).mockResolvedValue([app('notion', 'Notion'), app('dida', '滴答清单')])
    const onEnabledAppsChange = vi.fn()
    render(<Harness onEnabledAppsChange={onEnabledAppsChange} />)

    act(() => { trigger().dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    await flush(10)
    act(() => { (rows()[0].querySelector('.app-picker-sw') as HTMLElement).click() })
    act(() => { (rows()[1].querySelector('.app-picker-sw') as HTMLElement).click() })
    expect(onEnabledAppsChange).toHaveBeenLastCalledWith([])
  })

  it('⭐ 启动自动加入强调；停止从强调移除（开关与行点击分离）', async () => {
    // b 默认关闭（defaultOpen:false）⇒ 启动它属于「偏离默认」，落显式名单，便于断言
    vi.mocked(fetchAppEntries).mockResolvedValue([
      app('a', 'A'),
      { key: 'b', kind: 'mcp', name: 'B', usable: true, defaultOpen: false },
    ])
    const onAppsChange = vi.fn()
    const onEnabledAppsChange = vi.fn()
    render(<Composer {...baseProps({
      enabledApps: ['a'], onEnabledAppsChange, selectedApps: ['a'], onAppsChange,
    })} />)

    act(() => { trigger().dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    await flush(10)
    const sw = (k: string) => rows().find((r) => r.getAttribute('data-app-key') === k)!
      .querySelector('.app-picker-sw') as HTMLElement

    // 启动 b ⇒ 启动集 + 强调集都加 b
    act(() => { sw('b').dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    expect(onEnabledAppsChange).toHaveBeenLastCalledWith(['a', 'b'])
    expect(onAppsChange).toHaveBeenLastCalledWith(['a', 'b'])

    // 停止 a ⇒ 启动集移除 a（受控 props 不回写，基线仍是 ['a']）；强调集同步移除 a
    act(() => { sw('a').dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    expect(onEnabledAppsChange).toHaveBeenLastCalledWith([])
    expect(onAppsChange).toHaveBeenLastCalledWith([])
  })

  it('⭐ 点行 = 选择/取消强调：只调 onAppsChange，⛔ 不动启动集', async () => {
    vi.mocked(fetchAppEntries).mockResolvedValue([app('notion', 'Notion'), app('dida', '滴答清单')])
    const onAppsChange = vi.fn()
    const onEnabledAppsChange = vi.fn()
    render(<Composer {...baseProps({
      enabledApps: null, onEnabledAppsChange, selectedApps: null, onAppsChange,
    })} />)

    act(() => { trigger().dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    await flush(10)

    act(() => { rows()[0].dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    expect(onAppsChange).toHaveBeenLastCalledWith(['notion'])
    expect(onEnabledAppsChange).not.toHaveBeenCalled()

    // 「取消」方向：已强调的行再点 ⇒ 从强调集合移除
    act(() => {
      root!.render(<Composer {...baseProps({
        enabledApps: null, onEnabledAppsChange, selectedApps: ['notion'], onAppsChange,
      })} />)
    })
    await flush(0)
    // 面板保持打开（同一组件实例，仅 props 变化）
    act(() => { rows()[0].dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    expect(onAppsChange).toHaveBeenLastCalledWith([])
  })

  it('显式启动集里再开一个 ⇒ 加入名单；已开的再点 ⇒ 移出名单', async () => {
    vi.mocked(fetchAppEntries).mockResolvedValue([app('a', 'A'), app('b', 'B'), app('c', 'C')])
    const onEnabledAppsChange = vi.fn()
    render(<Composer {...baseProps({ enabledApps: ['a'], onEnabledAppsChange })} />)

    act(() => { trigger().dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    await flush(10)
    const sw = (i: number) => rows()[i].querySelector('.app-picker-sw') as HTMLElement
    act(() => { sw(2).click() })
    expect(onEnabledAppsChange).toHaveBeenLastCalledWith(['a', 'c'])
    act(() => { sw(0).click() })
    expect(onEnabledAppsChange).toHaveBeenLastCalledWith([])
  })

  it('不可用的行：行不选中、开关不可点（保留灰色与原因）', async () => {
    vi.mocked(fetchAppEntries).mockResolvedValue([app('off', '停掉的应用', false), app('on', '在线应用')])
    const onEnabledAppsChange = vi.fn()
    render(<Composer {...baseProps({ enabledApps: null, onEnabledAppsChange })} />)

    act(() => { trigger().dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    await flush(10)

    const off = rows().find((r) => r.getAttribute('data-app-key') === 'off') as HTMLElement
    expect(off.getAttribute('aria-checked')).toBe('false')
    expect(off.getAttribute('aria-disabled')).toBe('true')
    const offSw = off.querySelector('.app-picker-sw') as HTMLButtonElement
    expect(offSw.disabled).toBe(true)
    // 可用行的开关照常 ON（defaultOpen）
    const on = rows().find((r) => r.getAttribute('data-app-key') === 'on') as HTMLElement
    expect(on.querySelector('.app-picker-sw')!.getAttribute('aria-checked')).toBe('true')
  })

  it('不可用的行仍显示（入口常驻），但行点击与开关都无效', async () => {
    vi.mocked(fetchAppEntries).mockResolvedValue([app('off', '停掉的应用', false), app('on', '在线应用')])
    const onEnabledAppsChange = vi.fn()
    const onAppsChange = vi.fn()
    render(<Composer {...baseProps({ onEnabledAppsChange, onAppsChange })} />)

    act(() => { trigger().dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    await flush(10)

    const off = rows().find((r) => r.getAttribute('data-app-key') === 'off') as HTMLElement
    expect(off, '不可用的应用被隐藏了（应置灰保留）').toBeTruthy()
    expect(off.getAttribute('aria-disabled')).toBe('true')
    expect(off.textContent).toContain('未连接（启动后可用）')

    act(() => { off.dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    expect(onAppsChange).not.toHaveBeenCalled()
    expect(onEnabledAppsChange).not.toHaveBeenCalled()
  })

  it('搜索框按名字/描述过滤', async () => {
    vi.mocked(fetchAppEntries).mockResolvedValue([app('notion', 'Notion'), app('dida', '滴答清单')])
    render(<Composer {...baseProps({ onEnabledAppsChange: vi.fn() })} />)

    act(() => { trigger().dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    await flush(10)

    const input = host.querySelector('.app-picker-search-input') as HTMLInputElement
    expect(input).toBeTruthy()
    act(() => {
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!
      setter.call(input, '滴答')
      input.dispatchEvent(new Event('input', { bubbles: true }))
    })
    await flush(0)
    expect(rows().map((r) => r.getAttribute('data-app-key'))).toEqual(['dida'])
  })

  it('⭐ 每行可展开查看工具原始名（不可用行也能展开）', async () => {
    vi.mocked(fetchAppEntries).mockResolvedValue([
      {
        key: 'codegraph', kind: 'mcp', name: 'CodeGraph', usable: true, defaultOpen: true, toolCount: 2,
        tools: ['mcp__codegraph__explore', 'mcp__codegraph__node'],
      },
      { key: 'off-mcp', kind: 'mcp', name: '离线服务', usable: false, defaultOpen: false, unusableReason: '未连接' },
    ])
    render(<Composer {...baseProps({ enabledApps: null, onEnabledAppsChange: vi.fn() })} />)

    act(() => { trigger().dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    await flush(10)

    // 初始不展开
    expect(host.querySelector('[data-testid="app-tool-list"]')).toBeNull()

    const row = (k: string) => host.querySelector(`.app-picker-row[data-app-key="${k}"]`) as HTMLElement
    const expandBtn = row('codegraph').querySelector('.app-picker-expand') as HTMLButtonElement
    expect(expandBtn, '缺少展开按钮').toBeTruthy()
    await act(async () => { expandBtn.click() })
    await flush(0)

    const list = host.querySelector('[data-testid="app-tool-list"]') as HTMLElement
    expect(list).toBeTruthy()
    expect(list.getAttribute('data-app-key')).toBe('codegraph')
    expect(list.textContent).toContain('mcp__codegraph__explore')
    expect(list.textContent).toContain('mcp__codegraph__node')
    // 不可用行：没有 tools 清单 ⇒ 没有展开按钮（不造假空列表）
    expect(row('off-mcp').querySelector('.app-picker-expand')).toBeNull()

    // 再点收起
    await act(async () => { (row('codegraph').querySelector('.app-picker-expand') as HTMLElement).click() })
    await flush(0)
    expect(host.querySelector('[data-testid="app-tool-list"]')).toBeNull()
  })

  it('⭐ 浏览器 / 电脑操作 不出现在应用面板（已回工具面板）', async () => {
    vi.mocked(fetchAppEntries).mockResolvedValue([
      { key: 'nexusai-in-chrome', kind: 'browser', name: '浏览器', toolCount: 12, usable: true, defaultOpen: true },
      { key: 'computer-use', kind: 'computer-use', name: '桌面', toolCount: 5, usable: false, defaultOpen: false, unusableReason: '功能未开启' },
      app('notion', 'Notion'),
    ])
    render(<Composer {...baseProps({ enabledApps: null, onEnabledAppsChange: vi.fn() })} />)

    act(() => { trigger().dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    await flush(10)

    expect(rows().map((r) => r.getAttribute('data-app-key'))).toEqual(['notion'])
    expect(host.querySelector('.app-picker-row[data-app-key="nexusai-in-chrome"]')).toBeNull()
    expect(host.querySelector('.app-picker-row[data-app-key="computer-use"]')).toBeNull()
  })

  it('⭐ 默认启动集按 defaultOpen：开着的默认启动、关着的默认停止；启动默认关闭项 ⇒ 落显式名单', async () => {
    vi.mocked(fetchAppEntries).mockResolvedValue([
      { key: 'mcp-a', kind: 'mcp', name: 'MCP A', usable: true, defaultOpen: true },
      { key: 'mcp-b', kind: 'mcp', name: 'MCP B', usable: true, defaultOpen: true },
      { key: 'mcp-c', kind: 'mcp', name: 'MCP C', usable: true, defaultOpen: false },
    ])
    const onEnabledAppsChange = vi.fn()
    render(<Harness onEnabledAppsChange={onEnabledAppsChange} />)

    act(() => { trigger().dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    await flush(10)

    const sw = (k: string) => host.querySelector(`.app-picker-row[data-app-key="${k}"] .app-picker-sw`) as HTMLElement
    // 默认态：两个 defaultOpen 的开关 ON，C 开关 OFF
    expect(sw('mcp-a').getAttribute('aria-checked')).toBe('true')
    expect(sw('mcp-b').getAttribute('aria-checked')).toBe('true')
    expect(sw('mcp-c').getAttribute('aria-checked')).toBe('false')

    // 启动 C（默认关闭）⇒ 偏离默认 ⇒ 显式名单（不是 null）
    act(() => { sw('mcp-c').click() })
    expect(onEnabledAppsChange).toHaveBeenLastCalledWith(['mcp-a', 'mcp-b', 'mcp-c'])

    // 停止 B ⇒ 显式名单去掉它
    act(() => { sw('mcp-b').click() })
    expect(onEnabledAppsChange).toHaveBeenLastCalledWith(['mcp-a', 'mcp-c'])

    // 再启动 B ⇒ 集合仍含 C ⇒ 继续显式；停掉 C 才收回默认集 ⇒ null
    act(() => { sw('mcp-b').click() })
    expect(onEnabledAppsChange).toHaveBeenLastCalledWith(['mcp-a', 'mcp-c', 'mcp-b'])
    act(() => { sw('mcp-c').click() })
    expect(onEnabledAppsChange).toHaveBeenLastCalledWith(null)
  })

  it('行图标：有 icon 渲染 img；加载失败回落首字母；无 icon 的 MCP 用首字母', async () => {
    vi.mocked(fetchAppEntries).mockResolvedValue([
      { key: 'with-icon', kind: 'mcp', name: 'CodeGraph', usable: true, defaultOpen: true, icon: '/app-icons/x.svg' },
      { key: 'no-icon', kind: 'mcp', name: 'Notion', usable: true, defaultOpen: true },
      // 静态应用后端没给 icon ⇒ 前端按 kind 用内置线条图标
      { key: 'nexusai-in-chrome', kind: 'browser', name: '浏览器', usable: true, defaultOpen: true },
    ])
    render(<Composer {...baseProps({ enabledApps: null, onEnabledAppsChange: vi.fn() })} />)
    act(() => { trigger().dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    await flush(10)

    const avatar = (k: string) => host.querySelector(`.app-picker-row[data-app-key="${k}"] .app-picker-avatar`) as HTMLElement

    // ① 有 icon ⇒ <img src=…>
    const img = avatar('with-icon').querySelector('img') as HTMLImageElement
    expect(img, '有 icon 时应渲染 img').toBeTruthy()
    expect(img.getAttribute('src')).toBe('/app-icons/x.svg')

    // ② 无 icon 的 MCP ⇒ 首字母
    expect(avatar('no-icon').querySelector('img')).toBeNull()
    expect(avatar('no-icon').textContent).toBe('N')

    // ③ 加载失败 ⇒ 回落首字母（同 key 不再渲染 img）
    await act(async () => {
      img.dispatchEvent(new Event('error'))
    })
    await flush(0)
    expect(avatar('with-icon').querySelector('img')).toBeNull()
    expect(avatar('with-icon').textContent).toBe('C')
  })

  it('⭐ 不可用但后端 canStart ⇒ 开关可点并真调启动；失败显示真实原因', async () => {
    vi.mocked(fetchAppEntries).mockResolvedValue([
      { key: 'stopped', kind: 'mcp', name: '停止的', usable: false, defaultOpen: false, unusableReason: '未连接', canStart: true, serverId: 'srv-1' },
    ])
    vi.mocked(startApp).mockRejectedValueOnce(new Error('boom'))
    render(<Composer {...baseProps({ enabledApps: null, onEnabledAppsChange: vi.fn() })} />)

    act(() => { trigger().dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    await flush(10)

    const row = rows()[0]
    const sw = row.querySelector('.app-picker-sw') as HTMLButtonElement
    expect(sw.disabled, 'canStart 的不可用应用应可点启动').toBe(false)
    act(() => { sw.click() })
    await flush(10)

    expect(startApp).toHaveBeenCalledWith('srv-1', 'sess-1')
    expect(row.textContent).toContain('启动失败：boom')
  })

  it('行状态副标题：已启动 / 已停止（不可用则用后端原因）', async () => {
    vi.mocked(fetchAppEntries).mockResolvedValue([
      app('a', 'A'), app('b', 'B'), app('off', '停用的', false),
    ])
    render(<Composer {...baseProps({ enabledApps: ['a'], onEnabledAppsChange: vi.fn() })} />)
    act(() => { trigger().dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    await flush(10)

    const desc = (k: string) => (host.querySelector(`.app-picker-row[data-app-key="${k}"] .app-picker-desc`)?.textContent ?? '')
    expect(desc('a')).toContain('已启动')
    expect(desc('b')).toContain('已停止')
    expect(desc('off')).toContain('未连接')
  })
})
