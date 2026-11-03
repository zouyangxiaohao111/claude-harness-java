import { describe, expect, it } from 'vitest'
import { compactNumber, formatCountW } from '../format'

describe('compactNumber', () => {
  it('千分位 → compact 小写 k/m/b（WHY：消息/头部 token 用量展示，大数字需压成短文本避免撑破布局）', () => {
    expect(compactNumber(1321)).toBe('1.3k')
    expect(compactNumber(21000)).toBe('21k')
    expect(compactNumber(1500000)).toBe('1.5m')
  })
  it('小于千保持原样，0 正常输出（WHY：outputTokens 可能为空态/0，展示层需兜底）', () => {
    expect(compactNumber(999)).toBe('999')
    expect(compactNumber(0)).toBe('0')
  })
  it('k→m 进位边界归一化，避免 1000k（WHY：长会话累计 token 可能逼近百万，「1000k」是视觉 bug）', () => {
    expect(compactNumber(999999)).toBe('1m')
    expect(compactNumber(999950)).toBe('1m')
    expect(compactNumber(999949)).toBe('999.9k')
    expect(compactNumber(999500)).toBe('999.5k')
  })
})

/**
 * [skill-market v4 2026-11-01] 市场统计数字显示。
 *
 * WHY（规则九 · 验证意图）：用户拍板两件事 ——
 *   ① 统计数字必须**真实**（后端下发原始整数，前端格式化）；
 *   ② 单位统一用 **w**（与参考图一致，不用 k/m/亿）。
 * 这条的失败模式很隐蔽：若把「无数据（null）」渲染成 '0'，整个市场会出现一堆
 * 「0 次使用」的假象；若沿用 compactNumber 的 k/m，则会显示「615.6m」这种看不懂的写法。
 */
describe('formatCountW', () => {
  it('万为单位、后缀 w（WHY：用户拍板统一 w，参考图 615.64w 的写法）', () => {
    expect(formatCountW(6_156_400)).toBe('615.64w')
    expect(formatCountW(42_500)).toBe('4.25w')
    expect(formatCountW(232_200)).toBe('23.22w')
  })

  it('不足一万保持原数（WHY：9999 显示 9999 比 0.99w 更直观）', () => {
    expect(formatCountW(9_999)).toBe('9999')
    expect(formatCountW(0)).toBe('0')
  })

  it('整数万去掉尾随 0（WHY：120000 应该是 12w，不是 12.00w）', () => {
    expect(formatCountW(120_000)).toBe('12w')
    expect(formatCountW(100_000)).toBe('10w')
  })

  it('null/undefined → null（WHY：调用方据此整项不渲染；⛔ 绝不渲染成 0 —— 「无数据」≠「0 次使用」）', () => {
    expect(formatCountW(null)).toBeNull()
    expect(formatCountW(undefined)).toBeNull()
    expect(formatCountW(Number.NaN)).toBeNull()
  })
})
