/**
 * useProviders · 全局 Provider/Model 状态 + 真实后端 CRUD
 *
 * <p>替代 App.tsx 顶部 `import { providers } from './data'` 的直接 mock 引用。
 * <p>所有变更走真实 API，成功后才更新本地 state；失败抛 ApiError 由调用方 toast。
 */

import { useCallback, useEffect, useState } from 'react'
import { providerApi } from '../api/providers'
import { ApiError } from '../api/rest'
import type {
  Provider,
  CreateProviderRequest,
  UpdateProviderRequest,
  CreateModelRequest,
  UpdateModelRequest,
} from '../api/types'

export interface UseProviders {
  list: Provider[]
  loading: boolean
  error: string | null
  /** 任意变更后调用，或挂载时显式调用（虽然 hook 已自动挂载 fetch） */
  refresh: () => Promise<void>

  createProvider: (req: CreateProviderRequest) => Promise<Provider>
  updateProvider: (id: string, req: UpdateProviderRequest) => Promise<Provider>
  deleteProvider: (id: string) => Promise<void>
  toggleProvider: (id: string, enabled: boolean) => Promise<Provider>
  testProvider: (id: string) => Promise<{ ok: boolean; latencyMs: number | null; message: string }>

  createModel: (providerId: string, req: CreateModelRequest) => Promise<void>
  updateModel: (modelId: string, req: UpdateModelRequest) => Promise<void>
  deleteModel: (modelId: string) => Promise<void>
}

export function useProviders(): UseProviders {
  const [list, setList] = useState<Provider[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // ---- 拉取列表 ----
  const refresh = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const data = await providerApi.list()
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
  const replaceInList = (updated: Provider) =>
    setList((prev) => prev.map((p) => (p.id === updated.id ? updated : p)))

  const removeFromList = (id: string) =>
    setList((prev) => prev.filter((p) => p.id !== id))

  // ---- Provider CRUD ----
  const createProvider = useCallback(async (req: CreateProviderRequest): Promise<Provider> => {
    const created = await providerApi.create(req)
    setList((prev) => [...prev, created])
    return created
  }, [])

  const updateProvider = useCallback(async (id: string, req: UpdateProviderRequest): Promise<Provider> => {
    const updated = await providerApi.update(id, req)
    replaceInList(updated)
    return updated
  }, [])

  const deleteProvider = useCallback(async (id: string): Promise<void> => {
    await providerApi.remove(id)
    removeFromList(id)
  }, [])

  const toggleProvider = useCallback(async (id: string, enabled: boolean): Promise<Provider> => {
    return updateProvider(id, { enabled })
  }, [updateProvider])

  const testProvider = useCallback(async (id: string) => {
    return providerApi.test(id)
  }, [])

  // ---- Model CRUD (乐观更新) ----
  const createModel = useCallback(async (providerId: string, req: CreateModelRequest): Promise<void> => {
    const m = await providerApi.createModel(providerId, req)
    setList((prev) => prev.map((p) =>
      p.id === providerId ? { ...p, models: [m, ...p.models] } : p
    ))
  }, [])

  const updateModel = useCallback(async (modelId: string, req: UpdateModelRequest): Promise<void> => {
    const updated = await providerApi.updateModel(modelId, req)
    setList((prev) => prev.map((p) => ({
      ...p,
      models: p.models.map((m) => (m.id === modelId ? updated : m)),
    })))
  }, [])

  const deleteModel = useCallback(async (modelId: string): Promise<void> => {
    await providerApi.removeModel(modelId)
    setList((prev) => prev.map((p) => ({
      ...p,
      models: p.models.filter((m) => m.id !== modelId),
    })))
  }, [])

  return {
    list, loading, error, refresh,
    createProvider, updateProvider, deleteProvider, toggleProvider, testProvider,
    createModel, updateModel, deleteModel,
  }
}