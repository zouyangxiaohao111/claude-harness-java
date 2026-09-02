/**
 * Chrome 扩展安装桥接层（FNT-BROWSER-02）
 *
 * 封装 Tauri 三个 command：
 *   - chrome_extension_dir      → 扩展目录绝对路径
 *   - is_chrome_installed       → Chrome/Chromium 是否已安装
 *   - install_chrome_extension  → 用本机 Chrome 登录配置启动并加载扩展
 *
 * 动态 import + 非 Tauri 环境优雅降级（与 tauri-bridge.ts 同一模式）：
 * 浏览器 dev 模式不阻塞调用方，返回 null / false / 抛错由调用方展示。
 */

async function invokeTauri<T>(cmd: string): Promise<T> {
  const { isTauri, invoke } = await import('@tauri-apps/api/core')
  if (!isTauri()) throw new Error('非 Tauri 环境，请在桌面端使用')
  return invoke<T>(cmd)
}

/** 扩展目录（打包后 resources/extension；dev 回退项目根 extension/）。非 Tauri 返回 null。 */
export async function chromeExtensionDir(): Promise<string | null> {
  try {
    return await invokeTauri<string>('chrome_extension_dir')
  } catch {
    return null
  }
}

/** Chrome / Chromium 是否已安装。非 Tauri 返回 false。 */
export async function isChromeInstalled(): Promise<boolean> {
  try {
    return await invokeTauri<boolean>('is_chrome_installed')
  } catch {
    return false
  }
}

/** 一键启动 Chrome 并加载扩展，返回人类可读结果文案。失败抛错（调用方展示）。 */
export async function installChromeExtension(): Promise<string> {
  return invokeTauri<string>('install_chrome_extension')
}
