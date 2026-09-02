import { useEffect, useState } from 'react'
import { useChatStore } from '@/stores/chatStore'
import { sessionApi } from '@/api/sessions'
import { statsApi } from '@/api/stats'
import type { ChatMessageDto, StatsResponse } from '@/api/types'
import { compactNumber } from '@/utils/format'

/** 千位以上紧凑显示（按模型明细/上下文条用 · 对齐 ContextAnalyzeModal fmt） */
const fmt = (n: number) => (n >= 1000 ? `${(n / 1000).toFixed(1)}k` : String(n))

/** 稳定空数组（避免 selector `?? []` 每次返回新引用触发无限重渲染）。 */
const EMPTY_MESSAGES: ChatMessageDto[] = []

/** 条形图宽度百分比：v>0 时至少 2%（小值可见），否则 0（零数据不显示条）。 */
const barPct = (v: number, max: number) => (max > 0 && v > 0 ? Math.max(2, (v / max) * 100) : 0)

/**
 * F1/S3 · 「统计」标签页内容（GET /api/v1/stats 全量聚合 · 只读展示）
 * 总览卡片（会话数/tokens/金额）+ 按天条形图（date · token 相对比例）+ 按模型条形图
 * （model · input/output/cache 拆解 · 纯 CSS div 宽度百分比，无图表库）。
 * 打开时拉取；loading 占位 / 失败显示重试按钮（不阻塞用量标签页）。
 */
