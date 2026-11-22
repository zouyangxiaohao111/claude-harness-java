import { create } from 'zustand'
import { ApiError } from '@/api/rest'
import { peerInboxApi, type PeerInboxAggregateEvent } from '@/api/peerInbox'
import { normalizeActionOutcome, type PeerInboxActionOutcome } from '@/api/peerInboxOutcome'
import { sendSystemNotification } from '@/utils/systemNotify'
import type { PeerHeldItemDto, PeerInboxHoldEvent } from '@/api/types'

/**
 * 跨会话待收件（hold）面板状态 · 单会话视图（切换会话即作废旧列表）。
 *
 * <h2>为什么是全局 store 而不是面板局部 state</h2>
 * <p>入口按钮（未读角标）常驻在 WorkspaceHeader，面板只是它的展开态；两者必须共享同一份
 * 列表与刷新时序。切换会话/新建会话时旧列表必须立刻不可见 —— 这里用 `sessionId` 作为
 * 归属键：请求返回时若会话已切换，响应被丢弃（⛔ 不得把 A 会话的待收件显示到 B 会话）。
 *
 * <h2>接受 = 服务端一次性动作（不做本地乐观插入）</h2>
 * <p>approve 的 HTTP 200 **不代表已接受**：后端统一返回体里成功 = `success:true && code:0`；
 * 业务失败仍是 200（`success:false` + 数字码 423/404/403/500/900），原因在 `data.status`。
 * 只有 `ok` 才移除本行；其余一律保留行、把中文原因挂到该行（{@link PeerInboxState.rowErrors}）
 * 并给出会话提示；通信失败（断网/4xx/5xx）同样保留并提示「请求失败，请重试」。
 * 前端**不**把消息塞进 chatStore —— 目标会话的正文由后端投递后经 `message.insert`
 * 整行事件（或重新拉取）出现，前端不伪造。
 */
export interface PeerInboxState {
  /** 当前列表归属会话（null = 无会话视图） */
  sessionId: string | null
  items: PeerHeldItemDto[]
  loading: boolean
  error: string | null
  /** 动作进行中的 messageId（请求期间两按钮都禁用） */
  acting: string | null
  /** 「全部拒绝」批量动作进行中（期间整组动作禁用，防重复提交） */
  actingAll: boolean
  /** 最近一次动作的结果提示（成功/失败都显示；失败时行保留） */
  notice: string | null
  /**
   * 行内失败原因：messageId → 中文文案（仅「业务未达成 / 请求失败」时写入）。
   *
   * <p>WHY 单独一份而不是只靠 {@link notice}：多条并存时会话级提示只能表达「最近一条」，
   * 用户点完 A 行再点 B 行，A 行的原因会被覆盖 ⇒ 必须挂在行上。成功移除 / 列表刷新时清掉。
   */
  rowErrors: Record<string, string>
  /**
   * 待处理数 = `status === 'pending'` 的条数（**角标 / 输入框常驻条 / 启动提示的唯一口径**）。
   *
   * <p>⛔ 不等于未读：用户点开面板后 seenAt 会被回填（未读归零），但消息仍然待处理 ——
   * 用未读做角标会出现「看过了就当处理完了」的假清零。
   */
  pendingCount: number
  /** 未读数 = `pending && seenAt == null` · 只用于新到响铃与首次自动展开的边沿判断。 */
  unreadCount: number
  /**
   * [peer-inbox-aggregate] 全局事件流的最后应用序号（`/topic/peer-inbox` 的 `seq`）。
   *
   * <p>`null` = 尚无基线（首帧直接采纳）。只有 `seq === lastSeq + 1` 才允许增量应用；
   * 跳号/回退一律走 {@link reconcileCounts} 全量对账（⛔ 不跳过、不猜测补帧）。
   */
  lastSeq: number | null
  /**
   * 每会话 pending 计数（供左栏角标等全局视图使用）。
   *
   * <p>真值来源：聚合事件的 `pendingCount` → 对账用的 counts 接口 → 当前会话列表刷新时同步。
   * 口径 = pending（declined/expired/accepted 不计）。
   */
  countsBySession: Record<string, number>
  /** 全局待处理总数 = {@link countsBySession} 之和（左栏角标用）。 */
  totalPending: number
  /**
   * 收件箱的跨会话列表（每行一条 pending 来信；⛔ 不渲染正文）。
   *
   * <p>打开收件箱时按 `countsBySession` 里有待办的会话并行拉取并合并；FIFO 按 heldAt 升序。
   */
  inboxItems: PeerHeldItemDto[]
  inboxLoading: boolean
  inboxError: string | null
  /** 最近一次收件箱动作提示（成功/失败；失败时行保留）。 */
  inboxNotice: string | null
  /** 收件箱行内失败原因：messageId → 中文文案。 */
  inboxRowErrors: Record<string, string>
  /** 收件箱动作进行中的 messageId（期间全部动作禁用）。 */
  inboxActing: string | null
  /**
   * Web 系统通知点击 → 请求 App 跳会话（store 不直接依赖 chatStore）。
   *
   * <p>App 以 `seq` 去重消费；Tauri 通道由 App 的 onSystemNotificationClick 直接跳，
   * 两条路径最终都走同一个 jumpToPending。
   */
  jumpRequest: PeerJumpRequest | null
  /**
   * 最近一次「需要提醒」的信号（启动发现待处理 / 实时新 hold）。
   *
   * <p>`notified === false` = 系统通知通道不可用/被拒 ⇒ App 必须用页内 toast 兜底
   * （⛔ 不得静默）。seq 每次递增，App 以它作为 effect 依赖。
   */
  alert: PeerInboxAlert | null
  /**
   * 会话级「待处理」列表是否展开（集中在本 store）。
   *
   * <p>⛔ 只有用户主动点「待处理 / 去处理」才会置 true —— 启动、切会话、实时来信
   * **一律不自动展开**（2026-11-22 用户裁定：列表默认收起）。
   */
  open: boolean
  /**
   * 加载列表。
   *
   * @param opts.startup 仅「启动 / 切会话后的首次加载」传 true：发现 **未读** 项时发一条
   *   系统通知（⛔ 不自动展开列表；已读积压不重复提醒）。
   */
  load: (sessionId: string | null, opts?: { startup?: boolean }) => Promise<void>
  accept: (messageId: string) => Promise<void>
  reject: (messageId: string) => Promise<void>
  /** 批量拒绝本会话全部待处理项（后端一次性结算，发送方得到明确结果）。 */
  rejectAll: () => Promise<void>
  /**
   * 用户主动展开收件箱（⛔ 不切会话、不拉历史、不标记已读），并触发跨会话加载。
   *
   * <p>列表只渲染摘要 + 动作，不渲染任何来信正文；点行由 App 跳目标会话。
   */
  openPanel: () => void
  /** 用户主动关闭收件箱（不产生任何已读/自动打开副作用）。 */
  closePanel: () => void
  togglePanel: () => void
  /** 跨会话加载收件箱（打开时自动触发；也可手动重试）。 */
  loadInbox: () => Promise<void>
  /** 收件箱行内快速接受（按该行的 targetSessionId 调后端）。 */
  acceptInbox: (messageId: string) => Promise<void>
  /** 收件箱行内快速拒绝（按该行的 targetSessionId 调后端）。 */
  rejectInbox: (messageId: string) => Promise<void>
  /** Web 系统通知点击 → 请求 App 跳会话（App 校验会话存在性并定位卡片）。 */
  requestJump: (sessionId: string, messageId?: string | null) => void
  /**
   * [peer-inbox-realtime] 实时事件增量合并（`peer.inbox.hold`）。
   *
   * <p>只做本会话列表的增量：已存在按 messageId 原地更新（不重复、不重排），新到达追加末尾。
   * ⛔ 不切会话、不跳路由、不拉 `GET /messages`、不伪造 chatStore 消息；⛔ 不自动展开列表。
   */
  applyRealtimeEvent: (event: PeerInboxHoldEvent) => void
  /**
   * [peer-inbox-aggregate] 全局聚合事件（`/topic/peer-inbox`）应用入口。
   *
   * <p>seq 连续 ⇒ 更新该会话计数；目标是当前会话 ⇒ 同步列表与角标/提示条。
   * seq 跳号或回退 ⇒ 丢帧，调 `GET /peer-inbox/counts` 全量对账后覆盖本地状态（本帧不应用）。
   * ⛔ 不切会话、不拉历史、不伪造成功。
   */
  applyAggregateEvent: (event: PeerInboxAggregateEvent) => void
  /**
   * [peer-inbox-aggregate] 全量计数对账（`GET /peer-inbox/counts`）。
   *
   * <p>成功才覆盖 {@link countsBySession}/{@link totalPending}/{@link lastSeq}；
   * 失败保留本地状态（⛔ 不清零、不假绿），下一次事件/重连会再试。并发调用合并在途请求。
   *
   * @returns 是否对账成功（请求失败 / 端点缺失 = false）
   */
  reconcileCounts: () => Promise<boolean>
  /**
   * [peer-inbox-seen] 内联待确认卡渲染到视野时上报已读（批量、去重、失败静默可重试）。
   *
   * <p>触发方 = App 的「流贴底 + 有未读内联卡」effect（⛔ 不再是面板 open 门禁）。
   * 只上报 `seenAt == null` 且不在途的 id；成功后本地回填 seenAt（未读角标随之下降）。
   * 失败**不回填**（⛔ 不假绿），清掉在途标记让下次可见/轮询后重试。
   */
  markSeenForVisible: (items?: PeerHeldItemDto[]) => Promise<void>
}

