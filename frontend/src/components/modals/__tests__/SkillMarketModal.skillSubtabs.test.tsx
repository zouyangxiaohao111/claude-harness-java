// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MarketBrowser } from '@/components/market/MarketBrowser'

/**
 * 技能 tab（2026-11-23 用户终稿）：去掉「本机已装 / 市场」子 tab，目录直接合并展示；
 * 分类使用固定口径；「添加技能」是下拉菜单（查找 / 上传 / 创建）；
 * 右上「我安装的」仍是管理入口（打开已安装弹窗）。
 */
const {
  listAgentsMock, listInstalledMock, listInstalledDetailsMock, listCommandsMock, listExternalMock,
} = vi.hoisted(() => ({
  listAgentsMock: vi.fn(),
  listInstalledMock: vi.fn(),
  listInstalledDetailsMock: vi.fn(),
  listCommandsMock: vi.fn(),
  listExternalMock: vi.fn(),
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
vi.mock('@/api/experts', () => ({ expertsApi: { listBundled: vi.fn(async () => []), installBundled: vi.fn() } }))

const noop = () => undefined

const MARKET = {
  name: 'zjky-market',
  plugins: [
    { name: 'market-skill', displayName: '市场技能甲', description: '市场里的技能', category: 'skill', tags: [] },
  ],
}
const LOCAL_SKILL = { name: 'local-skill', description: '本机技能乙', source: 'USER' }

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
  listCommandsMock.mockReset(); listCommandsMock.mockResolvedValue([LOCAL_SKILL])
  listExternalMock.mockReset(); listExternalMock.mockResolvedValue(MARKET)
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
        initialTab="skill"
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

describe('技能 tab · 固定分类 + 添加技能菜单（2026-11-23 终稿）', () => {
  it('默认只展示市场技能（本机不混入）；固定分类常驻', async () => {
    renderPage()
    await flush()

    const subtabs = Array.from(container!.querySelectorAll('.sm-subtab')).map((b) => b.textContent ?? '')
    expect(subtabs.some((t) => t.includes('本机已装') || t.includes('市场'))).toBe(false)

    const text = container!.textContent ?? ''
    expect(text).toContain('市场技能甲')
    expect(text).not.toContain('本机技能乙')

    const cats = Array.from(container!.querySelectorAll('.sm-cat')).map((b) => b.textContent ?? '')
    for (const fixed of ['全部', 'Pay Skill', '办公效率', 'AI Agent', '行业专业']) {
      expect(cats.some((c) => c.includes(fixed)), `缺少固定分类 ${fixed}`).toBe(true)
    }
  })

  it('分类筛选按固定口径生效，无结果时给出空态', async () => {
    renderPage()
    await flush()

    const cat = (label: string) => Array.from(container!.querySelectorAll('.sm-cat'))
      .find((b) => (b.textContent ?? '').trim() === label) as HTMLElement

    act(() => { cat('办公效率').click() })
    await flush()
    expect(container!.textContent).toContain('市场技能甲')
    expect(container!.textContent).not.toContain('本机技能乙')

    act(() => { cat('数据分析').click() })
    await flush()
    expect(container!.textContent).toContain('该分类暂无技能')

    act(() => { cat('全部').click() })
    await flush()
    expect(container!.textContent).toContain('市场技能甲')
  })

  it('「添加技能」下拉包含 查找技能 / 上传技能 / 创建技能，上传打开上架弹窗', async () => {
    renderPage()
    await flush()

    act(() => { byText('添加技能')!.click() })
    await flush()
    const items = Array.from(container!.querySelectorAll('.sm-add-item')).map((el) => el.textContent ?? '')
    expect(items.some((t) => t.includes('查找技能'))).toBe(true)
    expect(items.some((t) => t.includes('上传技能'))).toBe(true)
    expect(items.some((t) => t.includes('创建技能'))).toBe(true)
    expect(items.some((t) => t.includes('上传专家'))).toBe(false)

    const upload = Array.from(container!.querySelectorAll('.sm-add-item'))
      .find((el) => (el.textContent ?? '').includes('上传技能')) as HTMLElement
    act(() => { upload.click() })
    await flush()
    expect(container!.querySelector('.sm-publish'), '上传技能应打开上架弹窗').toBeTruthy()
  })

  it('右上「我安装的」打开已安装管理弹窗（不是筛选开关）', async () => {
    renderPage()
    await flush()

    const btn = byText('我安装的')
    expect(btn, '整页技能 tab 右上应有「我安装的」入口').toBeTruthy()
    act(() => { btn!.click() })
    await flush()

    const dlg = container!.querySelector('[data-testid="installed-manager"]')
    expect(dlg, '点击后应打开已安装管理弹窗').toBeTruthy()
    expect(dlg!.textContent).toContain('本机技能乙')
  })
})
