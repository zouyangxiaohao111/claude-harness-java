// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Message, MessageList } from '../MessageList'
import { useChatStore } from '@/stores/chatStore'
import type { ChatMessageDto, ContentBlockDto, ToolCallDto } from '@/api/types'

/**
 * [content-blocks] assistant 消息按**内容块数组顺序**渲染（对齐 CC 的逐块 switch）。
 *
 * <h2>WHY（意图，不是只验行为）</h2>
 * <p>旧模板固定「思考 → 旁白 → 工具 → 正文」：一轮里「工具 1/2 → 文本 → 工具 3/4」的真实时序
 * 在 DOM 里变成「所有工具挤在一组、文本另放一处」⇒ 用户无法判断哪句话是在哪个工具之后说的。
 * 有 blocks 时 DOM 顺序必须**等于**数组顺序：相邻 tool_use 合成一组、连续 text 合并成一段、
 * 中间夹文本即断组；thinking 仍沿用现有折叠样式且可展开。
 *
 * <h2>RED teeth（变异自检）</h2>
 * <ul>
 *   <li>忽略 blocks 走旧顺序 ⇒ 用例 a 红（1 组 4 个工具、文本移位）；</li>
 *   <li>相邻 tool_use 不合并（一卡一段）⇒ 用例 c 红；</li>
 *   <li>连续 text 不合并 ⇒ 用例 b 红（.content 出现 2 个）；</li>
 *   <li>thinking 渲染成不可点击的静态块 ⇒ 用例 e 红；</li>
 *   <li>让 blocks 分支接管 peer 来信卡片 ⇒ 用例 f 红。</li>
 * </ul>
 */
vi.mock('@/api/socket', () => ({ onSocketConnect: () => () => {} }))

const SID = 'sess-content-blocks'
const SOURCE = 'sess-peer-source'
const noop = () => {}

function baseMsg(id: string, opts: Partial<ChatMessageDto> = {}): ChatMessageDto {
  return {
    id, sessionId: SID, role: 'assistant', author: 'nexus', content: '',
    reasoning: null, toolCalls: null, finishReason: null, inputTokens: null, outputTokens: null,
    reasoningDurationMs: null, time: null, toolCallId: null, assistantMessageId: null,
    userMessageId: 'u1', subtype: null, isMeta: false, isApiErrorMessage: false,
    apiError: null, error: null, errorDetails: null, matchedRule: null,
    ...opts,
  }
}

const assistant = (opts: Partial<ChatMessageDto> = {}): ChatMessageDto => baseMsg('a1', opts)

const bash = (id: string, cmd: string): ToolCallDto =>
  ({ id, name: 'Bash', arguments: JSON.stringify({ cmd }), result: 'ok', isError: false })
const grep = (id: string, pattern: string): ToolCallDto =>
  ({ id, name: 'Grep', arguments: JSON.stringify({ pattern }), result: 'ok', isError: false })

const toolBlock = (id: string, name: string, input: Record<string, unknown>, apiBlockIndex: number): ContentBlockDto =>
  ({ type: 'tool_use', id, name, input, apiBlockIndex })

let container: HTMLDivElement
let root: Root

function render(node: React.ReactNode) {
  root = createRoot(container)
  act(() => { root.render(node) })
}

const click = (el: Element) => act(() => {
  el.dispatchEvent(new MouseEvent('click', { bubbles: true }))
})

