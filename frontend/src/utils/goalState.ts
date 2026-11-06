/**
 * 目标（/goal）UI 态类型 · **按会话键控**（chatStore.goal[sessionId]）。
 *
 * <p>[多会话隔离] 与 `compact` / `tokenWarning` 同款：后端推的是**会话级** topic
 * （`/topic/sessions/{sid}/goal`），本仓是多会话 Web —— 全局单例会让「A 会话在追目标、
 * 切到 B」时 B 显示 A 的目标。故按 sessionId 键控。
 *
 * <p>[后端契约 · 对齐 CC claude.exe v2.1.318 的 activeGoal]
 * 形状与 `sessions.goal` 列一一对应（`SessionGoal.java`）：
 * ```
 * { condition, phase, iterations, setAt, tokensAtStart, lastReason, retryCount, retryLimit, tokensUsed }
 * ```
 * ⚠️ `tokensAtStart` 是**设定时刻的基线快照**（列里存的），`tokensUsed` 才是目标期间
 * **真实消耗**（后端按 `llm_usage_calls.started_at >= setAt` 求和，载荷级字段、不落列）。
 * `phase` ∈ `active | paused | achieved | impossible`。
 * `paused` 来自 CC v2.1.318 的 `kind:"pause"`（撞 block-cap / 评估器超时 / 用户排队打断），
 * **不是**终态：用户发下一条消息即自动恢复（后端 `GoalService.resumeFromPause`）。
 */
export interface GoalUiState {
  /** 目标条件原文（CC 语义，上限 4000 字符） */
  condition: string
  /**
   * active=追之中 / paused=已暂停（发消息继续）/ achieved=已达成 / impossible=无法达成
   *
   * <p>[B4 2026-11-04] `paused` 对齐 CC v2.1.318 的 `kind:"pause"` 语义：
   * 评估器连续判「未达成」撞 block-cap（CC 默认 8 次）后，目标不是失败而是暂停，
   * 用户发下一条消息即自动恢复（后端 `GoalService.resumeFromPause`）。
   */
  phase: 'active' | 'paused' | 'achieved' | 'impossible'
  /** 已评估轮数（**纯记账**，不参与判断；CC 的 goal 对象里没有上限字段） */
  iterations: number
  /** 设定时刻（epoch 毫秒） */
  setAt: number
  /** 设定时的输出 token 累计值（CC `DA()` 口径 = 只算 output token） */
  tokensAtStart: number
  /** 最近一次评估器返回的 reason（= 注入下一轮的那句话，也是「最近一次判定」的来源） */
  lastReason?: string | null
  /**
   * [B6] 已自动重试次数（0 = 从未重试）。
   *
   * <p>这是**运行时状态**（后端 `retryCount`），纯展示用途：评估器自身失败
   * （超时/JSON 解析失败/模型报错）时后端按 60/300/900 秒三档自动重试；
   * 用户在目标详情里能看到「已自动重试 2/3 次」，知道为什么目标还没推进。
   * ⛔ 这不是配置项 —— 重试间隔是后端常量（对齐 CC）。
   */
  retryCount?: number
  /** [B6] 自动重试上限（后端常量；前端不硬编码，随载荷走）。 */
  retryLimit?: number
  /**
   * 目标期间**真实消耗**的输出 token（后端 `llm_usage_calls.started_at >= setAt` 求和）。
   *
   * <p>与 `tokensAtStart`（设定时刻基线快照）语义不同，⛔ 不要混用。
   * `null`/`undefined` = 后端取不到（统计未接线 / 查询异常）⇒ **不渲染该字段**，不显示 0。
   */
  tokensUsed?: number | null
}

/** 无目标的读取回落（模块级稳定引用 —— 避免每次新对象触发无谓重渲）。 */
export const EMPTY_GOAL: GoalUiState | null = null

/** 是否处于「追之中」（= 该显示蓝色条 + 扫光条）。 */
export function isGoalActive(g: GoalUiState | null | undefined): boolean {
  return !!g && g.phase === 'active'
}

/** 目标持续时长（毫秒）· 用于目标头的「· 4m12s」。 */
export function goalDurationMs(g: GoalUiState | null | undefined, now: number): number {
  if (!g) return 0
  return Math.max(0, now - g.setAt)
}

/** 毫秒 → 「4m12s」形态（照 MessageList.formatTurnDuration 的口径）。 */
export function formatGoalDuration(ms: number): string {
  if (ms < 0) ms = 0
  const totalSec = Math.floor(ms / 1000)
  if (totalSec < 60) return `${totalSec}s`
  const min = Math.floor(totalSec / 60)
  const sec = totalSec % 60
  if (min < 60) return `${min}m${String(sec).padStart(2, '0')}s`
  const hour = Math.floor(min / 60)
  const m = min % 60
  return `${hour}h${String(m).padStart(2, '0')}m`
}

/** 输出 token 累计 → 「12.4k」形态（只算 output token，CC `DA()` 口径）。 */
export function formatGoalTokens(tokens: number): string {
  if (tokens < 1000) return String(tokens)
  if (tokens < 10000) return `${(tokens / 1000).toFixed(1)}k`
  return `${Math.round(tokens / 1000)}k`
}

/** 后端推送载荷 → UI 态（防御性：字段缺失/类型不对 → null，不渲染坏 UI）。 */
export function parseGoalPayload(raw: unknown): GoalUiState | null {
  if (!raw || typeof raw !== 'object') return null
  const o = raw as Record<string, unknown>
  const condition = typeof o.condition === 'string' ? o.condition : null
  if (!condition || !condition.trim()) return null
  const phaseRaw = typeof o.phase === 'string' ? o.phase : 'active'
  const phase: GoalUiState['phase'] =
    phaseRaw === 'achieved' ? 'achieved'
      : phaseRaw === 'impossible' ? 'impossible'
        : phaseRaw === 'paused' ? 'paused'
          : 'active'
  return {
    condition,
    phase,
    iterations: typeof o.iterations === 'number' ? o.iterations : 0,
    setAt: typeof o.setAt === 'number' ? o.setAt : Date.now(),
    tokensAtStart: typeof o.tokensAtStart === 'number' ? o.tokensAtStart : 0,
    lastReason: typeof o.lastReason === 'string' ? o.lastReason : null,
    retryCount: typeof o.retryCount === 'number' ? o.retryCount : 0,
    retryLimit: typeof o.retryLimit === 'number' ? o.retryLimit : 0,
    // ⚠️ 缺失/非数字 → null（不是 0）：后端「取不到」与「确实是 0」必须可分，
    //   否则新设目标会显示一个假的「0 tok」。
    tokensUsed: typeof o.tokensUsed === 'number' ? o.tokensUsed : null,
  }
}
