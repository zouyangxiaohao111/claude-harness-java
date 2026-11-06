// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SkillMarketModal } from '../SkillMarketModal'

/**
 * 「使用本地专家」与对话不互斥（用户 2026-11-06：「应该和技能一样 没点发送都是可以点击的」）。
 *
 * <p><b>WHY 这条必须单独钉住</b>：使用专家只 `PATCH sessions.main_thread_agent` ——
 * 纯写会话字段，不发消息、不开轮；而后端在**每轮开头**读一次该列
 * （`LlmAgentLoop.collectRunMaterial` 的 SP-03 段）⇒ 切换对正在跑的那一轮无影响，
 * 从下一轮起生效。busy 互斥在这里既没有必要，还会让用户在等一轮跑完时无法预先设定。
 *
 * <p>⚠️ 反向约束：市场专家的「使用」实际是**安装插件**（写磁盘 + reconcile），
 * 属安装语义，仍由 `installMarketPlugin` 自身的 busy 门拦截 —— 本文件不覆盖那条路径。
 *
 * <p>⚠️ 本机专家卡**只在「已安装」筛选打开时**才并入列表（2026-11-01 用户要求
 * 「市场默认展示」），所以用例必须先点 `.sm-installed-pill`。
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
  listAgentsMock.mockReset()
  listAgentsMock.mockResolvedValue([
    { agentType: 'local-expert', source: 'user', whenToUse: '本地专家演示', color: null, model: null },
  ])
  listInstalledMock.mockReset(); listInstalledMock.mockResolvedValue([])
  listInstalledDetailsMock.mockReset(); listInstalledDetailsMock.mockResolvedValue([])
  listCommandsMock.mockReset(); listCommandsMock.mockResolvedValue([])
})

afterEach(() => {
  if (root) act(() => root!.unmount())
  container?.remove()
})

describe('SkillMarketModal · 使用本地专家不因对话进行中被互斥', () => {
  it('busy=true 时点本地专家的「使用」仍回调 onUseLocalAgent，且不弹「对话进行中」', async () => {
    const onUse = vi.fn()
    const showToast = vi.fn()
    act(() => {
      root!.render(
        <SkillMarketModal
          sessionId="sess-busy"
          currentAgent={null}
          busy={true}
          onClose={noop}
          onUseLocalAgent={onUse}
          onSummonSkill={noop}
          showToast={showToast}
        />,
      )
    })
    await flush()

    // 打开「已安装」筛选（本机专家卡只在这时才并入列表）
    const installedPill = container!.querySelector('.sm-installed-pill') as HTMLElement | null
    expect(installedPill, '未找到「已安装」筛选按钮').toBeTruthy()
    act(() => { installedPill!.click() })
    await flush()

    const useBtn = container!.querySelector('.sm-use-btn') as HTMLButtonElement | null
    expect(useBtn, '未找到本地专家的「使用」按钮').toBeTruthy()
    // 灰态类不能出现 —— 否则用户看到的就是「不能点」
    expect(useBtn!.classList.contains('disabled'), 'busy 时不应渲染 disabled 灰态').toBe(false)

    act(() => { useBtn!.click() })
    await flush()

    expect(onUse, 'busy=true 时使用本地专家仍要生效').toHaveBeenCalledWith('local-expert')
    expect(
      showToast.mock.calls.some((c) => String(c[0]).includes('对话进行中')),
      '不得再弹「对话进行中」拦截提示',
    ).toBe(false)
  })
})
