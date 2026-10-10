// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Composer } from '../Composer'
import { useChatStore } from '@/stores/chatStore'
import type { ChatMessageDto } from '@/api/types'

/**
 * D1 · 底部数字（缓存% / 当前上下文）只统计「用户自己的请求」—— 后台任务 run 的 message.usage 不再污染。
 *
 * <h2>WHY（规则九 · 意图而非行为）</h2>
 * <p>同会话后台任务（CronIdleExecutor 的 cron 调度 / 任务通知 run）复用会话 stream topic 推
 * {@code message.usage}（实测 63% 事件出自 {@code cron-idle-*} 线程）。Composer 底部 footer 取
 * 「最后一条带 usage 的条目」⇒ 用户刚发一轮，footer 却显示后台任务那轮的缓存%/上下文
 * （数字忽大忽小、还不受用户操作影响）。修法 = 事件带「来源」标记，footer 取数只认 user 来源；
 * 其余展示（弹窗上下文条 / 助手消息自己的 t/s 与 usage）不动。
 *
 * <h2>RED（改回旧实现哪条红）</h2>
 * <ul>
 *   <li>footer 扫描源去掉 {@code isUserUsageSource} 过滤 → 「末条 background 快照不得顶掉用户那条」红；</li>
 *   <li>把缺省来源当 background（而不是 user）→ 「无来源标记的旧消息照常计入」红（静默隐藏既有数据）。</li>
 * </ul>
 *
 * <h2>手法</h2>
 * 沿用本仓 Composer 既有 jsdom 真实渲染模式（{@code Composer.stopVisibleWhenServerRunning.test.tsx}）：
 * createRoot + act，不引入新依赖；断言真实 DOM 文本（footer 由 useMemo 计算，必须走真渲染才覆盖接线）。
 */

vi.mock('@tauri-apps/api/core', () => ({ isTauri: () => false, invoke: vi.fn(async () => undefined) }))
vi.mock('@tauri-apps/api/webview', () => ({ getCurrentWebview: () => ({ onDragDropEvent: () => Promise.resolve(() => {}) }) }))
vi.mock('@tauri-apps/plugin-fs', () => ({ stat: vi.fn(), readFile: vi.fn(), writeTextFile: vi.fn() }))

const SID = 'sess-d1-footer'

/** 用户来源的 assistant 消息（无 usageSource = 旧消息/旧帧，展示侧按 user 计）。 */
function userMsg(): ChatMessageDto {
  return {
    id: 'a-user', sessionId: SID, role: 'assistant', author: 'nexus', content: '用户那轮',
    reasoning: null, toolCalls: null, finishReason: 'stop', inputTokens: null, outputTokens: null,
    reasoningDurationMs: null, time: null, toolCallId: null, assistantMessageId: null,
    userMessageId: 'u-1', subtype: null, isMeta: false, isApiErrorMessage: false, apiError: null,
    error: null, errorDetails: null, matchedRule: null,
    usage: { input_tokens: 1000, output_tokens: 50, cache_read_input_tokens: 500 },
    contextTokensUsed: 1000, contextWindow: 200000, percentLeft: 99,
  } as ChatMessageDto
}

/** 后台来源（cron 调度 / 任务通知 run）的 assistant 消息 —— 数字与用户那条明显不同，便于断言谁被采用。 */
function backgroundMsg(): ChatMessageDto {
  return {
    ...userMsg(),
    id: 'a-bg', content: '后台通知那轮', userMessageId: 'u-bg', usageSource: 'background',
    usage: { input_tokens: 9000, output_tokens: 10, cache_read_input_tokens: 900 },
    contextTokensUsed: 9000, contextWindow: 200000, percentLeft: 95,
  } as ChatMessageDto
}

