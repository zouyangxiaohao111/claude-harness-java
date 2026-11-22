// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { SessionToolDto } from '@/api/types'

/**
 * [Q8] 无人值守开启时关「回答直达」（SendUserMessage）：
 * 先确认，确认后**一次请求**同时关 Brief + 退出无人值守（⛔ 不拆两个请求）。
 *
 * <p>WHY：用户确认的是「两件事一起关」。若只关 Brief，无人值守仍在，消息会继续走
 * 无人值守链路；若拆两个请求，第二个失败会留下半状态。
 *
 * <p>变异判据：
 * <ul>
 *   <li>去掉确认闸门（直接 setBrief）⇒ 用例 1「取消不发请求」红；</li>
 *   <li>确认后只调 setBrief(sid,false)（不传 cascade）⇒ 用例 2「一次请求带 cascade」红。</li>
 * </ul>
 */

const listTools = vi.fn()
const setBrief = vi.fn()
const setToolEnabled = vi.fn()
const setCategoryEnabled = vi.fn()

vi.mock('@/api/sessions', () => ({
  sessionApi: {
    listTools: (...a: unknown[]) => listTools(...a),
    setBrief: (...a: unknown[]) => setBrief(...a),
    setToolEnabled: (...a: unknown[]) => setToolEnabled(...a),
    setCategoryEnabled: (...a: unknown[]) => setCategoryEnabled(...a),
  },
}))

vi.mock('@/api/rest', () => ({
  ApiError: class ApiError extends Error {
    userMessage() { return this.message }
  },
}))

import { SessionToolsPanel } from '@/components/center/SessionToolsPanel'

let container: HTMLDivElement
let root: Root

function t(name: string, category: SessionToolDto['category'], disabled = false): SessionToolDto {
  return { name, userFacingName: name, category, description: `${name} description`, disabled }
}

const FIXTURE: SessionToolDto[] = [
  t('mcp__computer-use__screenshot', 'CU', true),
  t('SendImAsUser', 'IM', false),
  t('ListImChats', 'IM', false),
  t('SendUserMessage', 'IM', false),   // 当前可用 ⇒ 点按钮 = 关闭
  t('Bash', 'SYS', false),
]

async function mount(props: Partial<React.ComponentProps<typeof SessionToolsPanel>> = {}) {
  await act(async () => {
    root.render(
      <SessionToolsPanel
        sessionId="sess-1"
        showToast={() => {}}
        onClose={() => {}}
        {...props}
      />,
    )
  })
  await act(async () => { await Promise.resolve() })
}

async function openImAndToggleBriefOff() {
  // 6 类有序；本 fixture 只有 CU/IM/SYS，IM 是第 2 项（index 1）
  const groups = container.querySelectorAll('.l1-item')
  const imGroup = Array.from(groups).find((g) => g.textContent?.includes('IM 工具')) as HTMLElement
  await act(async () => { imGroup.click() })
  const row = Array.from(container.querySelectorAll('.tool-row'))
    .find((r) => r.querySelector('.tool-key')?.textContent === 'SendUserMessage')!
  expect(row, '应能找到 SendUserMessage 行').toBeTruthy()
  await act(async () => { (row.querySelector('.toggle-btn') as HTMLButtonElement).click() })
}

beforeEach(() => {
  (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  listTools.mockReset().mockResolvedValue(FIXTURE)
  setBrief.mockReset().mockResolvedValue({ sessionId: 'sess-1', unattended: false, brief: false, channels: 1, canEnableUnattended: true })
  setToolEnabled.mockReset()
  setCategoryEnabled.mockReset().mockResolvedValue(FIXTURE)
})

afterEach(() => {
  act(() => root.unmount())
  container.remove()
})

describe('[Q8] 关回答直达的确认与原子级联', () => {
  it('无人值守开启：先弹确认；点「取消」⇒ 不发任何请求、状态不变', async () => {
    await mount({ unattended: true })
    await openImAndToggleBriefOff()

    const dialog = container.querySelector('[data-testid="brief-off-confirm"]')
    expect(dialog, '无人值守开启时关 Brief 必须先确认').toBeTruthy()
    expect(setBrief).not.toHaveBeenCalled()

    const cancel = Array.from(dialog!.querySelectorAll('button'))
      .find((b) => b.textContent === '取消') as HTMLButtonElement
    await act(async () => { cancel.click() })

    expect(container.querySelector('[data-testid="brief-off-confirm"]')).toBeNull()
    expect(setBrief, '取消不得发请求').not.toHaveBeenCalled()
  })

  it('无人值守开启：点「确认关闭」⇒ 只发一次请求且带 cascadeExitUnattended=true', async () => {
    const onModeChanged = vi.fn()
    await mount({ unattended: true, onModeChanged })
    await openImAndToggleBriefOff()

    const dialog = container.querySelector('[data-testid="brief-off-confirm"]')!
    const confirm = Array.from(dialog.querySelectorAll('button'))
      .find((b) => b.textContent === '确认关闭') as HTMLButtonElement
    await act(async () => { confirm.click() })
    await act(async () => { await Promise.resolve() })

    expect(setBrief).toHaveBeenCalledTimes(1)
    expect(setBrief.mock.calls[0]).toEqual(['sess-1', false, true])
    expect(onModeChanged).toHaveBeenCalled()
  })

  it('无人值守关闭：不弹确认、直接生效（不能打扰）', async () => {
    await mount({ unattended: false })
    await openImAndToggleBriefOff()

    expect(container.querySelector('[data-testid="brief-off-confirm"]')).toBeNull()
    await act(async () => { await Promise.resolve() })
    expect(setBrief).toHaveBeenCalledTimes(1)
    expect(setBrief.mock.calls[0]).toEqual(['sess-1', false])
  })
})
