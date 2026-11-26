// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { SessionList } from '../SessionList'
import type { SessionDto } from '@/api/types'

/**
 * 「进行中」块（原「正在运行」，2026-11-26 纳入已完成未读后改名；用户 2026-11-23 认可的设计稿）。
 * 意图：运行的会话浮在最上面一眼可见，但不能从原分组搬走（「下方文件组不隐藏」）；空则整块消失。
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
})

async function mount(props: {
  sessions: SessionDto[]
  runningSessionIds?: Set<string>
  pendingSessionIds?: Set<string>
  doneUnreadIds?: Set<string>
}) {
  act(() => {
    root.render(
      <SessionList
        activeSession=""
        switchSession={() => {}}
        projectNameFor={() => '项目A'}
        workspaceRedesign
        sessions={props.sessions}
        runningSessionIds={props.runningSessionIds}
        pendingSessionIds={props.pendingSessionIds}
        doneUnreadIds={props.doneUnreadIds}
      />,
    )
  })
  await act(async () => { await Promise.resolve(); await Promise.resolve() })
}

const runBlock = () => container.querySelector('.running-block')

function titlesIn(el: Element | null): string[] {
  return Array.from(el?.querySelectorAll('.session-item .title') ?? []).map((n) => n.textContent ?? '')
}

describe('SessionList · 进行中块（运行/待处理）', () => {
  it('运行中会话进块，同时仍留在项目分组里（不隐藏）', async () => {
    await mount({
      sessions: [mkSession('s-run', '2026-11-02T00:00:00Z'), mkSession('s-idle', '2026-11-01T00:00:00Z')],
      runningSessionIds: new Set(['s-run']),
    })
    expect(runBlock()).toBeTruthy()
    expect(runBlock()?.querySelector('.group-path')?.textContent).toBe('进行中')
    expect(titlesIn(runBlock())).toEqual(['会话 s-run'])
    expect(runBlock()?.querySelector('.session-item .status-dot.running')).toBeTruthy()
    expect(runBlock()?.querySelector('.run-elapsed')?.textContent).toMatch(/^\d+:\d{2}$/)

    const groups = Array.from(container.querySelectorAll('.left-section'))
      .filter((el) => !el.classList.contains('running-block'))
    expect(groups.some((el) => titlesIn(el).includes('会话 s-run')), '原分组不得隐藏该会话').toBe(true)
  })

  it('等待权限的会话也收进块（待处理角标分开计）', async () => {
    await mount({
      sessions: [mkSession('s-pend', '2026-11-01T00:00:00Z')],
      pendingSessionIds: new Set(['s-pend']),
    })
    expect(titlesIn(runBlock())).toEqual(['会话 s-pend'])
    expect(runBlock()?.querySelector('.label-right .run-badge.pending')?.textContent).toContain('待处理')
  })

  it('没有运行/待处理会话 ⇒ 整块不渲染', async () => {
    await mount({ sessions: [mkSession('s-idle', '2026-11-01T00:00:00Z')], runningSessionIds: new Set() })
    expect(runBlock()).toBeNull()
  })

  it('折叠后行隐藏、标题角标仍在', async () => {
    await mount({
      sessions: [mkSession('s-run', '2026-11-01T00:00:00Z')],
      runningSessionIds: new Set(['s-run']),
    })
    const label = runBlock()?.querySelector('.label') as HTMLElement
    act(() => { label.click() })
    expect(titlesIn(runBlock())).toEqual([])
    expect(runBlock()?.querySelector('.label-right .run-badge')?.textContent).toContain('运行中')
  })
})

describe('SessionList · 进行中块纳入「已完成未读」（2026-11-26）', () => {
  it('已完成未读进块：绿点 + 相对时间（无运行时长）+「已完成」角标', async () => {
    await mount({ sessions: [mkSession('s-done', '2026-11-01T00:00:00Z')], doneUnreadIds: new Set(['s-done']) })
    expect(titlesIn(runBlock())).toEqual(['会话 s-done'])
    expect(runBlock()?.querySelector('.session-item .status-dot.done')).toBeTruthy()
    expect(runBlock()?.querySelector('.session-item .run-elapsed')).toBeNull()
    expect(runBlock()?.querySelector('.session-item .time')).toBeTruthy()
    expect(runBlock()?.querySelector('.label-right .run-badge.done')?.textContent).toContain('已完成')
  })

  it('只有已完成未读（无运行/待处理）⇒ 块仍渲染、无运行脉冲', async () => {
    await mount({ sessions: [mkSession('s-done', '2026-11-01T00:00:00Z')], doneUnreadIds: new Set(['s-done']) })
    expect(runBlock()).toBeTruthy()
    expect(runBlock()?.querySelector('.group-path')?.textContent).toBe('进行中')
    expect(runBlock()?.querySelector('.run-pulse')).toBeNull()
  })

  it('查看即移出：doneUnreadIds 清除后该会话离开块（WHY：用户要「查看完成就立马移出」）', async () => {
    const s = mkSession('s-done', '2026-11-01T00:00:00Z')
    await mount({ sessions: [s], doneUnreadIds: new Set(['s-done']) })
    expect(titlesIn(runBlock())).toEqual(['会话 s-done'])
    // 模拟 App 切到该会话 ⇒ 清除 doneUnreadIds（App.tsx:1946 effect）
    await mount({ sessions: [s], doneUnreadIds: new Set() })
    expect(runBlock()).toBeNull()
  })

  it('运行中优先：既 running 又 doneUnread ⇒ 只算运行段，不重复、无已完成角标', async () => {
    await mount({ sessions: [mkSession('s-x', '2026-11-01T00:00:00Z')], runningSessionIds: new Set(['s-x']), doneUnreadIds: new Set(['s-x']) })
    expect(titlesIn(runBlock())).toEqual(['会话 s-x'])
    expect(runBlock()?.querySelector('.run-badge.done')).toBeNull()
    expect(runBlock()?.querySelector('.run-badge')?.textContent).toContain('运行中')
  })
})
