import { describe, expect, it } from 'vitest'
import {
  axisGridStepPct,
  buildAxisTicks,
  computeTraceAxis,
  formatAxisLabel,
  niceAxisStep,
} from '../traceAxis'

/**
 * 用例大半逐字照抄源站 `utils/traceAxis.test.ts`（那是在钉真值），
 * 另补派单书点名的判据：五档阶梯、494ms 轴、进位、单位一致、末刻度、MAX_TICKS。
 */

describe('niceAxisStep · 1 / 2 / 2.5 / 5 × 10^n 阶梯', () => {
  it('逐个档位向上取整（WHY：不取整就无法肉眼比对条形）', () => {
    expect(niceAxisStep(1)).toBe(1)
    expect(niceAxisStep(0.9)).toBe(1)
    expect(niceAxisStep(1.4)).toBe(2)
    expect(niceAxisStep(2.4)).toBe(2.5)
    expect(niceAxisStep(4)).toBe(5)
    // 10 不是独立档位，退化成「1 × 10¹」
    expect(niceAxisStep(7)).toBe(10)
    expect(niceAxisStep(10)).toBe(10)
    // 跨量级：6000 ⇒ 1 × 10⁴
    expect(niceAxisStep(6000)).toBe(10000)
    expect(niceAxisStep(98.8)).toBe(100)
  })

  it('退化输入回落到 1，不产出 NaN', () => {
    expect(niceAxisStep(0)).toBe(1)
    expect(niceAxisStep(-5)).toBe(1)
    expect(niceAxisStep(Number.NaN)).toBe(1)
    expect(niceAxisStep(Number.POSITIVE_INFINITY)).toBe(1)
  })
})

describe('computeTraceAxis · 轴上限必须是整数个步长', () => {
  it('退化时长产出空轴而不是 NaN', () => {
    for (const bad of [0, -1, Number.NaN, Number.POSITIVE_INFINITY]) {
      expect(computeTraceAxis(bad)).toEqual({ stepMs: 0, maxMs: 0 })
      expect(buildAxisTicks(computeTraceAxis(bad))).toEqual([])
    }
    expect(axisGridStepPct({ stepMs: 0, maxMs: 0 })).toBe('25%')
  })

  it('maxMs 是整步长且覆盖总时长（WHY：条形按 maxMs 定位，轴截断就会溢出）', () => {
    for (const total of [494, 1, 51, 4200, 42300, 300000, 987654]) {
      const { stepMs, maxMs } = computeTraceAxis(total)
      expect(maxMs, `${total}: 轴 ${maxMs} 截断了 trace`).toBeGreaterThanOrEqual(total)
      expect(Math.abs(maxMs / stepMs - Math.round(maxMs / stepMs))).toBeLessThan(1e-9)
    }
  })

  it('494ms 的真实缺陷：刻度落到整百，而不是 0/124/247/371/494', () => {
    const axis = computeTraceAxis(494)
    expect(axis).toEqual({ stepMs: 100, maxMs: 500 })
    expect(axis.maxMs % axis.stepMs).toBe(0)
    expect(axis.maxMs).toBeGreaterThanOrEqual(494)
    expect(buildAxisTicks(axis).map((t) => t.label)).toEqual([
      '0ms', '100ms', '200ms', '300ms', '400ms', '500ms',
    ])
    expect(buildAxisTicks(axis).map((t) => t.left)).toEqual([
      '0%', '20%', '40%', '60%', '80%', '100%',
    ])
    expect(axisGridStepPct(axis)).toBe('20%')
  })
})

describe('formatAxisLabel · 全尺一个单位 + 进位', () => {
  it('单位由 maxMs 决定：ms / s / m', () => {
    expect(formatAxisLabel(0, 500)).toBe('0ms')
    expect(formatAxisLabel(250, 500)).toBe('250ms')
    // 轴到 1s 后连零刻度也用秒，否则同轴出现「0ms」和「10s」
    expect(formatAxisLabel(0, 50000)).toBe('0s')
    expect(formatAxisLabel(10000, 50000)).toBe('10s')
    expect(formatAxisLabel(2500, 12500)).toBe('2.5s')
    expect(formatAxisLabel(0, 300000)).toBe('0m00s')
    expect(formatAxisLabel(75000, 300000)).toBe('1m15s')
  })

  it('119999ms 必须读成 2m00s，不能是 1m60s（进位）', () => {
    expect(formatAxisLabel(119_999, 120_000)).toBe('2m00s')
    expect(formatAxisLabel(119_999, 120_000).endsWith('2m00s')).toBe(true)
    expect(formatAxisLabel(119_999, 120_000)).not.toContain('1m60s')
    // 边界正下方不应误进位
    expect(formatAxisLabel(119_499, 120_000)).toBe('1m59s')
    expect(formatAxisLabel(599_999, 600_000)).toBe('10m00s')
  })

  it('同一条轴上所有刻度用同一个单位（不会 0ms 与 10.00s 混排）', () => {
    for (const total of [494, 42300, 300000, 7_200_000]) {
      const ticks = buildAxisTicks(computeTraceAxis(total))
      const units = new Set(ticks.map((t) => t.label.replace(/[\d.]/g, '')))
      expect(units.size, `${total}: 混排 ${[...units].join(' / ')}`).toBe(1)
    }
  })
})

describe('buildAxisTicks · 末刻度与刻度数', () => {
  it('刻度数上限 MAX_TICKS = 12（WHY：刻度挤满会把轴压成噪音）', () => {
    const crowded = buildAxisTicks({ stepMs: 1, maxMs: 1000 })
    expect(crowded.length).toBe(12)
    for (const total of [494, 42300, 300000, 7_200_000]) {
      const ticks = buildAxisTicks(computeTraceAxis(total))
      expect(ticks.length).toBeLessThanOrEqual(12)
      expect(ticks.length).toBeGreaterThanOrEqual(2)
    }
  })

  it('末刻度落在 maxMs（左端 100%），浮点补偿生效', () => {
    for (const total of [494, 42300, 300000, 7_200_000]) {
      const axis = computeTraceAxis(total)
      const ticks = buildAxisTicks(axis)
      expect(ticks[ticks.length - 1].left, `${total}: 末刻度没落到轴上限`).toBe('100%')
    }
  })

  it('累加漂移的步长也必须补出末刻度（WHY：0.3 累加到 1.8 会差 2e-16）', () => {
    // 0.3 在二进制里不可精确表示；累加得 1.7999999999999998，
    // 若上限只用 maxMs，`v <= maxMs` 会把末刻度整格丢掉。
    const axis = { stepMs: 0.3, maxMs: 1.7999999999999998 }
    const ticks = buildAxisTicks(axis)
    // 没有补偿时只有 6 格（末格 1.8 > 上限）；有补偿才补出第 7 格。
    expect(ticks.length).toBe(7)
    expect(Number.parseFloat(ticks[ticks.length - 1].left)).toBeGreaterThan(99.9)
  })
})
