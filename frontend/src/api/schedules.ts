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
  list: () => api<Schedule[]>('/schedules'),
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
