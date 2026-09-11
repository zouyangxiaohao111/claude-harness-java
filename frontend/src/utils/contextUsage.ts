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
 * @return 文案；null = 不渲染（无告警 / 压缩已成功 suppressed）
 */
export function tokenWarningBannerText(
  warning: TokenWarningEvent | null | undefined,
): string | null {
  if (!warning || warning.suppressed) return null
  return TOKEN_WARNING_TEXT
}
