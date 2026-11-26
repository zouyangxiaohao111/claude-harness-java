// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SessionList } from '../SessionList'
import type { SessionDto } from '@/api/types'

// [folder-group 2026-11-26] 日期用「相对今天」造：本文件测 ⋯ 菜单，与日期无关；
// 固定旧日期会被「超过3天收进更早」默认收起，导致行不可见。用近期日期保证行可见。
const recentIso = (daysAgo = 0): string => {
  const d = new Date(); d.setHours(12, 0, 0, 0); d.setDate(d.getDate() - daysAgo); return d.toISOString()
}

/**
 * 左栏会话行 ⋯ 菜单里的「复制」项（2026-11-18 用户要求：复制 = 以最后一条为分叉点分叉）。
 *
 * <b>WHY 渲染真组件</b>：菜单项是否真的出现在 ⋯ 菜单里、点了会不会把菜单收起来，
 * 都在组件里（SessionList 是纯展示组件，真正的分叉在 App 侧 → 见 utils/duplicateSession.test.ts）。
 */

const mkSession = (id: string, updatedAt: string): SessionDto => ({
  id,
  model: 'DS',
  modelName: 'ds/deepseek-v4-flash',
  title: `会话 ${id}`,
  time: '现在',
  group: 'current',
  tabId: null,
  mainProjectId: 'p-1',
  effortLevel: null,
  ultracodeEnabled: null,
  bareMode: null,
  messageCount: 3,
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
})

const click = (el: Element) => act(() => {
  el.dispatchEvent(new MouseEvent('click', { bubbles: true }))
})

const menuItems = () => [...container.querySelectorAll('.session-menu .sm-item')].map((e) => e.textContent)

describe('SessionList · 会话 ⋯ 菜单「复制」', () => {
  it('⋯ 菜单里有「复制」项', () => {
    act(() => {
      root.render(
        <SessionList
          sessions={[mkSession('s1', recentIso(0))]}
          activeSession=""
          switchSession={() => {}}
        />,
      )
    })
    click(container.querySelector('.session-menu-btn') as Element)
    expect(menuItems()).toContain('复制')
  })

  it('点「复制」⇒ 回调带上会话 id，且菜单收起', () => {
    const onDuplicateSession = vi.fn()
    act(() => {
      root.render(
        <SessionList
          sessions={[mkSession('s-42', recentIso(0))]}
          activeSession=""
          switchSession={() => {}}
          onDuplicateSession={onDuplicateSession}
        />,
      )
    })
    click(container.querySelector('.session-menu-btn') as Element)
    const item = [...container.querySelectorAll('.session-menu .sm-item')].find((e) => e.textContent === '复制') as HTMLElement
    click(item)

    expect(onDuplicateSession).toHaveBeenCalledTimes(1)
    expect(onDuplicateSession).toHaveBeenCalledWith('s-42')
    expect(menuItems()).toHaveLength(0)
  })
})
