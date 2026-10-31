// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { BasicSettingsModal } from '../BasicSettingsModal'
import type { FontSize, ThemeMode } from '@/types'

/**
 * 基础设置（左下角用户行）的交互守护。
 *
 * <p><b>WHY</b>：本弹窗是「外观 / 字号」的最高频入口。若它只改本地 reducer 而不写回
 * `settingsApi.update`，用户重启后设置会静默丢失；若「高级设置」没有把用户交回完整设置，
 * 左栏用户行就成了死路。两条都由本用例钉死（删掉 persist 调用 / 删掉 onOpenAdvanced 调用 ⇒ 对应断言红）。
 */

const { get, update } = vi.hoisted(() => ({ get: vi.fn(), update: vi.fn() }))
vi.mock('@/api/settings', () => ({ settingsApi: { get, update } }))
vi.mock('@tauri-apps/api/core', () => ({ isTauri: () => false, invoke: vi.fn() }))

let container: HTMLDivElement
let root: Root

beforeEach(() => {
  ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  get.mockResolvedValue({ theme: null, fontSize: null })
  update.mockResolvedValue({})
})

afterEach(() => {
  act(() => { root.unmount() })
  container.remove()
  vi.clearAllMocks()
})

interface RenderOpts {
  theme?: ThemeMode
  fontSize?: FontSize
  onOpenAdvanced?: () => void
  close?: () => void
}

function renderModal(opts: RenderOpts = {}) {
  const setTheme = vi.fn()
  const setFontSize = vi.fn()
  const onOpenAdvanced = opts.onOpenAdvanced ?? vi.fn()
  const close = opts.close ?? vi.fn()

  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  act(() => {
    root.render(
      <BasicSettingsModal
        theme={opts.theme ?? 'light'}
        setTheme={setTheme}
        fontSize={opts.fontSize ?? 'medium'}
        setFontSize={setFontSize}
        dataDir={'C:\\Users\\x\\.nexusai'}
        onOpenAdvanced={onOpenAdvanced}
        close={close}
        showToast={() => {}}
      />,
    )
  })
  return { setTheme, setFontSize, onOpenAdvanced, close }
}

function buttonByText(text: string): HTMLButtonElement {
  const btn = Array.from(container.querySelectorAll<HTMLButtonElement>('button'))
    .find((b) => b.textContent?.trim() === text)
  if (!btn) throw new Error(`未渲染按钮「${text}」`)
  return btn
}

describe('[基础设置] 四项内容 + 改外观/字号写回后端', () => {
  it('渲染外观 / 字体大小 / 本地数据目录 / 关于四项', () => {
    renderModal()
    expect(container.textContent).toContain('外观')
    expect(container.textContent).toContain('字体大小')
    expect(container.textContent).toContain('本地数据')
    expect(container.textContent).toContain('C:\\Users\\x\\.nexusai')
    expect(container.textContent).toContain('本机运行')
    expect(container.textContent).toContain('高级设置')
  })

  it('点「深色」⇒ 现有 settings 状态更新 + PUT 写回', () => {
    const { setTheme } = renderModal()
    act(() => { buttonByText('深色').click() })
    expect(setTheme).toHaveBeenCalledWith('dark')
    expect(update).toHaveBeenCalledWith({ theme: 'dark' })
  })

  it('点「大」⇒ 现有字号状态更新 + PUT 写回', () => {
    const { setFontSize } = renderModal()
    act(() => { buttonByText('大').click() })
    expect(setFontSize).toHaveBeenCalledWith('large')
    expect(update).toHaveBeenCalledWith({ fontSize: 'large' })
  })
})

describe('[基础设置] 关闭与「高级设置」出口', () => {
  it('「高级设置」⇒ 只回调 onOpenAdvanced（由 App 关本开完整设置）', () => {
    const { onOpenAdvanced } = renderModal()
    act(() => { buttonByText('高级设置').click() })
    expect(onOpenAdvanced).toHaveBeenCalledTimes(1)
  })

  it('点遮罩 / × / 完成 都调用 close；点面板内部不关闭', () => {
    const close = vi.fn()
    renderModal({ close })
    // 面板内部（标题）不触发遮罩关闭
    act(() => { container.querySelector<HTMLElement>('.bs-sheet')!.click() })
    expect(close).not.toHaveBeenCalled()
    act(() => { container.querySelector<HTMLElement>('.bs-x')!.click() })
    expect(close).toHaveBeenCalledTimes(1)
    act(() => { buttonByText('完成').click() })
    expect(close).toHaveBeenCalledTimes(2)
    act(() => { container.querySelector<HTMLElement>('.bs-backdrop')!.click() })
    expect(close).toHaveBeenCalledTimes(3)
  })
})
