import { useNotificationStore } from '@/stores/notificationStore'

/**
 * 展示 store.current：TextNotification 渲染 text，JSXNotification 渲染 jsx。
 * current 为空返回 null。样式用 className（notification-banner），暂不加 CSS（对齐 PermissionBubble 先例）。
 */
export function NotificationBanner() {
  const current = useNotificationStore((s) => s.current)
  if (!current) return null
  if ('jsx' in current) {
    return <div className="notification-banner">{current.jsx}</div>
  }
  return <div className="notification-banner">{current.text}</div>
}
