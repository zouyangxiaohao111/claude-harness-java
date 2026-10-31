/**
 * 产物「在浏览器中打开」通道（**只有 html 产物**有这个入口 · 用户 2026-10-31 裁定）。
 *
 * <p><b>WHY 先探测再打开</b>：raw 端点（{@code GET /sessions/{sid}/artifacts/{aid}/raw}）
 * 是后端并行开发中的契约，**还没上线时返回 404** ⇒ 直接把用户丢到一个 404 页面毫无意义
 * ⇒ 先探一次状态码（响应体立刻掐掉，别把整份产物拖下来），不通就回落「复制路径 + 提示」。
 *
 * <p>桌面壳（Tauri）走 {@code openUrl} 交给系统浏览器；浏览器/dev 走 {@code window.open} 新标签。
 */
import { artifactsApi, artifactRawUrl } from '@/api/artifacts'
import { artifactKindOf, isTextArtifact } from '@/utils/artifactKind'
import type { PreviewType } from '@/utils/standalonePreview'

/**
 * 产物「点开」的分发口径（用户 2026-10-31 裁定 · 纯函数 · 可单测）。
 *
 * <ul>
 *   <li>{@code view-html}：html ⇒ 走我们现有的 html 预览（源码查看/编辑/保存/运行 + 在浏览器中打开）；</li>
 *   <li>{@code standalone}：docx/xlsx/pdf/图片 ⇒ 走**现有渲染**（DocxPreview / ExcelPreview / PdfPreview / 图片）；</li>
 *   <li>{@code preview-text}：md / 其它文本 ⇒ Markdown 渲染 + 编辑保存；</li>
 *   <li>{@code unsupported}：其它二进制（pptx/zip…）⇒ 提示「该类型暂不支持预览」+ 复制路径。</li>
 * </ul>
 */
export type ArtifactOpenAction =
  | { kind: 'view-html' }
  | { kind: 'standalone'; previewType: PreviewType }
  | { kind: 'preview-text' }
  | { kind: 'unsupported' }

/** 按产物标题（扩展名）判定点开方式。 */
export function artifactOpenActionOf(title: string): ArtifactOpenAction {
  const kind = artifactKindOf(title)
  if (kind === 'html') return { kind: 'view-html' }
  if (kind === 'docx' || kind === 'xlsx' || kind === 'pdf' || kind === 'img') {
    return { kind: 'standalone', previewType: kind === 'img' ? 'image' : kind }
  }
  if (isTextArtifact(title)) return { kind: 'preview-text' }
  return { kind: 'unsupported' }
}

/**
 * raw 端点是否可用。
 *
 * <p>⛔ 只取状态码：响应体 {@code cancel()} 掉（产物可能几十 MB，拖下来只为看一个 200 不值）。
 *
 * @returns true = 端点可用（2xx）；false = 404 / 网络错误 / 其它非 2xx。
 */
export async function artifactRawReachable(sessionId: string, artifactId: string): Promise<boolean> {
  try {
    const res = await artifactsApi.raw(sessionId, artifactId)
    try { await res.body?.cancel() } catch { /* 浏览器可能已缓冲完，忽略 */ }
    return true
  } catch {
    return false
  }
}

/**
 * 用系统浏览器打开产物 raw 端点。
 *
 * @returns true = 已打开；false = 端点不可达（调用方负责降级：复制路径 + 提示，⛔ 不白屏）。
 */
export async function openArtifactRawInBrowser(sessionId: string, artifactId: string): Promise<boolean> {
  if (!(await artifactRawReachable(sessionId, artifactId))) return false
  const url = artifactRawUrl(sessionId, artifactId)
  try {
    const { isTauri } = await import('@tauri-apps/api/core')
    if (isTauri()) {
      const { openUrl } = await import('@tauri-apps/plugin-opener')
      await openUrl(url)
      return true
    }
  } catch { /* 桌面壳不可用 → 回落浏览器 */ }
  window.open(url, '_blank', 'noopener')
  return true
}
