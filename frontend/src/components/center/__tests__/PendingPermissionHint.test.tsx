// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { PendingPermissionHint } from '@/components/center/PendingPermissionHint'
import { useChatStore, type PermissionRequestItem } from '@/stores/chatStore'
import { kbPermissionOwnership } from '@/utils/kbPermissionOwnership'
import type { SessionDto } from '@/api/types'

/**
 * 「有工具在等你」浮层的行为契约。
 *
 * <p><b>WHY 这组断言必须存在</b>：
 * <ol>
 *   <li>⚠️ <b>只显示非当前会话</b> —— 当前会话的待决请求已由对话流里的 PermissionBubble
 *       承担（allow/deny 卡片就在消息流末尾），再在输入框上方提示一遍是重复噪音；</li>
 *   <li>⚠️ <b>普通权限请求（Bash 之类）也要显示</b> —— 后端 {@code future.get()} 无限等待，
 *       <b>任何</b>权限请求都会卡住那一轮（不只提问型）⇒ 后台会话卡在授权上时用户必须看得见。
 *       提问型与普通权限型共用同一个组件，只有加粗标题不同；</li>
 *   <li>⚠️ <b>每会话只显示一条</b> —— 同一会话可能同时挂着多个待决请求（或同一 tool_use 的
 *       message/bridge 双推）⇒ 堆叠会刷屏；</li>
 *   <li>⚠️ <b>选代表项的口径必须与 App.tsx 的 currentPermission 一致</b>（优先 kind==='message'）
 *       —— 否则浮层说的和用户点进去看到的会是两件事；</li>
 *   <li>⚠️ <b>KB 窗口接管的会话不提示</b> —— 主窗口本就不渲染它的卡片，提示了等于让用户点进
 *       一个「什么都没发生」的会话。</li>
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

/** 提问型请求（AskUserQuestion）：toolInput.questions 非空。 */
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

/** 普通权限请求（Bash 之类）：无 questions。 */
function plainItem(over: Partial<PermissionRequestItem> = {}): PermissionRequestItem {
  return {
    kind: 'message',
    sessionId: 's-bg',
    requestId: 'r-plain-1',
    toolName: 'Bash',
    description: '在 shell 里执行命令',
    toolInput: { command: 'ls -la' },
    ...over,
  }
}

