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

/**
 * 交通灯绿色按钮：切换 **macOS 原生全屏**（进入独立 Space）。
 *
 * <p><b>WHY 从 toggleMaximize 改成 setFullscreen</b>（2026-11-02 用户实测
 * 「当前选择最大化 不能和浏览器那样最大化成单独一个窗口」）：
 * {@code toggleMaximize()} 是 macOS 的 **zoom**（把窗口撑到可用工作区，仍在同一 Space、
 * 菜单栏与 Dock 照常显示），而用户说的「和浏览器那样」是**原生全屏**
 * （窗口独占一个 Space，Dock / 菜单栏隐去，三指滑动可切走）。
 * macOS 系统绿色按钮的默认行为就是全屏，故这里对齐它。
 *
 * <p>浏览器 dev 环境下没有 Tauri 窗口 API ⇒ 退回 DOM 全屏 API（体验等价）。
 */
export async function toggleMaximize(): Promise<void> {
  if (!(await isTauriEnv())) {
    // 浏览器兜底：Document Fullscreen API（与原生全屏观感一致）
    try {
      if (document.fullscreenElement) await document.exitFullscreen()
      else await document.documentElement.requestFullscreen()
    } catch { /* 用户拒绝 / 无权限：静默（与窗口 API 的失败口径一致） */ }
    return
  }
  const { getCurrentWindow } = await import('@tauri-apps/api/window')
  const win = getCurrentWindow()
  try {
    await win.setFullscreen(!(await win.isFullscreen()))
  } catch (e) {
    // [2026-11-02 用户实测「点击最大化失效」] 根因不是代码，而是 Tauri 权限没开：
    //   `window.set_fullscreen not allowed. Permissions: core:window:allow-set-fullscreen`
    //   （capabilities/default.json 原先只有 allow-maximize / allow-toggle-maximize）。
    //   ⚠️ 权限是**编译期**生成进二进制的 ⇒ 加完必须重新构建 Rust 侧才生效，
    //   热更新前端不行。这里加一道降级：全屏被拒时退回 zoom（至少窗口会变大，不至于点了没反应）。
    console.warn('[window] setFullscreen 被拒，退回 toggleMaximize：', e)
    await win.toggleMaximize()
  }
}

/** 当前是否全屏（供交通灯绿色按钮的状态反馈 / 图标切换） */
export async function isWindowMaximized(): Promise<boolean> {
  if (!(await isTauriEnv())) return document.fullscreenElement != null
  const { getCurrentWindow } = await import('@tauri-apps/api/window')
  return await getCurrentWindow().isFullscreen()
}

/** 监听全屏状态变化（resize 时回查）· 返回取消订阅函数 */
export async function onMaximizeChange(cb: (max: boolean) => void): Promise<() => void> {
  if (!(await isTauriEnv())) {
    const onFs = () => cb(document.fullscreenElement != null)
    document.addEventListener('fullscreenchange', onFs)
    return () => document.removeEventListener('fullscreenchange', onFs)
  }
  const { getCurrentWindow } = await import('@tauri-apps/api/window')
  const win = getCurrentWindow()
  const un = await win.onResized(async () => cb(await win.isFullscreen()))
  return () => { un() }
}

/** 启动放大动画时长（ms）。rAF 驱动，约 15 帧 @60Hz。 */
const SPLASH_EXPAND_MS = 250

/**
 * 启动放大动画：后端就绪后，把 L1 的小正方形窗（loader）平滑放大到
 * 主显示器物理尺寸的 85%，并把最小尺寸复位回主界面下限（960x600）。
 *
 * - 目标：currentMonitor() 物理尺寸 * 0.85
 * - 过程：rAF 按真实耗时驱动，easeOutCubic 缓动（先快后慢、收尾不突兀）
 * - 中心：每帧把窗口中心钉在显示器中心 ⇒ 四边同时外扩
 * - 结束：精确对齐目标尺寸 + setFocus() 聚焦
 * - 非 Tauri（浏览器 dev）直接跳过；任何窗口 API 异常静默吞掉，不阻塞 ready 内容切换。
 */
