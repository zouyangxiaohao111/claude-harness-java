import { useState, type ReactNode } from 'react'

/**
 * 右栏任务页的折叠分组（2026-10-21 用户裁定 D2）。
 *
 * <p>为什么改：原布局是 6 个模块卡各套「三张数字卡」，空态时整屏全是 0，
 * 噪音盖过信息。改成折叠行后，为 0 的组只占一行；**任意一组有数据自动展开**。
 *
 * <p>展开条件：`count > 0` 自动展开（用户手动折叠后不再强制展开 —— 用 `userToggled` 记住）。
 */
export function CollapsibleGroup({ title, count, defaultOpen = false, keepMounted = false, action, children }: {
  title: string
  /** 该组条目数（0 → 不自动展开）· 拿不到时可传 null，视为「未知，不自动展开」 */
  count?: number | null
  defaultOpen?: boolean
  /**
   * 折叠时仍挂载子组件（用 display:none 隐藏）。
   * ⚠️ 必须给「自身负责轮询/数据补录」的子组件开这个开关：折叠即卸载会让它的
   * useEffect 不跑 ⇒ 依赖它的数据源（如 subagentStore 的 REST 补录）永远为空。
   */
  keepMounted?: boolean
  /** 标题行右侧的动作（如「+ 创建」）；点击不会触发展开/折叠 */
  action?: ReactNode
  children: ReactNode
}) {
  const hasData = (count ?? 0) > 0
  // ⭐ 不用 useState 初值决定展开：count 首帧可能还是 0/undefined（子组件稍后才上报），
  //   用 useState(defaultOpen || hasData) 会把「首帧未定」固化下来，表现为「明明 0 却展开」。
  //   改成「未手动干预时 = 纯派生」：有数据自动展开，0 就只占一行。
  const [userOpen, setUserOpen] = useState<boolean | null>(null)
  const open = userOpen ?? (defaultOpen || hasData)
  const toggle = () => { setUserOpen((v) => !(v ?? (defaultOpen || hasData))) }

  return (
    <div className={`fold${open ? ' open' : ''}`}>
      <div
        className="fold-head"
        onClick={toggle}
        role="button"
        tabIndex={0}
        onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle() } }}
      >
        <span className={`fold-caret${open ? ' open' : ''}`}>▸</span>
        <span className="fold-title">{title}</span>
        {count != null && <span className="fold-count">{count}</span>}
        {action && <span className="fold-action" onClick={(e) => e.stopPropagation()}>{action}</span>}
      </div>
      {(open || keepMounted) && (
        <div className="fold-body" style={open ? undefined : { display: 'none' }}>{children}</div>
      )}
    </div>
  )
}
