import { useEffect, useMemo, useState } from 'react'
import { useChatStore } from '@/stores/chatStore'
import { resolveCtxInfo } from '@/utils/contextUsage'
import { sessionApi } from '@/api/sessions'
import { tasksApi, type BackgroundTaskDto } from '@/api/tasks'
import { statsApi } from '@/api/stats'
import type { ChatMessageDto, SessionAgentUsage, SessionUsageResponse, StatsByModel, StatsResponse } from '@/api/types'
import { compactNumber } from '@/utils/format'
import { failureReasonOf, subagentListOfSession, useSubagentStore } from '@/stores/subagentStore'
import { SUBAGENT_STATUS_LABEL, formatSubagentStat } from '@/utils/subagentStats'

/** 千位以上紧凑显示（按模型明细/上下文条用 · 对齐 ContextAnalyzeModal fmt） */
const fmt = (n: number) => (n >= 1000 ? `${(n / 1000).toFixed(1)}k` : String(n))

/** byModel 行 token 总计 · 按 provider 分派（后端 byModel 每行 anthropic 标志）：
 *  anthropic（claude）4 项和（input 不含 cache hit）；deepseek（openai 协议）input 已含 cache hit，
 *  只能 input+output，4 项和会双计 cache。 */
const modelRowTotal = (r: StatsByModel) => r.anthropic
  ? r.inputTokens + r.outputTokens + r.cacheReadInputTokens + r.cacheCreationInputTokens
  : r.inputTokens + r.outputTokens

/** 稳定空数组（避免 selector `?? []` 每次返回新引用触发无限重渲染）。 */
const EMPTY_MESSAGES: ChatMessageDto[] = []

/** 条形图宽度百分比：v>0 时至少 2%（小值可见），否则 0（零数据不显示条）。 */
const barPct = (v: number, max: number) => (max > 0 && v > 0 ? Math.max(2, (v / max) * 100) : 0)

/** 调用类型中文名（对齐后端 UsageCategory 枚举 · 未映射的值原样显示，不吞不猜）·
 *  下称 `CATEGORY_LABEL[x] ?? x` 直接内联使用。 */
const CATEGORY_LABEL: Record<string, string> = {
  MAIN: '主对话',
  SUBAGENT: '子代理',
  TEAMMATE: '队友',
  COMPACT: '压缩',
  BACKGROUND: '后台任务',
  HOOK: 'Hook',
  SIDE_CALL: '旁路调用',
  CLASSIFIER: '权限分类器',
  UNKNOWN: '未归类',
}

/**
 * 归属标签（后端 label）→ 中文用途名。**只覆盖本仓自己生成的 slug**（旁路用途名 / 压缩标签 /
 * 内置 agent 类型名）；用户自定义的子代理名（中文或英文）与未知新标签**原样显示**（不吞、不猜）。
 *
 * <p>WHY 需要它：`按代理层级` 里 `general-purpose` / `compact-fork` / `skill-catalog-summary`
 * 这类 slug 用户看不出含义（实测反馈「名称人类不可读」）。类别维度已有 {@link CATEGORY_LABEL}，
 * 本表只补「具体用途」这一层；渲染统一走 {@link usageNodeName}（类别 · 用途）。
 *
 * <p>维护：后端新增 label 字面量时在此补一行即可；漏补只会退回原样 slug，不会显示错误含义。
 */
