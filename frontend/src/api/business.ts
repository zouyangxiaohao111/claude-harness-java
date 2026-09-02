/**
 * Business REST 端点封装 · Branch / Export / Doctor
 * 对齐 nexusai-backend 的 BranchController / ExportController / DoctorController（Phase 5 · §4）
 */
import { api, ApiError, BASE_URL } from './rest'
import type {
  BranchActionResponse,
  BranchCreateResponse,
  BranchWorktree,
  DoctorReport,
  ExportCopyResponse,
  ExportShareResponse,
} from './types'

/**
 * 请求 JSON 且 HTTP 错误状态不抛异常。
 * 后端 Branch 写操作的错误体自带 status/error 字段（如 400 {status:'error', error:…}），
 * 由调用方按 status 裁决；仅网络层错误抛 ApiError。
 */
async function fetchJson<T>(
  path: string,
  opts: { method?: 'GET' | 'POST' | 'DELETE'; body?: unknown } = {},
): Promise<T> {
  const { method = 'GET', body } = opts
  const headers: Record<string, string> = { 'Accept': 'application/json', 'X-Client-Env': 'react' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'

  let res: Response
  try {
    res = await fetch(`${BASE_URL}${path}`, {
      method,
      headers,
      body: body !== undefined ? JSON.stringify(body) : undefined,
    })
  } catch (e) {
    throw new ApiError(
      `Network error: ${e instanceof Error ? e.message : String(e)}`,
      { status: 0, title: 'Network Error' }
    )
  }
  const raw = await res.text()
  if (!raw) return undefined as T
  return JSON.parse(raw) as T
}

export const branchApi = {
  /** 列出所有 worktree 分支（git worktree list --porcelain） */
  list: () => api<BranchWorktree[]>('/branches'),
  /** 创建分支（WorktreeService.createWorktree） */
  create: (slug: string) =>
    fetchJson<BranchCreateResponse>('/branches', { method: 'POST', body: { slug } }),
  /** 删除 worktree + 分支（WorktreeService.removeWorktree） */
  remove: (slug: string, discardChanges = false) =>
    fetchJson<BranchActionResponse>(
      `/branches/${encodeURIComponent(slug)}?discardChanges=${discardChanges}`,
      { method: 'DELETE' }
    ),
  /** 保留 worktree（WorktreeService.keepWorktree） */
  keep: (slug: string) =>
    fetchJson<BranchActionResponse>(`/branches/${encodeURIComponent(slug)}/keep`, { method: 'POST' }),
}

export const exportApi = {
  /** 下载会话 markdown（text/markdown → Blob 触发浏览器下载） */
  downloadMarkdown: async (sessionId: string): Promise<{ filename: string; content: string }> => {
    let res: Response
    try {
      res = await fetch(`${BASE_URL}/export/${encodeURIComponent(sessionId)}?format=md`, {
        headers: { 'Accept': 'text/markdown', 'X-Client-Env': 'react' },
      })
    } catch (e) {
      throw new ApiError(
        `Network error: ${e instanceof Error ? e.message : String(e)}`,
        { status: 0, title: 'Network Error' }
      )
    }
    if (!res.ok) {
      throw new ApiError(`导出失败 (${res.status})`, { status: res.status, title: 'Export Error' })
    }
    const content = await res.text()
    const cd = res.headers.get('Content-Disposition') ?? ''
    const m = /filename="([^"]+)"/.exec(cd)
    return { filename: m ? m[1] : `${sessionId}.md`, content }
  },
  /** 复制会话为 markdown（返回字符数与消息数） */
  copy: (sessionId: string) =>
    api<ExportCopyResponse>(`/export/${encodeURIComponent(sessionId)}/copy`, { method: 'POST' }),
  /** 创建分享链接 */
  share: (sessionId: string) =>
    api<ExportShareResponse>(`/export/${encodeURIComponent(sessionId)}/share`, { method: 'POST' }),
}

export const doctorApi = {
  /** 运行 doctor 诊断（git / 路径 / java / 内存） */
  diagnose: () => api<DoctorReport>('/doctor'),
}
