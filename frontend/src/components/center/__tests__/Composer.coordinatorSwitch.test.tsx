// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Composer } from '../Composer'
import type { PermissionMode } from '@/api/types'

/**
 * 协调者模式开关（输入框工具区）。
 *
 * <p>WHY：这个开关写的是**会话级覆盖**（`sessions.coordinator_mode`）。如果它只是视觉开关、
 * 不真的回调，用户会以为「本会话切了协调者」而实际后端仍按全局设置跑 —— 这是静默错配。
 * 所以断言落在「点击后回调收到的布尔值」上，而不是只看 class 变了。
 *
 * <p>同时锁住「浏览器入口已从输入框移除」：AI 侧已经知道浏览器的用法，
 * 输入框那一格让给协调者开关；浏览器 chip 若回来，这条会红。
 */
vi.mock('@tauri-apps/api/core', () => ({ isTauri: () => true, invoke: vi.fn(async () => undefined) }))
vi.mock('@tauri-apps/api/webview', () => ({
  getCurrentWebview: () => ({ onDragDropEvent: () => Promise.resolve(() => {}) }),
}))
vi.mock('@tauri-apps/plugin-fs', () => ({ stat: vi.fn(), readFile: vi.fn(), writeTextFile: vi.fn(async () => undefined) }))
vi.mock('@/api/chat', () => ({ uploadAttachment: vi.fn() }))

interface RenderOpts {
  coordinatorMode?: boolean
  onCoordinatorModeChange?: (on: boolean) => void
  permissionMode?: PermissionMode
  onPermissionModeChange?: (m: PermissionMode) => void
  onEnterPlanMode?: () => void
  onExitPlanMode?: () => void
  planPending?: boolean
  streaming?: boolean
}

function baseProps(opts: RenderOpts = {}) {
  return {
    composerText: '',
    setComposerText: () => {},
    sendMessage: () => {},
    showToast: () => {},
    streaming: false,
    onStop: () => {},
    queuedCommands: [],
    popEditable: async () => null,
    boundProjectName: null,
    onSelectProject: () => {},
    currentModel: 'ds-openai/deepseek-v4-flash',
    permissionMode: 'default' as const,
    empty: false,
    sessionId: 'sess-1',
    localRead: false,
    ...opts,
  }
}