function StatsSection() {
  const [data, setData] = useState<StatsResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)
    statsApi.get()
      .then((d) => { if (!cancelled) setData(d) })
      .catch((e) => {
        if (cancelled) return
        setError(e instanceof Error ? e.message : String(e))
      })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [attempt])

  if (loading) {
    return <div className="uc-stats-state">统计加载中…</div>
  }
  if (error || !data) {
    return (
      <div className="uc-stats-state">
        <div className="uc-stats-error">统计加载失败{error ? `：${error}` : ''}</div>
        <button className="uc-stats-retry" onClick={() => setAttempt((a) => a + 1)}>重试</button>
      </div>
    )
  }

  const { totals, byDay, byModel } = data
  const maxDayTokens = byDay.reduce((m, d) => Math.max(m, d.tokenCount), 0)
  const maxModelTokens = byModel.reduce((m, r) => Math.max(m, r.inputTokens + r.outputTokens + r.cacheReadInputTokens + r.cacheCreationInputTokens), 0)

  return (
    <div className="uc-stats">
      {/* 总览：会话数 / 总 tokens / 总金额 */}
      <div className="uc-section-title">总览</div>
      <div className="uc-stats-overview">
        <div className="uc-stats-card">
          <span className="uc-stats-card-value">{totals.sessionCount}</span>
          <span className="uc-stats-card-label">会话数</span>
        </div>
        <div className="uc-stats-card">
          <span className="uc-stats-card-value">{compactNumber(totals.tokenCount)}</span>
          <span className="uc-stats-card-label">总 tokens</span>
        </div>
        <div className="uc-stats-card">
          <span className="uc-stats-card-value uc-stats-cost">¥{totals.costYuan.toFixed(2)}</span>
          <span className="uc-stats-card-label">总金额</span>
        </div>
      </div>

      {/* 按天：date + token 相对比例条形图 + 金额 */}
      <div className="uc-section">
        <div className="uc-section-title">按天</div>
        {byDay.length === 0 && <div className="uc-muted">暂无数据</div>}
        {byDay.map((d) => (
          <div key={d.date} className="uc-stats-bar-row">
            <span className="uc-stats-bar-label">{d.date}</span>
            <div className="uc-stats-bar">
              <div className="uc-stats-bar-track">
                <div className="uc-stats-bar-fill" style={{ width: `${barPct(d.tokenCount, maxDayTokens)}%` }} />
              </div>
              <span className="uc-stats-bar-val">{fmt(d.tokenCount)} tok · ¥{d.costYuan.toFixed(2)}</span>
            </div>
          </div>
        ))}
      </div>

      {/* 按模型：model + input/output/cache 拆解 + 金额（token 相对比例条形图） */}
      <div className="uc-section">
        <div className="uc-section-title">按模型</div>
        {byModel.length === 0 && <div className="uc-muted">暂无数据</div>}
        {byModel.map((r) => {
          const total = r.inputTokens + r.outputTokens + r.cacheReadInputTokens + r.cacheCreationInputTokens
          return (
            <div key={r.model} className="uc-stats-model">
              <div className="uc-stats-bar-row">
                <span className="uc-stats-bar-label uc-stats-model-name" title={r.model}>{r.model}</span>
                <div className="uc-stats-bar">
                  <div className="uc-stats-bar-track">
                    <div className="uc-stats-bar-fill" style={{ width: `${barPct(total, maxModelTokens)}%` }} />
                  </div>
                  <span className="uc-stats-bar-val">{fmt(total)} tok · ¥{r.costUSD.toFixed(2)}</span>
                </div>
              </div>
              <div className="uc-stats-model-detail">
                in {fmt(r.inputTokens)} · out {fmt(r.outputTokens)}
                {r.cacheReadInputTokens > 0 && ` · cache读 ${fmt(r.cacheReadInputTokens)}`}
                {r.cacheCreationInputTokens > 0 && ` · cache写 ${fmt(r.cacheCreationInputTokens)}`}
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}

/**
 * F1 · 用量与花费弹窗（Composer 底部 hint-usage 点击打开 · 只读展示）
 * 顶部「当前会话」：金额（totalCostYuan）+ tokens（totalTokens）+ 按模型明细（末条 assistant 消息
 *   modelUsage 快照 · 会话累计）+ 当前上下文条（末条消息 contextTokensUsed/percentLeft，回落 token_warning 事件）。
 * 下方「所有会话」列表：map chatStore.sessions，每行 title+time+tokens+金额，点击 switchSession。
 * 打开时 sessionApi.list() 刷新（totalCostYuan/totalTokens 从后端 sessions 表重读）。
 */
export function UsageCostModal({
  activeSessionId,
  onSwitch,
  onClose,
}: {
  activeSessionId: string
  onSwitch: (sessionId: string) => void
  onClose: () => void
}) {
  // F1/S3 · 标签页：usage=本会话+所有会话，stats=全量统计（GET /api/v1/stats）
  const [tab, setTab] = useState<'usage' | 'stats'>('usage')
  const sessions = useChatStore((s) => s.sessions)
  const setSessions = useChatStore((s) => s.setSessions)
  const tokenWarning = useChatStore((s) => s.tokenWarning)
  const activeSession = sessions.find((s) => s.id === activeSessionId) ?? sessions[0] ?? null
  // 末条 assistant 消息（complete 事件透传 usage/modelUsage/上下文快照；纯思考轮 content 空但 usage 有效）
  const lastMsg = useChatStore((s) => {
    const msgs = s.messages[activeSessionId] ?? EMPTY_MESSAGES
    for (let i = msgs.length - 1; i >= 0; i--) {
      if (msgs[i].role === 'assistant') return msgs[i]
    }
    return null
  })

  // 打开时刷新会话列表（后端 sessions 表权威累计值 · 本地 store 可能滞后）
  useEffect(() => {
    let cancelled = false
    sessionApi.list()
      .then((list) => { if (!cancelled) setSessions(list) })
      .catch(() => { /* 静默：失败沿用本地 store 已有数据 */ })
    return () => { cancelled = true }
  }, [setSessions])

  const modelUsageEntries = lastMsg?.modelUsage ? Object.entries(lastMsg.modelUsage) : []
  // 当前上下文条：末条消息快照优先，回落 token_warning 事件
  const ctxUsed = lastMsg?.contextTokensUsed ?? tokenWarning?.tokenUsage ?? null
  const ctxWindow = lastMsg?.contextWindow ?? tokenWarning?.contextWindow ?? null
  const ctxPct = lastMsg?.percentLeft ?? tokenWarning?.percentLeft ?? null
  const ctxFill = ctxUsed != null && ctxWindow != null && ctxWindow > 0
    ? Math.max(0, Math.min(100, (ctxUsed / ctxWindow) * 100))
    : null
  // 全部会话合计：sessions 表累计值求和（只读展示 · 对齐「所有会话」行式，金额 accent 色）
  const totalCostSum = sessions.reduce((acc, s) => acc + (s.totalCostYuan ?? 0), 0)
  const totalTokensSum = sessions.reduce((acc, s) => acc + (s.totalTokens ?? 0), 0)

  return (
    <div className="uc-backdrop" onClick={onClose}>
      <div className="uc-modal" onClick={(e) => e.stopPropagation()}>
        <div className="uc-header">
          <span className="uc-title">用量与花费</span>
          <button className="uc-close" onClick={onClose} aria-label="关闭">
            <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: 12, height: 12 }}>
              <path d="M2 2L10 10M10 2L2 10" />
            </svg>
          </button>
        </div>
        <div className="uc-body">
          {/* F1/S3 · 标签页：用量（本会话+所有会话） / 统计（全量聚合 GET /api/v1/stats） */}
          <div className="uc-tabs">
            <button
              className={`uc-tab${tab === 'usage' ? ' active' : ''}`}
              onClick={() => setTab('usage')}
            >用量</button>
            <button
              className={`uc-tab${tab === 'stats' ? ' active' : ''}`}
              onClick={() => setTab('stats')}
            >统计</button>
          </div>
          {tab === 'usage' && (
          <>
          {/* 当前会话：金额 + tokens（会话累计） */}
          <div className="uc-section-title">当前会话</div>
          {activeSession ? (
            <div className="uc-total">
              {activeSession.totalCostYuan != null && activeSession.totalCostYuan > 0 && (
                <span className="uc-amount">¥{activeSession.totalCostYuan.toFixed(2)}</span>
              )}
              {activeSession.totalTokens != null && activeSession.totalTokens > 0 && (
                <span className="uc-tokens">· {compactNumber(activeSession.totalTokens)} tokens</span>
              )}
              {(activeSession.totalCostYuan == null || activeSession.totalCostYuan <= 0) &&
               (activeSession.totalTokens == null || activeSession.totalTokens <= 0) && (
                <span className="uc-muted">暂无用量数据</span>
              )}
            </div>
          ) : (
            <div className="right-empty">无当前会话</div>
          )}

          {/* 按模型明细（末条 assistant 消息 modelUsage · 会话累计；无数据整块省略） */}
          {modelUsageEntries.length > 0 && (
            <div className="uc-section">
              <div className="uc-sub-title">按模型明细（累计）</div>
              {modelUsageEntries.map(([name, u]) => (
                <div key={name} className="uc-row">
                  <span className="uc-name">{name}</span>
                  <span className="uc-tokens">
                    {fmt(u.inputTokens + u.outputTokens)} tokens
                    {u.costUSD != null && u.costUSD > 0 ? ` · ¥${u.costUSD.toFixed(2)}` : ''}
                  </span>
                </div>
              ))}
            </div>
          )}

          {/* 当前上下文条（复用死 CSS .hint-token-bar/.hint-token-fill/.hint-token-percent） */}
          {ctxUsed != null && ctxWindow != null && (
            <div className="uc-section">
              <div className="uc-sub-title">当前上下文</div>
              <div className="uc-ctx-row">
                <span className="uc-ctx-text">
                  {compactNumber(ctxUsed)} / {compactNumber(ctxWindow)} tokens
                  {ctxPct != null && <span className="hint-token-percent">（剩余 {ctxPct}%）</span>}
                </span>
                <div className="hint-token-bar">
                  <div className="hint-token-fill" style={{ width: `${ctxFill ?? 0}%` }} />
                </div>
              </div>
            </div>
          )}

          {/* 所有会话列表（点击切换 · 对齐 SessionList 行式） */}
          <div className="uc-section">
            <div className="uc-section-title">所有会话</div>
            {sessions.length === 0 && <div className="right-empty">无会话</div>}
            {sessions.map((s) => (
              <div
                key={s.id}
                className={`uc-session${s.id === activeSessionId ? ' active' : ''}`}
                onClick={() => { onSwitch(s.id); onClose() }}
              >
                <div className="uc-sess-main">
                  <span className="uc-sess-title">{s.title || '未命名会话'}</span>
                  <span className="uc-sess-time">{s.time ?? ''}</span>
                </div>
                <div className="uc-sess-meta">
                  {s.totalTokens != null && s.totalTokens > 0 && (
                    <span className="uc-sess-tokens">{compactNumber(s.totalTokens)} tok</span>
                  )}
                  {s.totalCostYuan != null && s.totalCostYuan > 0 && (
                    <span className="uc-sess-cost">¥{s.totalCostYuan.toFixed(2)}</span>
                  )}
                </div>
              </div>
            ))}
            {/* 全部会话合计：总 tokens + 总金额（对齐列表行 · 金额 accent 色） */}
            {sessions.length > 0 && (
              <div className="uc-session uc-total-row">
                <div className="uc-sess-main">
                  <span className="uc-sess-title">全部会话合计</span>
                  <span className="uc-sess-time">{sessions.length} 个会话</span>
                </div>
                <div className="uc-sess-meta">
                  {totalTokensSum > 0 && (
                    <span className="uc-sess-tokens">{compactNumber(totalTokensSum)} tok</span>
                  )}
                  {totalCostSum > 0 && (
                    <span className="uc-sess-cost">¥{totalCostSum.toFixed(2)}</span>
                  )}
                </div>
              </div>
            )}
          </div>
          </>
          )}
          {tab === 'stats' && <StatsSection />}
        </div>
      </div>
    </div>
  )
}
