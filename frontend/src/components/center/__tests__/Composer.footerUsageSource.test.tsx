// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Composer } from '../Composer'
import { useChatStore } from '@/stores/chatStore'
import type { ChatMessageDto, Provider } from '@/api/types'

/**
 * 底部数字（缓存% / 当前上下文）· [口径反转 2026-10-10 用户裁定]。
 *
 * <h2>WHY（规则九 · 意图而非行为）</h2>
 * <p>原 D1 口径（0.1.29）：footer 只统计「用户自己的请求」，cron/任务通知唤醒的轮被过滤。
 * 用户实测推翻该口径：**cron/通知唤醒的主会话轮就是主会话的对话**（主会话收到消息并回复了），
 * 用户长时间只看任务跑时数字被钉死（「缓存都不动了」）。裁定：**去掉来源过滤**——所有主会话轮
 * 都驱动底部数字；真正要排除的「子代理/fork 的 usage」结构性不进本流（不推 message.usage），
 * 无需过滤。同时按 deepseek-harness 官方口径**双显示**：本回合（最近一条 usage）+ 会话累计
 * （`modelUsage` 累计快照聚合，Σ命中/Σ输入）。
 *
 * <h2>RED（改回旧实现哪条红）</h2>
 * <ul>
 *   <li>把 {@code usageScan} 改回「按 isUserUsageSource 过滤」→ ①③④ 红（末条 background 被滤掉）；</li>
 *   <li>累计改用「最近一轮」的数（不聚合 modelUsage）→ 双显示用例的「（累计）」断言红。</li>
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

/** ModelUsageEntry 全字段（会话按模型累计快照 · 「累计」双显示的取数源）。 */
function mu(inTok: number, outTok: number, cr: number, cc: number) {
  return {
    inputTokens: inTok, outputTokens: outTok, cacheReadInputTokens: cr, cacheCreationInputTokens: cc,
    webSearchRequests: 0, costUSD: 0, contextWindow: 200000, maxOutputTokens: 0,
  }
}

/** 用户轮 assistant（openai 语义数据：cr ≤ input；本回合 500/1000 = 50%）。 */
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
  } as ChatMessageDto
}

/** 后台（cron/任务通知唤醒的主会话）轮 —— 本回合 900/9000 = 10%；modelUsage=会话累计（user+bg：1400/10000 = 14%）。 */
function backgroundMsg(): ChatMessageDto {
  return {
    ...userMsg(),
    id: 'a-bg', content: '后台通知那轮', userMessageId: 'u-bg', usageSource: 'background',
    usage: { input_tokens: 9000, output_tokens: 10, cache_read_input_tokens: 900 },
    contextTokensUsed: 9000, contextWindow: 200000, percentLeft: 95,
    modelUsage: { 'ds-openai/deepseek-v4-flash': mu(10000, 60, 1400, 0) },
  } as ChatMessageDto
}

