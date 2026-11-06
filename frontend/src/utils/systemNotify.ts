/**
 * 系统级通知（Windows 右下角 / macOS 通知中心）。
 *
 * <h2>WHY 需要双通道</h2>
 * 本仓有两种运行形态，通知的落地 API 完全不同：
 * <ul>
 *   <li><b>Tauri 桌面壳</b>（正式安装包 / `npm run tauri dev`）：走
 *       `@tauri-apps/plugin-notification`。它由 Rust 侧 `tauri_plugin_notification::init()`
 *       注册，能拿到系统级通知权限，Windows 上落「操作中心」，macOS 上落「通知中心」。</li>
 *   <li><b>纯浏览器</b>（日常开发 `localhost:3000`）：Web Notification API。
 *       需要用户授权（浏览器弹权限框），且 HTTPS / localhost 之外不可用。</li>
 * </ul>
 * ⛔ 不能只做 Tauri 那一路 —— 开发时天天在浏览器里跑，那条路会静默不生效，
 * 看起来像「功能没做」。
 *
 * <h2>授权策略</h2>
 * 浏览器要求**用户手势内**才能 `requestPermission()`。而轮次结束是异步事件，
 * 不在手势里 ⇒ 直接调会被静默拒绝（Chrome 甚至直接返回 'denied'）。
 * 故这里采取「**首次尝试，失败即止**」：不在后台反复请求权限，也不弹二次提示。
 * 用户想开，去浏览器站点设置里放行即可（下次轮次结束自动生效）。
 *
 * <h2>降级</h2>
 * 任何一步失败（插件未装 / 权限被拒 / 系统不支持 / 非安全上下文）一律**静默返回 false**，
 * 绝不抛异常、绝不 toast —— 通知是锦上添花，不能因为它把轮次收口流程打挂。
 */

/** 通知内容（与平台无关的最小集合）。 */
export interface SystemNotifyPayload {
  title: string
  body?: string
}

/**
 * 发送一条系统通知。
 *
 * @returns 真的发出去了 → true；任何一步不可用/被拒 → false（调用方无需处理）
 */
export async function sendSystemNotification(payload: SystemNotifyPayload): Promise<boolean> {
  // 空标题没有任何意义（某些平台会直接拒绝）
  const title = payload.title?.trim()
  if (!title) return false

  try {
    const { isTauri } = await import('@tauri-apps/api/core')
    if (isTauri()) return await sendViaTauri(title, payload.body)
    return await sendViaWeb(title, payload.body)
  } catch {
    // 插件缺失 / 非 Tauri 构建 / 浏览器不支持 —— 一律静默
    return false
  }
}

/** Tauri 通道：`@tauri-apps/plugin-notification`。 */
async function sendViaTauri(title: string, body?: string): Promise<boolean> {
  const plugin = await import('@tauri-apps/plugin-notification')
  let granted = await plugin.isPermissionGranted()
  if (!granted) {
    // 桌面壳里这个请求**不需要用户手势**（走系统权限，Windows 默认放行，macOS 首次弹系统框）
    granted = (await plugin.requestPermission()) === 'granted'
  }
  if (!granted) return false
  plugin.sendNotification({ title, body })
  return true
}

/**
 * 在**用户手势**里申请通知权限（浏览器只认手势内的请求）。
 *
 * <h2>WHY 需要这个入口</h2>
 * 轮次结束是异步事件，不在手势里 ⇒ 那时调 `requestPermission()` 必被静默拒
 * （Chrome 直接返回 'denied'，且**不再弹框**）。所以授权必须**提前**在某个用户动作里做。
 * 这里挂在「发送消息」上：那是用户最自然、频率合适的手势，也正好是「跑完想要被通知」
 * 这条需求的起点。
 *
 * <h2>行为</h2>
 * <ul>
 *   <li>只在 `permission === 'default'`（从未问过）时请求 —— 已 granted 不必再问，
 *       已 denied 再问也弹不出来，只会白跑一趟；</li>
 *   <li>Tauri 壳里直接返回（那条通道不依赖手势，且首次发通知时会自己申请）；</li>
 *   <li>**完全静默**，不 await 阻塞发送、不 toast、失败不影响任何主流程。</li>
 * </ul>
 */
export async function ensureNotificationPermissionOnGesture(): Promise<void> {
  try {
    const { isTauri } = await import('@tauri-apps/api/core')
    if (isTauri()) return
    if (typeof Notification === 'undefined') return
    if (Notification.permission !== 'default') return
    await Notification.requestPermission()
  } catch {
    // 浏览器不支持 / 被策略拦截 —— 静默（通知只是锦上添花）
  }
}

/** Web 通道：浏览器 Notification API。 */
async function sendViaWeb(title: string, body?: string): Promise<boolean> {
  if (typeof Notification === 'undefined') return false
  // ⛔ 不在异步事件里主动 requestPermission：浏览器要求手势，调了也只会被静默拒。
  //   只消费「用户此前已经放行」的结果。
  if (Notification.permission !== 'granted') return false
  const n = new Notification(title, {
    body,
    // 同一个 tag 会替换上一条（同一会话连续两轮结束时不会堆一屏）
    tag: 'nexusai-turn-done',
    silent: false,
  })
  // 点击通知 → 把窗口拉回前台（Web 端没有可靠的跨平台「聚焦窗口」API，
  //   这里只做「响应用户点击」的语义：关掉通知本身，由浏览器决定是否切标签页）
  n.onclick = () => {
    try {
      window.focus()
      n.close()
    } catch { /* 某些浏览器禁止程序化关闭，忽略 */ }
  }
  return true
}
