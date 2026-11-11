import { useMemo } from 'react'
import { useChatStore } from '@/stores/chatStore'
import { askQuestionSummary, isAskUserQuestion } from '@/utils/askUserNotice'

/**
 * 右下角「有工具在等你回答」浮层。
 *
 * <h2>WHY 需要它</h2>
 * 提问请求（AskUserQuestion）会**阻塞当前轮次** —— 后端在 {@code future.get()} 上无限等待，
 * 用户不回答，这一轮就永远不结束。但提问卡片渲染在**对话流里**（{@code PermissionBubble}），
 * 存在两个看不见的场景：
 * <ul>
 *   <li><b>会话不是当前会话</b>：提问在后台会话里等着，用户在另一个会话 ⇒ 对话流里根本没这张卡片，
 *       只有侧栏一个黄点（App.tsx 的 pendingSessionIds）。用户不看侧栏就完全不知道；</li>
 *   <li><b>当前会话但消息很长</b>：卡片在对话流末尾，用户可能滚不到那儿。</li>
 * </ul>
 * ⇒ 右下角固定一个浮层，任何会话的提问一到达就能看见，点一下直达。
 *
 * <h2>显示范围</h2>
 * 只显示**非当前会话**的提问：当前会话的提问已经在对话流里弹了卡片（{@code PermissionBubble}），
 * 再在右下角提示一遍是重复噪音。
 *
 * <p>⚠️ 「非当前会话」由**渲染时**的 activeSessionId 判定 ⇒ 用户切到该会话的那一刻，
 * 浮层自动消失（会话已切过去 ⇒ 卡片已经在对话流里可见）。
 *
 * <h2>位置</h2>
 * 右下角，但**不能与更新按钮重叠** —— 更新中心的悬浮按钮 {@code .ud-fab} 固定在
 * {@code right:18px; bottom:18px}（globals.css）。本浮层整体上移错开。
 */
export function AskUserPendingHint({
  activeSessionId,
  onOpenSession,
}: {
  /** 当前会话 id（由 App 传入 —— 它来自 session reducer，不在 chatStore 里）。 */
  activeSessionId: string
  onOpenSession: (sessionId: string) => void
}) {
  const permissionQueue = useChatStore((s) => s.permissionQueue)
  const sessions = useChatStore((s) => s.sessions)

  /** 非当前会话的提问请求（同一会话多条 ⇒ 只取一条，避免堆叠）。 */
  const pending = useMemo(() => {
    const seen = new Set<string>()
    const out: { sessionId: string; requestId: string; summary: string | null }[] = []
    for (const r of permissionQueue) {
      if (r.sessionId === activeSessionId) continue
      if (!isAskUserQuestion(r.toolInput)) continue
      if (seen.has(r.sessionId)) continue
      seen.add(r.sessionId)
      out.push({ sessionId: r.sessionId, requestId: r.requestId, summary: askQuestionSummary(r.toolInput) })
    }
    return out
  }, [permissionQueue, activeSessionId])

  if (pending.length === 0) return null

  return (
    <div className="ask-pending-hint">
      {pending.map((p) => {
        const title = sessions.find((s) => s.id === p.sessionId)?.title?.trim()
        return (
          <button
            key={p.requestId}
            className="ask-pending-hint-item"
            title={p.summary ?? '有工具在等你回答'}
            onClick={() => onOpenSession(p.sessionId)}
          >
            <span className="aph-dot" aria-hidden="true" />
            <span className="aph-text">
              <b>需要你回答</b>
              <span className="aph-session">{title || '未命名会话'}</span>
            </span>
          </button>
        )
      })}
    </div>
  )
}
