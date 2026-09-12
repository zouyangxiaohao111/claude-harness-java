// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { EnvConfigPanel } from '../EnvConfigPanel'
import type { AppSettings, UpdateSettingsRequest } from '@/api/types'

/**
 * [dtd-cfg] 「工具延迟加载公告用增量方式」开关 —— 前端可配置 + 保存链路。
 *
 * WHY（规则 9）：该开关决定 deferred 工具公告走「增量附件」还是「每轮全量清单」。
 * 此前前端完全没有这一项（AppSettings 无字段、面板无 UI）→ 后端 DB 列虽在但用户改不了。
 * 本用例锁死三件事：① 默认（未配置）关闭；② settings 带 true 时回填为开；③ 勾选后经
 * onSaveSettings 以 `deferredToolsDeltaEnabled` 键写回（后端 PUT /settings merge 生效）。
 *
 * 变异自证：把 checkbox 的 onChange 改成写别的键（或不写）→ 第 3 条断言红；把默认值改成
 * `?? true` → 第 1 条断言红。
 */

vi.mock('@/api/memory', () => ({
  getMemoryConfig: () => Promise.resolve({
    autoMemoryEnabled: false, autoDreamEnabled: false,
    dreamStatus: null, lastConsolidatedAtMs: null,
  }),
  updateMemoryConfig: () => Promise.resolve({}),
}))

const FIELD_NAME = '延迟加载公告用增量方式'

function baseSettings(over: Partial<AppSettings> = {}): AppSettings {
  return {
    theme: null, fontSize: null, accent: null, animationsEnabled: null,
    mainModelName: null, fastModelName: null, weakModelName: null, mediumModelName: null,
    strongModelName: null, subagentModelName: null, autoCompactWindow: null,
    maxOutputTokens: null, fallbackModelName: null, multimodalModelName: null,
    ttsModelName: null, asrModelName: null, autoMemoryEnabled: null, autoMemoryDirectory: null,
    ...over,
  }
}

describe('EnvConfigPanel · 工具延迟加载公告开关', () => {
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

  /** 面板里「延迟加载公告用增量方式」那一行的 checkbox。 */
  function deferredDeltaCheckbox(): HTMLInputElement {
    const rows = Array.from(container.querySelectorAll('.envc-row'))
    const row = rows.find((r) => r.querySelector('.envc-name')?.textContent === FIELD_NAME)
    if (!row) throw new Error(`未找到开关行：${FIELD_NAME}`)
    const box = row.querySelector('input[type="checkbox"]')
    if (!box) throw new Error('开关行内无 checkbox')
    return box as HTMLInputElement
  }

  async function render(settings: AppSettings | null, onSave: (req: UpdateSettingsRequest) => Promise<void>) {
    await act(async () => {
      root.render(<EnvConfigPanel settings={settings} onSaveSettings={onSave} onOpenMemoryEditor={() => {}} />)
      await Promise.resolve()
    })
  }

  it('settings 未配置该字段 → 默认关闭（checkbox 未勾选）', async () => {
    await render(baseSettings(), async () => {})
    expect(deferredDeltaCheckbox().checked).toBe(false)
  })

  it('settings.deferredToolsDeltaEnabled=true → 回填为开', async () => {
    await render(baseSettings({ deferredToolsDeltaEnabled: true }), async () => {})
    expect(deferredDeltaCheckbox().checked).toBe(true)
  })

  it('勾选 → 经 onSaveSettings 以 deferredToolsDeltaEnabled 键写回 true', async () => {
    const onSave = vi.fn(async (_req: UpdateSettingsRequest) => {})
    await render(baseSettings({ deferredToolsDeltaEnabled: false }), onSave)

    const box = deferredDeltaCheckbox()
    await act(async () => {
      box.click()
      await Promise.resolve()
    })

    expect(onSave).toHaveBeenCalledTimes(1)
    expect(onSave.mock.calls[0][0]).toEqual({ deferredToolsDeltaEnabled: true })
  })

  it('取消勾选 → 写回 false', async () => {
    const onSave = vi.fn(async (_req: UpdateSettingsRequest) => {})
    await render(baseSettings({ deferredToolsDeltaEnabled: true }), onSave)

    await act(async () => {
      deferredDeltaCheckbox().click()
      await Promise.resolve()
    })

    expect(onSave.mock.calls[0][0]).toEqual({ deferredToolsDeltaEnabled: false })
  })
})
