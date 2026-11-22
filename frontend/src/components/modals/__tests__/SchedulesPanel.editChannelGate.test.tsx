// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * 编辑弹窗与新建弹窗共用同一渠道判据（modeNeedsChannel）。
 *
 * <p>WHY：同一规则不能「新建拦、编辑只靠后端 400」—— 用户会在编辑时才撞墙。
 */
const listInstances = vi.fn(async () => [])
const listChats = vi.fn(async () => [])
vi.mock('@/api/im', () => ({
  imApi: { listInstances: () => listInstances(), listChats: () => listChats() },
}))
vi.mock('@/api/sessions', () => ({ sessionApi: { list: async () => [] } }))
vi.mock('@/api/projects', () => ({ projectApi: { list: async () => [] } }))
vi.mock('@/api/agent', () => ({ agentApi: { list: async () => [], listAgents: async () => [] } }))

import { ScheduleEditModal } from '../SchedulesPanel'
import type { Schedule } from '@/api/types'

function schedule(mode: 'REUSE' | 'NEW_EACH' | 'NEW_ONCE' | 'NONE', chats: string[]): Schedule {
  return {
    id: 'sch-1',
    name: '测试任务',
    kind: 'cron',
    cron: '0 9 * * *',
    intervalSeconds: null,
    runAt: null,
    command: '跑一下',
    description: '',
    sessionMode: mode,
    sessionId: null,
    notifyChats: chats,
    notifyChannelIds: [],
    mainThreadAgent: null,
    enabled: true,
    lastRunAt: null,
    lastRunStatus: null,
  } as unknown as Schedule
}

describe('编辑弹窗渠道前置', () => {
  let container: HTMLDivElement
  let root: Root | null = null

  beforeEach(() => {
    container = document.createElement('div')
    document.body.appendChild(container)
  })

  afterEach(() => {
    act(() => root?.unmount())
    container.remove()
    root = null
    vi.clearAllMocks()
  })

  const renderEdit = async (s: Schedule, onSave: (v: unknown) => void) => {
    root = createRoot(container)
    await act(async () => {
      root!.render(
        <ScheduleEditModal schedule={s} onSave={onSave} onCancel={() => {}} onRemove={() => {}} />,
      )
    })
  }

  const clickSave = async () => {
    const btn = Array.from(container.querySelectorAll('button')).find(
      (b) => b.textContent?.trim() === '保存',
    ) as HTMLButtonElement
    expect(btn).toBeTruthy()
    await act(async () => { btn.dispatchEvent(new MouseEvent('click', { bubbles: true })) })
  }

  it('需要渠道但没勾 ⇒ 保存被拦 + 行内提示', async () => {
    const onSave = vi.fn()
    await renderEdit(schedule('NEW_EACH', []), onSave)
    await clickSave()
    expect(onSave).not.toHaveBeenCalled()
    expect(container.textContent ?? '').toContain('需先配置渠道')
  })

  it('已勾渠道 ⇒ 正常保存', async () => {
    const onSave = vi.fn()
    await renderEdit(schedule('NEW_EACH', ['imch-1']), onSave)
    await clickSave()
    expect(onSave).toHaveBeenCalledTimes(1)
  })

  it('从需要渠道改成不需要 ⇒ 正常保存', async () => {
    const onSave = vi.fn()
    await renderEdit(schedule('REUSE', []), onSave)
    await clickSave()
    expect(onSave).toHaveBeenCalledTimes(1)
  })
})