/** 一次「需要提醒」的信号（系统通知 + UI 兜底）。 */
export interface PeerInboxAlert {
  /** 递增序号（App 用它触发兜底 toast，避免同一信号重复弹）。 */
  seq: number
  sessionId: string
  /** 提醒时的待处理条数。 */
  count: number
  source: 'startup' | 'realtime'
  /** 系统通知是否真的发出（false ⇒ 页内 toast 兜底）。 */
  notified: boolean
}

/** 某会话最早一条 pending 的排序键（rowId 权威，createdAt 展示/兜底）。 */
/** Web 系统通知点击 → App 跳转请求（seq 去重）。 */
export interface PeerJumpRequest {
  sessionId: string
  messageId: string | null
  seq: number
}

/**
 * 跳转后要聚焦的 pending 卡：优先指定 messageId，否则取**该会话最早一条**
 * （列表接口按 created_at ASC 返回，见 PeerHeldMessageMapper.listForDisplay）。
 */
export function pickPeerFocusTarget(
  items: PeerHeldItemDto[],
  messageId?: string | null,
): PeerHeldItemDto | null {
  const pending = items.filter((item) => isPendingHeldItem(item))
  if (pending.length === 0) return null
  if (messageId) {
    const exact = pending.find((item) => item.messageId === messageId)
    if (exact) return exact
  }
  return pending[0]!
}

/** hold 原因 → 用户可读文案（未知值原样带出，⛔ 不吞成「成功」）。 */
export function peerHeldReasonText(reason: string | null | undefined): string {
  switch ((reason ?? '').trim().toUpperCase()) {
    case 'EXPLICIT_HOLD':
      return '接收方设置为需人工确认'
    case 'MODE_MISMATCH':
      return '权限模式不一致'
    case 'MODE_UNKNOWN':
      return '无法判定权限模式'
    case 'NO_MODE_ASSERTED':
      return '发送方未声明模式'
    case 'INVALID_SETTING':
      return '入站策略未配置'
    default: {
      const raw = (reason ?? '').trim()
      return raw ? `需人工确认（${raw}）` : '需人工确认'
    }
  }
}

/**
 * 来源会话标题（会话标题快照 → 展示名；缺全部才用「未命名会话」）。
 *
 * <p>⚠️ 后端 `HeldView` 的标题字段名是 `sourceTitle`（不是 `fromSessionTitle`），两个都认 ——
 * 只认旧名会让标题恒空、退化成 fromName 冒充标题。
 * ⛔ 不用来源 id 冒充标题：id 固定显示在标题旁边的小字里（见 {@link peerHeldSourceId}）。
 */
export function peerHeldTitle(item: PeerHeldItemDto): string {
  const snapshot = (item.fromSessionTitle ?? '').trim() || (item.sourceTitle ?? '').trim()
  if (snapshot) return snapshot
  const name = (item.fromName ?? '').trim()
  if (name) return name
  return '未命名会话'
}

/** 来源会话 id（展示用；缺失返回空串，不伪造）。 */
export function peerHeldSourceId(item: PeerHeldItemDto): string {
  return (item.fromSessionId ?? '').trim()
}

/**
 * 发送方声明的权限模式（唯一口径）：只显示**声明值**；未声明 → 「未声明」。
 *
 * <p>⛔ 不得回落成 `default` —— 那会把「对方没说」伪装成「对方是默认模式」。
 */
export function peerModeText(fromMode: string | null | undefined): string {
  return (fromMode ?? '').trim() || '未声明'
}

/** 准入徽标（自动通过 / 手动批准；缺失 = 中性「准入未知」，⛔ 不猜）。 */
export function peerAdmissionBadge(
  admission: string | null | undefined,
  reason?: string | null,
): { label: string; kind: 'auto' | 'approved' | 'unknown' } {
  const a = (admission ?? '').trim().toLowerCase()
  const r = (reason ?? '').trim().toLowerCase()
  if (a === 'auto') {
    if (r === 'mode-match') return { label: '自动通过 · 模式一致', kind: 'auto' }
    if (r === 'self-sent') return { label: '自动通过 · 自发', kind: 'auto' }
    return { label: '自动通过', kind: 'auto' }
  }
  if (a === 'approved') return { label: '手动批准', kind: 'approved' }
  return { label: '准入未知', kind: 'unknown' }
}

/**
 * 已拒绝（终态）判据：列表返回 declined 后渲染为「已拒绝（未投递给 Claude）」卡片 ——
 * 可查看、不可再投递；⛔ 不计待处理/未读，也不触发提示音/自动展开/系统通知。
 *
 * <p>列表接口现返回 pending/approved/declined（declined 只是审计展示）；本判据只负责
 * 「后端透出就正确渲染成只读卡」，⛔ 不本地伪造终态。
 */
export function isDeclinedHeldItem(item: PeerHeldItemDto): boolean {
  const status = (item.status ?? '').trim().toLowerCase()
  return status === 'rejected' || status === 'declined' || status === 'refused'
}

/**
 * 待收件行的展示态（**唯一状态映射**）：只有 pending 可操作，其余一律只读。
 *
 * <p>后端已严格对齐 CC「批准即结算」：不再有 approved 中间态 —— 批准成功即 accepted
 * （普通来信卡），失败即终态并给发送方失败通知。因此前端 ⛔ 不得再为 approved 渲染
 * 「拒绝 / 投递」动作（那个端点已不存在，点了只会 404）。
 *
 * <p>历史数据里可能仍有迁移前的 approved 行：按只读「已批准（待迁移）」展示，不提供操作。
 * accepted / dropped / refused / rejected / declined / expired 等终态同理：可查看、不可操作。
 * 未知状态一律按只读处理（⛔ 宁可不给按钮，也不让用户点到必然失败的动作）。
 */
export interface PeerHeldStatusView {
  /** 仅 pending 为 true；false ⇒ 只读，不渲染任何动作按钮 */
  actionable: boolean
  /** 头部状态文案（pending = 未投递给 Claude；其余为终态/历史态） */
  label: string
  /** 只读行的弱化样式标记（复用既有 `.declined` 卡片样式）；pending = 空串 */
  tone: '' | 'declined'
}

