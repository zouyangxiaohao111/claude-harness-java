import { useEffect, useState } from 'react'
import type { PermissionRequestItem } from '@/stores/chatStore'
import type { AskUserAnswers, AskUserAnnotations, AskUserQuestion } from '@/api/types'

interface Props {
  request: PermissionRequestItem
  onDecision: (requestId: string, decision: 'allow' | 'deny', answers?: AskUserAnswers, annotations?: AskUserAnnotations) => void
  /** 中止权限请求（用户放弃决策 → App 发 deny + dequeue · 对齐 CC Ctrl+C onReject，解除 worker 等待） */
  onAbort?: () => void
}

/** 每问勾选态：question 文本 → 单选 label / 多选 label[] */
type Selection = Record<string, string | string[]>

/** 从 request.toolInput（后端 JsonNode → JSON 对象/字符串）解析 AskUser 问题列表；无 questions → 空数组 */
function extractQuestions(request: PermissionRequestItem): AskUserQuestion[] {
  const toolInput = (request as PermissionRequestItem & { toolInput?: unknown }).toolInput
  if (toolInput == null) return []
  const input = typeof toolInput === 'string' ? tryParse(toolInput) : toolInput
  if (typeof input !== 'object' || input === null) return []
  const questions = (input as Record<string, unknown>).questions
  return Array.isArray(questions) ? (questions as AskUserQuestion[]) : []
}

function tryParse(json: string): unknown {
  try { return JSON.parse(json) } catch { return null }
}

/** 多选判定 · 兼容 camelCase（multiSelect）与 snake_case（multiple_selection/multi_select）模型输出 */
function isMultiSelect(q: AskUserQuestion): boolean {
  return !!q.multiSelect || !!q.multiple_selection || !!q.multi_select
}

/** 等待时长（秒）：基于服务端推送时间戳 timestampMs 每秒刷新（对齐 CC permission 等待指示） */
function useElapsed(timestampMs?: number): number {
  const [now, setNow] = useState(Date.now())
  useEffect(() => {
    if (!timestampMs) return
    const t = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(t)
  }, [timestampMs])
  return timestampMs ? Math.max(0, Math.floor((now - timestampMs) / 1000)) : 0
}

/** leader inbox 徽标（workerBadgeColor 彩色 · 对齐 CC WorkerBadgeProps.color；null 回落默认） */
function WorkerBadge({ color }: { color?: string | null }) {
  return (
    <div className="pb-swarm-badge" style={color ? { background: color } : undefined}>swarm · 队友请求</div>
  )
}

/** 工具参数展示（普通权限弹窗 · toolInput 对齐 CC per-tool PermissionComponent 展示 input） */
function ToolInput({ toolInput }: { toolInput: unknown }) {
  if (toolInput == null) return null
  const text = typeof toolInput === 'string' ? toolInput : JSON.stringify(toolInput, null, 2)
  return <pre className="pb-input">{text}</pre>
}

export function PermissionBubble({ request, onDecision, onAbort }: Props) {
  const questions = extractQuestions(request)
  const elapsed = useElapsed(request.timestampMs)
  // 无 questions → 保持原 allow/deny 弹窗（+ 中止按钮）
  if (questions.length === 0) {
    return (
      <div className="permission-bubble">
        <div className="pb-title">
          {request.isLeaderInbox ? `队友请求使用工具：${request.toolName}` : `需要权限：${request.toolName}`}
        </div>
        {request.isLeaderInbox && <WorkerBadge color={request.workerBadgeColor} />}
        {request.warning && <div className="pb-warning">{request.warning}</div>}
        {request.description && <div className="pb-desc">{request.description}</div>}
        {request.isLeaderInbox && (
          <div className="pb-worker">{request.workerName ? `来自 ${request.workerName}` : '来自队友（名称待后端补字段）'}</div>
        )}
        {request.reason && <div className="pb-reason">{request.reason.reason ?? request.reason.detail}</div>}
        <ToolInput toolInput={(request as PermissionRequestItem & { toolInput?: unknown }).toolInput} />
        {request.timestampMs && <div className="pb-waiting">已等待 {elapsed}s</div>}
        <div className="pb-actions">
          {onAbort && <button className="pb-abort" onClick={onAbort}>中止</button>}
          <button onClick={() => onDecision(request.requestId, 'deny')}>拒绝</button>
          <button className="primary" onClick={() => onDecision(request.requestId, 'allow')}>允许</button>
        </div>
      </div>
    )
  }
  return <AskUserForm request={request} questions={questions} onDecision={onDecision} onAbort={onAbort} />
}

