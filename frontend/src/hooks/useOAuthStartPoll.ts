import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError } from '@/api/rest'
import { openExternalUrl } from '@/utils/openExternal'

/**
 * OAuth「start → 打开浏览器 → 轮询 → 成功回写」的共用逻辑（连接器抽屉 / MCP 面板行共用）。
 *
 * <p><b>为什么抽 hook 而不是复用组件</b>：两处视觉体系不同（市场用 `mc-` 抽屉，MCP 面板用设置页
 * `cli-*` 风格），但**流程完全相同**；只共享受控的状态机，不共享 DOM。
 *
 * <p>关键约束（照 `ImChannelPage.OauthPanel` 的实测经验）：
 * <ul>
 *   <li><b>StrictMode 双跑去重</b>：用 `startPromiseRef` 缓存同一个 start Promise，effect 跑两遍也只发一次
 *       ——否则后端会开两个授权会话、弹两次浏览器；</li>
 *   <li>授权页只自动打开一次（`openedRef`），用户仍可手动再开；</li>
 *   <li>轮询定时器在阶段变化/卸载时清理，回调走 ref（父组件内联箭头函数不会反复重启轮询）。</li>
 * </ul>
 */
export type OAuthPhase = 'idle' | 'starting' | 'waiting' | 'ok' | 'err'

export interface OAuthStartResult {
  authorizeUrl: string
  state: string
  redirectUri: string
  /** 轮询间隔（秒） */
  interval: number
  expiresIn: number
}

export interface OAuthPollResult {
  state: 'PENDING' | 'AUTHORIZED' | 'ERROR'
  message?: string | null
}

const errMsg = (e: unknown) => (e instanceof ApiError ? e.userMessage() : String(e))

export function useOAuthStartPoll(opts: {
  start: () => Promise<OAuthStartResult>
  poll: (state: string) => Promise<OAuthPollResult>
  /** AUTHORIZED 后拉一次权威状态并刷新父组件（可以 async） */
  onAuthorized: () => void | Promise<void>
  /** 系统浏览器没打开授权页时（Tauri 权限/弹窗被拦） */
  onOpenFailed?: () => void
  initialPhase?: OAuthPhase
}) {
  const [phase, setPhase] = useState<OAuthPhase>(opts.initialPhase ?? 'idle')
  const [auth, setAuth] = useState<OAuthStartResult | null>(null)
  const [err, setErr] = useState<string | null>(null)

  const startPromiseRef = useRef<Promise<OAuthStartResult> | null>(null)
  const openedRef = useRef(false)
  const cbRef = useRef(opts)
  cbRef.current = opts

  const reset = useCallback(() => {
    startPromiseRef.current = null
    openedRef.current = false
    setAuth(null)
    setErr(null)
    setPhase('idle')
  }, [])

  const begin = useCallback(async () => {
    setErr(null)
    setPhase('starting')
    try {
      if (!startPromiseRef.current) startPromiseRef.current = cbRef.current.start()
      const r = await startPromiseRef.current
      setAuth(r)
      setPhase('waiting')
      if (!openedRef.current) {
        openedRef.current = true
        const ok = await openExternalUrl(r.authorizeUrl)
        if (!ok) cbRef.current.onOpenFailed?.()
      }
    } catch (e) {
      setPhase('err')
      setErr(errMsg(e))
    }
  }, [])

  useEffect(() => {
    if (phase !== 'waiting' || !auth) return
    let alive = true
    let timer: number | undefined
    const intervalMs = Math.max(1, auth.interval || 3) * 1000
    const tick = async () => {
      try {
        const p = await cbRef.current.poll(auth.state)
        if (!alive) return
        if (p.state === 'AUTHORIZED') {
          await cbRef.current.onAuthorized()
          if (!alive) return
          setPhase('ok')
          return
        }
        if (p.state === 'ERROR') {
          setPhase('err')
          setErr(p.message || '授权失败')
          return
        }
        timer = window.setTimeout(tick, intervalMs)
      } catch (e) {
        if (alive) { setPhase('err'); setErr(errMsg(e)) }
      }
    }
    timer = window.setTimeout(tick, intervalMs)
    return () => { alive = false; if (timer) window.clearTimeout(timer) }
  }, [phase, auth])

  return { phase, auth, err, begin, reset }
}
