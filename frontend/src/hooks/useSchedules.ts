/**
 * useSchedules · 全局 Schedule 状态 + 真实后端 CRUD
 *
 * <p>所有变更走真实 API，成功后才更新本地 state；失败抛 ApiError 由调用方 toast。
 */

import { useCallback, useEffect, useState } from 'react'
import { scheduleApi } from '../api/schedules'
import { ApiError } from '../api/rest'
import type {
  Schedule,
  CreateScheduleRequest,
  UpdateScheduleRequest,
  RunNowResponse,
} from '../api/types'

export interface UseSchedules {
  list: Schedule[]
  loading: boolean
  error: string | null
  /** 任意变更后调用，或挂载时显式调用（虽然 hook 已自动挂载 fetch） */
  refresh: () => Promise<void>

  createSchedule: (req: CreateScheduleRequest) => Promise<Schedule>
  updateSchedule: (id: string, req: UpdateScheduleRequest) => Promise<Schedule>
  deleteSchedule: (id: string) => Promise<void>
  runNow: (id: string) => Promise<RunNowResult>
}

/** 后端 RunNowResponse 实测含 deleted（once fire-then-delete）；types.ts 尚未补该字段，局部扩展 */
export type RunNowResult = RunNowResponse & { deleted: boolean }

export function useSchedules(): UseSchedules {
  const [list, setList] = useState<Schedule[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // ---- 拉取列表 ----
  const refresh = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const data = await scheduleApi.list()
      // 内容守卫 + 按 id 去重：轮询数据未变则不 setList —— 新数组引用会触发 RightPanel 任务 tab
      // 整块重渲染（TeamPanel/AsyncTasks/Workflow 全部）→ 2s 刷新卡顿（对齐 AsyncTasksPanel:58 同款守卫）
      const deduped = data.filter((s, i, arr) => arr.findIndex((x) => x.id === s.id) === i)
      setList((prev) => (JSON.stringify(prev) === JSON.stringify(deduped) ? prev : deduped))
    } catch (e) {
      const msg = e instanceof ApiError ? e.userMessage() : String(e)
      setError(msg)
      // 不抛 — 列表为空时 UI 还能渲染
    } finally {
      setLoading(false)
    }
  }, [])

  // 挂载时自动 fetch + 2s 轮询：定时任务可由 AI（CronCreateTool）在对话中创建/删除，前端仅挂载
  //   refresh 一次会一直空（联调实测 2026-08-27）→ 轮询对齐 AsyncTasksPanel，AI 变更 2s 内显示
  useEffect(() => {
    void refresh()
    const timer = window.setInterval(refresh, 2000)
    return () => window.clearInterval(timer)
  }, [refresh])

  // ---- 局部更新辅助 ----
  const replaceInList = (updated: Schedule) =>
    setList((prev) => prev.map((s) => (s.id === updated.id ? updated : s)))

  const removeFromList = (id: string) =>
    setList((prev) => prev.filter((s) => s.id !== id))

  // ---- Schedule CRUD ----
  const createSchedule = useCallback(async (req: CreateScheduleRequest): Promise<Schedule> => {
    const created = await scheduleApi.create(req)
    setList((prev) => [...prev, created])
    return created
  }, [])

  const updateSchedule = useCallback(async (id: string, req: UpdateScheduleRequest): Promise<Schedule> => {
    const updated = await scheduleApi.update(id, req)
    replaceInList(updated)
    return updated
  }, [])

  const deleteSchedule = useCallback(async (id: string): Promise<void> => {
    await scheduleApi.remove(id)
    removeFromList(id)
  }, [])

  const runNow = useCallback(async (id: string): Promise<RunNowResult> => {
    const res = (await scheduleApi.runNow(id)) as RunNowResult
    // CRON-B4-4：once 任务 run 后行已删（fire-then-delete）→ 重拉列表移除该行
    if (res.deleted) await refresh()
    return res
  }, [refresh])

  return {
    list, loading, error, refresh,
    createSchedule, updateSchedule, deleteSchedule, runNow,
  }
}
