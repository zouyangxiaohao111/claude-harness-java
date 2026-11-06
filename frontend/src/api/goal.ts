import { api } from './rest'

/**
 * 会话目标（/goal）REST 客户端。
 *
 * <p>[goal V253] 后端真源 = `sessions.goal` 列（`SessionGoal`）。STOMP 推的是会话级 topic
 * （`/topic/sessions/{sid}/goal`），但 STOMP 只在「订阅已建立且事件到达」时才有值 ⇒
 * 切会话 / F5 / 后端重启后目标头拿不到初始状态 ⇒ 必须有这条读通道。
 *
 * <p>形状与 STOMP 推送载荷**逐字一致** ⇒ 前端同一套 `parseGoalPayload` 归一逻辑吃两处，
 * 不写第二套解析。
 */
export interface GoalPayload {
  condition: string
  phase: 'active' | 'paused' | 'achieved' | 'impossible'
  iterations: number
  setAt: number
  tokensAtStart: number
  lastReason: string | null
  /** [B6] 已自动重试次数（纯展示，非配置项） */
  retryCount?: number
  /** [B6] 自动重试上限（后端常量，随载荷走） */
  retryLimit?: number
  /**
   * 设定目标<b>至今真实消耗</b>的输出 token（后端 `llm_usage_calls` 按 `started_at >= setAt` 求和）。
   *
   * <p>⚠️ 与 `tokensAtStart` 语义不同：后者是「设定时刻的基线快照」，不能当消耗量显示。
   * 本字段缺失 / null = 后端取不到（统计未接线 / 查询异常）⇒ **不渲染**，⛔ 不显示 0。
   */
  tokensUsed?: number | null
}

export interface GoalResponse {
  /** goal=null = 该会话无目标 */
  goal: GoalPayload | null
}

export interface ClearResponse {
  /** cleared=false = 本来就没有目标（幂等） */
  cleared: boolean
}

/**
 * [goal V253] kickoff 提示词分隔标记 · 与后端 `CommandController.GOAL_KICKOFF_MARKER` 对齐。
 *
 * <p>WHY 用标记而不是 JSON：后端该端点 `produces=text/plain`（与 /compact 同款 ——
 * 字面路由优先于 `{name}` 路径变量，Accept 发 application/json 会 406），
 * 而前端 `api()` 强制 JSON 解析 ⇒ 不能直接吃 JSON 体。
 */
export const GOAL_KICKOFF_MARKER = '\n[[GOAL_KICKOFF]]\n'

/**
 * 从后端纯文本响应里切出 kickoff 提示词。
 *
 * @param raw 后端响应原文
 * @returns `{display, kickoff}` · 无标记时 kickoff 为空串
 */
export function splitGoalKickoff(raw: string): { display: string; kickoff: string } {
  const i = raw.indexOf(GOAL_KICKOFF_MARKER)
  if (i < 0) return { display: raw.trim(), kickoff: '' }
  return {
    display: raw.slice(0, i).trim(),
    kickoff: raw.slice(i + GOAL_KICKOFF_MARKER.length).trim(),
  }
}

export const goalApi = {
  /** 读取会话目标快照（GET /api/v1/sessions/{sessionId}/goal）。 */
  get: (sessionId: string) =>
    api<GoalResponse>(`/sessions/${encodeURIComponent(sessionId)}/goal`),

  /**
   * 清除会话目标（DELETE /api/v1/sessions/{sessionId}/goal）。
   *
   * <p>⛔ **不能只删前端 store**：那样后端 `sessions.goal` 列还在、prompt Stop hook 还挂着
   * ⇒ 用户以为清掉了，下一轮评估器仍会拦模型，且后端下次 push 后目标头又回来。
   * ⇒ 清除必须走后端。
   */
  clear: (sessionId: string) =>
    api<ClearResponse>(`/sessions/${encodeURIComponent(sessionId)}/goal`, { method: 'DELETE' }),
}