const LABEL_GLOSS: Record<string, string> = {
  // 内置子代理类型名（AgentDefinitionRegistry：built-in agents）
  'general-purpose': '通用型',
  'Explore': '探索型',
  'Plan': '规划型',
  'statusline-setup': '状态栏配置',
  'claude-code-guide': '使用指南型',
  // 子代理自身产生的旁路（SubagentExecutor 摘要）
  'agent-summary': '子代理摘要',
  // 压缩
  'compact-fork': 'fork 摘要',
  'compact-streaming-summary': '流式摘要',
  // 旁路单发（LlmSideCallClient 调用点自述名）
  'title-generation': '会话标题生成',
  'web-search-summary': '网页搜索摘要',
  'web-fetch-summary': '网页抓取摘要',
  'skill-catalog-summary': '技能目录摘要',
  'vision-analyze': '图片分析',
  'tool-use-summary': '工具调用摘要',
  'prompt-suggestion-fork': '提示建议',
  'oa-review-notes': 'OA 评审笔记',
  'memdir-relevance': '记忆相关性检索',
  'magic-doc-update': '文档更新',
  'btw-side-question': '旁问（/btw）',
  'away-summary': '离开摘要',
  'skill-improvement': '技能改进建议',
  'permission-explainer': '权限解释',
  'mcp-datetime-parse': 'MCP 时间解析',
  'exec-prompt-hook': 'hook 提示执行',
  // 权限分类器
  'yolo-classifier-1stage': '一级判定',
  'yolo-classifier-stage': '判定',
}

/**
 * 节点可读名称：{@code 类别中文名 ·（用途中文名 | 原样 label）}；无 label → 只给类别名。
 * 类别与用途同义时（如 label 恰为类别名）不重复拼接。
 */
const usageNodeName = (category: string, label: string | null) => {
  const cat = CATEGORY_LABEL[category] ?? category
  if (!label) return cat
  const gloss = LABEL_GLOSS[label] ?? label
  return gloss === cat ? cat : `${cat} · ${gloss}`
}

/** 质量标志小字（估算/中断/失败 · 仅列出 >0 的项；全 0 → 空串，不渲染那行）。
 *  这三项决定「这个数能不能当账单」：估算值不可对账、中断/失败的上游可能仍计费。 */
const usageFlagsText = (r: { estimatedCalls: number; abortedCalls: number; failedCalls: number }) => {
  const parts: string[] = []
  if (r.estimatedCalls > 0) parts.push(`估算 ${r.estimatedCalls} 次`)
  if (r.abortedCalls > 0) parts.push(`中断 ${r.abortedCalls} 次`)
  if (r.failedCalls > 0) parts.push(`失败 ${r.failedCalls} 次`)
  return parts.join(' · ')
}

/** [usage-gateway] 记账侧行文本：{@code ¥1.23 · 13.4k tokens · 8 次模型调用}（+ 估算/中断/失败标志）。
 *  token 合计 = input + output（cache 两列单列，不并入 —— 并入会双计；口径同统计页）。
 *  ⛔ {@code calls} 是**模型调用次数**，与 task 事件的「次调用」（工具调用次数）不是同一个量。 */