describe('PendingPermissionHint · 只提示非当前会话的待决请求', () => {
  it('后台会话在等回答 → 浮层出现，且带会话名', () => {
    useChatStore.setState({
      sessions: [session('s-bg', '后台会话')],
      permissionQueue: [askItem()],
    })
    render(<PendingPermissionHint activeSessionId="s-cur" onOpenSession={() => {}} />)
    const items = container.querySelectorAll('.perm-pending-hint-item')
    expect(items).toHaveLength(1)
    expect(items[0]!.textContent).toContain('需要你回答')
    expect(items[0]!.textContent).toContain('后台会话')
  })

  it('⚠️ 当前会话的提问 → **不显示**（对话流里已有卡片，重复提示是噪音）', () => {
    useChatStore.setState({
      sessions: [session('s-cur', '当前会话')],
      permissionQueue: [askItem({ sessionId: 's-cur' })],
    })
    render(<PendingPermissionHint activeSessionId="s-cur" onOpenSession={() => {}} />)
    expect(container.querySelectorAll('.perm-pending-hint-item')).toHaveLength(0)
  })

  it('⚠️ 普通权限请求（Bash）→ **也要显示**（后端无限等待，同样卡住那一轮）', () => {
    useChatStore.setState({
      sessions: [session('s-bg', '后台会话')],
      permissionQueue: [plainItem()],
    })
    render(<PendingPermissionHint activeSessionId="s-cur" onOpenSession={() => {}} />)
    const items = container.querySelectorAll('.perm-pending-hint-item')
    expect(items).toHaveLength(1)
    expect(items[0]!.textContent).toContain('需要授权：Bash')
    expect(items[0]!.textContent).toContain('后台会话')
  })

  it('⚠️ 当前会话的普通权限请求 → 同样不显示（对话流里已有 allow/deny 卡片）', () => {
    useChatStore.setState({
      sessions: [session('s-cur', '当前会话')],
      permissionQueue: [plainItem({ sessionId: 's-cur' })],
    })
    render(<PendingPermissionHint activeSessionId="s-cur" onOpenSession={() => {}} />)
    expect(container.querySelectorAll('.perm-pending-hint-item')).toHaveLength(0)
  })

  it('悬停摘要：提问型给首题文本，普通权限型给「工具名：描述」', () => {
    useChatStore.setState({
      sessions: [session('s-bg', '后台会话'), session('s-bg2', '后台会话2')],
      permissionQueue: [askItem(), plainItem({ sessionId: 's-bg2', requestId: 'r-plain-2' })],
    })
    render(<PendingPermissionHint activeSessionId="s-cur" onOpenSession={() => {}} />)
    const items = container.querySelectorAll('.perm-pending-hint-item')
    expect(items[0]!.getAttribute('title')).toContain('选哪个方案？')
    expect(items[1]!.getAttribute('title')).toContain('Bash：在 shell 里执行命令')
  })

  it('没有待处理请求 → 整个浮层不渲染', () => {
    useChatStore.setState({ sessions: [session('s-bg', '后台会话')], permissionQueue: [] })
    render(<PendingPermissionHint activeSessionId="s-cur" onOpenSession={() => {}} />)
    expect(container.querySelector('.perm-pending-hint')).toBeNull()
  })

  it('同一会话多条请求 → 只显示一条（不堆叠）', () => {
    useChatStore.setState({
      sessions: [session('s-bg', '后台会话')],
      permissionQueue: [askItem(), askItem({ requestId: 'r-ask-2' })],
    })
    render(<PendingPermissionHint activeSessionId="s-cur" onOpenSession={() => {}} />)
    expect(container.querySelectorAll('.perm-pending-hint-item')).toHaveLength(1)
  })

  it('⚠️ 同一会话的 message/bridge 双推 → 只显示一条，且取 message 通道（与 currentPermission 同口径）', () => {
    useChatStore.setState({
      sessions: [session('s-bg', '后台会话')],
      permissionQueue: [
        plainItem({ kind: 'bridge', requestId: 'r-bridge', toolName: 'BridgeTool' }),
        plainItem({ kind: 'message', requestId: 'r-message', toolName: 'Bash' }),
      ],
    })
    render(<PendingPermissionHint activeSessionId="s-cur" onOpenSession={() => {}} />)
    const items = container.querySelectorAll('.perm-pending-hint-item')
    expect(items).toHaveLength(1)
    expect(items[0]!.textContent).toContain('Bash')
    expect(items[0]!.textContent).not.toContain('BridgeTool')
  })

  it('⚠️ KB 窗口接管的会话 → 不提示（主窗口不渲染它的卡片，点了会「什么都没发生」）', () => {
    useChatStore.setState({
      sessions: [session('s-bg', '知识库会话')],
      permissionQueue: [askItem()],
    })
    kbPermissionOwnership.claim('s-bg')
    try {
      render(<PendingPermissionHint activeSessionId="s-cur" onOpenSession={() => {}} />)
      expect(container.querySelectorAll('.perm-pending-hint-item')).toHaveLength(0)
    } finally {
      kbPermissionOwnership.release('s-bg')
    }
  })

  it('⚠️ 点击提示 → 回调带目标会话 id（一键直达）', () => {
    const onOpenSession = vi.fn()
    useChatStore.setState({
      sessions: [session('s-bg', '后台会话')],
      permissionQueue: [askItem()],
    })
    render(<PendingPermissionHint activeSessionId="s-cur" onOpenSession={onOpenSession} />)
    const btn = container.querySelector('.perm-pending-hint-item') as HTMLButtonElement
    act(() => { btn.click() })
    expect(onOpenSession).toHaveBeenCalledWith('s-bg')
  })

  it('⚠️ 多个会话同时等决策 → 最多显示 3 条，其余折叠成计数条（浮层是单行，不限条会撑破输入框）', () => {
    useChatStore.setState({
      sessions: [
        session('s-bg1', '会话1'),
        session('s-bg2', '会话2'),
        session('s-bg3', '会话3'),
        session('s-bg4', '会话4'),
        session('s-bg5', '会话5'),
      ],
      permissionQueue: [
        plainItem({ sessionId: 's-bg1', requestId: 'r-1' }),
        plainItem({ sessionId: 's-bg2', requestId: 'r-2' }),
        plainItem({ sessionId: 's-bg3', requestId: 'r-3' }),
        plainItem({ sessionId: 's-bg4', requestId: 'r-4' }),
        plainItem({ sessionId: 's-bg5', requestId: 'r-5' }),
      ],
    })
    render(<PendingPermissionHint activeSessionId="s-cur" onOpenSession={() => {}} />)
    expect(container.querySelectorAll('.perm-pending-hint-item')).toHaveLength(3)
    const more = container.querySelector('.perm-pending-hint-more')
    expect(more?.textContent).toBe('+2')
  })

  it('会话名取不到 → 回落「未命名会话」（不显示空白）', () => {
    useChatStore.setState({ sessions: [], permissionQueue: [askItem()] })
    render(<PendingPermissionHint activeSessionId="s-cur" onOpenSession={() => {}} />)
    expect(container.querySelector('.perm-pending-hint-item')!.textContent).toContain('未命名会话')
  })
})