function mountComposer(): { container: HTMLDivElement; root: Root; footer: () => string } {
  ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  const container = document.createElement('div')
  document.body.appendChild(container)
  const root = createRoot(container)
  act(() => {
    root.render(
      <Composer
        composerText=""
        setComposerText={() => {}}
        sendMessage={() => {}}
        showToast={() => {}}
        streaming={false}
        onStop={() => {}}
        queuedCommands={[]}
        popEditable={async () => null}
        boundProjectName={null}
        onSelectProject={() => {}}
        currentModel="ds-openai/deepseek-v4-flash"
        permissionMode="default"
        empty={false}
        sessionId={SID}
        localRead={false}
        onOpenUsageCost={() => {}}
      />,
    )
  })
  return {
    container,
    root,
    footer: () => container.querySelector('.hint-usage')?.textContent ?? '',
  }
}

describe('D1 · Composer 底部数字来源过滤（缓存% / 当前上下文）', () => {
  let mounted: { container: HTMLDivElement; root: Root } | undefined

  beforeEach(() => {
    useChatStore.setState({ messages: {}, streams: {}, sessions: [] })
  })

  afterEach(() => {
    if (mounted) {
      const cur = mounted
      act(() => cur.root.unmount())
      cur.container.remove()
      mounted = undefined
    }
    useChatStore.setState({ messages: {}, streams: {}, sessions: [] })
  })

  it('⭐ 末条是后台来源快照 → footer 仍显示用户自己那条的数（缓存 50% / 1k / 200k），不被后台任务顶掉', () => {
    useChatStore.setState({ messages: { [SID]: [userMsg(), backgroundMsg()] } })
    const h = mountComposer()
    mounted = h

    const text = h.footer()
    expect(text).toContain('当前上下文 1k / 200k')
    expect(text).toContain('缓存 50%')
    expect(text).not.toContain('9k / 200k')
    expect(text).not.toContain('缓存 10%')
  })

  it('无来源标记的旧消息（旧帧 / 旧数据）照常计入 —— 缺省按 user 计，不得静默隐藏', () => {
    useChatStore.setState({ messages: { [SID]: [userMsg()] } })
    const h = mountComposer()
    mounted = h

    expect(h.footer()).toContain('当前上下文 1k / 200k')
    expect(h.footer()).toContain('缓存 50%')
  })

  it('只有后台来源快照（用户本轮尚未有带 usage 的 assistant）→ 不显示用户数（不得拿后台任务的数顶上）', () => {
    useChatStore.setState({ messages: { [SID]: [backgroundMsg()] } })
    const h = mountComposer()
    mounted = h

    expect(h.footer()).not.toContain('当前上下文')
  })

  it('⭐ F5/重拉态：后台轮的 assistant 行【无 usageSource】（DB 行）但 userMessageId 指向 is_meta=true 的 user 行 → 仍不得顶掉用户轮的数', () => {
    // 重拉数据形态（GET /messages 尾页）：实时事件的 source 不存在，判据 = user_message_id → user 行 is_meta
    const reloadedBgUser = {
      ...userMsg(), id: 'u-bg', role: 'user' as const, content: '<task-notification>…', isMeta: true,
      usage: null, contextTokensUsed: null, contextWindow: null, percentLeft: null,
    }
    const reloadedBgAssistant = {
      ...backgroundMsg(), id: 'a-bg', usageSource: undefined, userMessageId: 'u-bg',
    }
    useChatStore.setState({ messages: { [SID]: [userMsg(), reloadedBgUser, reloadedBgAssistant] } })
    const h = mountComposer()
    mounted = h

    const text = h.footer()
    expect(text).toContain('当前上下文 1k / 200k')
    expect(text).not.toContain('9k / 200k')
  })

  it('重拉态对照：user 行 is_meta=false（用户自己那条）→ 最新一条照常计入（不得把用户自己的数也滤掉）', () => {
    const reloadedUserAssistant = {
      ...backgroundMsg(), id: 'a-user2', usageSource: undefined, userMessageId: 'u-1',
      contextTokensUsed: 9000, usage: { input_tokens: 9000, output_tokens: 10, cache_read_input_tokens: 900 },
    }
    useChatStore.setState({ messages: { [SID]: [userMsg(), reloadedUserAssistant] } })
    const h = mountComposer()
    mounted = h

    expect(h.footer()).toContain('当前上下文 9k / 200k')
  })
})