describe('[content-blocks] 按块顺序渲染', () => {
  beforeEach(() => {
    (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    container = document.createElement('div')
    document.body.appendChild(container)
    useChatStore.setState({
      messages: {}, streams: {}, streamOrder: {}, streamTicks: {}, snippedIds: {}, apiErrors: {},
      changedFiles: {}, hasMore: {}, liveSeq: {},
    })
  })

  afterEach(() => {
    act(() => { root?.unmount() })
    container.remove()
  })

  it('a. 一轮「工具1,2 → 文本 → 工具3,4」：两组分开且文本夹在中间（顺序=数组顺序）', () => {
    const toolCalls = [bash('t1', 'ls -la'), grep('t2', 'alpha'), bash('t3', 'pwd'), grep('t4', 'beta')]
    const blocks: ContentBlockDto[] = [
      toolBlock('t1', 'Bash', { cmd: 'ls -la' }, 0),
      toolBlock('t2', 'Grep', { pattern: 'alpha' }, 1),
      { type: 'text', text: '先看目录，再搜一遍。', apiBlockIndex: 2 },
      toolBlock('t3', 'Bash', { cmd: 'pwd' }, 3),
      toolBlock('t4', 'Grep', { pattern: 'beta' }, 4),
    ]
    render(
      <Message
        msg={assistant({ content: '先看目录，再搜一遍。', toolCalls, blocks })}
        onDelete={noop} processExpanded showHeader={false} isFinalOfTurn={false}
        turnKey="u1" onToggleProcess={noop}
      />,
    )

    const body = container.querySelector('.turn-process-body')
    expect(body, '折叠区（轮中间过程）应渲染').not.toBeNull()

    const groups = body!.querySelectorAll('.tool-group')
    expect(groups, '中间夹文本 ⇒ 必须断组：两组工具').toHaveLength(2)
    expect(groups[0]!.querySelectorAll('.tool-card'), '第一组 = 工具 1,2').toHaveLength(2)
    expect(groups[1]!.querySelectorAll('.tool-card'), '第二组 = 工具 3,4').toHaveLength(2)

    // 真实顺序：工具 1 → 文本 → 工具 3（文本不搬家、不重排）
    const txt = body!.textContent ?? ''
    const iLs = txt.indexOf('ls -la')
    const iText = txt.indexOf('先看目录，再搜一遍。')
    const iPwd = txt.indexOf('pwd')
    expect(iLs, '第一个工具应在最前').toBeGreaterThanOrEqual(0)
    expect(iLs).toBeLessThan(iText)
    expect(iText, '文本必须夹在两组工具之间').toBeLessThan(iPwd)
  })

  it('b. 连续 text 合并成一段（一次 Markdown 渲染，不拆成两块）', () => {
    const blocks: ContentBlockDto[] = [
      { type: 'text', text: '第一段：先说明背景。', apiBlockIndex: 0 },
      { type: 'text', text: '第二段：再给出结论。', apiBlockIndex: 1 },
    ]
    render(<Message msg={assistant({ content: '第一段：先说明背景。\n\n第二段：再给出结论。', blocks })} onDelete={noop} />)

    const contents = container.querySelectorAll('.msg.assistant .content')
    expect(contents, '连续 text 应合并为 1 段').toHaveLength(1)
    const txt = contents[0]!.textContent ?? ''
    expect(txt).toContain('第一段')
    expect(txt).toContain('第二段')
    expect(txt.indexOf('第一段')).toBeLessThan(txt.indexOf('第二段'))
  })

  it('c. 相邻 tool_use 合并成一组；单个 tool_use 仍为单卡', () => {
    const adjacent: ContentBlockDto[] = [
      toolBlock('t1', 'Bash', { cmd: 'ls -la' }, 0),
      toolBlock('t2', 'Bash', { cmd: 'pwd' }, 1),
    ]
    render(
      <Message
        msg={assistant({ toolCalls: [bash('t1', 'ls -la'), bash('t2', 'pwd')], blocks: adjacent })}
        onDelete={noop}
      />,
    )
    const groups = container.querySelectorAll('.tool-group')
    expect(groups, '两个相邻 tool_use 应合成 1 组').toHaveLength(1)
    expect(groups[0]!.querySelectorAll('.tool-card')).toHaveLength(2)

    // 对照组：单个 tool_use 不建组（保持单卡）
    act(() => { root.unmount() })
    container.replaceChildren()
    render(
      <Message
        msg={assistant({ toolCalls: [bash('t1', 'ls -la')], blocks: [toolBlock('t1', 'Bash', { cmd: 'ls -la' }, 0)] })}
        onDelete={noop}
      />,
    )
    expect(container.querySelector('.tool-group')).toBeNull()
    expect(container.querySelectorAll('.tool-card')).toHaveLength(1)
  })

  it('d. 无 blocks：完全走旧分支（思考→工具→正文），不造假块', () => {
    const msg = assistant({
      reasoning: '先想一想返回结构。',
      content: '最终答复正文。',
      toolCalls: [bash('t1', 'ls -la'), grep('t2', 'alpha')],
    })
    expect(msg.blocks).toBeUndefined()
    render(
      <Message
        msg={msg}
        onDelete={noop} processExpanded showHeader={false} isFinalOfTurn
        turnKey="u1" onToggleProcess={noop}
      />,
    )

    const body = container.querySelector('.turn-process-body')!
    expect(body.querySelector('.thinking-wrap'), '旧分支思考块在折叠区首段').not.toBeNull()
    expect(body.querySelectorAll('.tool-group'), '旧分支：两个工具仍成 1 组').toHaveLength(1)
    expect(body.querySelectorAll('.content'), '最终段正文不进折叠区（旧行为）').toHaveLength(0)

    // 整条消息顺序仍是旧模板：思考（展开后）→ 工具 → 正文
    click(body.querySelector('.thinking-toggle')!)
    const txt = container.querySelector('.msg.assistant')!.textContent ?? ''
    expect(txt.indexOf('先想一想返回结构。')).toBeGreaterThanOrEqual(0)
    expect(txt.indexOf('先想一想返回结构。')).toBeLessThan(txt.indexOf('ls -la'))
    expect(txt.indexOf('ls -la')).toBeLessThan(txt.indexOf('最终答复正文。'))
  })

  it('e. thinking 块沿用现有折叠样式，点击可展开', () => {
    const blocks: ContentBlockDto[] = [
      { type: 'thinking', thinking: '先确认目录结构，再决定改哪里。', apiBlockIndex: 0 },
      { type: 'text', text: '结论正文。', apiBlockIndex: 1 },
    ]
    render(<Message msg={assistant({ content: '结论正文。', blocks })} onDelete={noop} />)

    const wrap = container.querySelector('.thinking-wrap')
    expect(wrap, 'thinking 用现有 .thinking-wrap 样式').not.toBeNull()
    expect(container.querySelector('.thinking-body'), '历史默认收起').toBeNull()

    click(container.querySelector('.thinking-toggle')!)
    expect(container.querySelector('.thinking-wrap')!.classList.contains('open')).toBe(true)
    expect(container.querySelector('.thinking-body')!.textContent).toBe('先确认目录结构，再决定改哪里。')
    // 思考与正文按数组顺序：思考在前
    const txt = container.querySelector('.msg.assistant')!.textContent ?? ''
    expect(txt.indexOf('先确认目录结构')).toBeLessThan(txt.indexOf('结论正文。'))
  })

  it('g. 轮最终段：末尾 text 上提到正文区，其余块留在折叠区（DOM 顺序不变）', () => {
    const blocks: ContentBlockDto[] = [
      { type: 'thinking', thinking: '想一想。', apiBlockIndex: 0 },
      toolBlock('t1', 'Bash', { cmd: 'ls -la' }, 1),
      toolBlock('t2', 'Grep', { pattern: 'alpha' }, 2),
      { type: 'text', text: '最终答复正文。', apiBlockIndex: 3 },
    ]
    render(
      <Message
        msg={assistant({ content: '最终答复正文。', toolCalls: [bash('t1', 'ls -la'), grep('t2', 'alpha')], blocks })}
        onDelete={noop} processExpanded showHeader={false} isFinalOfTurn
        turnKey="u1" onToggleProcess={noop}
      />,
    )

    const body = container.querySelector('.turn-process-body')!
    expect(body.querySelector('.thinking-wrap'), '思考留在折叠区').not.toBeNull()
    expect(body.querySelectorAll('.tool-card'), '工具留在折叠区').toHaveLength(2)
    // 末尾 text 已上提到正文区（不在折叠区里重复）
    expect(body.textContent).not.toContain('最终答复正文。')
    const contents = container.querySelectorAll('.msg.assistant .content')
    expect(contents, '正文区只有 1 段（上提的末尾 text）').toHaveLength(1)
    expect(contents[0]!.textContent).toContain('最终答复正文。')
    // 顺序仍是：思考/工具（折叠区）在前，正文在后
    expect(container.querySelector('.turn-process-body')!.compareDocumentPosition(contents[0]!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('f. peer 来信卡片的固定头（收到消息 · 来自会话《标题》）不受 blocks 影响', async () => {
    const peer = baseMsg('peer-1', {
      role: 'user',
      author: SOURCE,
      content: '请复核这批接口。',
      subtype: 'agent_message',
      isMeta: true,
      queuedOrigin: 'peer',
      userMessageId: null,
      structuredOutput: {
        peer_source: { sourceSessionId: SOURCE, sourceTitle: '审计报告', sourceKind: 'session' },
        agent_summary: '请复核',
      },
      // 故意带上 blocks：peer 分支必须先于块渲染生效（⛔ 不能被块渲染接管）
      blocks: [
        toolBlock('t1', 'Bash', { cmd: 'ls -la' }, 0),
        { type: 'text', text: '块文本', apiBlockIndex: 1 },
      ],
    })
    await act(async () => {
      root = createRoot(container)
      root.render(<MessageList sessionId={SID} messages={[peer]} onDelete={noop} />)
    })

    const card = container.querySelector('.msg.peer-message')
    expect(card, '跨会话来信仍是内联卡片').not.toBeNull()
    expect(card!.querySelector('.pm-dir')!.textContent).toBe('收到消息')
    expect(card!.querySelector('.pm-title')!.textContent).toBe('《审计报告》')
    // 块渲染分支不得渗透进来
    expect(card!.querySelector('.tool-group')).toBeNull()
    expect(card!.querySelector('.tool-card')).toBeNull()
  })
})
