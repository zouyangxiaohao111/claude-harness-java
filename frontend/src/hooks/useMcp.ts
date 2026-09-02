/**
 * useMcp · 全局 MCP server 状态 + 真实后端 CRUD
 *
 * <p>所有变更走真实 API，成功后才更新本地 state；失败抛 ApiError 由调用方 toast。
 */

import { useCallback, useEffect, useState } from 'react'
import { mcpApi } from '../api/mcp'
import { ApiError } from '../api/rest'
import type { McpServer, CreateMcpRequest, UpdateMcpRequest } from '../api/types'

export interface UseMcp {
  list: McpServer[]
  loading: boolean
  error: string | null
  /** 任意变更后调用，或挂载时显式调用（虽然 hook 已自动挂载 fetch） */
  refresh: () => Promise<void>

  createMcp: (req: CreateMcpRequest) => Promise<McpServer>
  updateMcp: (id: string, req: UpdateMcpRequest) => Promise<McpServer>
  deleteMcp: (id: string) => Promise<void>
  toggleMcp: (id: string, enabled: boolean) => Promise<McpServer>
}

export function useMcp(): UseMcp {
  const [list, setList] = useState<McpServer[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // ---- 拉取列表 ----
  const refresh = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const data = await mcpApi.list()
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
  const replaceInList = (updated: McpServer) =>
    setList((prev) => prev.map((m) => (m.id === updated.id ? updated : m)))

  const removeFromList = (id: string) =>
    setList((prev) => prev.filter((m) => m.id !== id))

  // ---- MCP CRUD ----
  const createMcp = useCallback(async (req: CreateMcpRequest): Promise<McpServer> => {
    const created = await mcpApi.create(req)
    setList((prev) => [...prev, created])
    return created
  }, [])

  const updateMcp = useCallback(async (id: string, req: UpdateMcpRequest): Promise<McpServer> => {
    const updated = await mcpApi.update(id, req)
    replaceInList(updated)
    return updated
  }, [])

  const deleteMcp = useCallback(async (id: string): Promise<void> => {
    await mcpApi.remove(id)
    removeFromList(id)
  }, [])

  const toggleMcp = useCallback(async (id: string, enabled: boolean): Promise<McpServer> => {
    return updateMcp(id, { enabled })
  }, [updateMcp])

  return {
    list, loading, error, refresh,
    createMcp, updateMcp, deleteMcp, toggleMcp,
  }
}
