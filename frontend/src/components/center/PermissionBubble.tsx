import { useEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { MarkdownTextV2 } from '@/markdown/vendor/renderer'
import { JsonBlock } from '@/markdown/JsonBlock'
import type { PermissionRequestItem } from '@/stores/chatStore'
import type { AskUserAnswers, AskUserAnnotations, AskUserQuestion, PermissionUpdate } from '@/api/types'
import { buildSuggestionOptions } from './permissionSuggestionLabels'
import { deliveryFailureText } from './permissionDeliveryText'
import { ComputerUseApprovalError, ComputerUseApprovalPanel, isComputerUseTool } from './ComputerUseApprovalPanel'

interface Props {
  request: PermissionRequestItem
  onDecision: (requestId: string, decision: 'allow' | 'deny', answers?: AskUserAnswers, annotations?: AskUserAnnotations, permissionUpdates?: PermissionUpdate[], acceptFeedback?: string, updatedInput?: unknown, afk?: { afkTimeoutMs: number; isByCountdown: boolean }) => void
  /** [plan-approval] 清空上下文后执行（对齐 CC clear-context 批准）：由 App 负责新建会话并发送执行指令 */
  onPlanClearContext?: (plan: string) => void
  /** 中止权限请求（用户放弃决策 → App 发 deny + dequeue · 对齐 CC Ctrl+C onReject，解除 worker 等待） */
  onAbort?: () => void
  /**
   * [批 A4c P3] 当前会话绑定项目的展示名 —— 项目级档位文案里的「项目标识」。
   *
   * <p>为什么需要它：后端推来的 `suggestions` 只带 `destination`（规则落到哪个设置文件），
   * 项目级 destination 的落点是 `<项目>/.nexusai/settings.local.json`，而项目名/路径只有
   * 前端（会话绑定项目）知道。取不到时传 null ⇒ 文案退化为「在本项目中」，不编造项目名。
   */
  projectLabel?: string | null
  /**
   * [appName 通道 2026-09-23] 自有根目录名（**不含**前导点，如 `nexusai` / `nexusai-scene`）——
   * 「编辑配置目录」档位文案里的自有根标记由它派生（`.{selfDirName}/`）。
   *
   * <p>为什么需要它：自有根 = `~/.{appName}`（后端 `NexusaiPaths.getAppConfigHomeDir`），
   * 而后端「编辑自有设置」档产出的规则是 `~/.{appName}/**`。写死 `.nexusai/` 时，
   * appName 非 nexusai 的发行里该规则命不中 ⇒ 文案退化成规则原文 + 每次渲染一条 console.warn。
   * 取不到时传 null ⇒ 回落 `.nexusai/`（与落地前逐字相同，不猜、不编造）。
   */
  selfDirName?: string | null
  /**
   * 当前会话权限模式（CC 串）。仅用于「提示后果」：plan 模式下接受带 setMode 的档位
   * 会切走计划模式，档位文案必须写明。
   */
  permissionMode?: string | null
  /**
   * [B3] 本批次序号（1-based）与总数。一次只展示一张（排队），这里告诉用户「第几张 / 共几张」。
   * 总数 < 2 不显示（对齐 CC：N<2 不显示）。
   */
  queueIndex?: number
  queueSize?: number
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

/**
 * [B3] 排队序号徽标「1 of 3」。
 *
 * <p>WHY：多条权限请求**一次只展示一张**（答完揭示下一张）；用户需要知道还剩几张，
 * 否则会以为只收到了一条。N<2 时不显示（对齐 CC）。
 */
function QueueBadge({ index, total }: { index?: number; total?: number }) {
  if (!index || !total || total < 2) return null
  return <span className="pb-queue-index" data-testid="pb-queue-index">{index} of {total}</span>
}

/** 工具参数展示（普通权限弹窗 · toolInput 对齐 CC per-tool PermissionComponent 展示 input） */
function ToolInput({ toolInput }: { toolInput: unknown }) {  // JsonBlock 无 null 守卫 → 由调用方先挡（无参数时不渲染参数区）
  if (toolInput == null) return null
  // toolInput 可能是 JSON 字符串节点（后端 JsonNode 序列化而来）：先尝试 parse，
  // 解析失败再按裸字符串交给 JsonBlock（它会 stringify，故裸串会带引号显示 —— 但那比丢内容好）。
  // 非字符串入参直接交给 JsonBlock 走 JSON.stringify（与改造前的分支等价）。
  return <JsonBlock label="工具参数" payload={typeof toolInput === 'string' ? tryParse(toolInput) ?? toolInput : toolInput} defaultOpen />
}

export function PermissionBubble({ request, onDecision, onPlanClearContext, onAbort, projectLabel, selfDirName, permissionMode, queueIndex, queueSize }: Props) {
  const questions = extractQuestions(request)
  const elapsed = useElapsed(request.timestampMs)
  // 一键授权档位（后端 suggestions → 文案）· useMemo：useElapsed 每秒触发重渲染，
  //   不 memo 会让「未识别形态」的 warn 每秒刷屏。
  //   ⚠️ selfDirName 必须进依赖数组：漏了会让 appName 变化（settings 加载完成）后文案不重算。
  const suggestionOptions = useMemo(
    () => buildSuggestionOptions(request.suggestions, request.toolName, projectLabel, selfDirName, permissionMode),
    [request.suggestions, request.toolName, projectLabel, selfDirName, permissionMode],
  )
  // plan 模式下，带 setMode 的档位会切换会话模式（CC 有意保留该建议）⇒ 显式警示一次，
  // 避免用户以为只是「允许这次写入」，结果悄悄退出计划模式。
  const planModeSwitchWarning = permissionMode === 'plan' &&
    suggestionOptions.some((o) => o.update.type === 'setMode' && o.update.mode !== 'plan')
  // [桌面控制审批面板] 后端带 computerUse ⇒ 渲染应用清单 + 授权范围（整组授予）。
  //   插在普通分支之前：该请求**没有** questions，且普通分支会把它当成「允许执行这个工具」——
  //   那样用户看不到要控制哪些应用、能控制到什么程度。
  if (request.computerUse) {
    // AskUser / CU 路径都不支持「一键授权档位」（那是规则，不是应用授权）——后端若给这条路径带了
    //   suggestions，不静默吞掉，留痕。
    if (suggestionOptions.length > 0) {
      console.warn('[perm] 桌面控制弹窗收到 suggestions，本路径不支持一键授权档位，已忽略', request.requestId)
    }
    return (
      <ComputerUseApprovalPanel
        request={request}
        onDecision={(id, decision) => onDecision(id, decision)}
        onAbort={onAbort}
      />
    )
  }
  // ⛔ fail-loud：桌面控制的授权请求却**没有** computerUse ⇒ 不得回落普通分支放行
  //   （普通分支只显示工具名 ⇒ 用户看不到「要控制哪些应用、能控制到什么程度」就点「允许」= 盲授权）。
  if (isComputerUseTool(request.toolName)) {
    return (
      <ComputerUseApprovalError
        request={request}
        onDecision={(id, decision) => onDecision(id, decision)}
        onAbort={onAbort}
      />
    )
  }
  // [plan-approval] 计划审批：正文是主角，选项是「执行方式」，不是普通工具授权。
  if (isPlanApprovalRequest(request)) {
    if (suggestionOptions.length > 0) {
      console.warn('[perm] 计划审批使用专属卡片，忽略后端通用 suggestions', request.requestId)
    }
    return (
      <PlanApprovalCard
        request={request}
        onDecision={onDecision}
        onPlanClearContext={onPlanClearContext}
        onAbort={onAbort}
      />
    )
  }
  // 无 questions → 保持原 allow/deny 弹窗（+ 中止按钮）
  if (questions.length === 0) {
    return (
      <div className="permission-bubble">
        <div className="pb-title">
          {request.permissionDisplay?.title
            || (request.isLeaderInbox
              ? `队友请求使用工具：${request.permissionDisplay?.displayName || request.toolName}`
              : `需要权限：${request.permissionDisplay?.displayName || request.toolName}`)}
          <QueueBadge index={queueIndex} total={queueSize} />
        </div>
        {request.isLeaderInbox && <WorkerBadge color={request.workerBadgeColor} />}
        {planModeSwitchWarning && (
          <div className="pb-warning">
            当前处于计划模式：下方带「会退出计划模式」的档位会切换会话模式，之后 ExitPlanMode 将不可用。
          </div>
        )}
        {request.warning && <div className="pb-warning">{request.warning}</div>}
        {(request.permissionDisplay?.description || request.description) && (
          <div className="pb-desc">{request.permissionDisplay?.description || request.description}</div>
        )}
        {request.isLeaderInbox && (
          <div className="pb-worker">{request.workerName ? `来自 ${request.workerName}` : '来自队友（名称待后端补字段）'}</div>
        )}
        {request.reason && <div className="pb-reason">{request.reason.reason ?? request.reason.detail}</div>}
        {/* [D 未送达上抛] 渠道没送出去 ⇒ 明确告诉人，但⛔ 不影响决策（卡片照常等允许/拒绝）。
            只列失败的渠道（成功的不标）。 */}
        {request.deliveryFailures && request.deliveryFailures.length > 0 && (
          <div className="pb-warning" data-testid="pb-delivery-failure">
            通知未送达：{request.deliveryFailures.map(deliveryFailureText).join('；')}
          </div>
        )}
        <ToolInput toolInput={(request as PermissionRequestItem & { toolInput?: unknown }).toolInput} />
        {request.timestampMs && <div className="pb-waiting">已等待 {elapsed}s</div>}
        {/* 一键授权档位：suggestions 为空则不渲染（后端没给建议就没有第三档）。
            每档只回传它自己那条建议 —— 允许/拒绝两档仍走空 updates（不产生任何规则）。 */}
        {suggestionOptions.length > 0 && (
          <div className="pb-suggestions">
            {suggestionOptions.map((opt, i) => (
              <button
                key={`${opt.update.type}-${i}`}
                className="pb-suggestion"
                onClick={() => onDecision(request.requestId, 'allow', undefined, undefined, [opt.update])}
              >
                {opt.label}
              </button>
            ))}
          </div>
        )}
        <div className="pb-actions">
          {onAbort && <button className="pb-abort" onClick={onAbort}>中止</button>}
          <button onClick={() => onDecision(request.requestId, 'deny')}>拒绝</button>
          <button className="primary" onClick={() => onDecision(request.requestId, 'allow')}>允许</button>
        </div>
      </div>
    )
  }
  // AskUser 提问弹窗无「一键授权」语义（答案走 answers/annotations，不是规则）——
  //   后端若给这条路径带了 suggestions，不静默吞掉，留痕。
  if (suggestionOptions.length > 0) {
    console.warn('[perm] AskUser 弹窗收到 suggestions，本路径不支持一键授权档位，已忽略', request.requestId)
  }
  return <AskUserForm request={request} questions={questions} onDecision={onDecision} onAbort={onAbort}
    queueIndex={queueIndex} queueSize={queueSize} />
}

function AskUserForm({ request, questions, onDecision, onAbort, queueIndex, queueSize }: {
  request: PermissionRequestItem
  questions: AskUserQuestion[]
  onDecision: Props['onDecision']
  onAbort?: () => void
  queueIndex?: number
  queueSize?: number
}) {
  const [selected, setSelected] = useState<Selection>({})
  // 每题自定义答案（手动填写 · 对齐 CC AskUserQuestion 输入框）：非空时优先于选项选择
  const [customInputs, setCustomInputs] = useState<Record<string, string>>({})
  const elapsed = useElapsed(request.timestampMs)

  // ── [V333 AFK] 倒计时（默认关闭：request.afkTimeoutMs 缺省 ⇒ 不渲染、不计时）──
  //   语义对齐 CC：计时只发生在「卡片可见」期间；到点以 allow 提交**已作答项**
  //   （未答不填默认值）。卡片隐藏 / 切会话 / 卸载一律停表，绝不在后台补发一次 allow。
  const afkTimeoutMs = typeof request.afkTimeoutMs === 'number' && request.afkTimeoutMs > 0
    ? request.afkTimeoutMs : null
  const afkCountdownMs = typeof request.afkCountdownMs === 'number' && request.afkCountdownMs > 0
    ? request.afkCountdownMs : null
  const [afkRemainingMs, setAfkRemainingMs] = useState<number | null>(null)
  // 提交态与答案快照都用 ref：定时器回调拿到的必须是最新一次渲染的值
  const afkSubmittedRef = useRef(false)
  const answersRef = useRef<{ selected: Selection; customInputs: Record<string, string> }>({ selected: {}, customInputs: {} })
  answersRef.current = { selected, customInputs }

  /** 汇总当前答案（手动填写优先；多选逗号拼接）· 超时与手动确认共用同一口径。 */
  function collectAnswers(
    sel: Selection, customs: Record<string, string>,
  ): AskUserAnswers {
    const answers: AskUserAnswers = {}
    for (const q of questions) {
      const custom = (customs[q.question] ?? '').trim()
      if (custom) { answers[q.question] = custom; continue }
      const val = sel[q.question]
      if (val === undefined || (Array.isArray(val) && val.length === 0)) continue
      answers[q.question] = Array.isArray(val) ? val.join(', ') : val
    }
    return answers
  }

  useEffect(() => {
    if (afkTimeoutMs == null) {
      setAfkRemainingMs(null)
      return
    }
    // 计时基准 = 卡片挂载（requestId 变化 ⇒ 新卡片重新计时）
    const deadline = Date.now() + afkTimeoutMs
    let stopped = false
    let timer: number | undefined
    const stop = () => {
      stopped = true
      if (timer !== undefined) window.clearInterval(timer)
    }
    const tick = () => {
      if (stopped) return
      const left = deadline - Date.now()
      setAfkRemainingMs(Math.max(0, left))
      if (left > 0) return
      stop()
      if (afkSubmittedRef.current) return
      afkSubmittedRef.current = true
      // 只提交已答；未答不补默认值（对齐 CC answersToSubmit / skipped 遥测）
      const snap = answersRef.current
      onDecision(
        request.requestId, 'allow',
        collectAnswers(snap.selected, snap.customInputs), {},
        undefined, undefined, undefined,
        { afkTimeoutMs, isByCountdown: true },
      )
    }
    timer = window.setInterval(tick, 1000)
    // 页面隐藏 ⇒ 立刻停表且不再恢复（回到前台也不重启：宁可让用户手动答，也不后台误放行）
    const onVisibility = () => {
      if (document.visibilityState === 'hidden') {
        stop()
        setAfkRemainingMs(null)
      }
    }
    document.addEventListener('visibilitychange', onVisibility)
    tick()
    return () => {
      stop()
      document.removeEventListener('visibilitychange', onVisibility)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [afkTimeoutMs, request.requestId])

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
    if (afkSubmittedRef.current) return
    afkSubmittedRef.current = true
    // 多选逗号拼接（对齐后端 AskUserQuestionTool outputSchema）
    const answers = collectAnswers(selected, customInputs)
    // 无 preview/notes 收集 UI → annotations 恒空对象（后端仅非空才并入 Allow.updatedInput）
    const annotations: AskUserAnnotations = {}
    onDecision(request.requestId, 'allow', answers, annotations)
  }

  return (
    <div className="permission-bubble">
      <div className="pb-title">
        {request.isLeaderInbox ? `队友请求使用工具：${request.toolName}` : `需要权限：${request.toolName}`}
        <QueueBadge index={queueIndex} total={queueSize} />
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
      {/* [V333 AFK] 只在剩余时间进入阈值内才显示剩余秒数（对齐 CC showCountdown） */}
      {afkRemainingMs != null && afkCountdownMs != null && afkRemainingMs <= afkCountdownMs && (
        <div className="pb-waiting" data-testid="afk-countdown">
          剩余 {Math.ceil(afkRemainingMs / 1000)} 秒：无人回答将按已选内容继续
        </div>
      )}
      <div className="pb-actions">
        {onAbort && <button className="pb-abort" onClick={onAbort}>中止</button>}
        <button className="fm-btn" onClick={() => onDecision(request.requestId, 'deny')}>拒绝</button>
        <button className="fm-btn primary" onClick={confirm}>确认</button>
      </div>
    </div>
  )
}

/** [plan-approval] ExitPlanMode 专属审批（对齐 CC ExitPlanModePermissionRequest）。 */
function isPlanApprovalRequest(request: PermissionRequestItem): boolean {
  return request.toolName === 'ExitPlanMode'
}

/** toolInput 可能是对象或 JSON 字符串；解析失败返回 null。 */
function parseToolInputObject(request: PermissionRequestItem): Record<string, unknown> | null {
  const raw = (request as PermissionRequestItem & { toolInput?: unknown }).toolInput
  if (raw == null) return null
  const parsed = typeof raw === 'string' ? tryParse(raw) : raw
  return typeof parsed === 'object' && parsed !== null && !Array.isArray(parsed)
    ? (parsed as Record<string, unknown>)
    : null
}

/** 计划规模摘要（只做确定性统计，不调用模型）。 */
function summarizePlan(plan: string): { points: number; files: number } {
  const points = (plan.match(/^\s*(?:\d+\.|[-*])\s+/gm) ?? []).length
  const files = new Set(
    (plan.match(/`[^`\n]+`/g) ?? [])
      .map((s) => s.slice(1, -1).trim())
      .filter((s) => /[./\\]/.test(s) || /^[A-Z][A-Za-z0-9]+$/.test(s)),
  ).size
  return { points, files }
}

export function PlanApprovalCard({
  request,
  onDecision,
  onPlanClearContext,
  onAbort,
}: {
  request: PermissionRequestItem
  onDecision: Props['onDecision']
  onPlanClearContext?: Props['onPlanClearContext']
  onAbort?: () => void
}) {
  const elapsed = useElapsed(request.timestampMs)
  const toolInput = parseToolInputObject(request)
  const plan = typeof toolInput?.plan === 'string' ? toolInput.plan : ''
  const planFilePath = typeof toolInput?.planFilePath === 'string' ? toolInput.planFilePath : ''
  const [expanded, setExpanded] = useState(false)
  const [editing, setEditing] = useState(false)
  const [editedPlan, setEditedPlan] = useState(plan)
  const [feedbackOpen, setFeedbackOpen] = useState(false)
  const [feedback, setFeedback] = useState('')
  const [feedbackError, setFeedbackError] = useState(false)
  const [confirmClear, setConfirmClear] = useState(false)
  const summary = useMemo(() => summarizePlan(plan), [plan])

  const modeUpdate = (mode: 'acceptEdits' | 'default'): PermissionUpdate[] => [
    { type: 'setMode', mode, destination: 'session' },
  ]

  function approve(mode: 'acceptEdits' | 'default') {
    const planChanged = editing && editedPlan !== plan
    const updatedInput = planChanged ? { ...(toolInput ?? {}), plan: editedPlan, planFilePath } : undefined
    onDecision(
      request.requestId,
      'allow',
      undefined,
      undefined,
      modeUpdate(mode),
      feedback.trim() || undefined,
      updatedInput,
    )
  }

  function submitFeedback() {
    if (!feedback.trim()) {
      setFeedbackError(true)
      return
    }
    setFeedbackError(false)
    onDecision(request.requestId, 'deny', undefined, undefined, undefined, feedback.trim())
  }

  const planLines = plan.split('\n')
  // 空计划 split 会得到 ['']，展示口径必须是 0 行。
  const planLineCount = plan.length === 0 ? 0 : planLines.length
  const hiddenLines = plan.length === 0 ? 0 : Math.max(0, planLines.length - 8)
  const collapsed = plan.length === 0
    ? 'No plan found. Please write your plan to the plan file first.'
    : planLines.slice(0, 8).join('\n')
  // 计划文件行只显示基名（完整路径放 title），`/` 与 `\` 都要切。
  const planFileName = planFilePath.split(/[\\/]/).filter(Boolean).pop() ?? ''

  return (
    <div className="permission-bubble plan-approval-inline">
      {/* [plan-approval inline] 内联在消息流里：去掉浮卡的居中/阴影/限高，贴着工具卡 */}
      <style>{'.plan-approval-inline{width:100%;max-width:none;margin:0 0 12px;box-shadow:none;'
        + 'border-radius:0 8px 8px 0;border-left:3px solid var(--accent,#0f5fd4);'
        + 'max-height:none;overflow:visible;z-index:auto}'}</style>
      <div className="pb-title">准备好开始了吗？</div>
      <div className="pb-desc">
        {request.permissionDisplay?.description || `这是 ${request.toolName} 的计划：`}
      </div>
      {planFileName && (
        <div
          className="pb-worker"
          title={planFilePath}
          style={{ fontFamily: 'ui-monospace, Consolas, monospace' }}
        >
          计划文件：{planFileName}
        </div>
      )}
      <div
        className="pb-desc"
        style={{
          border: '1px solid var(--border, #eee)',
          borderRadius: 6,
          padding: '8px 10px',
          background: 'var(--surface-2, #eff2f8)',
          whiteSpace: 'pre-wrap',
          maxHeight: expanded ? 320 : 180,
          overflow: 'auto',
          fontFamily: 'ui-monospace, Consolas, monospace',
          fontSize: 12,
          lineHeight: 1.7,
        }}
      >
        {expanded || editing ? (editing ? editedPlan : plan) : collapsed}
        {!expanded && !editing && hiddenLines > 0 && (
          <div className="pb-plan-more">… 还有 {hiddenLines} 行未展开（点『查看全文』展开）</div>
        )}
      </div>
      <div className="pb-worker">
        正文 {plan.length} 字符 · 共 {planLineCount} 行 · {summary.points} 个要点 · {summary.files} 处文件引用
      </div>
      <div className="pb-actions" style={{ justifyContent: 'flex-start' }}>
        <button className="fm-btn" onClick={() => setExpanded((v) => !v)}>
          {expanded ? '收起全文' : '查看全文'}
        </button>
        <button
          className="fm-btn"
          onClick={() => {
            if (!editing) setEditedPlan(plan)
            setEditing((v) => !v)
            setExpanded(true)
          }}
        >
          {editing ? '取消编辑' : '编辑计划'}
        </button>
      </div>
      {editing && createPortal(
        <div
          onClick={() => setEditing(false)}
          style={{
            position: 'fixed', inset: 0, zIndex: 1200, display: 'flex', alignItems: 'center',
            justifyContent: 'center', padding: 24, background: 'rgba(20,24,31,.45)',
          }}
        >
          <div
            onClick={(e) => e.stopPropagation()}
            style={{
              width: 'min(920px, 92vw)', height: 'min(72vh, 720px)', background: '#fff',
              borderRadius: 12, display: 'flex', flexDirection: 'column', overflow: 'hidden',
              boxShadow: '0 24px 60px rgba(20,24,31,.28)',
            }}
          >
            <div style={{
              display: 'flex', alignItems: 'center', padding: '12px 16px',
              borderBottom: '1px solid #EEEEEE', fontSize: 14, fontWeight: 650, color: '#14181f',
            }}>
              编辑计划
              <span style={{ marginLeft: 8, fontSize: 12, fontWeight: 400, color: '#8a93a2' }}>
                左侧编辑，右侧实时预览 Markdown
              </span>
              <button
                onClick={() => setEditing(false)}
                style={{ marginLeft: 'auto', border: 0, background: 'transparent', color: '#8a93a2', cursor: 'pointer', fontSize: 13 }}
              >
                关闭
              </button>
            </div>
            <div style={{ flex: 1, display: 'flex', minHeight: 0 }}>
              <textarea
                value={editedPlan}
                onChange={(e) => setEditedPlan(e.target.value)}
                spellCheck={false}
                style={{
                  flex: 1, minWidth: 0, border: 0, borderRight: '1px solid #EEEEEE',
                  padding: '12px 14px', fontFamily: 'ui-monospace, Consolas, monospace',
                  fontSize: 13, lineHeight: 1.7, resize: 'none', outline: 'none',
                }}
              />
              <div style={{ flex: 1, minWidth: 0, overflow: 'auto', padding: '12px 16px' }}>
                <MarkdownTextV2 text={editedPlan} className="content md" />
              </div>
            </div>
            <div style={{
              display: 'flex', gap: 8, justifyContent: 'flex-end',
              padding: '10px 16px', borderTop: '1px solid #EEEEEE',
            }}>
              <button className="fm-btn" onClick={() => setEditing(false)}>取消</button>
              <button
                className="fm-btn primary"
                onClick={() => { setEditing(false); setExpanded(true) }}
              >
                保存计划
              </button>
            </div>
          </div>
        </div>,
        document.body,
      )}
      {request.timestampMs && <div className="pb-waiting">已等待 {elapsed}s</div>}
      <div className="pb-actions">
        {onAbort && <button className="pb-abort" onClick={onAbort}>中止</button>}
        <button className="fm-btn" onClick={() => setFeedbackOpen((v) => !v)}>不，继续规划</button>
        <button className="fm-btn" onClick={() => approve('default')}>手动批准编辑</button>
        {onPlanClearContext && (
          <button className="fm-btn" onClick={() => setConfirmClear(true)}>批准并清空上下文执行</button>
        )}
        <button className="fm-btn primary" onClick={() => approve('acceptEdits')}>批准并执行</button>
      </div>
      <div className="pb-hint">
        手动批准编辑 = 切到默认权限模式：读文件照常，之后每次编辑或运行命令都要你单独确认。
      </div>
      {confirmClear && onPlanClearContext && (
        <div className="pb-warning" style={{ padding: '0 0 10px' }}>
          清空上下文后执行？当前对话历史会保留为历史，计划会作为新会话的第一条输入。
          <div className="pb-actions" style={{ justifyContent: 'flex-start', marginTop: 8 }}>
            <button className="fm-btn" onClick={() => setConfirmClear(false)}>取消</button>
            <button
              className="fm-btn primary"
              onClick={() => {
                setConfirmClear(false)
                onPlanClearContext(editing && editedPlan !== plan ? editedPlan : plan)
              }}
            >
              确认，清空并执行
            </button>
          </div>
        </div>
      )}
      {feedbackOpen && (
        <div style={{ padding: '0 0 10px' }}>
          <textarea
            className="pb-custom-input"
            style={{ width: '100%', minHeight: 64 }}
            placeholder="告诉模型要改什么（提交后计划退回修改）"
            value={feedback}
            onChange={(e) => {
              setFeedback(e.target.value)
              if (feedbackError && e.target.value.trim()) setFeedbackError(false)
            }}
          />
          {feedbackError && <div className="pb-warning">请先写下要修改的内容。</div>}
          <div className="pb-actions" style={{ justifyContent: 'flex-start' }}>
            <button className="fm-btn" onClick={() => setFeedbackOpen(false)}>取消</button>
            <button className="fm-btn primary" onClick={submitFeedback}>提交修改意见</button>
          </div>
        </div>
      )}
    </div>
  )
}
