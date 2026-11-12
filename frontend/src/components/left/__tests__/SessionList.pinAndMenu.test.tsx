// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SessionList } from '../SessionList'
import type { SessionDto } from '@/api/types'

/**
 * [V283] 左栏置顶 + ⋯ 菜单点外部收起（接线守门）。
 *
 * <b>WHY 渲染真组件</b>：`sortSessionsWithPinned` 的纯函数测试全绿也证明不了
 * `SessionList` 真的把置顶会话拎到最前、菜单真的挂了 document 监听 —— 这两点都在组件里。
 */

const mkSession = (id: string, mainProjectId: string, updatedAt: string, pinned?: boolean): SessionDto => ({
  id,
  model: 'DS',
  modelName: 'ds/deepseek-v4-flash',
  title: `会话 ${id}`,
  time: '现在',
  group: 'current',
  tabId: null,
  mainProjectId,
  effortLevel: null,
  ultracodeEnabled: null,
  bareMode: null,
  messageCount: 1,
  createdAt: updatedAt,
  updatedAt,
  pinned,
})

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
})

const click = (el: Element) => act(() => {
  el.dispatchEvent(new MouseEvent('click', { bubbles: true }))
})

describe('SessionList · [V283] 置顶', () => {
  it('置顶会话渲染在最前的「置顶」块，且早于项目组里的未置顶会话', () => {
    act(() => {
      root.render(
        <SessionList
          sessions={[
            // 未置顶的 updatedAt 更新 —— 旧口径下它才该排第一
            mkSession('sess-normal', 'p-1', '2026-09-12T23:59:59Z'),
            mkSession('sess-pin', 'p-1', '2026-09-01T00:00:00Z', true),
          ]}
          activeSession="sess-normal"
          switchSession={vi.fn()}
          projectNameFor={() => '项目一'}
        />,
      )
    })
    const firstSection = container.querySelector('.left-section')
    expect(firstSection?.querySelector('.label .group-path')?.textContent).toBe('置顶')
    expect(firstSection?.querySelector('.session-item .title')?.textContent).toBe('会话 sess-pin')
    // 未置顶会话不会重复出现在置顶块里
    expect(firstSection?.querySelectorAll('.session-item').length).toBe(1)
  })

  it('点 ⋯ 后对别处 mousedown，菜单收起 · WHY：此前没有 document 监听，菜单一直挂着', () => {
    act(() => {
      root.render(
        <SessionList
          sessions={[mkSession('sess-a', 'p-1', '2026-09-12T00:00:00Z')]}
          activeSession="sess-a"
          switchSession={vi.fn()}
          projectNameFor={() => '项目一'}
        />,
      )
    })
    const btn = container.querySelector('.session-menu-btn')
    expect(btn).toBeTruthy()
    click(btn!)
    expect(container.querySelector('.session-menu')).toBeTruthy()

    act(() => {
      document.body.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
    })
    expect(container.querySelector('.session-menu')).toBeNull()
  })

  it('菜单「置顶」调 onPinSession(id, true)；已置顶时显示「取消置顶」并回调 false', () => {
    const onPinSession = vi.fn()
    act(() => {
      root.render(
        <SessionList
          sessions={[mkSession('sess-a', 'p-1', '2026-09-12T00:00:00Z')]}
          activeSession="sess-a"
          switchSession={vi.fn()}
          projectNameFor={() => '项目一'}
          onPinSession={onPinSession}
        />,
      )
    })
    click(container.querySelector('.session-menu-btn')!)
    const pinItem = [...container.querySelectorAll('.session-menu .sm-item')]
      .find((el) => el.textContent === '置顶')
    expect(pinItem).toBeTruthy()
    click(pinItem!)
    expect(onPinSession).toHaveBeenCalledWith('sess-a', true)

    // 已置顶 → 菜单文案反转，回调 false
    act(() => {
      root.render(
        <SessionList
          sessions={[mkSession('sess-a', 'p-1', '2026-09-12T00:00:00Z', true)]}
          activeSession="sess-a"
          switchSession={vi.fn()}
          projectNameFor={() => '项目一'}
          onPinSession={onPinSession}
        />,
      )
    })
    click(container.querySelector('.session-menu-btn')!)
    const unpinItem = [...container.querySelectorAll('.session-menu .sm-item')]
      .find((el) => el.textContent === '取消置顶')
    expect(unpinItem).toBeTruthy()
    click(unpinItem!)
    expect(onPinSession).toHaveBeenCalledWith('sess-a', false)
  })

  it('行首五角星点击即置顶，且不触发会话切换 · WHY：用户要求「点星就置顶」，比进 ⋯ 菜单更快', () => {
    const onPinSession = vi.fn()
    const switchSession = vi.fn()
    act(() => {
      root.render(
        <SessionList
          sessions={[mkSession('sess-a', 'p-1', '2026-09-12T00:00:00Z')]}
          activeSession="sess-a"
          switchSession={switchSession}
          projectNameFor={() => '项目一'}
          onPinSession={onPinSession}
        />,
      )
    })
    const star = container.querySelector('.session-pin-btn')
    expect(star).toBeTruthy()
    expect(star?.getAttribute('aria-label')).toBe('置顶')
    click(star!)
    expect(onPinSession).toHaveBeenCalledWith('sess-a', true)
    expect(switchSession).not.toHaveBeenCalled()
  })

  it('已置顶行的五角星 aria-label = 取消置顶，点击传 false', () => {
    const onPinSession = vi.fn()
    act(() => {
      root.render(
        <SessionList
          sessions={[mkSession('sess-a', 'p-1', '2026-09-12T00:00:00Z', true)]}
          activeSession="sess-a"
          switchSession={vi.fn()}
          projectNameFor={() => '项目一'}
          onPinSession={onPinSession}
        />,
      )
    })
    const star = container.querySelector('.session-pin-btn')
    expect(star?.getAttribute('aria-label')).toBe('取消置顶')
    click(star!)
    expect(onPinSession).toHaveBeenCalledWith('sess-a', false)
  })
})
