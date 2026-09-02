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
export function TitleBar() {
  const [maximized, setMaximized] = useState(false)

  // 初始化查询 + 订阅最大化状态变化
  useEffect(() => {
    let unsub: (() => void) | null = null
    void isWindowMaximized().then(setMaximized)
    void onMaximizeChange(setMaximized).then((u) => { unsub = u })
    return () => { unsub?.() }
  }, [])

  return (
    <div className="titlebar" onDoubleClick={() => void toggleMaximize()}>
      <div className="traffic">
        <button
          type="button"
          className="traffic-btn c-red"
          onClick={() => void closeWindow()}
          title="关闭窗口"
          aria-label="关闭窗口"
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
          {maximized ? (
            // 还原图标：两个小方块
            <svg viewBox="0 0 10 10" fill="none" stroke="currentColor" strokeWidth="1.2" strokeLinejoin="round">
              <rect x="1.5" y="3" width="5.5" height="5.5" rx="0.5" />
              <path d="M3.5 3V2.5C3.5 2.2 3.7 2 4 2H7.5C7.8 2 8 2.2 8 2.5V6C8 6.3 7.8 6.5 7.5 6.5H7" />
            </svg>
          ) : (
            // 最大化图标：单个大方块
            <svg viewBox="0 0 10 10" fill="none" stroke="currentColor" strokeWidth="1.2" strokeLinejoin="round">
              <rect x="1.5" y="1.5" width="7" height="7" rx="0.5" />
            </svg>
          )}
        </button>
      </div>
      <span className="app-name">NexusAI</span>
    </div>
  )
}
