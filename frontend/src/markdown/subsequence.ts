/**
 * 判定 `sub` 是否为 `src` 的子序列（双指针）。
 *
 * 用途：本模块的「抢救只做插入」结构不变量 —— `repair(t).out` 恒为 `t` 的超序列，
 * 即 `t` 恒为 `out` 的子序列。**唯一消费者是同目录的单元测试**
 * （`__tests__/rescue.invariant.test.ts` 的 A 组）；
 * `front/scripts/rescue-corpus-check.mts` 的「回归」判据**不**经本函数 —— 它沿用 §6 基线的
 * `norm()`（剥空白后等值）口径，两者并非等价判据（该脚本头部「回归」定义处有说明）。
 *
 * @param sub 候选子序列
 * @param src 被判定为超序列的一方
 * @returns `sub` 是 `src` 的子序列时为 true（空 `sub` 恒为 true）
 */
export function isSubsequence(sub: string, src: string): boolean {
  let i = 0
  for (let j = 0; j < src.length && i < sub.length; j++) if (sub[i] === src[j]) i++
  return i === sub.length
}
