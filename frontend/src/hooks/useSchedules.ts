/**
 * useSchedules · 全局 Schedule 状态 + 真实后端 CRUD
 *
 * <p>所有变更走真实 API，成功后才更新本地 state；失败抛 ApiError 由调用方 toast。
 */

import { useCallback, useEffect, useRef, useState } from 'react'
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
   * 当前活动会话 id。
   *
   * <p>[V306] **仅作 A 形态（REUSE）未显式选会话时的回落** —— ⛔ 不再是「创建的前置」
   * （B/C/D 形态不需要会话 ⇒ 无活动会话也能建任务）。
   */
  activeSessionId: string | null

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
 * <p><b>[V306] 守的不变量①：只有 A 形态（REUSE）要 sessionId</b>。
 * B/C/D 三种形态自带会话语义（每次新建 / 建后绑定 / 压根不产生）⇒ **无活动会话也能建任务**
 * （设计稿 ⑩「⛔ 不再置灰添加自动化按钮」）。
 *
 * <p><b>守的不变量②：scope 恒为 `SESSION`</b>（对齐 CC 默认 + 后端两处缺省）。
 * ⛔ **不要**给 B/C/D 发 `scope=DURABLE`：后端 `ScheduleController.create` 对 DURABLE 强制要求
 * 「能解析出项目根的 sessionId」⇒ B/C/D 会被 400。走 SESSION 进 service，由
 * `ScheduleService.create` 把非 REUSE 形态**归一**为 DURABLE（那一刻才按形态决定项目锚：
 * B/C ⇒ cron 目录、D ⇒ 无锚）。
 *
 * <p>A 形态的 sessionId = <b>用户在下拉里选的那个会话</b>（可与活动会话不同 —— 设计稿 ③
 * 明确「绑到哪个会话」可选）⇒ ⛔ 不再像收口前那样用活动会话覆盖调用方传的值。
 *
 * @param activeSessionId 当前活动会话 · 仅作 A 形态**未显式选会话时**的回落
 * @throws Error A 形态但既没选会话也没有活动会话（后端 `scope=SESSION requires non-empty 'sessionId'` ⇒ 注定 400）
 */
export function buildCreatePayload(
  req: CreateScheduleRequest,
  activeSessionId?: string | null,
): CreateScheduleRequest {
  const mode = req.sessionMode ?? 'REUSE'
  const scope = req.scope ?? 'SESSION'
  if (mode !== 'REUSE') {
    // B/C/D：不带 sessionId（带了对后端也只是冗余；⛔ 更不能带活动会话 —— 那会让 B/C 形态
    // 看起来像「绑着某个会话」，与形态语义相反）
    const { sessionId: _unused, ...rest } = req
    return { ...rest, sessionMode: mode, scope }
  }
  const sessionId = req.sessionId ?? activeSessionId ?? undefined
  if (!sessionId) {
    throw new Error('「绑定已有会话」需要选一个会话 · 或改选其它形态（每次新建 / 新建后绑定 / 不绑定会话）')
  }
  return { ...req, sessionMode: mode, scope, sessionId }
}

/**
 * 定时任务 hook。
 *
 * @param activeSessionId 当前活动会话 id · **仅作 A 形态（REUSE）未显式选会话时的回落**。
 *   ⛔ [V306] 不再是「创建的前置」：B/C/D 三种形态不需要会话 ⇒ 无活动会话也能建任务
 *   （设计稿 ⑩：⛔ 不再置灰「添加自动化」按钮）。
 */
export function useSchedules(activeSessionId?: string | null): UseSchedules {
  const [list, setList] = useState<Schedule[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  /**
   * 是否成功加载过至少一次。
   *
   * <p>为什么需要它：本 hook 每 2s 轮询一次，若每次都 {@code setLoading(true)} 再 false，
   * 所有消费方（任务页顶部的「加载中…」行、右栏任务 tab）都会**每 2 秒闪一下**
   * （内容被替换成加载态再换回来，还带一次布局抖动）。
   * 轮询拿的是「同一份数据的新版本」⇒ 只有首次（还没有任何数据）才显示加载态，之后静默刷新。
   * <p>对齐 03af0129 在 IM 频道页 / 扫描面板的同一做法。
   */
  const loadedOnceRef = useRef(false)

  // ---- 拉取列表 ----
  const refresh = useCallback(async () => {
    if (!loadedOnceRef.current) {
      setLoading(true)
    }
    setError(null)
    try {
      // 按会话过滤（含全局 durable）· 用户 2026-10-21
      const data = await scheduleApi.list(activeSessionId ?? undefined)
      loadedOnceRef.current = true
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
  }, [activeSessionId])

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
    // [V306] sessionId 的注入按**形态**分：A(REUSE) 用调用方在下拉里选的会话（没选则回落活动会话）；
    //   B/C/D 不带 sessionId（形态自带会话语义）⇒ 无活动会话也能建任务。
    //   ⛔ scope 恒 SESSION：B/C/D 若发 DURABLE，后端 REST 边界会要求「能解析项目根的 sessionId」⇒ 400。
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
    activeSessionId: activeSessionId ?? null,
    createSchedule, updateSchedule, deleteSchedule, runNow,
  }
}
