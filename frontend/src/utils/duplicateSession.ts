import { chatApi } from '@/api/chat'
import { sessionApi } from '@/api/sessions'
import type { ForkSessionResponse } from '@/api/types'

/**
 * 会话「复制」= 以**最后一条消息**为分叉点，分叉出一个新会话。
 *
 * <h2>WHY 与「分叉」是同一个能力</h2>
 * 后端只有 {@code POST /sessions/{id}/fork}（{@code SessionForkService}）：按 pivot 消息的
 * {@code seq} 截断复制，标题追加「 分叉」，产物/附件按新会话 id 复制。
 * 主区「从这条回答继续分叉」是让用户**选** pivot；左栏「复制」是固定取**最后一条**
 * = 复制整个会话。所以这里是同一个后端能力、不同的 pivot 取法，不另造端点。
 *
 * <h2>pivot 怎么取</h2>
 * {@code GET /sessions/{id}/messages/page?limit=1} —— **尾页**（最新 limit 条）。
 * ⛔ 不用 {@code GET /messages} 全量：会话动辄几千条（本机实测 8439 条），复制一个会话
 * 先拉全量会把内存和带宽白扔。
 * 尾页返回按 {@code createdAt} 升序 ⇒ **最后一条即最新**（2026-11-18 真机核对过）。
 */
export type DuplicateSessionResult =
  | { ok: true; response: ForkSessionResponse }
  /** 会话一条消息都没有 ⇒ 没有 pivot 可分叉（后端 fork 强制要求 pivot 属于本源会话） */
  | { ok: false; reason: 'empty' }

/**
 * 复制会话。
 *
 * @param sourceSessionId 源会话 id
 * @returns 成功 ⇒ 新会话 + 复制的消息条数；空会话 ⇒ `{ ok: false, reason: 'empty' }`
 * @throws 后端错误（网络/校验）直接抛给调用方弹提示
 */
export async function duplicateSession(sourceSessionId: string): Promise<DuplicateSessionResult> {
  const page = await chatApi.listMessagesPage(sourceSessionId, { limit: 1 })
  const pivot = page.messages[page.messages.length - 1]
  if (!pivot) return { ok: false, reason: 'empty' }
  return { ok: true, response: await sessionApi.fork(sourceSessionId, pivot.id) }
}
