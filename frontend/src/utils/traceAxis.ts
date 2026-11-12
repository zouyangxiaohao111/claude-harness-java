/**
 * 知识处理瀑布图的时间轴（源站 `utils/traceAxis.ts` 逐字移植）。
 *
 * <h2>为什么不能把原始时长四等分</h2>
 * 时间线曾经把 trace 总时长分四份，494ms 的 trace 就被画成
 * 0 / 124 / 247 / 371 / 494ms。这些刻度既精确又没用：肉眼没法拿条形去比，
 * 而且 trace 还在跑时，每个轮询周期数字都在变。
 *
 * <p>所以改成：把刻度间隔取整到 `1 / 2 / 2.5 / 5 × 10^n`，轴上限向外扩到整数个间隔。
 * 条形按 `maxMs` 定位、⛔ 不按原始总时长 —— 这样「条形显示什么」和「刻度声称什么」永远一致。
 */

export interface AxisTick {
  left: string
  label: string
}

export interface TraceAxis {
  stepMs: number
  maxMs: number
}

const DEFAULT_TARGET_INTERVALS = 5
const MAX_TICKS = 12

/** 把粗糙间隔向上取整到最近的 `1 / 2 / 2.5 / 5 × 10^n`。 */
export function niceAxisStep(roughStep: number): number {
  if (!Number.isFinite(roughStep) || roughStep <= 0) return 1
  const base = Math.pow(10, Math.floor(Math.log10(roughStep)))
  const n = roughStep / base
  const mult = n <= 1 ? 1 : n <= 2 ? 2 : n <= 2.5 ? 2.5 : n <= 5 ? 5 : 10
  return mult * base
}

/** 由总时长算出 `{ stepMs, maxMs }`。退化输入 ⇒ `{ 0, 0 }`，⛔ 不产出 NaN。 */
export function computeTraceAxis(
  totalMs: number,
  targetIntervals = DEFAULT_TARGET_INTERVALS,
): TraceAxis {
  if (!Number.isFinite(totalMs) || totalMs <= 0) return { stepMs: 0, maxMs: 0 }
  const stepMs = niceAxisStep(totalMs / targetIntervals)
  return { stepMs, maxMs: Math.max(stepMs, Math.ceil(totalMs / stepMs) * stepMs) }
}

/**
 * 整把尺子只用<b>一个</b>单位，由轴上限决定。
 * 若按每个刻度值各自选单位，同一条轴上会印出 `0ms / 10.00s / 20.00s`。
 */
export function formatAxisLabel(ms: number, maxMs: number): string {
  if (maxMs >= 60000) {
    let mins = Math.floor(ms / 60000)
    let secs = Math.round((ms % 60000) / 1000)
    // 进位：119999ms 要读成 2m00s，不能是 1m60s。
    if (secs >= 60) {
      mins += 1
      secs -= 60
    }
    return `${mins}m${String(secs).padStart(2, '0')}s`
  }
  if (maxMs >= 1000) {
    const secs = ms / 1000
    return `${Number.isInteger(secs) ? secs : secs.toFixed(1)}s`
  }
  return `${Math.round(ms)}ms`
}

/** 生成刻度；刻度数受 {@link MAX_TICKS} 约束。 */
export function buildAxisTicks(axis: TraceAxis): AxisTick[] {
  const { stepMs, maxMs } = axis
  if (!stepMs || !maxMs) return []
  const ticks: AxisTick[] = []
  // `stepMs / 1000` 是浮点补偿：否则累加到末刻度时差一点点，最后一个刻度会掉。
  for (let v = 0; v <= maxMs + stepMs / 1000 && ticks.length < MAX_TICKS; v += stepMs) {
    ticks.push({ left: `${(v / maxMs) * 100}%`, label: formatAxisLabel(v, maxMs) })
  }
  return ticks
}

/** 网格线间距，表示成条形轨道的 CSS 百分比。 */
export function axisGridStepPct(axis: TraceAxis): string {
  if (!axis.maxMs || !axis.stepMs) return '25%'
  return `${(axis.stepMs / axis.maxMs) * 100}%`
}
