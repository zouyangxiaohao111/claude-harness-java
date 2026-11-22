// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AgentTranscriptMessage } from '@/api/types'

/**
 * 子代理视图里 SendMessage 通知行的**样式**（用后端 transcript 的真实出站形状）。
 *
 * <h2>WHY（意图验证 · 规则九）</h2>
 * <p>用户诉求：「主会话给子代理发 SendMessage 后，子代理会话里要显示『收到主代理通知』，
 * 能看到摘要、点开看原文」；明确**不是**普通用户气泡。
 *
 * <p>本测试钉两件事：
 * <ol>
 *   <li>transcript 行只要带后端契约的 `role=user + subtype=agent_message + isMeta=true + author`，
 *       就必须走「通知条」渲染（`.task-notice.agent-notice`），正文不得出现在 `.user-bubble`；</li>
 *   <li>判定**不依赖** `structuredOutput`：后端 transcript 映射（{@code AgentMessage}）根本不返回该键，
 *       若判据要求它，行就会掉进普通用户气泡（本用例即此回归的牙齿）。</li>
 * </ol>
 *
 * <h2>RED teeth</h2>
 * <ul>
 *   <li>把 {@code isAgentNotificationRow} 改回「还要求 structuredOutput」⇒ 第 1 条红；</li>
 *   <li>删掉 AgentTranscriptView 的 subtype/isMeta/author 透传 ⇒ 第 1 条红；</li>
 *   <li>删掉 MessageList 的 isAgentNotice 分支 ⇒ 第 1/2 条红。</li>
 * </ul>
 */

const transcriptMock = vi.hoisted(() => vi.fn())
vi.mock('@/api/subagents', () => ({
  subagentApi: { transcript: transcriptMock, list: vi.fn(async () => []) },
}))
vi.mock('@/api/socket', () => ({
  onSocketConnect: () => () => { /* 本用例不模拟重连 */ },
}))

import { AgentTranscriptView } from '@/components/right/AgentTranscriptView'

const SID = 'sess-1'
const AGENT_HEX = 'a0123456789abcdef'
const NOTICE_TEXT = '请收束并回报关键能力/缺口/索引发布方案及改动文件，未知标未知。'
const NOTICE_SUMMARY = '收束检索方案与文件清单'

/**
 * 后端 `GET /sessions/{sid}/subagents/{id}/transcript` 的真实出站形状
 * （{@code AgentMessage} record 的 15 个分量；⚠️ **没有** structuredOutput —— 它不是 DTO 字段）。
 */
function backendRow(partial: Partial<AgentTranscriptMessage> & {
  role: AgentTranscriptMessage['role']
  content: string
}): AgentTranscriptMessage {
  return {
    role: partial.role,
    content: partial.content,
    isApiError: false,
    agentId: AGENT_HEX,
    isSidechain: true,
    uuid: partial.uuid ?? `u-${Math.random().toString(36).slice(2)}`,
    parentUuid: partial.parentUuid ?? null,
    toolCalls: null,
    toolCallId: null,
    timestamp: partial.timestamp ?? null,
    reasoning: null,
    isMeta: partial.isMeta ?? false,
    author: partial.author ?? null,
    subtype: partial.subtype ?? null,
    summary: partial.summary ?? null,
  }
}

/** 真实 E2E 样本（D:/tmp/sendmessage-e2e4-subnotice.md §3：GET transcript 的出站行，原样）。 */
const REAL_AGENT_HEX = 'a675de211cbbefaec'
const REAL_TS = '2026-11-20T10:12:37.303994800Z'
const REAL_NOTICE_TEXT = 'hello-notify-body-RUNNING-1'
const REAL_NOTICE_SUMMARY = 'notify subagent 2'

/**
 * 真实 transcript 的五条行（含 3 条 attachment 注入行）· 通知行是**最后一行**。
 *
 * <p>与 {@link noticeRow} 的差别正是本用例的牙齿：timestamp 带 9 位小数（Java 纳秒），
 * 若格式化层把 `new Date(...)` 判成 Invalid，标签里的时间会整段消失。
 */
