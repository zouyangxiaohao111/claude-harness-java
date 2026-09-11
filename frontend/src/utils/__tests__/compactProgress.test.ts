import { describe, expect, it } from 'vitest'
import {
  COMPACT_DONE_HIDE_MS,
  EMPTY_COMPACT,
  EMPTY_COMPACT_TABLE,
  dropCompactSession,
  isCompactCanceled,
  reduceCompactTable,
  type CompactTable,
} from '../compactProgress'

/**
 * 压缩进度「按会话隔离」的纯函数回归。
 *
 * <p>WHY（为什么非测不可）：后端 compact-progress 是**会话级** topic
 * （/topic/sessions/{sid}/compact-progress），本仓是多会话 Web —— 原实现把进度态/累加器存成应用级
 * 单值，导致「A 压缩中切到 B」时 B 显示 A 的进度，且 B 的发送键变停止、点下去取消的是 B（不是真正
 * 在压缩的 A）。本组用例把「事件归属的 sid 决定写到哪个键、累加器也按同一键取」这条不变式钉住。
 *
 * <p>RED 条件（把实现改回全局单例时哪条断言会红 · 括号内为实测）：
 * <ul>
 *   <li>累积器回退成「跨会话共用一份 acc」（`const acc = Object.values(table.accs)[0]`，即原应用级
 *       firstChars 单值语义）→ 「两会话并发压缩：差分基准按会话各一份」红（<b>1 failed / 8</b>：B 的
 *       pct 被 A 的基准带偏）；</li>
 *   <li>reduceCompactTable 忽略 sid（ui/accs 换成单对象）→ 「A 的压缩进度只写 A 的键」红；</li>
 *   <li>hooks_start 回退成从全局状态取 pct → 「hooks_start 的阶段文案只改本会话」红。</li>
 * </ul>
 */

/** 依次喂事件（模拟 useChatSocket 的 handleCompactEvent：一条事件 → 新表）。 */
function feed(sid: string, events: Array<{ type?: string; hookType?: string; chars?: number }>, from: CompactTable = EMPTY_COMPACT_TABLE): CompactTable {
  let table = from
  for (const e of events) table = reduceCompactTable(table, sid, e).table
  return table
}

