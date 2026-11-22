import { api, ApiError } from './rest'
import {
  isCommonResult,
  normalizeActionOutcome,
  type PeerInboxActionOutcome,
  type PeerInboxOutcomeKind,
} from './peerInboxOutcome'
import type {
  CommonResultDto,
  PeerHeldActionResultDto,
  PeerHeldItemDto,
  PeerInboxRejectAllResultDto,
  PeerInboxSeenResultDto,
} from './types'

export { normalizeActionOutcome }
export type { PeerInboxActionOutcome, PeerInboxOutcomeKind }

/**
 * 跨会话待收件**聚合**事件（STOMP `/topic/peer-inbox`，全局一条订阅）。
 *
 * <p>与旧的按会话 topic（`/topic/sessions/{sid}/peer-inbox`）不同：一条流覆盖所有会话，
 * 靠 `targetSessionId` 归属、靠 `seq` 判连续性。`seq` 单调递增（long）：连续才可增量应用；
 * 跳号或回退 = 丢帧 ⇒ 一律改走 `GET /peer-inbox/counts` 全量对账（⛔ 不跳过、不猜测）。
 *
 * <p>`item` 可选（`peer.inbox.hold` 可能带一条行体）；`pendingCount` 是该会话的**服务端计数**
 * （口径 = pending，declined/expired/accepted 不计）。缺失时按事件类型做 ±1 兜底。
 */
export interface PeerInboxAggregateEvent {
  type: 'peer.inbox.hold' | 'peer.inbox.updated' | 'peer.inbox.removed' | string
  /** 全局单调序号；缺失 = 无法判连续（丢弃该帧，等对账） */
  seq?: number | null
  targetSessionId?: string | null
  /** 目标会话的服务端 pending 计数（首选真值） */
  pendingCount?: number | null
  /** 可选行体（hold 事件） */
  item?: Record<string, unknown> | null
  [key: string]: unknown
}

/** `GET /peer-inbox/counts` 的 data：全局 seq 基线 + 每会话 pending 计数 + FIFO 最早一条。 */
export interface PeerInboxCountsDto {
  seq?: number | null
  counts?: Record<string, number | null> | null
  /** 每会话最早一条 pending 的 SQLite rowid（权威 FIFO；可缺失 = 旧后端）。 */
  oldestRowId?: Record<string, number | null> | null
  /** 每会话最早一条 pending 的 created_at（展示/兜底；可缺失）。 */
  oldestCreatedAt?: Record<string, string | null> | null
}

/**
 * 跨会话待收件（held）审批端点 · 复用 {@link api} 的 HTTP 客户端与 RFC 7807 错误归一化。
 *
 * <p>路由与返回幂等以服务端 `apis/session/PeerInboxController` 为准（2026-11-20 起全部包
 * `CommonResult`：`{code:number, msg, data, success}`）：
 * <ul>
 *   <li>`GET  /sessions/{id}/peer-inbox` → `CommonResult<HeldView[]>`</li>
 *   <li>`POST /sessions/{id}/peer-inbox/{messageId}/approve` → `CommonResult<HeldActionResult>`</li>
 *   <li>`POST /sessions/{id}/peer-inbox/{messageId}/reject` → `CommonResult<HeldActionResult>`</li>
 *   <li>`POST /sessions/{id}/peer-inbox/seen` body `{messageIds:[…]}` → `CommonResult<{updated:n}>`</li>
 *   <li>`POST /sessions/{id}/peer-inbox/reject-all` → `CommonResult<{status:'REJECTED', rejected:n}>`</li>
 * </ul>
 *
 * <p><b>⚠️ HTTP 200 ≠ 已接受</b>：审批/拒绝一律回 200，**成功 = `success:true && code:0`**；
 * 业务失败 `success:false` + 数字码（423/404/403/500/900），原因在 `data.status`。
 * 只有传输/鉴权问题（401/403/5xx/断网）才走非 2xx ⇒ 归一化为 `kind:'transport'`。
 * 灰度期后端未更新时仍是旧扁平体（字符串 `code`），判读保留一版（见
 * {@link normalizeActionOutcome}）。
 */

/**
 * 统一返回体拆包：`success:true && code:0` 才把 `data` 交给调用方。
 *
 * <p>非统一返回体（旧实现）原样返回 —— 灰度期两端版本不同步时前端不至于直接崩。
 * 业务失败（HTTP 200 + `success:false`）抛 {@link ApiError}：列表/已读没有「行内保留」语义，
 * 交给调用方的错误态展示（⛔ 不得把失败当空列表）。
 */