function realTranscriptRows(): AgentTranscriptMessage[] {
  const row = (partial: Partial<AgentTranscriptMessage> & { role: AgentTranscriptMessage['role']; content: string }) =>
    backendRow({ ...partial, agentId: REAL_AGENT_HEX })
  return [
    row({
      role: 'user',
      content: 'You are a test subagent named notifier2. Reply with OK when done.',
      uuid: '56396a2c-07b3-493a-b58e-11580af3928d',
      timestamp: null,
    }),
    row({
      role: 'user',
      content: '<system-reminder>skills…</system-reminder>',
      uuid: '01e58a75-84db-49df-ab60-4cd44108c68d',
      parentUuid: '56396a2c-07b3-493a-b58e-11580af3928d',
      timestamp: '2026-11-20T18:12:37.186582400+08:00',
      isMeta: true,
      author: 'attachment',
      subtype: 'skill_listing',
    }),
    row({
      role: 'user',
      content: "Today's date is 2026-11-20.",
      uuid: 'u-date',
      timestamp: '2026-11-20T18:12:37.190583200+08:00',
      isMeta: true,
      author: 'attachment',
      subtype: 'date',
    }),
    row({
      role: 'user',
      content: '{"type":"tools_delta"}',
      uuid: 'u-tools',
      timestamp: '2026-11-20T18:12:37.204582100+08:00',
      isMeta: true,
      author: 'attachment',
      subtype: 'tools_delta',
    }),
    row({
      role: 'user',
      content: REAL_NOTICE_TEXT,
      uuid: '7baf581a-1be6-4a94-a0e0-66e4e4fd6bbb',
      parentUuid: 'e2427cb8-0646-4df4-9b22-c81d36a95be0',
      timestamp: REAL_TS,
      isMeta: true,
      author: 'team-lead',
      subtype: 'agent_message',
      summary: REAL_NOTICE_SUMMARY,
    }),
  ]
}

