// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SessionList } from '../SessionList'
import type { SessionDto } from '@/api/types'

/**
 * [folder-group 2026-11-26] 文件夹分组「更早」归档子组接线测试。
 *
 * 用户诉求：文件夹展开后所有会话全平铺，会话一多要滚很久 ⇒ 超过 3 天的会话默认收进「更早」子组。
 * 本文件渲染真组件、断言 DOM：
 *  ① >3天进更早且默认收起（最近可见、更早隐藏、徽章计数正确）；
 *  ② 点击更早展开/收起；
 *  ③ 当前会话落在更早 ⇒ 自动展开（保证可见）；
 *  ④ 0 条更早 ⇒ 不渲染子组行；
 *  ⑤ 阈值边界：3天前可见、4天前收起；
 *  ⑥ 文件夹图标琥珀色（照右栏 FILE_KIND_COLORS.folder）。
 *
 * 反向实验：把 isOlderSession 的 `days > OLDER_THAN_DAYS` 改成 `days > 99` ⇒ ①③⑤ 必红。
 */

// 用「相对今天的天数」造数据，避免写死日期随时间失效；统一取正午规避自然日边界抖动。
const base = new Date()
base.setHours(12, 0, 0, 0)
const iso = (daysAgo: number): string => {
  const d = new Date(base)
  d.setDate(d.getDate() - daysAgo)
  return d.toISOString()
}
const mk = (id: string, daysAgo: number): SessionDto => ({
  id,
  model: 'DS',
  modelName: 'ds/deepseek-v4-flash',
  title: `会话 ${id}`,
  time: '现在',
  group: 'current',
  tabId: null,
  mainProjectId: 'p',
  effortLevel: null,
  ultracodeEnabled: null,
  bareMode: null,
  messageCount: 1,
  createdAt: iso(daysAgo),
  updatedAt: iso(daysAgo),
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

function render(sessions: SessionDto[], active: string) {
  act(() => {
    root.render(
      <SessionList
        sessions={sessions}
        activeSession={active}
        switchSession={vi.fn()}
        projectNameFor={() => '项目'}
      />,
    )
  })
}
const rows = () => [...container.querySelectorAll('.left-section .session-item')]
const rowIds = () => rows().map((el) => (el.querySelector('.title')?.textContent ?? '').replace('会话 ', ''))
const olderHead = () => container.querySelector('.older-head')
const olderBadge = () => container.querySelector('.older-badge')?.textContent ?? ''
const olderChevOpen = () => !!container.querySelector('.older-head .chev.open')
const clickOlder = () => act(() => { (olderHead() as HTMLElement).click() })

describe('SessionList · 文件夹分组「更早」归档子组', () => {
  it('超过3天收进更早且默认收起 · 最近可见、更早隐藏、徽章计数=更早条数', () => {
    render([mk('today', 0), mk('d3', 3), mk('d4', 4), mk('d7', 7)], 'today')
    expect(olderHead()).not.toBeNull()
    expect(olderBadge()).toBe('2') // d4 + d7
    expect(rowIds()).toEqual(['today', 'd3']) // 仅最近两条；更早默认收起
    expect(olderChevOpen()).toBe(false)
  })

  it('阈值边界：3天前仍可见、4天前收起', () => {
    render([mk('d3', 3), mk('d4', 4)], 'd3')
    expect(rowIds()).toEqual(['d3'])
    expect(olderBadge()).toBe('1')
  })

  it('点击更早展开 → 再点收起', () => {
    render([mk('today', 0), mk('d4', 4), mk('d7', 7)], 'today')
    expect(rows()).toHaveLength(1)
    clickOlder()
    expect(rows()).toHaveLength(3)
    expect(olderChevOpen()).toBe(true)
    clickOlder()
    expect(rows()).toHaveLength(1)
    expect(olderChevOpen()).toBe(false)
  })

  it('当前会话落在更早 ⇒ 自动展开（保证可见）', () => {
    render([mk('today', 0), mk('d4', 4), mk('d7', 7)], 'd7')
    expect(olderChevOpen()).toBe(true)
    expect(rowIds()).toContain('d7')
    expect(rows()).toHaveLength(3)
  })

  it('0 条更早 ⇒ 不渲染子组行', () => {
    render([mk('today', 0), mk('d3', 3)], 'today')
    expect(olderHead()).toBeNull()
    expect(rows()).toHaveLength(2)
  })

  it('文件夹图标琥珀色（照右栏 FILE_KIND_COLORS.folder #F59E0B）', () => {
    render([mk('today', 0)], 'today')
    const ic = container.querySelector('.left-section .label .folder-ic') as HTMLElement
    expect(ic).not.toBeNull()
    expect(ic.style.color).toBe('rgb(245, 158, 11)')
    expect(ic.querySelector('svg')).not.toBeNull()
  })
})