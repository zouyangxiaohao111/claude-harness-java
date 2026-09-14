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

/**
 * 定时任务 hook。
 *
 * @param activeSessionId 当前活动会话 id（后端契约：REST 直建 DURABLE 任务**必须**带一个能解析出
 *   绑定项目根（boundProject）的 sessionId —— 任务的项目锚由后端按会话推导，客户端不得指定）。
 *   无活动会话时 `createSchedule` 会**先在前端拦截**并给出可读提示，不发请求（否则必然 400）。
 */
export function useSchedules(activeSessionId?: string | null): UseSchedules {
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
    // 后端契约（[cwd3]）：REST 直建 DURABLE 必须带能解析出绑定项目根的 sessionId；
    // 缺 id / 传哨兵 / 解析不到 ⇒ 400（Validation Failed / Unresolved Project Root）。
    // ⇒ 前端先拦截：无活动会话时直接给可读提示，不发这一次注定 400 的请求。
    const scope = req.scope ?? 'DURABLE'
    if (scope === 'DURABLE' && !activeSessionId) {
      throw new Error('请先打开一个会话再创建定时任务：任务需要归属一个已绑定项目的会话')
    }
    // DURABLE：sessionId 一律取当前活动会话（⛔ 不由调用方传，避免传错会话把任务锚到别的项目）；
    // 后端还会用 sessionId 解析结果**覆盖**请求体里的 boundProject ⇒ 客户端无锚可伪造。
    const payload: CreateScheduleRequest =
      scope === 'DURABLE' ? { ...req, scope, sessionId: activeSessionId! } : { ...req, scope }
    const created = await scheduleApi.create(payload)
    setList((prev) => [...prev, created])
    return created
  }, [activeSessionId])

  const updateSchedule = useCallback(async (id: string, req: UpdateScheduleRequest): Promise<Schedule> => {
    // ⛔ update 请求不得带 scope/sessionId（[cwd3 D6]：两者创建后不可变，带了 ⇒ 后端 400）。
    //   SchedulesPanel.buildRequest 本就不发这两个字段；此处再剥一层，防将来有人顺手带上。
    const rest: UpdateScheduleRequest = { ...req }
    delete rest.scope
    delete rest.sessionId
    const updated = await scheduleApi.update(id, rest)
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
