import { api } from './rest'

/**
 * 会话「按轮聚合用量」（只读）：历史会话重开后，旧消息在 DB 里没有 token 字段，
 * 真实用量按 userMessageId 聚合在 llm_usage_calls —— 轮底部用量行用它补缺。
 *
 * <p>只返回**有用量**的轮（没有用量的轮不出现 ⇒ 前端保持原文案）；
 * model 可能带 provider 前缀（deepseek/deepseek-flash），展示前需剥；costYuan 可能为 null。
 */
export interface TurnUsageDto {
  /** 轮键 = 发起该轮的 user 消息 id（与 MessageList 的 turnKey 同口径） */
  userMessageId: string
  inputTokens: number | null
  outputTokens: number | null
  costYuan: number | null
  model: string | null
}

export interface TurnsUsageResponse {
  turns: TurnUsageDto[]
}

export const usageTurnsApi = {
  list: (sessionId: string) =>
    api<TurnsUsageResponse>(`/sessions/${encodeURIComponent(sessionId)}/usage/turns`),
}
