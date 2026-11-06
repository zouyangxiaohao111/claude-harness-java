import { useEffect, useRef, useState } from 'react'

/** 目标条件上限 · 与后端 `SessionGoal.MAX_CONDITION_LENGTH` 对齐（4000）。 */
const MAX_CONDITION_LENGTH = 4000

/**
 * 设定目标面板 · 贴在输入框顶部内缘（与 {@link GoalHead} 同一位置、同一外壳语言）。
 *
 * <p>形态裁定（2026-11-04 用户「目标位置是对的」）：目标头是「这个会话在追什么」的常驻身份，
 * 设定态与展示态必须是**同一个位置**——用户点「+ → 目标」后，目标头那块区域就地变成输入面板，
 * 提交后再变回目标头。不弹模态、不跳位置。
 *
 * <p>提交等价于发一条 `/goal &lt;条件&gt;`：走同一个后端端点、同样立即触发一轮执行
 * （kickoff 语义不变）。⛔ 不在这里自己拼请求 —— 由 `App.runBuiltin('goal', condition)` 统一处理。
 */
export function GoalSetPanel({
  initialCondition = '',
  onSubmit,
  onCancel,
}: {
  /** 预填条件（已有目标时用于改写） */
  initialCondition?: string
  /** 提交 → App 走 runBuiltin('goal', condition)；抛出即视为失败，面板保持打开 */
  onSubmit: (condition: string) => Promise<void>
  onCancel: () => void
}) {
  const [text, setText] = useState(initialCondition)
  const [submitting, setSubmitting] = useState(false)
  const taRef = useRef<HTMLTextAreaElement>(null)

  // 打开即聚焦：用户是带着「我要设目标」的意图点进来的
  useEffect(() => {
    taRef.current?.focus()
  }, [])

  const trimmed = text.trim()
  const tooLong = trimmed.length > MAX_CONDITION_LENGTH
  const canSubmit = trimmed.length > 0 && !tooLong && !submitting

  const submit = async () => {
    if (!canSubmit) return
    setSubmitting(true)
    try {
      await onSubmit(trimmed)
    } catch {
      // 失败：App 已经弹过 toast（错误文案归 App），这里只保持面板打开供用户修改重试。
      //   ⛔ 不要在这里再弹一次 —— 会变成同一个错误两条提示。
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="goal-set">
      <div className="gs-head">
        <span className="gs-ico">
          <svg width="11" height="11" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.6">
            <circle cx="7" cy="7" r="4.6" />
            <circle cx="7" cy="7" r="1.3" />
            <path d="M7 .8v1.8M7 11.4v1.8M.8 7h1.8M11.4 7h1.8" strokeLinecap="round" />
          </svg>
        </span>
        <span className="gs-title">设定目标</span>
        <span className="gs-hint">模型每轮结束自动判定</span>
      </div>
      <textarea
        ref={taRef}
        className="gs-input"
        placeholder="例如：e2e 测试全部通过"
        value={text}
        rows={2}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={(e) => {
          // Ctrl/Cmd+Enter 提交（Enter 留给换行：目标条件常是多行）
          if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
            e.preventDefault()
            void submit()
          }
          if (e.key === 'Escape') {
            e.preventDefault()
            onCancel()
          }
        }}
      />
      <div className="gs-foot">
        <span className="gs-tip">
          {tooLong ? `超出上限（${trimmed.length} / ${MAX_CONDITION_LENGTH}）` : '达成前持续执行；可随时清除'}
        </span>
        <button type="button" className="gs-btn gs-btn-ghost" onClick={onCancel}>取消</button>
        <button type="button" className="gs-btn gs-btn-primary" disabled={!canSubmit} onClick={() => void submit()}>
          {submitting ? '提交中…' : '开始追踪'}
        </button>
      </div>
    </div>
  )
}
