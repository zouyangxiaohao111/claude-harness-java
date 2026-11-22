import { api, ApiError } from './rest'
import { isCommonResult } from './peerInboxOutcome'
import type { SessionDto, SessionCreateRequest, SessionUpdateRequest, SessionToolDto, SessionToolPatchRequest, SessionToolCategory, SessionToolCategoryToggleRequest, SessionUsageResponse, ComputerUseApproval, ForkSessionRequest, ForkSessionResponse, PlanExitRequest, PlanExitResponse, SessionModeDto, SessionModeToggleRequest } from './types'

/**
 * [V329] 拆统一返回体：成功 = `success === true && code === 0`。
 *
 * <p>与 `peerInbox.ts` 的 `unwrapData` 同一判据（用户裁定：前端只认 success&&code==0）。
 * 业务失败仍是 HTTP 200 + `success:false` + 数字码 ⇒ 抛 ApiError（status 200），
 * 调用方按普通错误展示 msg。
 */
function unwrapMode(raw: unknown): SessionModeDto {
  if (!isCommonResult(raw)) return raw as SessionModeDto
  const env = raw as { code?: unknown; msg?: unknown; data?: unknown; success?: unknown }
  const code = typeof env.code === 'number' ? env.code : null
  if (env.success !== true || (code !== null && code !== 0)) {
    const msg = typeof env.msg === 'string' && env.msg.trim() ? env.msg : '操作失败，请重试'
    throw new ApiError(msg, { status: 200, title: 'Business Error', detail: msg })
  }
  return env.data as SessionModeDto
}

