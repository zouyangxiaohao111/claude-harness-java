import { useEffect, useState } from 'react'
import { closeWindow, isWindowMaximized, minimizeWindow, onMaximizeChange, toggleMaximize } from '@/utils/tauri-bridge'

/**
 * TitleBar — macOS-style traffic light buttons (close / minimize / zoom).
 *
 * Each button:
 *   - shows an icon on hover (macOS convention)
 *   - has a tooltip via `title` attribute
 *   - cursor: pointer; `-webkit-app-region: no-drag` so clicks don't drag the window
 *   - active: scale-down for tactile feedback
 *
 * Zoom（绿色）按钮：双击标题栏 / 点击均切换最大化；isMaximized 时图标切换为「还原」。
 */
export function TitleBar({ onToggleSidebar, sidebarCollapsed }: {
  /** 左栏收起/展开（用户裁定：按钮放在红绿灯右侧，紧挨着「x -」） */
  onToggleSidebar?: () => void
  sidebarCollapsed?: boolean
} = {}) {
  const [maximized, setMaximized] = useState(false)

  // 初始化查询 + 订阅最大化状态变化
  useEffect(() => {
    let unsub: (() => void) | null = null
    void isWindowMaximized().then(setMaximized)
    void onMaximizeChange(setMaximized).then((u) => { unsub = u })
    return () => { unsub?.() }
  }, [])

  // ⛔ 不能保留 onDoubleClick={() => toggleMaximize()}：`.topbar` 上加了
  //   `data-tauri-drag-region` 后，Tauri 的 drag.js 在 Windows 上对双击自己发
  //   `internal_toggle_maximize`（drag.js: `e.detail === 2 ? 'internal_toggle_maximize'`）
  //   ⇒ 两个处理器各切一次 = 互相抵消 = 双击没反应。双击最大化统一交给 Tauri。
  return (
    <div className="titlebar">
      <div className="traffic">
        <button
          type="button"
          className="traffic-btn c-red"
          onClick={() => void closeWindow()}
          // [2026-11-04 用户裁定] 点红钮不再退出，而是隐藏到系统托盘；
          //   真退出走托盘右键菜单「退出 NexusAI」（Rust 侧 main.rs 的 HideOnClose 开关）。
          title="隐藏到托盘（退出请右键托盘图标）"
          aria-label="隐藏到托盘"
        >
          <svg viewBox="0 0 10 10" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round">
            <path d="M2.5 2.5L7.5 7.5M7.5 2.5L2.5 7.5" />
          </svg>
        </button>
        <button
          type="button"
          className="traffic-btn c-yellow"
          onClick={() => void minimizeWindow()}
          title="最小化"
          aria-label="最小化"
        >
          <svg viewBox="0 0 10 10" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round">
            <path d="M2.5 5H7.5" />
          </svg>
        </button>
        <button
          type="button"
          className="traffic-btn c-green"
          onClick={() => void toggleMaximize()}
          title={maximized ? '还原窗口' : '最大化窗口'}
          aria-label={maximized ? '还原窗口' : '最大化窗口'}
        >
          {/* [2026-11-02 用户裁定] 用 **macOS 原生缩放图标**：左上 + 右下两个对角三角形。
              旧实现是单个圆角矩形（最大化）/两个小方块（还原）—— 用户明确说「不是这个口」。
              展开态 = 两三角向外张开（↖↘），还原态 = 两三角向内收拢，与 macOS 绿钮逐字一致。 */}
          {maximized ? (
            // 还原：对角三角形换到「右上 + 左下」—— 与最大化态成镜像，一眼区分
            <svg viewBox="0 0 10 10" fill="currentColor" aria-hidden="true">
              <path d="M8.4 1.6v4.4h-4.4z" />
              <path d="M1.6 8.4V4h4.4z" />
            </svg>
          ) : (
            // 最大化：左上 + 右下 两个对角三角形（macOS 绿钮标准形）
            <svg viewBox="0 0 10 10" fill="currentColor" aria-hidden="true">
              <path d="M1.6 1.6h4.4L1.6 6z" />
              <path d="M8.4 8.4H4l4.4-4.4z" />
            </svg>
          )}
        </button>
        {/* [2026-11-01 用户裁定] 左栏开合按钮放在红绿灯右侧，紧挨着「x -」三个点。
            此前 `.topbar-side-toggle` 只有样式、从未渲染 —— 收起后按钮消失的根因之一。
            ⚠️ **只在收起态显示**：展开时左栏头部本来就有自己的收起按钮（`.rail-collapse-btn`），
            两个同时出现会重复（用户实测「点击展开后没消失」）。 */}
        {onToggleSidebar && sidebarCollapsed && (
          <button
            type="button"
            className="topbar-side-toggle"
            onClick={onToggleSidebar}
            title={sidebarCollapsed ? '展开会话栏' : '收起会话栏'}
            aria-label={sidebarCollapsed ? '展开会话栏' : '收起会话栏'}
            aria-expanded={!sidebarCollapsed}
          >
            <svg viewBox="0 0 16 14" fill="none" width="16" height="14">
              <rect x="1.1" y="1.1" width="13.8" height="11.8" rx="2.6" stroke="currentColor" strokeWidth="1.35" />
              <line x1="6" y1="1.4" x2="6" y2="12.6" stroke="currentColor" strokeWidth="1.35" strokeLinecap="round" />
            </svg>
          </button>
        )}
      </div>
      <span className="app-name">NexusAI</span>
    </div>
  )
}
