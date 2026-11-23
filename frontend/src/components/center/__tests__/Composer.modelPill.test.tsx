// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Composer } from '../Composer'
import { effortTag, resolveModelBrand } from '@/utils/modelBrand'

/**
 * 右侧模型胶囊（用户选型）：`DS` 别名 + 品牌图标 + `max` 档位标签；hover ≤100ms 出全名。
 *
 * <h2>WHY</h2>
 * 胶囊收窄到 ≤120px 后放不下完整模型名 ⇒ 用两字母别名；但用户必须仍能一眼确认「这是哪个模型」，
 * 所以 ①下拉里保持完整名 ②hover 用**自定义 tooltip（≤100ms）**，不能靠原生 title（约 500ms 才出）。
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
    onOpenModelPicker: () => {},
    onOpenEffort: () => {},
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
  vi.useRealTimers()
})

function pill(): HTMLElement {
  const el = host.querySelector('.model-effort-pill') as HTMLElement | null
  expect(el, '找不到模型胶囊').toBeTruthy()
  return el!
}

describe('模型别名 / 档位标签（纯函数）', () => {
  it('已知品牌用固定别名；规则顺序正确（deepseek 不被 openai 前缀抢走）', () => {
    expect(resolveModelBrand('ds-openai/deepseek-v4-flash')).toMatchObject({ alias: 'DS', brand: 'deepseek', provider: 'DeepSeek' })
    expect(resolveModelBrand('anthropic/claude-sonnet-4-5')).toMatchObject({ alias: 'CL', brand: 'claude' })
    expect(resolveModelBrand('openai/gpt-6.1-sol')).toMatchObject({ alias: 'OA', brand: 'openai' })
    expect(resolveModelBrand('google/gemini-3-pro')).toMatchObject({ alias: 'GM', brand: 'gemini' })
  })

  it('未知模型取末段前两位大写；图标回落到内置 fallback', () => {
    const b = resolveModelBrand('acme/zeta-9000')
    expect(b.alias).toBe('ZE')
    expect(b.icon).toBe('/provider-icons/_fallback.svg')
    expect(resolveModelBrand(null).alias).toBe('??')
  })

  it('档位标签：无档位/off 不显示；ultracode 记作 max', () => {
    expect(effortTag(null)).toBeNull()
    expect(effortTag('off')).toBeNull()
    expect(effortTag('max')).toBe('max')
    expect(effortTag('high', true)).toBe('max')
  })
})

describe('Composer · 模型胶囊渲染', () => {
  it('⭐ 显示顺序 = 别名 + 品牌图标 + 档位标签（DS → deepseek.svg → max）', async () => {
    act(() => {
      root!.render(<Composer {...baseProps({ effortLevel: 'max' })} />)
    })
    await flush(0)

    const p = pill()
    const alias = p.querySelector('.mep-alias') as HTMLElement
    const brand = p.querySelector('.mep-brand') as HTMLImageElement
    const tag = p.querySelector('.mep-effort') as HTMLElement
    expect(alias.textContent).toBe('DS')
    expect(brand.getAttribute('src')).toBe('/provider-icons/deepseek.svg')
    expect(tag.textContent).toBe('max')
    // 顺序：别名在图标之前、图标在档位标签之前
    expect(alias.compareDocumentPosition(brand) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(brand.compareDocumentPosition(tag) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('没有推理档位 ⇒ 不渲染 max 标签', async () => {
    act(() => {
      root!.render(<Composer {...baseProps({ effortLevel: null })} />)
    })
    await flush(0)
    expect(pill().querySelector('.mep-effort')).toBeNull()
  })

  it('⭐ hover 100ms 内出现自定义 tooltip（全名 + 供应商 + 档位），离开即消失', async () => {
    vi.useFakeTimers()
    act(() => {
      root!.render(<Composer {...baseProps({ effortLevel: 'max' })} />)
    })
    act(() => { vi.advanceTimersByTime(0) })

    act(() => { pill().dispatchEvent(new MouseEvent('mouseover', { bubbles: true })) })
    // 99ms 时还不显示
    act(() => { vi.advanceTimersByTime(99) })
    expect(host.querySelector('.model-effort-tip')).toBeNull()
    // 100ms 内（=100ms）出现
    act(() => { vi.advanceTimersByTime(1) })
    const tip = host.querySelector('.model-effort-tip') as HTMLElement
    expect(tip, '100ms 内应出现 tooltip').toBeTruthy()
    expect(tip.textContent).toContain('ds-openai/deepseek-v4-flash')
    expect(tip.textContent).toContain('DeepSeek')
    expect(tip.textContent).toContain('推理 max')

    act(() => { pill().dispatchEvent(new MouseEvent('mouseout', { bubbles: true })) })
    expect(host.querySelector('.model-effort-tip')).toBeNull()
  })

  it('下拉菜单仍显示完整模型名（别名只用于胶囊）', async () => {
    act(() => {
      root!.render(<Composer {...baseProps({ effortLevel: 'max' })} />)
    })
    await flush(0)
    act(() => { pill().dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    await flush(0)
    const drawer = host.querySelector('.pill-drawer') as HTMLElement
    expect(drawer).toBeTruthy()
    expect(drawer.textContent).toContain('ds-openai/deepseek-v4-flash')
  })
})
