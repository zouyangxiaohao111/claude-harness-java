// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { MessageList } from '../MessageList'
import { useChatStore } from '@/stores/chatStore'
import type { ChatMessageDto, ToolCallDto } from '@/api/types'

/**
 * [轮次中间过程折叠] 折叠粒度 = 轮次（turnKey = 发起该轮的 userMessageId），不是单条 assistant 消息。
 *
 * <b>WHY（规则九 · 用户补充裁定）</b>：
 *   · 轮次进行中（流式/running）→ 中间过程必须正常展示（用户要看到正在做什么）；
 *   · 轮次结束后 → 整轮思考 / 工具调用 / 技能加载默认收起，只留最终答复 + 产物入口，顶部一行
 *     「已完成 · 时长 ⌄」；
 *   · 最后一轮在跑时，历史轮次仍保持收起。
 * 若按「单条 assistant 消息是否非流式」判断，运行中已落库的中间段会被误收起 —— 这正是本用例锁死的点。
 */

const SID = 'sess-turn-process'
const noop = () => {}

function baseMsg(id: string, role: 'user' | 'assistant', opts: Partial<ChatMessageDto> = {}): ChatMessageDto {
  return {
    id, sessionId: SID, role, author: role === 'user' ? '你' : 'nexus', content: '',
    reasoning: null, toolCalls: null, finishReason: null, inputTokens: null, outputTokens: null,
    reasoningDurationMs: null, time: null, toolCallId: null, assistantMessageId: null,
    userMessageId: null, subtype: null, isMeta: false, isApiErrorMessage: false, apiError: null,
    error: null, errorDetails: null, matchedRule: null,
    ...opts,
  }
}

const userMsg = (id: string, createdAt?: string): ChatMessageDto =>
  baseMsg(id, 'user', { content: `问题-${id}`, userMessageId: id, createdAt })

const tool = (id: string): ToolCallDto => ({ id, name: 'Bash', arguments: '{"cmd":"ls"}', result: 'ok', isError: false })

/** 工具轮 assistant：带 reasoning + 普通工具卡（中间过程）。 */
const intermediateMsg = (id: string, turnId: string, createdAt?: string): ChatMessageDto =>
  baseMsg(id, 'assistant', {
    reasoning: `思考-${id}`, toolCalls: [tool(`t-${id}`)], finishReason: 'tool_calls',
    userMessageId: turnId, createdAt,
  })

const finalMsg = (id: string, turnId: string, createdAt?: string): ChatMessageDto =>
  baseMsg(id, 'assistant', { content: `最终答复-${id}`, finishReason: 'stop', userMessageId: turnId, createdAt })

let container: HTMLDivElement
let root: Root

function renderList(messages: ChatMessageDto[], opts: { thinking?: boolean } = {}) {
  root = createRoot(container)
  act(() => {
    root.render(
      <MessageList sessionId={SID} messages={messages} onDelete={noop} conversationId={null}
        thinking={opts.thinking} onNearBottomChange={noop} onLoadOlder={noop} />,
    )
  })
}

const toggles = () => Array.from(container.querySelectorAll<HTMLButtonElement>('.turn-process-toggle'))

