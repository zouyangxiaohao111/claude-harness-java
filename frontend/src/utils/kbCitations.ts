import type { CitationItem } from '../api/types'

/**
 * 后端实时 `citations` 事件用的占位 assistant id。
 *
 * <p>引用在**生成前**就推送（那时 assistant 消息还没落库）⇒ 事件里的 id 恒是这个占位值；
 * 消息落库后真实 id 是 `msg-xxxx`。前端若只按真实 id 查引用，会永远查不到。
 */
export const STUB_ASSISTANT_ID = 'msg-stub-pending'

/**
 * 取「某一轮」的引用。
 *
 * <p>顺序：真实 id 桶 → 占位桶 → 空。
 * 占位桶只兜底**最新一轮**（调用方传的就是最后一条 assistant 的 id）——
 * 历史轮次的引用由 `GET /messages/{id}/citations` 回放按真实 id 写入，不依赖这里。
 */
export function pickCitationsForTurn(
  byMessage: Record<string, CitationItem[]> | undefined | null,
  assistantMessageId: string | undefined | null,
): CitationItem[] {
  if (!byMessage) return []
  if (assistantMessageId) {
    const hit = byMessage[assistantMessageId]
    if (hit && hit.length > 0) return hit
  }
  return byMessage[STUB_ASSISTANT_ID] ?? []
}