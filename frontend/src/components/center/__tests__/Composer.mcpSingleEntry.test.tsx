// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Composer } from '../Composer'
import type { SessionToolDto } from '@/api/types'

/**
 * 应用层单一入口（Composer 侧接线）。
 *
 * <h2>WHY（意图验证）</h2>
 * <ul>
 *   <li>用户裁定：MCP / 浏览器 / 电脑操作 都归「应用」，工具面板**不再列出这三类**
 *       （开关在输入框「应用」里）。⛔ 面板里再出现这三行 = 又变成两套入口。</li>
 *   <li>老会话可能带历史 disabled_tools 行：面板给一个「应用层历史禁用 · 恢复」入口，
 *       只做恢复（`setCategoryEnabled(cat, true)`），⛔ 不提供禁用。</li>
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
  return { ...actual, fetchAppEntries: vi.fn(async () => []) }
})

const TOOLS: SessionToolDto[] = [
  { name: 'mcp__rea__inspect_plist', userFacingName: '读取 plist', category: 'MCP', description: '', disabled: true, mcpServer: 'rea' },
  { name: 'Bash', userFacingName: 'Bash（终端）', category: 'SYS', description: '', disabled: false },
]

const listTools = vi.fn(async () => TOOLS)
const setCategoryEnabled = vi.fn(async () => TOOLS)

vi.mock('@/api/sessions', () => ({
  sessionApi: {
    listTools: () => listTools(),
    setCategoryEnabled: (...a: unknown[]) => setCategoryEnabled(...(a as [])),
    setToolEnabled: vi.fn(),
    setBrief: vi.fn(),
  },
}))

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
  root = createRoot(host)
  localStorage.clear()
  listTools.mockClear()
  setCategoryEnabled.mockClear()
})

afterEach(() => {
  act(() => { root?.unmount() })
  root = null
  host.remove()
})

/** 打开「工具」面板：该入口是 hover 展开（onMouseEnter） */
async function openToolsPanel() {
  const wrap = host.querySelector('.tools-chip-wrap') as HTMLElement
  expect(wrap, '找不到工具入口').toBeTruthy()
  await act(async () => {
    wrap.dispatchEvent(new MouseEvent('mouseover', { bubbles: true }))
  })
  await flush(10)
}

describe('Composer · 应用层单一入口接线', () => {
  it('工具面板里不再有 MCP / 浏览器 / 电脑操作 分类（唯一入口 = 输入框「应用」）', async () => {
    act(() => {
      root!.render(<Composer {...baseProps({ selectedApps: null, onAppsChange: () => {} })} />)
    })
    await flush(0)
    await openToolsPanel()

    const names = Array.from(host.querySelectorAll('.l1-name')).map((n) => n.textContent)
    // 只有 MCP 移出；CU / BR 回到工具面板
    expect(names).not.toContain('MCP 工具')
    expect(names).toContain('浏览器控制')
    expect(names).toContain('电脑操作')
    // 开关：CU / KB / BR / IM / SYS 共 5 枚（MCP 无）
    expect(host.querySelectorAll('.tools-chip-wrap .tc-sw').length).toBe(5)
  })

  it('浏览器组空态保留「检查 / 安装扩展」入口（ChromePanel）', async () => {
    const onOpenChromePanel = vi.fn()
    act(() => {
      root!.render(<Composer {...baseProps({
        selectedApps: null, onAppsChange: () => {}, onOpenChromePanel,
      })} />)
    })
    await flush(0)
    await openToolsPanel()

    const brRow = Array.from(host.querySelectorAll('.l1-item'))
      .find((el) => el.querySelector('.l1-name')?.textContent === '浏览器控制') as HTMLElement
    await act(async () => { brRow.click() })
    await flush(0)

    const btn = Array.from(host.querySelectorAll('.tools-panel-retry'))
      .find((b) => (b.textContent ?? '').includes('检查 / 安装扩展')) as HTMLButtonElement
    expect(btn, '缺少 ChromePanel 入口').toBeTruthy()
    act(() => { btn.click() })
    expect(onOpenChromePanel).toHaveBeenCalledTimes(1)
  })

  it('历史禁用 ⇒ 面板出现恢复入口，点击只发「整类启用」（不提供禁用）', async () => {
    act(() => {
      root!.render(<Composer {...baseProps({ selectedApps: null, onAppsChange: () => {} })} />)
    })
    await flush(0)
    await openToolsPanel()

    const row = host.querySelector('[data-testid="app-managed-history"]') as HTMLElement | null
    expect(row, '缺少应用层历史禁用恢复入口').toBeTruthy()
    await act(async () => { row!.click() })
    await flush(10)

    expect(setCategoryEnabled).toHaveBeenCalledWith('sess-1', 'MCP', true)
  })
})
