// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { ImChannelPickerModal } from '../ImChannelPickerModal'

/**
 * 渠道选择弹窗：渠道有实例但**还没有聊天行**时的空态。
 *
 * <h2>WHY</h2>
 * 「接到此会话」按钮只在聊天行存在时渲染；聊天行来自入站（或同步）。
 * 若空态只写「等有人说一句话」，用户会以为坏了而是干等 ——
 * 必须写明先点「启动连接」、再让机器人收到一条消息（用户 2026-11-22 实测卡点）。
 */
describe('[im-picker] 空渠道的引导', () => {
  let root: Root | null = null

  beforeEach(() => {
    (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  })

  afterEach(() => {
    act(() => root?.unmount())
    root = null
    document.body.innerHTML = ''
  })

  it('没有任何聊天时，空态要说明「先启动连接并让机器人收到一条消息」', async () => {
    const host = document.createElement('div')
    document.body.appendChild(host)
    root = createRoot(host)
    await act(async () => {
      root!.render(
        <ImChannelPickerModal
          instances={[{ id: 'imc-1', label: '飞书机器人', platform: 'feishu' }]}
          chats={[]}
          boundChatIds={[]}
          onBind={() => undefined}
          onUnbind={() => undefined}
          onOpenChannels={() => undefined}
          onClose={() => undefined}
        />,
      )
    })

    // 弹窗走 portal，内容在 document.body 里
    const text = document.body.textContent ?? ''
    expect(text).toContain('还没收到过消息')
    expect(text).toContain('启动连接')
    expect(text).toContain('私聊机器人发一句话')
  })
})
