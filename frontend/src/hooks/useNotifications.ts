import { useNotificationStore } from '../stores/notificationStore'

/**
 * 薄封装：从 notificationStore 取 action，对齐 CC useNotifications 的返回值
 * { addNotification, removeNotification }。
 */
export function useNotifications() {
  const addNotification = useNotificationStore((s) => s.addNotification)
  const removeNotification = useNotificationStore((s) => s.removeNotification)
  return { addNotification, removeNotification }
}
