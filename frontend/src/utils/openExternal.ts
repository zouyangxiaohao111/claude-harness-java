/**
 * 打开外部链接（桌面壳走系统浏览器，浏览器/dev 走新标签）。
 *
 * <p><b>WHY 必须分叉</b>：Tauri 里 {@code window.open} 会开一个新 WebView 窗口（或直接被拦），
 * 而不是交给系统浏览器 —— 授权页/扫码页在 WebView 里既拿不到系统能力也容易被拦。
 * 桌面壳要用 {@code @tauri-apps/plugin-opener} 的 {@code openUrl}。
 *
 * <p>⚠️ 还需要 capability 授权（{@code src-tauri/capabilities/*.json}）：
 * 只有 {@code opener:allow-open-path} 不够，打开网址必须再加 {@code opener:allow-open-url}，
 * 否则 invoke 会被拒（表现为「点了一下没反应 / 报权限错误」）。
 */
export async function openExternalUrl(url: string): Promise<boolean> {
  try {
    const { isTauri } = await import('@tauri-apps/api/core')
    if (isTauri()) {
      const { openUrl } = await import('@tauri-apps/plugin-opener')
      await openUrl(url)
      return true
    }
  } catch (e) {
    // 桌面壳不可用 / 权限未开 → 回落浏览器新标签，不吞掉原因
    console.warn('[openExternal] Tauri openUrl 失败，回落 window.open:', e)
  }
  return window.open(url, '_blank', 'noopener') != null
}
