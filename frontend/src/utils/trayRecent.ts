/**
 * 托盘「最近任务」的前端侧接线。
 *
 * <h2>为什么前端要参与</h2>
 * 会话列表（含标题实时改名、本地乐观新建）唯一真源在**前端 store**。
 * 若让 Rust 再走一遍 HTTP 拉列表，就等于造第二份真源，还要处理端口/鉴权/错误。
 * 所以分工是：**前端推列表 → Rust 只负责重建菜单 → 点击后 Rust 发事件回来**。
 *
 * <h2>数据流</h2>
 * <pre>
 *   sessions 变化（含标题改名）
 *        ↓  updateTrayRecent(list)   [IPC: update_tray_recent]
 *   Rust rebuild_tray_menu → tray.set_menu(...)
 *        ↓  用户点某个任务
 *   Rust: show_main_window + emit('tray://switch-session', sid)
 *        ↓
 *   前端: onTraySwitchSession(cb) → cb(sid) → 切换会话
 * </pre>
 *
 * <p>所有失败一律**静默**：浏览器环境（无 Tauri）直接跳过；IPC 报错不回传
 * （托盘是增强功能，推列表失败不该影响任何主流程）。
 */

/** 推给托盘的「最近任务」条目（与 Rust 侧 `RecentTask` 同形）。 */
export interface TrayRecentTask {
  id: string
  title: string
}

/** 托盘菜单最多列几条（与 Rust 侧 `TRAY_RECENT_LIMIT` 不一致时以 Rust 为准，这里只做裁剪省流量）。 */
const TRAY_RECENT_LIMIT = 5

/**
 * 把「最近任务」推给 Rust 重建托盘菜单。
 *
 * <p>调用方（App）应在**会话列表变化时**调用它；这里自己做去重 ——
 * 内容没变就不发 IPC（会话列表在流式期间会频繁换引用，但标题/顺序通常不变）。
 *
 * @returns 真的发了 IPC → true；非 Tauri 环境 / 内容未变 / 失败 → false
 */
let lastSent = ''
export async function updateTrayRecent(
  tasks: TrayRecentTask[],
  currentId?: string | null,
): Promise<boolean> {
  try {
    const { isTauri } = await import('@tauri-apps/api/core')
    if (!isTauri()) return false
    const list = tasks.slice(0, TRAY_RECENT_LIMIT)
    // 去重：流式期间 sessions 引用频繁变化，但内容常常一模一样。
    // ⚠️ 签名必须**含 currentId** —— 切会话时列表内容不变，但勾选位置要跟着动。
    const sig = JSON.stringify({ list, currentId: currentId ?? null })
    if (sig === lastSent) return false
    const { invoke } = await import('@tauri-apps/api/core')
    await invoke('update_tray_recent', { tasks: list, currentId: currentId ?? null })
    lastSent = sig
    return true
  } catch {
    // 托盘未建（install_tray 失败）/ IPC 未注册 / 序列化异常 —— 静默
    return false
  }
}

/** 测试用：清掉「上次推送」的记忆（模块级变量会跨用例残留）。 */
export function __resetTrayRecentMemo(): void {
  lastSent = ''
}

/**
 * 监听托盘「最近任务」点击。
 *
 * @param cb 收到会话 id 时调用（宿主据此切换会话）
 * @returns 取消监听函数；非 Tauri 环境返回 no-op
 */
export async function onTraySwitchSession(cb: (sessionId: string) => void): Promise<() => void> {
  try {
    const { isTauri } = await import('@tauri-apps/api/core')
    if (!isTauri()) return () => {}
    const { listen } = await import('@tauri-apps/api/event')
    return await listen<string>('tray://switch-session', (e) => {
      const sid = e.payload
      if (typeof sid === 'string' && sid.trim() !== '') cb(sid)
    })
  } catch {
    return () => {}
  }
}
