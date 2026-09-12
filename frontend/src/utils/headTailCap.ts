// 头/尾高度截断的算术单点（工具卡共用）：超长结果的头/尾行数统一按「前 ceil(maxLines/2) 行 +
// 其余作为尾部行」计算，未超上限则 hidden ≤ 0（无行被隐藏）。
// 移植自 deepseek-harness/packages/client/ui-primitives/src/head-tail-cap.ts
// （MIT License, Copyright (c) 2026 DeepSeek）。
// 纯算术且只吃「行数」，不接触 AnsiLine —— 调用方用自己的行数组按 headLines/tailLines
// 切片，以便各块叠加自己的关注点。

/** 一列被截断行的头/尾拆分指标。 */
export interface HeadTailCap {
  /** 超出上限的行数（总行数 − maxLines）；≤ 0 表示未隐藏任何行。 */
  hidden: number
  /** 是否「超上限且未展开」，即正在展示头/尾切片。 */
  capped: boolean
  /** 头部切片行数：`ceil(maxLines / 2)`。 */
  headLines: number
  /** 尾部切片行数：头部切完后的余数。 */
  tailLines: number
}

/**
 * 按 `maxLines` 计算 `total` 行的头/尾截断指标（含 `expanded` 展开态）。
 * 纯算术；调用方自行用 `headLines`/`tailLines` 切自己的行。
 * 注意 `hidden` 可为负（未超上限时），判「是否隐藏了行」须用 `hidden > 0`
 * 而非 `capped` —— `capped` 含 `!expanded`，用它做按钮门控会使展开态按钮消失。
 * @param total - 列表总行数
 * @param maxLines - 折叠态的行数上限
 * @param expanded - 是否已展开（为真时 `capped` 恒为 false）
 */
export function headTailCap(total: number, maxLines: number, expanded: boolean): HeadTailCap {
  const hidden = total - maxLines
  const headLines = Math.ceil(maxLines / 2)
  return { hidden, capped: hidden > 0 && !expanded, headLines, tailLines: maxLines - headLines }
}
