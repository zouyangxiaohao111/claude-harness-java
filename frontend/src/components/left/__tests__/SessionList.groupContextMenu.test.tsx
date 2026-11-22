// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SessionList } from '../SessionList'
import type { SessionDto } from '@/api/types'

/**
 * 左栏「项目分组标题」右键菜单 →「在文件资源管理器中显示」（2026-11-18 用户要求，与右栏文件树一致）。
 *
 * <b>WHY 渲染真组件</b>：菜单挂载、右键拦截、点击后走哪条分支（桌面 Tauri / 浏览器兜底）
 * 全在组件里，纯函数测试证明不了。
 *
 * <b>口径</b>：jsdom 里没有 Tauri 壳 ⇒ {@code isTauri()} 为 false ⇒ 点菜单项走
 * 「复制路径 + 提示」兜底分支（这也是线上浏览器/e2e 环境的同一分支）。
 */

const mkSession = (id: string, mainProjectId: string | null, updatedAt: string): SessionDto => ({
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
  document.body.querySelectorAll('.project-context-menu').forEach((el) => el.remove())
})

const contextMenu = (el: Element) => act(() => {
  el.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true, clientX: 120, clientY: 80 }))
})
const click = (el: Element) => act(() => {
  el.dispatchEvent(new MouseEvent('click', { bubbles: true }))
})
const key = (k: string) => act(() => {
  document.dispatchEvent(new KeyboardEvent('keydown', { key: k, bubbles: true }))
})

const menuItems = () => [...document.querySelectorAll('.project-context-menu .ctx-menu-item')].map((e) => e.textContent)

describe('SessionList · 项目分组右键菜单', () => {
  it('右键项目分组标题 → 弹出「在文件资源管理器中显示」', () => {
    act(() => {
      root.render(
        <SessionList
          sessions={[mkSession('s1', 'p-1', '2026-11-01T00:00:00Z')]}
          activeSession=""
          switchSession={() => {}}
          projectPathFor={() => '/Users/me/projects/nexusai'}
          projectNameFor={() => 'nexusai'}
        />,
      )
    })
    const label = container.querySelector('.left-section .label') as HTMLElement
    expect(label).toBeTruthy()
    contextMenu(label)
    expect(menuItems()).toEqual(['在文件资源管理器中显示'])
  })

  it('点菜单项 → 浏览器环境走「复制路径 + 提示」兜底（不是静默无反应）', () => {
    const showToast = vi.fn()
    act(() => {
      root.render(
        <SessionList
          sessions={[mkSession('s1', 'p-1', '2026-11-01T00:00:00Z')]}
          activeSession=""
          switchSession={() => {}}
          projectPathFor={() => '/Users/me/projects/nexusai'}
          projectNameFor={() => 'nexusai'}
          showToast={showToast}
        />,
      )
    })
    contextMenu(container.querySelector('.left-section .label') as HTMLElement)
    const item = document.querySelector('.project-context-menu .ctx-menu-item') as HTMLElement
    click(item)
    expect(showToast).toHaveBeenCalledWith('浏览器环境无法打开资源管理器，已复制路径', 'info')
    // 菜单点完即关
    expect(document.querySelector('.project-context-menu')).toBeNull()
  })

  it('无路径的组（未绑定目录）右键不弹菜单', () => {
    act(() => {
      root.render(
        <SessionList
          sessions={[mkSession('s1', 'p-1', '2026-11-01T00:00:00Z')]}
          activeSession=""
          switchSession={() => {}}
          projectPathFor={() => ''}
          projectNameFor={() => '未绑定项目'}
        />,
      )
    })
    contextMenu(container.querySelector('.left-section .label') as HTMLElement)
    expect(document.querySelector('.project-context-menu')).toBeNull()
  })

  it('Esc 关闭菜单', () => {
    act(() => {
      root.render(
        <SessionList
          sessions={[mkSession('s1', 'p-1', '2026-11-01T00:00:00Z')]}
          activeSession=""
          switchSession={() => {}}
          projectPathFor={() => '/Users/me/projects/nexusai'}
          projectNameFor={() => 'nexusai'}
        />,
      )
    })
    contextMenu(container.querySelector('.left-section .label') as HTMLElement)
    expect(document.querySelector('.project-context-menu')).toBeTruthy()
    key('Escape')
    expect(document.querySelector('.project-context-menu')).toBeNull()
  })

  it('右键不触发分组折叠（右键 ≠ 点一下）', () => {
    act(() => {
      root.render(
        <SessionList
          sessions={[mkSession('s1', 'p-1', '2026-11-01T00:00:00Z')]}
          activeSession=""
          switchSession={() => {}}
          projectPathFor={() => '/Users/me/projects/nexusai'}
          projectNameFor={() => 'nexusai'}
          groupsDefaultCollapsed
        />,
      )
    })
    const label = container.querySelector('.left-section .label') as HTMLElement
    expect(label.className).toContain('collapsed')
    contextMenu(label)
    // 仍折叠 ⇒ 右键没有顺手 toggle
    expect((container.querySelector('.left-section .label') as HTMLElement).className).toContain('collapsed')
  })
})
