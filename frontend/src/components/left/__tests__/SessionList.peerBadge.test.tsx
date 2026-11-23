// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SessionList } from '../SessionList'
import type { SessionDto } from '@/api/types'
import { sumPeerCounts, usePeerInboxStore } from '@/stores/peerInboxStore'

/**
 * [peer-badge] 全局跨会话待处理角标（用户已选 B 方案）。
 *
 * <b>WHY 渲染真组件 + 真 store</b>：角标的价值在于「不在当前会话也能看到别处有待处理」——
 * 单纯断言 store 里的 map 全绿也证明不了 SessionList 真的按 sessionId 取值渲染、汇总真的
 * 等于各会话之和、点角标真的切会话。所以这里用与 App 同构的 Host（从 store 取
 * `countsBySession` / `totalPending`）驱动真组件。
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

const A = 'sess-a'
const B = 'sess-b'

let container: HTMLDivElement
let root: Root
let switchSession: ReturnType<typeof vi.fn<(id: string) => void>>
let onOpenPeerInbox: ReturnType<typeof vi.fn<() => void>>
let onOpenPeerPending: ReturnType<typeof vi.fn<(id: string) => void>> | null

/** 与 App.tsx 同构：数据源 = store 的 countsBySession / totalPending（不是组件自己算）。 */
function Host({ sessions, activeSession = '' }: { sessions: SessionDto[]; activeSession?: string }) {
  const counts = usePeerInboxStore((s) => s.countsBySession)
  const total = usePeerInboxStore((s) => s.totalPending)
  return (
    <SessionList
      sessions={sessions}
      activeSession={activeSession}
      switchSession={switchSession}
      projectNameFor={() => '项目一'}
      peerPendingCounts={counts}
      peerTotalPending={total}
      onOpenPeerInbox={onOpenPeerInbox}
      onOpenPeerPending={onOpenPeerPending ?? undefined}
    />
  )
}

const render = (sessions: SessionDto[], activeSession = '') => {
  act(() => { root.render(<Host sessions={sessions} activeSession={activeSession} />) })
}

const rowOf = (id: string): Element =>
  [...container.querySelectorAll('.session-item')]
    .find((el) => el.querySelector('.title')?.textContent === `会话 ${id}`)!

const badgeOf = (id: string): Element | null => rowOf(id).querySelector('.session-peer-badge')

const click = (el: Element) => act(() => {
  el.dispatchEvent(new MouseEvent('click', { bubbles: true }))
})

beforeEach(() => {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  switchSession = vi.fn<(id: string) => void>()
  onOpenPeerInbox = vi.fn<() => void>()
  onOpenPeerPending = null
  usePeerInboxStore.setState({
    sessionId: null,
    items: [],
    pendingCount: 0,
    unreadCount: 0,
    countsBySession: {},
    totalPending: 0,
    lastSeq: null,
    open: false,
    inboxItems: [],
    inboxLoading: false,
    inboxError: null,
    inboxNotice: null,
    inboxRowErrors: {},
    inboxActing: null,
    jumpRequest: null,
  })
})

afterEach(() => {
  act(() => root.unmount())
  container.remove()
})