describe('[轮次中间过程折叠]', () => {
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
    root = undefined as unknown as Root
  })

  it('轮次已结束：默认收起整轮中间过程，顶部唯一「已完成」行，最终答复仍显示', () => {
    renderList([
      userMsg('u1', '2026-10-28T10:00:00Z'),
      intermediateMsg('a1', 'u1', '2026-10-28T10:05:00Z'),
      finalMsg('a2', 'u1', '2026-10-28T10:16:34Z'),
    ])

    // 同一轮两条 assistant 段 → 只有一个「已完成」开关（轮次粒度，不是每条消息一个）
    expect(toggles()).toHaveLength(1)
    expect(toggles()[0]!.textContent).toContain('已完成')
    // 时长 = 发起用户消息 → 本轮最后一条 assistant 的时间差
    expect(toggles()[0]!.textContent).toContain('16m34s')
    // 中间过程收起：无工具卡、无思考正文
    expect(container.querySelectorAll('.tool-card')).toHaveLength(0)
    expect(container.querySelector('.thinking-body')).toBeNull()
    // 最终答复保持显示
    expect(container.textContent).toContain('最终答复-a2')
  })

  it('点击「已完成」展开整轮中间过程，再点收起（轮次内多条消息共享态）', () => {
    renderList([
      userMsg('u1', '2026-10-28T10:00:00Z'),
      intermediateMsg('a1', 'u1', '2026-10-28T10:01:00Z'),
      intermediateMsg('a2', 'u1', '2026-10-28T10:02:00Z'),
      finalMsg('a3', 'u1', '2026-10-28T10:03:00Z'),
    ])
    expect(toggles()).toHaveLength(1)
    expect(container.querySelectorAll('.tool-card')).toHaveLength(0)

    act(() => { toggles()[0]!.click() })
    // 同轮两段中间过程同时展开
    expect(container.querySelectorAll('.tool-card')).toHaveLength(2)
    // [2026-10-28 用户裁定] 思考块自身默认收起（「完成后收起、点开才显示完整」）
    //   ⇒ 展开轮次先露出 2 个思考摘要行；正文 .thinking-body 需再点开每个思考块。
    expect(container.querySelectorAll('.thinking-toggle')).toHaveLength(2)
    expect(container.querySelectorAll('.thinking-body')).toHaveLength(0)
    expect(toggles()[0]!.getAttribute('aria-expanded')).toBe('true')

    act(() => { toggles()[0]!.click() })
    expect(container.querySelectorAll('.tool-card')).toHaveLength(0)
    expect(container.querySelectorAll('.thinking-toggle')).toHaveLength(0)
    expect(container.querySelectorAll('.thinking-body')).toHaveLength(0)
  })

  it('轮次进行中（streaming）：不显示「已完成」，中间过程在收起态露出尾部段', () => {
    act(() => {
      useChatStore.getState().ensureStreamBlock(SID, 'blk-live', 'u1')
      useChatStore.getState().appendReasoning(SID, 'blk-live', '正在思考')
      useChatStore.getState().appendChunk(SID, 'blk-live', '流式正文')
    })
    renderList([
      userMsg('u1'),
      intermediateMsg('a1', 'u1'),
    ])

    // [2026-11-01 方案 3] 本轮未结束：**不显示「已完成」**（改为「正在执行」状态行）。
    //   原断言是「toggles 长度为 0」—— 那条是方案 3 之前的行为（进行中完全没有状态行）。
    //   现按用户裁定「未完成也能收」，进行中也有一行，但文案是「正在执行」而不是「已完成」。
    expect(container.textContent).not.toContain('已完成')
    const running = container.querySelector('.turn-process-toggle.running')
    expect(running, '进行中应显示「正在执行」状态行').not.toBeNull()
    expect(running!.textContent).toContain('正在执行')
    // [2026-11-12] 该轮默认收起，但收起态会露尾部 3 段 ⇒ 唯一的工具卡仍在（用户看得到在做啥）。
    expect(container.querySelectorAll('.tool-card')).toHaveLength(1)
    // 流式行自己的思考块保持展开（StreamBlockRow 尾块行为不变）
    const thinkingBodies = Array.from(container.querySelectorAll('.thinking-body')).map((el) => el.textContent ?? '')
    expect(thinkingBodies.some((t) => t.includes('正在思考'))).toBe(true)
  })

  it('[2026-11-02 用户裁定] 最后一轮在跑时，历史轮次**折叠**（执行中只看当前轮）', () => {
    renderList([
      userMsg('u0'),
      intermediateMsg('a0', 'u0'),
      finalMsg('a0-final', 'u0'),
      userMsg('u1'),
    ], { thinking: true })

    // <b>WHY 断言（规则九 · 最终裁定 · 第三次）</b>：用户原话
    //   「用户发送消息后 应该只显示该用户消息和当前轮次 其他的轮次隐藏了 直到完成后重新可以看到」
    //   + 「当前气泡就是顶」（连上一轮都不保留）。
    //   ⇒ 执行中：历史轮**从 DOM 移除**，当前用户气泡就是滚动区顶；折叠行也不画。
    //   ⚠️ 与下一用例配对：结束后必须**自动恢复**，否则就是用户上次报的「往上拉拉不到」。
    expect(container.querySelector('.ml-fold-row'), '执行中不应出现折叠行').toBeNull()
    const text = container.textContent ?? ''
    expect(text, '当前轮 user 气泡必须在').toContain('问题-u1')
    expect(text, '执行中历史轮必须隐藏（当前气泡就是顶）').not.toContain('问题-u0')
  })

  it('[2026-11-02 用户裁定] 轮次结束后，历史轮恢复可见（折叠行可展开）', () => {
    renderList([
      userMsg('u0'),
      intermediateMsg('a0', 'u0'),
      finalMsg('a0-final', 'u0'),
      userMsg('u1'),
      finalMsg('a1', 'u1'),
    ])

    // 已结束（无 thinking / 无流式块）⇒ 窗口恢复 3 轮 ⇒ 历史轮重新渲染，折叠行消失。
    expect(toggles()).toHaveLength(1)
    expect(container.querySelector('.ml-fold-row')).toBeNull()
    // ⭐ 关键：**历史内容真的回来了**（上一条用例断言执行中历史被隐藏 ⇒ 这一条必须对称地证明它恢复）
    //   否则就是用户报的「往上拉拉不到」—— 折叠行没了、历史也没了 = 永久丢失。
    const text = container.textContent ?? ''
    expect(text, '结束后历史轮必须重新可见').toContain('问题-u0')
    expect(text, '结束后当前轮也在').toContain('问题-u1')
  })

  /**
   * ⭐⭐ [2026-11-02 用户实测「发送后 正在执行 不展示 要刷新才有」]
   *
   * <p><b>根因</b>：状态行原先只挂在**已落库的 assistant 消息**上（`processHeaderIdByTurn` 只遍历
   * `kind === 'msg'`）。用户刚发消息时这一轮**只有流式块**、还没有任何落库 assistant
   * ⇒ 整轮没有状态行落点 ⇒ 看不到「正在执行」，F5 从 DB 重拉出 assistant 后才出现。
   *
   * <p>现改为：本轮还没有落库 assistant 时，状态行交给**首条流式块**渲染。
   */
  it('⭐⭐ 只有流式块、没有任何落库 assistant 时，仍显示「正在执行」', () => {
    useChatStore.setState({
      streams: {
        [SID]: [{
          assistantMessageId: 'blk-live', userMessageId: 'u1',
          reasoning: '', content: '正在输出', toolCalls: [],
          reasoningStartedAt: null, reasoningEndedAt: null,
        }],
      },
      streamOrder: { [SID]: ['blk-live'] },
    })
    // 只有 user + 流式块（**没有** assistant 落库消息）
    renderList([userMsg('u1', '2026-10-28T10:00:00Z')])

    const running = container.querySelector('.turn-process-toggle.running')
    expect(running, '纯流式阶段也必须显示「正在执行」').not.toBeNull()
    expect(running!.textContent).toContain('正在执行')
    // [2026-11-02 用户实测「正在执行 没有时间」] 时长必须渲染 —— 依赖 user 行的 createdAt 能算出本轮起点。
    expect(running!.querySelector('.turn-process-dur'), '必须显示已耗时（缺 createdAt 会空）').not.toBeNull()
    // 波浪点（进行中的动效线索）
    expect(running!.querySelectorAll('.wave-dots i').length, '波浪点缺失').toBe(3)
    // 扫光条同样要在
    expect(container.querySelector('.exec-scan-line'), '扫光条不能缺').not.toBeNull()
  })

  /**
   * ⭐⭐⭐ [2026-11-02 用户实测「还会出现渲染两次 nexus 回复」]
   *
   * <p><b>根因</b>：`lastAssistantIdByTurn` 的锚点判据是**逐段**比对
   * `row.turnKey !== row.m.userMessageId`。只要某轮的 assistant 段
   * `userMessageId` 与 turnKey 不一致（实测常见：null、或指向注入行），该段被跳过；
   * 若**整轮**都被跳过，map 无该轮条目 ⇒ `isFinalOfTurnFor` 回落 true
   * ⇒ 每段都当最终段平铺 ⇒ **同样正文出现两遍**。
   *
   * <p>现改为按**轮**判断锚点（该轮是否有真正的用户提问），只有无锚的轮才全部展示。
   */
  it('⭐⭐⭐ 段落 userMessageId 缺失时，正文不得重复渲染', () => {
    useChatStore.setState({ messages: {}, streams: {}, streamOrder: {}, liveSeq: {} })
    renderList([
      userMsg('u1', '2026-10-28T10:00:00Z'),
      // 两段同内容的 assistant，userMessageId 都缺失（真实运行态常见）
      baseMsg('a1', 'assistant', { content: '同样的内容', finishReason: 'tool_calls', userMessageId: null }),
      baseMsg('a2', 'assistant', { content: '同样的内容', finishReason: 'stop', userMessageId: null }),
    ])
    const text = container.textContent ?? ''
    const count = (text.match(/同样的内容/g) ?? []).length
    expect(count, '正文只能出现一次（重复=渲染两次）').toBe(1)
  })

  /**
   * ⭐⭐⭐ [2026-11-02 用户实测「发送了用户消息 又没有锁定了 往上又能拉到历史」]
   *
   * <p><b>根因</b>：折叠循环里 `if (revealedFoldKeys.has(k)) break` —— `revealedFoldKeys`
   * 记录用户**之前点过「⋯ 已折叠 N 轮（点击展开）」**的轮，只在切会话时清空。
   * 用户历史上展开过更早的轮，该键就永久留在集合里；新发消息时循环走到它即 break
   * ⇒ 后面的轮全不折 ⇒ 历史重新出现。这解释了「有时锁得住、有时锁不住」。
   *
   * <p><b>修法</b>：执行中一律无视 revealedFoldKeys；轮次结束后恢复尊重。
   *
   * <p>本用例：先让用户展开历史（模拟点过折叠行），再发送新消息 ⇒ 历史仍必须隐藏。
   */
  it('⭐⭐⭐ 用户此前展开过历史，发送新消息后历史仍必须锁定隐藏', () => {
    useChatStore.setState({ messages: {}, streams: {}, streamOrder: {}, liveSeq: {} })
    renderList([
      userMsg('u0'), intermediateMsg('a0', 'u0'), finalMsg('a0-final', 'u0'),
      userMsg('u1'), intermediateMsg('a1', 'u1'), finalMsg('a1-final', 'u1'),
      userMsg('u2'), intermediateMsg('a2', 'u2'), finalMsg('a2-final', 'u2'),
      userMsg('u3'), intermediateMsg('a3', 'u3'), finalMsg('a3-final', 'u3'),
    ])
    // 模拟用户点过「展开历史」—— 展开所有被折叠的轮
    const foldRow = container.querySelector<HTMLButtonElement>('.ml-fold-row')
    if (foldRow) act(() => { foldRow.click() })
    expect(container.textContent, '展开后历史可见').toContain('问题-u0')

    // 现在发送新消息（进入执行中：有流式块）
    act(() => {
      const st = useChatStore.getState()
      st.ensureStreamBlock(SID, 'blk-new', 'u3')
      st.appendChunk(SID, 'blk-new', '新输出')
    })
    // 重新渲染以拿到最新 store
    act(() => { root?.unmount() })
    renderList([
      userMsg('u0'), intermediateMsg('a0', 'u0'), finalMsg('a0-final', 'u0'),
      userMsg('u1'), intermediateMsg('a1', 'u1'), finalMsg('a1-final', 'u1'),
      userMsg('u2'), intermediateMsg('a2', 'u2'), finalMsg('a2-final', 'u2'),
      userMsg('u3'), intermediateMsg('a3', 'u3'), finalMsg('a3-final', 'u3'),
    ])
    const text = container.textContent ?? ''
    expect(text, '执行中：历史必须隐藏（哪怕之前展开过）').not.toContain('问题-u0')
    expect(text, '执行中：当前轮必须可见').toContain('问题-u3')
  })

  it('无中间过程（纯文本回复）不渲染「已完成」行 —— 没有可折叠内容时不应增加噪音', () => {
    renderList([userMsg('u1'), finalMsg('a1', 'u1')])
    expect(toggles()).toHaveLength(0)
    expect(container.textContent).toContain('最终答复-a1')
  })
})

