// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { SessionList } from '../SessionList'
import type { SessionDto } from '@/api/types'

// [folder-group 2026-11-26] 普通会话（plain）日期用「相对今天」造：它落在项目分组，
// 固定旧日期会被「超过3天收进更早」默认收起导致行不可见。派生/置顶/IM 成员不受归档影响。
const recentIso = (daysAgo = 0): string => {
  const d = new Date(); d.setHours(12, 0, 0, 0); d.setDate(d.getDate() - daysAgo); return d.toISOString()
}

/**
 * [session-family] 左栏「派生」块 + 家族色 + 三块折叠。
 *
 * <b>WHY 渲染真组件</b>：根解析/配色有纯函数测试，但「同一会话会不会在项目分组里重复」
 * 「置顶的创建者会不会又出现在派生块」「点标题是否真的收起」全在组件里 ——
 * 只有渲染真组件才能证明接线正确。
 */

const mkSession = (
  id: string,
  updatedAt: string,
  opts: { pinned?: boolean; parent?: string; project?: string } = {},
): SessionDto => ({
  id,
  model: 'DS',
  modelName: 'ds/deepseek-v4-flash',
  title: `会话 ${id}`,
  time: '现在',
  group: 'current',
  tabId: null,
  mainProjectId: opts.project ?? 'p-1',
  effortLevel: null,
  ultracodeEnabled: null,
  bareMode: null,
  messageCount: 1,
  createdAt: updatedAt,
  updatedAt,
  pinned: opts.pinned,
  derivedFromSessionId: opts.parent ?? null,
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

const rowEls = () => Array.from(container.querySelectorAll<HTMLElement>('.session-item'))
const titlesIn = (selector: string) =>
  Array.from(container.querySelectorAll(`${selector} .session-item`)).map((e) => e.textContent ?? '')
const rowByTitle = (id: string) => rowEls().find((e) => (e.textContent ?? '').includes(`会话 ${id}`))
const countTitle = (id: string) =>
  rowEls().filter((e) => (e.textContent ?? '').includes(`会话 ${id}`)).length
const famColor = (id: string) => rowByTitle(id)?.style.getPropertyValue('--fam') ?? ''
const rowIn = (selector: string, id: string) =>
  Array.from(container.querySelectorAll<HTMLElement>(`${selector} .session-item`)).find((e) =>
    (e.textContent ?? '').includes(`会话 ${id}`),
  )

const render = (sessions: SessionDto[], extra: Partial<Parameters<typeof SessionList>[0]> = {}) =>
  act(() => {
    root.render(
      <SessionList activeSession="" switchSession={() => {}} sessions={sessions} {...extra} />,
    )
  })

describe('SessionList · [session-family] 派生块', () => {
  it('派生块按源分组：源渲染成组标题，组内是它派生的会话；这些会话不再出现在项目分组里', () => {
    render([
      mkSession('root', '2026-11-03T00:00:00Z'),
      mkSession('child', '2026-11-02T00:00:00Z', { parent: 'root' }),
      mkSession('plain', recentIso(0)),
    ])
    // 源是完整会话行：有家族色条 + 折叠按钮（与普通行同样的 hover/状态/时间）
    const rootRow = rowByTitle('root')
    expect(rootRow?.querySelector('.session-collapse-btn')).toBeTruthy()
    expect(rootRow?.querySelector('.fam-bar')).toBeTruthy()
    expect(famColor('root')).toBeTruthy()
    // 组内是源 + 它派生的会话
    expect(titlesIn('.family-block').length).toBe(2)
    expect(titlesIn('.family-block').some((t) => t.includes('会话 root'))).toBe(true)
    expect(titlesIn('.family-block').some((t) => t.includes('会话 child'))).toBe(true)
    // 全栏每个会话只出现一次（普通会话仍在项目分组）
    expect(countTitle('root')).toBe(1)
    expect(countTitle('child')).toBe(1)
    expect(countTitle('plain')).toBe(1)
  })

  it('两个源 → 两个组标题；组可单独展开/收起', () => {
    render([
      mkSession('rootA', '2026-11-06T00:00:00Z'),
      mkSession('childA', '2026-11-05T00:00:00Z', { parent: 'rootA' }),
      mkSession('rootB', '2026-11-04T00:00:00Z'),
      mkSession('childB', '2026-11-03T00:00:00Z', { parent: 'rootB' }),
    ])
    expect(container.querySelectorAll('.family-group').length).toBe(2)
    expect(titlesIn('.family-block').length).toBe(4)

    // 收起源 A 的组：只隐藏 A 的子会话，B 不受影响
    const toggleA = rowByTitle('rootA')!.querySelector('.session-collapse-btn')!
    click(toggleA)
    const afterCollapse = titlesIn('.family-block')
    expect(afterCollapse.some((t) => t.includes('会话 childA'))).toBe(false)
    expect(afterCollapse.some((t) => t.includes('会话 childB'))).toBe(true)
    click(toggleA)
    expect(titlesIn('.family-block').some((t) => t.includes('会话 childA'))).toBe(true)
  })

  it('同族同色、两族异色（--fam 一致/不一致）', () => {
    render([
      mkSession('rootA', '2026-11-05T00:00:00Z'),
      mkSession('childA', '2026-11-04T00:00:00Z', { parent: 'rootA' }),
      mkSession('rootB', '2026-11-03T00:00:00Z'),
      mkSession('childB', '2026-11-02T00:00:00Z', { parent: 'rootB' }),
    ])
    expect(famColor('rootA')).toBeTruthy()
    expect(famColor('childA')).toBe(famColor('rootA'))
    expect(famColor('childB')).toBe(famColor('rootB'))
    expect(famColor('childA')).not.toBe(famColor('childB'))
  })

  it('普通会话没有家族色（不渲染色条）', () => {
    render([mkSession('plain', recentIso(0))])
    expect(famColor('plain')).toBe('')
    expect(rowByTitle('plain')?.querySelector('.fam-bar')).toBeNull()
  })

  it('⭐ 置顶的创建者：置顶块里有整行，派生块里作为「源」组标题，不重复成行', () => {
    render([
      mkSession('root', '2026-11-03T00:00:00Z', { pinned: true }),
      mkSession('child', '2026-11-02T00:00:00Z', { parent: 'root' }),
    ])
    const pinnedSection = container.querySelectorAll('.left-section')[0]
    const pinnedTitles = Array.from(pinnedSection.querySelectorAll('.session-item')).map((e) => e.textContent ?? '')
    expect(pinnedTitles.some((t) => t.includes('会话 root'))).toBe(true)
    // 派生块：源本身也是一行（带折叠按钮），子会话在它下面
    expect(titlesIn('.family-block').some((t) => t.includes('会话 root'))).toBe(true)
    expect(titlesIn('.family-block').some((t) => t.includes('会话 child'))).toBe(true)
    expect(rowIn('.family-block', 'root')?.querySelector('.session-collapse-btn')).toBeTruthy()
    // 跨块同色：置顶里的创建者与派生块里的子会话
    expect(famColor('root')).toBe(famColor('child'))
  })

  it('⭐ 置顶成员在派生块 / IM 块里出现时，行首是金色实心星（不是空白星）', () => {
    render(
      [
        mkSession('root', '2026-11-03T00:00:00Z', { pinned: true }),
        mkSession('child', '2026-11-02T00:00:00Z', { parent: 'root', pinned: true }),
      ],
      { imSessionIds: new Set(['child']) },
    )
    // child：置顶块 1 + 派生块 1 + IM 块 1 = 3 处，且每一处都是 .pinned 实心星
    const childRows = rowEls().filter((e) => (e.textContent ?? '').includes('会话 child'))
    expect(childRows.length).toBe(3)
    for (const row of childRows) {
      const btn = row.querySelector('.session-pin-btn')
      expect(btn?.classList.contains('pinned')).toBe(true)
      expect(btn?.querySelector('svg')?.getAttribute('fill')).toBe('currentColor')
    }
  })

  it('⭐ IM ∩ 派生：同一会话在两块都出现，颜色一致', () => {
    render(
      [
        mkSession('root', '2026-11-03T00:00:00Z'),
        mkSession('child', '2026-11-02T00:00:00Z', { parent: 'root' }),
      ],
      { imSessionIds: new Set(['child']) },
    )
    expect(titlesIn('.family-block').some((t) => t.includes('会话 child'))).toBe(true)
    expect(titlesIn('.im-block').some((t) => t.includes('会话 child'))).toBe(true)
    expect(famColor('child')).toBe(famColor('root'))
  })
})

describe('SessionList · [session-family] 三块可折叠', () => {
  it('置顶块点标题收起/展开', () => {
    render([mkSession('pinned', recentIso(0), { pinned: true })])
    const label = container.querySelector('.pinned-label')!
    expect(countTitle('pinned')).toBe(1)
    click(label)
    expect(countTitle('pinned')).toBe(0)
    click(label)
    expect(countTitle('pinned')).toBe(1)
  })

  it('派生块点标题收起/展开', () => {
    render([
      mkSession('root', '2026-11-03T00:00:00Z'),
      mkSession('child', '2026-11-02T00:00:00Z', { parent: 'root' }),
    ])
    const label = container.querySelector('.family-label')!
    expect(titlesIn('.family-block').length).toBe(2)
    click(label)
    expect(titlesIn('.family-block').length).toBe(0)
    click(label)
    expect(titlesIn('.family-block').length).toBe(2)
  })

  it('IM 块点标题收起/展开（点「查看全部」不会连带收起）', () => {
    const ids = ['a', 'b', 'c', 'd']
    render(
      ids.map((id, i) => mkSession(`s-${id}`, `2026-11-0${i + 1}T00:00:00Z`)),
      { imSessionIds: new Set(ids.map((id) => `s-${id}`)) },
    )
    const label = container.querySelector('.im-label')!
    expect(titlesIn('.im-block').length).toBe(3)
    const more = Array.from(container.querySelectorAll('button')).find((b) =>
      b.textContent?.includes('查看全部'),
    )!
    click(more)
    expect(titlesIn('.im-block').length).toBe(4)
    click(label)
    expect(titlesIn('.im-block').length).toBe(0)
    click(label)
    expect(titlesIn('.im-block').length).toBe(4)
  })
})