describe('SessionList · [peer-badge] 会话行角标', () => {
  it('a) 有 pending 的会话显示数字，0 的会话不显示', () => {
    const counts = { [A]: 2, [B]: 0 }
    usePeerInboxStore.setState({ countsBySession: counts, totalPending: sumPeerCounts(counts) })
    render([mkSession(A, '2026-11-01T00:00:00Z'), mkSession(B, '2026-10-01T00:00:00Z')])

    const badge = badgeOf(A)
    expect(badge, 'A 有 2 条待处理 ⇒ 必须有角标').toBeTruthy()
    expect(badge!.textContent, '角标是纯文本数字').toContain('2')
    expect(badgeOf(B), 'B 计数为 0 ⇒ 不渲染角标').toBeNull()
  })

  it('b) 顶部汇总 = 各会话 pending 之和（口径 = store.totalPending）', () => {
    const counts = { [A]: 2, [B]: 3 }
    const total = sumPeerCounts(counts)
    usePeerInboxStore.setState({ countsBySession: counts, totalPending: total })
    render([mkSession(A, '2026-11-01T00:00:00Z'), mkSession(B, '2026-10-01T00:00:00Z')])

    const summary = container.querySelector('.left-peer-total')
    expect(summary, '有 pending ⇒ 顶部汇总必须渲染').toBeTruthy()
    expect(summary!.querySelector('.n')?.textContent).toBe('5')
    // 与各会话数字之和一致（不是某一会话的数）
    expect(summary!.querySelector('.n')?.textContent).toBe(String(2 + 3))
    expect(usePeerInboxStore.getState().totalPending).toBe(5)
  })

  it('c) 点击行内角标切到对应会话，且不触发整行 onClick 的二次切换', () => {
    const counts = { [A]: 1, [B]: 4 }
    usePeerInboxStore.setState({ countsBySession: counts, totalPending: sumPeerCounts(counts) })
    render([mkSession(A, '2026-11-01T00:00:00Z'), mkSession(B, '2026-10-01T00:00:00Z')], A)

    click(badgeOf(B)!)
    expect(switchSession).toHaveBeenCalledTimes(1)
    expect(switchSession).toHaveBeenCalledWith(B)
  })

  it('c2) 提供了 onOpenPeerPending 时，行内角标走跳转（不再直接 switchSession）', () => {
    const counts = { [A]: 1, [B]: 4 }
    usePeerInboxStore.setState({ countsBySession: counts, totalPending: sumPeerCounts(counts) })
    onOpenPeerPending = vi.fn<(id: string) => void>()
    render([mkSession(A, '2026-11-01T00:00:00Z'), mkSession(B, '2026-10-01T00:00:00Z')], A)

    click(badgeOf(B)!)
    expect(onOpenPeerPending).toHaveBeenCalledWith(B)
    expect(switchSession).not.toHaveBeenCalled()
  })

  it('d) 汇总为 0 时不渲染整块；全 0 计数也不渲染', () => {
    render([mkSession(A, '2026-11-01T00:00:00Z')])
    expect(container.querySelector('.left-peer-total')).toBeNull()
    expect(container.querySelector('.session-peer-badge')).toBeNull()

    usePeerInboxStore.setState({ countsBySession: { [A]: 0, [B]: 0 }, totalPending: 0 })
    // 重渲染触发选择器读取
    render([mkSession(A, '2026-11-01T00:00:00Z')])
    expect(container.querySelector('.left-peer-total')).toBeNull()
    expect(container.querySelector('.session-peer-badge')).toBeNull()
  })

  it('e) 深浅色适配 = 全走主题变量、无硬编码色、无动画', () => {
    // 组件本身不感知主题（由 `.app[data-theme]` 覆盖变量）⇒ 判据落在样式表：
    // 角标色必须是 var(--accent*) 而不是十六进制，否则深色模式下会读不清。
    const css = readFileSync(join(process.cwd(), 'src/styles/globals.css'), 'utf8')
    const start = css.indexOf('/* [peer-badge]')
    expect(start, '必须存在 [peer-badge] 样式块').toBeGreaterThan(-1)
    const end = css.indexOf('.model-tag {', start)
    const block = css.slice(start, end)

    expect(block).toContain('.session-item .session-peer-badge')
    expect(block).toContain('.left-peer-total')
    expect(block).toContain('var(--accent)')
    expect(block).toContain('var(--accent-soft)')
    expect(block).not.toMatch(/#[0-9a-fA-F]{3,8}\b/)
    expect(block).not.toContain('animation')
  })

  it('f) 不影响置顶块与运行状态点（三者可并存）', () => {
    const counts = { [A]: 1, [B]: 0 }
    usePeerInboxStore.setState({ countsBySession: counts, totalPending: sumPeerCounts(counts) })
    act(() => {
      root.render(
        <SessionList
          sessions={[mkSession(A, '2026-09-01T00:00:00Z', true), mkSession(B, '2026-11-01T00:00:00Z')]}
          activeSession={B}
          switchSession={switchSession}
          projectNameFor={() => '项目一'}
          peerPendingCounts={counts}
          peerTotalPending={sumPeerCounts(counts)}
          onOpenPeerInbox={onOpenPeerInbox}
          runningSessionIds={new Set([B])}
        />,
      )
    })

    // [run-block 2026-11-23] 运行块在最前；置顶块紧随其后、置顶星标仍是实心态
    const sectionTitles = Array.from(container.querySelectorAll('.left-section > .label .group-path'))
      .map((el) => el.textContent)
    expect(sectionTitles.slice(0, 2)).toEqual(['正在运行', '置顶'])
    expect(rowOf(A).querySelector('.session-pin-btn')?.getAttribute('aria-label')).toBe('取消置顶')
    // 运行中状态点仍在（角标不抢占状态点的位置）
    expect(rowOf(B).querySelector('.status-dot.running')).toBeTruthy()
    // 角标同时存在
    expect(badgeOf(A)?.textContent).toContain('1')
    expect(badgeOf(B)).toBeNull()
  })

  it('g) 聚合事件到达后角标与汇总即时变化（无需切会话/轮询）', () => {
    render([mkSession(A, '2026-11-01T00:00:00Z'), mkSession(B, '2026-10-01T00:00:00Z')])
    expect(container.querySelector('.session-peer-badge')).toBeNull()
    expect(container.querySelector('.left-peer-total')).toBeNull()

    act(() => {
      usePeerInboxStore.getState().applyAggregateEvent({
        type: 'peer.inbox.hold', seq: 1, targetSessionId: A, pendingCount: 2,
      })
    })
    act(() => {
      usePeerInboxStore.getState().applyAggregateEvent({
        type: 'peer.inbox.hold', seq: 2, targetSessionId: B, pendingCount: 3,
      })
    })

    expect(badgeOf(A)?.textContent).toContain('2')
    expect(badgeOf(B)?.textContent).toContain('3')
    expect(container.querySelector('.left-peer-total .n')?.textContent).toBe('5')

    // 终态（服务端计数回落）同样即时生效
    act(() => {
      usePeerInboxStore.getState().applyAggregateEvent({
        type: 'peer.inbox.removed', seq: 3, targetSessionId: A, pendingCount: 0,
      })
    })
    expect(badgeOf(A)).toBeNull()
    expect(container.querySelector('.left-peer-total .n')?.textContent).toBe('3')
  })
})