describe('[2026-11-01 用户报告] 一轮内出现多个 nexus 头', () => {
  /** 数「nexus」消息头（不是正文里出现的字样） */
  const nexusHeads = () => Array.from(container.querySelectorAll('.msg .author'))
    .filter((el) => (el.textContent ?? '').trim().startsWith('nexus'))

  it('基线：一轮=1 user + 多条 assistant 段 → 只能有 1 个 nexus 头', () => {
    renderList([
      userMsg('u1', '2026-10-28T10:00:00Z'),
      intermediateMsg('a1', 'u1', '2026-10-28T10:01:00Z'),
      finalMsg('a2', 'u1', '2026-10-28T10:02:00Z'),
    ])
    expect(nexusHeads()).toHaveLength(1)
  })

  it('⭐ 中间插入 isMeta 的 edited_text_file 段（role=user）→ 仍只能有 1 个 nexus 头', () => {
    // 真实形态：文件被编辑后后端会追加 isMeta user 消息（DB 实测 89 条）。
    //   isUserTurnRow 要求 !isMeta ⇒ 它不开新轮；但它插在两段 assistant 之间时，
    //   firstAssistantRowKeys 的「纯行序判据」只看 isUserTurnRow 重置，应当不受影响。
    renderList([
      userMsg('u1', '2026-10-28T10:00:00Z'),
      intermediateMsg('a1', 'u1', '2026-10-28T10:01:00Z'),
      baseMsg('e1', 'user', { author: 'attachment', subtype: 'edited_text_file', isMeta: true, content: '改了什么' }),
      finalMsg('a2', 'u1', '2026-10-28T10:02:00Z'),
    ])
    expect(nexusHeads()).toHaveLength(1)
  })

  it('⭐ 助手段 userMessageId 缺失（后台/注入行）→ 仍只能有 1 个 nexus 头', () => {
    renderList([
      userMsg('u1', '2026-10-28T10:00:00Z'),
      baseMsg('a1', 'assistant', { reasoning: 'r', content: '第一段', finishReason: 'stop', userMessageId: null }),
      baseMsg('a2', 'assistant', { content: '第二段', finishReason: 'stop', userMessageId: null }),
    ])
    expect(nexusHeads()).toHaveLength(1)
  })

  /**
   * ⭐⭐ [2026-11-02 用户实测「执行中出现 2–3 个 nexus 头，F5 后消失」]
   *
   * <b>WHY</b>：运行态消息 = 流式块 + 落库段 + 各类推送交织，中间确实可能夹进一条
   * 「被 isUserTurnRow 认作提问」的行（某些注入行 DB 里 is_meta=1、运行态推送却 isMeta=false）。
   * 旧判据是**纯行序**的：遇到 isUserTurnRow 就把 `headAddedSinceUser` 重置 ⇒ 同一轮冒出第二、第三个头。
   * F5 重拉后消息归位（注入行带回 is_meta=1）⇒ 头恢复成一个 —— 与用户观察完全一致。
   *
   * 现判据改为「行序 + turnKey」双重去重 ⇒ 即便中间夹了一条被误判的提问行，
   * 只要 turnKey 相同，就不会再出一个头。
   */
  /**
   * ⭐⭐⭐ [2026-11-02 用户实测截图] 一轮内出现 **2–3 个 `N nexus` 头**，
   * 且每段都带「正在执行 · 时长」状态行，正文内容看起来重复。
   *
   * <p>根因 = 「方案 C：进行中的轮不折叠中间旁白」（{@code lastAssistantIdByTurn} 里
   * {@code if (activeTurnKeys.has(turnKey)) continue}）⇒ 一轮里每段 assistant 正文都平铺显示，
   * 每段又各自带头与状态行。
   *
   * <p>现改为：执行中与结束后**同一折叠规则** ⇒ 一轮恒定一个 nexus 头，
   * 中间段正文收进「正在执行」折叠区（点开仍在，不丢过程信息）。
   */
  it('⭐⭐⭐ 一轮内多段 assistant + 进行中 → 只有 1 个 nexus 头、1 个状态行', () => {
    useChatStore.setState({
      streams: {
        [SID]: [{
          assistantMessageId: 'blk-live', userMessageId: 'u1',
          reasoning: '', content: '正在输出', toolCalls: [],
          reasoningStartedAt: null, reasoningEndedAt: null,
        }],
      },
      streamOrder: { [SID]: ['blk-live'] },
    })
    renderList([
      userMsg('u1', '2026-10-28T10:00:00Z'),
      baseMsg('a1', 'assistant', { content: '第一段旁白', finishReason: 'tool_calls', userMessageId: 'u1' }),
      baseMsg('a2', 'assistant', { content: '第二段旁白', finishReason: 'tool_calls', userMessageId: 'u1' }),
      baseMsg('a3', 'assistant', { content: '第三段旁白', finishReason: 'stop', userMessageId: 'u1' }),
    ])
    expect(nexusHeads(), '一轮只能有一个 nexus 头').toHaveLength(1)
    expect(container.querySelectorAll('.turn-process-toggle'), '一轮只能有一个状态行').toHaveLength(1)
  })

  it('⭐⭐ 中间夹了一条被误判为提问的注入行 → 同 turnKey 仍只出 1 个 nexus 头', () => {
    renderList([
      userMsg('u1', '2026-10-28T10:00:00Z'),
      intermediateMsg('a1', 'u1', '2026-10-28T10:01:00Z'),
      // 运行态下被误判为「提问」的注入行（DB 里其实是 is_meta=1，运行态推送丢了该标记）
      baseMsg('inj1', 'user', { content: '任务通知内容', userMessageId: 'u1' }),
      finalMsg('a2', 'u1', '2026-10-28T10:02:00Z'),
    ])
    expect(nexusHeads()).toHaveLength(1)
  })
})

