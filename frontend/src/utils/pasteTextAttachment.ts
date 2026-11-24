/**
 * 输入框「大段文本粘贴 → txt 附件」（对齐 DSH 的 paste-as-text-attachment）。
 *
 * <p>DSH 默认阈值 = 10000 字符，按 JS {@code String.length}（UTF-16 码元）计，
 * 只有**超过**阈值才转换；文件名取内容首行（DSH 实测形如 {@code at org.springframework....txt}）。
 *
 * <p>⛔ 只接粘贴入口，不拦截正常键入 —— 与 DSH 的触发面一致（用户是「输入框输入」，
 * 实际动作是粘贴，键入超长不在此列）。
 */

/** 触发转换的字符数阈值（UTF-16 码元，严格大于）· 与 DSH 默认值一致。 */
export const PASTE_TEXT_ATTACHMENT_LIMIT = 10000

/** 文件名取首行时的最大字符数；超长追加 `....`（DSH 的附件名观感）。 */
const FILE_NAME_MAX_CHARS = 30

/** Windows / POSIX 都不接受的文件名字符 + 控制字符。 */
const INVALID_FILENAME_CHARS = /[\\/:*?"<>|\u0000-\u001F]/g

/**
 * 本次粘贴是否应转成 txt 附件。
 *
 * @param text      剪贴板 `text/plain` 内容
 * @param fileCount 剪贴板里同时携带的文件数 —— 有文件时交给既有文件通道，不重复处理
 */
export function shouldConvertPastedText(text: string, fileCount: number): boolean {
  return fileCount === 0 && text.length > PASTE_TEXT_ATTACHMENT_LIMIT
}

/**
 * 粘贴文本 → 附件文件名：取**第一段非空行**（日志首行常为空），截断到 30 字符后追加 `....`。
 *
 * <p>非法文件名字符替换为 `_`；尾部点/空格去掉（Windows 会静默吞掉尾部点，导致同名碰撞）。
 * 没有可用首行时回落 `pasted.txt`。截断后缀用 `...`，与扩展名的 `.txt` 连起来是 `....txt`（DSH 观感）。
 */
export function pasteTextFileName(text: string): string {
  const firstLine = text.split(/\r?\n/).find((line) => line.trim().length > 0)?.trim() ?? ''
  const cleaned = firstLine.replace(INVALID_FILENAME_CHARS, '_').trim()
  const truncated = cleaned.length > FILE_NAME_MAX_CHARS
  const base = (truncated ? cleaned.slice(0, FILE_NAME_MAX_CHARS).trimEnd() : cleaned)
    .replace(/[. ]+$/, '')
  return `${base || 'pasted'}${truncated ? '...' : ''}.txt`
}
