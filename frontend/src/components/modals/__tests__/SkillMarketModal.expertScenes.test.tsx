// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MarketBrowser } from '@/components/market/MarketBrowser'

/**
 * 专家页：精选场景 + 专家/专家团子 tab（2026-11-23 用户终稿，替换原来的上下两块）。
 *
 * <p><b>WHY</b>：
 * <ul>
 *   <li>精选场景是**前端常量映射**（不新增后端），点场景必须真的筛选下方专家；</li>
 *   <li>「专家 | 专家团」是子 tab（选中态明显），专家团按 division 分组、成员可安装；</li>
 *   <li>专家卡要有岗位小字与标签 chips，搜索同时作用于场景/专家/专家团。</li>
 * </ul>
 */
const {
  listAgentsMock, listInstalledMock, listInstalledDetailsMock, listCommandsMock, listExternalMock, listBundledMock,
} = vi.hoisted(() => ({
  listAgentsMock: vi.fn(),
  listInstalledMock: vi.fn(),
  listInstalledDetailsMock: vi.fn(),
  listCommandsMock: vi.fn(),
  listExternalMock: vi.fn(),
  listBundledMock: vi.fn(),
}))

vi.mock('@/api/agent', () => ({ agentApi: { listAgents: listAgentsMock } }))
vi.mock('@/api/market', () => ({
  marketApi: {
    listInstalled: listInstalledMock,
    listInstalledDetails: listInstalledDetailsMock,
    listExternal: listExternalMock,
    installPlugin: vi.fn(),
    getInstallProgress: vi.fn(),
    skillPreview: vi.fn(),
  },
}))
vi.mock('@/api/command', () => ({ commandApi: { list: listCommandsMock } }))
vi.mock('@/api/experts', () => ({
  expertsApi: { listBundled: listBundledMock, installBundled: vi.fn() },
}))

const noop = () => undefined

const MARKET = {
  name: 'zjky-market',
  plugins: [
    {
      name: 'expert-invest', displayName: '投资分析师', description: '财报拆解、估值与风险清单',
      category: 'expert', profession: '投研', tags: ['finance'], useCount: 12,
    },
    {
      name: 'expert-copy', displayName: '文案专家', description: '长文写作与内容改写',
      category: 'expert', profession: '内容', tags: ['writing'], useCount: 8,
    },
  ],
}
const BUNDLED = [
  { slug: 'engineering-backend-architect', division: 'engineering', name: '后端架构师', description: '可扩展系统设计', emoji: '🏗️', color: 'blue', installed: false },
  { slug: 'security-appsec-engineer', division: 'security', name: '应用安全工程师', description: '威胁建模', emoji: '🔐', color: null, installed: true },
]

async function flush(rounds = 12) {
  for (let i = 0; i < rounds; i++) {
    await act(async () => { await Promise.resolve() })
  }
}

let container: HTMLDivElement | undefined
let root: Root | undefined

beforeEach(() => {
  ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  listAgentsMock.mockReset(); listAgentsMock.mockResolvedValue([])
  listInstalledMock.mockReset(); listInstalledMock.mockResolvedValue([])
  listInstalledDetailsMock.mockReset(); listInstalledDetailsMock.mockResolvedValue([])
  listCommandsMock.mockReset(); listCommandsMock.mockResolvedValue([])
  listExternalMock.mockReset(); listExternalMock.mockResolvedValue(MARKET)
  listBundledMock.mockReset(); listBundledMock.mockResolvedValue(BUNDLED)
})

afterEach(() => {
  if (root) act(() => root!.unmount())
  container?.remove()
})

function renderPage() {
  act(() => {
    root!.render(
      <MarketBrowser
        sessionId="s"
        currentAgent={null}
        mode="page"
        initialTab="expert"
        onClose={noop}
        onUseLocalAgent={noop}
        onSummonSkill={noop}
        showToast={noop}
      />,
    )
  })
}

function byText(text: string): HTMLElement | undefined {
  return Array.from(container!.querySelectorAll('button'))
    .find((b) => (b.textContent ?? '').includes(text)) as HTMLElement | undefined
}

