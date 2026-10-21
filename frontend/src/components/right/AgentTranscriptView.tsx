import { useEffect, useState } from 'react'
import { subagentApi } from '@/api/subagents'
import { commandApi } from '@/api/command'
import { ApiError } from '@/api/rest'
import type { AgentTranscriptMessage, ChatMessageDto } from '@/api/types'
import { Message } from '@/components/center/MessageList'

/**
 * 右栏「agent 对话视图」（2026-10-21 用户裁定 F2）。
 *
 * <p>点子代理 / TEAM 成员 → **右栏原地切换**成该 agent 的执行记录（不是弹窗），
 * 顶部「‹ 返回」回到任务列表。消息渲染与中间对话区保持同一套语义（角色 + 正文 + 工具调用块）。
 *
 * <p>已停止的 agent 底部会出现输入框：发送即走内置 `/resume`（对齐 CC
 *「给已停止的 agent 发消息 = 自动 resume」，`SendMessage` → `resumeAgentBackground`）。
 */
export function AgentTranscriptView({ sessionId, agentId, name, subtitle, active, onBack, onExpand, expanded = false, showToast }: {
  sessionId: string | null
  agentId: string
  name: string
  /** 副标题：agent 类型 / 「已完成」，显示在名字下方 */
  subtitle?: string
  /** 仍在运行 → 底部只读；false/undefined → 允许输入并恢复 */
  active?: boolean
  onBack: () => void
  /** ⤢ 扩大：右栏向左覆盖中间区（不是弹窗） */
  onExpand?: () => void
  /** 当前是否已扩大（按钮图标切换） */
  expanded?: boolean
  showToast: (msg: string, type?: 'success' | 'info') => void
}) {
  const [msgs, setMsgs] = useState<AgentTranscriptMessage[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [draft, setDraft] = useState('')
  const [sending, setSending] = useState(false)

  // 拉取该 agent 的完整执行记录（transcript 接口 · 三键查找含历史 a+16hex / taskId）
  useEffect(() => {
    if (!sessionId) return
    let cancelled = false
    setMsgs(null)
    setError(null)
    subagentApi.transcript(sessionId, agentId)
      .then((list) => { if (!cancelled) setMsgs(list) })
      .catch((e) => { if (!cancelled) setError(e instanceof ApiError ? e.userMessage() : String(e)) })
    return () => { cancelled = true }
  }, [sessionId, agentId])

  // 已停止 = 红色（用户 2026-10-21）；subtitle 由调用方给出
  const stopped = !active && (subtitle ?? '').includes('已停止')
  // 工具结果映射：role='tool' 的消息按 toolCallId 归到对应 tool_use（供 ToolCard 的 OUT 区）
  const toolResults = new Map<string, string>()
  for (const m of msgs ?? []) {
    if (m.role === 'tool' && m.toolCallId && m.content) {
      toolResults.set(m.toolCallId, m.content)
    }
  }

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
              <span className={`td-badge ${active ? 'in_progress' : stopped ? 'stopped' : 'completed'}`}>
                {active ? '运行中' : stopped ? '已停止' : '已完成'}
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
        ) : msgs.length === 0 ? (
          <div className="atv-empty">暂无执行记录</div>
        ) : (
          msgs.map((m, i) => (
            // role='tool' 且能对上 tool_use 的 → 已并入工具块的 OUT 区，不单独成条
            (m.role === 'tool' && m.toolCallId && toolResults.has(m.toolCallId)) ? null : (
              <Message
                key={m.uuid ?? `m-${i}`}
                msg={toChatMessage(m, i, sessionId ?? '', toolResults)}
                onDelete={() => { /* 子代理记录只读，不可删 */ }}
              />
            )
          ))
        )}
      </div>

      {!active && (
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

/**
 * 把子代理 transcript 消息适配成主对话的 `ChatMessageDto`，以便**直接复用** `Message` 组件
 * （思考块 / 上下文 / 工具卡片 / 折叠规则全部继承主对话，不另写一套渲染）。
 *
 * <p>缺失字段的取值原则：transcript 没有 token 统计/耗时/权限规则 → 一律 null，
 * 让 `Message` 走「无数据不显示」的分支，不编造。
 */
function toChatMessage(
  m: AgentTranscriptMessage,
  index: number,
  sessionId: string,
  toolResults: Map<string, string>,
): ChatMessageDto {
  return {
    id: m.uuid ?? `transcript-${index}`,
    sessionId,
    role: m.role as ChatMessageDto['role'],
    author: m.agentId ?? null,
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
    toolCallId: m.toolCallId,
    assistantMessageId: null,
    subtype: null,
    isMeta: false,
    isApiErrorMessage: m.isApiError,
    apiError: null,
    error: null,
  } as ChatMessageDto
}
