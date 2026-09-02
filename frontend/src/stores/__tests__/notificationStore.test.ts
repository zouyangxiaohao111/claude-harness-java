import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest'
import {
  createNotificationStore,
  getNext,
  type Notification,
  type TextNotification,
} from '../notificationStore'

describe('notificationStore 通知状态机（对齐 CC notifications.tsx）', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    createNotificationStore().setState({ queue: [], current: null })
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('addNotification 非 immediate → 立即提升为 current，超时后清空', () => {
    const s = createNotificationStore()
    s.getState().addNotification({ key: 'a', priority: 'medium', text: 'hello' })
    expect(s.getState().current?.key).toBe('a')
    expect(s.getState().queue.length).toBe(0)
    vi.advanceTimersByTime(8000)
    expect(s.getState().current).toBeNull()
  })

  it('immediate 优先级 → 立即成为 current 且队列中无 immediate', () => {
    const s = createNotificationStore()
    s.getState().addNotification({ key: 'p', priority: 'low', text: 'pending' })
    expect(s.getState().current?.key).toBe('p')
    s.getState().addNotification({ key: 'imm', priority: 'immediate', text: 'now' })
    const st = s.getState()
    expect(st.current?.key).toBe('imm')
    expect(st.queue.some((n) => n.priority === 'immediate')).toBe(false)
    expect(st.queue.some((n) => n.key === 'p')).toBe(true)
  })

  it('同 key dedup：重复 add 同 key 不入队两次', () => {
    const s = createNotificationStore()
    s.getState().addNotification({ key: 'a', priority: 'low', text: 'a1' })
    s.getState().addNotification({ key: 'b', priority: 'medium', text: 'b1' })
    expect(s.getState().current?.key).toBe('a')
    expect(s.getState().queue.length).toBe(1)
    s.getState().addNotification({ key: 'b', priority: 'medium', text: 'b2' })
    expect(s.getState().queue.filter((n) => n.key === 'b').length).toBe(1)
  })

  it('fold 合并同 key（current）', () => {
    const s = createNotificationStore()
    const fold = (acc: Notification, inc: Notification): Notification => ({
      ...acc,
      text: `${(acc as TextNotification).text}+${(inc as TextNotification).text}`,
    })
    s.getState().addNotification({ key: 'f', priority: 'low', text: '1', fold })
    s.getState().addNotification({ key: 'f', priority: 'low', text: '2', fold })
    expect(s.getState().current?.key).toBe('f')
    expect((s.getState().current as TextNotification).text).toBe('1+2')
  })

  it('fold 合并同 key（队列中）', () => {
    const s = createNotificationStore()
    const fold = (acc: Notification, inc: Notification): Notification => ({
      ...acc,
      text: `${(acc as TextNotification).text}+${(inc as TextNotification).text}`,
    })
    s.getState().addNotification({ key: 'x', priority: 'low', text: 'a' })
    s.getState().addNotification({ key: 'y', priority: 'medium', text: 'y1' })
    s.getState().addNotification({ key: 'y', priority: 'medium', text: 'y2', fold })
    const queued = s.getState().queue.find((n) => n.key === 'y')
    expect((queued as TextNotification).text).toBe('y1+y2')
  })

  it('invalidates 失效 current 与队列中的指定 key', () => {
    const s = createNotificationStore()
    s.getState().addNotification({ key: 'old', priority: 'low', text: 'old' })
    s.getState().addNotification({ key: 'queued', priority: 'medium', text: 'q' })
    s.getState().addNotification({
      key: 'fresh',
      priority: 'high',
      text: 'fresh',
      invalidates: ['old', 'queued'],
    })
    const st = s.getState()
    expect(st.current?.key).toBe('fresh')
    expect(st.queue.some((n) => n.key === 'queued')).toBe(false)
    expect(st.queue.some((n) => n.key === 'old')).toBe(false)
  })

  it('removeNotification 按 key 移除 current', () => {
    const s = createNotificationStore()
    s.getState().addNotification({ key: 'r', priority: 'low', text: 'remove me' })
    expect(s.getState().current?.key).toBe('r')
    s.getState().removeNotification('r')
    expect(s.getState().current).toBeNull()
  })

  it('removeNotification 按 key 移除队列中的通知', () => {
    const s = createNotificationStore()
    s.getState().addNotification({ key: 'c', priority: 'low', text: 'c' })
    s.getState().addNotification({ key: 'q', priority: 'medium', text: 'q' })
    s.getState().removeNotification('q')
    expect(s.getState().queue.some((n) => n.key === 'q')).toBe(false)
    expect(s.getState().current?.key).toBe('c')
  })

  it('getNext 按优先级最小返回', () => {
    const q: Notification[] = [
      { key: 'low', priority: 'low', text: 'l' },
      { key: 'high', priority: 'high', text: 'h' },
      { key: 'medium', priority: 'medium', text: 'm' },
    ]
    expect(getNext(q)?.key).toBe('high')
    expect(getNext([])).toBeUndefined()
  })
})
