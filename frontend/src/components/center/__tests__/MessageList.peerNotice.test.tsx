// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ChatMessageDto } from '@/api/types'

/**
 * 跨会话来信的 **对话流内联卡片**（对齐 CC 真实观感）。
 *
 * <h2>WHY（意图验证）</h2>
 * <p>用户截图确认的 CC 行为：目标会话里直接出现一条**消息块** —— 头部是发送方名称，
 * 正文完整可见，紧接着目标自动执行；不是「收到…通知」的折叠通知条。
 * <p>本卡片的硬约束：①正文默认可见（长文才折叠）；②头部固定文案「收到消息 · 来自会话《标题》」+
 * 来源 id + 模式 + 时间（同名标题用来源 id 区分；方向词是固定文案，不随状态变）；
 * ③正文/标题是他人数据，纯文本渲染不执行 HTML；
 * ④⛔ 不生成用户轮、不切会话、不拉历史、不自动授权；⑤已读/pending 口径不因渲染而变。
 * <p>⛔ 发送方不加发信卡：仍走通用 SendMessage 工具卡（CC 源码 SendMessageTool/UI.tsx 证实），
 * 因此本文件的用例只覆盖接收方。
 *
 * <h2>RED teeth（变异自检）</h2>
 * <ul>
 *   <li>把正文默认改成折叠（初始 peerBodyOpen=true）⇒ 第 1 条红；</li>
 *   <li>删掉来源 id 小字或把发送方名回落成「主代理」⇒ 同名区分/头部用例红；</li>
 *   <li>用 dangerouslySetInnerHTML 渲染正文 ⇒ HTML 元素被创建 ⇒ 转义用例红；</li>
 *   <li>渲染时触发会话切换/拉历史 ⇒ fetch  spy 非零 ⇒ 对应用例红。</li>
 * </ul>
 */

vi.mock('@/api/socket', () => ({ onSocketConnect: () => () => {} }))

import { MessageList } from '@/components/center/MessageList'
import { usePeerInboxStore } from '@/stores/peerInboxStore'

const SID = 'sess-c7767a8d'
const SOURCE = 'sess-ce9234ed'
const LONG_CN = `${'跨会话来源标题与正文都是他人数据，必须按纯文本渲染不得执行任何标签。'.repeat(12)}（长中文结尾）`
const RAW_HTML = `${LONG_CN}\n<img src=x onerror="alert(1)"><script>alert(2)</script>`
/** 14 行（> 12 行折叠阈值）的长正文 */
const MANY_LINES = Array.from({ length: 14 }, (_, i) => `第 ${i + 1} 行：这是一封跨会话来信的正文。`).join('\n')
const CREATED_AT = '2026-11-19T09:42:31.000+08:00'

function baseMsg(id: string, patch: Partial<ChatMessageDto> & { role: ChatMessageDto['role'] }): ChatMessageDto {
  return {
    id,
    sessionId: SID,
    author: null,
    content: null,
    reasoning: null,
    toolCalls: null,
    finishReason: null,
    inputTokens: null,
    outputTokens: null,
    reasoningDurationMs: null,
    time: null,
    toolCallId: null,
    assistantMessageId: null,
    subtype: null,
    isMeta: false,
    isApiErrorMessage: false,
    apiError: null,
    error: null,
    ...patch,
  } as ChatMessageDto
}

/** 数据合同（方案 §3.4）：author=来源 session id、queuedOrigin=peer、peer_source + agent_summary。 */
function peerNotice(patch: Partial<ChatMessageDto> = {}): ChatMessageDto {
  return baseMsg('peer-1', {
    role: 'user',
    author: SOURCE,
    content: RAW_HTML,
    subtype: 'agent_message',
    isMeta: true,
    queuedOrigin: 'peer',
    structuredOutput: {
      peer_source: {
        sourceSessionId: SOURCE,
        sourceTitle: '审计报告',
        sourceKind: 'session',
        messageId: 'src-msg-9',
        endpointGeneration: 3,
        fromMode: 'default',
        from: 'sess-ce9234ed',
        hopChain: ['sess-ce9234ed'],
      },
      agent_summary: '请复核前端呈现方案',
    },
    createdAt: CREATED_AT,
    userMessageId: null,
    ...patch,
  })
}

async function flush(ms = 0): Promise<void> {
  await act(async () => { await new Promise((r) => setTimeout(r, ms)) })
}

let host: HTMLDivElement
let root: Root | null = null