/** 通知行：AgentNotificationMessages.toTranscriptMap 落盘后的读回形状（脱敏真实样本）。 */
function noticeRow(partial: Partial<AgentTranscriptMessage> = {}): AgentTranscriptMessage {
  return backendRow({
    role: 'user',
    content: NOTICE_TEXT,
    uuid: 'n-1',
    timestamp: '2026-11-20T08:42:31.000Z',
    isMeta: true,
    author: 'team-lead',
    subtype: 'agent_message',
    summary: NOTICE_SUMMARY,
    ...partial,
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
  transcriptMock.mockReset()
})

afterEach(() => {
  act(() => { root?.unmount() })
  root = null
  host.remove()
})

async function renderView(rows: AgentTranscriptMessage[]): Promise<void> {
  transcriptMock.mockResolvedValue(rows)
  await act(async () => {
    root = createRoot(host)
    root.render(
      <AgentTranscriptView
        sessionId={SID}
        agentId="task-1"
        name="hold-expiry 子代理"
        active
        onBack={() => {}}
        showToast={() => {}}
      />,
    )
  })
  await flush(10)
}

describe('AgentTranscriptView · SendMessage 通知行样式', () => {
  it('真实 transcript 形状（无 structuredOutput）⇒ 渲染成通知条，不是用户气泡', async () => {
    await renderView([
      backendRow({ role: 'user', content: '请审计这批接口', uuid: 'u-user' }),
      noticeRow(),
      backendRow({ role: 'assistant', content: '收到，继续核对。', uuid: 'u-asst' }),
    ])

    // ① 通知条必须存在
    const el = host.querySelector('.msg.task-notice.agent-notice')
    expect(el, '通知行必须渲染成通知条').not.toBeNull()

    // ② 正文不得出现在用户气泡里（用户气泡只能有真实提问那一条）
    const bubbles = Array.from(host.querySelectorAll('.msg.user .user-bubble'))
    expect(bubbles.some((b) => (b.textContent ?? '').includes('请收束并回报关键能力'))).toBe(false)
    expect(bubbles).toHaveLength(1)

    // ③ 发送方 + 摘要（时间有则一并显示）
    const text = el!.querySelector('.tn-text')!.textContent ?? ''
    expect(text).toContain('收到主代理通知')
    expect(text).toContain(NOTICE_SUMMARY)
    // 时间：transcript timestamp → createdAt → 通知条标签里的 HH:MM（与主对话同一格式化）
    const t = new Date('2026-11-20T08:42:31.000Z')
    expect(text).toContain(`${t.getHours()}:${String(t.getMinutes()).padStart(2, '0')}`)

    // ④ 展开看全文
    await act(async () => { (el!.querySelector('.tn-head') as HTMLElement).click() })
    await flush(10)
    expect(host.querySelector('.msg.task-notice.agent-notice .tn-body')!.textContent)
      .toBe(NOTICE_TEXT)

    // ⑤ 通知条是独立时间线条，不塞进「已完成」折叠区
    expect(el!.closest('.turn-process-body')).toBeNull()
  })

  it('缺 structuredOutput 但带 summary ⇒ 仍命中通知条（判定不依赖 structuredOutput）', async () => {
    const row = noticeRow({ uuid: 'n-2' })
    // 显式断言形状：没有 structuredOutput 键（后端 AgentMessage 的序列化结果）
    expect((row as unknown as Record<string, unknown>).structuredOutput).toBeUndefined()
    await renderView([
      backendRow({ role: 'user', content: '开始', uuid: 'u-user' }),
      row,
    ])
    expect(host.querySelector('.msg.task-notice.agent-notice')).not.toBeNull()
    expect(host.querySelector('.msg.task-notice.agent-notice .tn-text')!.textContent)
      .toContain('收到主代理通知')
  })

  /**
   * 真实 E2E 形状（隔离实例 GET transcript 的原样 5 行）：
   * 通知行在**尾行**、timestamp 是 9 位小数的 Java 纳秒 ISO 串。
   * 若时间解析失败，标签会丢掉 `HH:MM` 而本用例红；若尾行被折叠/丢弃，通知条直接不存在。
   */
  it('真实 E2E 形状（纳秒时间戳 + 通知行在尾行）⇒ 通知条含摘要与时间，正文不进用户气泡', async () => {
    await renderView(realTranscriptRows())

    const el = host.querySelector('.msg.task-notice.agent-notice')
    expect(el, '尾行的通知行必须渲染成通知条').not.toBeNull()

    const text = el!.querySelector('.tn-text')!.textContent ?? ''
    expect(text).toContain('收到主代理通知')
    expect(text).toContain(REAL_NOTICE_SUMMARY)
    // 纳秒 ISO 串必须能被解析（否则 formatMsgTimeAbsolute 返回 '' ⇒ 时间消失）
    const t = new Date(REAL_TS)
    expect(Number.isNaN(t.getTime())).toBe(false)
    expect(text).toContain(`${t.getHours()}:${String(t.getMinutes()).padStart(2, '0')}`)

    // 不生成用户轮 / 不出现「你」气泡：唯一用户气泡是子代理启动提示
    const bubbles = Array.from(host.querySelectorAll('.msg.user .user-bubble'))
    expect(bubbles).toHaveLength(1)
    expect(bubbles.some((b) => (b.textContent ?? '').includes(REAL_NOTICE_TEXT))).toBe(false)

    // 独立时间线条，不塞进「已完成」折叠区
    expect(el!.closest('.turn-process-body')).toBeNull()

    // 可展开看到正文
    await act(async () => { (el!.querySelector('.tn-head') as HTMLElement).click() })
    await flush(10)
    expect(host.querySelector('.msg.task-notice.agent-notice .tn-body')!.textContent)
      .toBe(REAL_NOTICE_TEXT)
  })

  it('缺失字段降级：无 summary 回落正文、无 author 显示「主代理」，均不崩', async () => {
    await renderView([
      backendRow({ role: 'user', content: '开始', uuid: 'u-user' }),
      noticeRow({ uuid: 'n-no-summary', summary: null, content: '没有摘要时的正文回落' }),
      noticeRow({ uuid: 'n-no-author', author: null, summary: '无发送方摘要', content: '正文二' }),
    ])
    const bars = Array.from(host.querySelectorAll('.msg.task-notice.agent-notice'))
    expect(bars).toHaveLength(2)

    const noSummary = bars[0]!.querySelector('.tn-text')!.textContent ?? ''
    expect(noSummary).toContain('收到主代理通知')
    expect(noSummary).toContain('没有摘要时的正文回落')

    const noAuthor = bars[1]!.querySelector('.tn-text')!.textContent ?? ''
    expect(noAuthor).toContain('收到主代理通知')
    expect(noAuthor).toContain('无发送方摘要')
  })
})
