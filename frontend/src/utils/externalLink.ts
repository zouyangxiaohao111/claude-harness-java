/**
 * 对话正文里外链的打开口径（用户 2026-11-18 裁定）。
 *
 * <ul>
 *   <li>左键点：交给系统默认浏览器（Tauri `openUrl` / 浏览器新标签）；</li>
 *   <li>右键：客户端内另开一个独立窗口（Tauri `WebviewWindow` / 浏览器新标签）。</li>
 * </ul>
 *
 * <p><b>WHY 必须先 preventDefault</b>：Tauri WebView 里 `<a href>` 没有地址栏也没有后退键，
 * 默认行为是<b>当前 webview 整页导航</b>到外站 —— 用户既回不到会话，也不知道怎么退。
 * 所以调用方要在 click / contextmenu 里先拦默认动作，再显式分发到这里。
 */

/** 外链判定：只认绝对地址 http/https/mailto/tel（hash、相对路径留给站内处理）。 */
export function isExternalLinkHref(href: string): boolean {
  try {
    const protocol = new URL(href).protocol
    return protocol === 'http:' || protocol === 'https:' || protocol === 'mailto:' || protocol === 'tel:'
  } catch {
    return false
  }
}

/** 交给系统默认程序打开（Tauri `openUrl` / 浏览器新标签）。 */
export async function openLinkInBrowser(href: string): Promise<void> {
  try {
    const { isTauri } = await import('@tauri-apps/api/core')
    if (isTauri()) {
      const { openUrl } = await import('@tauri-apps/plugin-opener')
      await openUrl(href)
      return
    }
  } catch { /* 桌面壳不可用 → 回落浏览器 */ }
  window.open(href, '_blank', 'noopener')
}

/** 在客户端内另起独立窗口打开（Tauri `WebviewWindow` / 浏览器新标签）。 */
export async function openLinkInAppWindow(href: string): Promise<void> {
  // mailto: 只能交给系统邮件客户端，开 webview 窗口没有意义。
  if (!/^https?:/i.test(href)) {
    await openLinkInBrowser(href)
    return
  }
  if (!(await isTauriShell())) {
    window.open(href, '_blank', 'noopener')
    return
  }
  try {
    const { WebviewWindow } = await import('@tauri-apps/api/webviewWindow')
    // 每次点开都是新窗口 ⇒ label 必须唯一（同 label 会被 Tauri 拒绝/复用成旧页面）。
    const win = new WebviewWindow(`external-link-${Date.now().toString(36)}`, {
      url: href,
      title: externalLinkTitle(href),
      width: 1180,
      height: 820,
      minWidth: 480,
      minHeight: 360,
      focus: true,
      // 外部网页没有自绘标题栏 ⇒ 必须由系统提供关闭/最小化按钮。
      decorations: true,
    })
    // ⚠️ Tauri v2：创建失败不抛异常，只发 `tauri://error` ⇒ 不监听就表现为「点了没反应」。
    void win.once('tauri://error', (event) => {
      console.error('[externalLink] 创建窗口失败，回退系统浏览器', event)
      void openLinkInBrowser(href)
    })
  } catch (e) {
    console.error('[externalLink] 打开窗口异常，回退系统浏览器', e)
    await openLinkInBrowser(href)
  }
}

/** 新窗口标题：优先域名，解析失败回退原串。 */
function externalLinkTitle(href: string): string {
  try {
    return new URL(href).host || href
  } catch {
    return href
  }
}

async function isTauriShell(): Promise<boolean> {
  try {
    const { isTauri } = await import('@tauri-apps/api/core')
    return isTauri()
  } catch {
    return false
  }
}
