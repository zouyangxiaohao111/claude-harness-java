// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { WorkspaceHeader, type WorkspaceHistoryPanelState } from '../WorkspaceHeader'

/**
 * 右上角收件箱入口：有数量显示角标；点击打开收件箱（不是只切会话）。
 * WHY：入口被删过一次，用户明确要求恢复；若只剩左栏「待处理」，主区没有常驻入口。
 */
const noop = () => {}

const history: WorkspaceHistoryPanelState = {
  open: false,
  loading: false,
  error: null,
  candidates: [],
  onClose: noop,
  onRetry: noop,
  onJump: noop,
  onCompact: noop,
  onTrim: noop,
}

let container: HTMLDivElement
let root: Root | null = null

function renderHeader(inboxCount: number, onOpenInbox: () => void): void {
  act(() => {
    root = createRoot(container)
    root.render(
      <WorkspaceHeader
        title="任务"
        isWork
        onRename={noop}
        onSearch={noop}
        onShare={noop}
        onTrace={noop}
        traceActive={false}
        onHistory={noop}
        history={history}
        inboxCount={inboxCount}
        onOpenInbox={onOpenInbox}
        showPanelToggle
        rightCollapsed
        onTogglePanel={noop}
      />,
    )
  })
}

beforeEach(() => {
  (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  container = document.createElement('div')
  document.body.appendChild(container)
})

afterEach(() => {
  act(() => { root?.unmount() })
  root = null
  container.remove()
})

describe('WorkspaceHeader · 收件箱入口', () => {
  it('有数量显示角标；点击回调 onOpenInbox', () => {
    const onOpen = vi.fn()
    renderHeader(3, onOpen)
    const entry = container.querySelector<HTMLButtonElement>('.inbox-entry')!
    expect(entry).toBeTruthy()
    expect(entry.querySelector('.inbox-badge')?.textContent).toBe('3')
    act(() => { entry.click() })
    expect(onOpen).toHaveBeenCalledTimes(1)
  })

  it('数量为 0 时入口仍在、无角标', () => {
    renderHeader(0, noop)
    expect(container.querySelector('.inbox-entry')).toBeTruthy()
    expect(container.querySelector('.inbox-badge')).toBeNull()
  })
})
