import { useChatStore } from '@/stores/chatStore'
import type { ChatMessageDto } from '@/api/types'
import type { Project, Session } from '@/types'
import { tabInfoMap } from '@/data'
import { compactNumber } from '@/utils/format'

interface StreamHeaderProps {
  centerTabId: string
  sessions: Session[]
  mainProject: Project
}

/** 状态点元信息：文案 + 圆点颜色（idle 停用 pulse，避免「就绪」还跳动误导）。 */
const STATUS_META = {
  thinking: { text: '思考中', color: 'var(--warning)' },
  streaming: { text: '对话进行中', color: 'var(--running)' },
  idle: { text: '就绪', color: 'var(--ink-faint)' },
} as const

/** 最近一次 assistant 消息的 outputTokens（message.complete 落库累计值）；无则 null。 */
function lastOutputTokens(msgs: ChatMessageDto[] | undefined): number | null {
  if (!msgs) return null
  for (let i = msgs.length - 1; i >= 0; i--) {
    const m = msgs[i]
    if (m.role === 'assistant' && m.outputTokens != null && m.outputTokens > 0) return m.outputTokens
  }
  return null
}

export function StreamHeader({ centerTabId, sessions, mainProject }: StreamHeaderProps) {
  const agentStatus = useChatStore((s) => s.agentStatus)
  const status = STATUS_META[agentStatus]
  // F37 · 用量展示：流式期间 streams 状态不携带 outputTokens，退而读 message.complete 落库的最近一次值；无数据静默
  const retry = useChatStore((s) => s.retry)
  const centerMessages = useChatStore((s) => (centerTabId ? s.messages[centerTabId] : undefined))
  const lastTokens = lastOutputTokens(centerMessages)
  const title = sessions.find((s) => s.id === centerTabId)?.title || '当前会话'
  const info = tabInfoMap[centerTabId]
  return (
    <div className="stream-header">
      <div className="stream-header-icon">
        <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5">
          <path d="M2 4.5C2 3.5 2.5 3 3.5 3H5L6 4.5H10.5C11.5 4.5 12 5 12 6V10C12 11 11.5 11.5 10.5 11.5H3.5C2.5 11.5 2 11 10V4.5Z" />
        </svg>
      </div>
      <div className="stream-header-info">
        <div className="stream-header-title">{title}</div>
        <div className="stream-header-meta">
          <span>{info?.icon || '◇'}</span>
          <span>{info?.subtitle || 'NexusAI 会话'}</span>
          <span style={{ color: 'var(--ink-faint)' }}>·</span>
          <span>主项目: {mainProject.name}</span>
        </div>
      </div>
      <div className="stream-header-status">
        <span className="stream-header-status-dot" style={{ background: status.color, ...(agentStatus === 'idle' ? { animation: 'none' } : {}) }}></span>
        <span>{status.text}</span>
        {retry && (
          <span className="stream-header-status-meta" style={{ color: 'var(--warning)' }}>已重试 {retry.attempt}/{retry.maxRetries}</span>
        )}
        {lastTokens != null && (
          <span className="stream-header-status-meta">tokens {compactNumber(lastTokens)}</span>
        )}
      </div>
    </div>
  )
}
