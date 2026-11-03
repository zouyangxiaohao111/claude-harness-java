import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { Client, StompSubscription } from '@stomp/stompjs'
import { subagentApi } from '@/api/subagents'
import { commandApi } from '@/api/command'
import { ApiError } from '@/api/rest'
import { onSocketConnect } from '@/api/socket'
import type { AgentTranscriptMessage, ChatMessageDto } from '@/api/types'
import { MessageList } from '@/components/center/MessageList'
import { useSubagentStore } from '@/stores/subagentStore'

/**
 * 右栏「agent 对话视图」（2026-10-21 用户裁定 F2）。
 *
 * <p>点子代理 / TEAM 成员 → **右栏原地切换**成该 agent 的执行记录（不是弹窗），
 * 顶部「‹ 返回」回到任务列表。
 *
 * <p><b>[2026-11-02] 渲染改为直接复用主对话的 {@link MessageList}</b>：此前是逐条平铺
 * `<Message>` —— 没有轮次分组、没有「已完成/进行中」状态行与中间过程折叠、没有一轮一个
 * `N nexus` 头，工具卡与正文的折叠规则也和主对话不一致，视觉上明显是「另一套」。
 * 现在把 transcript 适配成 `ChatMessageDto[]` 后整表交给同一个组件，轮次/工具组/折叠/
 * 思考块全部继承主对话，不另写一套（用户原话：直接复制过去就行，复用组件）。
 *
 * <p><b>实时</b>：后端在 transcript 落盘处推 {@code /topic/sessions/{sid}/subagents/{agentId}}
 * （见 {@code SubagentTranscriptStreamer}），本组件订阅并按 uuid 幂等追加 —— 不再是「打开时拉一次
 * 就静止」。订阅建立后补拉一次，闭合「首拉完成 → 订阅生效」之间的窗口。
 *
 * <p>已停止的 agent 底部会出现输入框：发送即走内置 `/resume`（对齐 CC
 *「给已停止的 agent 发消息 = 自动 resume」，`SendMessage` → `resumeAgentBackground`）。
 */
