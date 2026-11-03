/**
 * 目标（/goal）UI 态类型 · **按会话键控**（chatStore.goal[sessionId]）。
 *
 * <p>[多会话隔离] 与 `compact` / `tokenWarning` 同款：后端推的是**会话级** topic
 * （`/topic/sessions/{sid}/goal`），本仓是多会话 Web —— 全局单例会让「A 会话在追目标、
 * 切到 B」时 B 显示 A 的目标。故按 sessionId 键控。
 *
 * <p>[后端契约 · 对齐 CC claude.exe v2.1.212 的 activeGoal]
 * 形状与 `sessions.goal` 列一一对应（`SessionGoal.java`）：
 * ```
 * { condition, phase, iterations, setAt, tokensAtStart, lastReason }
 * ```
 * `phase` ∈ `active | achieved | impossible`（**没有** DSH 的 paused/blocked ——
 * 那是它缺评估器的补偿机制，我们照 CC 只有三种结局）。
 */
export interface GoalUiState {
  /** 目标条件原文（CC 语义，上限 4000 字符） */
  condition: string
  /** active=追之中 / achieved=已达成 / impossible=无法达成 */
  phase: 'active' | 'achieved' | 'impossible'
  /** 已评估轮数（**纯记账**，不参与判断；CC 的 goal 对象里没有上限字段） */
  iterations: number
  /** 设定时刻（epoch 毫秒） */
  setAt: number
  /** 设定时的输出 token 累计值（CC `DA()` 口径 = 只算 output token） */
  tokensAtStart: number
  /** 最近一次评估器返回的 reason（= 注入下一轮的那句话，也是「最近一次判定」的来源） */
  lastReason?: string | null
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
    phaseRaw === 'achieved' ? 'achieved' : phaseRaw === 'impossible' ? 'impossible' : 'active'
  return {
    condition,
    phase,
    iterations: typeof o.iterations === 'number' ? o.iterations : 0,
    setAt: typeof o.setAt === 'number' ? o.setAt : Date.now(),
    tokensAtStart: typeof o.tokensAtStart === 'number' ? o.tokensAtStart : 0,
    lastReason: typeof o.lastReason === 'string' ? o.lastReason : null,
  }
}
