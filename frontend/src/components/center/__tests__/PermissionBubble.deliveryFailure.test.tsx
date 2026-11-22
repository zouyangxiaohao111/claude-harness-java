// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { PermissionBubble } from '@/components/center/PermissionBubble'
import type { PermissionRequestItem } from '@/stores/chatStore'

/**
 * [D 未送达上抛] 权限卡上的「通知未送达」提示。
 *
 * <p><b>WHY</b>：微信 iLink 主动发消息必须先拿到对方来信里的 {@code context_token}；
 * 没先给机器人发过消息的人，通知会被策略层 SKIPPED。此前只有后端一行 warn，
 * 用户在桌面上完全看不出来「手机上收不到这条确认」。
 *
 * <p>⛔ 关键边界：<b>未送达 ≠ 拒绝</b>。提示只是展示，允许/拒绝按钮必须照常存在。
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

function req(extra: Partial<PermissionRequestItem> = {}): PermissionRequestItem {
  return {
    kind: 'message',
    sessionId: 'sess-delivery',
    requestId: 'req-delivery-1',
    toolUseId: 'req-delivery-1',
    toolName: 'Bash',
    description: '运行命令',
    toolInput: { command: 'ls' },
    ...extra,
  }
}

function render(r: PermissionRequestItem, onDecision: (...args: unknown[]) => void = vi.fn()) {
  root = createRoot(container)
  act(() => {
    root!.render(<PermissionBubble request={r} onDecision={onDecision as never} />)
  })
  return onDecision
}

describe('[D] 权限卡「通知未送达」提示', () => {
  it('微信未激活 ⇒ 显示未送达/未激活（带对方名字），且允许/拒绝仍在（未送达≠拒绝）', () => {
    const onDecision = render(req({
      deliveryFailures: [{
        channel: 'wechat',
        peerName: '张三',
        reasonCode: 'IM_REPLY_TOKEN_MISSING',
        reason: '需要对方先给机器人发一条消息',
      }],
    }))

    const el = container.querySelector('[data-testid="pb-delivery-failure"]')
    expect(el, '未激活渠道必须给出可见提示').toBeTruthy()
    expect(el!.textContent).toContain('未送达')
    expect(el!.textContent).toContain('未激活')
    expect(el!.textContent).toContain('张三')

    // 决策语义不变：按钮照常存在，且没有任何「自动拒绝」发生
    const labels = Array.from(container.querySelectorAll('button')).map((b) => b.textContent)
    expect(labels).toContain('允许')
    expect(labels).toContain('拒绝')
    expect(onDecision).not.toHaveBeenCalled()
  })

  it('全部送达（无 deliveryFailures）⇒ 不渲染该提示', () => {
    render(req())
    expect(container.querySelector('[data-testid="pb-delivery-failure"]')).toBeNull()
  })

  it('多渠道：只渲染失败的那些，成功的渠道不出现在提示里', () => {
    render(req({
      deliveryFailures: [
        { channel: 'wechat', peerName: '张三', reasonCode: 'IM_REPLY_TOKEN_MISSING' },
        { channel: 'feishu', peerName: '产品群', reasonCode: 'IM_SEND_INTERNAL', reason: '投递异常' },
      ],
    }))
    const text = container.querySelector('[data-testid="pb-delivery-failure"]')!.textContent ?? ''
    expect(text).toContain('微信「张三」')
    expect(text).toContain('飞书「产品群」')
  })
})
