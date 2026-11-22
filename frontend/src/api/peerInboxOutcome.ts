import type {
  CommonResultDto,
  PeerHeldActionResultDto,
  PeerInboxRejectAllResultDto,
} from './types'

/**
 * 待收件审批/拒绝的**业务结果**归一化（后端统一返回体 `{code, msg, data, success}`）。
 *
 * <p>单独成模块（而不是塞进 `peerInbox.ts`）的原因：`peerInbox.ts` 被多个测试整体
 * `vi.mock` 掉，业务码判读若与 {@link peerInboxApi} 同模块，测试替身会连带把判读函数变成
 * undefined —— 判读是**纯函数**，必须独立可测、不被网络替身屏蔽。
 *
 * <h2>三层判定（互斥）</h2>
 * <ol>
 *   <li><b>成功</b>：`success === true` 且数字 `code === 0`。⛔ 只有这一层允许调用方移除行。</li>
 *   <li><b>业务未达成</b>：HTTP 200 但 `success === false` / 数字码非 0，业务原因优先取
 *       `data.status`（404 与 403 各承载两种业务结果，单看数字码无法区分）。</li>
 *   <li><b>通信失败</b>：非 2xx / 断网 / 非 JSON（由 `peerInbox.ts` 的 catch 产出 `kind:'transport'`）。</li>
 * </ol>
 *
 * <p><b>灰度兼容</b>：后端未更新的窗口里仍是旧扁平体（`code` 为字符串 `ACCEPTED/REJECTED/HELD…`），
 * 这里保留一版读取；新逻辑以**数字码**为准，字符串码只在没有统一返回体外壳时生效。
 */

/** 业务结果类别：业务达成 / 业务未达成 / 通信失败（三者互斥）。 */
export type PeerInboxOutcomeKind = 'ok' | 'business' | 'transport'

/** 审批/拒绝的业务结果（调用方按 `ok` 分支，⛔ 不看 HTTP 状态码）。 */
export interface PeerInboxActionOutcome {
  kind: PeerInboxOutcomeKind
  /** 仅统一返回体 `success===true && code===0`（或旧码 ACCEPTED/REJECTED）时为 true */
  ok: boolean
  /** 归一化后的符号业务码：`ACCEPTED`/`REJECTED`/`HELD`/`NO_LIVE_SESSION`/`NOT_FOUND`/
   *  `REFUSED`/`FORBIDDEN`/`NOT_DELIVERED`/`APPROVAL_REJECTED`/`UNKNOWN`/`TRANSPORT_ERROR` */
  code: string
  /** 服务端文案（`msg` → `data.message` → `data.detail` → 旧 `message`/`detail`；缺失 = 空串） */
  message: string
  /** 新契约的数字业务码原值（旧字符串码 / 通信失败 = null） */
  numericCode?: number | null
  /** 批量拒绝实际结算条数（仅 rejectAll 携带） */
  rejected?: number
}

/** 数字业务码（新契约）→ 符号码，`data.status` 用于区分 404/403 的两种业务结果。 */
export function businessCodeOfNumeric(code: number, status?: string | null): string {
  const s = typeof status === 'string' ? status.trim().toUpperCase() : ''
  switch (code) {
    case 423:
      return 'HELD'
    case 404:
      return s === 'NO_LIVE_SESSION' ? 'NO_LIVE_SESSION' : 'NOT_FOUND'
    case 403:
      return s === 'REFUSED' ? 'REFUSED' : 'FORBIDDEN'
    case 500:
      return 'NOT_DELIVERED'
    case 900:
      return 'APPROVAL_REJECTED'
    default:
      return 'UNKNOWN'
  }
}

function asRecord(v: unknown): Record<string, unknown> | null {
  return v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : null
}

/**
 * 是否为统一返回体（CommonResult）。
 *
 * <p>判据：对象、非数组，且有布尔 `success` 或**数字** `code`。旧实现的 `code` 是字符串
 * （`ACCEPTED/REJECTED/…`）⇒ 不会被误认成新返回体，灰度兼容分支因此可靠。
 */
export function isCommonResult(raw: unknown): raw is CommonResultDto {
  const r = asRecord(raw)
  if (!r) return false
  return typeof r.success === 'boolean' || typeof r.code === 'number'
}

function upper(v: unknown): string {
  return typeof v === 'string' ? v.trim().toUpperCase() : ''
}

function pickMessage(r: Record<string, unknown>, data: Record<string, unknown> | null): string {
  const candidates = [r.msg, data?.message, data?.detail, r.message, r.detail]
  for (const v of candidates) {
    if (typeof v === 'string' && v.trim()) return v.trim()
  }
  return ''
}

/**
 * 业务载荷 → {@link PeerInboxActionOutcome}（前端唯一判读点）。
 *
 * <p>三种输入都认：① 统一返回体 `{code:number,msg,data,success}`；② 旧后端扁平体
 * `{code:'ACCEPTED'|…, status, message, detail}`；③ 只带 `rejected:N` 的批量拒绝回执（旧实现）。
 * 无法识别的输入一律按**未达成**（UNKNOWN），⛔ 绝不因 HTTP 200 而当成功。
 */
export function normalizeActionOutcome(
  raw: (PeerHeldActionResultDto & PeerInboxRejectAllResultDto) | CommonResultDto | null | undefined,
  opts: { inferRejectAll?: boolean } = {},
): PeerInboxActionOutcome {
  const r = asRecord(raw) ?? {}
  const data = asRecord(r.data)
  // data 缺失（旧扁平体）时字段就在顶层，统一按同一份记录读。
  const payload = data ?? r

  const rejected =
    typeof payload.rejected === 'number'
      ? payload.rejected
      : typeof r.rejected === 'number'
        ? r.rejected
        : undefined
  const status = upper(payload.status)
  const message = pickMessage(r, data)
  const withRejected = rejected === undefined ? {} : { rejected }

  if (isCommonResult(raw)) {
    const numeric = typeof r.code === 'number' ? r.code : null
    // 成功 = success 为 true **且** 数字码为 0（缺码时以 success 为准，仍不放宽到「码非空即成功」）。
    const ok = r.success === true && (numeric === null || numeric === 0)
    if (ok) {
      const code = status || (opts.inferRejectAll ? 'REJECTED' : 'ACCEPTED')
      return { kind: 'ok', ok: true, code, message, numericCode: numeric, ...withRejected }
    }
    return {
      kind: 'business',
      ok: false,
      code: numeric === null ? 'UNKNOWN' : businessCodeOfNumeric(numeric, status),
      message,
      numericCode: numeric,
      ...withRejected,
    }
  }

  // 旧实现（灰度期）：字符串码，`code` 优先于同义 `status`。
  let code = upper(r.code ?? payload.code ?? status) || 'UNKNOWN'
  if (code === 'UNKNOWN' && opts.inferRejectAll && rejected !== undefined) code = 'REJECTED'
  const ok = code === 'ACCEPTED' || code === 'REJECTED'
  return { kind: ok ? 'ok' : 'business', ok, code, message, numericCode: null, ...withRejected }
}