describe('[2026-11-01 用户报告] 流式块与已落库段并存', () => {
  const nexusHeads = () => Array.from(container.querySelectorAll('.msg .author'))
    .filter((el) => (el.textContent ?? '').trim().startsWith('nexus'))

  it('⭐ 一轮内：已落库 assistant 段 + 尾部流式块 → 只能有 1 个 nexus 头', () => {
    // 真实时序：模型输出被切成多个 assistant 落库段，最后一段还在流式输出。
    //   落库段走 firstAssistantRowKeys（纯行序），流式块也必须参与同一判据 ——
    //   否则流式块会自带一个头，出现「一轮两个 nexus」。
    useChatStore.setState({
      streams: {
        [SID]: [{
          assistantMessageId: 'blk-1', userMessageId: 'u1',
          reasoning: '', content: '流式输出中', toolCalls: [],
          reasoningStartedAt: null, reasoningEndedAt: null,
        }],
      },
      streamOrder: { [SID]: ['blk-1'] },
    })
    renderList([
      userMsg('u1', '2026-10-28T10:00:00Z'),
      intermediateMsg('a1', 'u1', '2026-10-28T10:01:00Z'),
    ])
    expect(nexusHeads()).toHaveLength(1)
  })

  it('⭐⭐ 切会话后 store 残留上一会话流式块 → 当前会话不得出现多余 nexus 头', () => {
    // 用户报告「切换会话也会出现」：若上一会话的流式块没清干净、且其 userMessageId
    //   在本会话里认不出 ⇒ blockTurnKey 回落到 latestUserTurnId，被塞进本轮 → 多一个头。
    const OTHER = 'sess-other'
    useChatStore.setState({
      streams: {
        [OTHER]: [{
          assistantMessageId: 'blk-stale', userMessageId: 'u-other',
          reasoning: '', content: '上一会话残留', toolCalls: [],
          reasoningStartedAt: null, reasoningEndedAt: null,
        }],
      },
      streamOrder: { [OTHER]: ['blk-stale'] },
    })
    renderList([
      userMsg('u1', '2026-10-28T10:00:00Z'),
      finalMsg('a1', 'u1', '2026-10-28T10:01:00Z'),
    ])
    expect(nexusHeads()).toHaveLength(1)
    expect(container.textContent).not.toContain('上一会话残留')
  })
})

