import { useEffect, useState } from 'react'
import { create } from 'zustand'
import { skillImprovementApi } from '@/api/skills'
import type { SkillUpdate } from '@/api/types'

/**
 * Skill Improvement 建议弹窗（FNT-DC-01/FE-10）。
 *
 * <p>后端 SkillImprovementHook 在生成 suggestion 时经 STOMP {@code /topic/sessions/{sess-xxx}}
 * 推送轻量信号（skillName/updateCount/sessionId）；完整建议内容走 REST
 * {@code GET /api/v1/skill-improvement/suggestion} 拉取，「批准/拒绝」走
 * {@code POST /api/v1/skill-improvement/decision}（applied 布尔闸门）。
 *
 * <p>本文件同时承载模块级 {@link useSkillSurveyStore}——useChatSocket 订阅到
 * {@code skill_improvement.suggestion} 事件时写入，App 渲染的 {@code <SkillSurvey/>} 响应式弹出。
 */

/** STOMP skill_improvement.suggestion 事件载荷（对齐 SkillImprovementSuggestionEvent） */
export interface SkillSuggestionSignal {
  skillName: string
  updateCount: number
  /** REST 决策端点键（事件携带的 UUID；后端 parseSessionUuid 归一化到 store 键） */
  sessionId: string
}

interface SkillSurveyState {
  signal: SkillSuggestionSignal | null
  show: (signal: SkillSuggestionSignal) => void
  clear: () => void
}

/** 模块级 store · 弹窗由信号驱动，决策后 clear 关闭 */
export const useSkillSurveyStore = create<SkillSurveyState>()((set) => ({
  signal: null,
  show: (signal) => set({ signal }),
  clear: () => set({ signal: null }),
}))

export function SkillSurvey() {
  const signal = useSkillSurveyStore((s) => s.signal)
  const clear = useSkillSurveyStore((s) => s.clear)
  const [updates, setUpdates] = useState<SkillUpdate[] | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // 打开时拉取完整建议（事件只带 skillName/updateCount 轻量信号，建议条目存于 store 侧）
  useEffect(() => {
    if (!signal) { setUpdates(null); setError(null); return }
    let cancelled = false
    setUpdates(null)
    skillImprovementApi.getSuggestion(signal.sessionId)
      .then((d) => { if (!cancelled) setUpdates(d?.updates ?? null) })
      .catch((e) => { if (!cancelled) setError(e instanceof Error ? e.message : String(e)) })
    return () => { cancelled = true }
  }, [signal])

  async function decide(applied: boolean) {
    if (!signal || busy) return
    setBusy(true)
    try {
      await skillImprovementApi.postDecision(signal.sessionId, applied)
      clear()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  if (!signal) return null

  return (
    <div className="fm-backdrop" onClick={clear}>
      <div className="fm-modal" style={{ width: 440 }} onClick={(e) => e.stopPropagation()}>
        <div className="fm-header">
          <span className="fm-status-dot" />
          <div className="fm-title">建议改进 {signal.skillName || '技能'}</div>
          <span className="fm-subtitle">{signal.updateCount} 条</span>
        </div>
        <div className="fm-body">
          <div className="fm-field-hint">检测到可沉淀的改进点，是否写入 SKILL.md？</div>
          {error && <div className="fm-field-hint error">{error}</div>}
          {updates ? (
            updates.length === 0 ? (
              <div className="fm-field-hint">暂无改进条目</div>
            ) : (
              updates.map((u, i) => (
                <div key={i} style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                  <div className="fm-field-label">{u.section}</div>
                  <div style={{ fontSize: 12, color: 'var(--ink-muted)', lineHeight: 1.5 }}>{u.change}</div>
                  {u.reason && (
                    <div style={{ fontSize: 10.5, color: 'var(--ink-faint)', lineHeight: 1.4 }}>{u.reason}</div>
                  )}
                </div>
              ))
            )
          ) : (
            <div className="fm-field-hint">加载建议中…</div>
          )}
        </div>
        <div className="fm-footer" style={{ justifyContent: 'flex-end' }}>
          <button className="fm-btn" disabled={busy} onClick={() => decide(false)}>拒绝</button>
          <button className="fm-btn primary" disabled={busy} onClick={() => decide(true)}>批准</button>
        </div>
      </div>
    </div>
  )
}
