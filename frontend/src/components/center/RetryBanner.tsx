import { useEffect, useState } from 'react'

interface Props {
  attempt?: number
  maxRetries?: number
  /** 本轮重试等待时长（后端 api_retry 事件 retryDelayMs；缺省不显示倒计时） */
  retryDelayMs?: number
  onClose: () => void
}

export function RetryBanner({ attempt, maxRetries, retryDelayMs, onClose }: Props) {
  const [remaining, setRemaining] = useState<number>(retryDelayMs ?? 0)

  useEffect(() => {
    setRemaining(retryDelayMs ?? 0)
    if (!retryDelayMs) return
    const start = Date.now()
    const timer = setInterval(() => {
      const left = retryDelayMs - (Date.now() - start)
      setRemaining(Math.max(0, left))
      if (left <= 0) clearInterval(timer)
    }, 100)
    return () => clearInterval(timer)
  }, [retryDelayMs])

  const secs = (remaining / 1000).toFixed(1)
  const hasCountdown = retryDelayMs != null && remaining > 0

  return (
    <div className="retry-banner" role="status">
      <span className="retry-spinner" aria-hidden="true" />
      <span className="retry-label">正在重试</span>
      {/* 用后端真实 attempt/maxRetries，不做本地默认值兜底（缺则整段不显示） */}
      {attempt != null && maxRetries != null && (
        <span className="retry-meta">第 {attempt} / {maxRetries} 次</span>
      )}
      {hasCountdown && <span className="retry-countdown">{secs}s 后重试</span>}
      <button className="retry-close" onClick={onClose} title="取消重试" aria-label="取消重试">
        <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: 10, height: 10 }}>
          <path d="M2 2L10 10M10 2L2 10" />
        </svg>
      </button>
    </div>
  )
}