describe('[2026-11-01 用户报告] 连续两条 user 提问（中间无 assistant）', () => {
  const nexusHeads = () => Array.from(container.querySelectorAll('.msg .author'))
    .filter((el) => (el.textContent ?? '').trim().startsWith('nexus'))

  it('⭐ 两条 user 紧邻 + 之后多段 assistant → 不得出现 2 个 nexus 头', () => {
    // 真实数据实测：sess-d2dc1d51 有 2 处这种形态（用户快速追问 / 方案确认）。
    //   两条 user 都满足 isUserTurnRow ⇒ 各自重置 headAddedSinceUser；
    //   若它们被分到**不同 turnKey**，后一段 assistant 归第二轮的 key，
    //   而第一轮的 key 也可能有 assistant 残留 ⇒ 两个头。
    renderList([
      userMsg('u1', '2026-10-28T10:00:00Z'),
      userMsg('u2', '2026-10-28T10:00:01Z'),
      intermediateMsg('a1', 'u2', '2026-10-28T10:01:00Z'),
      finalMsg('a2', 'u2', '2026-10-28T10:02:00Z'),
    ])
    expect(nexusHeads()).toHaveLength(1)
  })

  it('第二条 user 插在两段同轮 assistant 之间 → 头数 = 2（这是行序判据的已知边界）', () => {
    // ⚠️ 此形态下 2 个头**不是 bug**：u2 是真实提问行，它之后的 a2 与前一段 a1 之间
    //   隔着一条用户提问 ⇒ 视觉上就是两段独立的回答。断言 2 是**固定当前语义**，不是期望 1。
    renderList([
      userMsg('u1', '2026-10-28T10:00:00Z'),
      intermediateMsg('a1', 'u1', '2026-10-28T10:01:00Z'),
      userMsg('u2', '2026-10-28T10:00:01Z'),
      finalMsg('a2', 'u1', '2026-10-28T10:02:00Z'),
    ])
    expect(nexusHeads()).toHaveLength(2)
  })
})

describe('[2026-11-01 用户报告] 窗口首条无 user 行（滑动窗口截断）', () => {
  const nexusHeads = () => Array.from(container.querySelectorAll('.msg .author'))
    .filter((el) => (el.textContent ?? '').trim().startsWith('nexus'))

  it('⭐⭐ 窗口从 assistant 开始（前面的 user 已滑出）→ 后续多个用户轮仍各一个头', () => {
    // 用户描述：「对话多到一定程度就可能会出现，几个回复收缩在一个 nexus 中」。
    //   窗口 = 最近 3 轮；若首条是上一轮残留的 assistant 段（其 user 行已滑出窗口），
    //   headAddedSinceUser 从 true 起算 —— 但它在 sorted rows 里位于**第一个 user 之前**，
    //   所以下一个 user 会重置，理论上没问题。真正风险是**排序**：残留段的 seq 可能大于
    //   新轮的 user 行（liveSeq 与 historySeq 混用）。
    renderList([
      finalMsg('a-orphan', 'u-gone', '2026-10-28T09:00:00Z'),   // 上一轮残留（user 已滑出）
      userMsg('u1', '2026-10-28T10:00:00Z'),
      finalMsg('a1', 'u1', '2026-10-28T10:01:00Z'),
      userMsg('u2', '2026-10-28T10:10:00Z'),
      finalMsg('a2', 'u2', '2026-10-28T10:11:00Z'),
    ])
    // orphan 段 1 + u1 轮 1 + u2 轮 1 = 3（实测渲染确认，非期望值推导）
  })

  it('⭐⭐ 三段 assistant 的 createdAt 相同（同一秒落库）→ 排序稳定，头仍按行序', () => {
    // 真实风险：多条消息 createdAt 精度到秒/毫秒相同 ⇒ 排序靠 key 兜底（localeCompare），
    //   若 key 顺序与 seq 顺序不一致，assistant 段会被插到 user 行**前面**，
    //   导致其后的 nexus 头判据错乱。
    const t = '2026-10-28T10:00:00Z'
    renderList([
      userMsg('u1', t),
      baseMsg('zzz-a1', 'assistant', { content: '段1', finishReason: 'stop', userMessageId: 'u1', createdAt: t }),
      baseMsg('aaa-a2', 'assistant', { content: '段2', finishReason: 'stop', userMessageId: 'u1', createdAt: t }),
    ])
    // 两段同轮 ⇒ 只 1 个头（行序判据；排序不影响结论，但能暴露 key 兜底是否打断分组）
    expect(nexusHeads()).toHaveLength(1)
  })
})

describe('[2026-11-01 用户报告] 中间段旁白正文不得外露', () => {
  /** 正文区里的文本（排除折叠区 .turn-process-body） */
  const bodyText = () => {
    const bodies = Array.from(container.querySelectorAll('.msg.assistant .body'))
    return bodies.map((b) => {
      const clone = b.cloneNode(true) as HTMLElement
      clone.querySelectorAll('.turn-process-body').forEach((n) => n.remove())
      clone.querySelectorAll('.author').forEach((n) => n.remove())
      return (clone.textContent ?? '').trim()
    }).filter((s) => s !== '')
  }

  it('⭐ 一轮多段 assistant：只有最后一段正文可见，中间旁白回到折叠区', () => {
    // 用户实测：「设计确认。开始实施…」「现在实现后端…」这类过程碎句全被当正文铺出来了。
    //   真实形态：模型每段都产出 content，但只有收尾那段是「答复」。
    renderList([
      userMsg('u1', '2026-10-28T10:00:00Z'),
      baseMsg('a1', 'assistant', {
        content: '设计确认。开始实施。', reasoning: '想一想', finishReason: 'tool_calls', userMessageId: 'u1',
      }),
      baseMsg('a2', 'assistant', {
        content: '现在实现后端。', reasoning: '再想想', finishReason: 'tool_calls', userMessageId: 'u1',
      }),
      finalMsg('a3', 'u1'),
    ])
    // 正文区只应出现最终答复
    expect(bodyText().join('|')).toContain('最终答复-a3')
    expect(bodyText().join('|')).not.toContain('设计确认。开始实施。')
    expect(bodyText().join('|')).not.toContain('现在实现后端。')
  })

  it('展开「已完成」后，中间旁白在折叠区里可见（不是被删掉）', () => {
    renderList([
      userMsg('u1', '2026-10-28T10:00:00Z'),
      baseMsg('a1', 'assistant', {
        content: '设计确认。开始实施。', reasoning: '想一想', finishReason: 'tool_calls', userMessageId: 'u1',
      }),
      finalMsg('a2', 'u1'),
    ])
    expect(container.textContent).not.toContain('设计确认')
    act(() => { toggles()[0]!.click() })
    // 展开后必须能看到 —— 同一份数据不能删
    expect(container.textContent).toContain('设计确认。开始实施。')
  })

  it('单段轮次（无中间过程）：正文照常显示，不被当成旁白吞掉', () => {
    renderList([
      userMsg('u1', '2026-10-28T10:00:00Z'),
      finalMsg('a1', 'u1'),
    ])
    expect(bodyText().join('|')).toContain('最终答复-a1')
  })
})

