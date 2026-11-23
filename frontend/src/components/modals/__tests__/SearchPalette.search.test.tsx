// @vitest-environment jsdom
import { act, type ComponentProps } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SearchPalette } from '../SearchPalette'
import type { SessionDto } from '@/api/types'

/**
 * 搜索面板「会话」区必须是真的（后端模糊 + 分页）。
 *
 * <b>WHY（用户实测缺陷）</b>：旧实现只在**静态演示数组** searchItems 上做 includes，
 * 与真实会话无关（搜什么都是假结果），且硬 `slice(0, 8)` 没有任何翻页 —— 会话一多，
 * 旧会话根本搜不到。本用例钉住三件事：
 * ① 渲染的是父级传入的后端结果（不是静态数据）；
 * ② 总数/页号来自 total（翻页条出现，上一页在首页禁用、下一页回调 page+1）；
 * ③ 点会话行回传真实 id（跳转由 App 负责）。
 */

const noop = () => {}

function hit(id: string, title: string): SessionDto {
  return {
    id, model: null, modelName: null, title, time: null, group: null, tabId: null,
    mainProjectId: null, effortLevel: null, ultracodeEnabled: null, bareMode: null,
    messageCount: 3, updatedAt: '2026-11-23T10:00:00+08:00',
  }
}

let container: HTMLDivElement
let root: Root

function render(overrides: Partial<ComponentProps<typeof SearchPalette>> = {}) {
  const props: ComponentProps<typeof SearchPalette> = {
    searchQuery: 'bus',
    setSearchQuery: noop,
    sessionHits: [hit('sess-1', 'MessageBus 改 fan-out'), hit('sess-2', 'MessageBus 单元测试')],
    sessionTotal: 25,
    sessionPage: 1,
    sessionPageSize: 10,
    sessionLoading: false,
    sessionError: null,
    onSessionPage: noop,
    filteredItems: [],
    close: noop,
    onSessionPick: noop,
    onFilePick: noop,
    onModelPick: noop,
    onAddProjectPick: noop,
    onSettingsPick: noop,
    showToast: noop,
    ...overrides,
  }
  root = createRoot(container)
  act(() => { root.render(<SearchPalette {...props} />) })
  return props
}

beforeEach(() => {
  container = document.createElement('div')
  document.body.appendChild(container)
})

afterEach(() => {
  act(() => { root.unmount() })
  container.remove()
})

describe('SearchPalette · 会话真搜索 + 翻页', () => {
  it('渲染后端返回的会话命中与总数（不是静态演示数据）', () => {
    render()
    const text = container.textContent ?? ''
    expect(text).toContain('MessageBus 改 fan-out')
    expect(text).toContain('MessageBus 单元测试')
    expect(text).toContain('第 1 / 3 页 · 共 25 条')
  })

  it('首页「上一页」禁用；「下一页」回调 page+1', () => {
    const onSessionPage = vi.fn()
    render({ onSessionPage })
    const buttons = [...container.querySelectorAll('.search-pager-btn')] as HTMLButtonElement[]
    expect(buttons).toHaveLength(2)
    expect(buttons[0].disabled).toBe(true)
    act(() => { buttons[1].dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    expect(onSessionPage).toHaveBeenCalledWith(2)
  })

  it('点会话行回传真实会话 id', () => {
    const onSessionPick = vi.fn()
    render({ onSessionPick })
    const rows = [...container.querySelectorAll('.search-item')] as HTMLElement[]
    act(() => { rows[0].dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    expect(onSessionPick).toHaveBeenCalledWith('sess-1')
  })

  it('后端失败时显示错误文案，且不渲染翻页条', () => {
    render({ sessionHits: [], sessionTotal: 0, sessionError: 'Network error: boom' })
    const text = container.textContent ?? ''
    expect(text).toContain('Network error: boom')
    expect(container.querySelector('.search-pager')).toBeNull()
  })
})
