import { useEffect, useRef, useState } from 'react'
import { selectGoal, useChatStore } from '@/stores/chatStore'
import {
  formatGoalDuration,
  formatGoalTokens,
  isGoalActive,
} from '@/utils/goalState'

/**
 * [goal V253] 目标头 · 贴在输入框顶部内缘（**不是** App 层横幅）。
 *
 * <p>形态裁定（用户 2026-11-02「这个方向可以」）：
 * <ul>
 *   <li><b>目标 = 输入框的头</b> —— 目标不是「有事要你看」的告警，是「这个会话在追什么」的常驻身份。
 *       告警才配当横幅（会闪、可关、会消失）；目标不该和排队条抢同一层。</li>
 *   <li><b>排队 = 独立托盘</b>（见 QueuedCommandsBar）· <b>告警横幅保持现状</b>。</li>
 * </ul>
 *
 * <p>⚠️ <b>扫光条只挂在「追之中」态</b>：绿（已达成）/ 红（无法达成）不加 ——
 * 「已完成还在扫」自相矛盾。扫光条属于目标头，**一轮只能有一条**（同 MessageList 里
 * 「一轮只能有一条扫光条」的教训），别在消息段里再画一条。
 */
export function GoalHead({ sessionId }: { sessionId: string | null }) {
  const goal = useChatStore(selectGoal(sessionId))
  const setGoal = useChatStore((s) => s.setGoal)
  const [open, setOpen] = useState(false)
  const [now, setNow] = useState(() => Date.now())
  const popRef = useRef<HTMLDivElement>(null)

  // 跳秒：仅在有 active 目标时启动（同 MessageList 的 nowTick 口径 —— 不跑就省 CPU）
  useEffect(() => {
    if (!isGoalActive(goal)) return
    const t = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(t)
  }, [goal])

  // 达成后 6 秒淡出（照 CompactProgressBar 的 done 态节奏）
  useEffect(() => {
    if (!goal || goal.phase === 'active') return
    const t = window.setTimeout(() => {
      if (sessionId) setGoal(sessionId, null)
    }, 6000)
    return () => window.clearTimeout(t)
  }, [goal, sessionId, setGoal])

  // 点空白关闭详情浮层
  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      if (popRef.current && !popRef.current.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', onDown)
    return () => document.removeEventListener('mousedown', onDown)
  }, [open])

  if (!goal || !sessionId) return null

  const phaseClass =
    goal.phase === 'achieved' ? ' achieved' : goal.phase === 'impossible' ? ' failed' : ''

  return (
    <div className="goal-head-wrap">
      <div className={`goalhead${phaseClass}`} onClick={() => setOpen((v) => !v)} role="button" tabIndex={0}
        aria-expanded={open}
        title={goal.phase === 'active' ? '目标进行中（点击查看详情）' : '目标已结束（点击查看详情）'}>
        <span className="gh-ico">
          {goal.phase === 'achieved' ? (
            <svg width="11" height="11" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.8">
              <path d="M2.6 7.4l3 3 5.8-6.4" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          ) : goal.phase === 'impossible' ? (
            <svg width="11" height="11" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.6">
              <circle cx="7" cy="7" r="5.2" />
              <path d="M7 4.2v3.6M7 10.1v.2" strokeLinecap="round" />
            </svg>
          ) : (
            <svg width="11" height="11" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.6">
              <circle cx="7" cy="7" r="4.6" />
              <circle cx="7" cy="7" r="1.3" />
              <path d="M7 .8v1.8M7 11.4v1.8M.8 7h1.8M11.4 7h1.8" strokeLinecap="round" />
            </svg>
          )}
        </span>
        <span className="gh-text">{goal.condition}</span>
        <span className="gh-meta">
          <span className="gh-chip">
            {goal.phase === 'active'
              ? `第 ${goal.iterations + 1} 轮`
              : goal.phase === 'achieved'
                ? '已达成'
                : '无法达成'}
          </span>
          <span>{formatGoalDuration(now - goal.setAt)}</span>
          {goal.phase === 'active' && (
            <>
              <span className="gh-tok">·</span>
              <span className="gh-tok">{formatGoalTokens(goal.tokensAtStart)} tok</span>
            </>
          )}
        </span>
        <button
          className="gh-x"
          title="清除目标"
          onClick={(e) => {
            e.stopPropagation()
            setGoal(sessionId, null)
          }}
        >
          <svg width="10" height="10" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.7">
            <path d="M3.5 3.5l7 7M10.5 3.5l-7 7" strokeLinecap="round" />
          </svg>
        </button>
        {/* 扫光条：只在「追之中」态出现（绿/红不加） */}
        {goal.phase === 'active' && <span className="gh-scan" aria-hidden="true" />}
      </div>

      {open && (
        <div className="goal-pop" ref={popRef}>
          <div className="pop-head">
            <span className="pop-kicker">目标</span>
            <span className="pop-state">
              {goal.phase === 'active' ? '进行中' : goal.phase === 'achieved' ? '已达成' : '无法达成'}
            </span>
            <button className="pop-close" onClick={() => setOpen(false)}>
              <svg width="11" height="11" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.6">
                <path d="M3.5 3.5l7 7M10.5 3.5l-7 7" strokeLinecap="round" />
              </svg>
            </button>
          </div>
          <div className="pop-cond">{goal.condition}</div>
          <div className="pop-stats">
            <div className="pop-stat">
              <div className="k">已运行</div>
              <div className="v">{formatGoalDuration(now - goal.setAt)}</div>
            </div>
            <div className="pop-stat">
              <div className="k">已评估</div>
              <div className="v">{goal.iterations} 轮</div>
            </div>
            <div className="pop-stat">
              <div className="k">输出 token</div>
              <div className="v">{formatGoalTokens(goal.tokensAtStart)}</div>
            </div>
          </div>
          {goal.lastReason && (
            <div className="pop-check">
              <div className="k">
                <svg width="11" height="11" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.6">
                  <circle cx="7" cy="7" r="5" />
                  <path d="M7 4.2v3.2M7 9.6v.2" strokeLinecap="round" />
                </svg>
                最近一次判定
              </div>
              <div className="v">{goal.lastReason}</div>
            </div>
          )}
        </div>
      )}
    </div>
  )
}
