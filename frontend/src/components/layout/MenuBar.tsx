interface MenuBarProps {
  openSettings: () => void
}

/** 顶栏 · 单个「设置」按钮：点击直接弹出设置窗（用户拍板，无下拉菜单）。 */
export function MenuBar({ openSettings }: MenuBarProps) {
  return (
    <div className="menubar">
      <div className="menu-wrap">
        <button className="item settings-btn" onClick={openSettings}>
          设置
        </button>
      </div>
    </div>
  )
}