export function AgentTranscriptView({ sessionId, agentId, name, subtitle, active, onBack, onExpand, expanded = false, showToast, clientRef }: {
  sessionId: string | null
  agentId: string
  name: string
  /** 副标题：agent 类型 / 「已完成」，显示在名字下方 */
  subtitle?: string
  /** 进入视图时的运行态快照；实时态以 subagent store 为准（见下） */
  active?: boolean
  onBack: () => void
  /** ⤢ 扩大：右栏向左覆盖中间区（不是弹窗） */
  onExpand?: () => void
  /** 当前是否已扩大（按钮图标切换） */
  expanded?: boolean
  showToast: (msg: string, type?: 'success' | 'info') => void
  /** 复用的 STOMP 连接（App 的 useChatSocket clientRef）· 省略 = 不订阅实时（测试/嵌入方） */
  clientRef?: { current: Client | null }
}) {
  const [msgs, setMsgs] = useState<AgentTranscriptMessage[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  /** 订阅期间收到的增量（已按 resolvedAgentId 过滤）· 与 msgs 按 uuid 合并 */
  const [live, setLive] = useState<AgentTranscriptMessage[]>([])
  const [draft, setDraft] = useState('')
  const [sending, setSending] = useState(false)

  /**
   * 运行态以 store 为准：RightPanel 的 `viewing.active` 是**点击那一刻**的快照，
   * 子代理结束后不会更新 ⇒ 视图会永远显示「运行中」。store 里的身份是实时更新的。
   */
  const identity = useSubagentStore((s) => {
    const session = s.bySession[sessionId ?? ''] ?? {}
    return session[agentId] ?? Object.values(session).find((i) => i.taskId === agentId) ?? null
  })
  /** 是否曾拿到过实时身份（身份被 forget 后不能回落到「点击那一刻的 active 快照」= 假运行中） */
  const hadIdentityRef = useRef(false)
  if (identity) hadIdentityRef.current = true
  const running = identity
    ? identity.status === 'running'
    : hadIdentityRef.current ? false : !!active
  const stopped = !running && (identity ? identity.status === 'stopped' : (subtitle ?? '').includes('已停止'))

  // 首次拉取完整执行记录（transcript 接口 · 三键查找含历史 a+16hex / taskId）。
  // 运行中的 agent 可能还没落第一条 transcript（404）→ 1.2s 后重试；上限 20 次（≈24s）
  // 防「永远 404 却一直轮询」—— 超过就按错误展示，用户可返回再点开重试。
  useEffect(() => {
    if (!sessionId) return
    let cancelled = false
    let timer: number | undefined
    let attempts = 0
    setMsgs(null)
    setError(null)
    setLive([])
    const run = () => {
      subagentApi.transcript(sessionId, agentId)
        .then((list) => { if (!cancelled) setMsgs(list) })
        .catch((e) => {
          if (cancelled) return
          const notFound = e instanceof ApiError && e.status === 404
          // [真机实测 2026-11-02] 判据必须同时看 `active`（行状态给的快照）——
          //   只认 store 里的 identity 会漏掉「后端重启后 STOMP task_started 丢失」的运行中 agent：
          //   它在 store 里没有身份 ⇒ running=false ⇒ 不重试 ⇒ 直接报「没有执行记录」。
          if (notFound && (running || active) && attempts < 20) {
            attempts += 1
            timer = window.setTimeout(run, 1200)
            return
          }
          setError(e instanceof ApiError ? e.userMessage() : String(e))
        })
    }
    run()
    return () => { cancelled = true; if (timer) window.clearTimeout(timer) }
    // running 只作为「要不要重试」的初始判据，不参与重拉（否则轮次结束会整表重拉）
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionId, agentId])

  /**
   * 规范化 agent id：REST 出站消息自带 `agentId`（a+16hex，transcript 文件键）。
   * 前端点开的可能是 taskId（后台任务 id）→ 只有拉过 transcript 才知道真实键，
   * 而 STOMP topic 用的正是这个键。
   */
  const resolvedAgentId = msgs?.[0]?.agentId ?? null

  const appendLive = useCallback((incoming: AgentTranscriptMessage[]) => {
    if (incoming.length === 0) return
    setLive((prev) => {
      const seen = new Set(prev.map((m) => m.uuid).filter(Boolean) as string[])
      const add = incoming.filter((m) => !m.uuid || !seen.has(m.uuid))
      return add.length > 0 ? [...prev, ...add] : prev
    })
  }, [])

  // 订阅该 agent 的 transcript 增量（断线重连后 onSocketConnect 会补订）
  useEffect(() => {
    if (!sessionId || !resolvedAgentId) return
    let sub: StompSubscription | null = null
    let cancelled = false
    const topic = `/topic/sessions/${sessionId}/subagents/${resolvedAgentId}`
    const handle = (frame: { body: string }) => {
      try {
        const payload = JSON.parse(frame.body) as { agentId?: string; messages?: AgentTranscriptMessage[] }
        if (payload.agentId && payload.agentId !== resolvedAgentId) return
        appendLive(payload.messages ?? [])
      } catch { /* 坏帧忽略：下一条增量仍会追加 */ }
    }
    const attach = () => {
      const client: Client | null = clientRef?.current ?? null
      if (!client?.connected) return
      try { sub?.unsubscribe() } catch { /* 旧订阅已随连接失效 */ }
      sub = client.subscribe(topic, handle)
      // 订阅生效后补拉一次：闭合「首次拉取完成 → 订阅建立」之间写入的消息（按 uuid 幂等）。
      // 每次都补拉（不只在运行中）—— 重连/状态翻面同样可能有丢窗，代价只是一次 REST。
      subagentApi.transcript(sessionId, resolvedAgentId)
        .then((list) => { if (!cancelled) setMsgs(list) })
        .catch(() => { /* 首次已成功过：这里失败不覆盖既有内容 */ })
    }
    attach()
    const off = onSocketConnect(attach)
    return () => {
      cancelled = true
      off()
      try { sub?.unsubscribe() } catch { /* 连接已失效 */ }
    }
  }, [sessionId, resolvedAgentId, clientRef, appendLive])

  /** msgs + 实时增量（uuid 去重）。refetch 与增量重叠时以先到者为准，内容一致。 */
  const merged = useMemo(() => {
    const base = msgs ?? []
    if (live.length === 0) return base
    const seen = new Set(base.map((m) => m.uuid).filter(Boolean) as string[])
    const extra = live.filter((m) => !m.uuid || !seen.has(m.uuid))
    return extra.length > 0 ? [...base, ...extra] : base
  }, [msgs, live])

  /**
   * transcript → 主对话消息（工具结果按 toolCallId 归到对应 tool_use，供 ToolCard 的 OUT 区）。
   * 缺失字段一律 null/undefined，让 `Message` 走「无数据不显示」分支，不编造。
   */
  const chatMessages = useMemo<ChatMessageDto[]>(() => {
    const toolResults = new Map<string, string>()
    for (const m of merged) {
      if (m.role === 'tool' && m.toolCallId && m.content) toolResults.set(m.toolCallId, m.content)
    }
    const out: ChatMessageDto[] = []
    merged.forEach((m, i) => {
      // tool 行不单独成条：结果已并入对应工具卡的 OUT 区（无主的 tool 行也没有展示价值）
      if (m.role === 'tool') return
      // system 行（压缩边界等转录内部产物）在 REST 出站里**丢了 subtype**，
      // 渲染出来只会变成一条挂着 nexus 头的裸正文 ⇒ 子代理视图不展示。
      // ⚠️ 系统注入行是 role=user（不是 system），不在此列 —— 它们由 isSystemInjectedRow
      //    统一收进该轮折叠区（见下方 author/isMeta/subtype 透传）。
      if (m.role === 'system') return
      out.push(toChatMessage(m, i, agentId, toolResults))
    })
    return out
  }, [merged, agentId])

  const send = async () => {
    const prompt = draft.trim()
    if (!prompt || !sessionId) return
    setSending(true)
    try {
      await commandApi.executeBuiltin('resume', sessionId, { agentId, prompt })
      setDraft('')
      showToast(`已恢复 ${name}，继续执行`, 'success')
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    } finally {
      setSending(false)
    }
  }

  return (
    <div className="agent-transcript">
      <div className="atv-head">
        <button className="atv-back" onClick={onBack} title="返回任务列表" aria-label="返回">‹</button>
        <div className="atv-title">
          <div className="atv-name">{name}</div>
          {subtitle && (
            <div className="atv-meta">
              <span className={`td-badge ${running ? 'in_progress' : stopped ? 'stopped' : 'completed'}`}>
                {running ? '运行中' : stopped ? '已停止' : '已完成'}
              </span>
              <span className="td-id">{subtitle}</span>
            </div>
          )}
        </div>
        {onExpand && (
          <button
            className={`atv-expand${expanded ? ' on' : ''}`}
            onClick={onExpand}
            title={expanded ? '还原宽度' : '扩大（向左覆盖对话区）'}
            aria-label={expanded ? '还原宽度' : '扩大'}
          >
            {expanded ? '⤡' : '⤢'}
          </button>
        )}
      </div>

      <div className="atv-body">
        {error ? (
          <div className="atv-empty">{error}</div>
        ) : msgs === null ? (
          <div className="atv-empty">加载中…</div>
        ) : chatMessages.length === 0 ? (
          <div className="atv-empty">暂无执行记录</div>
        ) : (
          // 与中间对话区**同一个**组件：轮次分组 / 一轮一个 nexus 头 / 工具组 / 中间过程折叠 /
          // 进行中状态行全部继承。sessionId 传 agentId —— 让 store 里主会话的流式块与裁剪标记
          // 不会串进子代理视图（agentId 永不等于真实会话 id）。
          <MessageList
            sessionId={agentId}
            messages={chatMessages}
            onDelete={noop}
            thinking={running}
          />
        )}
      </div>

      {!running && (
        <div className="atv-compose">
          <input
            className="atv-input"
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); void send() } }}
            placeholder="继续让它做点什么…（发送后恢复运行）"
            disabled={sending}
          />
          <button className="atv-send" onClick={() => void send()} disabled={sending || !draft.trim()}>
            {sending ? '…' : '➤'}
          </button>
        </div>
      )}
    </div>
  )
}

