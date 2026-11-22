/**
 * 输入区「暂不能发送」常驻提示条。
 *
 * <p>视觉复用 `.peer-pending-hint`（同色系警示条），判据由 {@link useComposerBlock} 统一给出：
 * <ul>
 *   <li>待确认消息 > 0 ⇒ 「有 N 条待确认消息，处理后才能发送」；</li>
 *   <li>未处理审批 > 0 ⇒ 「等待审批结果，暂不能发送」。</li>
 * </ul>
 * 两条可同时成立（各自一行）。「去处理」只定位/打开对应处理面 —— ⛔ 不切会话、不拉历史、
 * 不动当前输入内容。
 *
 * <p>⛔ 不使用遮罩：封锁的只是发送，面板/滚动/审批卡必须仍然可操作。
 */
export function ComposerBlockHint({
  peerCount,
  approvalCount,
  onOpenInbox,
  onRevealApproval,
}: {
  peerCount: number
  approvalCount: number
  /** 打开待收件面板（App 传入 = peerInboxStore.openPanel，只展开面板、不切会话）。 */
  onOpenInbox?: () => void
  /** 定位到审批卡片（App 传入 = 触发对话区滚到底部，露出 PermissionBubble / 内联计划审批）。 */
  onRevealApproval?: () => void
}) {
  if (peerCount <= 0 && approvalCount <= 0) return null
  return (
    <div className="peer-pending-hint composer-block-hint" role="status" aria-live="polite">
      {peerCount > 0 && (
        <span className="composer-block-line">
          <span className="peer-pending-dot" aria-hidden="true" />
          <span className="peer-pending-text">有 {peerCount} 条待确认消息，处理后才能发送</span>
          {onOpenInbox && (
            <button className="peer-pending-go" type="button" onClick={onOpenInbox}>去处理</button>
          )}
        </span>
      )}
      {approvalCount > 0 && (
        <span className="composer-block-line">
          <span className="peer-pending-dot" aria-hidden="true" />
          <span className="peer-pending-text">等待审批结果，暂不能发送</span>
          {onRevealApproval && (
            <button className="peer-pending-go" type="button" onClick={onRevealApproval}>去处理</button>
          )}
        </span>
      )}
    </div>
  )
}
