// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import { SettingsModal } from '../SettingsModal'
import type { AppSettings, UpdateSettingsRequest } from '@/api/types'

/**
 * SettingsModal「高级」tab · 「按会话展开请求头占位符」开关（V72 allowDynamicHeaderValues）。
 *
 * WHY（规则 9）：这个开关决定提供商自定义请求头里的会话 ID 占位符是「按会话展开」还是
 * 「统一发固定值 nexusai-static」。此前前端既没有这个字段也没有入口 —— 后端列虽在
 * （NOT NULL DEFAULT 1 = 默认开）、用户却改不了。本用例锁死四件事：
 * ① 未拿到字段时按「开」显示（与后端默认开一致，不能显示与后端实际行为相反的状态）；
 * ② settings 带 false 时回填为关；
 * ③ **写回必须走 `onSaveSettings`**（App 侧 setAppSettings 是 appSettings 的唯一正常写入路径），
 *    而不是直接调 settingsApi.update 把返回值丢掉 —— 后者会让 appSettings 保持陈旧，而本弹窗
 *    「关闭即卸载」，重开时以陈旧值为初值 → 开关显示与后端相反；
 * ④ appSettings 异步到达时开关要跟着翻（真实路径，见 App.tsx 初值 null）。
 *
 * 两条写渠道**刻意用两个可区分的 mock**（`onSave` vs `settingsUpdate`）：若只让二者落到同一个
 * mock，就分不出实现在走哪条，这正是上一版用例发现不了「绕过 appSettings」的原因。
 *
 * 变异自证：把 ③ 的实现退回 `persistSettings(...)` → ③ 红（onSave 未被调用）；
 * 删掉挂载 effect → ④ 红；把 `?? true` 改成 `?? false` → ① 红。
 */

/** 直接被 SettingsModal 调用的渠道（persistSettings → settingsApi.update）。与 onSave 分开。 */
const settingsUpdate = vi.fn(async (req: Record<string, unknown>) => req as unknown as AppSettings)

vi.mock('@/api/settings', () => ({
  settingsApi: {
    get: () => Promise.resolve({} as AppSettings),
    update: (req: Record<string, unknown>) => settingsUpdate(req),
  },
}))

const LABEL = '按会话展开请求头占位符'

function noop(): void {}

/** App 传下来的 onSaveSettings（写 appSettings 的唯一正常路径）。 */
let onSave: Mock<(req: UpdateSettingsRequest) => Promise<void>>

function render(root: Root, settings: AppSettings | null) {
  act(() => {
    root.render(
      <SettingsModal
        settingsTab="advanced"
        setSettingsTab={noop}
        theme="dark"
        setTheme={noop}
        fontSize="medium"
        setFontSize={noop}
        animationsEnabled
        setAnimationsEnabled={noop}
        models={[]}
        providers={[]}
        providersApi={{} as never}
        skillsApi={{} as never}
        mcpApi={{} as never}
        databasesApi={{} as never}
        schedulesApi={{} as never}
        close={noop}
        showToast={noop}
        appSettings={settings}
        onSaveSettings={onSave}
        fastModel={null}
        pickFast={noop}
        clearFast={noop}
        onOpenMemoryEditor={noop}
      />,
    )
  })
}

describe('SettingsModal · 按会话展开请求头占位符开关', () => {
  let container: HTMLDivElement
  let root: Root

  beforeEach(() => {
    (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    settingsUpdate.mockClear()
    onSave = vi.fn(async () => {})
  })

  afterEach(() => {
    act(() => root.unmount())
    container.remove()
    vi.restoreAllMocks()
  })

  /** 「高级」tab 里那一行的 checkbox。 */
  function toggle(): HTMLInputElement {
    const row = Array.from(container.querySelectorAll('.settings-row')).find(
      (r) => r.querySelector('.settings-row-label')?.textContent === LABEL,
    )
    if (!row) throw new Error(`未找到开关行：${LABEL}`)
    const box = row.querySelector('input[type="checkbox"]')
    if (!box) throw new Error('开关行内无 checkbox')
    return box as HTMLInputElement
  }

  it('文案直白说明功能，且把 ${session_id} 原样渲染出来（JSX 文本里的花括号会被当表达式）', () => {
    render(root, null)
    const row = Array.from(container.querySelectorAll('.settings-row')).find(
      (r) => r.querySelector('.settings-row-label')?.textContent === LABEL,
    )!
    const desc = row.querySelector('.settings-row-desc')?.textContent ?? ''
    expect(desc).toContain('${session_id}')
    expect(desc).toContain('nexusai-static')
  })

  it('未拿到该字段 → 按默认「开」显示（后端列 NOT NULL DEFAULT 1）', () => {
    render(root, null)
    expect(toggle().checked).toBe(true)
  })

  it('settings.allowDynamicHeaderValues=false → 回填为关', () => {
    render(root, { allowDynamicHeaderValues: false } as AppSettings)
    expect(toggle().checked).toBe(false)
  })

  it('取消勾选 → 走 onSaveSettings 以 allowDynamicHeaderValues 键写回 false（不得绕过 appSettings）', async () => {
    render(root, null)
    await act(async () => {
      toggle().click()
      await Promise.resolve()
    })
    // ③ 的核心断言：写回走的是 onSaveSettings（App 会 setAppSettings → 重开不陈旧）
    expect(onSave).toHaveBeenCalledWith({ allowDynamicHeaderValues: false })
    // 且**不是**直接调 settingsApi.update 那条会丢弃返回值的渠道
    expect(settingsUpdate).not.toHaveBeenCalled()
    expect(toggle().checked).toBe(false)
  })

  it('重新勾选 → 走 onSaveSettings 写回 true', async () => {
    render(root, { allowDynamicHeaderValues: false } as AppSettings)
    await act(async () => {
      toggle().click()
      await Promise.resolve()
    })
    expect(onSave).toHaveBeenCalledWith({ allowDynamicHeaderValues: true })
    expect(settingsUpdate).not.toHaveBeenCalled()
    expect(toggle().checked).toBe(true)
  })

  it('真实路径：appSettings 异步到达（null → false）时开关跟着翻（挂载 effect 的职责）', async () => {
    // App.tsx:279 的初值是 null，settings 随后异步到达 —— 这才是真实路径，
    // 而它正是上一版用例漏掉的：删掉 effect 也没人发现。
    render(root, null)
    expect(toggle().checked).toBe(true)

    await act(async () => {
      render(root, { allowDynamicHeaderValues: false } as AppSettings)
      await Promise.resolve()
    })
    expect(toggle().checked).toBe(false)
  })
})
