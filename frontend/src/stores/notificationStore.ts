import { create } from 'zustand'
import type { ReactNode } from 'react'

/**
 * 纯客户端通知状态机，对齐 CC Open-ClaudeCode/src/context/notifications.tsx。
 * CC 无 server→client 通知通道，通知是前端本地队列。
 */

export type Priority = 'low' | 'medium' | 'high' | 'immediate'

export interface BaseNotification {
  key: string
  /**
   * 本通知会失效的 key 列表：被失效的通知从队列移除；若正展示则立即清除。
   * CC original: invalidates (Open-ClaudeCode/src/context/notifications.tsx)
   */
  invalidates?: string[]
  priority: Priority
  timeoutMs?: number
  /**
   * 同 key 通知合并，类似 Array.reduce()。当队列或 current 中已存在同 key 通知时，
   * 以 fold(accumulator, incoming) 调用，返回合并后的通知（应继续携带 fold 供后续合并）。
   * CC original: fold (Open-ClaudeCode/src/context/notifications.tsx)
   */
  fold?: (accumulator: Notification, incoming: Notification) => Notification
}

export interface TextNotification extends BaseNotification {
  text: string
  /** CC 用 keyof Theme；前端无 Theme 类型，降级为 string。CC original: color (keyof Theme) */
  color?: string
}

export interface JSXNotification extends BaseNotification {
  jsx: ReactNode
}

export type Notification = TextNotification | JSXNotification

const DEFAULT_TIMEOUT_MS = 8000

const PRIORITIES: Record<Priority, number> = {
  immediate: 0,
  high: 1,
  medium: 2,
  low: 3,
}

// 模块级 timeout 句柄，immediate 到达时 clear（对齐 CC 的 module-level currentTimeoutId）
let currentTimeoutId: ReturnType<typeof setTimeout> | null = null

/** 队列中优先级最高者（PRIORITIES 值最小）。CC original: getNext */
export function getNext(queue: Notification[]): Notification | undefined {
  if (queue.length === 0) return undefined
  return queue.reduce((min, n) =>
    PRIORITIES[n.priority] < PRIORITIES[min.priority] ? n : min,
  )
}

export interface NotificationState {
  queue: Notification[]
  current: Notification | null
  addNotification: (notif: Notification) => void
  removeNotification: (key: string) => void
}

export const useNotificationStore = create<NotificationState>()((set, get) => {
  // 把 CC 的 processQueue 折叠进 store：current 为空时取 getNext 出队，setTimeout 超时清 current 再递归
  const processQueue = () => {
    const prev = get()
    const next = getNext(prev.queue)
    if (prev.current !== null || !next) return

    currentTimeoutId = setTimeout(() => {
      currentTimeoutId = null
      set((p) => {
        // 按 key 而非引用比较，兼容重建的通知对象
        if (p.current?.key !== next.key) return {}
        return { queue: p.queue, current: null }
      })
      processQueue()
    }, next.timeoutMs ?? DEFAULT_TIMEOUT_MS)

    set((p) => ({ queue: p.queue.filter((n) => n !== next), current: next }))
  }

  const addNotification = (notif: Notification) => {
    // immediate 优先级：立即显示，清空非 immediate 队列
    if (notif.priority === 'immediate') {
      if (currentTimeoutId) {
        clearTimeout(currentTimeoutId)
        currentTimeoutId = null
      }

      currentTimeoutId = setTimeout(() => {
        currentTimeoutId = null
        set((prev) => {
          if (prev.current?.key !== notif.key) return {}
          return {
            queue: prev.queue.filter((n) => !notif.invalidates?.includes(n.key)),
            current: null,
          }
        })
        processQueue()
      }, notif.timeoutMs ?? DEFAULT_TIMEOUT_MS)

      set((prev) => ({
        current: notif,
        queue: [
          ...(prev.current ? [prev.current] : []),
          ...prev.queue,
        ].filter(
          (n) => n.priority !== 'immediate' && !notif.invalidates?.includes(n.key),
        ),
      }))
      return
    }

    // 非 immediate
    set((prev) => {
      if (notif.fold) {
        // 与 current 同 key → fold 合并
        if (prev.current?.key === notif.key) {
          const folded = notif.fold(prev.current, notif)
          if (currentTimeoutId) {
            clearTimeout(currentTimeoutId)
            currentTimeoutId = null
          }
          currentTimeoutId = setTimeout(() => {
            currentTimeoutId = null
            set((p) => {
              if (p.current?.key !== folded.key) return {}
              return { queue: p.queue, current: null }
            })
            processQueue()
          }, folded.timeoutMs ?? DEFAULT_TIMEOUT_MS)
          return { current: folded, queue: prev.queue }
        }

        // 与队列中同 key → fold 合并
        const queueIdx = prev.queue.findIndex((n) => n.key === notif.key)
        if (queueIdx !== -1) {
          const folded = notif.fold(prev.queue[queueIdx]!, notif)
          const newQueue = [...prev.queue]
          newQueue[queueIdx] = folded
          return { current: prev.current, queue: newQueue }
        }
      }

      // 同 key 去重（防止重复入队）
      const queuedKeys = new Set(prev.queue.map((n) => n.key))
      const shouldAdd =
        !queuedKeys.has(notif.key) && prev.current?.key !== notif.key
      if (!shouldAdd) return {}

      const invalidatesCurrent =
        prev.current !== null && notif.invalidates?.includes(prev.current.key)
      if (invalidatesCurrent && currentTimeoutId) {
        clearTimeout(currentTimeoutId)
        currentTimeoutId = null
      }

      return {
        current: invalidatesCurrent ? null : prev.current,
        queue: [
          ...prev.queue.filter(
            (n) =>
              n.priority !== 'immediate' && !notif.invalidates?.includes(n.key),
          ),
          notif,
        ],
      }
    })

    processQueue()
  }

  const removeNotification = (key: string) => {
    set((prev) => {
      const isCurrent = prev.current?.key === key
      const inQueue = prev.queue.some((n) => n.key === key)
      if (!isCurrent && !inQueue) return {}
      if (isCurrent && currentTimeoutId) {
        clearTimeout(currentTimeoutId)
        currentTimeoutId = null
      }
      return {
        current: isCurrent ? null : prev.current,
        queue: prev.queue.filter((n) => n.key !== key),
      }
    })
    processQueue()
  }

  return {
    queue: [],
    current: null,
    addNotification,
    removeNotification,
  }
})

export function createNotificationStore() {
  return useNotificationStore
}
