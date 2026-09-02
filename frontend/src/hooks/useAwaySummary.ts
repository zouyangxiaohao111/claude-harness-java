import { useEffect, useRef } from 'react'
import { agentApi } from '../api/agent'
import { featuresApi } from '../api/features'

const BLUR_DELAY_MS = 5 * 60_000

/**
 * blur 5min 后调 POST /api/agent/away-summary，200 文本回插为 away_summary 系统消息。
 *
 * <p>门控（对齐 CC feature('AWAY_SUMMARY') + flag 'tengu_sedge_lantern'）：异步拉后端
 * GET /api/v1/features，两开关都 true 才注册 blur/focus 监听（默认关）。
 * 去重防抖：①pending 中（POST 未返回）不重复触发；②5min 窗口内只触发一次。
 *
 * <p>对齐 CC useAwaySummary 触发链：窗口失焦开始计时，持续 5min 失焦即触发一次摘要；
 * 提前回到前台（focus）则取消本次计时。204/500 静默降级。
 */
export function useAwaySummary(sessionId: string | null, onSummary: (text: string) => void) {
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  // ref 缓存最新 sessionId / onSummary，避免 blur 定时器闭包捕获旧值
  const sessionIdRef = useRef(sessionId)
  const onSummaryRef = useRef(onSummary)
  sessionIdRef.current = sessionId
  onSummaryRef.current = onSummary
  // 去重防抖：pending（POST 未返回）锁 + 上次触发时间戳（5min 窗口）
  const pendingRef = useRef(false)
  const lastTriggerRef = useRef(0)

  useEffect(() => {
    let cancelled = false
    let cleanup: (() => void) | null = null

    // 门控：后端 features 两开关都开才允许触发（拉取失败默认关）
    featuresApi.get()
      .then((f) => {
        if (cancelled) return
        if (!f.AWAY_SUMMARY || !f.tengu_sedge_lantern) return

        const clearTimer = () => {
          if (timerRef.current) {
            clearTimeout(timerRef.current)
            timerRef.current = null
          }
        }

        const onBlur = () => {
          clearTimer()
          // 去重：pending 中不重复排定；5min 窗口内已触发过则不再触发
          if (pendingRef.current) return
          if (Date.now() - lastTriggerRef.current < BLUR_DELAY_MS) return
          timerRef.current = setTimeout(() => {
            timerRef.current = null
            const sid = sessionIdRef.current
            if (!sid) return
            pendingRef.current = true
            agentApi.awaySummary(sid)
              .then((text) => { if (text) onSummaryRef.current(text) })
              .catch(() => { /* 204/500 静默，对齐 CC 降级 */ })
              .finally(() => { pendingRef.current = false; lastTriggerRef.current = Date.now() })
          }, BLUR_DELAY_MS)
        }

        const onFocus = () => clearTimer()

        window.addEventListener('blur', onBlur)
        window.addEventListener('focus', onFocus)
        cleanup = () => {
          clearTimer()
          window.removeEventListener('blur', onBlur)
          window.removeEventListener('focus', onFocus)
        }
      })
      .catch(() => { /* 后端 features 未就绪 → 默认关 */ })

    return () => { cancelled = true; cleanup?.() }
  }, [])
}