describe('reduceCompactTable · 会话隔离（A 的进度事件不影响 B）', () => {
  it('A 的压缩进度只写 A 的键，B 读到的是回落态（WHY：进度/告警横幅按会话键控，多会话并行不得串台）', () => {
    // 首帧只定基准（delta=0 → 8%），第二帧才推进：8 + floor(1600/8000×82) = 24
    let table = feed('sess-A', [{ type: 'compact_start' }, { type: 'compact_progress', chars: 2400 }, { type: 'compact_progress', chars: 4000 }])

    expect(table.ui['sess-A'].visible).toBe(true)
    expect(table.ui['sess-A'].pct).toBe(24)
    // B 未被写入：读取侧（selectCompact('sess-B')）回落 EMPTY_COMPACT —— 「A 压缩中切到 B」B 不显示 A 的进度
    expect(table.ui['sess-B']).toBeUndefined()
    expect(table.accs['sess-B']).toBeUndefined()
    // 显示条件（CompactProgressBar）：B 的键不存在 → visible=false → 不渲染 + 发送键不变停止
    expect((table.ui['sess-B'] ?? EMPTY_COMPACT).visible).toBe(false)
  })

  it('两会话并发压缩：差分基准按会话各一份，互不污染（WHY：原实现首帧 chars 基准是应用级单值，B 的首帧会顶掉 A 的基准 → A 的百分比跳变）', () => {
    // A 先开压（基准定为 1000），B 后开压（基准定为 9000，远大于 A）
    let table = feed('sess-A', [{ type: 'compact_start' }, { type: 'compact_progress', chars: 1000 }])
    table = feed('sess-B', [{ type: 'compact_start' }, { type: 'compact_progress', chars: 9000 }], table)

    // B 的首帧只影响 B：pct 均为起步 8%（各自的基准 = 各自首帧 chars）
    expect(table.ui['sess-A'].pct).toBe(8)
    expect(table.ui['sess-B'].pct).toBe(8)

    // A 继续推进 +2000 字符：delta 必须相对【A 自己的基准 1000】= 2000 → 8 + floor(2000/8000×82) = 28
    table = feed('sess-A', [{ type: 'compact_progress', chars: 3000 }], table)
    expect(table.ui['sess-A'].pct).toBe(28)
    // 若基准被 B 的 9000 顶掉，delta 会被 Math.max(0, 负) 夹成 0 → pct 恒 8（本断言即 RED）
    expect(table.ui['sess-B'].pct).toBe(8)
    expect(table.accs['sess-A'].firstChars).toBe(1000)
    expect(table.accs['sess-B'].firstChars).toBe(9000)
  })

  it('hooks_start 的阶段文案只改本会话，进度沿用【本会话】累加器（WHY：原实现回落全局 pct，会把别人会话的进度显示成本会话的）', () => {
    let table = feed('sess-A', [{ type: 'compact_start' }, { type: 'compact_progress', chars: 1000 }, { type: 'compact_progress', chars: 3000 }])
    table = feed('sess-B', [{ type: 'compact_start' }], table)
    // B 处于 8%，A 处于 28%：A 收到 hooks_start 必须显示 28%（而不是 B 的 8%）
    table = feed('sess-A', [{ type: 'hooks_start', hookType: 'post_compact' }], table)
    expect(table.ui['sess-A']).toMatchObject({ visible: true, status: 'running', hookType: 'post_compact', pct: 28 })
    expect(table.ui['sess-B'].pct).toBe(8)
  })

  it('compact_start 重置【本会话】累加器并清掉上一阶段 hookType（WHY：整表写入避免字段级 ?? 把上一阶段文案带过来）', () => {
    let table = feed('sess-A', [{ type: 'hooks_start', hookType: 'pre_compact' }, { type: 'compact_progress', chars: 5000 }])
    table = feed('sess-A', [{ type: 'compact_start' }], table)
    expect(table.ui['sess-A']).toEqual({ visible: true, status: 'running', hookType: undefined, pct: 8 })
    expect(table.accs['sess-A']).toEqual({ firstChars: null, pct: 8 })
  })

  it('compact_end 只把【本会话】置完成态并给出渐隐时长（WHY：每会话一个隐藏定时器，互不 clear）', () => {
    let table = feed('sess-A', [{ type: 'compact_start' }])
    table = feed('sess-B', [{ type: 'compact_start' }], table)
    const r = reduceCompactTable(table, 'sess-A', { type: 'compact_end' })
    expect(r.hideAfterMs).toBe(COMPACT_DONE_HIDE_MS)
    expect(r.table.ui['sess-A']).toMatchObject({ visible: true, status: 'done', pct: 100 })
    // B 仍在跑（A 的 end 不得终止 B 的进度）
    expect(r.table.ui['sess-B']).toMatchObject({ visible: true, status: 'running', pct: 8 })
  })

  it('未知子类型 / 空 sid 不改变表（WHY：脏载荷不得把进度态刷成缺省或写到空键）', () => {
    const table = feed('sess-A', [{ type: 'compact_start' }])
    const unknown = reduceCompactTable(table, 'sess-A', { type: 'whatever' })
    expect(unknown.changed).toBe(false)
    expect(unknown.table).toBe(table)
    const empty = reduceCompactTable(table, '', { type: 'compact_start' })
    expect(empty.changed).toBe(false)
    expect(empty.table).toBe(table)
  })
})

describe('dropCompactSession / isCompactCanceled（会话结束与用户取消不留残留）', () => {
  it('dropCompactSession 删掉该会话的 ui + 累加器，其余会话原样（WHY：完成态渐隐到期/删会话须释放该键，否则永不再被读）', () => {
    let table = feed('sess-A', [{ type: 'compact_start' }])
    table = feed('sess-B', [{ type: 'compact_start' }], table)
    const after = dropCompactSession(table, 'sess-A')
    expect(after.ui['sess-A']).toBeUndefined()
    expect(after.accs['sess-A']).toBeUndefined()
    expect(after.ui['sess-B']).toBeDefined()
    // 键不存在 → 读取侧回落稳定 EMPTY_COMPACT（横幅消失、发送键复原）
    expect(after.ui['sess-A'] ?? EMPTY_COMPACT).toBe(EMPTY_COMPACT)
    // 已不存在 → 原引用返回（不制造无谓新对象）
    expect(dropCompactSession(after, 'sess-A')).toBe(after)
  })

  it('isCompactCanceled 只认 canceled（WHY：用户点停止后后端 finally 仍推 compact_end，须据此抑制「压缩完成」回弹）', () => {
    expect(isCompactCanceled({ visible: false, status: 'canceled', hookType: undefined, pct: 0 })).toBe(true)
    expect(isCompactCanceled({ visible: true, status: 'done', hookType: undefined, pct: 100 })).toBe(false)
    expect(isCompactCanceled(undefined)).toBe(false)
  })
})