export function peerHeldStatusView(item: PeerHeldItemDto): PeerHeldStatusView {
  const raw = (item.status ?? '').trim()
  switch (raw.toLowerCase()) {
    case '':
    case 'pending':
      return { actionable: true, label: '未投递给 Claude', tone: '' }
    case 'approved':
      // 迁移前的历史中间态：保留可见，但无动作（后端已无 approved 结算路径）。
      return { actionable: false, label: '已批准（待迁移）', tone: 'declined' }
    case 'accepted':
      return { actionable: false, label: '已投递', tone: 'declined' }
    case 'dropped':
      return { actionable: false, label: '已丢弃（未投递）', tone: 'declined' }
    case 'rejected':
    case 'declined':
    case 'refused':
      return { actionable: false, label: '已拒绝（未投递给 Claude）', tone: 'declined' }
    case 'expired':
      return { actionable: false, label: '已过期（未投递）', tone: 'declined' }
    default:
      // 未知状态：只读兜底，不猜成可操作（避免点击必然失败的动作）。
      return { actionable: false, label: raw ? `不可操作（${raw}）` : '不可操作', tone: 'declined' }
  }
}

/**
 * 正文规模文案（后端 `bodyLines` / `bodyChars`）：「共 12 行 · 820 字」；两者都缺失 → 空串。
 *
 * <p>⛔ 不本地数正文冒充后端真值（正文可能被截断）；缺失就不显示规模。
 */
export function peerHeldScaleText(item: PeerHeldItemDto): string {
  const lines = typeof item.bodyLines === 'number' && item.bodyLines >= 0 ? item.bodyLines : null
  const chars = typeof item.bodyChars === 'number' && item.bodyChars >= 0 ? item.bodyChars : null
  if (lines == null && chars == null) return ''
  const parts: string[] = []
  if (lines != null) parts.push(`共 ${lines} 行`)
  if (chars != null) parts.push(`${chars} 字`)
  return parts.join(' · ')
}

/** 摘要：后端 summary 优先；缺失取正文首行前 60 字。 */
export function peerHeldSummary(item: PeerHeldItemDto): string {
  const summary = (item.summary ?? '').replace(/\s+/g, ' ').trim()
  if (summary) return summary
  const body = (item.body ?? '').replace(/\s+/g, ' ').trim()
  return body.slice(0, 60) || '(无摘要)'
}

/**
 * 业务未达成时的用户文案（数字码归一化后的符号码 → 中文；行必须保留）。
 *
 * <p>数字码映射（后端契约）：423 `HELD` → 仍待确认；404+`NO_LIVE_SESSION` → 目标会话不在线；
 * 404+`NOT_FOUND` → 消息不存在；403+`REFUSED` → 策略已拒绝；403 其它 → 无权限；
 * 500 → 未送达；900 → 已被处理或归属不符；其余 → 请求失败，请重试。
 */
export function peerApproveFailureText(code: string): string {
  switch ((code ?? '').trim().toUpperCase()) {
    case 'HELD':
      return '仍待确认：目标会话当前仍按策略待人确认，本条已保留'
    case 'NO_LIVE_SESSION':
      return '目标会话不在线，本条已保留'
    case 'SOURCE_NOT_LIVE':
      return '来源会话已退出，本条已保留'
    case 'REFUSED':
      return '策略已拒绝，本条已保留'
    case 'FORBIDDEN':
      return '无权限，本条已保留'
    case 'APPROVAL_REJECTED':
      return '该消息已被处理或归属不符，本条已保留'
    case 'NOT_FOUND':
      return '消息不存在，本条已保留'
    case 'NOT_WIRED':
    case 'NOT_DELIVERED':
    case 'DELIVERY_UNCONFIRMED':
      return '未送达，本条已保留'
    default:
      return '请求失败，请重试'
  }
}

/** 列表请求序号：更新的列表请求让更旧的在途响应过期。 */
let loadSeq = 0
/** 本地变更序号：任何成功移除都会让「变更前发出」的列表响应过期（防旧列表把行带回来）。 */
let mutationSeq = 0
/** 计数对账在途请求（并发触发合并成一次，避免 seq 缺口时打爆接口）。 */
let reconcileInFlight: Promise<boolean> | null = null

function errorText(e: unknown): string {
  return e instanceof ApiError ? e.userMessage() : e instanceof Error ? e.message : String(e)
}

/** 请求层失败（断网 / 401 / 4xx / 5xx）的统一文案：保留列表、可原地重试。 */
function requestFailedText(detail?: string): string {
  const d = (detail ?? '').trim()
  return d ? `请求失败，请重试（${d}）` : '请求失败，请重试'
}

/**
 * api 返回值 → 统一业务结果（**唯一读取点**）。
 *
 * <p>三种输入都认：① 已归一化的 `{kind,ok,code,...}`（`api/peerInbox` 产出或测试替身）；
 * ② 统一返回体 `{code:number,msg,data,success}`；③ 旧后端扁平体 `{code:'ACCEPTED'|…, status}`。
 * 业务码缺失一律 UNKNOWN（⛔ 不当成功）。
 */
function readOutcome(raw: unknown, opts: { inferRejectAll?: boolean } = {}): PeerInboxActionOutcome {
  if (raw && typeof raw === 'object') {
    const r = raw as Partial<PeerInboxActionOutcome>
    if ((r.kind === 'ok' || r.kind === 'business' || r.kind === 'transport') && typeof r.ok === 'boolean') {
      return {
        kind: r.kind,
        ok: r.ok,
        code: (typeof r.code === 'string' ? r.code.trim().toUpperCase() : '') || 'UNKNOWN',
        message: typeof r.message === 'string' ? r.message : '',
        ...(typeof r.numericCode === 'number' || r.numericCode === null ? { numericCode: r.numericCode } : {}),
        ...(typeof r.rejected === 'number' ? { rejected: r.rejected } : {}),
      }
    }
  }
  return normalizeActionOutcome(raw as never, opts)
}

/** 清掉某一行的失败原因（重试前 / 成功后）。 */
function omitRowError(map: Record<string, string>, messageId: string): Record<string, string> {
  if (!(messageId in map)) return map
  const next = { ...map }
  delete next[messageId]
  return next
}

/** 只保留仍在列表里的失败原因（列表刷新/批量拒绝后清掉孤儿键）。 */
function pruneRowErrors(map: Record<string, string>, items: PeerHeldItemDto[]): Record<string, string> {
  const keep = new Set(items.map((i) => i.messageId))
  const entries = Object.entries(map).filter(([id]) => keep.has(id))
  if (entries.length === Object.keys(map).length) return map
  return Object.fromEntries(entries)
}

type InboxSet = (
  partial: Partial<PeerInboxState> | ((state: PeerInboxState) => Partial<PeerInboxState>),
) => void

/**
 * 收件箱行内快速接受/拒绝：按该行的 **targetSessionId** 调后端（可跨会话）。
 *
 * <p>成功 ⇒ 从收件箱移除；若目标就是当前会话，同时从内联卡列表移除并回写计数。
 * 业务未达成/通信失败 ⇒ 行保留并把中文原因挂到该行（⛔ 不弹假成功）。
 * 最后一条处理完 ⇒ 收起收件箱。
 */
