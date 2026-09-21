/**
 * 数字紧凑显示 · 千分位 → compact notation 小写 k/m/b（对齐 CC utils/format.ts:124-131）
 * 1321 → '1.3k'；21000 → '21k'；1500000 → '1.5m'
 * 供消息/头部 token 用量展示（F37 / StreamHeader）。
 */

/** 保留 1 位小数（21 → 21，1.3 → 1.3，浮点自动归一化）。 */
function round1(v: number): number {
  return Math.round(v * 10) / 10
}

export function compactNumber(n: number): string {
  const abs = Math.abs(n)
  if (abs >= 1_000_000) return round1(n / 1_000_000) + 'm'
  if (abs >= 1_000) {
    const k = round1(n / 1_000)
    // 进位边界兜底：999.9k 四舍五入后为 1000k，归一化到 m（避免长会话累计 token 显示 '1000k'）
    if (Math.abs(k) >= 1000) return round1(n / 1_000_000) + 'm'
    return k + 'k'
  }
  return String(n)
}

/**
 * 文件大小显示 · 逐条对齐 CC {@code utils/format.ts:9-23 formatFileSize}
 * （1023 → '1023 bytes'；1024 → '1KB'；1572864 → '1.5MB'；一位小数并去掉尾随 {@code .0}）。
 *
 * 供 SendUserMessage 附件列表（CC BriefTool/UI.tsx:99 的 {@code ({formatFileSize(att.size)})}）。
 */
export function formatFileSize(sizeInBytes: number): string {
  const kb = sizeInBytes / 1024
  if (kb < 1) return `${sizeInBytes} bytes`
  if (kb < 1024) return `${kb.toFixed(1).replace(/\.0$/, '')}KB`
  const mb = kb / 1024
  if (mb < 1024) return `${mb.toFixed(1).replace(/\.0$/, '')}MB`
  const gb = mb / 1024
  return `${gb.toFixed(1).replace(/\.0$/, '')}GB`
}
