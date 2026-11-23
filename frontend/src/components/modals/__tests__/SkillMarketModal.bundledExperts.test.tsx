// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/rest'
import { SkillMarketModal } from '../SkillMarketModal'

/**
 * 专家 tab → 「内置专家」子 tab（272 个随包专家，按需安装）。
 *
 * <p><b>WHY</b>：
 * <ul>
 *   <li><b>惰性</b>：默认停在市场，不能给所有打开弹窗的用户额外发一次 272 条目录请求；</li>
 *   <li><b>搜索</b>：272 条不可能靠滚动找，顶部搜索必须能过滤名称/描述/分类；</li>
 *   <li><b>安装 = 真调用</b>：点安装必须打后端；只有响应成功才显示「已安装」，409 不伪造成功。</li>
 * </ul>
 */
const {
  listAgentsMock, listInstalledMock, listInstalledDetailsMock, listCommandsMock, listExternalMock,
  installMock, getProgressMock, listBundledMock, installBundledMock,
} = vi.hoisted(() => ({
  listAgentsMock: vi.fn(),
  listInstalledMock: vi.fn(),
  listInstalledDetailsMock: vi.fn(),
  listCommandsMock: vi.fn(),
  listExternalMock: vi.fn(),
  installMock: vi.fn(),
  getProgressMock: vi.fn(),
  listBundledMock: vi.fn(),
  installBundledMock: vi.fn(),
}))

vi.mock('@/api/agent', () => ({ agentApi: { listAgents: listAgentsMock } }))
vi.mock('@/api/market', () => ({
  marketApi: {
    listInstalled: listInstalledMock,
    listInstalledDetails: listInstalledDetailsMock,
    listExternal: listExternalMock,
    installPlugin: installMock,
    getInstallProgress: getProgressMock,
  },
}))
vi.mock('@/api/command', () => ({ commandApi: { list: listCommandsMock } }))
vi.mock('@/api/experts', () => ({
  expertsApi: { listBundled: listBundledMock, installBundled: installBundledMock },
}))
vi.mock('@/api/rest', () => ({
  ApiError: class ApiError extends Error {
    status: number
    constructor(message: string, init?: { status?: number }) {
      super(message)
      this.status = init?.status ?? 0
    }
    userMessage() { return this.message }
  },
}))

const MARKET = { name: 'zjky-market', plugins: [] }

const BUNDLED = [
  { slug: 'engineering-backend-architect', division: 'engineering', name: '后端架构师', description: '可扩展系统设计与 API 架构', emoji: '🏗️', color: 'blue', installed: false },
  { slug: 'security-appsec-engineer', division: 'security', name: '应用安全工程师', description: '威胁建模与安全代码审查', emoji: '🔐', color: '#059669', installed: true },
  { slug: 'marketing-seo-specialist', division: 'marketing', name: 'SEO 专家', description: '技术 SEO 与内容优化', emoji: null, color: null, installed: false },
]

function noop(): void {}

async function flush(rounds = 12) {
  for (let i = 0; i < rounds; i++) {
    await act(async () => { await Promise.resolve() })
  }
}

let container: HTMLDivElement | undefined
let root: Root | undefined
let toast: (msg: string, type?: 'success' | 'info') => void

beforeEach(() => {
  ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  toast = vi.fn()
  listAgentsMock.mockReset(); listAgentsMock.mockResolvedValue([])
  listInstalledMock.mockReset(); listInstalledMock.mockResolvedValue([])
  listInstalledDetailsMock.mockReset(); listInstalledDetailsMock.mockResolvedValue([])
  listCommandsMock.mockReset(); listCommandsMock.mockResolvedValue([])
  listExternalMock.mockReset(); listExternalMock.mockResolvedValue(MARKET)
  installMock.mockReset(); installMock.mockResolvedValue({ success: true })
  getProgressMock.mockReset(); getProgressMock.mockResolvedValue(undefined)
  listBundledMock.mockReset(); listBundledMock.mockResolvedValue(BUNDLED)
  installBundledMock.mockReset()
})

afterEach(() => {
  if (root) act(() => root!.unmount())
  container?.remove()
})

function renderModal() {
  act(() => {
    root!.render(
      <SkillMarketModal
        sessionId="sess-bundled"
        currentAgent={null}
        onClose={noop}
        onUseLocalAgent={noop}
        onSummonSkill={noop}
        showToast={toast}
      />,
    )
  })
}

function clickButton(label: string) {
  const btn = Array.from(container!.querySelectorAll('button'))
    .find((b) => (b.textContent ?? '').trim() === label)
  expect(btn, `按钮「${label}」应存在`).toBeTruthy()
  act(() => btn!.dispatchEvent(new MouseEvent('click', { bubbles: true })))
}

async function openBuiltinTab() {
  renderModal()
  await flush()
  const btn = Array.from(container!.querySelectorAll('button'))
    .find((b) => (b.textContent ?? '').includes('内置专家'))
  expect(btn, '内置专家子 tab 应可见').toBeTruthy()
  act(() => btn!.dispatchEvent(new MouseEvent('click', { bubbles: true })))
  await flush()
}

describe('[bundled-experts] 专家 tab 内置专家', () => {
  it('默认不在市场页请求内置目录；切到内置专家后才加载并展示列表/分类徽章', async () => {
    renderModal()
    await flush()
    expect(listBundledMock).not.toHaveBeenCalled()

    await openBuiltinTab()
    expect(listBundledMock).toHaveBeenCalledTimes(1)

    const text = container!.textContent ?? ''
    expect(text).toContain('后端架构师')
    expect(text).toContain('应用安全工程师')
    expect(text).toContain('工程')
    expect(text).toContain('安全')
    expect(text).toContain('已安装')
    expect(text).toContain('3 位内置专家')
  })

  it('搜索按名称/描述/分类过滤', async () => {
    await openBuiltinTab()
    const input = container!.querySelector('.sm-search-pill input') as HTMLInputElement
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!
    act(() => {
      setter.call(input, '安全')
      input.dispatchEvent(new Event('input', { bubbles: true }))
    })
    await flush()

    const text = container!.textContent ?? ''
    expect(text).toContain('应用安全工程师')
    expect(text).not.toContain('后端架构师')
  })

  it('点击安装调用后端；成功后显示已安装', async () => {
    installBundledMock.mockResolvedValue({ status: 'INSTALLED', slug: 'engineering-backend-architect', path: 'x' })
    await openBuiltinTab()
    clickButton('安装')
    await flush()

    expect(installBundledMock).toHaveBeenCalledWith('engineering-backend-architect')
    expect(toast).toHaveBeenCalledWith('已安装专家 后端架构师', 'success')
    const text = container!.textContent ?? ''
    expect(text).toContain('已安装')
    expect(text).not.toContain('安装中…')
  })

  it('409 冲突如实提示，不伪造成功', async () => {
    installBundledMock.mockRejectedValue(new ApiError('conflict', { status: 409 }))
    await openBuiltinTab()

    // 第一个未安装条目 = 后端架构师
    clickButton('安装')
    await flush()

    expect(installBundledMock).toHaveBeenCalledWith('engineering-backend-architect')
    expect(toast).toHaveBeenCalledWith(
      '该专家已存在且本地内容与内置版本不同；如确认覆盖，请先移走本地文件或带 overwrite 重新安装',
      'info',
    )
    // 仍然有「安装」按钮（说明没有本地乐观置位成已安装）
    const installButtons = Array.from(container!.querySelectorAll('button'))
      .filter((b) => (b.textContent ?? '').trim() === '安装')
    expect(installButtons.length).toBeGreaterThan(0)
  })
})
