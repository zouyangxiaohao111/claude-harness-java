import { useChatStore } from '@/stores/chatStore'

/** hooks_start 阶段文案（可选副提示 · 对齐 CC REPL spinner：Running PreCompact hooks… 等） */
const HOOK_LABEL: Record<string, string> = {
  pre_compact: '正在运行压缩前钩子…',
  post_compact: '正在收尾压缩…',
  session_start: '正在启动新会话…',
}

/**
 * 上下文压缩进度横幅（输入框上方 · STOMP compact-progress 事件驱动 · 贴合 TokenWarningBanner 暖橙族）。
 *
 * <p>状态（chatStore.compact，useChatSocket 归一）：<ul>
 *   <li>compact_start → running，pct 起步 8%；</li>
 *   <li>compact_progress{chars} → 摘要流式推进（封顶 90%）；hooks_start 切阶段文案；</li>
 *   <li>compact_end → done（100% 绿，短暂后由 useChatSocket 定时隐藏）；</li>
 *   <li>canceled → 手动停止（Composer 发送键压缩中变停止 / Esc，见 Composer）。</li>
 * </ul>
 * 停止能力共用输入框<b>发送键</b>（压缩中发送键变停止方块）——本横幅不含独立停止钮。
 */
export function CompactProgressBar() {
  const compact = useChatStore((s) => s.compact)
  if (!compact.visible) return null

  const isDone = compact.status === 'done'
  const isCancel = compact.status === 'canceled'
  const label = isDone ? '压缩完成'
    : isCancel ? '压缩已取消'
      : compact.hookType ? (HOOK_LABEL[compact.hookType] ?? '正在压缩上下文…')
        : '正在压缩上下文…'
  const pctText = isCancel ? '—' : `${Math.round(compact.pct)}%`

  return (
    <div className="retry-wrap">
      <div className={`cp-banner${isDone || isCancel ? ' cp-done' : ''}`} role="status" aria-live="polite">
        <div className="cp-status">
          <span className={`cp-icon${isDone ? ' done' : ''}${isCancel ? ' cancel' : ''}`}>
            <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5" width="13" height="13">
              <path d="M2 4.5C2 3.5 2.5 3 3.5 3H5L6 4.5H10.5C11.5 4.5 12 5 12 6V10C12 11 11.5 11.5 10.5 11.5H3.5C2.5 11.5 2 11 2 9.5V4.5Z" />
              <path d="M5 8L3 10M5 8H2.8M5 8V10.2" strokeLinecap="round" />
            </svg>
          </span>
          <span className={`cp-text${isDone ? ' done' : ''}${isCancel ? ' cancel' : ''}`}>{label}</span>
          <span className="cp-pct">{pctText}</span>
        </div>
        <div className="cp-track">
          <div className={`cp-fill${isDone ? ' done' : ''}${isCancel ? ' cancel' : ''}`} style={{ width: `${compact.pct}%` }} />
        </div>
      </div>
    </div>
  )
}
