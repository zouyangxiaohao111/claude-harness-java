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

/**
 * 启动放大动画：后端就绪后，把 L1 的小窗（460x400 级 loader）平滑放大到
 * 主显示器物理尺寸的 85%，并把最小尺寸复位回主界面下限（960x600）。
 *
 * - 目标：currentMonitor() 物理尺寸 * 0.85
 * - 过程：~12 帧、每帧 ~16ms，从当前 outerSize 线性插值到目标
 * - 结束：center() 居中 + setFocus() 聚焦
 * - 非 Tauri（浏览器 dev）直接跳过；任何窗口 API 异常静默吞掉，不阻塞 ready 内容切换。
 */
export async function expandFromSplash(): Promise<void> {
  if (!(await isTauriEnv())) return
  try {
    const { getCurrentWebviewWindow } = await import('@tauri-apps/api/webviewWindow')
    const { currentMonitor } = await import('@tauri-apps/api/window')
    const { PhysicalSize } = await import('@tauri-apps/api/dpi')
    const win = getCurrentWebviewWindow()

    const monitor = await currentMonitor()
    if (!monitor) return

    const targetW = Math.round(monitor.size.width * 0.85)
    const targetH = Math.round(monitor.size.height * 0.85)

    // 从当前物理尺寸平滑放大到目标。
    // 注意：必须在放大期间保留 L1 临时调低的 min（460x400）——若先在 460 尺寸上把
    // min 复位到 960x600，Windows 会把小于 min 的 setSize 直接钳到 960x600，动画变成瞬间跳变。
    const current = await win.outerSize()
    const FRAMES = 12
    for (let i = 1; i <= FRAMES; i++) {
      const t = i / FRAMES
      const w = Math.round(current.width + (targetW - current.width) * t)
      const h = Math.round(current.height + (targetH - current.height) * t)
      await win.setSize(new PhysicalSize(w, h))
      await sleep(16)
    }

    // 收尾：精确对齐目标尺寸 → 复位主界面最小尺寸下限 → 居中 + 聚焦
    await win.setSize(new PhysicalSize(targetW, targetH))
    await win.setMinSize(new PhysicalSize(960, 600))
    await win.center()
    await win.setFocus()
  } catch {
    // 放大失败静默：不影响 ready 内容切换
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}
