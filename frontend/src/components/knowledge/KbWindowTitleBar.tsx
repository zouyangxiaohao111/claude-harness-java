import { useEffect, useRef, useState } from 'react'
import type { Window as TauriWindow } from '@tauri-apps/api/window'
import { isTauriEnv } from '@/utils/tauri-bridge'

/**
 * 知识库独立窗口的**自绘标题栏**（36px · 透明底 · 只留右上三按钮）。
 *
 * <h2>为什么不再用系统标题栏</h2>
 * 独立窗口原先用 `decorations: true`（2026-11-09 裁定：必须能原生拖拽/关闭）。
 * 但用户 2026-11-13 反馈「最顶上还有一条颜色，看着很别扭」——
 * 系统标题栏的底色不受页面 CSS 控制，应用侧也无法在 Tauri 配置里改色，
 * 只能二选一：留着这条异色条，或者自绘。
 *
 * <h2>为什么条是透明的、且不占布局高度</h2>
 * 用户要求「左边侧栏颜色直接顶到上面去，和主会话一致」——
 * 侧栏 `.kb-aside` 的 `#f9f9f9` 必须从 y=0 铺到窗口底。
 * 所以这条是 `position:absolute` 覆盖层：不挤占布局，底色透出下面的侧栏 / 主区。
 * 文字也去掉了（用户 2026-11-13：「左上角知识库 文字去掉」）。
 *
 * <h2>自绘必须补齐的两件事（否则等于把用户关在窗口里）</h2>
 * <ol>
 *   <li><b>可拖拽</b>：整条 `data-tauri-drag-region`（Tauri 的 drag.js 接管；
 *       双击由它自己发 `internal_toggle_maximize`，⛔ 不要再挂 onDoubleClick，否则互相抵消）</li>
 *   <li><b>最小化 / 最大化 / 关闭</b>：右上三按钮，位置与 Windows 原生一致
 *       （从右往左依次是 关闭 / 最大化 / 最小化）</li>
 * </ol>
 *
 * <h2>⚠️ capability 必须给这个窗口开权限</h2>
 * 窗口 API 按 label 判权限：`capabilities/default.json` 只匹配 `main`，
 * 新窗口没权限时 `close()` 会被拒（表现为「点了没反应」）。
 * 本窗的权限在 `capabilities/knowledge-window.json`（2026-11-13 补）。
 *
 * <h2>⚠️ 与主窗口 `TitleBar` 不是同一个</h2>
 * 主窗口是 macOS 风格红绿灯（左侧三个圆点、红钮=隐藏到托盘）；
 * 这里是 Windows 风格三按钮（右侧、关闭即关闭）。两者语义不同，⛔ 不要合并。
 */
export function KbWindowTitleBar() {
  const [maximized, setMaximized] = useState(false)
  /** 窗口 API 的惰性句柄（非 Tauri 环境恒 null ⇒ 按钮无动作但条仍显示）。 */
  const winRef = useRef<TauriWindow | null>(null)

  useEffect(() => {
    let un: (() => void) | undefined
    let alive = true
    void (async () => {
      if (!(await isTauriEnv())) return
      const { getCurrentWindow } = await import('@tauri-apps/api/window')
      const win = getCurrentWindow()
      if (!alive) return
      winRef.current = win
      try { setMaximized(await win.isMaximized()) } catch { /* 窗口正在关闭 */ }
      try {
        // 最大化状态会随系统手势（Win+↑ / 拖到屏幕顶）变化 ⇒ 必须订阅，不能只在挂载时读一次
        un = await win.onResized(() => {
          void win.isMaximized().then((v) => { if (alive) setMaximized(v) }).catch(() => {})
        })
      } catch { /* 同上 */ }
    })()
    return () => { alive = false; un?.() }
  }, [])

  const act = (fn: 'minimize' | 'toggleMaximize' | 'close') => {
    const w = winRef.current
    if (!w) {
      // 没有句柄 = 非 Tauri 环境（浏览器 dev）或窗口 API 尚未就绪。
      // ⚠️ 仍保留 warn：权限缺失时 Tauri 会 reject，被下面 catch 接住并打出来。
      console.warn('[kb-window] 窗口 API 不可用，忽略', fn)
      return
    }
    void w[fn]().catch((e) => {
      // 最常见原因：capabilities 里没给这个窗口 label 开对应 allow-*（Tauri 按 label 判权限）
      console.warn('[kb-window] 窗口操作被拒', fn, e)
    })
  }

  return (
    <div className="kb-window-titlebar" data-tauri-drag-region>
      <div className="kb-window-titlebar-actions">
        <button type="button" className="kb-window-titlebar-btn" title="最小化" aria-label="最小化" onClick={() => act('minimize')}>
          <svg viewBox="0 0 10 10" width="10" height="10" aria-hidden="true"><path d="M1 5h8" stroke="currentColor" strokeWidth="1" /></svg>
        </button>
        <button type="button" className="kb-window-titlebar-btn" title={maximized ? '向下还原' : '最大化'} aria-label={maximized ? '向下还原' : '最大化'} onClick={() => act('toggleMaximize')}>
          {maximized ? (
            <svg viewBox="0 0 10 10" width="10" height="10" fill="none" stroke="currentColor" strokeWidth="1" aria-hidden="true">
              <rect x="1.5" y="3.5" width="5" height="5" />
              <path d="M3.5 3.5v-2h5v5h-2" />
            </svg>
          ) : (
            <svg viewBox="0 0 10 10" width="10" height="10" fill="none" stroke="currentColor" strokeWidth="1" aria-hidden="true">
              <rect x="1.5" y="1.5" width="7" height="7" />
            </svg>
          )}
        </button>
        <button type="button" className="kb-window-titlebar-btn is-close" title="关闭" aria-label="关闭" onClick={() => act('close')}>
          <svg viewBox="0 0 10 10" width="10" height="10" aria-hidden="true"><path d="M1.5 1.5l7 7M8.5 1.5l-7 7" stroke="currentColor" strokeWidth="1" /></svg>
        </button>
      </div>
    </div>
  )
}
