import { describe, expect, it } from 'vitest'
import { tagToClass } from './data'

describe('tagToClass · 自由文本 tag 的哈希配色', () => {
  it('历史 5 值仍走原来的固定颜色槽（回归哨兵）', () => {
    expect(tagToClass('DS')).toBe('ds')
    expect(tagToClass('CL')).toBe('cl')
    expect(tagToClass('GP')).toBe('gp')
    expect(tagToClass('QW')).toBe('qw')
    expect(tagToClass('KIMI')).toBe('kimi')
  })

  it('未知 tag 落入 t1..t6 槽，且同一 tag 稳定复现', () => {
    expect(tagToClass('OR')).toMatch(/^t[1-6]$/)
    expect(tagToClass('OR')).toBe(tagToClass('OR'))
    expect(tagToClass('GRO')).toMatch(/^t[1-6]$/)
    expect(tagToClass('MCC')).toMatch(/^t[1-6]$/)
    expect(tagToClass('MM')).toMatch(/^t[1-6]$/)
  })

  it('空值安全兜底为 other（不抛）', () => {
    expect(tagToClass('')).toBe('other')
  })
})
