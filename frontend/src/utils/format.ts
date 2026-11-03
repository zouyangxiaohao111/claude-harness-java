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

/**
 * 市场统计数字显示（使用量 / 下载量）· **万为单位，后缀 `w`**。
 *
 * <p>用户 2026-11-01 拍板：与参考图一致用 `w`，不用「万」/「k」。
 * 例：6156400 → `615.64w`；42500 → `4.25w`；9999 → `9999`（< 1 万不换算）。
 *
 * <p><b>⚠️ 与 {@link compactNumber} 分开的两个理由</b>：
 * <ol>
 *   <li>单位不同 —— compactNumber 走 k/m（token 计数的国际惯例），
 *       市场统计走中文用户习惯的「万」；两者混用会出现「1.5m 次使用」这种看不懂的写法。</li>
 *   <li>精度不同 —— token 计数保留 1 位小数即可（1.3k），统计量级大且要显得精确，保留 2 位。</li>
 * </ol>
 *
 * <p><b>入参契约</b>：后端下发<b>原始整数</b>（如 6156400），绝不传「615.64w」这种展示串。
 * 传 null/undefined → 返回 null，调用方据此<b>整项不渲染</b>
 * （⛔ 不是渲染 0 —— 「无数据」与「0 次使用」是两回事）。
 */
export function formatCountW(n: number | null | undefined): string | null {
  if (n === null || n === undefined || !Number.isFinite(n)) return null
  const abs = Math.abs(n)
  if (abs < 10_000) return String(Math.round(n))
  const w = n / 10_000
  // 两位小数并去掉尾随 0：615.64w / 4.25w / 12w（12.00 → 12）
  return `${w.toFixed(2).replace(/\.?0+$/, '')}w`
}