export async function expandFromSplash(): Promise<void> {
  if (!(await isTauriEnv())) return
  try {
    const { getCurrentWebviewWindow } = await import('@tauri-apps/api/webviewWindow')
    const { currentMonitor } = await import('@tauri-apps/api/window')
    const { PhysicalSize, PhysicalPosition, LogicalSize } = await import('@tauri-apps/api/dpi')
    const win = getCurrentWebviewWindow()

    const monitor = await currentMonitor()
    if (!monitor) return

    const targetW = Math.round(monitor.size.width * 0.85)
    const targetH = Math.round(monitor.size.height * 0.85)
    const centerX = monitor.position.x + monitor.size.width / 2
    const centerY = monitor.position.y + monitor.size.height / 2

    // [2026-11-02 用户实测「不是从屏幕正中四角扩大，而是从左上角长出来」]
    // 只改 `setSize` 时 macOS 会**固定窗口左上角**、向右下生长（不居中的根因）；
    // 原来的 `center()` 又只在最后一帧调用 ⇒ 整段动画是「左上角长满屏 + 末帧瞬移居中」。
    // 故每帧同时设尺寸与位置：把窗口中心钉在显示器中心 ⇒ 四边等速外扩。
    // 位置与当前尺寸无关（纯按目标尺寸算），不依赖 setSize 的异步生效顺序。
    const place = async (w: number, h: number) => {
      // 尺寸与位置**并行**下发：串行 await 每帧要多付一次 IPC 往返，60fps 预算内会掉帧
      await Promise.all([
        win.setSize(new PhysicalSize(w, h)),
        win.setPosition(
          new PhysicalPosition(Math.round(centerX - w / 2), Math.round(centerY - h / 2)),
        ),
      ])
    }

    // 从当前物理尺寸平滑放大到目标。
    // 注意：必须在放大期间保留 L1 临时调低的 min（正方形小窗边长）——若先在窗口还是
    // 小尺寸时把 min 复位到 960x600，Windows 会把小于 min 的 setSize 直接钳到 960x600，
    // 动画变成瞬间跳变。
    const current = await win.outerSize()

    // [2026-11-02 用户实测「动画不够平滑」] 原实现 12 帧 × 16ms ≈ 190ms 且为**线性插值**，
    // 起步和收尾都硬切 ⇒ 看起来像「弹一下」。改为 rAF 按真实耗时驱动 + easeOutCubic
    // （先快后慢，与系统窗口放大同感），时长 SPLASH_EXPAND_MS（用户定为 250ms）。
    await new Promise<void>((resolve) => {
      const started = performance.now()
      const step = async (now: number) => {
        const t = Math.min(1, (now - started) / SPLASH_EXPAND_MS)
        const eased = 1 - Math.pow(1 - t, 3)
        const w = Math.round(current.width + (targetW - current.width) * eased)
        const h = Math.round(current.height + (targetH - current.height) * eased)
        await place(w, h)
        if (t < 1) requestAnimationFrame((n) => void step(n))
        else resolve()
      }
      requestAnimationFrame((n) => void step(n))
    })

    // 收尾：精确对齐目标尺寸与位置 → 复位主界面最小尺寸下限 → 聚焦
    await place(targetW, targetH)
    // 最小尺寸必须用 LogicalSize：960x600 与 tauri.conf.json 的 minWidth/minHeight 同为逻辑点；
    // 用 PhysicalSize 在 Retina 上等于只给了 480x300 逻辑点，主界面能被缩到比设计下限还小。
    await win.setMinSize(new LogicalSize(960, 600))
    await win.setFocus()
  } catch {
    // 放大失败静默：不影响 ready 内容切换
  }
}
