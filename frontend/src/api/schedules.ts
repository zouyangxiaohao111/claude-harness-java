/**
 * Schedule REST 端点封装
 * 对应 nexusai-backend Phase C4 端点
 */
import { api } from './rest'
import type {
  Schedule,
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
}
