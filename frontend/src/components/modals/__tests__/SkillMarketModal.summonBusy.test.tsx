// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SkillMarketModal } from '../SkillMarketModal'

/**
 * 召唤与对话不互斥（用户 2026-10-27 明确：「召唤跟对话是两码事不能互斥」）。
 *
 * <p>召唤只是把斜杠命令写进输入框，不发送、不切 agent；对话进行中（busy=true）
 * 也必须能点「召唤」。本用例钉住：busy=true 时点召唤，回调仍被调用。
 */
const { listAgentsMock, listInstalledMock, listInstalledDetailsMock, listCommandsMock } = vi.hoisted(() => ({
  listAgentsMock: vi.fn(),
  listInstalledMock: vi.fn(),
  listInstalledDetailsMock: vi.fn(),
  listCommandsMock: vi.fn(),
}))

vi.mock('@/api/agent', () => ({ agentApi: { listAgents: listAgentsMock } }))
vi.mock('@/api/market', () => ({
  marketApi: {
    listInstalled: listInstalledMock,
    listInstalledDetails: listInstalledDetailsMock,
    listExternal: vi.fn(async () => null),
    installPlugin: vi.fn(),
    getInstallProgress: vi.fn(async () => undefined),
  },
}))
vi.mock('@/api/command', () => ({ commandApi: { list: listCommandsMock } }))
vi.mock('@/api/rest', () => ({ ApiError: class ApiError extends Error {} }))

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
  listCommandsMock.mockReset(); listCommandsMock.mockResolvedValue([
    { id: 'c1', name: 'summon-skill', description: 'demo', source: 'USER', enabled: true },
  ])
})

afterEach(() => {
  if (root) act(() => root!.unmount())
  container?.remove()
})

describe('SkillMarketModal · 召唤不因对话进行中被互斥', () => {
  it('busy=true 时点「召唤」仍回调 onSummonSkill', async () => {
    const onSummon = vi.fn()
    act(() => {
      root!.render(
        <SkillMarketModal
          sessionId="sess-busy"
          currentAgent={null}
          busy={true}
          onClose={noop}
          onUseLocalAgent={noop}
          onSummonSkill={onSummon}
          showToast={noop}
        />,
      )
    })
    await flush()

    // 切到本地来源（本地技能自带「召唤」按钮）
    const sourceBtn = container!.querySelector('.sm-source-btn') as HTMLButtonElement
    expect(sourceBtn).toBeTruthy()
    act(() => { sourceBtn.click() })
    const local = Array.from(container!.querySelectorAll('.sm-source-item'))
      .find((el) => el.textContent === '本地') as HTMLElement | undefined
    expect(local).toBeTruthy()
    act(() => { local!.click() })
    await flush()

    // 技能 Tab（默认是专家 Tab）
    const skillTab = Array.from(container!.querySelectorAll('.sm-tab'))
      .find((el) => el.textContent === '技能') as HTMLElement | undefined
    expect(skillTab).toBeTruthy()
    act(() => { skillTab!.click() })
    await flush()

    const summon = container!.querySelector('.sm-summon-btn') as HTMLButtonElement
    expect(summon).toBeTruthy()
    act(() => { summon.click() })
    await flush()

    expect(onSummon).toHaveBeenCalledWith('summon-skill', 'summon-skill')
  })
})
