import { create } from 'zustand'
import type { TodoItem } from '../api/types'

/**
 * Todo 清单 store（TodoPanel + useChatSocket 共享）· 模块级 zustand。
 *
 * <p>todoKey = 会话 id（REST 响应与 STOMP 事件同一键）。STOMP /topic/sessions/{sessionId}/todos
 * 为整体替换语义：收到事件直接 set 该 todoKey 清单（不增量）；allDone 时 todos 为空数组。
 * 会话切换由 TodoPanel 按新 sessionId 重新拉取，旧会话清单按键保留。
 */
export interface TodoState {
  /** todoKey（会话 id）→ todo 清单 */
  todos: Record<string, TodoItem[]>
  /** 整体替换该 todoKey 清单（STOMP 事件 / REST 拉取兜底） */
  setTodos: (todoKey: string, list: TodoItem[]) => void
  /** 会话切换清理：移除指定会话的 todo 清单 */
  clearSession: (sessionId: string) => void
  /** 清空全部 todo 态 */
  clearAll: () => void
}

export const useTodoStore = create<TodoState>()((set) => ({
  todos: {},
  setTodos: (todoKey, list) => set((st) => ({ todos: { ...st.todos, [todoKey]: list } })),
  clearSession: (sessionId) => set((st) => {
    if (!st.todos[sessionId]) return st
    const todos = { ...st.todos }
    delete todos[sessionId]
    return { todos }
  }),
  clearAll: () => set({ todos: {} }),
}))
