// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SkillMarketModal } from '../SkillMarketModal'

/**
 * 开发者弹窗（v5）：头部作者信息 + 作品统计、技能/专家两个 tab、作品卡全部复用共享
 * MarketCard、空态、Esc 关闭回原列表（不改筛选）、按作者搜索、专家卡作者行入口。
 */
const mocks = vi.hoisted(() => ({
  listAgents: vi.fn(), listInstalled: vi.fn(), listInstalledDetails: vi.fn(),
  listCommands: vi.fn(), listExternal: vi.fn(), install: vi.fn(), progress: vi.fn(),
  listBundled: vi.fn(),
}))

vi.mock('@/api/agent', () => ({ agentApi: { listAgents: mocks.listAgents } }))
vi.mock('@/api/market', () => ({
  marketApi: {
    listInstalled: mocks.listInstalled,
    listInstalledDetails: mocks.listInstalledDetails,
    listExternal: mocks.listExternal,
    installPlugin: mocks.install,
    getInstallProgress: mocks.progress,
  },
}))
vi.mock('@/api/command', () => ({ commandApi: { list: mocks.listCommands } }))
vi.mock('@/api/connectors', () => ({ connectorsApi: { list: vi.fn(async () => ({ connectors: [] })) } }))
vi.mock('@/api/experts', () => ({ expertsApi: { listBundled: mocks.listBundled } }))
vi.mock('@/api/rest', () => ({
  ApiError: class ApiError extends Error {
    status = 0
    userMessage() { return this.message }
  },
}))

const AUTHOR = { authorName: '张三', authorVerified: true, authorEmail: 'zhang@example.com' }
const MARKET = {
  name: 'zjky-market',
  plugins: [
    { name: 'sk1', displayName: '演示技能', description: '技能一句话', version: '1.0.0', category: 'skill', tags: [], ...AUTHOR },
    { name: 'exp1', displayName: '演示专家', description: '专家一句话', version: '2.0.0', category: 'expert', tags: [], profession: '资深架构师', ...AUTHOR },
    { name: 'other', displayName: '别人的技能', description: '别人的技能说明', version: '1.0.0', category: 'skill', tags: [], authorName: '李四' },
  ],
}

function noop(): void {}
async function flush(n = 12) { for (let i = 0; i < n; i++) await act(async () => { await Promise.resolve() }) }

let root: Root | undefined
let container: HTMLDivElement | undefined

beforeEach(() => {
  ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)

  mocks.listAgents.mockReset(); mocks.listAgents.mockResolvedValue([])
  mocks.listInstalled.mockReset(); mocks.listInstalled.mockResolvedValue([])
  mocks.listInstalledDetails.mockReset(); mocks.listInstalledDetails.mockResolvedValue([])
  mocks.listCommands.mockReset(); mocks.listCommands.mockResolvedValue([])
  mocks.listExternal.mockReset(); mocks.listExternal.mockResolvedValue(MARKET)
  mocks.install.mockReset(); mocks.install.mockResolvedValue({ success: true, pluginName: 'exp1' })
  mocks.progress.mockReset(); mocks.progress.mockResolvedValue(undefined)
  mocks.listBundled.mockReset(); mocks.listBundled.mockResolvedValue([])
})

afterEach(() => {
  if (root) act(() => root!.unmount())
  container?.remove()
})

async function mount() {
  act(() => {
    root!.render(
      <SkillMarketModal
        sessionId="sess-1"
        currentAgent={null}
        onClose={noop}
        onUseLocalAgent={noop}
        onSummonSkill={noop}
        showToast={noop}
      />,
    )
  })
  await flush()
}

async function openSkillTab() {
  await mount()
  const tab = Array.from(container!.querySelectorAll('.sm-tab-flat'))
    .find((el) => (el.textContent ?? '').includes('技能')) as HTMLElement
  act(() => { tab.click() })
  await flush()
}

function skillCards(): HTMLElement[] {
  return Array.from(container!.querySelectorAll('.sk-card') as NodeListOf<HTMLElement>)
}

function skillCard(name: string): HTMLElement {
  return skillCards().find((el) => (el.textContent ?? '').includes(name))!
}

async function openDeveloperFromSkill(name: string) {
  const authorRow = skillCard(name).querySelector('.sk-author') as HTMLElement
  expect(authorRow, `${name} 卡片缺少作者行`).toBeTruthy()
  act(() => { authorRow.click() })
  await flush()
}

function devModal(): HTMLElement | null {
  return container!.querySelector('.dev-modal')
}

function devTabs(): HTMLElement[] {
  return Array.from(container!.querySelectorAll('.dev-tab') as NodeListOf<HTMLElement>)
}

