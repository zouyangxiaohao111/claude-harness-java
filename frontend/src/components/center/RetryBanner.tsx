import { useEffect, useState } from 'react'

interface Props {
  attempt?: number
  maxRetries?: number
  /** 本轮重试等待时长（后端 api_retry 事件 retryDelayMs；缺省不显示倒计时） */
  retryDelayMs?: number
  /** 客户端收到该重试态的时刻（ms）→ 无倒计时（retryDelayMs 为 0/缺省）时改显示「已等待」正计时 */
  at?: number
  onClose: () => void
}

export function RetryBanner({ attempt, maxRetries, retryDelayMs, at, onClose }: Props) {
  const [remaining, setRemaining] = useState<number>(retryDelayMs ?? 0)
  const [waited, setWaited] = useState<number>(0)
  // 倒计时模式 = 后端给了正数等待时长（退避中）；否则是「尝试进行中」的长静默期 → 正计时
  const countdown = retryDelayMs != null && retryDelayMs > 0

  useEffect(() => {
    if (!countdown) {
      setRemaining(0)
      return
    }
    setRemaining(retryDelayMs ?? 0)
    const start = Date.now()
    const timer = setInterval(() => {
      const left = (retryDelayMs ?? 0) - (Date.now() - start)
      setRemaining(Math.max(0, left))
      if (left <= 0) clearInterval(timer)
    }, 100)
    return () => clearInterval(timer)
  }, [retryDelayMs, countdown])

  // 已等待正计时：一次尝试可能挂几分钟（上游迟迟不吐字节），必须让用户看见「还在跑」和已经等了多久
  useEffect(() => {
    if (countdown) {
      setWaited(0)
      return
    }
    const start = at ?? Date.now()
    const tick = () => setWaited(Math.max(0, Date.now() - start))
    tick()
    const timer = setInterval(tick, 500)
    return () => clearInterval(timer)
  }, [countdown, at])

  const hasCountdown = countdown && remaining > 0
  const secs = (remaining / 1000).toFixed(1)
  const waitedSecs = Math.floor(waited / 1000)

  return (
    <div className="retry-banner" role="status">
      <span className="retry-spinner" aria-hidden="true" />
      <span className="retry-label">正在重试</span>
      {/* 用后端真实 attempt/maxRetries，不做本地默认值兜底（缺则整段不显示） */}
      {attempt != null && maxRetries != null && (
        <span className="retry-meta">第 {attempt} / {maxRetries} 次</span>
      )}
      {hasCountdown && <span className="retry-countdown">{secs}s 后重试</span>}
      {!hasCountdown && <span className="retry-countdown">已等待 {waitedSecs}s</span>}
      <button className="retry-close" onClick={onClose} title="取消重试" aria-label="取消重试">
        <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: 10, height: 10 }}>
          <path d="M2 2L10 10M10 2L2 10" />
        </svg>
      </button>
    </div>
  )
}
