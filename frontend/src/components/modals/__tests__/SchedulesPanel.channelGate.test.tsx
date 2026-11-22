// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * 三类执行形态的渠道前置（与后端 ScheduleService.validateChannelPrecondition 同口径）。
 *
 * <p>WHY：无头 / 新会话执行没有「人正开着会话」可应答，结果只能靠渠道送达；
 * 前端若允许不选渠道就保存，用户会得到一个「保存成功但执行时无处投递」的任务。
 */
const listInstances = vi.fn(async () => [])
const listChats = vi.fn(async () => [])
vi.mock('@/api/im', () => ({
  imApi: {
    listInstances: () => listInstances(),
    listChats: () => listChats(),
  },
}))
vi.mock('@/api/sessions', () => ({ sessionApi: { list: async () => [] } }))
vi.mock('@/api/projects', () => ({ projectApi: { list: async () => [] } }))
vi.mock('@/api/agent', () => ({
  agentApi: {
    list: async () => [],
    listAgents: async () => [],
  },
}))

import { ScheduleAddModal, modeNeedsChannel } from '../SchedulesPanel'

describe('调度形态渠道前置', () => {
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

  it('B/C/D 需要渠道，A 不需要', () => {
    expect(modeNeedsChannel('NEW_EACH')).toBe(true)
    expect(modeNeedsChannel('NEW_ONCE')).toBe(true)
    expect(modeNeedsChannel('NONE')).toBe(true)
    expect(modeNeedsChannel('REUSE')).toBe(false)
  })

  it('选「每次触发都新建」但没选渠道 ⇒ 保存被拦下并提示', async () => {
    const onSave = vi.fn(async () => {})
    root = createRoot(container)
    await act(async () => {
      root!.render(
        <ScheduleAddModal kind="cron" onChangeKind={() => {}} onSave={onSave} onCancel={() => {}} />,
      )
    })

    const modeCard = Array.from(container.querySelectorAll('.fm-radio-item')).find((n) =>
      (n.textContent ?? '').includes('每次触发都新建'),
    ) as HTMLElement | undefined
    expect(modeCard).toBeTruthy()
    act(() => { modeCard!.dispatchEvent(new MouseEvent('click', { bubbles: true })) })

    const nameInput = container.querySelector('input.fm-input') as HTMLInputElement
    const textarea = container.querySelector('textarea.fm-textarea') as HTMLTextAreaElement
    act(() => {
      nameInput.value = '测试任务'
      nameInput.dispatchEvent(new Event('input', { bubbles: true }))
      textarea.value = '跑一下'
      textarea.dispatchEvent(new Event('input', { bubbles: true }))
    })

    const saveBtn = Array.from(container.querySelectorAll('button')).find(
      (b) => b.textContent?.trim() === '保存',
    ) as HTMLButtonElement | undefined
    expect(saveBtn).toBeTruthy()
    await act(async () => { saveBtn!.dispatchEvent(new MouseEvent('click', { bubbles: true })) })

    expect(onSave).not.toHaveBeenCalled()
    expect(container.textContent ?? '').toContain('需先配置渠道')
  })
})
