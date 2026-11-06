import { useEffect, useRef, useState } from 'react'
import { selectGoal, useChatStore } from '@/stores/chatStore'
import { goalApi } from '@/api/goal'
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
  //
  // [B4 2026-11-04] **paused 不淡出**：暂停态需要常驻等用户看到「发消息继续」，
  //   与 impossible（红条常驻）同理；只有 achieved（绿条）是一次性提示。
  useEffect(() => {
    if (!goal || goal.phase === 'active' || goal.phase === 'paused') return
    const t = window.setTimeout(() => {
      // 达成后的绿条是一次性提示：淡出后连后端列一起清（否则 F5 后绿条又出现）。
      // ⚠️ impossible（红条）**不自动清** —— 它要常驻等用户处理，故只对 achieved 生效。
      if (sessionId && goal.phase === 'achieved') {
        void goalApi.clear(sessionId).catch(() => undefined)
      }
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

  /** 清除目标：先走后端 DELETE（真源），再清前端 store。 */
  const clearGoal = async () => {
    if (!sessionId) return
    try {
      await goalApi.clear(sessionId)
    } catch (e) {
      console.warn('[goal] 清除目标失败（前端仍清显示态）:', e)
    }
    setGoal(sessionId, null)
  }

  if (!goal || !sessionId) return null

  const phaseClass =
    goal.phase === 'achieved' ? ' achieved'
      : goal.phase === 'impossible' ? ' failed'
        : goal.phase === 'paused' ? ' paused'
          : ''

  return (
    <div className="goal-head-wrap">
      <div className={`goalhead${phaseClass}`} onClick={() => setOpen((v) => !v)} role="button" tabIndex={0}
        aria-expanded={open}
        title={
          goal.phase === 'active' ? '目标进行中（点击查看详情）'
            : goal.phase === 'paused' ? '目标已暂停 · 发消息即可继续（点击查看详情）'
              : '目标已结束（点击查看详情）'}>
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
          ) : goal.phase === 'paused' ? (
            /* 暂停图标：两根竖线（对齐播放器的 pause 语义，一眼可辨） */
            <svg width="11" height="11" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.9">
              <path d="M5 3.2v7.6M9 3.2v7.6" strokeLinecap="round" />
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
                : goal.phase === 'paused'
                  ? '已暂停 · 发消息继续'
                  : '无法达成'}
          </span>
          <span>{formatGoalDuration(now - goal.setAt)}</span>
          {/* [goal tokens 2026-11-05] 显示**真实消耗**（后端按 llm_usage_calls 聚合），
              ⛔ 不是 tokensAtStart —— 那是「设定时刻的基线快照」，此前被当成消耗量显示，
              实测目标是 0 时条上恒显示「0 tok」，而实际已跑掉 13 万 output token。
              tokensUsed null ⇒ 后端取不到 ⇒ 整段不渲染（不显示假的 0）。 */}
          {(goal.phase === 'active' || goal.phase === 'paused')
            && typeof goal.tokensUsed === 'number' && (
            <>
              <span className="gh-tok">·</span>
              <span className="gh-tok">{formatGoalTokens(goal.tokensUsed)} tok</span>
            </>
          )}
        </span>
        <button
          className="gh-x"
          title="清除目标"
          onClick={(e) => {
            e.stopPropagation()
            // [2026-11-04 用户裁定] 先确认再清：X 紧贴目标条右缘，误点一下目标就没了。
            //   与 /goal clear 同一条链路（后端 GoalService.clearGoal），只是多了这道确认。
            if (!window.confirm(`清除目标「${goal.condition}」？\n清除后本会话不再追踪该目标。`)) return
            // [goal V253] 清除必须走后端：只删前端 store 的话，后端 sessions.goal 列还在、
            //   prompt Stop hook 还挂着 ⇒ 下一轮评估器仍会拦模型，且后端下次 push 后目标头又回来。
            void clearGoal()
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
              {goal.phase === 'active' ? '进行中'
                : goal.phase === 'achieved' ? '已达成'
                  : goal.phase === 'paused' ? '已暂停' : '无法达成'}
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
            {/* [goal tokens 2026-11-05] 真实消耗（后端 llm_usage_calls 聚合）；
                null ⇒ 取不到 ⇒ 整格不渲染（不显示假的 0）。 */}
            {typeof goal.tokensUsed === 'number' && (
              <div className="pop-stat">
                <div className="k">输出 token</div>
                <div className="v">{formatGoalTokens(goal.tokensUsed)}</div>
              </div>
            )}
            {/* [B6] 自动重试进度 —— 只在真的重试过时显示（0 次不占版面）。
                WHY 要显示：评估器自身失败（超时/JSON 坏/模型报错）时目标会「看起来没动」，
                用户看到「已自动重试 2/3 次」才知道不是卡死，而是正在按后端节奏重判。
                ⚠️ 上限来自后端载荷（retryLimit），⛔ 不在前端硬编码 3。 */}
            {(goal.retryCount ?? 0) > 0 && (
              <div className="pop-stat">
                <div className="k">自动重试</div>
                <div className="v">
                  {goal.retryCount}
                  {typeof goal.retryLimit === 'number' && goal.retryLimit > 0
                    ? ` / ${goal.retryLimit}`
                    : ''}{' '}
                  次
                </div>
              </div>
            )}
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
