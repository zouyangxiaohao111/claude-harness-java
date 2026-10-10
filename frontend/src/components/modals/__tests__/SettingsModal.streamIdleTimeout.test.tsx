// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import { SettingsModal } from '../SettingsModal'
import type { AppSettings, UpdateSettingsRequest } from '@/api/types'

/**
 * SettingsModal「通用」tab · 流空闲超时（V78 stream_idle_timeout_ms）。
 *
 * WHY（规则 9）：流空闲看门狗的中断阈值（超过此时长没有任何输出就中断流）后端已接线
 * （env CLAUDE_STREAM_IDLE_TIMEOUT_MS 优先），但用户无法在界面上配置 —— 本行把这个配置口
 * 开到「通用」页。本用例锁死四件事：
 * ① 未配置时输入框留空（= 用后端默认 300000），有值时回填（含 appSettings 异步到达的真实路径）；
 * ② **写回必须走 `onSaveSettings`**（App 侧 setAppSettings 是 appSettings 的唯一正常写入路径），
 *    不是直接 settingsApi.update 把返回值丢掉 —— 后者会让 appSettings 保持陈旧，而本弹窗
 *    「关闭即卸载」，重开时以陈旧值为初值 → 显示与后端相反；
 * ③ 低于有效下限 300000 的值**拦下并明示**（后端会把低于下限的值抬到 300s；若前端静默放行
 *    「设了 60000 却按 300000 生效」，用户以为设成功了 —— 静默偏差比报错更坏）；
 * ④ 清空 → 写回 null（回到默认），而不是空串或 0。
 *
 * 两条写渠道**刻意用两个可区分的 mock**（`onSave` vs `settingsUpdate`）：若只让二者落到同一个
 * mock，就分不出实现在走哪条。
 *
 * 变异自证：把 ② 的实现退回 `persistSettings(...)` → ② 红（onSave 未被调用）；
 * 删掉下限判断 → ③ 红；把清空分支的 `parsed=null` 改成 0/空串 → ④ 红；
 * 删掉挂载 effect → ① 的异步到达一段红。
 */

/** 直接被 SettingsModal 调用的渠道（persistSettings → settingsApi.update）。与 onSave 分开。 */
const settingsUpdate = vi.fn(async (req: Record<string, unknown>) => req as unknown as AppSettings)

vi.mock('@/api/settings', () => ({
  settingsApi: {
    get: () => Promise.resolve({} as AppSettings),
    update: (req: Record<string, unknown>) => settingsUpdate(req),
  },
}))

const LABEL = '流空闲超时（毫秒）'

function noop(): void {}

/** App 传下来的 onSaveSettings（写 appSettings 的唯一正常路径）。 */
let onSave: Mock<(req: UpdateSettingsRequest) => Promise<void>>
/** 前端拦下非法值时的提示渠道。 */
let toast: Mock<(msg: string, type?: 'success' | 'info') => void>

function render(root: Root, settings: AppSettings | null) {
  act(() => {
    root.render(
      <SettingsModal
        settingsTab="general"
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
        showToast={toast}
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

/** React 受控 input 需绕过 value tracker 才能触发 onChange（等价 testing-library 的做法）。 */
function typeInto(el: HTMLInputElement, value: string) {
  const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set
  setter?.call(el, value)
  el.dispatchEvent(new Event('input', { bubbles: true }))
}

describe('SettingsModal · 流空闲超时（通用 tab · V78）', () => {
  let container: HTMLDivElement
  let root: Root

  beforeEach(() => {
    (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    settingsUpdate.mockClear()
    onSave = vi.fn(async () => {})
    toast = vi.fn()
  })

  afterEach(() => {
    act(() => root.unmount())
    container.remove()
    vi.restoreAllMocks()
  })

  /** 「通用」tab 里那一行。 */
  function row(): Element {
    const r = Array.from(container.querySelectorAll('.settings-row')).find(
      (r) => r.querySelector('.settings-row-label')?.textContent === LABEL,
    )
    if (!r) throw new Error(`未找到设置行：${LABEL}`)
    return r
  }

  function input(): HTMLInputElement {
    const el = row().querySelector('input[type="number"]')
    if (!el) throw new Error('设置行内无数字输入框')
    return el as HTMLInputElement
  }

  function saveButton(): HTMLButtonElement {
    const btn = row().querySelector('button.envc-save')
    if (!btn) throw new Error('设置行内无保存按钮')
    return btn as HTMLButtonElement
  }

  it('未配置（null）→ 输入框留空，占位显示默认值 300000', () => {
    render(root, null)
    expect(input().value).toBe('')
    expect(input().placeholder).toBe('300000')
    expect(Number(input().min)).toBe(300000)
  })

  it('真实路径：appSettings 异步到达（null → 900000）时输入框回填', async () => {
    render(root, null)
    expect(input().value).toBe('')

    await act(async () => {
      render(root, { streamIdleTimeoutMs: 900000 } as AppSettings)
      await Promise.resolve()
    })
    expect(input().value).toBe('900000')
  })

  it('输入合法值点保存 → 走 onSaveSettings 以 streamIdleTimeoutMs 键写回（不得绕过 appSettings）', async () => {
    render(root, null)
    await act(async () => {
      typeInto(input(), '600000')
      await Promise.resolve()
    })
    await act(async () => {
      saveButton().click()
      await Promise.resolve()
    })
    expect(onSave).toHaveBeenCalledWith({ streamIdleTimeoutMs: 600000 })
    expect(settingsUpdate).not.toHaveBeenCalled()
  })

  it('恰好等于下限 300000 → 允许保存（边界放行，不误伤）', async () => {
    render(root, null)
    await act(async () => {
      typeInto(input(), '300000')
      await Promise.resolve()
    })
    await act(async () => {
      saveButton().click()
      await Promise.resolve()
    })
    expect(onSave).toHaveBeenCalledWith({ streamIdleTimeoutMs: 300000 })
  })

  it('低于下限 60000 → 拦下不写回，toast 明示下限（避免"设了 60000 却按 300000 生效"的静默偏差）', async () => {
    render(root, { streamIdleTimeoutMs: 900000 } as AppSettings)
    await act(async () => {
      typeInto(input(), '60000')
      await Promise.resolve()
    })
    await act(async () => {
      saveButton().click()
      await Promise.resolve()
    })
    expect(onSave).not.toHaveBeenCalled()
    expect(settingsUpdate).not.toHaveBeenCalled()
    expect(toast).toHaveBeenCalled()
    expect(String(toast.mock.calls[0][0])).toContain('300000')
  })

  it('清空 → 写回 null（回到默认 300000），不是空串/0', async () => {
    render(root, { streamIdleTimeoutMs: 900000 } as AppSettings)
    await act(async () => {
      typeInto(input(), '')
      await Promise.resolve()
    })
    await act(async () => {
      saveButton().click()
      await Promise.resolve()
    })
    expect(onSave).toHaveBeenCalledWith({ streamIdleTimeoutMs: null })
    expect(settingsUpdate).not.toHaveBeenCalled()
  })
})
