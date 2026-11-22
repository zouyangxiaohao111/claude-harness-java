// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Composer } from '../Composer'
import { PeerPendingHint } from '../PeerPendingHint'
import { useChatStore, type PermissionRequestItem } from '@/stores/chatStore'
import { usePeerInboxStore, peerInboxPendingCount } from '@/stores/peerInboxStore'
import { peerInboxApi } from '@/api/peerInbox'
import type { AttachmentRequest, PeerHeldItemDto } from '@/api/types'

/**
 * [composer-block] 「有待确认消息 / 待审批时封锁输入框」守门测试。
 *
 * <h2>WHY（规则九）</h2>
 * 待确认的跨会话消息必须先由用户接受/拒绝才会投递；权限审批 / Ask 提问期间后端工具线程
 * 卡在等决策、整轮没有结束。此时放行发送会让用户误以为消息已正常进入当前轮次。封锁的
 * 目的不是「锁界面」，而是让**发送**这一动作与真实可执行状态一致 —— 所以同时要钉：
 * 面板仍能打开、不切会话、declined/expired 不算待处理、处理完立刻恢复。
 *
 * <p>手法沿用本仓 jsdom 真实渲染（`Composer.stopVisibleWhenServerRunning.test.tsx`）：
 * 真实现 + 真 store，只 mock 网络与 Tauri。
 */

vi.mock('@tauri-apps/api/core', () => ({ isTauri: () => false, invoke: vi.fn(async () => undefined) }))
vi.mock('@tauri-apps/api/webview', () => ({ getCurrentWebview: () => ({ onDragDropEvent: () => Promise.resolve(() => {}) }) }))
vi.mock('@tauri-apps/plugin-fs', () => ({ stat: vi.fn(), readFile: vi.fn(), writeTextFile: vi.fn() }))
vi.mock('@/api/peerInbox', () => ({
  peerInboxApi: {
    list: vi.fn(async () => []),
    approve: vi.fn(async () => ({ success: true, code: 0, data: { status: 'ACCEPTED' } })),
    reject: vi.fn(async () => ({ success: true, code: 0, data: { status: 'REJECTED' } })),
    rejectAll: vi.fn(async () => ({ success: true, code: 0, data: { status: 'REJECTED', rejected: 2 } })),
    markSeen: vi.fn(async () => ({ updated: 0 })),
  },
}))

const SID = 'sess-block'

const held = (messageId: string, status?: string): PeerHeldItemDto => ({
  messageId,
  targetSessionId: SID,
  status,
})

let root: Root | null = null
let onSend: ReturnType<typeof vi.fn<(attachments?: AttachmentRequest[]) => void>>
let onReveal: ReturnType<typeof vi.fn<() => void>>

function seedPeer(items: PeerHeldItemDto[], sessionId = SID): void {
  act(() => {
    usePeerInboxStore.setState({
      sessionId,
      items,
      pendingCount: peerInboxPendingCount(items),
      unreadCount: items.length,
      acting: null,
      actingAll: false,
      notice: null,
      rowErrors: {},
      open: false,
      error: null,
      loading: false,
    })
  })
}

function seedApproval(queue: PermissionRequestItem[]): void {
  act(() => { useChatStore.setState({ permissionQueue: queue }) })
}

const approval = (requestId: string, sessionId = SID): PermissionRequestItem => ({
  kind: 'message',
  sessionId,
  requestId,
  toolName: 'Bash',
})

function mount(): { container: HTMLDivElement; textarea: HTMLTextAreaElement; sendBtn: HTMLButtonElement | null } {
  ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  const container = document.createElement('div')
  document.body.appendChild(container)
  act(() => {
    root = createRoot(container)
    root.render(<Host />)
  })
  return {
    container,
    textarea: container.querySelector('textarea') as HTMLTextAreaElement,
    sendBtn: container.querySelector('button.send-btn:not(.danger)'),
  }
}

/** 与 App.tsx 同源接线：peerHoldHint 走同一个 store 口径，onRevealApproval 由 App 传入。 */
function Host() {
  const peerPending = usePeerInboxStore((s) => (s.sessionId === SID ? s.pendingCount : 0))
  const actingAll = usePeerInboxStore((s) => s.actingAll)
  return (
    <Composer
      composerText="待发送内容"
      setComposerText={() => {}}
      sendMessage={onSend}
      showToast={() => {}}
      streaming={false}
      onStop={() => {}}
      queuedCommands={[]}
      popEditable={async () => null}
      boundProjectName={null}
      onSelectProject={() => {}}
      currentModel="deepseek-v4-flash"
      permissionMode="default"
      empty={false}
      sessionId={SID}
      localRead={false}
      onRevealApproval={onReveal}
      peerHoldHint={peerPending > 0 ? (
        <PeerPendingHint
          count={peerPending}
          onOpen={() => usePeerInboxStore.getState().openPanel()}
          onRejectAll={() => { void usePeerInboxStore.getState().rejectAll() }}
          acting={actingAll}
        />
      ) : null}
    />
  )
}

function pressEnter(el: HTMLElement): void {
  act(() => {
    el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }))
  })
}

beforeEach(() => {
  onSend = vi.fn<(attachments?: AttachmentRequest[]) => void>()
  onReveal = vi.fn<() => void>()
  usePeerInboxStore.setState({
    sessionId: null, items: [], pendingCount: 0, unreadCount: 0,
    acting: null, actingAll: false, notice: null, rowErrors: {}, open: false,
  })
  useChatStore.setState({ permissionQueue: [] })
})

