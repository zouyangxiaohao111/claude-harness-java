// @vitest-environment jsdom
import { act } from 'react'
import type { ReactNode } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { PeerPendingHint } from '../PeerPendingHint'

/**
 * 输入框上方「待处理投递」常驻条。
 *
 * <h2>WHY（规则九）</h2>
 * <p>未读 ≠ 待处理：点开面板会把未读清零，但消息仍未处理。所以本条的判断/文案只认
 * pendingCount；「全部拒绝」是**不可恢复**的收口动作，必须二次确认并明确后果。
 * 「去处理」只开面板（⛔ 不切会话、不拉历史）。
 */

let host: HTMLDivElement
let root: Root | null = null

function render(node: ReactNode): void {
  act(() => {
    root = createRoot(host)
    root.render(node)
  })
}

beforeEach(() => {
  (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  host = document.createElement('div')
  document.body.appendChild(host)
})

afterEach(() => {
  act(() => { root?.unmount() })
  root = null
  host.remove()
})

describe('PeerPendingHint', () => {
  it('显示待处理条数，「去处理」只开面板', () => {
    const onOpen = vi.fn()
    const onRejectAll = vi.fn()
    render(<PeerPendingHint count={3} onOpen={onOpen} onRejectAll={onRejectAll} />)

    expect(host.querySelector('.peer-pending-hint')).not.toBeNull()
    expect(host.querySelector('.peer-pending-text')!.textContent).toBe('有 3 条待确认消息，处理后才能发送')

    act(() => { host.querySelector<HTMLButtonElement>('.peer-pending-go')!.click() })
    expect(onOpen).toHaveBeenCalledTimes(1)
    expect(onRejectAll).not.toHaveBeenCalled()
  })

  it('全部拒绝必须二次确认，文案写明不可恢复且发送方会收到结果', () => {
    const onRejectAll = vi.fn()
    render(<PeerPendingHint count={2} onOpen={() => {}} onRejectAll={onRejectAll} />)

    act(() => { host.querySelector<HTMLButtonElement>('.peer-pending-reject')!.click() })
    expect(onRejectAll).not.toHaveBeenCalled()
    const confirm = host.querySelector('.peer-pending-confirm')!
    expect(confirm.textContent).toContain('拒绝后不可恢复')
    expect(confirm.textContent).toContain('发送方会收到结果')

    act(() => { host.querySelector<HTMLButtonElement>('.peer-pending-cancel')!.click() })
    expect(host.querySelector('.peer-pending-confirm')).toBeNull()
    expect(onRejectAll).not.toHaveBeenCalled()

    act(() => { host.querySelector<HTMLButtonElement>('.peer-pending-reject')!.click() })
    act(() => { host.querySelector<HTMLButtonElement>('.peer-pending-reject-confirm')!.click() })
    expect(onRejectAll).toHaveBeenCalledTimes(1)
  })

  it('批量动作进行中禁用按钮（不重复提交）', () => {
    render(<PeerPendingHint count={1} onOpen={() => {}} onRejectAll={() => {}} acting />)
    expect(host.querySelector<HTMLButtonElement>('.peer-pending-reject')!.disabled).toBe(true)
  })
})