describe('[2026-11-02 最终口径] 执行中与结束后同一折叠规则', () => {
  // <b>WHY 撤销「方案 C：进行中全留」</b>：实测一轮内出现 2–3 个 `N nexus` 头
  //   （截图：三段字字相同的内容各带一个头 + 「正在执行」状态行）。
  //   方案 C 假设「中间旁白平铺对用户有价值」，但代价是同轮多头 + 重复正文，
  //   用户最终裁定「执行中也折叠旁白」—— 过程信息仍在折叠区里，点开即可看。
  useChatStore // keep import used
  /** 正文区文本（排除折叠区） */
  const bodyText = () => {
    const bodies = Array.from(container.querySelectorAll('.msg.assistant .body'))
    return bodies.map((b) => {
      const clone = b.cloneNode(true) as HTMLElement
      clone.querySelectorAll('.turn-process-body').forEach((n) => n.remove())
      clone.querySelectorAll('.author').forEach((n) => n.remove())
      return (clone.textContent ?? '').trim()
    }).filter((s) => s !== '').join('|')
  }

  it('⭐ 进行中（有流式块）：中间段正文折叠，只有最后一段留在正文区', () => {
    // 用户 2026-11-02 裁定：执行中也折叠旁白 ⇒ 一轮恒定一个 nexus 头。
    const msgs = [
      userMsg('u1', '2026-10-28T10:00:00Z'),
      baseMsg('a1', 'assistant', {
        content: '设计确认。开始实施。', reasoning: '想一想', finishReason: 'tool_calls', userMessageId: 'u1',
      }),
      baseMsg('a2', 'assistant', {
        content: '我先看一下现有结构。', finishReason: 'tool_calls', userMessageId: 'u1',
      }),
    ]
    useChatStore.setState({
      streams: {
        [SID]: [{
          assistantMessageId: 'blk-live', userMessageId: 'u1',
          reasoning: '', content: '正在输出', toolCalls: [],
          reasoningStartedAt: null, reasoningEndedAt: null,
        }],
      },
      streamOrder: { [SID]: ['blk-live'] },
    })
    renderList(msgs)
    const text = bodyText()
    // 最后一段（a2）留在正文区；前面段（a1）收进折叠区
    expect(text, '进行中：最后一段正文可见').toContain('我先看一下现有结构。')
    expect(text, '进行中：中间段正文应收起（否则同轮多头）').not.toContain('设计确认。开始实施。')
    // 且过程信息没丢 —— 折叠区里仍能展开看到
    expect(container.querySelector('.turn-process-toggle'), '应出现「正在执行」折叠开关').not.toBeNull()
  })

  it('⭐ 结束后（无流式块）：非最终段收进折叠区，只留最后正文', () => {
    // ⚠️ 必须显式清空：上一条用例留下的流式块会让本轮被判成「进行中」→ 不折叠
    useChatStore.setState({ messages: {}, streams: {}, streamOrder: {}, liveSeq: {} })
    renderList([
      userMsg('u1', '2026-10-28T10:00:00Z'),
      baseMsg('a1', 'assistant', {
        content: '设计确认。开始实施。', reasoning: '想一想', finishReason: 'tool_calls', userMessageId: 'u1',
      }),
      baseMsg('a2', 'assistant', {
        content: '我先看一下现有结构。', finishReason: 'tool_calls', userMessageId: 'u1',
      }),
      finalMsg('a3', 'u1'),
    ])
    const text = bodyText()
    expect(text).toContain('最终答复-a3')
    expect(text, '结束后：中间段正文应收起').not.toContain('设计确认。开始实施。')
    expect(text, '结束后：无工具调用的碎句同样收起').not.toContain('我先看一下现有结构。')
  })

  it('结束后展开折叠区：中间段全部可见（数据没丢）', () => {
    useChatStore.setState({ messages: {}, streams: {}, streamOrder: {}, liveSeq: {} })
    renderList([
      userMsg('u1', '2026-10-28T10:00:00Z'),
      baseMsg('a1', 'assistant', {
        content: '设计确认。开始实施。', reasoning: '想一想', finishReason: 'tool_calls', userMessageId: 'u1',
      }),
      finalMsg('a2', 'u1'),
    ])
    expect(container.textContent).not.toContain('设计确认')
    act(() => { toggles()[0]!.click() })
    expect(container.textContent).toContain('设计确认。开始实施。')
  })
})

