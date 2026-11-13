import { describe, expect, it } from 'vitest'

import { slotFromRatio } from '../effortSlider'

/**
 * 推理等级滑块的坐标映射 · 边界单测。
 *
 * 背景：加「关闭」档后出现过两个真实缺陷 ——
 *   ① 点「关闭」（第 1 格）的右半边落到 low（映射按 round，分界线偏移半格）；
 *   ② 点最后一格落到 max（末格索引被写死成 5，加档后变成 6）。
 * 本文件把这两类回归钉死在边界值上。
 */
describe('slotFromRatio', () => {
  const N = 7 // 关闭 / low / medium / high / xhigh / max / ultracode

  it('最左端命中第 0 格（关闭）', () => {
    expect(slotFromRatio(0, N)).toBe(0)
    expect(slotFromRatio(0.05, N)).toBe(0)
    // 第 1 格右半边（0.14 仍在本格内，1/7≈0.1429）—— 旧实现这里会算成 1（low）
    expect(slotFromRatio(0.13, N)).toBe(0)
  })

  it('每格整块宽度都命中自己（1/7 的整数倍是分界线）', () => {
    for (let i = 0; i < N; i++) {
      const left = i / N
      const right = (i + 0.999) / N
      expect(slotFromRatio(left, N)).toBe(i)
      expect(slotFromRatio(right, N)).toBe(i)
    }
  })

  it('每格中点命中该格', () => {
    for (let i = 0; i < N; i++) {
      expect(slotFromRatio((i + 0.5) / N, N)).toBe(i)
    }
  })

  it('最右端命中末格（ultracode），不会越界', () => {
    expect(slotFromRatio(0.99, N)).toBe(N - 1)
    expect(slotFromRatio(1, N)).toBe(N - 1)
    expect(slotFromRatio(1.5, N)).toBe(N - 1)
  })

  it('左越界夹到 0，非法输入不抛', () => {
    expect(slotFromRatio(-0.3, N)).toBe(0)
    expect(slotFromRatio(Number.NaN, N)).toBe(0)
    expect(slotFromRatio(0.5, 0)).toBe(0)
  })

  it('格数变化时仍然正确（加档 / 减档不会让末格错位）', () => {
    // 6 格（无关闭档）
    expect(slotFromRatio(0.99, 6)).toBe(5)
    expect(slotFromRatio(0.5, 6)).toBe(3)
    // 8 格
    expect(slotFromRatio(0.99, 8)).toBe(7)
    expect(slotFromRatio(0.5, 8)).toBe(4)
  })
})
