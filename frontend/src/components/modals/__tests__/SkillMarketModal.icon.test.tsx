// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SkillMarketModal } from '../SkillMarketModal'

const mocks = vi.hoisted(() => ({
  listAgents: vi.fn(), listInstalled: vi.fn(), listInstalledDetails: vi.fn(),
  listCommands: vi.fn(), listExternal: vi.fn(), install: vi.fn(), progress: vi.fn(),
}))

vi.mock('@/api/agent', () => ({ agentApi: { listAgents: mocks.listAgents } }))
vi.mock('@/api/market', () => ({ marketApi: {
  listInstalled: mocks.listInstalled, listInstalledDetails: mocks.listInstalledDetails,
  listExternal: mocks.listExternal, installPlugin: mocks.install, getInstallProgress: mocks.progress,
}}))
vi.mock('@/api/command', () => ({ commandApi: { list: mocks.listCommands } }))
vi.mock('@/api/rest', () => ({ ApiError: class ApiError extends Error {} }))

const MARKET = { name: 'zjky-market', plugins: [
  { name: 'with-icon', displayName: 'WithIcon', description: 'd', version: '1.0.0', category: 'skill', tags: [], icon: 'http://m/i.svg' },
  { name: 'no-icon', displayName: 'NoIcon', description: 'd', version: '1.0.0', category: 'skill', tags: [] },
]}

function noop(): void {}
async function flush(n = 12) { for (let i = 0; i < n; i++) await act(async () => { await Promise.resolve() }) }
let root: Root | undefined
let container: HTMLDivElement | undefined

beforeEach(() => {
  (globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
  container = document.createElement('div'); document.body.appendChild(container)
  root = createRoot(container)
  mocks.listAgents.mockResolvedValue([]); mocks.listInstalled.mockResolvedValue([])
  mocks.listInstalledDetails.mockResolvedValue([]); mocks.listCommands.mockResolvedValue([])
  mocks.listExternal.mockResolvedValue(MARKET); mocks.progress.mockResolvedValue(undefined)
})
afterEach(() => { if (root) act(() => root!.unmount()); container?.remove() })

async function openSkillTab() {
  act(() => { root!.render(<SkillMarketModal sessionId="s" currentAgent={null} busy={false} onClose={noop} onUseLocalAgent={noop} onSummonSkill={noop} showToast={noop} />) })
  await flush()
  const tab = Array.from(container!.querySelectorAll('.sm-tab-flat')).find((e) => (e.textContent ?? '').includes('技能')) as HTMLElement
  act(() => { tab.click() }); await flush()
}

function card(name: string): HTMLElement {
  return Array.from(container!.querySelectorAll('.sk-card') as NodeListOf<HTMLElement>)
    .find((el) => (el.textContent ?? '').includes(name))!
}

describe('SkillMarketModal icon', () => {
  it('renders market icon when provided', async () => {
    await openSkillTab()
    const img = card('WithIcon').querySelector('.sk-ico img') as HTMLImageElement
    expect(img).toBeTruthy()
    expect(img.src).toContain('http://m/i.svg')
  })

  it('falls back to initial when no icon', async () => {
    await openSkillTab()
    const ico = card('NoIcon').querySelector('.sk-ico')!
    expect(ico.querySelector('img')).toBeNull()
    expect(ico.textContent).toBe('N')
  })

  it('falls back after image load error', async () => {
    await openSkillTab()
    const img = card('WithIcon').querySelector('.sk-ico img')!
    act(() => { img.dispatchEvent(new Event('error', { bubbles: false })) })
    await flush()
    const ico = card('WithIcon').querySelector('.sk-ico')!
    expect(ico.querySelector('img')).toBeNull()
    expect(ico.textContent).toBe('W')
  })
})
