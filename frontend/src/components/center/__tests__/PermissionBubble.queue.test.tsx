// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { PermissionBubble } from '@/components/center/PermissionBubble'
import { createChatStore, type PermissionRequestItem } from '@/stores/chatStore'

/**
 * [B3] 权限卡排队展示：一次只显示一张 + 「1 of N」。
 *
 * <p>WHY（CC 2.1.330 实证）：多条 ask 会**逐条推送**（后端不合并），桌面 UI 必须
 * 排队、一次只显示一张；答掉一张揭示下一张，且其余请求仍然 pending。
 * 序号按**本批次**计算（答掉的不缩号）⇒ 第 2 张仍是 "2 of 3"。
 */

let container: HTMLDivElement
let root: Root | null = null

beforeEach(() => {
  (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  container = document.createElement('div')
  document.body.appendChild(container)
})

afterEach(() => {
  if (root) act(() => root!.unmount())
  root = null
  container.remove()
})

function request(id: string): PermissionRequestItem {
  return { kind: 'message', sessionId: 'sess-q', requestId: id, toolName: 'Read' }
}

function render(ui: React.ReactNode) {
  if (!root) root = createRoot(container)
  act(() => root!.render(ui))
}

describe('[B3] 权限卡排队序号', () => {
  it('3 条待答 ⇒ 单张卡片带 "1 of 3"', () => {
    render(<PermissionBubble request={request('r1')} onDecision={() => {}} queueIndex={1} queueSize={3} />)
    const badge = container.querySelector('[data-testid="pb-queue-index"]')
    expect(badge?.textContent).toBe('1 of 3')
    // 只渲染一张卡（排队展示）
    expect(container.querySelectorAll('.permission-bubble')).toHaveLength(1)
  })

  it('答掉一张 ⇒ 下一张显示 "1 of 2"（按剩余队列计数 · 对齐 CC）', () => {
    render(<PermissionBubble request={request('r2')} onDecision={() => {}} queueIndex={1} queueSize={2} />)
    expect(container.querySelector('[data-testid="pb-queue-index"]')?.textContent).toBe('1 of 2')
  })

  it('只有 1 条时不显示序号（对齐 CC：N<2 不显示）', () => {
    render(<PermissionBubble request={request('r1')} onDecision={() => {}} queueIndex={1} queueSize={1} />)
    expect(container.querySelector('[data-testid="pb-queue-index"]')).toBeNull()
  })
})

describe('[B3] 队列语义（不合并 / 答一张不影响其余）', () => {
  it('3 条不同调用 ⇒ 队列 3 条（不被合并）', () => {
    const s = createChatStore()
    s.getState().enqueuePermission(request('r1'))
    s.getState().enqueuePermission(request('r2'))
    s.getState().enqueuePermission(request('r3'))

    expect(s.getState().permissionQueue).toHaveLength(3)
  })

  it('答掉一条 ⇒ 其余 2 条仍 pending（下一张按剩余队列显示 1 of 2）', () => {
    const s = createChatStore()
    s.getState().enqueuePermission(request('r1'))
    s.getState().enqueuePermission(request('r2'))
    s.getState().enqueuePermission(request('r3'))

    s.getState().resolvePermission('sess-q', null, 'r1')

    expect(s.getState().permissionQueue.map((r) => r.requestId)).toEqual(['r2', 'r3'])
    expect(s.getState().permissionQueue).toHaveLength(2)
  })

  it('队列清空后新请求 ⇒ 只剩它自己（1 条时不显示序号）', () => {
    const s = createChatStore()
    s.getState().enqueuePermission(request('r1'))
    s.getState().resolvePermission('sess-q', null, 'r1')
    s.getState().enqueuePermission(request('r9'))

    expect(s.getState().permissionQueue.map((r) => r.requestId)).toEqual(['r9'])
  })
})