beforeEach(() => {
  host = document.createElement('div')
  document.body.appendChild(host)
})

afterEach(() => {
  act(() => { root?.unmount() })
  root = null
  host.remove()
  vi.unstubAllGlobals()
})

async function render(
  messages: ChatMessageDto[],
  props: { onOpenPeerSession?: (sid: string) => void } = {},
): Promise<void> {
  await act(async () => {
    root = createRoot(host)
    root.render(<MessageList sessionId={SID} messages={messages} onDelete={() => {}} {...props} />)
  })
  await flush(10)
  // [2026-11-22 用户改判] 来信卡跟随轮收起态（默认收起）⇒ 本文件验证的是**卡片本体**，
  //   先点开承载它的轮再断言；折叠/顶替行为由 MessageList.peerFold.test.tsx 覆盖。
  const toggle = host.querySelector<HTMLButtonElement>('.turn-process-toggle')
  if (toggle) {
    await act(async () => { toggle.click() })
    await flush(10)
  }
}

function card(): HTMLElement {
  const el = host.querySelector<HTMLElement>('.msg.peer-message')
  expect(el, '跨会话来信必须以对话流内联卡片渲染').not.toBeNull()
  return el!
}

describe('MessageList · 跨会话来信内联卡片', () => {
  it('正文默认可见（无需任何点击），头部写「收到消息 · 来自会话《标题》」+ 来源 id/模式/时间', async () => {
    await render([
      baseMsg('u1', { role: 'user', content: '帮我审计这批接口', userMessageId: 'u1' }),
      peerNotice(),
      baseMsg('a1', { role: 'assistant', content: '收到，继续核对。', userMessageId: 'u1' }),
    ])

    const el = card()
    // ① 正文默认可见：渲染后立即可读全文（不是折叠通知条）
    const body = el.querySelector('.pm-body')!
    expect(body.textContent).toBe(RAW_HTML)
    expect(body.classList.contains('clamped')).toBe(false)

    // ② 头部方向 + 来源：固定文案 + 来源 id（小字）/ 来源模式 / 时间
    expect(el.querySelector('.pm-dir')!.textContent).toBe('收到消息')
    expect(el.querySelector('.pm-from-label')!.textContent).toBe('来自会话')
    expect(el.querySelector('.pm-title')!.textContent).toBe('《审计报告》')
    expect(el.querySelector('.pm-src')!.textContent).toBe(SOURCE)
    expect(el.querySelector('.pm-mode')!.textContent).toBe('default')
    const d = new Date(CREATED_AT)
    expect(el.querySelector('.pm-time')!.textContent)
      .toBe(`${d.getHours()}:${String(d.getMinutes()).padStart(2, '0')}`)

    // ③ 不是用户气泡（isMeta 行不得落进 .user-bubble），也不新增用户轮
    const bubbles = Array.from(host.querySelectorAll('.msg.user .user-bubble'))
    expect(bubbles).toHaveLength(1)
    expect(bubbles[0]!.textContent).toContain('帮我审计这批接口')
    expect(Array.from(host.querySelectorAll('.user-bubble')).some((b) => (b.textContent ?? '').includes('跨会话来源标题'))).toBe(false)
  })

  it('长文（>12 行）默认折叠 → 展开全文 → 收起；短正文不出现按钮', async () => {
    await render([
      baseMsg('u1', { role: 'user', content: '继续', userMessageId: 'u1' }),
      peerNotice({ content: MANY_LINES }),
    ])
    const el = card()
    const body = el.querySelector('.pm-body')!
    const more = el.querySelector<HTMLButtonElement>('.pm-more')!
    expect(more).not.toBeNull()
    expect(more.textContent).toBe('展开全文')
    expect(body.classList.contains('clamped')).toBe(true)

    await act(async () => { more.click() })
    await flush(10)
    expect(el.querySelector('.pm-body')!.classList.contains('clamped')).toBe(false)
    expect(el.querySelector('.pm-more')!.textContent).toBe('收起')
    expect(el.querySelector('.pm-body')!.textContent).toBe(MANY_LINES)

    await act(async () => { el.querySelector<HTMLButtonElement>('.pm-more')!.click() })
    await flush(10)
    expect(el.querySelector('.pm-body')!.classList.contains('clamped')).toBe(true)

    // 对照组：短正文（单行）不显示「展开全文」
    act(() => { root?.unmount() })
    host.replaceChildren()
    await render([
      baseMsg('u2', { role: 'user', content: '继续', userMessageId: 'u2' }),
      peerNotice({ id: 'peer-short', content: '短来信' }),
    ])
    expect(host.querySelector('.pm-more')).toBeNull()
    expect(host.querySelector('.pm-body')!.classList.contains('clamped')).toBe(false)
  })

  it('同名标题用来源会话 id 区分（两条不同来源，标题相同）', async () => {
    const a = peerNotice({ id: 'peer-a', author: 'sess-aaaa1111', structuredOutput: {
      peer_source: { sourceSessionId: 'sess-aaaa1111', sourceTitle: 'researcher', sourceKind: 'session' },
      agent_summary: '甲',
    } })
    const b = peerNotice({ id: 'peer-b', author: 'sess-bbbb2222', structuredOutput: {
      peer_source: { sourceSessionId: 'sess-bbbb2222', sourceTitle: 'researcher', sourceKind: 'session' },
      agent_summary: '乙',
    } })
    await render([baseMsg('u1', { role: 'user', content: '提问', userMessageId: 'u1' }), a, b])

    const cards = Array.from(host.querySelectorAll('.msg.peer-message'))
    expect(cards).toHaveLength(2)
    const heads = cards.map((c) => ({
      title: c.querySelector('.pm-title')?.textContent ?? '',
      src: c.querySelector('.pm-src')?.textContent ?? '',
    }))
    expect(heads[0]!.title).toBe('《researcher》')
    expect(heads[1]!.title).toBe('《researcher》')
    expect(heads[0]!.src).toBe('sess-aaaa1111')
    expect(heads[1]!.src).toBe('sess-bbbb2222')
    expect(heads[0]!.src).not.toBe(heads[1]!.src)
  })

  it('他人数据（标题/正文）按纯文本渲染，不执行 HTML', async () => {
    await render([
      baseMsg('u1', { role: 'user', content: '继续', userMessageId: 'u1' }),
      peerNotice({ structuredOutput: {
        peer_source: { sourceSessionId: SOURCE, sourceTitle: '<b>审计</b><img src=x onerror="alert(1)">' },
        agent_summary: '摘要',
      } }),
    ])
    const el = card()
    expect(el.querySelector('.pm-body')!.textContent).toBe(RAW_HTML)
    expect(el.querySelector('.pm-title')!.textContent).toBe('《<b>审计</b><img src=x onerror="alert(1)">》')
    expect(el.querySelector('img')).toBeNull()
    expect(el.querySelector('script')).toBeNull()
    expect(el.querySelector('b')).toBeNull()
  })

  it('底部：来源信息默认折叠，展开显示转发链/回信地址；复制正文可用', async () => {
    const writeText = vi.fn(async () => {})
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    await render([
      baseMsg('u1', { role: 'user', content: '继续', userMessageId: 'u1' }),
      peerNotice(),
    ])
    const el = card()
    // 默认折叠：看不到转发链/回信地址
    expect(el.querySelector('.pm-meta')).toBeNull()
    await act(async () => { el.querySelector<HTMLButtonElement>('.pm-src-toggle')!.click() })
    await flush(10)
    const meta = el.querySelector('.pm-meta')!.textContent ?? ''
    expect(meta).toContain(`来源会话：${SOURCE}`)
    expect(meta).toContain(`回信地址：${SOURCE}`)
    expect(meta).toContain(`转发链：${SOURCE}`)

    await act(async () => { el.querySelector<HTMLButtonElement>('.pm-copy')!.click() })
    await flush(10)
    expect(writeText).toHaveBeenCalledWith(RAW_HTML)
    expect(el.querySelector('.pm-copy')!.textContent).toBe('已复制')

    // ⛔ 不提供「回复该会话」按钮、不提供任何跳转/切会话入口
    expect(Array.from(el.querySelectorAll('button')).map((b) => b.textContent))
      .not.toContain('回复该会话')
    expect(el.querySelectorAll('a')).toHaveLength(0)
    expect(el.querySelectorAll('input,textarea,select')).toHaveLength(0)
  })

  it('不切会话、不拉历史：渲染来信卡片不发任何网络请求', async () => {
    const fetchSpy = vi.fn(() => Promise.resolve(new Response('{}')))
    vi.stubGlobal('fetch', fetchSpy)
    await render([
      baseMsg('u1', { role: 'user', content: '继续', userMessageId: 'u1' }),
      peerNotice(),
    ])
    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it('已读/待处理口径不受渲染影响（pendingCount / unreadCount 不变；hold 项不进对话流）', async () => {
    const before = usePeerInboxStore.getState()
    await render([
      baseMsg('u1', { role: 'user', content: '继续', userMessageId: 'u1' }),
      peerNotice(),
    ])
    const after = usePeerInboxStore.getState()
    expect(after.pendingCount).toBe(before.pendingCount)
    expect(after.unreadCount).toBe(before.unreadCount)
    expect(after.items).toBe(before.items)
  })

  it('缺标题时显示「未命名会话」+ 来源 id（⛔ 不拿 id 冒充名称）', async () => {
    await render([
      baseMsg('u1', { role: 'user', content: '继续', userMessageId: 'u1' }),
      peerNotice({ structuredOutput: {
        peer_source: { sourceSessionId: 'sess-notitle01' },
        agent_summary: '无标题来源',
      } }),
    ])
    const el = card()
    expect(el.querySelector('.pm-title')!.textContent).toBe('《未命名会话》')
    expect(el.querySelector('.pm-title')!.classList.contains('is-missing')).toBe(true)
    expect(el.querySelector('.pm-src')!.textContent).toBe('sess-notitle01')
  })

  it('头部固定「收到消息 · 来自会话」+ 标题；标题只有显式点击才跳（键盘可达）', async () => {
    const onJump = vi.fn()
    await render([
      baseMsg('u1', { role: 'user', content: '继续', userMessageId: 'u1' }),
      peerNotice({ structuredOutput: {
        peer_source: {
          sourceSessionId: SOURCE,
          sourceTitle: '审计报告',
          fromName: 'researcher',
          sourceKind: 'session',
          admission: 'auto',
          admissionReason: 'mode-match',
        },
        agent_summary: '请复核',
      } }),
    ], { onOpenPeerSession: onJump })

    const el = card()
    expect(el.querySelector('.pm-dir')!.textContent).toBe('收到消息')
    expect(el.querySelector('.pm-from-label')!.textContent).toBe('来自会话')
    // ⛔ fromName 不再当标题/名称展示：恒显示来源会话标题
    const title = el.querySelector<HTMLButtonElement>('.pm-title.pm-jump')!
    expect(title.textContent).toBe('《审计报告》')
    expect(title.tagName).toBe('BUTTON')
    expect(el.querySelector('.pm-src')!.textContent).toBe(SOURCE)
    // 准入徽标（服务端判定，不猜）
    expect(el.querySelector('.pm-admission')!.textContent).toBe('自动通过 · 模式一致')
    // ⛔ 仅渲染不跳转
    expect(onJump).not.toHaveBeenCalled()
    act(() => { title.focus(); title.click() })
    expect(onJump).toHaveBeenCalledTimes(1)
    expect(onJump).toHaveBeenCalledWith(SOURCE)
  })

  it('模式缺失显示「未声明」（⛔ 不回落成 default）；admission 缺失显示中性「准入未知」', async () => {
    await render([
      baseMsg('u1', { role: 'user', content: '继续', userMessageId: 'u1' }),
      peerNotice({ structuredOutput: {
        peer_source: { sourceSessionId: SOURCE, sourceTitle: '审计报告' },
        agent_summary: '无模式无准入',
      } }),
    ])
    const el = card()
    expect(el.querySelector('.pm-mode')!.textContent).toBe('未声明')
    expect(el.querySelector('.pm-mode')!.classList.contains('undeclared')).toBe(true)
    expect(el.querySelector('.pm-admission')!.textContent).toBe('准入未知')
  })

  it('旧回归：同会话子代理通知（无 peer_source）保持原「收到主代理通知」通知条', async () => {
    await render([
      baseMsg('u1', { role: 'user', content: '继续', userMessageId: 'u1' }),
      baseMsg('n-legacy', {
        role: 'user', author: 'team-lead', subtype: 'agent_message', isMeta: true,
        content: '请收束并回报', summary: '收束检索方案', userMessageId: null,
      }),
    ])
    const legacy = host.querySelector('.msg.task-notice.agent-notice')!
    expect(legacy.classList.contains('peer-message')).toBe(false)
    const text = legacy.querySelector('.tn-text')!.textContent ?? ''
    expect(text).toContain('收到主代理通知')
    expect(text).toContain('收束检索方案')
    expect(host.querySelector('.peer-message')).toBeNull()
  })
})