async function settleInboxItem(
  get: () => PeerInboxState,
  set: InboxSet,
  messageId: string,
  accept: boolean,
): Promise<void> {
  const state = get()
  const item = state.inboxItems.find((i) => i.messageId === messageId)
  if (!item || state.inboxActing) return
  const target = (item.targetSessionId || state.sessionId || '').trim()
  if (!target) return
  set({
    inboxActing: messageId,
    inboxNotice: null,
    inboxRowErrors: omitRowError(state.inboxRowErrors, messageId),
  })
  let raw: unknown
  try {
    raw = accept
      ? await peerInboxApi.approve(target, messageId)
      : await peerInboxApi.reject(target, messageId)
  } catch (e) {
    const hint = requestFailedText(errorText(e))
    set((p) => ({
      inboxActing: null,
      inboxNotice: `${accept ? '接受' : '拒绝'}失败：${hint}`,
      inboxRowErrors: { ...p.inboxRowErrors, [messageId]: hint },
    }))
    return
  }
  const outcome = readOutcome(raw)
  if (outcome.kind === 'transport') {
    const hint = requestFailedText(outcome.message)
    set((p) => ({
      inboxActing: null,
      inboxNotice: `${accept ? '接受' : '拒绝'}失败：${hint}`,
      inboxRowErrors: { ...p.inboxRowErrors, [messageId]: hint },
    }))
    return
  }
  if (!outcome.ok) {
    const text = peerApproveFailureText(outcome.code)
    set((p) => ({
      inboxActing: null,
      inboxNotice: text,
      inboxRowErrors: { ...p.inboxRowErrors, [messageId]: text },
    }))
    return
  }
  mutationSeq++
  set((p) => {
    const inboxItems = p.inboxItems.filter((i) => i.messageId !== messageId)
    const isCurrent = target === p.sessionId
    const items = isCurrent ? p.items.filter((i) => i.messageId !== messageId) : p.items
    const patch = isCurrent ? itemsPatch(items) : {}
    const countsBySession = { ...p.countsBySession }
    const nextCount = Math.max(0, (countsBySession[target] ?? 0) - 1)
    if (nextCount > 0) countsBySession[target] = nextCount
    else delete countsBySession[target]
    return {
      inboxActing: null,
      inboxItems,
      inboxNotice: accept ? '已接受，本条已投递到目标会话' : '已拒绝',
      inboxRowErrors: omitRowError(p.inboxRowErrors, messageId),
      ...patch,
      countsBySession,
      totalPending: sumPeerCounts(countsBySession),
      open: inboxItems.length > 0 ? p.open : false,
    }
  })
  void get().reconcileCounts()
}

/**
 * 待处理判据：列表接口只返回 pending 行；实时事件载荷可能不带 `status`。
 *
 * <p>⇒ 缺省/null 视为 pending（列表真源已保证），有任何**终态**值（accepted/rejected/expired）
 * 一律不计。⛔ 不要用 `seenAt` 参与本判据 —— 已读 ≠ 已处理。
 */
export function isPendingHeldItem(item: PeerHeldItemDto): boolean {
  const status = (item.status ?? '').trim().toLowerCase()
  return status === '' || status === 'pending'
}

/** 未读判据（唯一口径）：`pending && seenAt` 为空才算未读；已读项仍在列表里、仍可接受/拒绝。 */
export function isUnreadHeldItem(item: PeerHeldItemDto): boolean {
  return isPendingHeldItem(item) && !item.seenAt
}

/** 待处理数（角标 / 常驻提示条 / 启动提示的唯一口径）。 */
export function peerInboxPendingCount(items: PeerHeldItemDto[]): number {
  return items.reduce((n, item) => (isPendingHeldItem(item) ? n + 1 : n), 0)
}

/** 未读数（只用于响铃与首次自动展开边沿，⛔ 不作角标）。 */
export function peerInboxUnreadCount(items: PeerHeldItemDto[]): number {
  return items.reduce((n, item) => (isUnreadHeldItem(item) ? n + 1 : n), 0)
}

/** 列表及其两个计数（口径同步：任何改 items 的 set 都必须走这里/带 counts）。 */
function itemsPatch(items: PeerHeldItemDto[]): Pick<PeerInboxState, 'items' | 'pendingCount' | 'unreadCount'> {
  return {
    items,
    pendingCount: peerInboxPendingCount(items),
    unreadCount: peerInboxUnreadCount(items),
  }
}

/**
 * 终态判据（聚合事件行体用）：declined/expired/accepted/dropped 等**不计**入 pending。
 *
 * <p>approved（迁移前历史中间态）也不再是待处理：它不可操作，计进角标只会让用户以为还有事要做。
 */
export function isTerminalHeldStatus(status: string | null | undefined): boolean {
  const s = (status ?? '').trim().toLowerCase()
  return s === 'declined' || s === 'rejected' || s === 'refused' || s === 'expired'
    || s === 'accepted' || s === 'dropped' || s === 'approved'
}

/** 计数值归一化：有限非负数才认（缺失/NaN/负数 = null，⛔ 不当 0）。 */
function normalizeCount(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? Math.floor(value) : null
}

/** 每会话计数之和（全局待处理总数）。 */
export function sumPeerCounts(counts: Record<string, number>): number {
  return Object.values(counts).reduce((n, v) => n + (Number.isFinite(v) ? v : 0), 0)
}

/**
 * 更新某会话计数并同步全局总数（唯一口径）。
 *
 * <p>值未变返回空 patch（避免无意义重渲染）；`sessionId` 为空 = 无归属，不写。
 */
function withSessionCount(
  state: Pick<PeerInboxState, 'countsBySession' | 'totalPending'>,
  sessionId: string | null,
  count: number,
): Partial<PeerInboxState> {
  if (!sessionId) return {}
  const current = state.countsBySession[sessionId] ?? 0
  if (current === count) return {}
  const countsBySession = { ...state.countsBySession, [sessionId]: count }
  return { countsBySession, totalPending: sumPeerCounts(countsBySession) }
}

function str(v: unknown): string | null {
  return typeof v === 'string' && v !== '' ? v : null
}

/** 收件箱 FIFO：heldAt 升序；缺时间排最后，再按 messageId 稳定。 */
function sortInboxItems(items: PeerHeldItemDto[]): PeerHeldItemDto[] {
  return items.slice().sort((a, b) => {
    const ta = a.heldAt ? Date.parse(a.heldAt) : NaN
    const tb = b.heldAt ? Date.parse(b.heldAt) : NaN
    const fa = Number.isFinite(ta)
    const fb = Number.isFinite(tb)
    if (fa && fb && ta !== tb) return ta - tb
    if (fa && !fb) return -1
    if (!fa && fb) return 1
    return a.messageId < b.messageId ? -1 : a.messageId > b.messageId ? 1 : 0
  })
}

/** 收件箱要拉取的会话：counts>0 的会话 + 当前会话（当前会话计数可能滞后）。 */
function inboxSessionIds(
  state: Pick<PeerInboxState, 'countsBySession' | 'sessionId' | 'pendingCount'>,
): string[] {
  const ids = new Set<string>()
  for (const [sid, n] of Object.entries(state.countsBySession)) {
    if (n > 0) ids.add(sid)
  }
  if (state.sessionId && state.pendingCount > 0) ids.add(state.sessionId)
  return [...ids]
}

/**
 * 实时事件载荷归一化 → {@link PeerHeldItemDto}。
 *
 * <p>后端设计稿与实施契约的字段名不一致（`fromSessionId/fromName/heldAt` vs
 * `sourceSessionId/sourceTitle/sourceKind/receivedAt/createdAt`）⇒ 全部认，缺失按 null。
 * messageId 缺失 = 无法增量合并（也无从去重）⇒ 返回 null，调用方丢弃。
 */
