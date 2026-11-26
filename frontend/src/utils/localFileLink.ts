/**
 * 对话正文里的「本地文件链接」通道（用户 2026-11-26 缺陷：链接是蓝的，点不动）。
 *
 * <p><b>WHY href 会丢</b>：模型常把产物写成 `[打开入口页](file:///D:/.../x.html)`。
 * DOMPurify 默认把 `file:` / Windows 盘符这类协议视为不安全 ⇒ 整个 `href` 被剥掉，
 * 只剩一个没有 href 的 `<a>`：CSS 仍按链接上色（看起来可点），点击却什么也不发生。
 * 所以这类链接改由 marked 渲染成 `data-local-file`，点击走系统默认程序打开。
 *
 * <p><b>⚠️ 扩展名白名单</b>：链接文字来自模型输出，`openPath` 对 exe/bat 等于直接
 * 运行程序 ⇒ 只放行常见文档/媒体类型，其余拒绝并留日志。
 */

/** 允许交给系统默认程序打开的类型（文档 / 图片 / 音视频）。 */
const OPENABLE_EXT = /\.(?:html?|pdf|md|markdown|txt|log|csv|json|ya?ml|png|jpe?g|gif|webp|bmp|svg|mp4|webm|mov|m4v|mp3|wav|m4a|ogg|flac|docx?|xlsx?|pptx?|epub)$/i

/**
 * href → 本地绝对路径。
 *
 * @param href markdown 链接原始目标（含 `file:` 协议或盘符路径）
 * @returns 本地路径；http/https/mailto/hash/相对路径等非本地目标返回 null
 */
export function localFilePathOf(href: string): string | null {
  const raw = (href ?? '').trim()
  if (!raw) return null
  if (/^file:/i.test(raw)) {
    // 先去掉 #锚点 / ?查询（文件系统路径里用不到；%23 这类转义留到解码后处理）
    let path = raw.replace(/^file:(?:\/\/)?/i, '').split('#')[0].split('?')[0]
    try {
      path = decodeURIComponent(path)
    } catch { /* 非法转义：保留原样 */ }
    // file:///D:/a.html → /D:/a.html（盘符前多出的根斜杠要吃掉）
    if (/^\/[A-Za-z]:[\\/]/.test(path)) path = path.slice(1)
    return /^[A-Za-z]:[\\/]/.test(path) ? path : null
  }
  // 无协议直接写盘符：D:/x.html、D:\x.html；UNC：\\server\share\x.html
  return /^(?:[A-Za-z]:[\\/]|\\\\)/.test(raw) ? raw : null
}

/**
 * 用系统默认程序打开本地文件（.html 交给默认浏览器）。
 *
 * @returns true = 已交给桌面壳；false = 非白名单类型 / 浏览器环境（无本地文件通道）
 */
export async function openLocalFile(path: string): Promise<boolean> {
  if (!OPENABLE_EXT.test(path)) {
    console.warn('[localFileLink] 拒绝打开非文档/媒体类型:', path)
    return false
  }
  try {
    const { isTauri } = await import('@tauri-apps/api/core')
    if (isTauri()) {
      const { openPath } = await import('@tauri-apps/plugin-opener')
      await openPath(path)
      return true
    }
  } catch (e) {
    console.warn('[localFileLink] 打开本地文件失败:', e)
  }
  return false
}