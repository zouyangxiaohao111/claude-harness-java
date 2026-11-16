// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SkillMarketModal } from '../SkillMarketModal'

/**
 * 安装 / 更新与对话不互斥（用户 2026-11-15：「对话进行中更新技能出现不可用，提示对话进行中」）。
 *
 * <p>WHY：安装只是下载解包 + 刷注册表，不发消息、不切轮；正在跑的那一轮已固化技能定义，
 * 刷新对它是安全的。此前 installMarketPlugin 首行有 busy 门，导致对话进行中「更新」被拒。
 * 与「召唤」「使用本地专家」同一条裁定：操作 ≠ 发送。本用例钉住 busy=true 时两条路径都放行。
 */
const { listAgentsMock, listInstalledMock, listInstalledDetailsMock, listCommandsMock, listExternalMock, installMock, getProgressMock } =
  vi.hoisted(() => ({
    listAgentsMock: vi.fn(),
    listInstalledMock: vi.fn(),
    listInstalledDetailsMock: vi.fn(),
    listCommandsMock: vi.fn(),
    listExternalMock: vi.fn(),
    installMock: vi.fn(),
    getProgressMock: vi.fn(),
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
vi.mock('@/api/rest', () => ({ ApiError: class ApiError extends Error {} }))

const MARKET = {
  name: 'zjky-market',
  plugins: [{
    name: 'demo-skill', displayName: '演示技能', description: 'demo', version: '1.0.0',
    fingerprint: 'sha256:new', category: 'skill', tags: [],
  }],
}

function noop(): void {}

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
  installMock.mockReset(); installMock.mockResolvedValue({ success: true, pluginName: 'demo-skill' })
  getProgressMock.mockReset(); getProgressMock.mockResolvedValue(undefined)
})

afterEach(() => {
  if (root) act(() => root!.unmount())
  container?.remove()
})

/** 打开技能 tab（默认专家），拿到市场条目的操作按钮。 */
async function openSkillTab() {
  act(() => {
    root!.render(
      <SkillMarketModal
        sessionId="sess-busy"
        currentAgent={null}
        busy={true}
        onClose={noop}
        onUseLocalAgent={noop}
        onSummonSkill={noop}
        showToast={noop}
      />,
    )
  })
  await flush()
  const skillTab = Array.from(container!.querySelectorAll('.sm-tab-flat'))
    .find((el) => (el.textContent ?? '').includes('技能')) as HTMLElement | undefined
  expect(skillTab).toBeTruthy()
  act(() => { skillTab!.click() })
  await flush()
}

describe('SkillMarketModal · 安装/更新不因对话进行中被互斥', () => {
  it('busy=true 时点「安装」仍发起安装', async () => {
    await openSkillTab()
    const btn = container!.querySelector('.sk-summon') as HTMLButtonElement | null
    expect(btn, '未找到安装按钮').toBeTruthy()
    expect(btn!.textContent).toContain('安装')
    act(() => { btn!.click() })
    await flush()
    expect(installMock).toHaveBeenCalledTimes(1)
    expect(installMock.mock.calls[0][0]).toMatchObject({ pluginId: 'demo-skill@zjky-market', scope: 'user' })
  })

  it('busy=true 时已装旧版点「更新」仍发起覆盖安装', async () => {
    // 本地 0.9.0 / 旧指纹，市场 1.0.0 / 新指纹 ⇒ 判定可更新
    listInstalledMock.mockResolvedValue(['demo-skill'])
    listInstalledDetailsMock.mockResolvedValue([
      { name: 'demo-skill', version: '0.9.0', installPath: 'x', fingerprint: 'sha256:old' },
    ])
    await openSkillTab()
    const btn = container!.querySelector('.sk-summon') as HTMLButtonElement | null
    expect(btn, '未找到更新按钮').toBeTruthy()
    expect(btn!.textContent).toContain('更新')
    act(() => { btn!.click() })
    await flush()
    expect(installMock).toHaveBeenCalledTimes(1)
    expect(installMock.mock.calls[0][0]).toMatchObject({ pluginId: 'demo-skill@zjky-market' })
  })
})
