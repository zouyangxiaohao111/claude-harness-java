// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Composer } from '../Composer'
import { useChatStore } from '@/stores/chatStore'
import type { ChatMessageDto, SessionDto } from '@/api/types'

/**
 * 底部数字（缓存% / 当前上下文）· [口径反转 2026-10-10 用户裁定]。
 *
 * <h2>WHY（规则九 · 意图而非行为）</h2>
 * <p>原 D1 口径（0.1.29）：footer 只统计「用户自己的请求」，cron/任务通知唤醒的轮被过滤。
 * 用户实测推翻该口径：**cron/通知唤醒的主会话轮就是主会话的对话**（主会话收到消息并回复了），
 * 用户长时间只看任务跑时数字被钉死（「缓存都不动了」）。裁定：**去掉来源过滤**——所有主会话轮
 * 都驱动底部数字；真正要排除的「子代理/fork 的 usage」结构性不进本流（不推 message.usage），
 * 无需过滤。[V101] 缓存% 双显示改为**消费后端权威值**：本回合 = `message.usage` 事件顶层
 * `cache_hit_rate`（该条 assistant 自身命中率）；累计 = `message.complete` 事件顶层 `cache_hit_rate`
 * （会话累计）——前端不再按 provider 协议自行分派公式，也不再用 modelUsage 聚合自算。
 *
 * <h2>RED（改回旧实现哪条红）</h2>
 * <ul>
 *   <li>把 {@code usageScan} 改回「按 isUserUsageSource 过滤」→ ①②③④ 红（末条 background 被滤掉）；</li>
 *   <li>把缓存% 改回前端自算（cr/input 或 cr/(input+cr+cc)）→ 缓存用例红（本组 fixture 不再提供可算的派生数）；</li>
 *   <li>缺值渲染成 0%（而不是整段不渲染）→「两值皆缺 → 不渲染」红。</li>
 * </ul>
 *
 * <h2>手法</h2>
 * 沿用本仓 Composer 既有 jsdom 真实渲染模式：createRoot + act，断言真实 DOM 文本
 * （footer 由 useMemo 计算，必须走真渲染才覆盖接线）。
 */

vi.mock('@tauri-apps/api/core', () => ({ isTauri: () => false, invoke: vi.fn(async () => undefined) }))
vi.mock('@tauri-apps/api/webview', () => ({ getCurrentWebview: () => ({ onDragDropEvent: () => Promise.resolve(() => {}) }) }))
vi.mock('@tauri-apps/plugin-fs', () => ({ stat: vi.fn(), readFile: vi.fn(), writeTextFile: vi.fn() }))

const SID = 'sess-d1-footer'

/** ModelUsageEntry 全字段（会话按模型累计快照 · 消息形状完整性；[V101] 底部数字已不再由它派生）。 */
function mu(inTok: number, outTok: number, cr: number, cc: number) {
  return {
    inputTokens: inTok, outputTokens: outTok, cacheReadInputTokens: cr, cacheCreationInputTokens: cc,
    webSearchRequests: 0, costUSD: 0, contextWindow: 200000, maxOutputTokens: 0,
  }
}

/** 用户轮 assistant（[V101] 本回合命中率 = 事件顶层 cache_hit_rate 透传的 0.5）。 */
function userMsg(): ChatMessageDto {
  return {
    id: 'a-user', sessionId: SID, role: 'assistant', author: 'nexus', content: '用户那轮',
    reasoning: null, toolCalls: null, finishReason: 'stop', inputTokens: null, outputTokens: null,
    reasoningDurationMs: null, time: null, toolCallId: null, assistantMessageId: null,
    userMessageId: 'u-1', subtype: null, isMeta: false, isApiErrorMessage: false, apiError: null,
    error: null, errorDetails: null, matchedRule: null,
    usage: { input_tokens: 1000, output_tokens: 50, cache_read_input_tokens: 500 },
    contextTokensUsed: 1000, contextWindow: 200000, percentLeft: 99,
    modelUsage: { 'ds-openai/deepseek-v4-flash': mu(1000, 50, 500, 0) },
    cacheHitRate: 0.5,
  } as ChatMessageDto
}

