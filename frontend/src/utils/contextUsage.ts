import type { TokenWarningEvent } from '../api/types'

/**
 * 上下文「已用 / 窗口（剩余%）」的**快照口径**纯函数（Composer 上下文条 / 用量弹窗共用）。
 *
 * <p>[P3-d 口径统一 2026-09-11] 后端把上下文统计收敛成两层口径，前端**只准消费快照口径**：
 * <ul>
 *   <li><b>快照口径（唯一权威 · 本模块）</b>：服务端真实 usage（末条带 usage 的 assistant 消息，
 *       协议分派）+ 模型原始 {@code max_context_tokens} + 窗口相对百分比。来源 = {@code message.usage} /
 *       {@code message.complete} 透传到消息（或流式块）的 {@code contextTokensUsed/contextWindow/percentLeft}
 *       （后端 {@code ContextUsageCalculator.Snapshot} 单点产出）。</li>
 *   <li><b>阈值相对口径（禁止当余量渲染）</b>：{@code token_warning.percentLeft} —— 后端
 *       {@code CompactThresholdSystem.TokenWarningState.thresholdRelativePercentLeft}，分母是
 *       autoCompactThreshold（不是上下文窗口），{@code token_warning.tokenUsage} 还是**本地估算**
 *       （非服务端真实 usage）。它只服务 warning/error/auto/blocking 四态判定。</li>
 * </ul>
 * 两套口径数值不同（分母不同）且数据源不同源，混着显示会让同一位置忽大忽小；故本模块只认快照口径，
 * 无快照即返回 null（调用方不显示该行）—— 与 CC {@code getCurrentUsage()} 找不到带 usage 的消息时
 * 指示器归零同语义，也是压缩后「数字降下来」的前端表现。
 */
export interface CtxSnapshot {
  /** 上下文已用 tokens（服务端真实 usage · 协议分派） */
  contextTokensUsed?: number | null
  /** 模型上下文窗口（tokens · 模型原始 max_context_tokens） */
  contextWindow?: number | null
  /** 窗口相对剩余百分比（0-100 · 无 usage 时 null） */
  percentLeft?: number | null
}

/** 快照口径的展示三元组（已用 / 窗口 / 剩余%）。 */
export interface CtxInfo {
  used: number
  window: number
  pct: number | null
}

/**
 * 从**快照源列表倒序**取最新一条完整快照（两字段齐备才算一条）。
 *
 * <p>倒序 WHY（沿用原 Composer 扫描语义）：实时的扫描源 = {@code [...msgs, ...liveBlocks]}
 * —— 流式块挂在尾部，从尾向前才能命中「本轮最新」那一条；多轮 turn 内每条 assistant 的
 * {@code message.usage} 到达即刷新，turn 结束清流后由 msgs 兜底。
 *
 * <p>返回 null = 本会话当前**无快照**（如 /compact 后 boundary 之后尚无新一轮 assistant）→
 * 调用方隐藏该行，**不得**回落 {@code token_warning}（阈值相对口径 + 本地估算，见模块头注释）。
 *
 * @param sources 快照源（消息 / 流式块 / 单条消息；null/undefined 元素跳过）
 * @return 最新快照三元组；无完整快照 → null
 */
export function resolveCtxInfo(
  sources: ReadonlyArray<CtxSnapshot | null | undefined>,
): CtxInfo | null {
  for (let i = sources.length - 1; i >= 0; i--) {
    const s = sources[i]
    if (s && s.contextTokensUsed != null && s.contextWindow != null) {
      return { used: s.contextTokensUsed, window: s.contextWindow, pct: s.percentLeft ?? null }
    }
  }
  return null
}

/** [D1 usage-source] {@code message.usage} 事件「用户自己的请求」标记值（与后端
 *  {@code MessageUsageEvent.SOURCE_USER} 同值域；含 busy-queued 排队消息）。 */
export const USAGE_SOURCE_USER = 'user'

/** [D1 usage-source] {@code message.usage} 事件「后台来源」标记值（与后端 MessageUsageEvent.source 同值域）。
 *  后台来源 = CronIdleExecutor 起的 cron 调度 / 任务通知 run（不是用户自己的请求）。 */
export const USAGE_SOURCE_BACKGROUND = 'background'

/**
 * [D1 usage-source] 从消息列表派生「后台来源 flow」id 集合（**F5 / 重拉态**的判据来源）。
 *
 * <p><b>WHY 需要它</b>：{@code usageSource} 只在实时 {@code message.usage} 事件上（前端内存，
 * 事件 → 流式块 → 消息）；F5 / 重连补偿 / 切会话重拉 / compact 后重拉拿到的都是 **DB 行**，
 * 没有该字段 ⇒ 后台那轮的 usage 又会被当成用户的最新一条（与用户原始报障同形）。
 *
 * <p><b>判据（DB 权威列，非启发式）</b>：assistant 行的 {@code user_message_id}（V47）指向该轮的
 * user 行；cron 调度 / 任务通知这类<b>后台来源</b>的注入 user 行恒 {@code is_meta=true}（V51；
 * 生产写入点 = {@code CronIdleExecutor} 的 prompt 落库分支 / mid-turn 注入落库分支），而用户自己的
 * prompt 与 busy-queued 排队消息恒 {@code is_meta=false}。hook / skill_listing / slash-meta 等
 * 其它 is_meta 行不会成为该轮 assistant 的 user_message_id（监听器只对「已登记的 mid-turn 注入项」
 * 推进 lastUserMessageId），故不会误判。
 *
 * <p>返回集只含 user 行的 id，供 {@link isUserUsageSource} 做 O(1) 判定。
 *
 * @param messages 消息列表（重拉尾页 / 内存列表；null 项跳过）
 * @return 后台来源 flow id 集合（无 is_meta=true 的 user 行 → 空集）
 */
