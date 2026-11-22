// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { EnvConfigPanel } from '../EnvConfigPanel'
import type { AppSettings, UpdateSettingsRequest } from '@/api/types'

/**
 * [SendMessage 跨会话 · E 设置持久化] 「权限与协作」段的跨会话入站策略。
 *
 * <p>WHY：这一行是策略唯一的用户入口，三态（自动/接受/待确认/拒绝）都必须落到
 * {@code crossSessionInbound} 字段上；任何一个值被静默丢弃，用户就会以为策略改了、
 * 实际仍按 default 跑。另两条同样重要的意图：
 * <ul>
 *   <li>GET 返回 {@code null}（未设置）时<b>显示自动</b>，且不把运行时推导出的 accept 回写；</li>
 *   <li>点「自动」发的是 {@code 'auto'} <b>哨兵</b>（后端据此清列回 NULL），
 *       保存后界面只认服务端回灌的 settings（真实读回），不做本地乐观假值。</li>
 * </ul>
 */
vi.mock('@/api/memory', () => ({
  getMemoryConfig: () => Promise.resolve({
    autoMemoryEnabled: false, autoDreamEnabled: false,
    dreamStatus: null, lastConsolidatedAtMs: null,
  }),
  updateMemoryConfig: () => Promise.resolve({}),
}))

function baseSettings(over: Partial<AppSettings> = {}): AppSettings {
  return {
    theme: null, fontSize: null, accent: null, animationsEnabled: null,
    mainModelName: null, fastModelName: null, weakModelName: null, mediumModelName: null,
    strongModelName: null, subagentModelName: null, autoCompactWindow: null,
    fallbackModelName: null, multimodalModelName: null,
    ttsModelName: null, asrModelName: null, autoMemoryEnabled: null, autoMemoryDirectory: null,
    ...over,
  }
}

describe('EnvConfigPanel · 跨会话入站策略（V321 cross_session_inbound）', () => {
  let container: HTMLDivElement
  let root: Root

  beforeEach(() => {
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
  })

  afterEach(() => {
    act(() => root.unmount())
    container.remove()
    vi.restoreAllMocks()
  })

  async function render(settings: AppSettings | null, onSave: (req: UpdateSettingsRequest) => Promise<boolean>) {
    await act(async () => {
      root.render(<EnvConfigPanel settings={settings} onOpenMemoryEditor={() => {}} onSaveSettings={onSave} />)
      await Promise.resolve()
    })
  }

  function pill(): HTMLElement {
    const el = container.querySelector('[data-testid="cross-session-inbound-pill"]')
    expect(el, '跨会话入站 pill 必须渲染').toBeTruthy()
    return el as HTMLElement
  }

  function valueText(): string {
    return (pill().querySelector('.pm-value')?.textContent ?? '').trim()
  }

  async function openDrawer() {
    await act(async () => { pill().click() })
  }

  async function clickOption(v: 'auto' | 'accept' | 'hold' | 'refuse') {
    const el = container.querySelector(`[data-testid="cross-session-inbound-option-${v}"]`)
    expect(el, `选项 ${v} 必须渲染`).toBeTruthy()
    await act(async () => { (el as HTMLElement).click() })
  }

  it('位置：位于「默认权限模式」与「Team 协作」之间', async () => {
    await render(baseSettings(), async () => true)
    const names = Array.from(container.querySelectorAll('.envc-name'))
      .map((n) => n.textContent?.trim() ?? '')
      .filter((n) => n.startsWith('默认权限模式') || n.startsWith('跨会话入站') || n.startsWith('Team 协作'))
    expect(names).toEqual(['默认权限模式', '跨会话入站', 'Team 协作 swarms'])
  })

  it('settings.crossSessionInbound = null/undefined ⇒ 显示「自动（按权限模式）」，且挂载时不写库', async () => {
    const onSave = vi.fn<(req: UpdateSettingsRequest) => Promise<boolean>>(async () => true)
    await render(baseSettings(), onSave)

    expect(valueText()).toBe('自动（按权限模式）')
    // 值不被推导成 accept 回写：渲染本身不得触发任何保存
    expect(onSave).not.toHaveBeenCalled()
  })

  it.each([
    ['accept', '接受'],
    ['hold', '待确认'],
    ['refuse', '拒绝'],
  ] as const)('settings.crossSessionInbound = %s ⇒ 显示「%s」', async (value, label) => {
    await render(baseSettings({ crossSessionInbound: value }), async () => true)
    expect(valueText()).toBe(label)
  })

  it('选中「待确认」⇒ 发送 { crossSessionInbound: "hold" }；服务端回灌后显示待确认（真实读回）', async () => {
    const onSave = vi.fn<(req: UpdateSettingsRequest) => Promise<boolean>>(async () => true)
    await render(baseSettings({ crossSessionInbound: null }), onSave)

    await openDrawer()
    await clickOption('hold')

    expect(onSave).toHaveBeenCalledTimes(1)
    expect(onSave.mock.calls[0][0]).toEqual({ crossSessionInbound: 'hold' })

    // 模拟 App.onSaveSettings → PUT 响应回灌（组件只认 prop，不保留本地乐观值）
    await render(baseSettings({ crossSessionInbound: 'hold' }), onSave)
    expect(valueText()).toBe('待确认')
  })

  it('当前为 accept 时点「自动」⇒ 发送清除哨兵 "auto"；回灌 null 后显示自动', async () => {
    const onSave = vi.fn<(req: UpdateSettingsRequest) => Promise<boolean>>(async () => true)
    await render(baseSettings({ crossSessionInbound: 'accept' }), onSave)

    await openDrawer()
    await clickOption('auto')

    expect(onSave).toHaveBeenCalledTimes(1)
    expect(onSave.mock.calls[0][0]).toEqual({ crossSessionInbound: 'auto' })

    // 后端把列清成 NULL，GET/PUT 响应回灌 null ⇒ 仍显示「自动」
    await render(baseSettings({ crossSessionInbound: null }), onSave)
    expect(valueText()).toBe('自动（按权限模式）')
  })

  it('保存失败不改变显示来源：仍按传入的 settings 渲染（不做本地假生效）', async () => {
    const onSave = vi.fn<(req: UpdateSettingsRequest) => Promise<boolean>>(async () => false)
    await render(baseSettings({ crossSessionInbound: 'refuse' }), onSave)

    await openDrawer()
    await clickOption('accept')

    expect(onSave.mock.calls[0][0]).toEqual({ crossSessionInbound: 'accept' })
    expect(valueText()).toBe('拒绝')
  })
})
