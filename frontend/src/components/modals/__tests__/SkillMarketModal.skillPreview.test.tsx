// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SkillMarketModal } from '../SkillMarketModal'

/**
 * 技能详情预览（SKILL.md 正文）+ 安装联动。
 *
 * <p>钉住：市场技能走 /market/skills/{name}/preview、本机走 /skills/{name}/content；
 * 404/失败必须显示可读错误 + 重试（⛔ 不显示假正文）；详情不再有假开关；安装后计数联动。
 */
const {
  listAgentsMock, listInstalledMock, listInstalledDetailsMock, listCommandsMock, listExternalMock,
  installMock, getProgressMock, skillPreviewMock, skillContentMock,
} = vi.hoisted(() => ({
  listAgentsMock: vi.fn(),
  listInstalledMock: vi.fn(),
  listInstalledDetailsMock: vi.fn(),
  listCommandsMock: vi.fn(),
  listExternalMock: vi.fn(),
  installMock: vi.fn(),
  getProgressMock: vi.fn(),
  skillPreviewMock: vi.fn(),
  skillContentMock: vi.fn(),
}))

vi.mock('@/api/agent', () => ({ agentApi: { listAgents: listAgentsMock } }))
vi.mock('@/api/market', () => ({
  marketApi: {
    listInstalled: listInstalledMock,
    listInstalledDetails: listInstalledDetailsMock,
    listExternal: listExternalMock,
    installPlugin: installMock,
    getInstallProgress: getProgressMock,
    uninstallPlugin: vi.fn(),
    publishPlugin: vi.fn(),
    skillPreview: skillPreviewMock,
  },
}))
vi.mock('@/api/skills', () => ({ skillApi: { content: skillContentMock } }))
vi.mock('@/api/command', () => ({ commandApi: { list: listCommandsMock } }))
vi.mock('@/api/connectors', () => ({
  connectorsApi: { list: vi.fn(async () => ({ connectors: [] })) },
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
vi.mock('@/utils/openExternal', () => ({ openExternalUrl: vi.fn(async () => true) }))

const SKILL = {
  name: 'sk1', displayName: '演示技能', description: '技能一句话', version: '1.0.0',
  fingerprint: 'fp-1', category: 'skill', tags: [],
}

function noop(): void {}

async function flush(rounds = 12) {
  for (let i = 0; i < rounds; i++) await act(async () => { await Promise.resolve() })
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
  listExternalMock.mockReset()
  listExternalMock.mockResolvedValue({ name: 'zjky-market', plugins: [SKILL] })
  installMock.mockReset(); installMock.mockResolvedValue({ success: true, pluginName: 'sk1' })
  getProgressMock.mockReset(); getProgressMock.mockResolvedValue(undefined)
  skillPreviewMock.mockReset()
  skillContentMock.mockReset()
})

afterEach(() => {
  if (root) act(() => root!.unmount())
  container?.remove()
})

async function openMarketSkillDetail() {
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
  const skillTab = Array.from(container!.querySelectorAll('.sm-tab-flat'))
    .find((el) => (el.textContent ?? '').includes('技能')) as HTMLElement
  act(() => { skillTab.click() })
  await flush()
  const card = container!.querySelector('.sk-card') as HTMLElement
  expect(card, '未找到技能卡').toBeTruthy()
  act(() => { card.click() })
  await flush()
}

describe('SkillMarketModal · 技能详情预览', () => {
  it('市场技能：渲染 SKILL.md Markdown；详情无假开关', async () => {
    skillPreviewMock.mockResolvedValue({ markdown: '# 标题\n\n技能**正文**内容', version: '1.0.0' })
    await openMarketSkillDetail()

    expect(skillPreviewMock).toHaveBeenCalledTimes(1)
    expect(skillPreviewMock.mock.calls[0][0]).toBe('sk1')
    const md = container!.querySelector('[data-testid="skill-preview-md"]')!
    expect(md.textContent).toContain('技能')
    expect(md.textContent).toContain('正文')
    expect(md.querySelector('h1')!.textContent).toContain('标题')
    // 假开关已删（详情里不再有 checkbox）
    expect(container!.querySelector('.dt-toggle input[type="checkbox"]')).toBeNull()
  })

  it('预览 404：显示可读错误 + 重试（不显示正文）', async () => {
    const { ApiError } = await import('@/api/rest')
    const err = new ApiError('not found', { status: 404 })
    skillPreviewMock.mockRejectedValueOnce(err)
    skillPreviewMock.mockResolvedValueOnce({ markdown: '# 恢复' })
    await openMarketSkillDetail()

    const box = container!.querySelector('[data-testid="skill-preview-error"]')!
    expect(box.textContent).toContain('HTTP 404')
    expect(container!.querySelector('[data-testid="skill-preview-md"]')).toBeNull()

    act(() => {
      ;(Array.from(box.querySelectorAll('button')).find((b) => (b.textContent ?? '').includes('重试')) as HTMLButtonElement).click()
    })
    await flush()
    expect(skillPreviewMock).toHaveBeenCalledTimes(2)
    expect(container!.querySelector('[data-testid="skill-preview-md"]')!.textContent).toContain('恢复')
  })

  it('安装按钮：点击真实安装并刷新列表（计数联动）', async () => {
    skillPreviewMock.mockResolvedValue({ markdown: '# 正文' })
    await openMarketSkillDetail()
    expect(listInstalledMock).toHaveBeenCalledTimes(1)

    const installBtn = container!.querySelector('.dt-actions .dt-try') as HTMLButtonElement
    expect(installBtn.textContent).toContain('安装')
    act(() => { installBtn.click() })
    await flush()

    expect(installMock).toHaveBeenCalledTimes(1)
    expect(installMock.mock.calls[0][0]).toMatchObject({ pluginId: 'sk1@zjky-market' })
    // 安装成功后重拉列表（listInstalled 第二次）
    expect(listInstalledMock.mock.calls.length).toBeGreaterThanOrEqual(2)
  })
})
