// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { EnvConfigPanel } from '../EnvConfigPanel'
import type { AppSettings, UpdateSettingsRequest } from '@/api/types'

/**
 * [V334] 「权限与协作」段的两个渠道门开关。
 *
 * <p>WHY：这是中继能力的唯一用户入口，默认必须显示「开」——
 * 用户裁定「两个开关默认打开，但都开不意味着开启推送，只有会话接入了渠道才行」。
 * 任何一条不满足都会让「默认开」变成谎言（用户看到关、或改了没落库）。
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

describe('EnvConfigPanel · 渠道两道门（V334）', () => {
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

  function sw(testId: string): HTMLInputElement {
    const el = container.querySelector(`[data-testid="${testId}"]`) as HTMLInputElement
    expect(el, `${testId} 必须渲染在「权限与协作」段`).toBeTruthy()
    return el
  }

  it('未配置（null）⇒ 两个开关都显示为开（默认开）', async () => {
    await render(baseSettings(), async () => true)
    expect(sw('channels-enabled-switch').checked).toBe(true)
    expect(sw('channel-permission-relay-switch').checked).toBe(true)
  })

  it('关掉权限中继 ⇒ 发 channelsPermissionRelayEnabled=false', async () => {
    const onSave = vi.fn(async (_req: UpdateSettingsRequest) => true)
    await render(baseSettings(), onSave)

    act(() => { sw('channel-permission-relay-switch').click() })

    expect(onSave).toHaveBeenCalledTimes(1)
    expect(onSave.mock.calls[0][0]).toEqual({ channelsPermissionRelayEnabled: false })
  })

  it('关掉渠道能力 ⇒ 发 channelsEnabled=false', async () => {
    const onSave = vi.fn(async (_req: UpdateSettingsRequest) => true)
    await render(baseSettings(), onSave)

    act(() => { sw('channels-enabled-switch').click() })

    expect(onSave).toHaveBeenCalledTimes(1)
    expect(onSave.mock.calls[0][0]).toEqual({ channelsEnabled: false })
  })

  it('已有值为 false ⇒ 显示为关', async () => {
    await render(baseSettings({ channelsEnabled: false, channelsPermissionRelayEnabled: false }), async () => true)
    expect(sw('channels-enabled-switch').checked).toBe(false)
    expect(sw('channel-permission-relay-switch').checked).toBe(false)
  })
})
