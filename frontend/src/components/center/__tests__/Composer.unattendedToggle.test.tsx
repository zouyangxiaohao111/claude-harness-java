// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Composer } from '../Composer'
import type { PermissionMode } from '@/api/types'

/**
 * 无人值守入口（输入框「渠道」右侧常驻开关）· 用户 2026-11-21 两处实测缺陷。
 *
 * <p>WHY：
 * ① 开关状态只由指示点表达（与「计划 / 协调者」同一套 chip 视觉：`.tool-chip.plan-mode`
 *    + `.plan-indicator`，关闭灰点 / 开启蓝点呼吸），用户明确不要旁边再写可见的「开 / 关」文字，
 *    ⛔ 也不要迷你拨杆（2026-11-21 要求对齐计划/协调者样式）；
 *    但 role=switch + aria-checked / aria-label 必须保留给读屏与测试。
 * ② 「请先配置渠道」曾经是入口旁的行内气泡：点一次置真，只有「后来配了渠道」才清，
 *    关闭弹窗 / 切会话都清不掉 ⇒ 弹出来就不消失。现在文案走既有 toast（info=6s），
 *    弹窗内说明条随关闭 / 配置成功 / 切会话清除。
 *
 * <p>这两个判据都落在**用户能感知的行为**上：DOM 里没有状态文字、引导不会在关闭弹窗后
 * 继续挂着、配好渠道后点击真的走切换回调。
 */
vi.mock('@tauri-apps/api/core', () => ({ isTauri: () => true, invoke: vi.fn(async () => undefined) }))
vi.mock('@tauri-apps/api/webview', () => ({
  getCurrentWebview: () => ({ onDragDropEvent: () => Promise.resolve(() => {}) }),
}))
vi.mock('@tauri-apps/plugin-fs', () => ({ stat: vi.fn(), readFile: vi.fn(), writeTextFile: vi.fn(async () => undefined) }))
vi.mock('@/api/chat', () => ({ uploadAttachment: vi.fn() }))

interface RenderOpts {
  unattended?: boolean
  unattendedPending?: boolean
  onToggleUnattended?: () => void
  imInstances?: Array<{ id: string; label: string; platform?: 'wecom' }>
  imChats?: Array<{ id: string; name: string; kind: 'group' | 'p2p'; instanceId: string }>
  imBoundChatIds?: string[]
  onOpenImChannels?: () => void
  onRefreshImData?: () => void
  showToast?: (msg: string, type?: 'success' | 'info') => void
  sessionId?: string
  permissionMode?: PermissionMode
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
    // 生产里 App 恒传该回调（渠道入口 + 弹窗的渲染条件）—— 测试也按真实调用方给上
    onOpenImChannels: () => {},
    ...opts,
  }
}

describe('Composer 无人值守入口', () => {
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
    document.querySelectorAll('.chanm-backdrop').forEach((n) => n.remove())
  })

  const render = (opts: RenderOpts = {}) => {
    act(() => { root.render(<Composer {...baseProps(opts)} />) })
  }

  it('状态只用状态点表达（对齐计划/协调者 chip）：没有可见的「开/关」文字，aria 语义保留', () => {
    render({ unattended: true, onToggleUnattended: () => {}, imInstances: [{ id: 'i1', label: '企微' }] })
    const chip = container.querySelector('.tool-chip.uo-entry') as HTMLElement
    expect(chip, '找不到无人值守开关').toBeTruthy()
    expect(chip.getAttribute('role')).toBe('switch')
    expect(chip.getAttribute('aria-checked')).toBe('true')
    expect(chip.getAttribute('aria-label')).toBe('无人值守')
    // 用户明确：状态由指示点表达，不要再挂「开/关」文字
    expect(chip.querySelector('.uo-state')).toBeNull()
    expect(chip.textContent ?? '').not.toMatch(/[开关]/)
    // [2026-11-21 用户要求] 与「计划 / 协调者」同一套 chip 视觉：plan-mode + 激活态 + 指示点，
    // ⛔ 不再有迷你拨杆（.uo-mini）。
    expect(chip.classList.contains('plan-mode')).toBe(true)
    expect(chip.classList.contains('active')).toBe(true)
    expect(chip.querySelector('.plan-indicator')).toBeTruthy()
    expect(chip.querySelector('.uo-mini')).toBeNull()
  })

  it('未配渠道点击：走既有 toast 提示 + 打开渠道弹窗，不调用真正的切换', () => {
    const showToast = vi.fn()
    const onToggleUnattended = vi.fn()
    render({ showToast, onToggleUnattended, imInstances: [] })

    const chip = container.querySelector('.tool-chip.uo-entry') as HTMLElement
    act(() => { chip.dispatchEvent(new MouseEvent('click', { bubbles: true })) })

    expect(showToast).toHaveBeenCalledWith('请先配置渠道', 'info')
    expect(onToggleUnattended).not.toHaveBeenCalled()
    expect(document.querySelector('.chanm-modal')).toBeTruthy()
    expect(document.body.textContent ?? '').toContain('请先配置渠道')
    // 行内常驻气泡已删（不会出现第二份「请先配置渠道」）
    expect(document.querySelector('.uo-guide')).toBeNull()
  })

  it('关闭渠道弹窗后引导立即消失：再打开弹窗不再挂着说明条', () => {
    const onOpenImChannels = vi.fn()
    render({ imInstances: [], onOpenImChannels, onToggleUnattended: () => {} })

    const chip = container.querySelector('.tool-chip.uo-entry') as HTMLElement
    act(() => { chip.dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    expect(document.querySelector('.chanm-modal')).toBeTruthy()

    const close = document.querySelector('.chanm-x') as HTMLElement
    act(() => { close.dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    expect(document.querySelector('.chanm-modal')).toBeNull()

    // 再次从「渠道」入口打开（不经无人值守入口）⇒ 不应继承上一次的引导
    const chanEntry = container.querySelector('.tool-chip.chan-entry') as HTMLElement
    act(() => { chanEntry.dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    expect(document.querySelector('.chanm-modal')).toBeTruthy()
    expect(document.querySelector('.chan-guide')).toBeNull()
  })

  it('切会话清除引导：上一会话的「请先配置渠道」不跟到新会话', () => {
    render({ imInstances: [], sessionId: 'sess-1', onToggleUnattended: () => {} })
    const chip = container.querySelector('.tool-chip.uo-entry') as HTMLElement
    act(() => { chip.dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    expect(document.querySelector('.chan-guide')).toBeTruthy()

    // 同一组件实例（App 在切会话时不重挂 Composer）⇒ 只换 sessionId
    render({ imInstances: [], sessionId: 'sess-2', onToggleUnattended: () => {} })
    expect(container.querySelector('.tool-chip.uo-entry')).toBeTruthy()
    expect(document.querySelector('.chan-guide')).toBeNull()
  })

  it('已配渠道点击：直接切换，不弹引导、不发提示', () => {
    const showToast = vi.fn()
    const onToggleUnattended = vi.fn()
    render({
      showToast,
      onToggleUnattended,
      imInstances: [{ id: 'i1', label: '企微' }],
      imChats: [{ id: 'c1', name: '张三', kind: 'p2p', instanceId: 'i1' }],
    })

    const chip = container.querySelector('.tool-chip.uo-entry') as HTMLElement
    act(() => { chip.dispatchEvent(new MouseEvent('click', { bubbles: true })) })

    expect(onToggleUnattended).toHaveBeenCalledTimes(1)
    expect(showToast).not.toHaveBeenCalled()
    expect(document.querySelector('.chanm-modal')).toBeNull()
  })
})
