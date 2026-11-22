// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { PeerInboxPanel, type PeerInboxPanelState } from '@/components/center/PeerInboxPanel'
import type { PeerHeldItemDto } from '@/api/types'

/**
 * 收件箱：一行一封信（来源 + 目标会话 + 摘要 + 时间 + 动作），不渲染正文。
 * 点主区 = 跳目标会话并定位该条卡；接受/拒绝 = 就地处理。
 */
const A = 'sess-aaaa1111'
const B = 'sess-bbbb2222'

function item(id: string, target: string, patch: Partial<PeerHeldItemDto> = {}): PeerHeldItemDto {
  return {
    messageId: id,
    targetSessionId: target,
    fromSessionId: 'sess-src-a',
    sourceTitle: '来源会话 A',
    summary: '请复核这次方案',
    reason: 'EXPLICIT_HOLD',
    heldAt: '2026-11-19T10:00:00Z',
    body: '正文原文（不应出现在收件箱）',
    status: 'pending',
    ...patch,
  }
}

let host: HTMLDivElement
let root: Root | null = null

function render(overrides: Partial<PeerInboxPanelState> = {}): void {
  const props: PeerInboxPanelState = {
    open: true,
    items: [item('peer-a', A), item('peer-b', B, { sourceTitle: '来源会话 B' })],
    sessionTitles: { [A]: '任务 A', [B]: '任务 B' },
    loading: false,
    error: null,
    notice: null,
    onClose: () => {},
    onRetry: () => {},
    onAccept: () => {},
    onReject: () => {},
    onOpenSession: () => {},
    ...overrides,
  }
  act(() => {
    root = createRoot(host)
    root.render(<PeerInboxPanel {...props} />)
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

describe('PeerInboxPanel · 收件箱', () => {
  it('一行一封信：来源 + 来源 id + 目标会话 + 摘要 + 时间；不含正文/内联卡动作', () => {
    render()
    const rows = Array.from(host.querySelectorAll('.inbox-row'))
    expect(rows).toHaveLength(2)
    const first = rows[0]!.textContent ?? ''
    expect(first).toContain('《来源会话 A》')
    expect(first).toContain('sess-src-a')
    expect(first).toContain('任务 A')
    expect(first).toContain('请复核这次方案')
    expect(first).toMatch(/11[\/-]19/)
    expect(rows[1]!.textContent).toContain('任务 B')

    // 硬要求：不渲染正文，也不出现内联卡的投递按钮。
    expect(host.querySelector('.ph-body')).toBeNull()
    expect(host.querySelector('.ph-accept')).toBeNull()
    expect(host.textContent).not.toContain('正文原文')
    expect(first).not.toContain('sess-aaaa1111')
  })

  it('点主区 → onOpenSession(目标会话, messageId)；动作区不触发跳转', () => {
    const opened: Array<[string, string]> = []
    render({ onOpenSession: (sid, mid) => { opened.push([sid, mid]) } })
    const mains = Array.from(host.querySelectorAll<HTMLButtonElement>('.inbox-main'))
    act(() => { mains[1]!.click() })
    expect(opened).toEqual([[B, 'peer-b']])
    expect(opened[0]![0]).not.toBe('peer-b')
  })

  it('接受/拒绝按钮回调带 messageId；动作进行中全部禁用', () => {
    const accepted: string[] = []
    const rejected: string[] = []
    render({ acting: 'peer-a', onAccept: (id) => { accepted.push(id) }, onReject: (id) => { rejected.push(id) } })
    const row = host.querySelector('.inbox-row')!
    const accept = row.querySelector<HTMLButtonElement>('.inbox-accept')!
    const reject = row.querySelector<HTMLButtonElement>('.inbox-reject')!
    expect(accept.disabled).toBe(true)
    expect(reject.disabled).toBe(true)
    expect(accept.textContent).toBe('处理中…')

    // 解除 busy 后再点（重渲染）
    act(() => { root?.unmount() })
    root = null
    host.innerHTML = ''
    render({ onAccept: (id) => { accepted.push(id) }, onReject: (id) => { rejected.push(id) } })
    act(() => { host.querySelector<HTMLButtonElement>('.inbox-accept')!.click() })
    act(() => { host.querySelector<HTMLButtonElement>('.inbox-reject')!.click() })
    expect(accepted).toEqual(['peer-a'])
    expect(rejected).toEqual(['peer-a'])
  })
})

describe('PeerInboxPanel · 状态', () => {
  it('open=false 不渲染', () => {
    render({ open: false })
    expect(host.querySelector('.ws-peer-panel')).toBeNull()
  })

  it('加载中 / 失败可重试 / 空态文案', () => {
    render({ items: [], loading: true })
    expect(host.textContent).toContain('加载中')
    act(() => { root?.unmount() })
    root = null
    host.innerHTML = ''
    let retried = 0
    render({ items: [], loading: false, error: '请求失败', onRetry: () => { retried += 1 } })
    expect(host.textContent).toContain('收件箱加载失败')
    act(() => { host.querySelector<HTMLButtonElement>('.inbox-reject')!.click() })
    expect(retried).toBe(1)
    act(() => { root?.unmount() })
    root = null
    host.innerHTML = ''
    render({ items: [] })
    expect(host.textContent).toContain('收件箱已清空')
  })

  it('行内失败原因与顶部 notice 原样展示（行保留）', () => {
    render({ notice: '部分会话加载失败，可重试', rowErrors: { 'peer-a': '仍待确认：目标会话当前仍按策略待人确认' } })
    expect(host.textContent).toContain('部分会话加载失败')
    expect(host.querySelector('.inbox-row-error')?.textContent).toContain('仍待确认')
  })
})