export function backgroundUsageFlowIds(
  messages: ReadonlyArray<{ id?: string | null; role?: string | null; isMeta?: boolean | null } | null | undefined>,
): Set<string> {
  const out = new Set<string>()
  for (const m of messages) {
    if (m && m.role === 'user' && m.isMeta === true && m.id) out.add(m.id)
  }
  return out
}

/**
 * [D1] 该条 usage/快照是否来自「用户自己的请求」（底部数字只统计它）。
 *
 * <p><b>WHY</b>：同会话后台任务（cron 调度 / 任务通知 run）复用会话 stream topic 推
 * {@code message.usage}（实测 63% 事件出自 {@code cron-idle-*} 线程）→ 底部「缓存% / 当前上下文」
 * 读「最后一条带 usage 的条目」时被后台任务那轮顶掉，数字与用户操作无关地跳变。来源标记由后端在
 * usage-push 时点打（{@code MessageUsageEvent.source}），经 {@code applyMessageUsage → 流式块 →
 * 消息}一路透传到同名的 {@code usageSource} 字段。
 *
 * <p><b>判定优先级</b>：① 显式 {@code usageSource}（live 事件 / 块转消息）> ② 派生
 * （{@code userMessageId ∈ backgroundFlowIds}，重拉态用，见 {@link backgroundUsageFlowIds}）>
 * ③ 缺省计入。显式优先是有意的：live 事件的来源是 run 级事实（谁起的这轮），比 DB 派生更准
 * （HANDOFF：显式标记 vs 派生判据不得互相覆盖）。
 *
 * <p><b>缺省语义（向后兼容 · 不得隐藏既有数据）</b>：无标记且无法派生（无 userMessageId / 该 user
 * 行不在本列表窗口内）一律按「用户来源」计 —— 旧帧（后端未升级）、complete 兜底、窗口外的行都
 * 不得因过滤而凭空消失。
 *
 * @param s 带 usageSource / userMessageId 的消息或流式块（null/undefined → 按用户来源计）
 * @param backgroundFlowIds 后台来源 flow id 集合（{@link backgroundUsageFlowIds} 产出；缺省/空 = 不派生）
 * @return true = 计入底部数字
 */
export function isUserUsageSource(
  s: { usageSource?: string | null; userMessageId?: string | null } | null | undefined,
  backgroundFlowIds?: ReadonlySet<string> | null,
): boolean {
  if (s?.usageSource === USAGE_SOURCE_BACKGROUND) return false
  if (s?.usageSource === USAGE_SOURCE_USER) return true
  // 缺省（重拉态 / 旧帧）→ 按该轮 user 行是否为后台注入行派生
  const flowId = s?.userMessageId
  return !(flowId && backgroundFlowIds && backgroundFlowIds.has(flowId))
}

/** 阈值告警横幅文案（唯一文案 · 不含百分比）。 */
export const TOKEN_WARNING_TEXT = '上下文接近自动压缩窗口'

/**
 * token_warning 横幅文案（纯函数 · 决定「显示什么 / 是否显示」）。
 *
 * <p>WHY 不显示百分比（[P3-d] 契约）：{@code token_warning.percentLeft} 是**阈值相对**口径
 * （分母 = autoCompactThreshold 或有效窗口），{@code tokenWarning.tokenUsage} 是本地估算 ——
 * 把它当「上下文剩余 %」渲染是错的（同一时刻与 Composer 快照口径的数值对不上）。
 * 本横幅的职责只是「上下文接近自动压缩窗口」这一**文字**提示（backed by 四态判定的
 * {@code isAboveWarningThreshold}）；要显示数字就走快照口径（{@link resolveCtxInfo}）。
 *
 * @param warning 该会话的 token_warning（键不存在传 null）
 * @return 文案；null = 不渲染（无告警 / 压缩已成功 suppressed / **载荷无真实用量数字**）
 */
export function tokenWarningBannerText(
  warning: TokenWarningEvent | null | undefined,
): string | null {
  if (!warning || warning.suppressed) return null
  // 「有真实用量」判据（产出侧锚点）：
  //   · 真数字 = LlmAgentLoop.java:6884-6892 在 isAboveWarningThreshold() 时推的
  //     tokenUsage/effectiveWindow/percentLeft；
  //   · 占位载荷 = CompactWarningState.java:291 publishTokenWarning(pushCtx, value, 0L, 0L, null)
  //     —— 它只表示「抑制开关翻转」，恒 tokenUsage=0，不是用量告警。
  // 判据只用 tokenUsage（> 0 即真实），⛔不得用 contextWindow：
  //   effectiveWindow = min(模型窗口, settings.auto_compact_window) − reserved，用户把
  //   auto_compact_window 配得极小时 effectiveWindow 可 ≤ 0 ⇒ 用它会静默吞掉真告警。
  if (warning.tokenUsage == null || warning.tokenUsage <= 0) return null
  return TOKEN_WARNING_TEXT
}
