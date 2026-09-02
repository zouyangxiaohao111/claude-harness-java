/**
 * useSkills · 全局 Skill 状态 + 真实后端 CRUD
 *
 * <p>所有变更走真实 API，成功后才更新本地 state；失败抛 ApiError 由调用方 toast。
 */

import { useCallback, useEffect, useState } from 'react'
import { skillApi } from '../api/skills'
import { ApiError } from '../api/rest'
import type { Skill, CreateSkillRequest, UpdateSkillRequest } from '../api/types'

export interface UseSkills {
  list: Skill[]
  loading: boolean
  error: string | null
  /** 任意变更后调用，或挂载时显式调用（虽然 hook 已自动挂载 fetch） */
  refresh: () => Promise<void>

  createSkill: (req: CreateSkillRequest) => Promise<Skill>
  updateSkill: (id: string, req: UpdateSkillRequest) => Promise<Skill>
  deleteSkill: (id: string) => Promise<void>
  toggleSkill: (id: string, enabled: boolean) => Promise<Skill>
}

export function useSkills(): UseSkills {
  const [list, setList] = useState<Skill[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // ---- 拉取列表 ----
  const refresh = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const data = await skillApi.list()
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
  const replaceInList = (updated: Skill) =>
    setList((prev) => prev.map((s) => (s.id === updated.id ? updated : s)))

  const removeFromList = (id: string) =>
    setList((prev) => prev.filter((s) => s.id !== id))

  // ---- Skill CRUD ----
  const createSkill = useCallback(async (req: CreateSkillRequest): Promise<Skill> => {
    const created = await skillApi.create(req)
    setList((prev) => [...prev, created])
    return created
  }, [])

  const updateSkill = useCallback(async (id: string, req: UpdateSkillRequest): Promise<Skill> => {
    const updated = await skillApi.update(id, req)
    replaceInList(updated)
    return updated
  }, [])

  const deleteSkill = useCallback(async (id: string): Promise<void> => {
    await skillApi.remove(id)
    removeFromList(id)
  }, [])

  const toggleSkill = useCallback(async (id: string, enabled: boolean): Promise<Skill> => {
    return updateSkill(id, { enabled })
  }, [updateSkill])

  return {
    list, loading, error, refresh,
    createSkill, updateSkill, deleteSkill, toggleSkill,
  }
}
