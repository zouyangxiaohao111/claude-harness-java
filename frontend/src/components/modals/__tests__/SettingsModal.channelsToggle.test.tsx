// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import { SettingsModal } from '../SettingsModal'
import type { AppSettings, UpdateSettingsRequest } from '@/api/types'

/**
 * SettingsModal「高级」tab · 「外部渠道」开关（V254 channelsEnabled）。
 *
 * WHY（规则 9）：这个开关是**外部入口的总闸** —— 关着的时候，外部 MCP server（如微信）
 * 不能往会话里注入任何消息。它与「按会话展开请求头占位符」的关键差别在默认值方向：
 * 那个开关默认**开**（无害行为，配了就生效），这个开关默认**关**（外部注入，fail-closed）。
 * 默认值写反的症状是：用户没打开通道，外部消息却进来了 —— 而全程零报错。
 *
 * 本用例锁死四件事：
 * ① 未拿到字段时按「关」显示（不能显示与后端实际行为相反的状态）；
 * ② settings 带 true 时回填为开；带 false 时回填为关；
 * ③ **写回必须走 `onSaveSettings`**（App 侧 setAppSettings 是 appSettings 的唯一正常写入路径），
 *    而不是直接调 settingsApi.update 把返回值丢掉 —— 后者会让 appSettings 保持陈旧，
 *    而本弹窗「关闭即卸载」，重开时以陈旧值为初值 → 开关显示与后端相反；
 * ④ appSettings 异步到达时开关要跟着翻（真实路径，见 App.tsx 初值 null）。
 *
 * 两条写渠道**刻意用两个可区分的 mock**（`onSave` vs `settingsUpdate`）：若只让二者落到同一个
 * mock，就分不出实现在走哪条，这正是「绕过 appSettings」类缺陷以前发现不了的原因。
 *
 * 变异自证：把 `?? false` 改成 `?? true` → ① 红；删掉挂载 effect → ④ 红；
 * 把写回退回 `persistSettings(...)` → ③ 红。
 */

/** 直接被 SettingsModal 调用的渠道（persistSettings → settingsApi.update）。与 onSave 分开。 */
const settingsUpdate = vi.fn(async (req: Record<string, unknown>) => req as unknown as AppSettings)

vi.mock('@/api/settings', () => ({
  settingsApi: {
    get: () => Promise.resolve({} as AppSettings),
    update: (req: Record<string, unknown>) => settingsUpdate(req),
  },
}))

const LABEL = '外部渠道'

function noop(): void {}
/** 异步 noop（档位解绑回调返回 Promise） */
async function noopAsync(): Promise<void> {}

/** App 传下来的 onSaveSettings（写 appSettings 的唯一正常路径）。 */
let onSave: Mock<(req: UpdateSettingsRequest) => Promise<boolean>>

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
        mcpApi={{} as never}
        databasesApi={{} as never}
        schedulesApi={{} as never}
        close={noop}
        showToast={noop}
        appSettings={settings}
        onSaveSettings={onSave}
        onBindModelTier={noop}
        onBindAllModelTiers={noopAsync}
        onClearModelTier={noopAsync}
        onClearAllModelTiers={noopAsync}
        onOpenMemoryEditor={noop}
      />,
    )
  })
}

describe('SettingsModal · 外部渠道开关', () => {
  let container: HTMLDivElement
  let root: Root

  beforeEach(() => {
    (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    settingsUpdate.mockClear()
    onSave = vi.fn(async () => true)
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

  it('文案说明这是外部消息入口，且明说关闭后通道消息不会进来', () => {
    render(root, null)
    const row = Array.from(container.querySelectorAll('.settings-row')).find(
      (r) => r.querySelector('.settings-row-label')?.textContent === LABEL,
    )!
    const desc = row.querySelector('.settings-row-desc')?.textContent ?? ''
    expect(desc).toContain('MCP')
    expect(desc).toContain('关闭')
  })

  it('未拿到该字段 → 按默认「关」显示（外部入口 fail-closed）', () => {
    // WHY：这是与「请求头占位符」开关的关键差别 —— 那个默认开（无害），
    //   这个默认关（外部注入）。默认值写反 = 用户没开通道外部消息却能进来。
    render(root, null)
    expect(toggle().checked).toBe(false)
  })

  it('settings.channelsEnabled=true → 回填为开', () => {
    render(root, { channelsEnabled: true } as AppSettings)
    expect(toggle().checked).toBe(true)
  })

  it('settings.channelsEnabled=false → 回填为关', () => {
    render(root, { channelsEnabled: false } as AppSettings)
    expect(toggle().checked).toBe(false)
  })

  it('勾选 → 走 onSaveSettings 以 channelsEnabled 键写回 true（不得绕过 appSettings）', async () => {
    render(root, null)
    await act(async () => {
      toggle().click()
      await Promise.resolve()
    })
    // ③ 的核心断言：写回走的是 onSaveSettings（App 会 setAppSettings → 重开不陈旧）
    expect(onSave).toHaveBeenCalledWith({ channelsEnabled: true })
    // 且**不是**直接调 settingsApi.update 那条会丢弃返回值的渠道
    expect(settingsUpdate).not.toHaveBeenCalled()
    expect(toggle().checked).toBe(true)
  })

  it('取消勾选 → 走 onSaveSettings 写回 false', async () => {
    render(root, { channelsEnabled: true } as AppSettings)
    await act(async () => {
      toggle().click()
      await Promise.resolve()
    })
    expect(onSave).toHaveBeenCalledWith({ channelsEnabled: false })
    expect(settingsUpdate).not.toHaveBeenCalled()
    expect(toggle().checked).toBe(false)
  })

  it('真实路径：appSettings 异步到达（null → true）时开关跟着翻（挂载 effect 的职责）', async () => {
    // App.tsx 的初值是 null，settings 随后异步到达 —— 这才是真实路径。
    render(root, null)
    expect(toggle().checked).toBe(false)

    await act(async () => {
      render(root, { channelsEnabled: true } as AppSettings)
      await Promise.resolve()
    })
    expect(toggle().checked).toBe(true)
  })
})
