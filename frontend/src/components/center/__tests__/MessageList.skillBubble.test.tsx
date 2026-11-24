// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MessageList } from '../MessageList'
import { resetSkillCatalogCache } from '@/utils/skillBubble'
import type { ChatMessageDto } from '@/api/types'

/**
 * 用户气泡技能标识（用户 2026-11-24 定）：
 * 有 displayName → 气泡内顶部 chip 行；没有 → 正文内联 /name chip；目录未命中 → 原样。
 *
 * <p>WHY 要真实渲染：判据抽在 `utils/skillBubble`（已单测），但「chip 挂到气泡内、正文保留」
 * 是接线；纯函数全绿而渲染没接上，就是「功能不存在但测试绿」。
 */
const CATALOG = [
  { name: 'deep-research', displayName: '深度研究', icon: null, description: '调研' },
  { name: 'archify', displayName: null, icon: null, description: '图示' },
]
vi.mock('@/api/command', () => ({
  commandApi: { list: vi.fn(async () => CATALOG) },
}))

const SID = 'sess-skill-bubble'
const noop = () => {}

const base = (content: string): ChatMessageDto => ({
  id: `u-${content.slice(0, 8)}`, sessionId: SID, role: 'user', author: '你', content,
  reasoning: null, toolCalls: null, finishReason: null, inputTokens: null, outputTokens: null,
  reasoningDurationMs: null, time: null, createdAt: undefined, toolCallId: null,
  assistantMessageId: null, userMessageId: 'u1', subtype: null, isMeta: false, isApiErrorMessage: false,
  apiError: null, error: null, errorDetails: null, matchedRule: null,
})

let container: HTMLDivElement
let root: Root

async function flush(rounds = 4) {
  for (let i = 0; i < rounds; i++) {
    await act(async () => { await new Promise((r) => setTimeout(r, 0)) })
  }
}

async function renderMessage(content: string) {
  await act(async () => {
    root.render(
      <MessageList
        sessionId={SID}
        messages={[base(content)]}
        onDelete={noop}
        conversationId={null}
        onNearBottomChange={noop}
        onLoadOlder={noop}
        onOpenRightTab={noop}
      />,
    )
  })
  await flush()
}

beforeEach(() => {
  ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  resetSkillCatalogCache()
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
})

afterEach(() => {
  act(() => root.unmount())
  container.remove()
})

describe('[技能气泡] 用户消息', () => {
  it('有 displayName → 气泡内顶部 chip（中文名）+ 正文 /name 也着色（DSH 同款）', async () => {
    await renderMessage('/deep-research 帮我核对这份材料')

    const chip = container.querySelector('.msg.user .user-bubble .bubble-tags .skill-chip')
    expect(chip, '应渲染技能 chip 行').toBeTruthy()
    expect(chip!.textContent).toContain('深度研究')
    // 正文不丢：仍能看到实际发出的命令，且命令词按 DSH 样式着色（inline-skill）
    const bubble = container.querySelector('.msg.user .user-bubble')!
    expect(bubble.textContent).toContain('/deep-research')
    expect(bubble.querySelector('.inline-skill')?.textContent, 'named 时正文命令也要着色').toBe('/deep-research')
  })

  it('无 displayName → 正文内联 /name chip（DSH 式）', async () => {
    await renderMessage('/archify 画个架构图')

    const inline = container.querySelector('.msg.user .user-bubble .inline-skill')
    expect(inline?.textContent).toBe('/archify')
    expect(container.querySelector('.msg.user .user-bubble')!.textContent).toContain('画个架构图')
    expect(container.querySelector('.bubble-tags'), '无 displayName 不走 chip 行').toBeNull()
  })

  it('目录未命中（普通命令）→ 不加任何技能标识', async () => {
    await renderMessage('/init 初始化项目')

    expect(container.querySelector('.bubble-tags')).toBeNull()
    expect(container.querySelector('.inline-skill')).toBeNull()
    expect(container.querySelector('.msg.user .user-bubble')!.textContent).toContain('/init')
  })
})
