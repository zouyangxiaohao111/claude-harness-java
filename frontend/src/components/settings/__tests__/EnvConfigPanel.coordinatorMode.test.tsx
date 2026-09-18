// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { EnvConfigPanel } from '../EnvConfigPanel'
import type { AppSettings, UpdateSettingsRequest } from '@/api/types'

/**
 * [coord-mode] 「协调者模式」开关 —— 前端可配置 + 保存链路。
 *
 * WHY（规则 9）：该开关决定主 Claude 是否退化为「只派发、不执行」的协调者（后端
 * PromptAlignSettingsResolver.coordinatorModeEnabled 读 → LlmAgentLoop 的 coordinator 分支门）。
 * 此前前端完全没有这一项（AppSettings 无字段、面板无 UI）→ 后端 DB 列虽在但用户只能靠 API 或
 * 直接改库打开。本用例锁死四件事：① 未配置/null 默认关闭（后端默认 false）；② settings 带 true
 * 时回填为开；③ 勾选后经 onSaveSettings 以 `coordinatorModeEnabled` 键写回 true（后端 PUT /settings merge 生效）；
 * ④ 取消勾选写回 false。
 *
 * 变异自证：把 checkbox 的 onChange 改成写别的键（或不写）→ 第 4/5 条断言红；把默认值改成
 * `?? true` → 第 1/2 条断言红。
 */

vi.mock('@/api/memory', () => ({
  getMemoryConfig: () => Promise.resolve({
    autoMemoryEnabled: false, autoDreamEnabled: false,
    dreamStatus: null, lastConsolidatedAtMs: null,
  }),
  updateMemoryConfig: () => Promise.resolve({}),
}))

const FIELD_NAME = '协调者模式'

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

describe('EnvConfigPanel · 协调者模式开关', () => {
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

  /** 面板里「协调者模式」那一行的 checkbox。 */
  function coordinatorModeCheckbox(): HTMLInputElement {
    const rows = Array.from(container.querySelectorAll('.envc-row'))
    const row = rows.find((r) => r.querySelector('.envc-name')?.textContent === FIELD_NAME)
    if (!row) throw new Error(`未找到开关行：${FIELD_NAME}`)
    const box = row.querySelector('input[type="checkbox"]')
    if (!box) throw new Error('开关行内无 checkbox')
    return box as HTMLInputElement
  }

  async function render(settings: AppSettings | null, onSave: (req: UpdateSettingsRequest) => Promise<void>) {
    await act(async () => {
      root.render(<EnvConfigPanel settings={settings} onOpenMemoryEditor={() => {}} onSaveSettings={onSave} />)
      await Promise.resolve()
    })
  }

  it('settings 未配置该字段 → 默认关闭（checkbox 未勾选）', async () => {
    await render(baseSettings(), async () => {})
    expect(coordinatorModeCheckbox().checked).toBe(false)
  })

  it('settings.coordinatorModeEnabled=null → 默认关闭', async () => {
    await render(baseSettings({ coordinatorModeEnabled: null }), async () => {})
    expect(coordinatorModeCheckbox().checked).toBe(false)
  })

  it('settings.coordinatorModeEnabled=true → 回填为开', async () => {
    await render(baseSettings({ coordinatorModeEnabled: true }), async () => {})
    expect(coordinatorModeCheckbox().checked).toBe(true)
  })

  it('勾选 → 经 onSaveSettings 以 coordinatorModeEnabled 键写回 true', async () => {
    const onSave = vi.fn(async (_req: UpdateSettingsRequest) => {})
    await render(baseSettings({ coordinatorModeEnabled: false }), onSave)

    const box = coordinatorModeCheckbox()
    await act(async () => {
      box.click()
      await Promise.resolve()
    })

    expect(onSave).toHaveBeenCalledTimes(1)
    expect(onSave.mock.calls[0][0]).toEqual({ coordinatorModeEnabled: true })
  })

  it('取消勾选 → 写回 false', async () => {
    const onSave = vi.fn(async (_req: UpdateSettingsRequest) => {})
    await render(baseSettings({ coordinatorModeEnabled: true }), onSave)

    await act(async () => {
      coordinatorModeCheckbox().click()
      await Promise.resolve()
    })

    expect(onSave.mock.calls[0][0]).toEqual({ coordinatorModeEnabled: false })
  })
})
