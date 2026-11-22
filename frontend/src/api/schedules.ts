/**
 * Schedule REST 端点封装
 * 对应 nexusai-backend Phase C4 端点
 */
import { api } from './rest'
import type {
  Schedule,
  ScheduleRun,
  CreateScheduleRequest,
  UpdateScheduleRequest,
  RunNowResponse,
} from './types'

export const scheduleApi = {
  /** 列表 · 传 sessionId 时按会话过滤（含全局 durable 任务） */
  list: (sessionId?: string) =>
    api<Schedule[]>(sessionId ? `/schedules?sessionId=${encodeURIComponent(sessionId)}` : '/schedules'),
  create: (req: CreateScheduleRequest) =>
    api<Schedule>('/schedules', { method: 'POST', body: req }),
  update: (id: string, req: UpdateScheduleRequest) =>
    // 后端为 POST 部分更新（非 PUT/PATCH），见 ScheduleController#update
    api<Schedule>(`/schedules/${encodeURIComponent(id)}`, { method: 'POST', body: req }),
  remove: (id: string) =>
    api<void>(`/schedules/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  runNow: (id: string) =>
    api<RunNowResponse>(`/schedules/${encodeURIComponent(id)}/run`, { method: 'POST' }),
  /**
   * 某任务的运行记录（时间倒序 · 默认最近 50 条）。
   *
   * <p>与 `schedules.lastRunStatus` 不同：后者在**入队那一刻**就写了（假信号），
   * 本端点记录的是 run **真正结束**后的结果（ok / error / cancelled / running）。
   */
  runs: (id: string, limit = 50) =>
    api<ScheduleRun[]>(`/schedules/${encodeURIComponent(id)}/runs?limit=${limit}`),
  /** 全部任务的运行记录（「运行记录」tab 首屏 · 时间倒序 · 默认最近 100 条） */
  allRuns: (limit = 100) =>
    api<ScheduleRun[]>(`/schedules/runs/all?limit=${limit}`),
}
