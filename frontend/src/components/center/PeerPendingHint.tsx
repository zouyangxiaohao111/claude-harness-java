import { useState } from 'react'

/**
 * 输入框上方的「待处理投递」常驻条。
 *
 * <h2>WHY（口径）</h2>
 * <p>未读 ≠ 待处理：用户点开待收件面板后 seenAt 会被回填（未读归零），但消息仍未处理。
 * 所以本条的判据是 **pendingCount**（status==='pending'），只有接受或拒绝才会让它消失。
 * pendingCount=0 时组件整条不渲染（由调用方不传/不挂载保证）。
 *
 * <h2>交互</h2>
 * <ul>
 *   <li>「去处理」= 打开收件箱（App 的 openInbox；⛔ 不切会话）。</li>
 *   <li>「全部拒绝」= 二次确认后批量结算；确认文案必须写明**不可恢复**且发送方会收到结果。</li>
 * </ul>
 */
export function PeerPendingHint({
  count,
  onOpen,
  onRejectAll,
  acting = false,
  titles = [],
}: {
  count: number
  onOpen: () => void
  onRejectAll: () => void
  acting?: boolean
  /** 待处理项的来源标题（去重；用户裁定：常驻条必须显示《标题》）。 */
  titles?: string[]
}) {
  const [confirming, setConfirming] = useState(false)

  return (
    <div className="peer-pending-hint" role="status" aria-live="polite">
      <span className="peer-pending-dot" aria-hidden="true" />
      <span className="peer-pending-text">有 {count} 条待确认消息，处理后才能发送</span>
      {titles.length > 0 && (
        <span className="peer-pending-titles" title={titles.join('、')}>
          {titles.length === 1
            ? `来自《${titles[0]}》`
            : `来自《${titles[0]}》等 ${titles.length} 个会话`}
        </span>
      )}
      <button className="peer-pending-go" type="button" onClick={onOpen}>去处理</button>
      {confirming ? (
        <span className="peer-pending-confirm">
          <span className="peer-pending-confirm-text">拒绝后不可恢复，发送方会收到结果</span>
          <button
            className="peer-pending-reject-confirm"
            type="button"
            disabled={acting}
            onClick={() => { setConfirming(false); onRejectAll() }}
          >
            {acting ? '处理中…' : '确认全部拒绝'}
          </button>
          <button className="peer-pending-cancel" type="button" onClick={() => setConfirming(false)}>取消</button>
        </span>
      ) : (
        <button
          className="peer-pending-reject"
          type="button"
          disabled={acting}
          onClick={() => setConfirming(true)}
        >
          {acting ? '处理中…' : '全部拒绝'}
        </button>
      )}
    </div>
  )
}