const ledgerUsageText = (a: SessionAgentUsage) => {
  const parts = [`¥${a.costYuan.toFixed(2)}`,
    `${compactNumber(a.inputTokens + a.outputTokens)} tokens`,
    `${a.calls} 次模型调用`]
  const flags = usageFlagsText(a)
  return flags ? `${parts.join(' · ')} · ${flags}` : parts.join(' · ')
}

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

  // byCategory/byAgent 是后加的维度：老后端不返回 → 空列表（不炸整个统计页）
  const { totals, byDay, byModel, byCategory = [], byAgent = [] } = data
  const maxDayTokens = byDay.reduce((m, d) => Math.max(m, d.tokenCount), 0)
  const maxModelTokens = byModel.reduce((m, r) => Math.max(m, modelRowTotal(r)), 0)
  // [usage-gateway] 明细两维度的条形按**金额**取比例（问题是「谁烧钱」而非「谁 token 多」）·
  //   全 0 金额（未配价）→ max=0 → barPct 返回 0，不显示条（不伪造比例）。
  const maxCategoryCost = byCategory.reduce((m, r) => Math.max(m, r.costYuan), 0)
  const maxAgentCost = byAgent.reduce((m, r) => Math.max(m, r.costYuan), 0)
  // 层级行显示名 = 「类别 · 用途」中文；同类同用途有多个节点时追加序号（1/2/3…）区分
  //   —— ⛔ 不用 agentId / UUID（对用户无意义）。
  const agentNameTotals = new Map<string, number>()
  for (const r of byAgent) {
    const k = `${r.category}|${r.label ?? ''}`
    agentNameTotals.set(k, (agentNameTotals.get(k) ?? 0) + 1)
  }
  const agentNameSeen = new Map<string, number>()
  const agentDisplayRows = byAgent.map((r) => {
    const k = `${r.category}|${r.label ?? ''}`
    const idx = (agentNameSeen.get(k) ?? 0) + 1
    agentNameSeen.set(k, idx)
    const suffix = (agentNameTotals.get(k) ?? 1) > 1 ? `（${idx}）` : ''
    return { row: r, name: usageNodeName(r.category, r.label) + suffix }
  })

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
          const total = modelRowTotal(r)
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

      {/* [usage-gateway] 按调用类型：**哪类调用烧钱**（llm_usage_calls 每 call 明细聚合 ·
          条形按金额取比例）。与「按模型」互补：模型维度看不出子代理/压缩/旁路各烧了多少。 */}
      <div className="uc-section">
        <div className="uc-section-title">按调用类型</div>
        {byCategory.length === 0 && <div className="uc-muted">暂无明细数据</div>}
        {byCategory.map((r) => {
          const flags = usageFlagsText(r)
          return (
            <div key={r.category} className="uc-stats-model">
              <div className="uc-stats-bar-row">
                <span className="uc-stats-bar-label uc-stats-model-name" title={r.category}>
                  {CATEGORY_LABEL[r.category] ?? r.category}
                </span>
                <div className="uc-stats-bar">
                  <div className="uc-stats-bar-track">
                    <div className="uc-stats-bar-fill" style={{ width: `${barPct(r.costYuan, maxCategoryCost)}%` }} />
                  </div>
                  <span className="uc-stats-bar-val">
                    ¥{r.costYuan.toFixed(2)} · {r.calls} 次 · {fmt(r.inputTokens + r.outputTokens)} tok
                  </span>
                </div>
              </div>
              {flags && <div className="uc-stats-model-detail">其中 {flags}</div>}
            </div>
          )
        })}
      </div>

      {/* [usage-gateway] 按代理层级：**谁的花费**（depth 缩进还原父子链 · 后端按 depth 升序返回，
          父在前）。名称 = 「类别 · 用途」中文（见 usageNodeName；label 是后端自述 slug，用户读不出含义）；
          同类同用途多个节点加序号（1/2/3…）区分。⛔ 不显示 agentId / UUID —— 对用户无意义。 */}
      <div className="uc-section">
        <div className="uc-section-title">按代理层级</div>
        {byAgent.length === 0 && <div className="uc-muted">暂无明细数据</div>}
        {agentDisplayRows.map(({ row: r, name }) => {
          const flags = usageFlagsText(r)
          return (
            <div key={r.agentId} className="uc-stats-model">
              <div className="uc-stats-bar-row">
                <span
                  className="uc-stats-bar-label uc-stats-tree-label"
                  style={{ paddingLeft: r.depth * 10 }}
                  title={`${CATEGORY_LABEL[r.category] ?? r.category}${r.label ? ` · ${r.label}` : ''}`}
                >
                  {name}
                </span>
                <div className="uc-stats-bar">
                  <div className="uc-stats-bar-track">
                    <div className="uc-stats-bar-fill" style={{ width: `${barPct(r.costYuan, maxAgentCost)}%` }} />
                  </div>
                  <span className="uc-stats-bar-val">
                    ¥{r.costYuan.toFixed(2)} · {r.calls} 次 · {fmt(r.inputTokens + r.outputTokens)} tok
                  </span>
                </div>
              </div>
              {flags && <div className="uc-stats-model-detail uc-stats-tree-detail">{flags}</div>}
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
 *   modelUsage 快照 · 会话累计）+ 当前上下文条（末条 assistant 的快照口径 contextTokensUsed/contextWindow/
 *   percentLeft · 由 resolveCtxInfo 单点解析，与 Composer 上下文条同源；无快照即不显示该条）。
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
  // 当前上下文条：**只认快照口径**（resolveCtxInfo 单点，与 Composer 同源）。
  //   [P3-d 口径统一 2026-09-11] 删除「回落 token_warning」：其 tokenUsage 是本地估算、percentLeft 是
  //   阈值相对口径（分母 autoCompactThreshold ≠ 窗口），与服务端真实 usage 不同源 → 混显示会让弹窗与
  //   Composer 上下文条各说一套；无快照即不显示该条（对齐 CC getCurrentUsage undefined）。
  //   [口径拉齐 2026-10-10 → 口径反转 2026-10-10] 取数 = 与 Composer 底部同类（全列表单点）；
  //   口径经用户实测修正：**去掉「只统计用户来源」过滤**——cron/任务通知唤醒的主会话轮属于主会话
  //   对话，照常驱动本条（用户裁定「前端不能停止」）。
  const msgsOfSession = useChatStore((s) => s.messages[activeSessionId] ?? EMPTY_MESSAGES)
  const ctxInfo = resolveCtxInfo(msgsOfSession)
  const ctxUsed = ctxInfo?.used ?? null
  const ctxWindow = ctxInfo?.window ?? null
  const ctxPct = ctxInfo?.pct ?? null
  const ctxFill = ctxUsed != null && ctxWindow != null && ctxWindow > 0
    ? Math.max(0, Math.min(100, (ctxUsed / ctxWindow) * 100))
    : null
  // 全部会话合计：sessions 表累计值求和（只读展示 · 对齐「所有会话」行式，金额 accent 色）
  const totalCostSum = sessions.reduce((acc, s) => acc + (s.totalCostYuan ?? 0), 0)
  const totalTokensSum = sessions.reduce((acc, s) => acc + (s.totalTokens ?? 0), 0)
  // [D2] 子代理（本会话）：身份来自 subagentStore（task_started 登记 / task_progress·task_notification
  //   补 usage），统计行展示口径 = utils/subagentStats 单点。⚠️ selector 只取 bySession 原引用，
  //   派生列表走 useMemo —— 直接在选择器里返回新数组会导致 zustand 无限重渲染（仓内既有坑）。
  const subagentBySession = useSubagentStore((s) => s.bySession)
  const subagents = useMemo(
    () => subagentListOfSession(subagentBySession, activeSessionId),
    [subagentBySession, activeSessionId],
  )
  // [usage-gateway] 记账侧权威用量（GET /sessions/{id}/usage）：子代理行的 token/费用/模型调用次数
  //   来自明细表（每次 LLM 调用一行），比 task 事件的进度估算更全 —— 失败/中断路径的 task 事件常常
  //   没有 usage（用户实测表现为「统计不可用」）。老后端无该端点 → 静默回退 task 事件统计。
  const [sessionUsage, setSessionUsage] = useState<SessionUsageResponse | null>(null)
  // 任务注册表（内存态 · /api/v1/tasks?sessionId=）：task_id → 子代理名称（description，与任务面板/
  //   子代理卡同一口径）——仅记账可得、本端无实时身份的行也要显示**子代理名称**而不是类型名。
  const [sessionTasks, setSessionTasks] = useState<BackgroundTaskDto[]>([])
  useEffect(() => {
    let cancelled = false
    setSessionUsage(null)
    setSessionTasks([])
    sessionApi.usage(activeSessionId)
      .then((u) => { if (!cancelled) setSessionUsage(u) })
      .catch(() => { /* 静默：无该端点/失败 → 行内回退 task 事件统计（不阻塞弹窗） */ })
    tasksApi.list(activeSessionId)
      .then((t) => { if (!cancelled) setSessionTasks(t) })
      .catch(() => { /* 静默：拿不到名称 → 回退「类别 · 用途」 */ })
    return () => { cancelled = true }
  }, [activeSessionId])

  /** task_id → 子代理名称（任务注册表的 description）。 */
  const taskNameByTaskId = useMemo(() => {
    const map = new Map<string, string>()
    for (const t of sessionTasks) {
      if (t.description) map.set(t.id, t.description)
    }
    return map
  }, [sessionTasks])

  // 关联表：task_id → 记账节点（键是运行期产生的动态 id ⇒ 用 Map，非静态字面量表；
  //   后端已把 agentId 换算成 task_id，前端不解析 agentId 格式）
  const usageByTaskId = useMemo(() => {
    const map = new Map<string, SessionAgentUsage>()
    for (const a of sessionUsage?.agents ?? []) {
      if (a.taskId) map.set(a.taskId, a)
    }
    return map
  }, [sessionUsage])

  // 只有记账、没有实时身份的子代理节点（主 agent 归「当前会话」块）：刷新/换端后 subagentStore
  //   （localStorage）可能没有这些行，但记账数据仍在 —— 照常列出。
  //   判据 = `taskId != null`（后端只在「真 agent 任务」上给出该键：子代理/fork 的 a+16hex ↔ 任务注册表
  //   task_id），或归类本就是子代理/队友。⛔ 不可用 category 单独判定：节点类别取该 agent **首行**，
  //   而子代理的首行可能是它自己的旁路调用（实测：归一后子代理节点首行是 skill-catalog-summary）
  //   ⇒ 只按类别过滤会把真子代理漏掉；同理会话根自身发起的旁路（无 taskId、类别 SIDE_CALL）不得列入。
  const ledgerOnlyAgents = useMemo(() => {
    const known = new Set(subagents.map((s) => s.taskId).filter((t): t is string => !!t))
    return (sessionUsage?.agents ?? []).filter((a) => a.depth >= 1
      && (a.taskId != null || a.category === 'SUBAGENT' || a.category === 'TEAMMATE')
      && (!a.taskId || !known.has(a.taskId)))
  }, [sessionUsage, subagents])

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

          {/* [D2] 子代理（本会话）· 每行 = 名字 · 状态 · tokens · 调用次数 · 时长；
              失败行附失败原因小字（无原因文本时不渲染那行）。数据源 = /topic/tasks 的 task 事件 usage。
              [usage-gateway] 记账侧权威值优先：按 taskId 关联 GET /sessions/{id}/usage 的 agent 节点，
              有则用「¥ · tokens · 次模型调用」替代 task 事件的进度估算（后者失败/中断路径常缺数据，
              实测表现为「统计不可用」）；无关联 → 照旧回退 task 事件统计。 */}
          <div className="uc-section">
            <div className="uc-section-title">子代理（本会话）</div>
            {subagents.length === 0 && ledgerOnlyAgents.length === 0 && (
              <div className="uc-muted">暂无子代理记录</div>
            )}
            {subagents.map((sub) => {
              const node = sub.taskId ? usageByTaskId.get(sub.taskId) : undefined
              const stats = node ? ledgerUsageText(node) : formatSubagentStat(sub.usage)
              const reason = failureReasonOf(sub)
              return (
                <div key={sub.taskId ?? sub.name} className="uc-subagent-item">
                  <div className="uc-row">
                    <span className="uc-name" title={sub.name}>@{sub.name}</span>
                    <span className="uc-tokens">
                      {SUBAGENT_STATUS_LABEL[sub.status]} · {stats ?? '统计不可用'}
                    </span>
                  </div>
                  {reason && <div className="uc-subagent-reason">失败原因：{reason}</div>}
                </div>
              )
            })}
            {/* 仅记账可得的子代理节点（本端没有实时身份/事件）：仍列出用量，名称走 usageNodeName
                （类别 · 用途中文），⛔ 不伪造「运行中/已完成」—— 本端不知道它的实时状态。 */}
            {ledgerOnlyAgents.map((a) => {
              // 名称优先取任务注册表的 description（= 子代理名称，与其他界面同口径）；
              //   任务已出注册表（重启/淘汰）→ 回退「类别 · 用途」中文，仍可读懂是什么
              const taskName = a.taskId ? taskNameByTaskId.get(a.taskId) : undefined
              return (
                <div key={a.agentId} className="uc-subagent-item">
                  <div className="uc-row">
                    <span className="uc-name" title={taskName ?? a.label ?? a.category}>
                      {taskName ? `@${taskName}` : usageNodeName(a.category, a.label)}
                    </span>
                    <span className="uc-tokens">{ledgerUsageText(a)}</span>
                  </div>
                </div>
              )
            })}
          </div>
          </>
          )}
          {tab === 'stats' && <StatsSection />}
        </div>
      </div>
    </div>
  )
}
