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

  /**
   * 能否创建定时任务（= 有活动会话）。
   * ⭐ **两个 scope 都**要求会话（见 {@link createSchedule}）⇒ 无会话时 UI 应**置灰创建按钮**
   *   （而不是让用户填完表单再吃 400 —— 该前置在对话框里改不掉，纯确定性判据）。
   */
  canCreate: boolean

  createSchedule: (req: CreateScheduleRequest) => Promise<Schedule>
  updateSchedule: (id: string, req: UpdateScheduleRequest) => Promise<Schedule>
  deleteSchedule: (id: string) => Promise<void>
  runNow: (id: string) => Promise<RunNowResult>
}

/** 后端 RunNowResponse 实测含 deleted（once fire-then-delete）；types.ts 尚未补该字段，局部扩展 */
export type RunNowResult = RunNowResponse & { deleted: boolean }

/**
 * 构造创建请求体（纯函数 · 可确定性单测）。
 *
 * <p><b>守的不变量①：两个 scope 都必须带 sessionId</b>。收口前只有 DURABLE 分支注入 sessionId，
 * SESSION 分支直接 `{...req, scope}` —— 若调用方没自带 sessionId，后端 service
 * （`scope=SESSION requires non-empty 'sessionId'`）必然 400。而 SESSION 的语义正是
 * 「生命周期绑创建一个会话」，它的 sessionId 本来就该是**活动会话**，⛔ 不该由调用方指定。
 *
 * <p><b>守的不变量②：缺省 scope = `SESSION`</b>（对齐 CC）。CC 真源
 * {@code CronCreateTool.ts:117} `durable = false`（默认）+ {@code ScheduleCronTool/prompt.ts:78}
 * 「By default (durable: false) the job lives only in this Claude session … Only use durable: true
 * when the user explicitly asks for the task to persist」。⛔ 与后端两处缺省同源
 * （`ScheduleService.create` / `ScheduleController.create`），否则同一能力两套判据。
 *
 * @param activeSessionId 当前活动会话（两 scope 皆必填）
 * @throws Error 无活动会话（注定 400，前端先给可读提示）
 */
export function buildCreatePayload(
  req: CreateScheduleRequest,
  activeSessionId?: string | null,
): CreateScheduleRequest {
  if (!activeSessionId) {
    throw new Error('请先打开一个会话再创建定时任务：两种任务都需要归属一个会话')
  }
  return { ...req, scope: req.scope ?? 'SESSION', sessionId: activeSessionId }
}

/**
 * 定时任务 hook。
 *
 * @param activeSessionId 当前活动会话 id。⭐ **创建的必备前置（两 scope 皆然）**：
 *   - `DURABLE`：后端 REST 边界要求该 id 能解析出绑定项目根（boundProject），否则 400
 *     （`ScheduleController.create` 三段判据；任务的项目锚由后端按会话推导，客户端不得指定）；
 *   - `SESSION`：后端 service 层要求非空（`ScheduleService.create` `scope=SESSION requires
 *     non-empty 'sessionId'`），且会话必须存在 —— 会话不存活时任务既不 fire
 *     （`CronIdleExecutor` 要求 SESSION 命令会话存活）也不会被 closeSession 清理 ⇒ 成孤儿。
 *   ⇒ 无活动会话时 `createSchedule` **先在前端拦截**并给出可读提示，不发这一次注定 400 的请求。
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
    // 两 scope 的 sessionId 一律取当前活动会话（⛔ 不由调用方传，避免传错会话把任务锚到别的项目）；
    // DURABLE 下后端还会用 sessionId 解析结果**覆盖**请求体里的 boundProject ⇒ 客户端无锚可伪造。
    // 无活动会话 ⇒ buildCreatePayload 抛（UI 侧另由 canCreate 置灰按钮，这是防旁路的最后一道闸）。
    const created = await scheduleApi.create(buildCreatePayload(req, activeSessionId))
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
    canCreate: !!activeSessionId,
    createSchedule, updateSchedule, deleteSchedule, runNow,
  }
}