export function normalizeHoldEventItem(raw: Record<string, unknown> | null | undefined): PeerHeldItemDto | null {
  if (!raw) return null
  const messageId = str(raw.messageId)
  if (!messageId) return null
  return {
    messageId,
    targetSessionId: str(raw.targetSessionId),
    fromSessionId: str(raw.fromSessionId) ?? str(raw.sourceSessionId),
    fromName: str(raw.fromName) ?? str(raw.sourceName),
    fromSessionTitle: str(raw.fromSessionTitle) ?? str(raw.sourceTitle),
    fromAddress: str(raw.fromAddress) ?? str(raw.sourceAddress),
    fromMode: str(raw.fromMode) ?? str(raw.sourceMode),
    summary: str(raw.summary),
    reason: str(raw.reason),
    // 事件载荷的时间字段是 `createdAt`（后端 PeerInboxHoldEvent 同名），列表接口是 `heldAt`
    //    ⇒ 三个都认；否则实时插入的那一行「收到时间」为空。
    heldAt: str(raw.heldAt) ?? str(raw.receivedAt) ?? str(raw.createdAt),
    body: str(raw.body),
    status: str(raw.status),
    sourceKind: str(raw.sourceKind),
    seenAt: str(raw.seenAt),
    admission: str(raw.admission),
    admissionReason: str(raw.admissionReason) ?? str(raw.admission_reason),
    bodyLines: typeof raw.bodyLines === 'number' ? raw.bodyLines : null,
    bodyChars: typeof raw.bodyChars === 'number' ? raw.bodyChars : null,
  }
}

/**
 * 合并服务端最新列表：已加载项保持原位置（只更新字段），新到的追加到末尾。
 *
 * <p>WHY：轮询返回的是完整列表，若直接整体替换，Vue/React 的 key 虽能复用，但顺序可能被
 * 服务端重排，用户正在看的那一行会跳动。面板打开状态下「只更新列表与角标、不重排已加载项」。
 *
 * <p>已读不回退：本地已置 `seenAt` 的项，若服务端/重复事件回的是 `seenAt:null`（旧快照或
 * 重复推送），保留本地值 —— 否则已读项会「复活」成未读，重复响铃。
 */
function mergeHeldItems(existing: PeerHeldItemDto[], incoming: PeerHeldItemDto[]): PeerHeldItemDto[] {
  const byId = new Map(incoming.map((i) => [i.messageId, i]))
  const merged: PeerHeldItemDto[] = []
  const seen = new Set<string>()
  for (const item of existing) {
    const fresh = byId.get(item.messageId)
    if (fresh) {
      merged.push({ ...fresh, seenAt: fresh.seenAt ?? item.seenAt ?? null })
      seen.add(item.messageId)
    }
  }
  for (const item of incoming) {
    if (!seen.has(item.messageId)) {
      merged.push(item)
      seen.add(item.messageId)
    }
  }
  return merged
}

// ════════════════════════════════════════════════════════════════════════
// 新 hold 提示音（Web Audio 合成，零外部资源）
// ════════════════════════════════════════════════════════════════════════

type AudioContextCtor = new () => AudioContext

let holdAudioContext: AudioContext | null = null
let userGestureSeen = false
let gestureBound = false

function onUserGesture(): void {
  userGestureSeen = true
  if (typeof window !== 'undefined') {
    window.removeEventListener('pointerdown', onUserGesture, true)
    window.removeEventListener('keydown', onUserGesture, true)
  }
  // 首次交互后若已有挂起的上下文，顺手恢复（浏览器策略下可能仍被拦截，失败静默）。
  try {
    if (holdAudioContext && holdAudioContext.state === 'suspended') {
      void holdAudioContext.resume().catch(() => {})
    }
  } catch {
    /* 静默降级 */
  }
}

/** 监听首个用户手势：浏览器只允许在用户交互后创建/恢复 AudioContext。 */
function bindUserGestureOnce(): void {
  if (gestureBound || typeof window === 'undefined') return
  gestureBound = true
  window.addEventListener('pointerdown', onUserGesture, true)
  window.addEventListener('keydown', onUserGesture, true)
}

bindUserGestureOnce()

function resolveAudioCtor(): AudioContextCtor | null {
  if (typeof window === 'undefined') return null
  const w = window as unknown as { AudioContext?: AudioContextCtor; webkitAudioContext?: AudioContextCtor }
  return w.AudioContext ?? w.webkitAudioContext ?? null
}

/**
 * 播放「新待收件」提示音：约 120ms、双音（880Hz → 1174Hz），不依赖任何音频文件。
 *
 * <p>约束：
 * <ul>
 *   <li>未收到首个用户手势前不创建 AudioContext（浏览器自动播放策略）；</li>
 *   <li>创建/播放失败一律静默返回 —— ⛔ 不得打断列表刷新；</li>
 *   <li>调用方按「每批一次」调用，本函数不排队/不叠加。</li>
 * </ul>
 */
export function playPeerHoldChime(): void {
  if (!userGestureSeen) return
  const Ctor = resolveAudioCtor()
  if (!Ctor) return
  try {
    if (!holdAudioContext) holdAudioContext = new Ctor()
    const ctx = holdAudioContext
    if (ctx.state === 'suspended') void ctx.resume().catch(() => {})
    const master = ctx.createGain()
    master.gain.setValueAtTime(0.0001, ctx.currentTime)
    master.gain.exponentialRampToValueAtTime(0.12, ctx.currentTime + 0.012)
    master.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + 0.13)
    master.connect(ctx.destination)
    const tones = [880, 1174]
    tones.forEach((freq, index) => {
      const start = ctx.currentTime + index * 0.06
      const osc = ctx.createOscillator()
      const gain = ctx.createGain()
      osc.type = 'sine'
      osc.frequency.setValueAtTime(freq, start)
      gain.gain.setValueAtTime(0.0001, start)
      gain.gain.exponentialRampToValueAtTime(0.5, start + 0.008)
      gain.gain.exponentialRampToValueAtTime(0.0001, start + 0.055)
      osc.connect(gain)
      gain.connect(master)
      osc.start(start)
      osc.stop(start + 0.06)
    })
    // 提示音播完即断开总线，避免长期运行累积已连接的增益节点。
    window.setTimeout(() => {
      try {
        master.disconnect()
      } catch {
        /* 已在关闭 / 环境不支持 */
      }
    }, 250)
  } catch {
    /* 静默降级：不允许提示音失败影响收件列表 */
  }
}

/** 测试辅助：关闭并清空音频上下文与手势状态（生产代码不调用）。 */
export function __resetPeerHoldNotifyForTest(): void {
  try {
    void holdAudioContext?.close()
  } catch {
    /* noop */
  }
  holdAudioContext = null
  userGestureSeen = false
  if (typeof window !== 'undefined') {
    window.removeEventListener('pointerdown', onUserGesture, true)
    window.removeEventListener('keydown', onUserGesture, true)
  }
  gestureBound = false
  bindUserGestureOnce()
}

/**
 * 把一次完整的服务端列表应用到 store。
 *
 * <p><b>新 hold 事件</b>：同一会话内「**未读**条数增加」或「出现此前没有的**未读** messageId」。
 * 会话切换后的首屏是基线（用户主动导航带来的既有积压不算新到达）——不响、不自动弹。
 * 已读项（seenAt 非空）无论是否新到都不提醒。
 *
 * <p><b>⛔ 不再自动展开列表</b>（2026-11-22 用户裁定）：启动 / 切会话 / 实时来信都不弹；
 * 新到只做三件事 —— 更新列表与计数、提示音（同一批一次）、系统通知（见调用方）。
 */
function applyIncoming(
  current: PeerInboxState,
  incoming: PeerHeldItemDto[],
  sameSession: boolean,
  opts: { syncCounts?: boolean } = {},
): Partial<PeerInboxState> {
  const merged = mergeHeldItems(current.items, incoming)
  if (!sameSession) {
    return { ...itemsPatch(merged), loading: false, error: null }
  }

  const prevUnread = peerInboxUnreadCount(current.items)
  const mergedUnread = peerInboxUnreadCount(merged)
  const prevIds = new Set(current.items.map((i) => i.messageId))
  const isNewHold =
    mergedUnread > prevUnread ||
    incoming.some((i) => isUnreadHeldItem(i) && !prevIds.has(i.messageId))

  // 同一批只响一次，不叠加（列表已收起，没有「用户正看着」需要抑制的场景）。
  if (isNewHold) playPeerHoldChime()

  const counts = opts.syncCounts === false
    ? {}
    : withSessionCount(current, current.sessionId, peerInboxPendingCount(merged))
  return { ...itemsPatch(merged), ...counts, loading: false, error: null }
}