function unwrapData<T>(raw: unknown, what: string): T {
  if (!isCommonResult(raw)) return raw as T
  const env = raw as CommonResultDto<T>
  const code = typeof env.code === 'number' ? env.code : null
  if (env.success !== true || (code !== null && code !== 0)) {
    const msg = (env.msg ?? '').trim() || `${what}失败，请重试`
    throw new ApiError(msg, { status: 200, title: 'Business Error', detail: msg })
  }
  return env.data as T
}

/** 执行一次审批/拒绝动作：通信失败不抛，统一归一化成 `kind:'transport'`。 */
async function actionRequest(
  path: string,
  opts: { inferRejectAll?: boolean } = {},
): Promise<PeerInboxActionOutcome> {
  try {
    const raw = await api<unknown>(path, { method: 'POST' })
    return normalizeActionOutcome(raw as PeerHeldActionResultDto & PeerInboxRejectAllResultDto, opts)
  } catch (e) {
    const status = e instanceof ApiError ? e.status : 0
    // 审批主体不可信 = HTTP 403（body 也是 CommonResult，code=403）⇒ 中文原因直接给「无权限」。
    const message = status === 403
      ? '无权限'
      : e instanceof ApiError ? e.userMessage() : e instanceof Error ? e.message : String(e)
    return { kind: 'transport', ok: false, code: 'TRANSPORT_ERROR', message, numericCode: null }
  }
}

export const peerInboxApi = {
  /** 列出本会话的 held 项（错归属 / 不可信主体 → HTTP 403）。 */
  list: async (sessionId: string): Promise<PeerHeldItemDto[]> => {
    const raw = await api<unknown>(`/sessions/${encodeURIComponent(sessionId)}/peer-inbox`)
    return unwrapData<PeerHeldItemDto[]>(raw, '加载待收件') ?? []
  },

  /**
   * 全局计数基线（对账用）：`GET /peer-inbox/counts` → `CommonResult<{seq, counts}>`。
   *
   * <p>只在「seq 跳号/回退」「socket 重连」「页面回到前台」时调用（⛔ 不再周期轮询）。
   * 失败必须抛出 —— 调用方据此**不覆盖**本地状态（⛔ 不拿失败当 0）。
   */
  counts: async (): Promise<PeerInboxCountsDto> => {
    const raw = await api<unknown>('/peer-inbox/counts')
    const data = unwrapData<PeerInboxCountsDto>(raw, '加载待收件计数')
    return data ?? {}
  },

  /**
   * 接受一条（一次性）：后端重新校验目标在线/代次/策略，通过才投递。
   *
   * <p>返回 {@link PeerInboxActionOutcome}；**只有 `ok`（`success:true && code:0`）才代表已投递**。
   */
  approve: (sessionId: string, messageId: string) =>
    actionRequest(
      `/sessions/${encodeURIComponent(sessionId)}/peer-inbox/${encodeURIComponent(messageId)}/approve`,
    ),

  /** 拒绝一条（移除并让发送方得到明确结果）。成功 = `ok`（新契约 `data.status:'REJECTED'`）。 */
  reject: (sessionId: string, messageId: string) =>
    actionRequest(
      `/sessions/${encodeURIComponent(sessionId)}/peer-inbox/${encodeURIComponent(messageId)}/reject`,
    ),

  /**
   * 批量拒绝本会话**全部**待处理项（一次性结算，发送方会收到结果）。
   *
   * <p>契约：`POST /sessions/{id}/peer-inbox/reject-all` → `CommonResult<{status:'REJECTED', rejected:N}>`。
   * **只有 `ok` 才清列表**；通信失败归一化为 `kind:'transport'`（调用方保留列表与计数，
   * ⛔ 不假装清空）。
   */
  rejectAll: (sessionId: string) =>
    actionRequest(
      `/sessions/${encodeURIComponent(sessionId)}/peer-inbox/reject-all`,
      { inferRejectAll: true },
    ),

  /**
   * 已读上报（批量；空数组不发请求）。
   *
   * <p>成功 = 2xx 且 `success:true && code:0`（`data:{updated:n}`）。**失败必须抛出**
   * （由 `api` / {@link unwrapData} 归一化为 ApiError）——调用方据此决定「不回填本地 seenAt」，
   * ⛔ 不得把失败吞成成功（否则角标假绿、过期项再也不重试）。
   */
  markSeen: async (sessionId: string, messageIds: string[]): Promise<PeerInboxSeenResultDto> => {
    if (messageIds.length === 0) return { updated: 0 }
    const raw = await api<unknown>(
      `/sessions/${encodeURIComponent(sessionId)}/peer-inbox/seen`,
      { method: 'POST', body: { messageIds } },
    )
    const data = unwrapData<PeerInboxSeenResultDto>(raw, '已读上报')
    return { updated: data?.updated ?? 0 }
  },
}