describe('专家页 · 精选场景 / 专家团子 tab', () => {
  it('渲染 6 个精选场景；专家卡有岗位小字与标签 chips', async () => {
    renderPage()
    await flush()

    expect(container!.querySelectorAll('.mk-scene')).toHaveLength(6)
    expect(container!.textContent).toContain('内容创作')
    expect(container!.textContent).toContain('投资分析')

    // 岗位小字（后端无 profession 字段 ⇒ 目前回落使用量文案，⛔ 不编真人姓名）+ 标签
    expect(container!.querySelectorAll('.mk-expert-role').length).toBeGreaterThan(0)
    expect(container!.textContent).toContain('12 次使用')
    expect(container!.textContent).toContain('finance')
  })

  it('点场景打开代表专家的详情弹窗（不再筛选下方列表）', async () => {
    renderPage()
    await flush()

    const scene = Array.from(container!.querySelectorAll('.mk-scene'))
      .find((el) => (el.textContent ?? '').includes('投资分析')) as HTMLElement
    act(() => { scene.click() })
    await flush()

    const modal = container!.querySelector('.ed-card') as HTMLElement | null
    expect(modal, '点场景应打开专家详情弹窗').toBeTruthy()
    expect(modal!.textContent).toContain('投资分析师')
    expect(modal!.textContent).toContain('专家帮你做')

    // 列表不被场景过滤：两张专家卡都还在
    const grid = () => container!.querySelector('.sm-expert-grid')?.textContent ?? ''
    expect(grid()).toContain('文案专家')

    act(() => { (modal!.querySelector('.ed-close') as HTMLElement).click() })
    await flush()
    expect(container!.querySelector('.ed-card')).toBeNull()
  })

  it('专家团子 tab 惰性加载，按 division 分组展示成员；查看成员真实展开', async () => {
    renderPage()
    await flush()
    expect(listBundledMock).not.toHaveBeenCalled()

    act(() => { byText('专家团')!.click() })
    await flush()
    expect(listBundledMock).toHaveBeenCalledTimes(1)

    const cards = container!.querySelectorAll('[data-testid="expert-team-card"]')
    expect(cards.length).toBe(2)
    expect(container!.textContent).toContain('后端架构师')
    expect(container!.textContent).toContain('应用安全工程师')
    expect(container!.textContent).toContain('2 位内置专家')
    expect(byText('查看成员')).toBeTruthy()
  })

  it('搜索时隐藏精选场景，并同时过滤专家列表', async () => {
    renderPage()
    await flush()

    const input = container!.querySelector('.sm-search-pill input') as HTMLInputElement
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!
    act(() => {
      setter.call(input, '财报')
      input.dispatchEvent(new Event('input', { bubbles: true }))
    })
    await flush()

    expect(container!.querySelector('[data-testid="expert-scenes"]')).toBeNull()
    expect(container!.textContent).toContain('投资分析师')
    expect(container!.textContent).not.toContain('文案专家')
  })

  it('搜索同时作用于专家团（按成员名称过滤团队）', async () => {
    renderPage()
    await flush()
    act(() => { byText('专家团')!.click() })
    await flush()

    const input = container!.querySelector('.sm-search-pill input') as HTMLInputElement
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!
    act(() => {
      setter.call(input, '安全')
      input.dispatchEvent(new Event('input', { bubbles: true }))
    })
    await flush()

    const cards = container!.querySelectorAll('[data-testid="expert-team-card"]')
    expect(cards.length).toBe(1)
    expect(container!.textContent).toContain('应用安全工程师')
    expect(container!.textContent).not.toContain('后端架构师')
  })

  it('空态：搜索无结果时给出明确文案', async () => {
    renderPage()
    await flush()

    const input = container!.querySelector('.sm-search-pill input') as HTMLInputElement
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!
    act(() => {
      setter.call(input, 'zzz-no-such-expert')
      input.dispatchEvent(new Event('input', { bubbles: true }))
    })
    await flush()

    expect(container!.textContent).toContain('没有匹配的专家')
  })
})