/** 已读上报在途集合（模块级）：同一批 id 不重复发请求（失败后清掉以便重试）。 */
const seenInFlight = new Set<string>()

// ════════════════════════════════════════════════════════════════════════
// 启动提示 + 系统通知（一律按 pending 口径，⛔ 不看 seenAt）
// ════════════════════════════════════════════════════════════════════════

/** 已做过「启动提示」判定的会话（本窗口内；切走再回来不重复提示/不重复弹）。 */
const startupChecked = new Set<string>()
/** 已发过系统通知的 messageId（事件重发/重连不重复弹系统通知）。 */
const alertedMessageIds = new Set<string>()
let alertSeq = 0
/** 跳转请求序号（App 以它去重；Date.now 同毫秒会漏）。 */
let jumpReqSeq = 0

/** 待处理项的来源标题（去重、最多 3 个；标题缺失由 peerHeldTitle 给「未命名会话」）。 */
function pendingTitles(items: PeerHeldItemDto[]): string[] {
  const seen = new Set<string>()
  for (const item of items) {
    if (!isPendingHeldItem(item) || isDeclinedHeldItem(item)) continue
    seen.add(peerHeldTitle(item))
  }
  return [...seen].slice(0, 3)
}

/**
 * 通知正文（与输入框常驻条同一口径 + 来源标题）。
 *
 * <p>系统通知必须显示《标题》（用户裁定）：只有一条来源 →「（来自《X》）」，
 * 多条 →「（来自《X》等 N 个会话）」；标题缺失时 peerHeldTitle 已回落「未命名会话」。
 */
function peerAlertText(count: number, titles: string[]): string {
  const head = titles.length === 0
    ? ''
    : titles.length === 1
      ? `（来自《${titles[0]}》）`
      : `（来自《${titles[0]}》等 ${titles.length} 个会话）`
  return `还有 ${count} 条未确认投递${head}，必须处理`
}

/**
 * 发一条「有待处理投递」系统通知，并把结果写进 `alert`（notified=false ⇒ App 用 toast 兜底）。
 *
 * <p>tag 按会话区分：Web 通道同 tag 只替换同会话的旧通知，不会顶掉轮次完成/提问通知。
 * 点击：Web 通道发 {@link PeerInboxState.requestJump}；Tauri 通道由 App 的
 * onSystemNotificationClick 读 {@code extra} —— 两条路径最终都跳会话 + 定位首条 pending 卡。
 *
 * @param messageId 可选：精确聚焦的那条 pending（非当前会话的实时 hold 会带上）
 */
async function raisePeerAlert(
  sessionId: string,
  count: number,
  source: 'startup' | 'realtime',
  titles: string[] = [],
  messageId: string | null = null,
): Promise<void> {
  const seq = ++alertSeq
  let notified = false
  try {
    notified = await sendSystemNotification({
      title: 'NexusAI · 有待确认投递',
      body: peerAlertText(count, titles),
      tag: `nexusai-peer-hold:${sessionId}`,
      extra: { sessionId, kind: 'peer-hold', messageId },
      onClick: () => {
        usePeerInboxStore.getState().requestJump(sessionId, messageId)
      },
    })
  } catch {
    notified = false
  }
  usePeerInboxStore.setState({ alert: { seq, sessionId, count, source, notified } })
}

/** 测试辅助：清空启动判定 / 通知去重 / 最近信号（生产代码不调用）。 */
export function __resetPeerInboxAlertForTest(): void {
  startupChecked.clear()
  alertedMessageIds.clear()
  alertSeq = 0
  jumpReqSeq = 0
  usePeerInboxStore.setState({
    alert: null,
    jumpRequest: null,
    inboxItems: [],
    inboxLoading: false,
    inboxError: null,
    inboxNotice: null,
    inboxRowErrors: {},
    inboxActing: null,
    open: false,
  })
}

