import { describe, expect, it } from 'vitest'
import { EMPTY_COMPACT, EMPTY_COMPACT_TABLE, dropCompactSession, reduceCompactTable, type CompactTable } from '../../utils/compactProgress'
import { createChatStore, selectCompact, selectTokenWarning } from '../chatStore'
import type { TokenWarningEvent } from '../../api/types'

/**
 * 压缩进度 / 压缩警告「按会话键控」的 store 级回归（事件 → 按 sid 写键 → 按 sid 读）。
 *
 * <p>WHY（为什么非测不可）：渲染侧（App.tsx 的 compactActive、CompactProgressBar、TokenWarningBanner）
 * 与发送键⇄停止键共用同一份状态。原实现是应用级单对象/单字段 → 「A 压缩中切到 B」时 B 显示 A 的进度、
 * B 的发送键变停止、点下去 cancel(B)（真正在压缩的 A 反而没被取消）；A 的上下文告警也会出现在 B。
 * 本组用例从「事件到达」这一端出发，断言读到的永远只是【被问的那个会话】那一份。
 *
 * <p>RED 条件（把实现改回全局单例时哪条断言会红 · 括号内为实测）：
 * <ul>
 *   <li>setCompact / setTokenWarning 忽略 sessionId（写固定键，= 全局单对象）→ <b>7 failed / 7</b>
 *       （B 读到了 A 的进度与告警）；</li>
 *   <li>selectCompact 忽略入参（取表里任一键，= 回退 `(s) => s.compact` 的语义）→ <b>4 failed / 3</b>
 *       （进度相关 4 条红：A/B 读到同一份）；</li>
 *   <li>clearCompact 不按键删除（写成「把整表清空」或写全局隐藏态）→ 「clearCompact 只删该会话的键」红
 *       （B 的进度被一起清掉）。</li>
 * </ul>
 */

/** 模拟 useChatSocket.handleCompactEvent 的落库环节：纯函数表变换 → 只把该 sid 的那一份写进 store。 */
function deliverCompact(s: ReturnType<typeof createChatStore>, sid: string, events: Array<{ type?: string; hookType?: string; chars?: number }>, table: CompactTable): CompactTable {
  let t = table
  for (const e of events) {
    const r = reduceCompactTable(t, sid, e)
    t = r.table
    if (r.changed) s.getState().setCompact(sid, t.ui[sid])
  }
  return t
}

const warning = (sessionId: string, tokenUsage: number): TokenWarningEvent => ({ type: 'token_warning', sessionId, suppressed: false, tokenUsage })

