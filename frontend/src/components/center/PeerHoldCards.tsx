import { useState } from 'react'
import type { PeerHeldItemDto } from '@/api/types'
import {
  peerAdmissionBadge,
  peerHeldReasonText,
  peerHeldScaleText,
  peerHeldSourceId,
  peerHeldStatusView,
  peerHeldTitle,
  peerModeText,
} from '@/stores/peerInboxStore'

/**
 * 待确认（hold）来信 · **对话流内联卡片**。
 *
 * <h2>WHY（数据来源）</h2>
 * <p>hold 消息**不进 messages 表**（用户 2026-11-20 改判）：`peer_held_message` 本身已持久化，
 * 所以本组卡片的数据必须来自**待收件列表接口**（App 把 store 里那份 `items` 传进来），
 * ⛔ 不从消息行读 `heldState` —— 那个字段不写、读它必然「刷新就消失」。
 *
 * <h2>状态映射（唯一口径见 {@link peerHeldStatusView}）</h2>
 * <ul>
 *   <li>{@code pending} → 待确认卡：虚线/警示边 + 拒绝 / 投递并继续，处理前一直未投递；</li>
 *   <li>{@code approved} → 迁移前历史中间态：只读「已批准（待迁移）」，⛔ 无动作；</li>
 *   <li>{@code accepted / dropped / rejected / declined / refused / expired} → 终态只读卡
 *       （实线弱化）：可查看、不可再投递。</li>
 * </ul>
 *
 * <h2>安全</h2>
 * <p>标题/正文/来源全部是**他人数据**：只用文本节点渲染，⛔ 不跑 Markdown、不注入 HTML。
 * 标题点击 = 唯一跳转入口（键盘可达）；⛔ 渲染/自动行为一律不切会话。
 */
export function PeerHoldCards({
  items,
  acting,
  rowErrors,
  onAccept,
  onReject,
  onJump,
  focusedId,
}: {
  items: PeerHeldItemDto[]
  acting?: string | null
  rowErrors?: Record<string, string>
  onAccept: (messageId: string) => void
  onReject: (messageId: string) => void
  /** 标题显式点击 → App 校验来源会话是否存在后跳转（缺失时提示、不跳）。 */
  onJump?: (sourceSessionId: string) => void
  /** 跳转后短暂高亮的卡片 id（1.5s 后由 MessageList 清空）。 */
  focusedId?: string | null
}) {
  const [openIds, setOpenIds] = useState<Record<string, boolean>>({})
  if (items.length === 0) return null
  const busy = acting != null

  return (
    <>
      {items.map((item) => {
        const view = peerHeldStatusView(item)
        const readOnly = !view.actionable
        const title = peerHeldTitle(item)
        const srcId = peerHeldSourceId(item)
        const mode = peerModeText(item.fromMode)
        const badge = peerAdmissionBadge(item.admission, item.admissionReason)
        const body = item.body ?? ''
        const scale = peerHeldScaleText(item)
        const long = item.bodyLines != null ? item.bodyLines > 12 : peerTextIsLong(body)
        const expanded = openIds[item.messageId] === true
        const rowError = rowErrors?.[item.messageId]
        return (
          <div
            key={item.messageId}
            className={`msg peer-hold${view.tone ? ` ${view.tone}` : ''}${focusedId === item.messageId ? ' ph-focus' : ''}`}
            data-peer-message-id={item.messageId}
          >
            <div className="ph-card">
              <div className="ph-head">
                <span className="ph-lead">来自</span>
                {srcId && onJump ? (
                  <button
                    type="button"
                    className="ph-title ph-jump"
                    title={`打开来源会话 ${srcId}`}
                    onClick={() => onJump(srcId)}
                  >
                    《{title}》
                  </button>
                ) : (
                  <span className="ph-title">《{title}》</span>
                )}
                {/* 标题是他人数据 ⇒ 来源 id 必须同屏（同名标题靠它区分） */}
                {srcId ? <span className="ph-src" title={`来源会话 ${srcId}`}>{srcId}</span> : null}
                <span className="ph-flag">{view.label}</span>
                {readOnly ? null : <span className="ph-reason">{peerHeldReasonText(item.reason)}</span>}
              </div>
              <div className="ph-badges">
                <span className="ph-mode" title="发送方声明的权限模式（只显示声明值，不继承）">{mode}</span>
                <span className={`ph-admission ${badge.kind}`}>{badge.label}</span>
                {scale ? <span className="ph-scale">{scale}</span> : null}
                {item.heldAt ? <span className="ph-time">{formatHeldTime(item.heldAt)}</span> : null}
              </div>
              {body ? (
                <>
                  <div className={`ph-body${long && !expanded ? ' clamped' : ''}`}>{body}</div>
                  {long ? (
                    <button
                      type="button"
                      className="ph-more"
                      aria-expanded={expanded}
                      onClick={() => setOpenIds((p) => ({ ...p, [item.messageId]: !expanded }))}
                    >
                      {expanded ? '收起' : '展开全文'}
                    </button>
                  ) : null}
                </>
              ) : (
                <div className="ph-body ph-empty">（无正文）</div>
              )}
              {readOnly ? null : (
                <div className="ph-actions">
                  <button
                    type="button"
                    className="ph-reject"
                    disabled={busy}
                    onClick={() => onReject(item.messageId)}
                  >
                    {acting === item.messageId ? '处理中…' : '拒绝'}
                  </button>
                  <button
                    type="button"
                    className="ph-accept"
                    disabled={busy}
                    onClick={() => onAccept(item.messageId)}
                  >
                    {acting === item.messageId ? '处理中…' : '投递并继续'}
                  </button>
                </div>
              )}
              {rowError ? (
                <div className="ph-error" role="status">{rowError}</div>
              ) : null}
            </div>
          </div>
        )
      })}
    </>
  )
}

/** 长文判据：> 12 行或 > 720 字（与已投递卡片的 12 行钳制同口径）。 */
function peerTextIsLong(text: string): boolean {
  if (text === '') return false
  let lines = 1
  for (let i = 0; i < text.length; i++) if (text.charCodeAt(i) === 10) lines++
  return lines > 12 || text.length > 12 * 60
}

/** heldAt → 本地「月/日 时:分」；非法时间返回空串。 */
function formatHeldTime(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  return d.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}