export const usePeerInboxStore = create<PeerInboxState>()((set, get) => ({
  sessionId: null,
  items: [],
  loading: false,
  error: null,
  acting: null,
  actingAll: false,
  notice: null,
  rowErrors: {},
  pendingCount: 0,
  unreadCount: 0,
  lastSeq: null,
  countsBySession: {},
  totalPending: 0,
  inboxItems: [],
  inboxLoading: false,
  inboxError: null,
  inboxNotice: null,
  inboxRowErrors: {},
  inboxActing: null,
  jumpRequest: null,
  alert: null,
  open: false,

  load: async (sessionId, opts) => {
    if (!sessionId) {
      loadSeq++
      set({
        sessionId: null,
        ...itemsPatch([]),
        loading: false,
        error: null,
        acting: null,
        actingAll: false,
        notice: null,
        rowErrors: {},
        open: false,
      })
      return
    }
    const seq = ++loadSeq
    const mutAtStart = mutationSeq
    const switched = get().sessionId !== sessionId
    // 「启动 / 切会话后首次加载」：本窗口内每个会话只判定一次（轮询/重连不传 startup）。
    const startup = opts?.startup === true && !startupChecked.has(sessionId)
    set(switched
      ? {
          sessionId,
          ...itemsPatch([]),
          loading: true,
          error: null,
          acting: null,
          actingAll: false,
          notice: null,
          rowErrors: {},
          open: false,
        }
      : { loading: true, error: null })
    try {
      const incoming = await peerInboxApi.list(sessionId)
      if (seq !== loadSeq || mutAtStart !== mutationSeq || get().sessionId !== sessionId) return
      if (startup) startupChecked.add(sessionId)
      const patch = applyIncoming(get(), incoming, !switched)
      const pending = peerInboxPendingCount(incoming)
      // 列表刷新后清掉已不存在行的失败原因（不给消失的条目留孤儿文案）。
      // [peer-inbox-aggregate] 列表是当前会话 pending 的真源 ⇒ 同步每会话计数与全局总数。
      set((p) => ({
        ...patch,
        rowErrors: pruneRowErrors(p.rowErrors, patch.items ?? p.items),
        ...withSessionCount(p, sessionId, pending),
      }))
      // 启动提示：只看**未读**（已读积压重启不重复提醒），且 ⛔ 不展开列表、不播放提示音。
      // 已被实时事件通知过的 id 不再重复弹（非当前会话的新 hold 已走过一次系统通知）。
      const unread = peerInboxUnreadCount(incoming)
      if (startup && pending > 0 && unread > 0) {
        const unalerted = incoming.filter(
          (i) => isUnreadHeldItem(i) && !alertedMessageIds.has(i.messageId))
        if (unalerted.length > 0) {
          for (const i of unalerted) alertedMessageIds.add(i.messageId)
          void raisePeerAlert(sessionId, pending, 'startup', pendingTitles(incoming))
        }
      }
    } catch (e) {
      if (seq !== loadSeq || mutAtStart !== mutationSeq || get().sessionId !== sessionId) return
      set({ loading: false, error: errorText(e) })
    }
  },

  accept: async (messageId) => {
    const sessionId = get().sessionId
    if (!sessionId || get().acting || get().actingAll) return
    set((p) => ({ acting: messageId, notice: null, rowErrors: omitRowError(p.rowErrors, messageId) }))
    let raw: unknown
    try {
      raw = await peerInboxApi.approve(sessionId, messageId)
    } catch (e) {
      const hint = requestFailedText(errorText(e))
      if (get().sessionId === sessionId) {
        set((p) => ({ acting: null, notice: `接受失败：${hint}`, rowErrors: { ...p.rowErrors, [messageId]: hint } }))
      } else set({ acting: null })
      return
    }
    if (get().sessionId !== sessionId) {
      set({ acting: null })
      return
    }
    const outcome = readOutcome(raw)
    if (outcome.kind === 'transport') {
      const hint = requestFailedText(outcome.message)
      set((p) => ({ acting: null, notice: `接受失败：${hint}`, rowErrors: { ...p.rowErrors, [messageId]: hint } }))
      return
    }
    if (outcome.ok) {
      // 只移除待收件行；⛔ 不向 chatStore 插入消息（等后端 message.insert 整行）。
      mutationSeq++
      set((p) => {
        const items = p.items.filter((i) => i.messageId !== messageId)
        return {
          acting: null,
          ...itemsPatch(items),
          ...withSessionCount(p, sessionId, peerInboxPendingCount(items)),
          rowErrors: omitRowError(p.rowErrors, messageId),
          notice: '已接受，本条已投递到目标会话',
        }
      })
    } else {
      // 业务未达成（HELD / REFUSED / NO_LIVE_SESSION / NOT_DELIVERED / APPROVAL_REJECTED /
      // NOT_FOUND …）：行必须保留，原因同时挂到行上与会话提示。
      const text = peerApproveFailureText(outcome.code)
      set((p) => ({ acting: null, notice: text, rowErrors: { ...p.rowErrors, [messageId]: text } }))
    }
  },

  reject: async (messageId) => {
    const sessionId = get().sessionId
    if (!sessionId || get().acting || get().actingAll) return
    set((p) => ({ acting: messageId, notice: null, rowErrors: omitRowError(p.rowErrors, messageId) }))
    let raw: unknown
    try {
      raw = await peerInboxApi.reject(sessionId, messageId)
    } catch (e) {
      const hint = requestFailedText(errorText(e))
      if (get().sessionId === sessionId) {
        set((p) => ({ acting: null, notice: `拒绝失败：${hint}`, rowErrors: { ...p.rowErrors, [messageId]: hint } }))
      } else set({ acting: null })
      return
    }
    if (get().sessionId !== sessionId) {
      set({ acting: null })
      return
    }
    const outcome = readOutcome(raw)
    if (outcome.kind === 'transport') {
      const hint = requestFailedText(outcome.message)
      set((p) => ({ acting: null, notice: `拒绝失败：${hint}`, rowErrors: { ...p.rowErrors, [messageId]: hint } }))
      return
    }
    if (outcome.ok) {
      mutationSeq++
      set((p) => {
        const items = p.items.filter((i) => i.messageId !== messageId)
        return {
          acting: null,
          ...itemsPatch(items),
          ...withSessionCount(p, sessionId, peerInboxPendingCount(items)),
          rowErrors: omitRowError(p.rowErrors, messageId),
          notice: '已拒绝',
        }
      })
    } else {
      const text = peerApproveFailureText(outcome.code)
      set((p) => ({ acting: null, notice: `拒绝未达成：${text}`, rowErrors: { ...p.rowErrors, [messageId]: text } }))
    }
  },

  rejectAll: async () => {
    const sessionId = get().sessionId
    if (!sessionId || get().acting || get().actingAll) return
    set({ actingAll: true, notice: null })
    let raw: unknown
    try {
      raw = await peerInboxApi.rejectAll(sessionId)
    } catch (e) {
      // 失败：⛔ 不清列表、不动计数（保持与服务端一致，可重试）。
      if (get().sessionId === sessionId) set({ actingAll: false, notice: `全部拒绝失败：${requestFailedText(errorText(e))}` })
      else set({ actingAll: false })
      return
    }
    if (get().sessionId !== sessionId) {
      set({ actingAll: false })
      return
    }
    const outcome = readOutcome(raw, { inferRejectAll: true })
    if (outcome.kind === 'transport') {
      set({ actingAll: false, notice: `全部拒绝失败：${requestFailedText(outcome.message)}` })
      return
    }
    if (!outcome.ok) {
      // 业务未达成：列表与计数原样保留（⛔ 不假装清空）。
      set({ actingAll: false, notice: `全部拒绝失败：${peerApproveFailureText(outcome.code)}` })
      return
    }
    // 仅成功（success:true && code:0）才结算：按 rejected 条数移除（缺失 = 全部），
    // 角标/常驻条随 pendingCount 下降。
    mutationSeq++
    const rejected = outcome.rejected
    set((p) => {
      const remove = rejected === undefined
        ? p.items.length
        : Math.min(Math.max(rejected, 0), p.items.length)
      const items = p.items.slice(remove)
      return {
        ...itemsPatch(items),
        ...withSessionCount(p, sessionId, peerInboxPendingCount(items)),
        actingAll: false,
        rowErrors: pruneRowErrors(p.rowErrors, items),
        notice: rejected === undefined ? '已拒绝全部待处理投递' : `已拒绝 ${rejected} 条待处理投递`,
      }
    })
  },

  // 列表只展示会话（标题/条数/最早时间），不渲染正文、不标记已读 ⇒ 打开/关闭无副作用。
  openPanel: () => {
    set({ open: true, inboxNotice: null, inboxRowErrors: {} })
    void get().loadInbox()
  },

  closePanel: () => set({ open: false }),

  togglePanel: () => {
    if (get().open) {
      set({ open: false })
    } else {
      set({ open: true, inboxNotice: null, inboxRowErrors: {} })
      void get().loadInbox()
    }
  },

  loadInbox: async () => {
    if (!get().open) return
    let ids = inboxSessionIds(get())
    if (ids.length === 0) {
      // counts 可能还没对账：先拉一次基线，再决定有没有会话可拉。
      await get().reconcileCounts()
      ids = inboxSessionIds(get())
    }
    if (ids.length === 0) {
      set({ inboxItems: [], inboxLoading: false, inboxError: null, open: false })
      return
    }
    set({ inboxLoading: true, inboxError: null, inboxNotice: null })
    const results = await Promise.allSettled(ids.map((sid) => peerInboxApi.list(sid)))
    if (!get().open) return
    const merged: PeerHeldItemDto[] = []
    const seen = new Set<string>()
    let failures = 0
    let firstError = ''
    results.forEach((result, idx) => {
      if (result.status === 'fulfilled') {
        for (const raw of result.value ?? []) {
          if (!isPendingHeldItem(raw) || seen.has(raw.messageId)) continue
          seen.add(raw.messageId)
          merged.push({ ...raw, targetSessionId: raw.targetSessionId ?? ids[idx] })
        }
      } else {
        failures += 1
        if (!firstError) firstError = errorText(result.reason)
      }
    })
    const items = sortInboxItems(merged)
    const allFailed = failures === ids.length
    set({
      inboxItems: items,
      inboxLoading: false,
      inboxError: allFailed ? requestFailedText(firstError) : null,
      inboxNotice: !allFailed && failures > 0 ? '部分会话加载失败，可重试' : null,
    })
    if (!allFailed && items.length === 0) {
      // 计数说有、实际列表为空（可能已过期/在别处处理）⇒ 收起并纠正计数。
      set({ open: false })
      void get().reconcileCounts()
    }
  },

  acceptInbox: (messageId) => settleInboxItem(get, set, messageId, true),
  rejectInbox: (messageId) => settleInboxItem(get, set, messageId, false),

  requestJump: (sessionId, messageId) => {
    const sid = (sessionId ?? '').trim()
    if (!sid) return
    set({ jumpRequest: { sessionId: sid, messageId: messageId ?? null, seq: ++jumpReqSeq } })
  },

  applyRealtimeEvent: (event) => {
    if ((event?.type ?? '') !== 'peer.inbox.hold') return
    const sessionId = get().sessionId
    if (!sessionId) return
    // 载荷形态（以代码为准）：后端 PeerInboxHoldEvent 是**扁平**序列化
    //   （messageId/targetSessionId/sourceSessionId/createdAt… 全在顶层，见 E2E 原始帧），
    //   ⛔ 没有 item 包装。`item` 只作旧形态回落 —— 曾因只读 event.item，所有真实事件都在这里
    //   被当成「缺 messageId」丢掉，表现就是「角标 12s 不动、60s 轮询才补上」。
    const payload = (event.item ?? event) as Record<string, unknown>
    const item = normalizeHoldEventItem(payload)
    if (!item) return
    // 归属双重校验：topic 会话 / 载荷 sessionId / item.targetSessionId 都不能指向别的会话；
    // 两处都没给归属（无法证明属于当前会话）同样丢弃 —— 宁可等轮询补齐，也不串会话。
    // 这里读的是 store 里的**当前**会话（切会话后是新值），不依赖订阅时的闭包。
    const topicSession = str(event.sessionId)
    if (topicSession && topicSession !== sessionId) return
    if (item.targetSessionId && item.targetSessionId !== sessionId) return
    if (!topicSession && !item.targetSessionId) return
    // 真实新到达 = 此前列表里没有这个 messageId 且仍属未读（已读/重复推送不提醒）。
    const isNewArrival =
      isUnreadHeldItem(item) && !get().items.some((i) => i.messageId === item.messageId)
    // 与轮询不同：事件只带**一条**行，而 mergeHeldItems 以「服务端整表」为准（不在 incoming
    //   里的旧行会被丢掉）⇒ 传入「现有行 + 本条」= 增量 upsert。
    //   ⛔ 直接传 [item] 会把同会话其它待收件挤没（角标 2 → 1）。
    set(applyIncoming(get(), [...get().items, item], true))
    // 新 hold 实时到达 → 系统通知（同一 messageId 只发一次；同一批只有本事件一条）。
    if (isNewArrival && !alertedMessageIds.has(item.messageId)) {
      alertedMessageIds.add(item.messageId)
      void raisePeerAlert(
        sessionId, peerInboxPendingCount(get().items), 'realtime',
        pendingTitles(get().items), item.messageId)
    }
  },

  applyAggregateEvent: (event) => {
    const seq = normalizeCount(event?.seq)
    const targetSessionId = str(event?.targetSessionId)
    // 无 seq 无法判连续、无归属无法落计数 ⇒ 丢弃（等 counts 对账/重连补拉，⛔ 不猜）。
    if (seq == null || !targetSessionId) return
    const state = get()
    const lastSeq = state.lastSeq
    if (lastSeq != null && seq !== lastSeq + 1) {
      // 跳号或回退 = 丢帧：本帧**不得**增量应用（会与真实计数错位），改全量对账覆盖本地。
      void state.reconcileCounts()
      return
    }
    const type = (event.type ?? '').trim()
    const prev = state.countsBySession[targetSessionId] ?? 0
    const rawItem = (event.item ?? null) as Record<string, unknown> | null
    const serverCount = normalizeCount(event.pendingCount)
    const payload = (rawItem ?? event) as Record<string, unknown>
    const row = normalizeHoldEventItem(payload)
    // 终态（declined/expired/…）不计数：服务端 pendingCount 已排除；无计数时也不 +1。
    const terminal = row ? isTerminalHeldStatus(row.status) : false
    let next: number
    if (serverCount != null) next = serverCount
    else if (type === 'peer.inbox.hold') next = terminal ? prev : prev + 1
    else if (type === 'peer.inbox.removed') next = Math.max(0, prev - 1)
    else next = prev
    // 计数/序号先落库（目标不是当前会话时到此为止，⛔ 不动列表/不切会话）。
    set({ lastSeq: seq, ...withSessionCount(state, targetSessionId, next) })
    // 收件箱开着 ⇒ 增量维护（hold 直接插入；removed/updated 拉一次收件箱刷新）。
    if (state.open) {
      if (type === 'peer.inbox.hold' && row && !terminal) {
        const item: PeerHeldItemDto = { ...row, targetSessionId: row.targetSessionId ?? targetSessionId }
        set((p) => ({
          inboxItems: sortInboxItems([
            ...p.inboxItems.filter((i) => i.messageId !== item.messageId), item,
          ]),
        }))
      } else if (type === 'peer.inbox.removed' || type === 'peer.inbox.updated') {
        void state.loadInbox()
      }
    }
    if (state.sessionId !== targetSessionId) {
      // [非当前会话的新 hold] 也要有应用外提示：单靠左栏角标在左栏收起时完全无感。
      // 点击走 requestJump → App 切会话 + 定位该条卡；同一 messageId 只提醒一次。
      if (type === 'peer.inbox.hold' && row && !terminal
          && !alertedMessageIds.has(row.messageId)) {
        alertedMessageIds.add(row.messageId)
        void raisePeerAlert(targetSessionId, next, 'realtime', [peerHeldTitle(row)], row.messageId)
      }
      return
    }
    if (type === 'peer.inbox.hold' && row && !terminal) {
      // 事件自带行体：增量 upsert（复用实时合并的提醒/自动打开边沿）。
      state.applyRealtimeEvent(event as unknown as PeerInboxHoldEvent)
      // 列表合并后的 pending 可能与服务端计数有瞬时差 ⇒ 以事件计数为准回到同一口径。
      if (serverCount != null) set((p) => withSessionCount(p, targetSessionId, serverCount))
    } else {
      // updated / removed / 无行体：列表才是角标与提示条的真源 ⇒ 同步刷新一次（⛔ 不拉历史）。
      void state.load(targetSessionId)
    }
  },

  reconcileCounts: async () => {
    // 端点未接线（灰度期 / 测试替身）⇒ 不动本地状态，交由旧路径兜底。
    if (typeof peerInboxApi.counts !== 'function') return false
    if (reconcileInFlight) return reconcileInFlight
    reconcileInFlight = (async () => {
      try {
        const data = await peerInboxApi.counts()
        const counts: Record<string, number> = {}
        for (const [sid, value] of Object.entries((data?.counts ?? {}) as Record<string, unknown>)) {
          const n = normalizeCount(value)
          if (sid && n != null) counts[sid] = n
        }
        const seq = normalizeCount(data?.seq)
        // 整体覆盖（不是合并）：对账的语义就是「服务端说的才算」。
        set({
          countsBySession: counts,
          totalPending: sumPeerCounts(counts),
          ...(seq != null ? { lastSeq: seq } : {}),
        })
        // 当前会话的角标/提示条派生自列表 ⇒ 计数不一致时按服务端真值刷新一次。
        const sid = get().sessionId
        if (sid && (counts[sid] ?? 0) !== get().pendingCount) void get().load(sid)
        return true
      } catch {
        // 对账失败：⛔ 不清零、不覆盖（保留旧 seq，下一帧缺口会再次触发；重连/前台也会补拉）。
        return false
      } finally {
        reconcileInFlight = null
      }
    })()
    return reconcileInFlight
  },

  markSeenForVisible: async (visible) => {
    const state = get()
    const sessionId = state.sessionId
    // 触发方已保证「内联卡渲染到视野」；这里只做归属与去重（⛔ 不再依赖面板 open）。
    if (!sessionId) return
    const source = visible ?? state.items
    const ids = Array.from(new Set(
      source
        .filter((i) => isUnreadHeldItem(i) && !seenInFlight.has(i.messageId))
        .filter((i) => !i.targetSessionId || i.targetSessionId === sessionId)
        .map((i) => i.messageId),
    ))
    if (ids.length === 0) return
    for (const id of ids) seenInFlight.add(id)
    try {
      await peerInboxApi.markSeen(sessionId, ids)
      // 请求期间切了会话 → 旧会话的已读结果不得写到新列表。
      if (get().sessionId !== sessionId) return
      const now = new Date().toISOString()
      set((p) => {
        const items = p.items.map((item) =>
          ids.includes(item.messageId) && !item.seenAt ? { ...item, seenAt: now } : item)
        return itemsPatch(items)
      })
    } catch {
      // 失败静默：不回填 seenAt（⛔ 不假绿），保留行与未读角标，下次再试。
    } finally {
      for (const id of ids) seenInFlight.delete(id)
    }
  },
}))
