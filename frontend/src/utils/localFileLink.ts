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

import { openStandalone, previewKindOfPath, type PreviewType } from '@/utils/standalonePreview'

/** 允许交给系统默认程序打开的类型（文档 / 图片 / 音视频）。 */
const OPENABLE_EXT = /\.(?:html?|pdf|md|markdown|txt|log|csv|json|ya?ml|png|jpe?g|gif|webp|bmp|svg|mp4|webm|mov|m4v|mp3|wav|m4a|ogg|flac|docx?|xlsx?|pptx?|epub)$/i

/**
 * html 内联预览的体积上限（字符数）。
 *
 * <p>html 内部预览要把整份文本塞进独立窗的 localStorage 描述符（5MB / origin），
 * 超了会静默写入失败 ⇒ 用户看到「点了没反应」。超过此值退回系统浏览器。
 */
const INTERNAL_HTML_MAX_CHARS = 3_500_000

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

/**
 * 相对路径判定。
 *
 * <p>只认「没有协议、不是站内 hash、不是盘符/UNC 绝对路径」的形态；
 * `file:` / `http:` / `sandbox:` / `tauri:` 等一律不算（交给各自通道或直接拦截）。
 */
export function isRelativeFilePath(href: string): boolean {
  const raw = (href ?? '').trim()
  if (!raw || raw.startsWith('#')) return false
  if (/^[A-Za-z][A-Za-z0-9+.-]*:/.test(raw)) return false   // 任何协议头
  if (/^(?:[A-Za-z]:[\\/]|\\\\)/.test(raw)) return false     // 盘符 / UNC（绝对）
  return true
}

/** 取路径所在目录（同时识别 `\` 与 `/`）。 */
export function dirnameOf(path: string): string {
  const idx = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'))
  return idx > 0 ? path.slice(0, idx) : path
}

/**
 * 相对路径 → 每个基准目录下的候选绝对路径。
 *
 * <p>去掉 `./`、前导 `/`、`#锚点`、`?查询` 后拼接；分隔符跟随基准目录的形态。
 */
export function candidatePathsFor(rel: string, baseDirs: readonly string[]): string[] {
  let p = (rel ?? '').trim().split('#')[0].split('?')[0]
  p = p.replace(/^\.\//, '').replace(/^[/\\]+/, '')
  try { p = decodeURIComponent(p) } catch { /* 非法转义：保留原样 */ }
  if (!p) return []
  const out: string[] = []
  for (const base of baseDirs) {
    const dir = (base ?? '').trim().replace(/[/\\]+$/, '')
    if (!dir) continue
    const sep = dir.includes('\\') ? '\\' : '/'
    out.push(`${dir}${sep}${p.replace(/[/\\]+/g, sep)}`)
  }
  return out
}

/**
 * 按基准目录顺序找第一个存在的文件。
 *
 * <p>顺序由调用方决定（用户 2026-11-27 裁定：会话交付物目录 → 绑定项目根）。
 * 浏览器环境没有文件系统通道 ⇒ 直接返回 null。
 */
export async function resolveRelativeFilePath(
  rel: string,
  baseDirs: readonly string[],
): Promise<string | null> {
  const candidates = candidatePathsFor(rel, baseDirs)
  if (candidates.length === 0) return null
  try {
    const { isTauri } = await import('@tauri-apps/api/core')
    if (!isTauri()) return null
    const { exists } = await import('@tauri-apps/plugin-fs')
    for (const candidate of candidates) {
      try {
        if (await exists(candidate)) return candidate
      } catch { /* 单条探测失败：继续下一条 */ }
    }
  } catch (e) {
    console.warn('[localFileLink] 相对路径解析不可用:', e)
  }
  return null
}

/** 本地文件 → 客户端内预览类型；null = 该类型没有内部预览。 */
export function internalPreviewTypeOf(path: string): PreviewType | null {
  if (/\.html?$/i.test(path)) return 'html'
  if (/\.(md|mdx|markdown)$/i.test(path)) return 'markdown'
  if (/\.(txt|log)$/i.test(path)) return 'text'
  return previewKindOfPath(path)
}

/** 音视频/图片的 Blob 类型（独立窗按 path 读盘后建 objectURL，缺了会退化成 octet-stream）。 */
const MEDIA_TYPE_BY_EXT: Record<string, string> = {
  mp4: 'video/mp4', webm: 'video/webm', mov: 'video/quicktime', m4v: 'video/x-m4v',
  avi: 'video/x-msvideo', mkv: 'video/x-matroska',
  mp3: 'audio/mpeg', wav: 'audio/wav', m4a: 'audio/mp4', ogg: 'audio/ogg',
  aac: 'audio/aac', flac: 'audio/flac',
  png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif',
  webp: 'image/webp', bmp: 'image/bmp', ico: 'image/x-icon', avif: 'image/avif',
}

/** 按扩展名取 Blob 类型；未知返回 undefined（交给默认 octet-stream）。 */
export function mediaTypeOf(path: string): string | undefined {
  const ext = /\.([A-Za-z0-9]+)$/.exec(path.trim())?.[1]?.toLowerCase()
  return ext ? MEDIA_TYPE_BY_EXT[ext] : undefined
}

export type InternalOpenResult = 'opened' | 'too-large' | 'unsupported' | 'failed'

/**
 * 在客户端内预览本地文件（独立预览窗，与产物「全屏」同一套渲染）。
 *
 * <p>html 没有 path 源 ⇒ 先读文本再以 `code` 内联；超过
 * {@link INTERNAL_HTML_MAX_CHARS} 直接返回 `too-large`，由调用方退回系统浏览器。
 */
export async function openLocalFileInternally(path: string): Promise<InternalOpenResult> {
  const type = internalPreviewTypeOf(path)
  if (!type) return 'unsupported'
  const title = path.split(/[/\\]/).pop() || path
  try {
    if (type === 'html') {
      const { readFile } = await import('@tauri-apps/plugin-fs')
      const bytes = await readFile(path)
      const code = new TextDecoder('utf-8').decode(bytes)
      if (code.length > INTERNAL_HTML_MAX_CHARS) return 'too-large'
      openStandalone({ type: 'html', title, code })
      return 'opened'
    }
    openStandalone({ type, title, item: { filename: title, path, mediaType: mediaTypeOf(path) } })
    return 'opened'
  } catch (e) {
    console.warn('[localFileLink] 内部预览失败:', path, e)
    return 'failed'
  }
}