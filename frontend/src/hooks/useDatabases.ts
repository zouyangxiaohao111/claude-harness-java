/**
 * useDatabases · 全局 Database connection 状态 + 真实后端 CRUD
 *
 * <p>所有变更走真实 API，成功后才更新本地 state；失败抛 ApiError 由调用方 toast。
 */

import { useCallback, useEffect, useState } from 'react'
import { databaseApi } from '../api/databases'
import { ApiError } from '../api/rest'
import type {
  DatabaseConnection,
  CreateDatabaseRequest,
  UpdateDatabaseRequest,
  TestConnectionResponse,
} from '../api/types'

export interface UseDatabases {
  list: DatabaseConnection[]
  loading: boolean
  error: string | null
  /** 任意变更后调用，或挂载时显式调用（虽然 hook 已自动挂载 fetch） */
  refresh: () => Promise<void>

  createDatabase: (req: CreateDatabaseRequest) => Promise<DatabaseConnection>
  updateDatabase: (id: string, req: UpdateDatabaseRequest) => Promise<DatabaseConnection>
  deleteDatabase: (id: string) => Promise<void>
  testDatabase: (id: string) => Promise<TestConnectionResponse>
}

export function useDatabases(): UseDatabases {
  const [list, setList] = useState<DatabaseConnection[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // ---- 拉取列表 ----
  const refresh = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const data = await databaseApi.list()
      setList(data)
    } catch (e) {
      const msg = e instanceof ApiError ? e.userMessage() : String(e)
      setError(msg)
      // 不抛 — 列表为空时 UI 还能渲染
    } finally {
      setLoading(false)
    }
  }, [])

  // 挂载时自动 fetch
  useEffect(() => {
    void refresh()
  }, [refresh])

  // ---- 局部更新辅助 ----
  const replaceInList = (updated: DatabaseConnection) =>
    setList((prev) => prev.map((d) => (d.id === updated.id ? updated : d)))

  const removeFromList = (id: string) =>
    setList((prev) => prev.filter((d) => d.id !== id))

  // ---- Database CRUD ----
  const createDatabase = useCallback(async (req: CreateDatabaseRequest): Promise<DatabaseConnection> => {
    const created = await databaseApi.create(req)
    setList((prev) => [...prev, created])
    return created
  }, [])

  const updateDatabase = useCallback(async (id: string, req: UpdateDatabaseRequest): Promise<DatabaseConnection> => {
    const updated = await databaseApi.update(id, req)
    replaceInList(updated)
    return updated
  }, [])

  const deleteDatabase = useCallback(async (id: string): Promise<void> => {
    await databaseApi.remove(id)
    removeFromList(id)
  }, [])

  const testDatabase = useCallback(async (id: string): Promise<TestConnectionResponse> => {
    return databaseApi.test(id)
  }, [])

  return {
    list, loading, error, refresh,
    createDatabase, updateDatabase, deleteDatabase, testDatabase,
  }
}
