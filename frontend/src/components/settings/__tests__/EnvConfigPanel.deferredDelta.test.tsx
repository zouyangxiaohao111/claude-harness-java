// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { EnvConfigPanel } from '../EnvConfigPanel'
import type { AppSettings, UpdateSettingsRequest } from '@/api/types'

/**
 * 「延迟加载公告用增量方式」开关已退役 —— 前端不再提供该勾选框。
 *
 * WHY（规则 9）：该开关写 settings.deferredToolsDeltaEnabled，但后端已删除该判据
 * （deferred 工具公告的「增量附件 / 每轮全量清单」两态收敛为固定走增量附件），开关不再有任何
 * 行为。留一个写不进行为的勾选框只会误导用户，故整块移除。后端该 DB 列与 DTO 字段随后一并清除
 * （V77 迁移 DROP COLUMN），本用例锁死移除后的形态：面板不再渲染
 * 这一行（防止被误加回）。原用例（默认关 / 回填 / 勾选写回 / 取消写回）断言的正是已删除的开关
 * 行为，随开关一并移除。
 */

vi.mock('@/api/memory', () => ({
  getMemoryConfig: () => Promise.resolve({
    autoMemoryEnabled: false, autoDreamEnabled: false,
    dreamStatus: null, lastConsolidatedAtMs: null,
  }),
  updateMemoryConfig: () => Promise.resolve({}),
}))

const RETIRED_FIELD_NAME = '延迟加载公告用增量方式'

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

describe('EnvConfigPanel · 工具延迟加载公告开关（已退役）', () => {
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

  async function render(settings: AppSettings | null, onSave: (req: UpdateSettingsRequest) => Promise<void>) {
    await act(async () => {
      root.render(<EnvConfigPanel settings={settings} onSaveSettings={onSave} onOpenMemoryEditor={() => {}} />)
      await Promise.resolve()
    })
  }

  it('面板不再渲染「延迟加载公告用增量方式」开关行（该字段已从 AppSettings 契约移除）', async () => {
    await render(baseSettings(), async () => {})
    const names = Array.from(container.querySelectorAll('.envc-name')).map((n) => n.textContent)
    expect(names).not.toContain(RETIRED_FIELD_NAME)
  })
})