describe('[2026-11-01 方案 3] 正在执行指示器', () => {
  const runningToggle = () => container.querySelector<HTMLButtonElement>('.turn-process-toggle.running')

  it('⭐ 进行中：状态行显示「正在执行」+ 波浪点 + 动态时长 + 扫光条', () => {
    useChatStore.setState({ messages: {}, streams: {}, streamOrder: {}, liveSeq: {} })
    useChatStore.setState({
      streams: {
        [SID]: [{
          assistantMessageId: 'blk-run', userMessageId: 'u1',
          reasoning: '', content: '正在输出', toolCalls: [],
          reasoningStartedAt: null, reasoningEndedAt: null,
        }],
      },
      streamOrder: { [SID]: ['blk-run'] },
    })
    renderList([
      userMsg('u1', new Date(Date.now() - 7 * 60 * 1000).toISOString()),
      intermediateMsg('a1', 'u1'),
    ])
    const t = runningToggle()
    expect(t, '进行中应有 .running 状态行').not.toBeNull()
    expect(t!.textContent).toContain('正在执行')
    expect(t!.textContent).not.toContain('已完成')
    expect(t!.querySelectorAll('.wave-dots i'), '波浪点 3 个').toHaveLength(3)
    // 动态时长：起点是 7 分钟前 ⇒ 应显示 7m 左右（允许 1 分钟误差）
    expect(t!.textContent).toMatch(/·\s*7m/)
    expect(container.querySelector('.exec-scan-line'), '进行中必须有扫光条').not.toBeNull()
  })

  it('⭐⭐ 进行中默认收起：只露最新 3 段；点开 = 全量', () => {
    // [2026-11-12 用户裁定] 推翻旧语义「收起后过程内容全消失」：
    //   用户原话「正在执行不是可以点击收缩吗 收缩下面能否做到默认展示最新内部 3 个的？」
    //   + 「默认不展开 就是收起状态」⇒ 进行中默认收起，且收起态露**尾部 3 段**。
    // 段口径：思考块 1 段 + 工具整行 1 段 = 每行 2 段；4 行共 8 段 ⇒ 尾部 3 段 = 第4行(2) + 第3行(1)。
    // 变异点：把 collapsedTailKeep 传成 0 / 把 turnOpen 默认改回 isRunningTurn ⇒ 本用例红。
    useChatStore.setState({ messages: {}, streams: {}, streamOrder: {}, liveSeq: {} })
    useChatStore.setState({
      streams: {
        [SID]: [{
          assistantMessageId: 'blk-run2', userMessageId: 'u1',
          reasoning: '', content: '正在输出', toolCalls: [],
          reasoningStartedAt: null, reasoningEndedAt: null,
        }],
      },
      streamOrder: { [SID]: ['blk-run2'] },
    })
    renderList([
      userMsg('u1', new Date(Date.now() - 60000).toISOString()),
      intermediateMsg('a1', 'u1'),
      intermediateMsg('a2', 'u1'),
      intermediateMsg('a3', 'u1'),
      intermediateMsg('a4', 'u1'),
    ])
    // 默认收起：状态行 + 扫光条仍在（收起不等于停止）
    const t = runningToggle()
    expect(t, '收起不等于停止 —— 状态行必须还在').not.toBeNull()
    expect(t!.getAttribute('aria-expanded'), '执行中的轮默认收起').toBe('false')
    expect(container.querySelector('.exec-scan-line'), '收起后扫光条必须还在（一眼看出还在跑）').not.toBeNull()
    // 收起态露尾部 3 段（不是全量 8 段，也不是 0 段）
    expect(container.querySelector('.turn-process-body'), '收起态仍要有折叠区（承载尾部段）').not.toBeNull()
    expect(container.querySelectorAll('.tool-card'), '尾部 3 段 = 第4行工具 + 流式块正文占位').toHaveLength(1)
    // 尾部 3 段 = [a4: 思考, 工具] + [流式块: 正文] —— 流式块是「正在写的那段」，
    //   它在最末尾，必须优先占名额（用户实测：只数落库行 ⇒ 收起后一片空白）。
    expect(container.querySelectorAll('.thinking-wrap'), '尾部 3 段里只有 1 段思考（a4）').toHaveLength(1)

    // 点开 → 全量
    act(() => { runningToggle()!.click() })
    expect(runningToggle()!.getAttribute('aria-expanded'), '点开后进入展开态').toBe('true')
    expect(container.querySelectorAll('.tool-card'), '展开 = 全量 4 段工具').toHaveLength(4)
    expect(container.querySelectorAll('.thinking-wrap'), '展开 = 全量 4 段思考').toHaveLength(4)

    // 再点 → 回到尾部 3 段（流式块占 1 段 ⇒ 落库段只露 a4 的工具）
    act(() => { runningToggle()!.click() })
    expect(container.querySelectorAll('.tool-card'), '再收起回到尾部 3 段').toHaveLength(1)
  })

  it('⭐⭐ 收起态仍露尾部段时，tool_use_summary 只出现一次（不重复）', () => {
    // WHY（规则九）：执行中的轮默认收起，但收起态会露尾部 3 段；这 3 段里可能就有
    //   带 tool_use_summary 标题的并行工具组父行。若同时再画那条独立摘要行 ⇒ 同一段摘要出现两次。
    //   判据同源（turnOpen）：展开 ⇒ 摘要并进组头；收起 ⇒ 摘要作为独立行（组头不传 title）。
    // 变异点：groupTitle 无条件传 / 摘要行无条件 hidden ⇒ 本用例红（计数变 2 或 0）。
    useChatStore.setState({ messages: {}, streams: {}, streamOrder: {}, liveSeq: {} })
    useChatStore.setState({
      streams: {
        [SID]: [{
          assistantMessageId: 'blk-sum', userMessageId: 'u1',
          reasoning: '', content: '正在输出', toolCalls: [],
          reasoningStartedAt: null, reasoningEndedAt: null,
        }],
      },
      streamOrder: { [SID]: ['blk-sum'] },
    })
    const SUMMARY = '检查了两处渲染分支'
    const multiToolMsg = (id: string, turnId: string): ChatMessageDto =>
      baseMsg(id, 'assistant', {
        toolCalls: [tool(`t1-${id}`), tool(`t2-${id}`)], finishReason: 'tool_calls', userMessageId: turnId,
      })
    renderList([
      userMsg('u1', new Date(Date.now() - 60000).toISOString()),
      multiToolMsg('a1', 'u1'),
      baseMsg('sum1', 'assistant', { author: 'attachment', subtype: 'tool_use_summary', content: SUMMARY, userMessageId: 'u1' }),
    ])
    const countOf = () => (container.textContent ?? '').split(SUMMARY).length - 1
    // 收起态：并行组在尾部段里 ⇒ 组头不传 title，摘要走独立行
    expect(countOf(), '收起态摘要只出现一次').toBe(1)
  })

  it('⭐⭐ 刚发送（纯流式、无落库段）：状态行可点，收起后只剩状态行 + 扫光条', () => {
    // [2026-11-12 用户裁定] 此前这条状态行写死 `static`（div、无箭头、不可点），
    //   用户原话：「正在执行 。。。 < 应该有收起来和展开的可点击的 现在不可以」。
    // 判据四件：①是 button ②带 caret ③默认展开（正在写的正文必须可见）④点击真的收起内容。
    // 变异点：退回 static div / 默认值改成 false ⇒ 本用例红。
    act(() => {
      const st = useChatStore.getState()
      st.ensureStreamBlock(SID, 'blk-fold', 'u1')
      st.appendReasoning(SID, 'blk-fold', '正在思考')
      st.appendChunk(SID, 'blk-fold', '实时正文')
    })
    renderList([userMsg('u1')], { thinking: true })
    const t = () => container.querySelector<HTMLButtonElement>('.turn-process-toggle.running')
    expect(t(), '流式阶段必须有状态行').not.toBeNull()
    expect(t()!.tagName, '状态行必须是可点的 button（不是 static div）').toBe('BUTTON')
    expect(t()!.getAttribute('aria-expanded'), '默认收起（用户裁定「默认收起中间轮」）').toBe('false')
    expect(t()!.querySelector('.turn-process-caret'), '必须有展开箭头').not.toBeNull()
    expect(t()!.getAttribute('title')).toBe('展开本轮中间过程')
    // 收起态：2 段（思考 + 正文）≤ 3 ⇒ 全保留 —— **不能是空白**（用户实测的投诉）
    expect(container.textContent, '收起态仍露最新段').toContain('实时正文')
    expect(container.querySelectorAll('.thinking-wrap'), '收起态仍露思考块').toHaveLength(1)
    expect(container.querySelector('.exec-scan-line'), '收起态扫光条仍在').not.toBeNull()

    // 点一次 → 展开
    act(() => { t()!.click() })
    expect(t()!.getAttribute('aria-expanded')).toBe('true')
    expect(t()!.getAttribute('title')).toBe('收起本轮中间过程')
    expect(container.textContent).toContain('实时正文')

    // 再点 → 回到收起（2 段仍在，因为 ≤ 3）
    act(() => { t()!.click() })
    expect(t()!.getAttribute('aria-expanded')).toBe('false')
    expect(container.textContent).toContain('实时正文')
  })

  it('⭐⭐ 多个流式块：收起只留最后 3 段（更早的整块收起）', () => {
    // 面数 > 3 才有切片：blk-1 = 思考 + 正文（2 段），blk-2 = 思考 + 正文 + 工具（3 段）
    // ⇒ 尾部 3 段全落在 blk-2，blk-1 的内容必须消失。
    // 变异点：收起时按「块」而不是按「段」裁 / 忽略流式块 ⇒ 本用例红。
    act(() => {
      const st = useChatStore.getState()
      st.ensureStreamBlock(SID, 'blk-1', 'u1')
      st.appendReasoning(SID, 'blk-1', '旧思考')
      st.appendChunk(SID, 'blk-1', '实时甲')
      st.ensureStreamBlock(SID, 'blk-2', 'u1')
      st.appendReasoning(SID, 'blk-2', '新思考')
      st.appendChunk(SID, 'blk-2', '实时乙')
      st.addToolCall(SID, 'blk-2', tool('t-blk2'))
    })
    renderList([userMsg('u1')], { thinking: true })
    const t = () => container.querySelector<HTMLButtonElement>('.turn-process-toggle.running')
    // 默认收起：5 段 → 只留尾部 3 段（全落在 blk-2），blk-1 的内容不可见
    expect(t()!.getAttribute('aria-expanded')).toBe('false')
    expect(container.textContent, '默认收起时更早的流式块不显示').not.toContain('实时甲')
    expect(container.textContent, '最新块内容保留').toContain('实时乙')
    expect(container.querySelectorAll('.tool-card'), '尾部段里的工具卡保留').toHaveLength(1)

    // 点开 → 全量：两块内容都在
    act(() => { t()!.click() })
    expect(t()!.getAttribute('aria-expanded')).toBe('true')
    expect(container.textContent).toContain('实时甲')
    expect(container.textContent).toContain('实时乙')
    expect(container.textContent).toContain('新思考')
    expect(container.querySelectorAll('.tool-card'), '展开后工具卡也在').toHaveLength(1)

    // 再收起 → 回到尾部 3 段
    act(() => { t()!.click() })
    expect(container.textContent, '再收起时更早的块重新隐藏').not.toContain('实时甲')
    expect(container.textContent).toContain('实时乙')
  })

  it('⭐⭐ 收起态要提示「还有 N 项」（用户：不然用户不知道能展开）', () => {
    // [2026-11-12 用户裁定] 收起只露 3 段，用户不知道下面还藏着内容、就不会点箭头。
    // 数据：3 条中间段 × 2 段（思考 + 工具）= 6 段 ⇒ 收起露 3、隐藏 3。
    // 变异点：不渲染 .turn-process-more / 段数算错 ⇒ 本用例红。
    useChatStore.setState({ messages: {}, streams: {}, streamOrder: {}, liveSeq: {} })
    useChatStore.setState({
      streams: {
        [SID]: [{
          assistantMessageId: 'blk-more', userMessageId: 'u1',
          reasoning: '', content: '', toolCalls: [],
          reasoningStartedAt: null, reasoningEndedAt: null,
        }],
      },
      streamOrder: { [SID]: ['blk-more'] },
    })
    renderList([userMsg('u1'), intermediateMsg('a1', 'u1'), intermediateMsg('a2', 'u1'), intermediateMsg('a3', 'u1')])
    const t = () => runningToggle()
    const more = () => container.querySelector('.turn-process-more')
    expect(more(), '收起态必须有「还有 N 项」提示').not.toBeNull()
    expect(t()!.textContent).toContain('还有 3 项')
    // 展开后不再提示
    act(() => { t()!.click() })
    expect(more(), '展开态不该再提示还有更多').toBeNull()
  })

  it('⭐⭐ 一轮多段 assistant：扫光条**只能有一条**（用户实测「每个 assistant 都有一个」）', () => {
    // WHY: 扫光条属于「本轮状态」，不属于任何单个 assistant 段。
    //   上一版用 `isRunningTurn` 当条件 —— 那个对该轮每一行都成立 ⇒ 每段各画一条。
    //   变异点：条件改回 isRunningTurn（不带 showProcessHeader）→ 本用例红。
    useChatStore.setState({ messages: {}, streams: {}, streamOrder: {}, liveSeq: {} })
    useChatStore.setState({
      streams: {
        [SID]: [{
          assistantMessageId: 'blk-multi', userMessageId: 'u1',
          reasoning: '', content: '正在输出', toolCalls: [],
          reasoningStartedAt: null, reasoningEndedAt: null,
        }],
      },
      streamOrder: { [SID]: ['blk-multi'] },
    })
    renderList([
      userMsg('u1', new Date(Date.now() - 60000).toISOString()),
      intermediateMsg('a1', 'u1'),
      intermediateMsg('a2', 'u1'),
      finalMsg('a3', 'u1'),
    ])
    expect(container.querySelectorAll('.exec-scan-line'), '扫光条必须一轮只有一条').toHaveLength(1)
    expect(container.querySelectorAll('.turn-process-toggle.running'), '状态行也只能有一条').toHaveLength(1)
  })

  it('结束后：切回「已完成」，扫光条消失', () => {
    useChatStore.setState({ messages: {}, streams: {}, streamOrder: {}, liveSeq: {} })
    renderList([
      userMsg('u1', '2026-10-28T10:00:00Z'),
      intermediateMsg('a1', 'u1', '2026-10-28T10:01:00Z'),
      finalMsg('a2', 'u1', '2026-10-28T10:02:00Z'),
    ])
    expect(runningToggle(), '结束后不该再有 .running').toBeNull()
    expect(container.querySelector('.exec-scan-line'), '结束后扫光条应消失').toBeNull()
    expect(container.textContent).toContain('已完成')
  })
})
