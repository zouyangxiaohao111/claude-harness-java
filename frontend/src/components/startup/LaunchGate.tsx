import { useEffect, useState, type ReactNode } from 'react'
import { CatLoader } from './CatLoader'
import { expandFromSplash } from '@/utils/tauri-bridge'

/** 后端就绪探测地址（业务端点，200 即视为后端就绪）。
 *  用 /api/v1/settings 而非 /actuator/health：后端 CORS 只放行 /api/**，
 *  /actuator 不在白名单 → tauri 源（tauri://localhost / http://tauri.localhost）下请求会被 CORS 拦截，
 *  事件 miss 时兜底探测永远探不通（卡 ~15s 进错误卡）。改走 CORS 内业务端点。 */
const READY_URL = 'http://localhost:3458/api/v1/settings'
const READY_POLL_MS = 500
const READY_TIMEOUT_MS = 15_000

type GateStatus = 'booting' | 'ready' | 'error'

interface LaunchGateProps {
  /** 真实主界面（App）· 后端就绪后才渲染 */
  children: ReactNode
}

/**
 * LaunchGate — 启动就绪门 + 小窗放大动画。
 *
 * 状态机：booting（渲染 CatLoader）→ ready（先 expandFromSplash 放大窗口，再淡入 children）
 *                                    └→ error（后端启动失败/超时失败卡）
 *
 * 就绪来源（取先到者，去重后只生效一次）：
 *  1. Rust 侧 emit 的 backend-ready / backend-error（Tauri 环境，可能早于 webview 挂监听 → 由 3 兜底）
 *  2. 后端业务端点 /api/v1/settings 探活轮询（CORS 白名单 /api/** 内；浏览器非 Tauri 环境同样适用）
 */
export function LaunchGate({ children }: LaunchGateProps) {
  const [status, setStatus] = useState<GateStatus>('booting')
  const [errorMsg, setErrorMsg] = useState('')

  useEffect(() => {
    let alive = true
    let settled = false
    let unReady: (() => void) | null = null
    let unErr: (() => void) | null = null
    let pollId: number | undefined
    let failId: number | undefined

    const clearTimers = () => {
      if (pollId !== undefined) window.clearInterval(pollId)
      if (failId !== undefined) window.clearTimeout(failId)
      pollId = undefined
      failId = undefined
    }
    const clearEvents = () => {
      try {
        unReady?.()
      } catch {
        /* 忽略退订异常 */
      }
      try {
        unErr?.()
      } catch {
        /* 忽略退订异常 */
      }
      unReady = null
      unErr = null
    }
    const goError = (msg: string) => {
      if (!alive || settled) return
      settled = true
      clearTimers()
      clearEvents()
      setErrorMsg(msg)
      setStatus('error')
    }
    const goReady = () => {
      if (!alive || settled) return
      settled = true
      clearTimers()
      clearEvents()
      // 先放大窗口（Tauri 环境；浏览器秒返回）→ 放大完成后再切主界面，避免小窗里露出 App
      void (async () => {
        await expandFromSplash()
        if (alive) setStatus('ready')
      })()
    }
    const pollOnce = async () => {
      if (!alive || settled) return
      try {
        const res = await fetch(READY_URL)
        // 业务端点返回 200 即视为后端已就绪（不再解析 actuator 的 {"status":"UP"}）
        if (res.ok) goReady()
      } catch {
        // 后端尚未就绪或网络/CORS 暂不可达，下一轮重试
      }
    }

    // ① Tauri 事件监听（非 Tauri 环境 import 成功但 listen 会抛 → 回退纯探活）
    void (async () => {
      try {
        const { listen } = await import('@tauri-apps/api/event')
        if (!alive) return
        const unR = await listen('backend-ready', () => goReady())
        if (!alive) {
          unR()
          return
        }
        unReady = unR
        const unE = await listen<string>('backend-error', (e) => {
          const msg = typeof e.payload === 'string' && e.payload.length > 0 ? e.payload : '后端启动失败。'
          goError(msg)
        })
        if (!alive) {
          unE()
          return
        }
        unErr = unE
      } catch {
        // 非 Tauri：仅靠健康探活
      }
    })()

    // ② 兜底自探活：挂载即查一次，未通则每 500ms 重试，最多 ~15s
    void pollOnce()
    pollId = window.setInterval(() => void pollOnce(), READY_POLL_MS)
    failId = window.setTimeout(
      () => goError('启动超时：本地引擎在 15 秒内未就绪。请检查后端服务后关闭并重新打开应用。'),
      READY_TIMEOUT_MS,
    )

    return () => {
      alive = false
      clearTimers()
      clearEvents()
    }
  }, [])

  if (status === 'ready') {
    // 放大完成后淡入主界面（cl-enter 提供 opacity + 轻微 scale 入场）
    return <div className="cl-enter">{children}</div>
  }

  if (status === 'error') {
    return (
      <div className="cl-stage">
        <div className="cl-errCard" role="alert">
          <svg className="cl-errIcon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <circle cx="12" cy="12" r="10" fill="#FF7A3D" opacity="0.14" />
            <path d="M12 7v6M12 16.5v.01" stroke="#E65C00" strokeWidth="2" strokeLinecap="round" />
          </svg>
          <div className="cl-errTitle">本地引擎未能启动</div>
          <div className="cl-errMsg">{errorMsg}</div>
          <div className="cl-errHint">
            请查看 ~/.nexusai/logs/backend.log 了解原因；
            <br />
            如持续失败，请关闭应用后重新打开。
          </div>
        </div>
      </div>
    )
  }

  return <CatLoader />
}
