// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { WorkspaceHeader, type WorkspaceHistoryPanelState } from '../WorkspaceHeader'

/**
 * [历史提问跳转] 行点击 = 跳转（onJump），不是压缩（onCompact）。
 *
 * <b>WHY（规则九）</b>：用户点一行提问的意图是「回到那次提问」；若仍走 onCompact，
 * 点击会直接打开压缩流程 —— 语义完全相反且不可撤销。压缩 / 裁剪只能由各自按钮触发。
 */

const noop = () => {}

function historyOf(overrides: Partial<WorkspaceHistoryPanelState> = {}): WorkspaceHistoryPanelState {
  return {
    open: true,
    loading: false,
    error: null,
    candidates: [
      { id: 'm-1', createdAt: '2026-10-28T10:00:00Z', previewSource: '第一个问题', removedAfter: 1 },
      { id: 'm-2', createdAt: '2026-10-28T11:00:00Z', previewSource: '第二个问题', removedAfter: 0 },
    ],
    onClose: noop,
    onRetry: noop,
    onJump: noop,
    onCompact: noop,
    onTrim: noop,
    ...overrides,
  }
}

let container: HTMLDivElement
let root: Root

function renderHeader(history: WorkspaceHistoryPanelState) {
  root = createRoot(container)
  act(() => {
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
        showPanelToggle
        rightCollapsed
        onTogglePanel={noop}
      />,
    )
  })
}

function actionByText(label: string): HTMLButtonElement | null {
  return Array.from(container.querySelectorAll<HTMLButtonElement>('.ws-hp-act'))
    .find((b) => (b.textContent ?? '').trim() === label) ?? null
}

describe('[历史提问] 行点击跳转', () => {
  beforeEach(() => {
    (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    container = document.createElement('div')
    document.body.appendChild(container)
  })

  afterEach(() => {
    act(() => { root?.unmount() })
    container.remove()
    root = undefined as unknown as Root
  })

  it('点击提问行 → onJump(该行 id)，且不触发 onCompact（WHY：点击 ≠ 压缩）', () => {
    const onJump = vi.fn()
    const onCompact = vi.fn()
    renderHeader(historyOf({ onJump, onCompact }))

    const rows = container.querySelectorAll<HTMLButtonElement>('.ws-hp-main')
    expect(rows).toHaveLength(2)
    act(() => { rows[1]!.click() })

    expect(onJump).toHaveBeenCalledTimes(1)
    expect(onJump).toHaveBeenCalledWith('m-2')
    expect(onCompact).not.toHaveBeenCalled()
  })

  it('压缩 / 裁剪按钮保留各自行为（不触发行跳转）', () => {
    const onJump = vi.fn()
    const onCompact = vi.fn()
    const onTrim = vi.fn()
    renderHeader(historyOf({ onJump, onCompact, onTrim }))

    act(() => { actionByText('压缩')!.click() })
    act(() => { actionByText('裁剪')!.click() })

    expect(onCompact).toHaveBeenCalledTimes(1)
    expect(onTrim).toHaveBeenCalledTimes(1)
    expect(onJump).not.toHaveBeenCalled()
  })

  it('loading / error / 重试 通道保持不变', () => {
    const onRetry = vi.fn()
    renderHeader(historyOf({ loading: true }))
    expect(container.querySelector('.ws-hp-state')?.textContent).toContain('加载中')

    act(() => { root.unmount() })
    root = createRoot(container)
    act(() => {
      root.render(
        <WorkspaceHeader
          title="任务" isWork onRename={noop} onSearch={noop} onShare={noop} onTrace={noop}
          traceActive={false} onHistory={noop}
          history={historyOf({ error: '加载失败', onRetry })}
          showPanelToggle rightCollapsed onTogglePanel={noop}
        />,
      )
    })
    expect(container.querySelector('.ws-hp-state')?.textContent).toContain('加载失败')
    const retry = container.querySelector<HTMLButtonElement>('.ws-hp-retry')
    expect(retry).not.toBeNull()
    act(() => { retry!.click() })
    expect(onRetry).toHaveBeenCalledTimes(1)
  })
})
