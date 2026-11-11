// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AskUserPendingHint } from '@/components/center/AskUserPendingHint'
import { useChatStore, type PermissionRequestItem } from '@/stores/chatStore'
import type { SessionDto } from '@/api/types'

/**
 * 右下角「有工具在等你回答」浮层的行为契约。
 *
 * <p><b>WHY 这组断言必须存在</b>：
 * <ol>
 *   <li>⚠️ <b>只显示非当前会话</b> —— 当前会话的提问已经在对话流里弹了 PermissionBubble，
 *       再在右下角提示一遍是重复噪音。这条是本组件存在的**唯一理由**（当前会话不需要它）；</li>
 *   <li>⚠️ <b>普通权限请求不算</b>（Bash 写入之类）—— 只有「提问型」（toolInput.questions 非空）
 *       才阻塞轮次、才值得提示。否则用户会被一堆权限请求刷屏；</li>
 *   <li>⚠️ <b>点击要能切到目标会话</b> —— 提示的价值就在「一键直达」，只显示不跳转等于没做完。</li>
 * </ol>
 *
 * 环境写法照 permissionSuggestions.test.tsx（jsdom + react-dom/client + act）。
 */

let container: HTMLDivElement
let root: Root

beforeEach(() => {
  (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  useChatStore.setState({ sessions: [], permissionQueue: [] })
})

afterEach(() => {
  act(() => root.unmount())
  container.remove()
})

function render(el: React.ReactElement) {
  act(() => root.render(el))
}

function session(id: string, title: string): SessionDto {
  return {
    id, model: null, modelName: null, title, time: null, group: null, tabId: null,
    mainProjectId: null, effortLevel: null, ultracodeEnabled: null, bareMode: null,
    messageCount: 0,
  } as unknown as SessionDto
}

function askItem(over: Partial<PermissionRequestItem> = {}): PermissionRequestItem {
  return {
    kind: 'message',
    sessionId: 's-bg',
    requestId: 'r-ask-1',
    toolName: 'AskUserQuestion',
    toolInput: { questions: [{ question: '选哪个方案？', header: 'H', options: [] }] },
    ...over,
  }
}

describe('AskUserPendingHint · 只提示非当前会话的提问', () => {
  it('后台会话在等回答 → 右下角出现提示，且带会话名', () => {
    useChatStore.setState({
      sessions: [session('s-bg', '后台会话')],
      permissionQueue: [askItem()],
    })
    render(<AskUserPendingHint activeSessionId="s-cur" onOpenSession={() => {}} />)
    const items = container.querySelectorAll('.ask-pending-hint-item')
    expect(items).toHaveLength(1)
    expect(items[0]!.textContent).toContain('需要你回答')
    expect(items[0]!.textContent).toContain('后台会话')
  })

  it('⚠️ 当前会话的提问 → **不显示**（对话流里已有卡片，重复提示是噪音）', () => {
    useChatStore.setState({
      sessions: [session('s-cur', '当前会话')],
      permissionQueue: [askItem({ sessionId: 's-cur' })],
    })
    render(<AskUserPendingHint activeSessionId="s-cur" onOpenSession={() => {}} />)
    expect(container.querySelectorAll('.ask-pending-hint-item')).toHaveLength(0)
  })

  it('⚠️ 普通权限请求（非提问型）→ 不显示', () => {
    useChatStore.setState({
      sessions: [session('s-bg', '后台会话')],
      permissionQueue: [askItem({ toolName: 'Bash', toolInput: { command: 'rm -rf x' } })],
    })
    render(<AskUserPendingHint activeSessionId="s-cur" onOpenSession={() => {}} />)
    expect(container.querySelectorAll('.ask-pending-hint-item')).toHaveLength(0)
  })

  it('没有待处理请求 → 整个浮层不渲染', () => {
    useChatStore.setState({ sessions: [session('s-bg', '后台会话')], permissionQueue: [] })
    render(<AskUserPendingHint activeSessionId="s-cur" onOpenSession={() => {}} />)
    expect(container.querySelector('.ask-pending-hint')).toBeNull()
  })

  it('同一会话多条提问 → 只显示一条（不堆叠）', () => {
    useChatStore.setState({
      sessions: [session('s-bg', '后台会话')],
      permissionQueue: [askItem(), askItem({ requestId: 'r-ask-2' })],
    })
    render(<AskUserPendingHint activeSessionId="s-cur" onOpenSession={() => {}} />)
    expect(container.querySelectorAll('.ask-pending-hint-item')).toHaveLength(1)
  })

  it('⚠️ 点击提示 → 回调带目标会话 id（一键直达）', () => {
    const onOpenSession = vi.fn()
    useChatStore.setState({
      sessions: [session('s-bg', '后台会话')],
      permissionQueue: [askItem()],
    })
    render(<AskUserPendingHint activeSessionId="s-cur" onOpenSession={onOpenSession} />)
    const btn = container.querySelector('.ask-pending-hint-item') as HTMLButtonElement
    act(() => { btn.click() })
    expect(onOpenSession).toHaveBeenCalledWith('s-bg')
  })

  it('会话名取不到 → 回落「未命名会话」（不显示空白）', () => {
    useChatStore.setState({ sessions: [], permissionQueue: [askItem()] })
    render(<AskUserPendingHint activeSessionId="s-cur" onOpenSession={() => {}} />)
    expect(container.querySelector('.ask-pending-hint-item')!.textContent).toContain('未命名会话')
  })
})
