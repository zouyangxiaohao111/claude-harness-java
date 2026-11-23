// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { SessionList } from '../SessionList'
import type { SessionDto } from '@/api/types'

/**
 * 「正在运行」块（用户 2026-11-23 认可的设计稿）。
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
      />,
    )
  })
  await act(async () => { await Promise.resolve(); await Promise.resolve() })
}

const runBlock = () => container.querySelector('.running-block')

function titlesIn(el: Element | null): string[] {
  return Array.from(el?.querySelectorAll('.session-item .title') ?? []).map((n) => n.textContent ?? '')
}

describe('SessionList · 正在运行块', () => {
  it('运行中会话进块，同时仍留在项目分组里（不隐藏）', async () => {
    await mount({
      sessions: [mkSession('s-run', '2026-11-02T00:00:00Z'), mkSession('s-idle', '2026-11-01T00:00:00Z')],
      runningSessionIds: new Set(['s-run']),
    })
    expect(runBlock()).toBeTruthy()
    expect(runBlock()?.querySelector('.group-path')?.textContent).toBe('正在运行')
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
