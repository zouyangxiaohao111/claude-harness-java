// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { EnvConfigPanel } from '../EnvConfigPanel'
import type { AppSettings, UpdateSettingsRequest } from '@/api/types'

/**
 * [coord-mode 2026-11-19 用户裁定] 协调者模式**不再有全局开关**，改为按会话激活
 * （输入框「协调者」chip 写 `sessions.coordinator_mode`）。
 *
 * <p>WHY 要锁住「不存在」：这个开关原先在设置页，一旦被误加回来，用户会再次面临
 * 「全局一开、所有会话都显示为开」的困惑——这与按会话激活的产品语义直接冲突。
 * 用例断言面板里根本没有这一行，而不是断言某个 checkbox 的值。
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

describe('EnvConfigPanel · 协调者模式全局开关已移除', () => {
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

  it('即使 settings 里 coordinatorModeEnabled=true，面板也不再渲染该行', async () => {
    await render(baseSettings({ coordinatorModeEnabled: true }), async () => true)
    const names = Array.from(container.querySelectorAll('.envc-name'))
      .map((n) => n.textContent?.trim() ?? '')
    expect(names.some((n) => n.startsWith('协调者模式'))).toBe(false)
  })
})
