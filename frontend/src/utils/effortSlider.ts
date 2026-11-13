/**
 * 推理等级滑块 · 坐标 → 档位索引。
 *
 * 抽成纯函数的原因：这段逻辑在改动里已经错过两次 ——
 *   ① 用 `Math.round(p * (n - 1))` 会让每格的分界线整体偏移半格，
 *      于是点第 1 格（「关闭」）的右半边会落到第 2 格（low）；
 *   ② 用写死的 `slot === 5` 判断最后一格（ultracode），加一档之后它就指到 max 了。
 * ⇒ 现在写成「按格数均分 + 动态末格」，并由单测钉住每条边界。
 *
 * 前提：轨道内的格子是等宽均分的（`.eff-option { flex: 1 }`，轨道本身无 padding / gap）。
 *
 * @param p        指针在轨道内的水平比例，0 = 最左，1 = 最右（越界会被夹取）
 * @param slotCount 格总数 = 档位数 + 1（末格是 ultracode）
 * @returns 命中的格索引，`0 .. slotCount - 1`；`slotCount === 档位数` 即末格
 */
export function slotFromRatio(p: number, slotCount: number): number {
  if (!Number.isFinite(p) || slotCount <= 0) return 0
  const clamped = Math.min(1, Math.max(0, p))
  // floor 保证「这一格的整个宽度」都命中这一格；p=1 会算出 slotCount，故再夹一次。
  return Math.min(slotCount - 1, Math.floor(clamped * slotCount))
}
