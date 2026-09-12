import { describe, expect, it } from 'vitest'
import { isSubsequence } from '../subsequence.ts'

describe('isSubsequence · 边界（不变量判据自身的正确性）', () => {
  it('空串 / 完全相等 / sub 比 src 长 / 中间缺字符', () => {
    expect(isSubsequence('', 'abc')).toBe(true)       // 空 sub 恒为子序列（本函数自身的边界；`repair('')` 那条空转用例在 B 组，断言 `out === t`，不经本判据）
    expect(isSubsequence('', '')).toBe(true)
    expect(isSubsequence('abc', 'abc')).toBe(true)    // 完全相等
    expect(isSubsequence('abcd', 'abc')).toBe(false)  // sub 比 src 长 → 必不成立（超序列判据的反面）
    expect(isSubsequence('ac', 'abc')).toBe(true)     // 中间缺字符（跳过 b）仍是子序列
    expect(isSubsequence('acb', 'abc')).toBe(false)   // 顺序颠倒 → 不是子序列
    expect(isSubsequence('a', '')).toBe(false)        // src 为空而 sub 非空
  })
})
