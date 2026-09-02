import { useChatStore } from '@/stores/chatStore'

/**
 * 压缩警告抑制态横幅 · 对齐 CC TokenWarning（上下文快满 / 自动压缩被抑制时提示）。
 * 消费后端 token_warning 事件：suppressed=true（压缩成功）→ 隐藏；false → 恢复显示。
 * percentLeft 可选，缺省用 tokenUsage/contextWindow 计算剩余百分比。
 */
export function TokenWarningBanner() {
  const warning = useChatStore((s) => s.tokenWarning)
  if (!warning || warning.suppressed) return null

  const pct = warning.percentLeft != null
    ? warning.percentLeft
    : warning.tokenUsage != null && warning.contextWindow
      ? Math.round((1 - warning.tokenUsage / warning.contextWindow) * 100)
      : null
  const text = pct != null
    ? `上下文剩余 ${pct}% · 接近自动压缩`
    : '上下文接近自动压缩窗口'

  return (
    // 复用 retry-wrap：与输入框同宽（max-width 760 居中 + padding 32）左对齐
    <div className="retry-wrap">
      <div className="tw-banner" role="status">
        <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: 13, height: 13 }}>
          <path d="M7 1.5L8.5 4.5L11.5 5L8.5 6.5L7 9.5L5.5 6.5L2.5 5L5.5 4.5L7 1.5Z" />
        </svg>
        <span className="tw-text">{text}</span>
        <button className="tw-close" onClick={() => useChatStore.getState().setTokenWarning(null)} aria-label="关闭">
          <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: 10, height: 10 }}>
            <path d="M2 2L10 10M10 2L2 10" />
          </svg>
        </button>
      </div>
    </div>
  )
}
