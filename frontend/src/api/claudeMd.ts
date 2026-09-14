/**
 * /claude-md 相关 REST 端点封装
 * 对应 nexusai-backend ClaudeMdController（GET /api/v1/claude-md/include-status · POST /api/v1/claude-md/include-approval）
 */
import { api, ApiError } from './rest'

/** GET /claude-md/include-status 响应 · 前端判断「CLAUDE.md 外部 @import 是否待审批并弹窗」 */
export interface IncludeStatus {
  /** 是否需审批（存在外部 include 且未审批且未示警；后端 shouldShowClaudeMdExternalIncludesWarning） */
  needsApproval: boolean
  /** 外部 @import 文件绝对路径列表（后端 getExternalClaudeMdIncludes，不受审批门控） */
  files: string[]
}

/**
 * 查询 CLAUDE.md 外部 @import 审批状态（2026-08-24 后端已实现）。
 * 前端在**会话激活后**调用；needsApproval=true → 弹 IncludeApprovalModal。
 *
 * [T15-3] `sessionId` **必传**：后端审批态按**项目**分区（由 sessionId 解析项目根，
 * 对齐 CC project config claudemd.ts:796/:1420），缺参 / 解析不到项目根 → 400。
 * ⚠️ 会话未就绪（activeSessionId 为空）时**不要调用本函数**（后端口是 400 而非「跳过」）。
 */
export function getIncludeStatus(sessionId: string): Promise<IncludeStatus> {
  return api<IncludeStatus>(`/claude-md/include-status?sessionId=${encodeURIComponent(sessionId)}`)
}

/** POST /claude-md/include-approval 响应 · 审批态二值（对齐后端按项目键存储的审批态） */
export interface IncludeApprovalResponse {
  approved: boolean
}

/**
 * CLAUDE.md 外部 @import include 审批。
 * 契约：body { approved, sessionId } → { approved }；approved/sessionId 缺失或解析不到项目根 → 400；
 * 引擎未接线 → 500。后端审批态按**项目键**存储（默认 false）→ 批准后本项目外部文件被加载。
 */
export function approveInclude(sessionId: string, approved: boolean): Promise<IncludeApprovalResponse> {
  return api<IncludeApprovalResponse>('/claude-md/include-approval', {
    method: 'POST',
    body: { approved, sessionId },
  })
}

/**
 * [T15-3] `getIncludeStatus` 探测失败的**分流**：返回应打印的告警文案；`null` = 静默。
 *
 * <p>WHY 需要分流（⛔ 不一律静默）：本端点按用户裁定「每个会话一定有绑定目录，查不到就是严重 bug」
 * ⇒ 后端 400（缺 sessionId / 解析不到项目根）属 (a) 类「本该有却没有」，一律静默会让该缺陷
 * **结构性不可见**（既不弹窗、无 toast、也无日志）——正是 T15-3 要消灭的失败模式。
 * 而网络层错（后端未就绪）属 (b) 类「本就不需要」⇒ 静默，不阻塞启动。
 *
 * <p>判据来自 `api()` 的真实抛错形状（见 `rest.ts`）：网络层错被包成
 * `ApiError{status: 0, title: 'Network Error'}`（fetch catch 分支），HTTP 非 2xx 则由 `parseProblem`
 * 带**真实 `res.status`** 抛出 ⇒ 可据此可靠分流（status 未被抹掉）。
 *
 * @param e          catch 到的任意异常
 * @param sessionId  当前会话 id（仅用于日志定位）
 * @returns 告警文案；`null` = 应静默（网络层错）
 */
export function describeIncludeStatusProbeFailure(e: unknown, sessionId: string): string | null {
  if (e instanceof ApiError) {
    // status 0 = 网络层错（断网 / CORS / DNS / 后端未就绪）⇒ (b) 类 ⇒ 静默
    if (e.status === 0) return null
    return (
      `[claude-md] include-status 探测失败：sessionId=${sessionId} status=${e.status}` +
      (e.status === 400
        ? '（会话无绑定项目 ⇒ 外部 include 审批弹窗不可用；后端要求 sessionId 能解析出项目根）'
        : '（后端非 2xx ⇒ 审批弹窗本轮不可用）')
    )
  }
  return `[claude-md] include-status 探测抛出非 ApiError 异常：${String(e)}`
}
