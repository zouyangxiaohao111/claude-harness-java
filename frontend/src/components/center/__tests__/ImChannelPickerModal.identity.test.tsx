// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// 身份探测走后端；这里只验「渲染 + 快速绑定回调」，不联网。
vi.mock('@/api/im', () => ({
  imApi: {
    instanceIdentity: vi.fn(async () => ({
      ok: true, platform: 'feishu', botName: 'NexusAI 助手', chatVisible: '3',
    })),
  },
}))

import { ImChannelPickerModal } from '@/components/center/ImChannelPickerModal'

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

function render(ui: React.ReactNode) {
  if (!root) root = createRoot(container)
  act(() => root!.render(ui))
}

describe('[渠道选择器] 机器人身份 + 快速绑定', () => {
  it('渠道行展示机器人名/群数；点快速绑定回调该渠道', async () => {
    const onQuickBind = vi.fn()
    render(
      <ImChannelPickerModal
        instances={[{ id: 'inst-1', label: '飞书', platform: 'feishu' }]}
        chats={[]}
        boundChatIds={[]}
        sessionId="sess-1"
        onBind={() => {}}
        onUnbind={() => {}}
        onQuickBind={onQuickBind}
        onOpenChannels={() => {}}
        onClose={() => {}}
      />,
    )

    // 身份探测是异步的（后端 bot/v3/info）；等它落到行上
    await vi.waitFor(() => {
      expect(document.querySelector('[data-testid="chanm-bot-inst-1"]')?.textContent)
        .toContain('机器人：NexusAI 助手')
    })

    const quick = document.querySelector('.chanm-quickbind') as HTMLButtonElement | null
    expect(quick).not.toBeNull()
    act(() => quick!.click())
    expect(onQuickBind).toHaveBeenCalledWith('inst-1')
  })

  it('只有平台 id 的私聊标「对方未命名」，不让人猜 oc_…', () => {
    render(
      <ImChannelPickerModal
        instances={[{ id: 'inst-1', label: '飞书', platform: 'feishu' }]}
        chats={[{ id: 'c-oc', name: 'oc_6ac65141', kind: 'p2p', instanceId: 'inst-1', unnamed: true }]}
        boundChatIds={[]}
        onBind={() => {}}
        onUnbind={() => {}}
        onOpenChannels={() => {}}
        onClose={() => {}}
      />,
    )
    expect(document.body.textContent).toContain('对方未命名')
  })
})