describe('chatStore.compact 按会话键控', () => {
  it('A 的压缩进度不影响 B 的读取（WHY：横幅/发送键只看当前活动会话那一份，多会话并行不得串台）', () => {
    const s = createChatStore()
    deliverCompact(s, 'sess-A', [{ type: 'compact_start' }, { type: 'compact_progress', chars: 2400 }, { type: 'compact_progress', chars: 4000 }], EMPTY_COMPACT_TABLE)

    // A：进行中 → 横幅可见 + 发送键变停止
    expect(selectCompact('sess-A')(s.getState()).visible).toBe(true)
    expect(selectCompact('sess-A')(s.getState()).pct).toBe(24)
    // B：无该键 → 回落稳定空态 → App.tsx 的 compactActive 假 → B 仍是【发送键】，不会 cancel 错会话
    expect(selectCompact('sess-B')(s.getState())).toBe(EMPTY_COMPACT)
    expect(selectCompact('sess-B')(s.getState()).visible).toBe(false)
  })

  it('活动会话切换后进度条依据正确：A 压缩中切到 B → B 不显示 A 的进度，切回 A 仍在（WHY：订阅常驻不退订，原实现会让 B 显示 A 的横幅）', () => {
    const s = createChatStore()
    deliverCompact(s, 'sess-A', [{ type: 'compact_start' }], EMPTY_COMPACT_TABLE)

    // 切到 B（activeSessionId=B）：读 B 的那一份
    expect(selectCompact('sess-B')(s.getState()).visible).toBe(false)
    // 切回 A：A 的进度仍在（未被切换动作清掉）
    expect(selectCompact('sess-A')(s.getState()).visible).toBe(true)
    // 无活动会话（null）→ 不渲染
    expect(selectCompact(null)(s.getState())).toBe(EMPTY_COMPACT)
  })

  it('两会话并发压缩时百分比基准互不污染（WHY：累加器按会话各一份；原实现共享一个 firstChars，B 的首帧会顶掉 A 的基准）', () => {
    const s = createChatStore()
    let t = deliverCompact(s, 'sess-A', [{ type: 'compact_start' }, { type: 'compact_progress', chars: 1000 }], EMPTY_COMPACT_TABLE)
    t = deliverCompact(s, 'sess-B', [{ type: 'compact_start' }, { type: 'compact_progress', chars: 9000 }], t)
    deliverCompact(s, 'sess-A', [{ type: 'compact_progress', chars: 3000 }], t)

    // A 的 delta 相对【A 的基准 1000】：8 + floor(2000/8000×82) = 28（若基准被 B 的 9000 顶掉 → 恒 8，RED）
    expect(selectCompact('sess-A')(s.getState()).pct).toBe(28)
    expect(selectCompact('sess-B')(s.getState()).pct).toBe(8)
  })

  it('clearCompact 只删该会话的键（WHY：完成态渐隐到期只收起正在压缩的那个会话，不得波及并行的另一会话）', () => {
    const s = createChatStore()
    let t = deliverCompact(s, 'sess-A', [{ type: 'compact_start' }], EMPTY_COMPACT_TABLE)
    t = deliverCompact(s, 'sess-B', [{ type: 'compact_start' }], t)
    s.getState().clearCompact('sess-A')
    t = dropCompactSession(t, 'sess-A')

    expect(selectCompact('sess-A')(s.getState())).toBe(EMPTY_COMPACT)
    expect(selectCompact('sess-B')(s.getState()).visible).toBe(true)
    expect(selectCompact('sess-B')(s.getState()).pct).toBe(8)
  })

  it('clearSession 一并释放该会话的进度与告警键（WHY：删会话 = 该会话内存整体释放，不留永不再读的键）', () => {
    const s = createChatStore()
    s.getState().setCompact('sess-A', { visible: true, status: 'running', hookType: undefined, pct: 12 })
    s.getState().setCompact('sess-B', { visible: true, status: 'running', hookType: undefined, pct: 12 })
    s.getState().setTokenWarning('sess-A', warning('sess-A', 100))
    s.getState().clearSession('sess-A')

    expect(s.getState().compact['sess-A']).toBeUndefined()
    expect(s.getState().tokenWarning['sess-A']).toBeUndefined()
    expect(s.getState().compact['sess-B']).toBeDefined()
  })
})

describe('chatStore.tokenWarning 按会话键控', () => {
  it('A 的上下文告警不影响 B 的读取（WHY：原全局单字段 → A 的告警横幅出现在 B，切会话也不清）', () => {
    const s = createChatStore()
    s.getState().setTokenWarning('sess-A', warning('sess-A', 12345))

    expect(selectTokenWarning('sess-A')(s.getState())?.tokenUsage).toBe(12345)
    // B 无该键 → null（TokenWarningBanner 不渲染；Composer 上下文条不会拿到 A 的值兜底）
    expect(selectTokenWarning('sess-B')(s.getState())).toBeNull()
    expect(selectTokenWarning(null)(s.getState())).toBeNull()
  })

  it('suppressed=true 只清除该会话的告警（WHY：压缩成功只收敛本会话告警，并行的另一会话告警仍在）', () => {
    const s = createChatStore()
    s.getState().setTokenWarning('sess-A', warning('sess-A', 1))
    s.getState().setTokenWarning('sess-B', warning('sess-B', 2))
    s.getState().setTokenWarning('sess-A', null)

    expect(selectTokenWarning('sess-A')(s.getState())).toBeNull()
    expect(selectTokenWarning('sess-B')(s.getState())?.tokenUsage).toBe(2)
  })
})