/** 子代理记录只读，删除入口不可用（MessageList 的 onDelete 是必填 prop） */
const noop = () => { /* 不可删 */ }

/**
 * 把子代理 transcript 消息适配成主对话的 `ChatMessageDto`，以便**直接复用** `MessageList`
 * （思考块 / 上下文 / 工具卡片 / 折叠规则全部继承主对话，不另写一套渲染）。
 *
 * <p>缺失字段的取值原则：transcript 没有 token 统计/耗时/权限规则 → 一律 null，
 * 让渲染侧走「无数据不显示」的分支，不编造。
 *
 * <p>`author` 留 null = 消息头显示 `nexus`（与主对话一致，用户要求「按主会话的方式渲染」）；
 * `createdAt` 取 transcript 的 timestamp，状态行才有「已完成 · 时长」、消息头才有时间。
 */
function toChatMessage(
  m: AgentTranscriptMessage,
  index: number,
  agentId: string,
  toolResults: Map<string, string>,
): ChatMessageDto {
  return {
    id: m.uuid ?? `transcript-${index}`,
    sessionId: agentId,
    role: m.role as ChatMessageDto['role'],
    // [2026-11-02] author/isMeta/subtype 必须透传：否则右栏把技能清单这种系统注入行
    //   当成「用户提问」渲染成气泡（用户实测的原始问题）。
    author: m.author ?? null,
    isMeta: m.isMeta === true,
    subtype: m.subtype ?? null,
    content: m.content ?? null,
    reasoning: m.reasoning ?? null,
    toolCalls: (m.toolCalls ?? []).map((t) => ({
      id: t.id,
      name: t.name,
      arguments: t.arguments,
      result: toolResults.get(t.id) ?? null,
      isError: false,
    })) as ChatMessageDto['toolCalls'],
    finishReason: null,
    inputTokens: null,
    outputTokens: null,
    reasoningDurationMs: null,
    time: null,
    createdAt: m.timestamp ?? undefined,
    toolCallId: m.toolCallId,
    assistantMessageId: null,
    isApiErrorMessage: m.isApiError,
    apiError: null,
    error: null,
  } as ChatMessageDto
}
