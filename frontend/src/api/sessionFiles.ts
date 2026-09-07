import { api } from './rest'
import type { DiffFile, SessionFile } from '@/types'

/**
 * 会话改动文件 REST（Phase 2 · 后端 SessionFilesController）。
 * 数据源 = 后端 SessionFilesRecorder（agent Edit/Write 写盘捕获 · 与 files.changed 推送同源）。
 * list 返回已对齐前端 SessionFile 形状（name/path/adds/dels/isNew）。
 */

export interface RevertResponse { success: boolean }

export const sessionFilesApi = {
  /** 本会话改动文件列表（会话切换/初始对账 · 无改动 = []）。 */
  list: (sessionId: string) => api<SessionFile[]>(`/sessions/${encodeURIComponent(sessionId)}/files`),
  /** 真实 diff（DiffModal 直用 · 404 → 无记录）。 */
  diff: (sessionId: string, path: string) =>
    api<DiffFile>(`/sessions/${encodeURIComponent(sessionId)}/files/diff?path=${encodeURIComponent(path)}`),
  /** 回滚到本会话改动前（body {path}）。 */
  revert: (sessionId: string, path: string) =>
    api<RevertResponse>(`/sessions/${encodeURIComponent(sessionId)}/files/revert`, { method: 'POST', body: { path } }),
}