/** 后台（cron/任务通知唤醒的主会话）轮 —— [V101] 本回合 0.1；会话累计 0.14（complete 事件顶层透传）。 */
function backgroundMsg(): ChatMessageDto {
  return {
    ...userMsg(),
    id: 'a-bg', content: '后台通知那轮', userMessageId: 'u-bg', usageSource: 'background',
    usage: { input_tokens: 9000, output_tokens: 10, cache_read_input_tokens: 900 },
    contextTokensUsed: 9000, contextWindow: 200000, percentLeft: 95,
    modelUsage: { 'ds-openai/deepseek-v4-flash': mu(10000, 60, 1400, 0) },
    cacheHitRate: 0.1, sessionCacheHitRate: 0.14,
  } as ChatMessageDto
}

/** 活动会话（[V101] 累计命中率的 **F5 回退源**）：cacheHitRate 由后端 sessions.model_usage_json 派生；
 *  用例按需覆盖 cacheHitRate/totalTokens/totalCostYuan。 */
const SESSION_BASE: SessionDto = {
  id: SID, title: '底部数字会话', model: null, modelName: null, time: null, group: null,
  tabId: null, mainProjectId: null, effortLevel: null, ultracodeEnabled: null, bareMode: null,
  messageCount: 1,
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

describe('[口径反转 2026-10-10] 底部数字跟随「所有主会话轮」（含后台唤醒轮）', () => {
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

  it('⭐ 末条是后台唤醒轮 → 显示「它」（缓存 10%（本回合） / 上下文 9k），不再钉在旧用户轮', () => {
    useChatStore.setState({ messages: { [SID]: [userMsg(), backgroundMsg()] } })
    const h = mountComposer()
    mounted = h

    const text = h.footer()
    expect(text).toContain('当前上下文 9k / 200k')
    expect(text).toContain('缓存 10%（本回合）')
    expect(text).not.toContain('1k / 200k')
  })

  it('无来源标记的旧消息（旧帧 / 旧数据）照常计入——缺省按 user 计不隐藏', () => {
    useChatStore.setState({ messages: { [SID]: [userMsg()] } })
    const h = mountComposer()
    mounted = h

    expect(h.footer()).toContain('当前上下文 1k / 200k')
    expect(h.footer()).toContain('缓存 50%（本回合）')
  })

  it('只有后台快照（用户本轮尚无 assistant）→ 照常显示它（主会话轮即是当前活动）', () => {
    useChatStore.setState({ messages: { [SID]: [backgroundMsg()] } })
    const h = mountComposer()
    mounted = h

    const text = h.footer()
    expect(text).toContain('当前上下文 9k / 200k')
    expect(text).toContain('缓存 10%（本回合）')
  })

  it('⭐ F5/重拉态：后台轮 assistant 行【无 usageSource】但 userMessageId 指向 is_meta=true 的 user 行 → 照常计入（不得再被滤掉）', () => {
    const reloadedBgUser = {
      ...userMsg(), id: 'u-bg', role: 'user' as const, content: '<task-notification>…', isMeta: true,
      usage: null, contextTokensUsed: null, contextWindow: null, percentLeft: null, modelUsage: null,
    }
    const reloadedBgAssistant = {
      ...backgroundMsg(), id: 'a-bg', usageSource: undefined, userMessageId: 'u-bg',
    }
    useChatStore.setState({ messages: { [SID]: [userMsg(), reloadedBgUser, reloadedBgAssistant] } })
    const h = mountComposer()
    mounted = h

    const text = h.footer()
    expect(text).toContain('当前上下文 9k / 200k')
    expect(text).toContain('缓存 10%（本回合）')
  })

  it('重拉态对照：user 行 is_meta=false（用户自己那条）→ 最新一条照常计入', () => {
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

/**
 * [V101] 底部缓存% = **消费后端权威值**（0-1 比率 → 整数百分比）：本回合 = 最近一条消息/流式块上的
 * `message.usage` 事件顶层 `cache_hit_rate`（该条 assistant 自身）；累计 = `message.complete` 事件顶层
 * `cache_hit_rate`（会话累计）经 finalize 透传到该轮 assistant 消息；F5/重拉态消息行来自 DB（无该字段）
 * → 回退**当前活动会话** `SessionDto.cacheHitRate`（后端 model_usage_json 派生）。两处各自独立判定：
 * 只有一个也照常显示那一个；两个都缺 → 整段不渲染（⛔ 不臆造 0）· ⛔ 不回退 modelUsage 自算。
 */
describe('[V101] 缓存%消费后端事件值：本回合（message.usage）+ 累计（message.complete）', () => {
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

  it('两值皆有 → 双显示（0.97 → 97% / 0.57 → 57%）', () => {
    useChatStore.setState({ messages: { [SID]: [{ ...userMsg(), cacheHitRate: 0.97, sessionCacheHitRate: 0.57 }] } })
    const h = mountComposer()
    mounted = h

    const text = h.footer()
    expect(text).toContain('缓存 97%（本回合）')
    expect(text).toContain('57%（累计）')
  })

  it('只有本回合值（complete 未给 / F5 重拉的 DB 行无该字段）→ 只显示本回合，不显示累计', () => {
    useChatStore.setState({ messages: { [SID]: [userMsg()] } })
    const h = mountComposer()
    mounted = h

    const text = h.footer()
    expect(text).toContain('缓存 50%（本回合）')
    expect(text).not.toContain('累计')
  })

  it('只有累计值（本回合缺省）→ 只显示累计，⛔ 不补一个 0% 的「本回合」', () => {
    const msg = { ...userMsg(), cacheHitRate: null, sessionCacheHitRate: 0.33 }
    useChatStore.setState({ messages: { [SID]: [msg] } })
    const h = mountComposer()
    mounted = h

    const text = h.footer()
    expect(text).toContain('33%（累计）')
    expect(text).not.toContain('本回合')
  })

  it('两值皆缺（老后端 / 无缓存数据）→ 整个缓存段不渲染（不得显示 0%）', () => {
    const msg = { ...userMsg(), cacheHitRate: null, sessionCacheHitRate: null }
    useChatStore.setState({ messages: { [SID]: [msg] } })
    const h = mountComposer()
    mounted = h

    const text = h.footer()
    expect(text).not.toContain('缓存')
    expect(text).toContain('当前上下文 1k / 200k')   // 同段其他数字照常（缺值只影响缓存段）
  })

  it('F5/重拉态：消息行无 sessionCacheHitRate → 回退活动会话 SessionDto.cacheHitRate（后端 model_usage_json 派生）', () => {
    const msg = { ...userMsg(), sessionCacheHitRate: null }
    useChatStore.setState({
      messages: { [SID]: [msg] },
      sessions: [{ ...SESSION_BASE, cacheHitRate: 0.42 }],
    })
    const h = mountComposer()
    mounted = h

    const text = h.footer()
    expect(text).toContain('缓存 50%（本回合）')
    expect(text).toContain('42%（累计）')
  })

  it('实时值优先于会话回退值（complete 透传的更准）→ 显示消息上的值而非 SessionDto 的', () => {
    const msg = { ...userMsg(), sessionCacheHitRate: 0.57 }
    useChatStore.setState({
      messages: { [SID]: [msg] },
      sessions: [{ ...SESSION_BASE, cacheHitRate: 0.42 }],
    })
    const h = mountComposer()
    mounted = h

    const text = h.footer()
    expect(text).toContain('57%（累计）')
    expect(text).not.toContain('42%（累计）')
  })

  it('重拉态对照：会话 cacheHitRate 缺省（老后端）→ 不显示累计（不臆造 0）', () => {
    const msg = { ...userMsg(), sessionCacheHitRate: null }
    useChatStore.setState({ messages: { [SID]: [msg] }, sessions: [SESSION_BASE] })
    const h = mountComposer()
    mounted = h

    const text = h.footer()
    expect(text).toContain('缓存 50%（本回合）')
    expect(text).not.toContain('累计')
  })
})


