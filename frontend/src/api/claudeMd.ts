/**
 * /claude-md 相关 REST 端点封装
 * 对应 nexusai-backend ClaudeMdController（GET /api/v1/claude-md/include-status · POST /api/v1/claude-md/include-approval）
 */
import { api } from './rest'

/** GET /claude-md/include-status 响应 · 前端判断「CLAUDE.md 外部 @import 是否待审批并弹窗」 */
export interface IncludeStatus {
  /** 是否需审批（存在外部 include 且未审批且未示警；后端 shouldShowClaudeMdExternalIncludesWarning） */
  needsApproval: boolean
  /** 外部 @import 文件绝对路径列表（后端 getExternalClaudeMdIncludes，不受审批门控） */
  files: string[]
}

/**
 * 查询 CLAUDE.md 外部 @import 审批状态（2026-08-24 后端已实现）。
 * 前端启动/加载上下文时调用；needsApproval=true → 弹 IncludeApprovalModal。
 */
export function getIncludeStatus(): Promise<IncludeStatus> {
  return api<IncludeStatus>('/claude-md/include-status')
}

/** POST /claude-md/include-approval 响应 · 审批态二值（对齐后端 externalIncludesApproved） */
export interface IncludeApprovalResponse {
  approved: boolean
}

/**
 * CLAUDE.md 外部 @import include 审批。
 * 契约：body { approved } → { approved }；approved 缺失 → 400；引擎未接线 → 500。
 * 后端审批态 externalIncludesApproved（默认 false）→ 批准后外部文件被加载。
 */
export function approveInclude(approved: boolean): Promise<IncludeApprovalResponse> {
  return api<IncludeApprovalResponse>('/claude-md/include-approval', {
    method: 'POST',
    body: { approved },
  })
}