describe('SkillMarketModal · 开发者弹窗', () => {
  it('头部展示作者信息与作品统计（技能/专家计数）', async () => {
    await openSkillTab()
    await openDeveloperFromSkill('演示技能')

    const modal = devModal()
    expect(modal).toBeTruthy()
    const text = modal!.textContent ?? ''
    expect(text).toContain('张三')
    expect(text).toContain('zhang@example.com')
    expect(text).toContain('作者 ID · 张三')
    expect(modal!.querySelector('.dev-verify')).toBeTruthy()
    // 统计：该作者 1 个技能 + 1 个专家（「别人的技能」属李四，不进来）
    const stats = modal!.querySelector('.dev-stats')!.textContent ?? ''
    expect(stats).toContain('1 技能')
    expect(stats).toContain('1 专家')
  })

  it('两个 tab：默认技能，切专家显示作品卡，且全部复用共享 MarketCard（无 dev-card）', async () => {
    await openSkillTab()
    await openDeveloperFromSkill('演示技能')

    const tabs = devTabs()
    expect(tabs).toHaveLength(2)
    expect(tabs[0].textContent).toContain('技能')
    expect(tabs[0].textContent).toContain('(1)')
    expect(tabs[1].textContent).toContain('专家')
    expect(tabs[0].className).toContain('active')

    // 技能作品卡：共享 MarketCard 外壳
    const skillGridCard = container!.querySelector('.dev-grid .market-card-base') as HTMLElement
    expect(skillGridCard).toBeTruthy()
    expect(skillGridCard.textContent).toContain('演示技能')
    expect(container!.querySelector('.dev-card')).toBeNull()

    act(() => { tabs[1].click() })
    await flush()
    expect(tabs[1].className).toContain('active')
    const expertGridCard = container!.querySelector('.dev-grid .market-card-base') as HTMLElement
    expect(expertGridCard).toBeTruthy()
    expect(expertGridCard.textContent).toContain('演示专家')
  })

  it('空态：无作品的分支显示提示', async () => {
    await openSkillTab()
    await openDeveloperFromSkill('别人的技能')

    const modal = devModal()!
    expect(modal.querySelectorAll('.dev-tab')).toHaveLength(2)
    act(() => { devTabs()[1].click() })
    await flush()
    const empty = modal.querySelector('.dev-empty')
    expect(empty).toBeTruthy()
    expect(empty!.textContent).toContain('暂无专家')
  })

  it('Esc 只关闭开发者弹窗，回到原列表且筛选不变', async () => {
    await openSkillTab()
    await openDeveloperFromSkill('演示技能')
    expect(devModal()).toBeTruthy()

    act(() => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    })
    await flush()

    expect(devModal()).toBeNull()
    // 仍停在技能 tab，技能卡还在（筛选未被重置）
    const activeTopTab = container!.querySelector('.sm-tab-flat.active') as HTMLElement
    expect(activeTopTab.textContent).toContain('技能')
    expect(skillCards().length).toBeGreaterThanOrEqual(2)
  })

  it('搜索按作者名匹配技能', async () => {
    await openSkillTab()
    expect(skillCards()).toHaveLength(2)

    const input = container!.querySelector('.sm-search-pill input') as HTMLInputElement
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!
    act(() => {
      setter.call(input, '张三')
      input.dispatchEvent(new Event('input', { bubbles: true }))
    })
    await flush()

    const cards = skillCards()
    expect(cards).toHaveLength(1)
    expect(cards[0].textContent).toContain('演示技能')
  })

  it('专家卡作者行点击打开同一个开发者弹窗', async () => {
    await mount() // 默认专家 tab
    const expertCard = Array.from(container!.querySelectorAll('.sm-expert-card') as NodeListOf<HTMLElement>)
      .find((el) => (el.textContent ?? '').includes('演示专家'))!
    expect(expertCard).toBeTruthy()
    const authorRow = expertCard.querySelector('.sk-author') as HTMLElement
    expect(authorRow, '专家卡缺少作者行').toBeTruthy()

    act(() => { authorRow.click() })
    await flush()
    expect(devModal()!.textContent).toContain('张三')
  })

  it('开发者弹窗内点专家作品卡 → 安装该插件', async () => {
    await openSkillTab()
    await openDeveloperFromSkill('演示技能')
    act(() => { devTabs()[1].click() })
    await flush()

    const expertCard = container!.querySelector('.dev-grid .market-card-base') as HTMLElement
    act(() => { expertCard.click() })
    await flush()

    expect(mocks.install).toHaveBeenCalledTimes(1)
    expect(mocks.install.mock.calls[0][0]).toMatchObject({ pluginId: 'exp1@zjky-market' })
  })
})
