/**
 * HTML 代码块「另起页面打开」管理。
 *
 * 独立查看器 = 应用内一个极简路由视图（location.hash `#/standalone-html`），整屏 sandbox
 * iframe 展示模型 HTML（安全与右栏预览一致，模型内容不离开 iframe）。
 *
 * 联动模型：openStandaloneHtml(code) 先把 code 写进 localStorage（同一信号通道），再打开/复用
 * 独立查看器。查看器监听 `storage` 事件 + focus 回读 → 对话里再次「运行」时，已开着的独立页自动
 * 刷新；没开就新建。跨环境：
 *  - 浏览器 dev/web：window.open 同名标签页（复用同一个 tab）。
 *  - Tauri：WebviewWindow('html-standalone')，已存在则 setFocus。
 */
export const HTML_STANDALONE_LS_KEY = 'nexusai.html.standalone'

/** 是否为独立查看器路由（main.tsx 据此渲染 StandaloneHtmlView 而非整 App）。 */
export function isStandaloneHtmlRoute(): boolean {
  try {
    return window.location.hash.startsWith('#/standalone-html')
  } catch {
    return false
  }
}

/** 独立查看器当前应展示的 HTML（最近一次「运行」写下的内容）。 */
export function currentStandaloneHtml(): string {
  try {
    return window.localStorage.getItem(HTML_STANDALONE_LS_KEY) ?? ''
  } catch {
    return ''
  }
}

/** 把 code 广播给所有打开着的独立查看器（不 open 时只更新内容，下次打开读最新）。 */
export function pushStandaloneHtml(code: string): void {
  try {
    window.localStorage.setItem(HTML_STANDALONE_LS_KEY, code)
  } catch {
    /* 隐私模式等禁用 storage 时静默：查看器仍可手动「↻」回读当前会话内容 */
  }
}

async function isTauriEnv(): Promise<boolean> {
  try {
    const { isTauri } = await import('@tauri-apps/api/core')
    return isTauri()
  } catch {
    return false
  }
}

/** 独立查看器 URL（去掉 hash 后拼路由）。 */
function standaloneUrl(): string {
  const base = window.location.href.split('#')[0]
  return `${base}#/standalone-html`
}

/**
 * 在独立窗口/标签页打开 HTML 预览（幂等复用）。
 * 先广播 code，再打开/聚焦查看器；浏览器同名 tab 复用，Tauri 按 label 复用窗口。
 */
export async function openStandaloneHtml(code: string): Promise<void> {
  pushStandaloneHtml(code)
  if (await isTauriEnv()) {
    try {
      const { WebviewWindow } = await import('@tauri-apps/api/webviewWindow')
      const existing = await WebviewWindow.getByLabel('html-standalone')
      if (existing) {
        try { await existing.setFocus() } catch { /* 窗口可能正关闭中 */ }
        return
      }
      new WebviewWindow('html-standalone', {
        title: 'HTML 运行预览',
        url: standaloneUrl(),
        width: 1280,
        height: 840,
        minWidth: 480,
        minHeight: 360,
        center: true,
        focus: true,
      })
    } catch { /* Tauri 环境异常（缺 capability 等）：静默，右栏预览仍可用 */ }
    return
  }
  // 浏览器：同名窗口 = 复用已开的独立页；被弹窗拦截时返回 null 静默。
  const win = window.open(standaloneUrl(), 'nexusai-html-standalone')
  try { win?.focus() } catch { /* ignore */ }
}
