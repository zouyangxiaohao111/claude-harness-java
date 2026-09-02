/**
 * Tauri API 桥接层
 * 封装窗口控制 API，在非 Tauri 环境（浏览器 dev）中优雅降级
 *
 * 注意：不缓存 tauriAvailable —— 每次调用实时检测（isTauri），
 * 避免「模块加载时检测未完成、用户点击时 tauriAvailable 仍为 false」的时序 bug。
 */

async function isTauriEnv(): Promise<boolean> {
  try {
    const { isTauri } = await import('@tauri-apps/api/core')
    return isTauri()
  } catch {
    return false
  }
}

export async function closeWindow(): Promise<void> {
  if (!(await isTauriEnv())) { window.close(); return }
  const { getCurrentWindow } = await import('@tauri-apps/api/window')
  await getCurrentWindow().close()
}

export async function minimizeWindow(): Promise<void> {
  if (!(await isTauriEnv())) return
  const { getCurrentWindow } = await import('@tauri-apps/api/window')
  await getCurrentWindow().minimize()
}

export async function toggleMaximize(): Promise<void> {
  if (!(await isTauriEnv())) return
  const { getCurrentWindow } = await import('@tauri-apps/api/window')
  await getCurrentWindow().toggleMaximize()
}

/** 当前是否最大化（供交通灯绿色按钮状态反馈） */
export async function isWindowMaximized(): Promise<boolean> {
  if (!(await isTauriEnv())) return false
  const { getCurrentWindow } = await import('@tauri-apps/api/window')
  return await getCurrentWindow().isMaximized()
}

/** 监听最大化状态变化（resize 时回查）· 返回取消订阅函数 */
export async function onMaximizeChange(cb: (max: boolean) => void): Promise<() => void> {
  if (!(await isTauriEnv())) return () => {}
  const { getCurrentWindow } = await import('@tauri-apps/api/window')
  const win = getCurrentWindow()
  const un = await win.onResized(async () => cb(await win.isMaximized()))
  return () => { un() }
}