afterEach(() => {
  act(() => { root?.unmount() })
  root = null
  usePeerInboxStore.setState({
    sessionId: null, items: [], pendingCount: 0, unreadCount: 0,
    acting: null, actingAll: false, notice: null, rowErrors: {}, open: false,
  })
  useChatStore.setState({ permissionQueue: [] })
})

describe('[composer-block] 待确认消息 / 待审批时封锁发送', () => {
  it('① pending>0 ⇒ 输入框与发送键禁用，提示条写明条数与「处理后才能发送」', () => {
    seedPeer([held('m1'), held('m2')])
    const h = mount()

    expect(h.textarea.disabled).toBe(false) // 只锁发送：输入框仍可打字
    expect(h.sendBtn?.disabled).toBe(true)
    expect(h.container.querySelector('.composer')?.getAttribute('data-composer-blocked')).toBe('true')
    const hint = h.container.querySelector('.peer-pending-hint')!
    expect(hint.textContent).toContain('有 2 条待确认消息，处理后才能发送')
  })

  it('② 批量拒绝后立即恢复可输入（无需刷新）', async () => {
    seedPeer([held('m1'), held('m2')])
    const h = mount()
    expect(h.textarea.disabled).toBe(false) // 只锁发送：输入框仍可打字

    await act(async () => { await usePeerInboxStore.getState().rejectAll() })

    expect(usePeerInboxStore.getState().pendingCount).toBe(0)
    expect(h.textarea.disabled).toBe(false)
    expect(h.sendBtn?.disabled).toBe(false)
    expect(h.container.querySelector('.peer-pending-hint')).toBeNull()
  })

  it('③ 单条接受后立即恢复可输入', async () => {
    seedPeer([held('m1')])
    const h = mount()
    expect(h.textarea.disabled).toBe(false) // 只锁发送：输入框仍可打字

    await act(async () => { await usePeerInboxStore.getState().accept('m1') })

    expect(usePeerInboxStore.getState().pendingCount).toBe(0)
    expect(h.textarea.disabled).toBe(false)
  })

  it('④ 有未处理审批 ⇒ 禁用并提示「等待审批结果，暂不能发送」', () => {
    seedApproval([approval('r1')])
    const h = mount()

    expect(h.textarea.disabled).toBe(false) // 只锁发送：输入框仍可打字
    expect(h.sendBtn?.disabled).toBe(true)
    expect(h.container.querySelector('.composer-block-hint')!.textContent)
      .toContain('等待审批结果，暂不能发送')
  })

  it('⑤ 审批结束（决策出队）⇒ 立即恢复，提示消失', () => {
    seedApproval([approval('r1')])
    const h = mount()
    expect(h.textarea.disabled).toBe(false) // 只锁发送：输入框仍可打字

    act(() => { useChatStore.getState().dequeuePermission('r1') })

    expect(h.textarea.disabled).toBe(false)
    expect(h.container.querySelector('.composer-block-hint')).toBeNull()
  })

  it('⑥ declined / expired 不是待处理 ⇒ 不触发禁用', () => {
    seedPeer([held('m1', 'declined'), held('m2', 'expired'), held('m3', 'accepted')])
    const h = mount()

    expect(usePeerInboxStore.getState().pendingCount).toBe(0)
    expect(h.textarea.disabled).toBe(false)
    expect(h.sendBtn?.disabled).toBe(false)
    expect(h.container.querySelector('.composer-block-hint')).toBeNull()
  })

  it('⑦ 禁用时 Enter 不得发送；解除后同一按键正常发送（对照组证明按键链路有效）', () => {
    seedPeer([held('m1')])
    const h = mount()

    pressEnter(h.textarea)
    expect(onSend).not.toHaveBeenCalled()

    act(() => { usePeerInboxStore.setState({ items: [], pendingCount: 0, unreadCount: 0 }) })
    pressEnter(h.textarea)
    expect(onSend).toHaveBeenCalledTimes(1)
  })

  it('⑧ 禁用时仍能打开收件箱，且不切会话', async () => {
    seedPeer([held('m1'), held('m2')])
    // 收件箱打开会跨会话拉取一次列表；给 mock 返回同两条，避免被当成「已清空」自动收起。
    vi.mocked(peerInboxApi.list).mockResolvedValueOnce([held('m1'), held('m2')])
    const h = mount()
    expect(h.textarea.disabled).toBe(false) // 只锁发送：输入框仍可打字

    const go = h.container.querySelector<HTMLButtonElement>('.peer-pending-go')!
    await act(async () => { go.click() })

    expect(usePeerInboxStore.getState().open).toBe(true)
    expect(usePeerInboxStore.getState().sessionId).toBe(SID)
  })

  it('⑨ 别的会话的待办不封锁本会话（子代理/多会话隔离）', () => {
    seedPeer([held('m1'), held('m2')], 'sess-other')
    const h = mount()

    expect(h.textarea.disabled).toBe(false)
    expect(h.container.querySelector('.composer-block-hint')).toBeNull()
  })

  it('⑩ 「等待审批」提示的「去处理」触发定位回调（不切会话、不改输入）', () => {
    seedApproval([approval('r1')])
    const h = mount()

    const go = h.container.querySelector<HTMLButtonElement>('.composer-block-hint .peer-pending-go')!
    act(() => { go.click() })

    expect(onReveal).toHaveBeenCalledTimes(1)
    expect(usePeerInboxStore.getState().sessionId).toBeNull()
  })
})
