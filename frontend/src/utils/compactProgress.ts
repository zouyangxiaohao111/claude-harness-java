import type { CompactProgressEventType } from '../api/types'

/**
 * 压缩进度 UI 态（**按会话键控** · chatStore.compact[sessionId]）。
 *
 * <p>[多会话隔离 2026-09-11] 后端 compact-progress 推的是**会话级** topic
 * （`/topic/sessions/{sid}/compact-progress` · CompactProgressState.java:118-121），而本仓是多会话 Web
 * （内存铁律 multi-session-vs-cc-single-session）——CC REPL.tsx:3000-3024 的全局单例态是对的（CC 单会话），
 * 本仓照搬会让「A 压缩中切到 B」时 B 显示 A 的进度（并把 B 的发送键变停止 → 取消错会话）。故按 sid 键控。
 */
export interface CompactUiState {
  visible: boolean
  status: 'running' | 'done' | 'canceled'
  /** hooks_start 阶段（pre_compact/post_compact/session_start）；undefined = 无阶段文案 */
  hookType: string | undefined
  pct: number
}

/** 无在飞压缩的读取回落（模块级稳定引用 —— selector 缺省返回同引用，避免每次新对象触发无谓重渲）。 */
export const EMPTY_COMPACT: CompactUiState = { visible: false, status: 'running', hookType: undefined, pct: 0 }

/**
 * 单会话进度累加器：firstChars = 首个 compact_progress 的 chars 基准（后端只推「已收字符数」无总量 →
 * 差分估进度），pct = 当前百分比。
 *
 * <p>原实现把基准存成应用级单值（App 只调一次 useChatSocket）→ 两会话并发压缩时 B 的首帧 chars
 * 覆盖 A 的基准 → A 的百分比跳变。故随会话键控（accs[sid]）。
 */
export interface CompactAccumulator {
  firstChars: number | null
  pct: number
}

export const EMPTY_COMPACT_ACC: CompactAccumulator = { firstChars: null, pct: 0 }

/**
 * 会话级压缩进度表（**唯一可变单元**）：ui + 累加器都按 sessionId 键控。
 * useChatSocket 持有一份（ref），chatStore 只镜像 ui 部分；本模块对此表的变换是纯函数（可测）。
 */
export interface CompactTable {
  ui: Record<string, CompactUiState>
  accs: Record<string, CompactAccumulator>
}

export const EMPTY_COMPACT_TABLE: CompactTable = { ui: {}, accs: {} }

/** compact_end（完成态 100%）后的停留时长：到期收起横幅 + 复位该会话累加器（渐隐，对齐原 900ms）。 */
export const COMPACT_DONE_HIDE_MS = 900

/** 摘要进度推算常量：起步 8%、按已收字符差分推进、封顶 90%（100% 只在 compact_end）。 */
const PCT_START = 8
const PCT_CAP = 90
/** 差分满量程字符数（≈ 一份摘要的典型长度）→ pct = 8% + delta/8000 × 82%。 */
const PCT_FULL_CHARS = 8000

/** compact-progress STOMP 载荷（后端 CompactProgressState.toFrontendJson · 未知子类型容忍） */
export type CompactProgressWire = { type?: string; hookType?: string; chars?: number }

export interface CompactTableResult {
  /** 新表（未变更时返回**原引用** —— 未知子类型不制造新对象） */
  table: CompactTable
  /** 本事件是否改变了该会话的 UI 态（false = 调用方不写 store / 不安排定时器） */
  changed: boolean
  /** 非 null → 调用方为该会话安排一次「延迟收起」定时器（每会话一个，不跨会话 clear） */
  hideAfterMs: number | null
}

/**
 * 压缩进度事件归一（**纯函数** · 会话级表 + sid + wire 载荷 → 新表 / 是否变更 / 延迟收起时长）。
 *
 * <p>抽成纯函数的用意：把「会话隔离」这条不变式做成可断言的**唯一入口** —— 事件归属会话由 sid 显式给定
 * （订阅闭包里的 sid），A 的事件在物理上不可能写进 B 的键；累加器也按同一键取，两会话并发时基准互不污染。
 *
 * <p>各子类型语义（对齐 CompactProgressState.toFrontendJson + 原 handleCompactEvent）：
 * <ul>
 *   <li>{@code compact_start} → 摘要请求前，进度起步 8%（该会话累加器重置）；</li>
 *   <li>{@code hooks_start} → 阶段文案切换（hookType），进度**沿用该会话当前 pct**（不回落别人会话的值）；</li>
 *   <li>{@code compact_progress{chars}} → 首帧定为差分基准，之后 pct = min(90, 8 + delta/8000 × 82)；</li>
 *   <li>{@code compact_end} → 100% 完成态 + hideAfterMs（调用方定时收起并复位该会话累加器）。</li>
 * </ul>
 * 未知子类型 / sid 为空 → 原样返回（changed=false，不写坏态）。
 */
export function reduceCompactTable(table: CompactTable, sid: string, raw: CompactProgressWire | CompactProgressEventType): CompactTableResult {
  if (!sid) return { table, changed: false, hideAfterMs: null }
  const acc = table.accs[sid] ?? EMPTY_COMPACT_ACC
  const put = (ui: CompactUiState, nextAcc: CompactAccumulator, hideAfterMs: number | null): CompactTableResult => ({
    table: { ui: { ...table.ui, [sid]: ui }, accs: { ...table.accs, [sid]: nextAcc } },
    changed: true,
    hideAfterMs,
  })
  switch (raw.type) {
    case 'compact_start':
      return put(
        { visible: true, status: 'running', hookType: undefined, pct: PCT_START },
        { firstChars: null, pct: PCT_START },
        null,
      )
    case 'hooks_start':
      // 阶段切换不推进进度：pct 取**该会话**累加器当前值
      return put(
        { visible: true, status: 'running', hookType: raw.hookType, pct: acc.pct },
        acc,
        null,
      )
    case 'compact_progress': {
      const chars = typeof raw.chars === 'number' ? raw.chars : 0
      const firstChars = acc.firstChars ?? chars
      const pct = Math.min(PCT_CAP, PCT_START + Math.max(0, Math.floor(((chars - firstChars) / PCT_FULL_CHARS) * (PCT_CAP - PCT_START))))
      return put(
        { visible: true, status: 'running', hookType: undefined, pct },
        { firstChars, pct },
        null,
      )
    }
    case 'compact_end':
      // finally（无论成败）→ 100% + 短暂完成态（渐隐由调用方定时收口）
      return put(
        { visible: true, status: 'done', hookType: undefined, pct: 100 },
        acc,
        COMPACT_DONE_HIDE_MS,
      )
    default:
      return { table, changed: false, hideAfterMs: null }
  }
}

/** 删除某会话的表内键（完成态渐隐到期 / 用户取消收口）→ 读取侧回落 EMPTY_COMPACT（横幅消失、发送键复原）。 */
export function dropCompactSession(table: CompactTable, sid: string): CompactTable {
  if (!table.ui[sid] && !table.accs[sid]) return table
  const ui = { ...table.ui }
  const accs = { ...table.accs }
  delete ui[sid]
  delete accs[sid]
  return { ui, accs }
}

/**
 * 会话压缩是否已被用户显式取消（App 停止键写入 status='canceled'）。
 * 用途：后端 finally 仍会推 compact_end —— 取消后不得再弹回「压缩完成」态。
 */
export function isCompactCanceled(ui: CompactUiState | undefined): boolean {
  return ui?.status === 'canceled'
}
