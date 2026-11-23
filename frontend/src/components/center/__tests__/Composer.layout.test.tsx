// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { Composer } from '../Composer'

/**
 * 工具栏单排布局（用户 2026-11-23 截图：应用入口把一整行挤成两排）。
 *
 * <h2>WHY</h2>
 * 应用入口曾做成 32×32 圆钮 + 自定义高度，比同行 chip（28px）高 ⇒ `.toolbar { flex-wrap: wrap }`
 * 把右侧模型/发送挤到第二排。修法 = **完全复用 `.tool-chip`**（宽高/padding/gap 都交给 globals），
 * 本文件把「不得再自定义高度」和「空 chip 行不产生高度」钉成断言。
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

const CSS = path.resolve(process.cwd(), 'src/styles/app-picker.css')
const GLOBALS = path.resolve(process.cwd(), 'src/styles/globals.css')
function globalsCss(): string {
  return readFileSync(GLOBALS, 'utf8').replace(/\r\n?/g, '\n')
}
function css(): string {
  return readFileSync(CSS, 'utf8').replace(/\r\n?/g, '\n')
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
  root = createRoot(host)
})

afterEach(() => {
  act(() => { root?.unmount() })
  root = null
  host.remove()
})

describe('Composer · 工具栏单排', () => {
  it('⭐ 应用入口与其它 chip 同在 .left-tools（同一 flex 容器），且用 .tool-chip 的尺寸', async () => {
    act(() => {
      root!.render(<Composer {...baseProps({ selectedApps: [], onAppsChange: () => {} })} />)
    })
    await flush(0)

    const leftTools = host.querySelector('.toolbar .left-tools') as HTMLElement
    expect(leftTools, '找不到工具行容器').toBeTruthy()

    const trigger = host.querySelector('[data-testid="app-picker-trigger"]') as HTMLElement
    expect(trigger, '找不到应用入口').toBeTruthy()
    // 同一个 flex 行容器里的直接子项（不是单独一行/单独 wrap）
    expect(leftTools.contains(trigger)).toBe(true)
    expect(trigger.closest('.app-picker-wrap')!.parentElement).toBe(leftTools)
    // 复用 chip 类（尺寸/间距来自 globals 的 .tool-chip，28px）
    expect(trigger.classList.contains('tool-chip')).toBe(true)
    // 文字标签与其他 chip 同款（窄栏/compact 靠 `tool-chip > span` 收掉）
    expect(trigger.querySelector('span')!.textContent).toBe('应用')
  })

  it('⭐ CSS 不再给入口自定义高度/圆角/包裹层（否则会挤成两排）', () => {
    const text = css()
    // 取 app-trigger 规则块（到下一个注释或 .app-trigger-count 为止）
    const start = text.indexOf('.tool-chip.app-trigger {')
    const end = text.indexOf('/* 已选数量角标', start)
    const block = text.slice(start, end)
    expect(block).not.toMatch(/height\s*:/)
    expect(block).not.toMatch(/width\s*:/)
    expect(block).not.toMatch(/padding\s*:/)
    expect(block).not.toMatch(/border-radius\s*:/)
    // 入口包裹层也不许有尺寸
    const wrapStart = text.indexOf('.composer .app-picker-wrap {')
    const wrapBlock = text.slice(wrapStart, text.indexOf('}', wrapStart))
    expect(wrapBlock).not.toMatch(/height\s*:/)
  })

  it('⭐ chip 行空时不产生高度（无强调 ⇒ 不渲染 .input-chips）', async () => {
    act(() => {
      root!.render(<Composer {...baseProps({ selectedApps: null, onAppsChange: () => {} })} />)
    })
    await flush(0)
    expect(host.querySelector('[data-testid="input-chips"]')).toBeNull()

    // 有强调才出现，且与 topChips 同容器（不额外起行）
    act(() => {
      root!.render(<Composer {...baseProps({
        selectedApps: ['notion'],
        onAppsChange: () => {},
        topChips: <span data-testid="kb-chip">KB</span>,
      })} />)
    })
    await flush(0)
    const row = host.querySelector('[data-testid="input-chips"]') as HTMLElement
    expect(row.querySelector('[data-testid="kb-chip"]')).toBeTruthy()
    expect(row.querySelector('.app-chip')).toBeTruthy()
  })

  it('CSS：input-chips 无内边距（不抬高 input-box），compact 与窄栏同一套收文字规则', () => {
    const text = css()
    const start = text.indexOf('.composer .input-chips {')
    const block = text.slice(start, text.indexOf('}', start))
    expect(block).not.toMatch(/padding\s*:/)
    // compact 不再排除 app-trigger（否则它比别的 chip 大、又挤行）
    expect(text).not.toContain('.tool-chip:not(.app-trigger)')
  })

  it('⭐ 指定 chip 与「+」同款白底（应用/计划/工具/渠道/无人），协调者不在覆盖内', () => {
    const text = css()
    // 只取**基础态**那段（从注释到第一个 }），hover 段单独存在不算数
    const start = text.indexOf('指定入口与「+」同款')
    const base = text.slice(start, text.indexOf('}', start))
    for (const cls of ['app-trigger', 'plan-mode-chip', 'tool-entry', 'chan-entry', 'uo-entry']) {
      expect(base, `${cls} 缺白底覆盖`).toContain(`.tool-chip.${cls}:not(.active)`)
    }
    // 白底 = 透明；边框用主题变量（深色可读）
    expect(base).toMatch(/background:\s*transparent;/)
    expect(base).toMatch(/border: 1px solid var\(--hairline\);/)
    // ⛔ 协调者保持自己的激活语言
    expect(text).not.toContain('.coordinator-chip:not(.active)')
  })

  it('⭐ 默认显示文字：收字阈值只在 ≤700px（⛔ 不再 701–962px 提前收）', () => {
    const text = globalsCss()
    expect(text).toContain('@container composer-inner (max-width: 700px)')
    expect(text).not.toContain('@container composer-inner (max-width: 960px)')
  })

  it('⭐ 701–962px 单排靠「不换行 + 压缩间距/内边距」，⛔ 不给 .left-tools 加 overflow（会裁弹层）', async () => {
    const text = css()
    expect(text).toMatch(/\.composer \.toolbar \{\s*\n\s*flex-wrap: nowrap;/)
    const at = text.indexOf('.composer .toolbar .left-tools {')
    const block = text.slice(at, text.indexOf('}', at))
    expect(block).toContain('flex-wrap: nowrap')
    expect(block).toContain('overflow: visible')
    // P0：裁剪上下文会把 plus-menu / 应用面板 / 工具面板裁掉
    expect(block).not.toContain('overflow-x: auto')
    expect(text).not.toContain('.composer .toolbar .left-tools::-webkit-scrollbar')
    // 单排靠两档压缩
    expect(text).toContain('@container composer-inner (max-width: 960px)')
    expect(text).toContain('@container composer-inner (max-width: 860px)')

    // 默认 DOM 里这些入口的文字 label 必须都在（900px 属于默认态，不收字）
    act(() => {
      root!.render(<Composer {...baseProps({
        selectedApps: [], onAppsChange: () => {},
        onCoordinatorModeChange: () => {},
        onEnterPlanMode: () => {},
        onOpenMarket: () => {},
        onOpenImChannels: () => {},
        onToggleUnattended: () => {},
        imInstances: [{ id: 'i1', label: '飞书' }],
      })} />)
    })
    await flush(0)
    const labels = Array.from(host.querySelectorAll('.left-tools .tool-chip > span'))
      .map((n) => (n.textContent ?? '').trim())
    for (const expected of ['应用', '计划', '协调者', '渠', '无人']) {
      expect(labels.some((t) => t.includes(expected)), `${expected} 默认应显示文字`).toBe(true)
    }
  })

  it('⭐ 弹层不被裁剪：应用 / 工具 / 渠道 都能弹出并挂在 document 上', async () => {
    act(() => {
      root!.render(<Composer {...baseProps({
        selectedApps: [], onAppsChange: () => {},
        onOpenImChannels: () => {},
        imInstances: [{ id: 'i1', label: '飞书' }],
      })} />)
    })
    await flush(0)

    // 应用面板：点击触发器后必须渲染（锚在 .left-tools 内，overflow 不得为裁剪上下文）
    act(() => { (host.querySelector('[data-testid="app-picker-trigger"]') as HTMLElement).click() })
    await flush(10)
    const appPanel = host.querySelector('[data-testid="app-picker-panel"]') as HTMLElement | null
    expect(appPanel, '应用面板没弹出').toBeTruthy()
    // 祖先里不允许出现会裁剪的 overflow（容器查询/媒体查询之外的 inline 或 CSS 类都在源码断言里）
    const leftTools = appPanel!.closest('.left-tools') as HTMLElement | null
    expect(leftTools, '应用面板应仍锚在工具行内').toBeTruthy()
    expect((leftTools as HTMLElement).style.overflowX).not.toBe('auto')

    // 工具面板：hover 入口后渲染
    const wrap = host.querySelector('.tools-chip-wrap') as HTMLElement
    act(() => { wrap.dispatchEvent(new MouseEvent('mouseover', { bubbles: true })) })
    await flush(10)
    expect(host.querySelector('.tools-panel'), '工具面板没弹出').toBeTruthy()

    // 渠道：模态走 portal（挂 body）
    act(() => { (host.querySelector('.chan-entry') as HTMLElement).click() })
    await flush(10)
    expect(document.body.querySelector('.chanm-backdrop, .fm-backdrop'), '渠道弹窗没弹出').toBeTruthy()
  })

  it('⭐ max 徽章常显：任何 CSS 规则都不得把 .mep-effort 隐藏', () => {
    for (const text of [globalsCss(), css()]) {
      const hideRules = text.split('\n').filter((l) => l.includes('mep-effort') && l.includes('display: none'))
      expect(hideRules, 'mep-effort 被 display:none 隐藏了').toEqual([])
    }
  })

  it('⭐ 长文本（>8000）不渲染透明高亮层；阈值内照常渲染，文本不受影响', async () => {
    const long = 'a'.repeat(8001)
    act(() => {
      root!.render(<Composer {...baseProps({ composerText: long })} />)
    })
    await flush(0)
    expect(host.querySelector('[data-testid="composer-highlight"]'), '超长文本必须关掉 overlay').toBeNull()
    expect((host.querySelector('textarea') as HTMLTextAreaElement).value).toBe(long)

    act(() => {
      root!.render(<Composer {...baseProps({ composerText: 'a'.repeat(8000) })} />)
    })
    await flush(0)
    expect(host.querySelector('[data-testid="composer-highlight"]'), '阈值内 overlay 应保留').not.toBeNull()
  })

  it('CSS：模型胶囊 ≤120px（别名 + 品牌图标 + 档位标签）并带自定义 tooltip', () => {
    const text = css()
    expect(text).toMatch(/\.composer \.toolbar \.right-tools \.model-effort-pill \{\s*\n\s*max-width: 120px;/)
    for (const cls of ['.mep-alias', '.mep-brand', '.model-effort-tip']) {
      expect(text, `${cls} 缺样式`).toContain(cls + ' {')
    }
  })
})