export const sessionApi = {
  list: () => api<SessionDto[]>('/sessions'),
  get: (id: string) => api<SessionDto>(`/sessions/${encodeURIComponent(id)}`),
  create: (req: SessionCreateRequest) => api<SessionDto>('/sessions', { method: 'POST', body: req }),
  /** 分叉：从某轮末消息截断复制出一个新会话（标题追加「 分叉」，产物/附件按新会话 id 复制） */
  fork: (id: string, throughMessageId: string) =>
    api<ForkSessionResponse>(`/sessions/${encodeURIComponent(id)}/fork`, {
      method: 'POST',
      body: { throughMessageId } satisfies ForkSessionRequest,
    }),
  update: (id: string, req: SessionUpdateRequest) =>
    api<SessionDto>(`/sessions/${encodeURIComponent(id)}`, { method: 'PATCH', body: req }),
  /** [plan-approval] 计划审批「退出并执行」：恢复会话权限模式；clearContext=true 时新建干净会话。 */
  exitPlan: (id: string, req: PlanExitRequest) =>
    api<PlanExitResponse>(`/sessions/${encodeURIComponent(id)}/plan/exit`, {
      method: 'POST',
      body: req,
    }),
  remove: (id: string) => api<void>(`/sessions/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  /** 会话工具列表（GET /sessions/{id}/tools · 被禁工具仍在列表，disabled=true 供恢复） */
  listTools: (id: string) => api<SessionToolDto[]>(`/sessions/${encodeURIComponent(id)}/tools`),
  /** 会话工具禁用/恢复（PATCH /sessions/{id}/tools/{toolName} · body { enabled }，非 toggle） */
  setToolEnabled: (id: string, toolName: string, enabled: boolean) =>
    api<SessionToolDto>(`/sessions/${encodeURIComponent(id)}/tools/${encodeURIComponent(toolName)}`, {
      method: 'PATCH',
      body: { enabled } satisfies SessionToolPatchRequest,
    }),
  /**
   * 按分类批量启用/禁用（PATCH /sessions/{id}/tools · body {category, enabled, mcpServer?}
   * · 返回整表，前端整表替换）。
   *
   * <p>`mcpServer` 是三级联动的中间一级：传 server 名时只开关该 MCP server 的工具
   * （`{category:'MCP', mcpServer:'rea'}`）；不传时行为与旧版一致（整个分类）。
   */
  setCategoryEnabled: (id: string, category: SessionToolCategory, enabled: boolean, mcpServer?: string) =>
    api<SessionToolDto[]>(`/sessions/${encodeURIComponent(id)}/tools`, {
      method: 'PATCH',
      body: (mcpServer
        ? { category, enabled, mcpServer }
        : { category, enabled }) satisfies SessionToolCategoryToggleRequest,
    }),
  /**
   * [usage-gateway] 会话级用量明细（GET /sessions/{id}/usage · 记账侧权威值）。
   * 供「用量与花费」弹窗「子代理（本会话）」行按 taskId 关联；老后端无该端点 → 抛错，
   * 调用方静默回退 task 事件统计（不得因此阻塞弹窗）。
   */
  usage: (id: string) => api<SessionUsageResponse>(`/sessions/${encodeURIComponent(id)}/usage`),
  /**
   * 会话运行时状态（GET /sessions/{id}/state）· 对齐 dsh「宿主在每次打开/切换会话时**重放**
   * 未回答的 pending question/approval」（stable rpcId replay）。
   *
   * <p>pendingPermissions 与 STOMP permission-requests 事件同形，前端可直接入队。
   * 切会话/重连时调用 —— 补齐「请求到达时该会话未订阅」而丢失的交互。
   */
  state: (id: string) =>
    api<{ sessionId: string; pendingPermissions: PermissionEventPayload[] | null }>(
      `/sessions/${encodeURIComponent(id)}/state`,
    ),

  /** [V329] 会话「无人值守 / Brief」状态（GET /sessions/{id}/mode · CommonResult 拆包） */
  getMode: async (id: string): Promise<SessionModeDto> =>
    unwrapMode(await api<unknown>(`/sessions/${encodeURIComponent(id)}/mode`)),

  /**
   * [V329] 开/关无人值守（PUT /sessions/{id}/mode/unattended）。
   *
   * <p>开启硬前置 = 渠道实例 ≥ 1；无渠道 → 业务失败（ApiError，msg「请先配置渠道」），
   * 前端必须提示并打开渠道弹窗，⛔ 不静默。
   */
  setUnattended: async (id: string, enabled: boolean): Promise<SessionModeDto> =>
    unwrapMode(await api<unknown>(`/sessions/${encodeURIComponent(id)}/mode/unattended`, {
      method: 'PUT',
      body: { enabled } satisfies SessionModeToggleRequest,
    })),

  /**
   * [V329][Q8] 开/关 Brief（PUT /sessions/{id}/mode/brief）。
   *
   * <p>与 SendUserMessage 工具开关、`/brief` 同一份会话状态。无人值守开启时关闭：
   * 不带 `cascadeExitUnattended` ⇒ 后端以 `code:409` 拒绝（前端据此弹确认框，状态不变）；
   * 带 `true` ⇒ 一次请求原子写入 brief=false + unattended=false。
   */
  setBrief: async (id: string, enabled: boolean, cascadeExitUnattended?: boolean): Promise<SessionModeDto> =>
    unwrapMode(await api<unknown>(`/sessions/${encodeURIComponent(id)}/mode/brief`, {
      method: 'PUT',
      body: { enabled, cascadeExitUnattended } satisfies SessionModeToggleRequest,
    })),
}

/**
 * 会话状态里的待处理权限/提问（与 STOMP `/permission-requests` 事件同形）。
 *
 * <p>⭐ 本类型同时是**三条权限通道**（message / bridge / channel）的入站线格式 ——
 * 三者共用同一个归一化函数（`toPermissionItem`，front/src/hooks/useChatSocket.ts），
 * 只在「工具入参」的**字段名**上不同（见下方 `toolInput` / `displayInput` / `inputPreview`）。
 */
export interface PermissionEventPayload {
  requestId: string
  /** 工具调用 id（requestId 通常相同）· 回答后给对应工具卡打「已回答」标记 */
  toolUseId?: string | null
  toolName?: string
  /** 工具入参（message 通道字段名） */
  toolInput?: unknown
  description?: string | null
  reason?: { type?: string; detail?: string; reason?: string } | null
  warning?: string | null
  suggestions?: unknown[] | null
  timestampMs?: number
  workerName?: string | null
  workerBadgeColor?: string | null
  /** 工具入参（bridge 通道字段名 —— 后端 BridgePermissionRequestEvent.displayInput） */
  displayInput?: unknown
  /** 工具入参预览（channel 通道字段名 —— 后端 ChannelPermissionRequestEvent.inputPreview，截断串） */
  inputPreview?: unknown
  /**
   * 桌面控制授权载荷（后端算好的应用清单 + 授权范围文案 + 被请求的额外能力）。
   * null/缺省 ⇒ 不是桌面控制请求。⭐ **三条通道 + 补拉路径都必须带上本字段** ——
   * 漏了它，弹窗会退回 fail-loud 错误面板（安全但体验差）或普通 allow/deny（授权范围消失）。
   */
  computerUse?: ComputerUseApproval | null
  /**
   * [V333 AFK] 本次提问卡片是否启用倒计时 + 总时长（毫秒）。
   * null/缺省 ⇒ 不启用（默认：卡片持久显示、不计时）。
   */
  afkTimeoutMs?: number | null
  /** [V333 AFK] 倒计时显示阈值（毫秒）：剩余时间 ≤ 该值才显示剩余秒数（对齐 CC showCountdown）。 */
  afkCountdownMs?: number | null
}
