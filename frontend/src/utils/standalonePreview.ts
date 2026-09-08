/**
 * 「独立应用窗口预览」统一通道（HTML/PDF/Word/Excel/图片/音视频/项目文件）。
 *
 * 模式对齐原 HTML 独立查看器（htmlStandalone.ts，已并入本文件）：载荷 = 描述符写入
 * localStorage → 打开/复用独立窗口（Tauri `WebviewWindow('standalone-preview')` /
 * 浏览器同名 tab）→ 独立窗监听 storage 事件 + focus 回读实现跨类型刷新（开着 PDF 再点
 * HTML 运行 → 同窗切换为 HTML）。
 *
 * 关键约束：localStorage 只承载<b>描述符</b>（type + 内容源 url/path/base64 之一 / 项目端点 /
 * 内联 code），<b>绝不存附件 base64 大字符串</b>（约 5MB quota 会爆）——trim 时按
 * url > path > base64 只保留一种内容源；实际字节由独立窗内自行 fetch / plugin-fs 读取
 * （见 components/preview/usePreviewContent.ts）。
 */

/** 独立预览载荷类型。 */
export type PreviewType = 'html' | 'pdf' | 'docx' | 'xlsx' | 'image' | 'video' | 'audio' | 'file'

/** 对话附件内容源（结构兼容 ChatMessageDto.userAttachments 元素，缺省可空）。 */
export interface StandaloneAttachment {
  type?: string | null
  filename?: string | null
  mediaType?: string | null
  contentId?: string | null
  /** 三态内容源之一（发送后 F5 重拉由后端回填后端 url） */
  url?: string | null
  path?: string | null
  base64?: string | null
}

/** 独立预览载荷（描述符，v1）。v 由 openStandalone 写入时补 1，调用方可省略。 */
export interface StandalonePayload {
  v?: 1
  type: PreviewType
  title: string
  /** 对话附件（trim 后只留 url | path | base64 一种源） */
  item?: StandaloneAttachment
  /** 项目文件（docx/xlsx/pdf/image/video/audio 走后端 raw 端点） */
  project?: { id: string; path: string }
  /** html：内联内容（对话代码块运行 / FileViewModal 运行） */
  code?: string
}

export const STANDALONE_LS_KEY = 'nexusai.standalone.preview'
const STANDALONE_ROUTE = '#/standalone-preview'
const WINDOW_LABEL = 'standalone-preview'
const BROWSER_TAB_NAME = 'nexusai-standalone-preview'

/** 是否 Tauri 运行时（同步判定，浏览器分支据此同步 window.open 防弹窗拦截）。 */
export const IS_TAURI = typeof window !== 'undefined' && !!(window as unknown as { __TAURI_INTERNALS__?: unknown }).__TAURI_INTERNALS__

/** 是否为独立预览路由（main.tsx 据此渲染 StandalonePreviewView 而非整 App）。 */
export function isStandalonePreviewRoute(): boolean {
  try {
    return window.location.hash.startsWith(STANDALONE_ROUTE)
  } catch {
    return false
  }
}

/** 独立查看器当前应展示的载荷（最近一次「打开」写下的描述符）。 */
export function readStandalonePayload(): StandalonePayload | null {
  try {
    const raw = window.localStorage.getItem(STANDALONE_LS_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as StandalonePayload
    return parsed && parsed.v === 1 && parsed.type ? parsed : null
  } catch {
    return null
  }
}

/** 独立查看器 URL（去掉 hash 后拼路由）。 */
function standaloneUrl(): string {
  const base = window.location.href.split('#')[0]
  return `${base}${STANDALONE_ROUTE}`
}

/** 附件 trim：按 url > path > base64 只保留一种内容源（防大 base64 撑爆 localStorage）。 */
export function trimItemForTransfer(item: StandaloneAttachment): StandaloneAttachment {
  const { url, path, base64, ...rest } = item
  if (url) return { ...rest, url }
  if (path) return { ...rest, path }
  if (base64) return { ...rest, base64 }
  return { ...rest }
}

/** 附件 → 预览类型（docx/xlsx 按扩展名；pdf/video/audio 按 type 或扩展名；其余 file）。 */
export function previewKindOfAttachment(a: StandaloneAttachment): PreviewType {
  const name = a.filename ?? ''
  if (/\.docx$/i.test(name)) return 'docx'
  if (/\.xlsx$/i.test(name)) return 'xlsx'
  if (a.type === 'pdf' || /\.pdf$/i.test(name)) return 'pdf'
  if (a.type === 'video' || /\.(mp4|webm|mov|m4v|avi|mkv)$/i.test(name)) return 'video'
  if (a.type === 'audio' || /\.(mp3|wav|m4a|ogg|aac|flac)$/i.test(name)) return 'audio'
  if (a.type === 'image' || /\.(png|jpe?g|gif|webp|bmp|ico|avif)$/i.test(name)) return 'image'
  return 'file'
}

/** 项目文件路径 → 预览类型；.html / 文本代码（含 .svg）返回 null → 走 FileViewModal。 */
export function previewKindOfPath(path: string): PreviewType | null {
  if (/\.docx$/i.test(path)) return 'docx'
  if (/\.xlsx$/i.test(path)) return 'xlsx'
  if (/\.pdf$/i.test(path)) return 'pdf'
  if (/\.(png|jpe?g|gif|webp|bmp|ico|avif)$/i.test(path)) return 'image'
  if (/\.(mp4|webm|mov|m4v|avi|mkv)$/i.test(path)) return 'video'
  if (/\.(mp3|wav|m4a|ogg|aac|flac)$/i.test(path)) return 'audio'
  return null // html 与文本代码保持 Monaco 查看/编辑
}

/** 从路径取显示名。 */
export function basenameOf(path: string): string {
  const seg = path.split('/').pop()
  return seg || path
}

/** 打开独立预览窗口（幂等复用）：写载荷 → 聚焦已开窗口 / 新建。载荷在 trim 后的最终形态。 */
export function openStandalone(payload: StandalonePayload): void {
  const p: StandalonePayload = {
    ...payload,
    v: 1,
    item: payload.item ? trimItemForTransfer(payload.item) : payload.item,
  }
  // 先广播内容（同窗 storage 不自触发，独立窗靠 focus 回读兜底）
  try {
    window.localStorage.setItem(STANDALONE_LS_KEY, JSON.stringify(p))
  } catch (e) {
    // quota（超大 base64/html）等：静默降级（旧内容仍在，独立窗可手动 ↻ 触发 focus 重读）
    console.warn('[standalonePreview] localStorage 写入失败（可能超 quota），无法更新独立预览内容', e)
  }
  if (IS_TAURI) {
    void (async () => {
      try {
        const { WebviewWindow } = await import('@tauri-apps/api/webviewWindow')
        const existing = await WebviewWindow.getByLabel(WINDOW_LABEL)
        if (existing) {
          try { await existing.setFocus() } catch { /* 窗口可能正关闭中 */ }
          return
        }
        new WebviewWindow(WINDOW_LABEL, {
          title: p.title || '预览',
          url: standaloneUrl(),
          width: 1280,
          height: 840,
          minWidth: 480,
          minHeight: 360,
          center: true,
          focus: true,
        })
      } catch { /* Tauri 环境异常（缺 capability 等）：静默 */ }
    })()
    return
  }
  // 浏览器：同名窗口 = 复用已开的独立页；被弹窗拦截时返回 null 静默
  const win = window.open(standaloneUrl(), BROWSER_TAB_NAME)
  try { win?.focus() } catch { /* ignore */ }
}
