// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SessionList } from '../SessionList'
import type { SessionDto } from '@/api/types'

/**
 * 左栏「IM 频道」一级入口 + 「IM 会话」块。
 *
 * <b>WHY 渲染真组件而不是只测纯函数</b>：这两块的行为全在组件里 ——
 * 入口是否按 workspaceRedesign 条件渲染、点击是否真的回调、
 * 「置顶优先（同一条不重复出现）」这条排序规则是否真的生效。
 */

const mkSession = (id: string, updatedAt: string, pinned?: boolean): SessionDto => ({
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

const labelTexts = () =>
  Array.from(container.querySelectorAll('.label .group-path')).map((e) => e.textContent)

const rowTitles = () =>
  Array.from(container.querySelectorAll('.session-item')).map((e) => e.textContent ?? '')

/** 只取「IM 会话」块内的行（该块有 im-block 类，避免把下面任务分组里的同一条数进来）。 */
const imBlockTitles = () =>
  Array.from(container.querySelectorAll('.im-block .session-item')).map((e) => e.textContent ?? '')

describe('SessionList · IM 频道入口', () => {
  it('workspaceRedesign 下渲染「IM 频道」入口，点击回调 onOpenIm', () => {
    const onOpenIm = vi.fn()
    act(() => {
      root.render(
        <SessionList
          activeSession=""
          switchSession={() => {}}
          sessions={[mkSession('s1', '2026-11-01T00:00:00Z')]}
          onOpenIm={onOpenIm}
          workspaceRedesign
        />,
      )
    })
    const entry = Array.from(container.querySelectorAll('.left-entry')).find((e) =>
      e.textContent?.includes('IM 频道'),
    )
    expect(entry).toBeTruthy()
    click(entry!)
    expect(onOpenIm).toHaveBeenCalledTimes(1)
  })

  it('imActive=true ⇒ 入口带 on 类（高亮）', () => {
    act(() => {
      root.render(
        <SessionList activeSession="" switchSession={() => {}} sessions={[]} onOpenIm={() => {}} imActive workspaceRedesign />,
      )
    })
    const entry = Array.from(container.querySelectorAll('.left-entry')).find((e) =>
      e.textContent?.includes('IM 频道'),
    )
    expect(entry?.className).toContain('ws-nav-im')
    expect(entry?.className).toContain('on')
  })

  it('不传 onOpenIm ⇒ 不渲染入口（其它宿主不受影响）', () => {
    act(() => {
      root.render(<SessionList activeSession="" switchSession={() => {}} sessions={[]} workspaceRedesign />)
    })
    const entry = Array.from(container.querySelectorAll('.left-entry')).find((e) =>
      e.textContent?.includes('IM 频道'),
    )
    expect(entry).toBeFalsy()
  })
})

describe('SessionList · IM 会话块', () => {
  it('有接线的会话渲染在「IM 会话」块里', () => {
    act(() => {
      root.render(
        <SessionList
          activeSession=""
          switchSession={() => {}}
          sessions={[
            mkSession('s-plain', '2026-11-05T00:00:00Z'),
            mkSession('s-im', '2026-11-01T00:00:00Z'),
          ]}
          imSessionIds={new Set(['s-im'])}
          workspaceRedesign
        />,
      )
    })
    expect(labelTexts()).toContain('IM 会话')
    expect(imBlockTitles().some((t) => t.includes('会话 s-im'))).toBe(true)
  })

  it('没有接线 ⇒ 不渲染该块', () => {
    act(() => {
      root.render(
        <SessionList activeSession="" switchSession={() => {}} sessions={[mkSession('s1', '2026-11-01T00:00:00Z')]} imSessionIds={new Set()} workspaceRedesign />,
      )
    })
    expect(labelTexts()).not.toContain('IM 会话')
  })

  it('⭐ [session-family] 既是 IM 会话又已置顶 ⇒ 置顶块与 IM 块都出现（普通分组不重复）', () => {
    act(() => {
      root.render(
        <SessionList
          activeSession=""
          switchSession={() => {}}
          sessions={[mkSession('s-both', '2026-11-01T00:00:00Z', true)]}
          imSessionIds={new Set(['s-both'])}
          workspaceRedesign
        />,
      )
    })
    // 两个视图块都保留该条（2026-11-23 用户裁定：IM / 派生 / 置顶可重叠），任务分组不含置顶
    expect(labelTexts()).toContain('置顶')
    expect(labelTexts()).toContain('IM 会话')
    const occurrences = rowTitles().filter((t) => t.includes('会话 s-both')).length
    expect(occurrences).toBe(2)   // 置顶块 1 + IM 块 1；任务分组已排除置顶
  })

  it('⭐ [session-family] IM 会话不再出现在任务分组里（全栏只出现一次）', () => {
    act(() => {
      root.render(
        <SessionList
          activeSession=""
          switchSession={() => {}}
          sessions={[mkSession('s-im', '2026-11-01T00:00:00Z')]}
          imSessionIds={new Set(['s-im'])}
          workspaceRedesign
        />,
      )
    })
    // 只在 IM 块出现；下面的项目分组（含「未分组」）已排除 IM 接线会话
    expect(imBlockTitles().length).toBe(1)
    expect(rowTitles().filter((t) => t.includes('会话 s-im')).length).toBe(1)
  })

  it('超过 3 条默认折叠，点「查看全部」展开', () => {
    const ids = ['a', 'b', 'c', 'd']
    act(() => {
      root.render(
        <SessionList
          activeSession=""
          switchSession={() => {}}
          sessions={ids.map((id, i) => mkSession(`s-${id}`, `2026-11-0${i + 1}T00:00:00Z`))}
          imSessionIds={new Set(ids.map((id) => `s-${id}`))}
          workspaceRedesign
        />,
      )
    })
    const before = imBlockTitles().length
    expect(before).toBe(3)
    const more = Array.from(container.querySelectorAll('button')).find((b) =>
      b.textContent?.includes('查看全部'),
    )
    expect(more).toBeTruthy()
    click(more!)
    const after = imBlockTitles().length
    expect(after).toBe(4)
  })
})
