// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { EnvConfigPanel } from '../EnvConfigPanel'
import type { AppSettings, UpdateSettingsRequest } from '@/api/types'

/**
 * [V333 AFK] 「权限与协作」段的提问卡片超时档位。
 *
 * <p>WHY：这是该能力唯一的用户入口，且默认必须是「从不」（= 用户说的关）。
 * 任何一条不满足都是产品事故：
 * <ol>
 *   <li>GET 返回 null（老数据/未配置）⇒ 必须显示「从不」，不能显示某个时长；</li>
 *   <li>选中档位必须落到 {@code askUserAfkTimeout} 字段（值是 CC 的 60s/5m/10m/never，
 *       不是毫秒数、不是布尔）；</li>
 *   <li>选完立即发一次 PUT，界面以服务端回灌为准（不做本地乐观假值）。</li>
 * </ol>
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

describe('EnvConfigPanel · 提问卡片超时（V333 ask_user_afk_timeout）', () => {
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
    const el = container.querySelector('[data-testid="ask-afk-pill"]')
    expect(el, '提问卡片超时 pill 必须渲染在「权限与协作」段').toBeTruthy()
    return el as HTMLElement
  }

  function valueText(): string {
    return (pill().querySelector('.pm-value')?.textContent ?? '').trim()
  }

  it('未配置（null）⇒ 显示「从不」（默认不自动继续）', async () => {
    await render(baseSettings(), async () => true)
    expect(valueText()).toBe('从不')
  })

  it('选「5 分钟」⇒ 发 askUserAfkTimeout=5m（CC 档位字面值，不是毫秒）', async () => {
    const onSave = vi.fn(async (_req: UpdateSettingsRequest) => true)
    await render(baseSettings({ askUserAfkTimeout: 'never' }), onSave)

    act(() => { pill().click() })
    const option = container.querySelector('[data-testid="ask-afk-option-5m"]') as HTMLElement
    expect(option, '抽屉里必须有 5 分钟档').toBeTruthy()
    act(() => { option.click() })

    expect(onSave).toHaveBeenCalledTimes(1)
    expect(onSave.mock.calls[0][0]).toEqual({ askUserAfkTimeout: '5m' })
  })

  it('已有值 ⇒ 显示对应档位名', async () => {
    await render(baseSettings({ askUserAfkTimeout: '10m' }), async () => true)
    expect(valueText()).toBe('10 分钟')
  })
})
