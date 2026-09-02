import { describe, expect, it } from 'vitest'
import { compactNumber } from '../format'

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