describe('Composer 协调者开关', () => {
  let container: HTMLDivElement
  let root: Root

  beforeEach(() => {
    ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
  })

  afterEach(() => {
    act(() => root.unmount())
    container.remove()
  })

  it('传了回调 → 渲染「协调者」+ 开关，点击回调收到取反后的值', () => {
    const onCoordinatorModeChange = vi.fn()
    act(() => {
      root.render(<Composer {...baseProps({ coordinatorMode: true, onCoordinatorModeChange })} />)
    })

    const chip = container.querySelector('.tool-chip.coordinator-chip') as HTMLElement
    expect(chip, '找不到协调者 chip').toBeTruthy()
    expect(chip.textContent).toContain('协调者')
    expect(chip.getAttribute('aria-pressed')).toBe('true')
    expect(chip.classList.contains('active')).toBe(true)
    // 用户 2026-11-19 明确要「不要开关，整块点击激活」⇒ 小开关不许回来
    expect(chip.querySelector('.coord-switch')).toBeNull()

    act(() => { chip.dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    expect(onCoordinatorModeChange).toHaveBeenCalledWith(false)
  })

  it('关闭态 → 无 active 类，点击回调收到 true', () => {
    const onCoordinatorModeChange = vi.fn()
    act(() => {
      root.render(<Composer {...baseProps({ coordinatorMode: false, onCoordinatorModeChange })} />)
    })
    const chip = container.querySelector('.tool-chip.coordinator-chip') as HTMLElement
    expect(chip.classList.contains('active')).toBe(false)
    expect(chip.getAttribute('aria-pressed')).toBe('false')

    act(() => { chip.dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    expect(onCoordinatorModeChange).toHaveBeenCalledWith(true)
  })

  it('不传回调（旧调用方）→ 不渲染协调者 chip，也不再有浏览器入口', () => {
    act(() => { root.render(<Composer {...baseProps()} />) })
    expect(container.querySelector('.tool-chip.coordinator-chip')).toBeNull()
    const text = container.textContent ?? ''
    expect(text).not.toContain('浏览器')
  })

  it('计划模式 chip：空闲时点击进 / 退（走 EnterPlanMode 链路，不写权限模式）', () => {
    const onEnterPlanMode = vi.fn()
    const onExitPlanMode = vi.fn()
    const onPermissionModeChange = vi.fn()
    act(() => {
      root.render(<Composer {...baseProps({
        permissionMode: 'default', onEnterPlanMode, onExitPlanMode, onPermissionModeChange,
      })} />)
    })
    const chip = container.querySelector('.tool-chip.plan-mode-chip') as HTMLElement
    expect(chip, '找不到计划模式 chip').toBeTruthy()
    expect(chip.textContent).toContain('计划')
    expect(chip.classList.contains('active')).toBe(false)

    act(() => { chip.dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    expect(onEnterPlanMode).toHaveBeenCalledTimes(1)
    expect(onExitPlanMode).not.toHaveBeenCalled()
    // ⛔ 不是权限模式下拉：点 chip 不许写 permissionMode
    expect(onPermissionModeChange).not.toHaveBeenCalled()

    // 已开启态（permissionMode 已由 session_permission_rules 覆盖透出 plan）→ 点击走退出
    act(() => { root.render(<Composer {...baseProps({ permissionMode: 'plan', onEnterPlanMode, onExitPlanMode })} />) })
    const on = container.querySelector('.tool-chip.plan-mode-chip') as HTMLElement
    expect(on.classList.contains('active')).toBe(true)
    act(() => { on.dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    expect(onExitPlanMode).toHaveBeenCalledTimes(1)
    expect(onEnterPlanMode).toHaveBeenCalledTimes(1)
  })

  it('运行中点击 → 仍触发回调（排队与乐观显示在 App；chip 不做拦截）', () => {
    const onEnterPlanMode = vi.fn()
    const onExitPlanMode = vi.fn()
    act(() => {
      root.render(<Composer {...baseProps({
        streaming: true, permissionMode: 'default', onEnterPlanMode, onExitPlanMode,
      })} />)
    })
    const chip = container.querySelector('.tool-chip.plan-mode-chip') as HTMLElement
    act(() => { chip.dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    // CC：运行中输入 /plan 入队，不是被禁用；chip 只负责发起
    expect(onEnterPlanMode).toHaveBeenCalledTimes(1)
    expect(onExitPlanMode).not.toHaveBeenCalled()
  })

  it('排队中（planPending）→ chip 显示 pending 独立态，不冒充已生效', () => {
    const onEnterPlanMode = vi.fn()
    act(() => {
      root.render(<Composer {...baseProps({
        permissionMode: 'default', planPending: true, onEnterPlanMode,
      })} />)
    })
    const chip = container.querySelector('.tool-chip.plan-mode-chip') as HTMLElement
    expect(chip.classList.contains('pending')).toBe(true)
    // 排队 ≠ 已生效：active 必须为假，点击也不重复排队
    expect(chip.classList.contains('active')).toBe(false)
    act(() => { chip.dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    expect(onEnterPlanMode).not.toHaveBeenCalled()
  })

  it('不传 enter/exit 回调 → 不渲染计划 chip', () => {
    act(() => { root.render(<Composer {...baseProps()} />) })
    expect(container.querySelector('.tool-chip.plan-mode-chip')).toBeNull()
  })

  it('工具入口的图标容器必须是 <i>：窄栏 `tool-chip > span` 会隐藏所有 span，', () => {
    act(() => { root.render(<Composer {...baseProps()} />) })
    // 若改回 <span class="entry-ico">，<620px 时齿轮图标会被收文字规则一起藏掉（用户实测 550px 无图标）
    const ico = container.querySelector('.tool-entry .entry-ico') as HTMLElement | null
    expect(ico, '找不到工具图标容器').toBeTruthy()
    expect(ico!.tagName).toBe('I')
    // 禁用角标已按用户要求移除
    expect(container.querySelector('.entry-dot')).toBeNull()
  })
})