function mountComposer(model = 'ds-openai/deepseek-v4-flash', providers?: Provider[]): { container: HTMLDivElement; root: Root; footer: () => string } {
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
        currentModel={model}
        providers={providers}
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
 * [缓存%双显示 2026-10-10 用户裁定] 「本回合」+「累计（会话）」两个数：
 * 累计 = Σ命中/Σ输入（modelUsage 累计聚合 · deepseek-harness 官方口径），每轮回复都推动本数
 * （含后台唤醒轮——不依赖用户输入）。
 */
describe('[缓存%双显示] 本回合 + 会话累计（Σ命中/Σ输入）', () => {
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

  it('openai 语义：本回合 10%（背景轮）· 累计 14%（Σ1400/Σ10000）——两数不同、各自口径对', () => {
    useChatStore.setState({ messages: { [SID]: [userMsg(), backgroundMsg()] } })
    const h = mountComposer()
    mounted = h

    const text = h.footer()
    expect(text).toContain('缓存 10%（本回合）')
    expect(text).toContain('14%（累计）')       // round(1400/10000*100)
  })

  it('ant 语义（type=anthropic）：本回合 = cr/(input+cr+cc) = 97%；累计 = Σ 三桶 = 57%', () => {
    const msg = {
      ...userMsg(),
      usage: { input_tokens: 2560, output_tokens: 100, cache_read_input_tokens: 86600, cache_creation_input_tokens: 0 },
      modelUsage: { 'ds-zcw/deepseek-flash': mu(5000, 600, 8000, 1000) },
    }
    useChatStore.setState({ messages: { [SID]: [msg] } })
    const h = mountComposer('ds-zcw/deepseek-flash', [{
      id: 'p-zcw', name: 'ds-zcw', type: 'anthropic', baseUrl: '', apiKeyMasked: '', extraHeaders: null, enabled: true, models: [],
    }])
    mounted = h

    const text = h.footer()
    expect(text).toContain('缓存 97%（本回合）')   // 86600/(2560+86600)
    expect(text).toContain('57%（累计）')          // 8000/(5000+8000+1000)
  })

  it('无 modelUsage（老数据）→ 只显示本回合，不显示累计（不得凭空造数）', () => {
    const msg = { ...userMsg(), modelUsage: null }
    useChatStore.setState({ messages: { [SID]: [msg] } })
    const h = mountComposer()
    mounted = h

    const text = h.footer()
    expect(text).toContain('缓存 50%（本回合）')
    expect(text).not.toContain('累计')
  })
})

/**
 * [缓存%判据修复 2026-10-10] 「是否 Anthropic 协议」判据从「provider 名前缀猜」改为查 providers
 * 列表的权威 type 字段（后端 ProviderDto.type）——自定义命名的 ant provider（如 ds-zcw）不再判错。
 *
 * <h2>RED（改回「名判据」哪条红）</h2>
 * 用例 1（ds-zcw：名不含 'anthropic'、type=anthropic、真三小票数据）——旧名判 → false → cr/input
 * = 86600/2560 ≈ 3383%（用户实报「缓存 3386%」同形）；新判据查表 → 97% → 断言红。
 */
describe('[缓存%判据修复] Anthropic 判定查 providers.type（不再按名字猜）', () => {
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

  const realProvider = (name: string, type: Provider['type']): Provider => ({
    id: 'p-' + name, name, type, baseUrl: '', apiKeyMasked: '', extraHeaders: null, enabled: true, models: [],
  })

  it('自定义命名 ant provider（ds-zcw/deepseek-flash，type=anthropic）→ cr/(input+cr+cc) = 97%（旧名判据为 3383%）', () => {
    // 真机观测形态：input=本轮未命中增量（2.56k）、cache_read=命中的历史（86.6k）→ 总量 89.2k
    const msg = {
      ...userMsg(),
      usage: { input_tokens: 2560, output_tokens: 100, cache_read_input_tokens: 86600, cache_creation_input_tokens: 0 },
      modelUsage: null,
    }
    useChatStore.setState({ messages: { [SID]: [msg] } })
    const h = mountComposer('ds-zcw/deepseek-flash', [realProvider('ds-zcw', 'anthropic')])
    mounted = h
    expect(h.footer()).toContain('缓存 97%')       // 86600/(2560+86600) = 0.9713
    expect(h.footer()).not.toContain('3383%')      // 旧名判据会除以 input（未命中增量）→ 3383%
  })

  it('openai 型 provider（input 已含缓存语义）→ 仍用 cr/input（不得误用三项分母）', () => {
    // oc 行形态：input=471359（全量含缓存）、cache_read=470784（命中子集）→ cr/input ≈ 100%
    const msg = {
      ...userMsg(),
      usage: { input_tokens: 471359, output_tokens: 100, cache_read_input_tokens: 470784, cache_creation_input_tokens: 0 },
      modelUsage: null,
    }
    useChatStore.setState({ messages: { [SID]: [msg] } })
    const h = mountComposer('oc/deepseek-v4.1-flash', [realProvider('oc', 'openai_compatible')])
    mounted = h
    expect(h.footer()).toContain('缓存 100%')      // round(470784/471359*100) = 100
  })
})