function AskUserForm({ request, questions, onDecision, onAbort }: {
  request: PermissionRequestItem
  questions: AskUserQuestion[]
  onDecision: Props['onDecision']
  onAbort?: () => void
}) {
  const [selected, setSelected] = useState<Selection>({})
  // 每题自定义答案（手动填写 · 对齐 CC AskUserQuestion 输入框）：非空时优先于选项选择
  const [customInputs, setCustomInputs] = useState<Record<string, string>>({})
  const elapsed = useElapsed(request.timestampMs)

  function clearCustom(q: AskUserQuestion) {
    setCustomInputs((c) => (c[q.question] ? { ...c, [q.question]: '' } : c))
  }
  function toggleSingle(q: AskUserQuestion, label: string) {
    setSelected((s) => ({ ...s, [q.question]: label }))
    clearCustom(q)
  }
  function toggleMulti(q: AskUserQuestion, label: string) {
    setSelected((s) => {
      const cur = (s[q.question] as string[] | undefined) ?? []
      const next = cur.includes(label) ? cur.filter((l) => l !== label) : [...cur, label]
      return { ...s, [q.question]: next }
    })
    clearCustom(q)
  }
  // 手动填写 → 清空该题选项选中（单选/多选互斥：自定义输入优先）
  function handleCustom(q: AskUserQuestion, value: string) {
    setCustomInputs((c) => ({ ...c, [q.question]: value }))
    setSelected((s) => {
      if (!s[q.question]) return s
      const next = { ...s }
      delete next[q.question]
      return next
    })
  }

  function confirm() {
    const answers: AskUserAnswers = {}
    for (const q of questions) {
      // 自定义输入优先（trim 后非空用自定义，否则回退选项选择）
      const custom = (customInputs[q.question] ?? '').trim()
      if (custom) { answers[q.question] = custom; continue }
      const val = selected[q.question]
      if (val === undefined || (Array.isArray(val) && val.length === 0)) continue
      // multi-select 逗号拼接（对齐后端 AskUserQuestionTool outputSchema）
      answers[q.question] = Array.isArray(val) ? val.join(', ') : val
    }
    // 无 preview/notes 收集 UI → annotations 恒空对象（后端仅非空才并入 Allow.updatedInput）
    const annotations: AskUserAnnotations = {}
    onDecision(request.requestId, 'allow', answers, annotations)
  }

  return (
    <div className="permission-bubble">
      <div className="pb-title">
        {request.isLeaderInbox ? `队友请求使用工具：${request.toolName}` : `需要权限：${request.toolName}`}
      </div>
      {request.isLeaderInbox && <WorkerBadge color={request.workerBadgeColor} />}
      {request.warning && <div className="pb-warning">{request.warning}</div>}
      {request.isLeaderInbox && (
        <div className="pb-worker">{request.workerName ? `来自 ${request.workerName}` : '来自队友（名称待后端补字段）'}</div>
      )}
      <div className="pb-desc">请回答以下问题以继续</div>
      {questions.map((q) => (
        <div key={q.question} className="pb-question">
          <div className="fm-field-label">{q.header} · {isMultiSelect(q) ? '可多选' : '单选'}</div>
          <div className="fm-field-hint">{q.question}</div>
          <div className="pb-options">
            {q.options.map((opt) => (
              <label key={opt.label} className="pb-option">
                <input
                  type={isMultiSelect(q) ? 'checkbox' : 'radio'}
                  name={q.question}
                  checked={isMultiSelect(q)
                    ? ((selected[q.question] as string[] | undefined)?.includes(opt.label) ?? false)
                    : selected[q.question] === opt.label}
                  onChange={() => (isMultiSelect(q) ? toggleMulti(q, opt.label) : toggleSingle(q, opt.label))}
                />
                <span className="pb-option-block">
                  <span className="pb-option-label">{opt.label}</span>
                  {opt.description && <span className="pb-option-desc">{opt.description}</span>}
                </span>
              </label>
            ))}
          </div>
          {/* 手动填写（对齐 CC AskUserQuestion 输入框 · 每问含多选都支持 · 非空优先于选项） */}
          <input
            className="pb-custom-input"
            type="text"
            placeholder="输入自定义答案…（优先于选项选择）"
            value={customInputs[q.question] ?? ''}
            onChange={(e) => handleCustom(q, e.target.value)}
          />
        </div>
      ))}
      {request.timestampMs && <div className="pb-waiting">已等待 {elapsed}s</div>}
      <div className="pb-actions">
        {onAbort && <button className="pb-abort" onClick={onAbort}>中止</button>}
        <button className="fm-btn" onClick={() => onDecision(request.requestId, 'deny')}>拒绝</button>
        <button className="fm-btn primary" onClick={confirm}>确认</button>
      </div>
    </div>
  )
}
