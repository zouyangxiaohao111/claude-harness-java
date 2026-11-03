// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { WorkspaceHeader, type WorkspaceHistoryPanelState } from '../WorkspaceHeader'

/**
 * 轨迹按钮在 trace 态是「返回对话」（tooltip 已这么写），点击必须真的回到对话。
 *
 * <b>WHY（CLAUDE.md 规则九 · 用户实测缺陷）</b>：旧实现 App.handleWorkspaceTrace 在
 * `centerView==='trace'` 分支只重试拉全量就 return，**全仓没有一处 setCenterView('chat')**
 * ⇒ 进了轨迹就再也回不去（用户原话「点击轨迹后没法点击回到会话，点击轨迹按钮没用」）。
 * 本用例钉住「trace 态下按钮文案 + 点击会触发回调」，App 侧的 toggle 由 App 单测覆盖。
 */

const noop = () => {}

function historyState(): WorkspaceHistoryPanelState {
  return {
    open: false, loading: false, error: null, candidates: [],
    onClose: noop, onRetry: noop, onJump: noop, onCompact: noop, onTrim: noop,
  }
}

let container: HTMLDivElement
let root: Root

function render(traceActive: boolean, onTrace: () => void) {
  root = createRoot(container)
  act(() => {
    root.render(
      <WorkspaceHeader
        title="任务"
        isWork
        onRename={noop}
        onSearch={noop}
        onShare={noop}
        onTrace={onTrace}
        traceActive={traceActive}
        traceCount={7}
        onHistory={noop}
        history={historyState()}
        showPanelToggle
        rightCollapsed
        onTogglePanel={noop}
      />,
    )
  })
}

beforeEach(() => {
  container = document.createElement('div')
  document.body.appendChild(container)
})

afterEach(() => {
  act(() => { root.unmount() })
  container.remove()
})

function traceButton(): HTMLButtonElement {
  const btn = Array.from(container.querySelectorAll<HTMLButtonElement>('.ws-icon-btn'))
    .find((b) => (b.getAttribute('aria-label') ?? '').includes('轨迹')
      || (b.getAttribute('aria-label') ?? '').includes('返回对话'))
  if (!btn) throw new Error('找不到轨迹按钮')
  return btn
}

describe('WorkspaceHeader 轨迹按钮', () => {
  it('对话态：文案是「轨迹 · 共 N 条」，点击回调一次', () => {
    let calls = 0
    render(false, () => { calls++ })
    expect(traceButton().title).toContain('轨迹')
    expect(traceButton().title).toContain('7')
    act(() => { traceButton().click() })
    expect(calls, '点击必须冒泡到宿主（由宿主切到 trace）').toBe(1)
  })

  it('⭐ 轨迹态：文案变成「返回对话」且仍可点（不是禁用态）', () => {
    let calls = 0
    render(true, () => { calls++ })
    const btn = traceButton()
    expect(btn.title).toBe('返回对话')
    expect(btn.disabled, '返回对话的出口不能被禁用 —— 用户实测「点了没用」就是回路断了').toBe(false)
    expect(btn.classList.contains('on'), '轨迹态按钮应高亮（当前视图指示）').toBe(true)
    act(() => { btn.click() })
    expect(calls, '轨迹态点击同样必须回调（宿主据此切回对话）').toBe(1)
  })
})
