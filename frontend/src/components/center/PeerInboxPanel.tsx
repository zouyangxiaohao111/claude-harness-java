import type { Ref } from 'react'
import type { PeerHeldItemDto } from '@/api/types'
import { isPendingHeldItem, peerHeldSourceId, peerHeldSummary, peerHeldTitle } from '@/stores/peerInboxStore'

/**
 * 跨会话收件箱（App 持有数据，WorkspaceHeader 只负责摆放）。
 *
 * <h2>分工</h2>
 * <ul>
 *   <li>收件箱 = **快捷动作面**：一行一封信（来源 + 目标会话 + 摘要 + 时间 + 接受/拒绝）；</li>
 *   <li>内联卡 = **唯一正文面**：完整正文只在所属会话的对话流里出现一次。</li>
 * </ul>
 * ⛔ 收件箱不渲染正文、不做自动展开；点行动作区以外的整行 = 跳目标会话并定位那条卡。
 */
export interface PeerInboxPanelState {
  open: boolean
  items: PeerHeldItemDto[]
  /** 会话 id → 展示标题（目标会话名必须显示，否则全局列表分不清归属）。 */
  sessionTitles: Record<string, string>
  loading: boolean
  error: string | null
  notice: string | null
  /** 行内失败原因：messageId → 中文文案（行保留、可重试）。 */
  rowErrors?: Record<string, string>
  /** 动作进行中的 messageId（期间全部动作禁用）。 */
  acting?: string | null
  onClose: () => void
  onRetry: () => void
  onAccept: (messageId: string) => void
  onReject: (messageId: string) => void
  /** 点主区（非动作区）→ 跳目标会话 + 定位该 messageId 的内联卡。 */
  onOpenSession: (targetSessionId: string, messageId: string) => void
}

/** heldAt → 本地「月/日 时:分」；非法时间静默返回空串。 */
function timeOf(iso: string | null | undefined): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  return d.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

export function PeerInboxPanel({
  open,
  items,
  sessionTitles,
  loading,
  error,
  notice,
  rowErrors,
  acting,
  onClose,
  onRetry,
  onAccept,
  onReject,
  onOpenSession,
  panelRef,
}: PeerInboxPanelState & { panelRef?: Ref<HTMLDivElement> }) {
  if (!open) return null
  const busy = acting != null
  const pending = items.filter((item) => isPendingHeldItem(item))

  return (
    <div ref={panelRef} className="ws-peer-panel inbox" role="dialog" aria-label="收件箱">
      <div className="ws-hp-head">
        收件箱
        <span className="ws-hp-count">{pending.length}</span>
        <button className="ws-hp-close" type="button" title="关闭" aria-label="关闭" onClick={onClose}>×</button>
      </div>
      <div className="inbox-sub">按最早优先 · 点行进目标会话审批</div>

      {notice && <div className="inbox-notice">{notice}</div>}

      {loading && pending.length === 0 ? (
        <div className="inbox-state">加载中…</div>
      ) : error && pending.length === 0 ? (
        <div className="inbox-state">
          <b>收件箱加载失败</b>
          <span>{error}</span>
          <div style={{ marginTop: 10 }}>
            <button type="button" className="inbox-reject" onClick={onRetry}>重试</button>
          </div>
        </div>
      ) : pending.length === 0 ? (
        <div className="inbox-state">
          <b>收件箱已清空</b>
          没有待处理的跨会话来信
        </div>
      ) : (
        <div className="inbox-list">
          {pending.map((item) => {
            const target = (item.targetSessionId ?? '').trim()
            const targetTitle = sessionTitles[target] ?? target
            const rowError = rowErrors?.[item.messageId]
            return (
              <div className="inbox-row" key={item.messageId} data-item={item.messageId}>
                <button
                  type="button"
                  className="inbox-main"
                  title={target ? `跳到《${targetTitle}》并定位这条卡` : '目标会话未知'}
                  onClick={() => { if (target) onOpenSession(target, item.messageId) }}
                >
                  <span className="inbox-top">
                    <span className="inbox-source">《{peerHeldTitle(item)}》</span>
                    {peerHeldSourceId(item) && <span className="inbox-src-id">{peerHeldSourceId(item)}</span>}
                    <span className="inbox-time">{timeOf(item.heldAt)}</span>
                  </span>
                  <span className="inbox-target"><span className="arrow">→</span>{targetTitle || '目标会话未知'}</span>
                  <span className="inbox-summary">{peerHeldSummary(item)}</span>
                </button>
                <div className="inbox-actions">
                  <button
                    className="inbox-reject"
                    type="button"
                    disabled={busy}
                    onClick={() => onReject(item.messageId)}
                  >
                    拒绝
                  </button>
                  <button
                    className="inbox-accept"
                    type="button"
                    disabled={busy}
                    onClick={() => onAccept(item.messageId)}
                  >
                    {acting === item.messageId ? '处理中…' : '接受一次'}
                  </button>
                </div>
                {rowError && <div className="inbox-row-error" role="status">{rowError}</div>}
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}
