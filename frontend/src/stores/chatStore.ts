import { create } from 'zustand'
import type { ChatMessageDto, SessionDto, TokenWarningEvent, ToolCallDto, MessageUsageDto, ModelUsageEntry } from '../api/types'

/** 流式块：一个 assistant 轮次（key = 后端 chunk.assistantMessageId = turnAssistantId）。
 *  三内容字段皆可空——「纯思考轮」（仅 reasoning）、「思考+工具轮·无正文」（reasoning+toolCalls）、「正文轮」（仅 content）。
 *  契约 #1/#6：chunk 带真实轮 id；tool_call 按块 id 精确挂工具卡片（后端同源前匹配不到则不挂，
 *  complete 时 finalizeBlocks 将块转消息——id=turnAssistantId 与后端落库同源后即权威）。 */
export interface StreamBlock {
  assistantMessageId: string
  /** 所属 flow 的用户消息 id（chunk 事件透传 · 前端按此分组锚定消息链，工具轮挂主气泡下） */
  userMessageId?: string | null
  reasoning: string
  content: string
  /** 工具卡片（回放推 tool_call 挂入 · 契约 #6；arguments 已在前端 JSON.stringify 转字符串） */
  toolCalls: ToolCallDto[]
  /** message.usage 事件逐条挂载：该条 assistant 的 usage（含 decode_ms · 块内优先，complete 累计兜底） */
  usage?: MessageUsageDto | null
  /** message.usage 事件逐条挂载：上下文已用 tokens（块内优先） */
  contextTokensUsed?: number | null
  /** message.usage 事件逐条挂载：模型上下文窗口（tokens · 块内优先） */
  contextWindow?: number | null
  /** message.usage 事件逐条挂载：上下文剩余百分比（块内优先） */
  percentLeft?: number | null
}

/** 会话流式 API 错误（message.error 事件 → 对话流助手位置错误卡渲染 · 对齐 CC assistant API error 语义） */
export interface ApiFlowError {
  userMessageId?: string | null
  assistantMessageId?: string | null
  code?: string | null
  message: string
}

/** 会话运行状态（对齐后端 SessionStatusEvent.status：thinking/streaming/idle）。 */
export type AgentStatus = 'thinking' | 'streaming' | 'idle'

/** 权限请求（3 种 kind 归一化：message/bridge/channel）。 */
export interface PermissionRequestItem {
  kind: 'message' | 'bridge' | 'channel'
  /** 来源会话 id（STOMP topic 归属 · 决策 sendPermissionResponse 路由目标） */
  sessionId: string
  requestId: string
  toolName: string
  description?: string | null
  /** 决策归因（WF-11 新契约 reason.reason 单字段串；兼容旧 reason.type/detail） */
  reason?: { type?: string; detail?: string; reason?: string } | null
  /** 危险命令警告（红/黄警示文案；空则正常弹窗） */
  warning?: string | null
  /** 原始 toolInput（AskUser questions 承载于 toolInput.questions）；透传，前端不解析结构 */
  toolInput?: unknown
  /** 是否 leader 收到的 worker 权限请求（reason.reason === 'leader_inbox'）· C1 队友请求标识 */
  isLeaderInbox?: boolean
  /** worker 名（后端补 workerName 字段后透传；未补前从 description 提取，可能为空）· C1 */
  workerName?: string | null
  /** 服务端推送时间戳（毫秒 · 对齐 MessagePermissionRequestEvent.timestampMs · 等待时长基准） */
  timestampMs?: number
  /** worker 徽标颜色（leader inbox 请求 · 后端 #132 补 workerBadgeColor · 可 null 回落默认） */
  workerBadgeColor?: string | null
}

export interface ChatState {
  sessions: SessionDto[]
  messages: Record<string, ChatMessageDto[]>          // sessionId -> 历史消息
  /** 图片缓存：sessionId → id → {mediaType, base64}（重拉后按 imagePasteIds 批量拉图显示缩略图） */
  imageCache: Record<string, Record<string, { mediaType: string; base64: string }>>
  streams: Record<string, StreamBlock[]>              // sessionId -> 当前流式块列表（按 assistantMessageId 分轮）
  /** [snip-persist] 会话级被裁剪消息 id 集合（Snip 后前端标注「已裁剪」· 实时 STOMP + F5 boundary 解析合并） */
  snippedIds: Record<string, string[]>
  conversationIds: Record<string, string>             // sessionId -> partial 压缩后新 conversationId（消息 row key 刷新）
  permissionQueue: PermissionRequestItem[]
  connection: 'idle' | 'connecting' | 'connected' | 'disconnected'
  agentStatus: AgentStatus
  retry: { attempt?: number; maxRetries?: number; retryDelayMs?: number } | null
  /** 压缩警告抑制态（token_warning 事件 · 非 null 且 !suppressed 时显示横幅） */
  tokenWarning: TokenWarningEvent | null
  /** 压缩进度 UI 态（compact-progress 事件归一 · 驱动输入框上方 CompactProgressBar + Composer 发送键变停止） */
  compact: { visible: boolean; status: 'running' | 'done' | 'canceled'; hookType?: string; pct: number }
  /** 会话 API 错误（message.error → 对话流错误卡 · key=sessionId） */
  apiErrors: Record<string, ApiFlowError[]>
  // actions
  setSessions: (s: SessionDto[]) => void
  /** 会话 token/金额汇总实时更新（complete 事件 → 覆盖会话累计 · 底部 footer 展示） */
  updateSessionUsage: (sessionId: string, usage: { totalCostYuan?: number | null; totalTokens?: number | null }) => void
  setMessages: (sessionId: string, msgs: ChatMessageDto[]) => void
  /** [snip-persist] 合并被裁剪消息 id（STOMP message.boundary 实时 → 会话 snippedIds） */
  markSnipped: (sessionId: string, ids: string[]) => void
  /** 合并图片缓存（重拉后 batch 拉图结果写入 · 覆盖同 id，保留其余） */
  setImageCache: (sessionId: string, images: Record<string, { mediaType: string; base64: string }>) => void
  removeMessage: (sessionId: string, messageId: string) => void
  /** 删除会话：清空该会话的消息 + 流式 + conversationId */
  clearSession: (sessionId: string) => void
  clearStream: (sessionId: string) => void
  setConversationId: (sessionId: string, conversationId: string) => void
  setConnection: (c: ChatState['connection']) => void
  setAgentStatus: (s: AgentStatus) => void
  /** 按轮次 id 惰性建流式块（chunk 到达时确保存在；同 id 复用最后块） */
  ensureStreamBlock: (sessionId: string, assistantMessageId: string, userMessageId?: string | null) => void
  appendChunk: (sessionId: string, assistantMessageId: string, delta: string) => void
  appendReasoning: (sessionId: string, assistantMessageId: string, reasoning: string) => void
  /** 契约 #6：回放推 tool_call → 按块 id 精确挂工具卡片；后端同源前 id 匹配不到则忽略（等后端统一 turnAssistantId 后精确归属） */
  addToolCall: (sessionId: string, assistantMessageId: string, tool: ToolCallDto) => void
  /** 契约 #6：tool_result 按 toolCallId 匹配卡片填 result/isError（跨块遍历） */
  fillToolResult: (sessionId: string, toolCallId: string, result: string | null, isError: boolean | null) => void
  /** message.usage（消息级完成、非 turn 终态）：按 assistantMessageId 定位块挂 usage/上下文快照
   *  （lastIndexOf 取最新轮）；找不到 no-op（纯工具轮无块 → 由下一条 assistant / complete 兜底） */
  applyMessageUsage: (sessionId: string, assistantMessageId: string | null | undefined, meta?: {
    usage?: MessageUsageDto | null
    contextTokensUsed?: number | null
    contextWindow?: number | null
    percentLeft?: number | null
  }) => void
  /** complete 收口：流式块转 assistant 消息（id=turnAssistantId · 与后端同源后即 DB 权威 id），清空流式块 */
  /** 块级流式收口 → 块转消息；meta 可选（complete 事件透传 reasoningDurationMs + token usage/cost/上下文快照，无则 null） */
  finalizeBlocks: (sessionId: string, meta?: {
    reasoningDurationMs?: number | null
    usage?: MessageUsageDto | null
    totalCostUsd?: number | null
    modelUsage?: Record<string, ModelUsageEntry> | null
    contextTokensUsed?: number | null
    percentLeft?: number | null
    /** F4 t/s 速度：解码耗时（ms · complete 事件 usage.decode_ms）→ 块消息 decodeMs */
    decodeMs?: number | null
    /** 模型上下文窗口（tokens · complete 事件 contextWindow）→ 块消息 contextWindow */
    contextWindow?: number | null
    /** complete 事件 userMessageId（DB 权威）：透传给块消息，覆盖可能缺失/错误的 streaming 块归属 */
    userMessageId?: string | null
  }) => void
  enqueuePermission: (req: PermissionRequestItem) => void
  dequeuePermission: (requestId: string) => void
  expirePermission: (sessionId: string, requestId: string) => void
  setRetry: (r: { attempt?: number; maxRetries?: number; retryDelayMs?: number } | null) => void
  setTokenWarning: (w: TokenWarningEvent | null) => void
  /** 压缩进度 UI 态更新（compact-progress 事件归一写入；visible=false 隐藏横幅/恢复发送键） */
  setCompact: (c: { visible: boolean; status?: 'running' | 'done' | 'canceled'; hookType?: string; pct?: number }) => void
  /** message.error → 记录会话 API 错误（对话流错误卡） */
  addApiError: (sessionId: string, err: ApiFlowError) => void
  /** 清空会话 API 错误（新 user 消息发送时调用 · 错误卡属上一轮） */
  clearApiErrors: (sessionId: string) => void
  /** 后端推送的 user 消息（message.user）：isMeta=true/缺省 = cron/Ask 后台落库占位不显示（保 flow 顺序）；
   *  isMeta=false = 正式 user 气泡（含 busy-queued 排队插队注入）。按 id 幂等去重。 */
  appendMetaUser: (sessionId: string, id: string, content?: string | null, isMeta?: boolean) => void
}

const createChatStoreCreator = () => create<ChatState>()((set) => ({
  sessions: [],
  messages: {},
  imageCache: {},
  streams: {},
  snippedIds: {},          // [snip-persist] 会话级被裁剪消息 id（Snip 后「已裁剪」角标）
  conversationIds: {},
  permissionQueue: [],
  connection: 'idle',
  agentStatus: 'idle',
  retry: null,
  tokenWarning: null,
  compact: { visible: false, status: 'running', pct: 0 },
  apiErrors: {},
  setSessions: (sessions) => set({ sessions }),
  updateSessionUsage: (sessionId, usage) => set((st) => ({
    sessions: st.sessions.map((s) => {
      if (s.id !== sessionId) return s
      const next = { ...s }
      // 只合并非 null：失败轮 complete（0 cost / 空 modelUsage）不得用 0/undefined 覆盖已持久化的会话累计
      if (usage.totalCostYuan != null) next.totalCostYuan = usage.totalCostYuan
      if (usage.totalTokens != null) next.totalTokens = usage.totalTokens
      return next
    }),
  })),
  setMessages: (sessionId, msgs) => set((st) => {
    // [snip-persist] F5 兜底：从 GET /messages 返回的 boundary 消息（ChatMessageDto.snipMetadata.removedUuids）
    //   解析被裁剪消息 id → 合并进 snippedIds（与 STOMP message.boundary 实时同集合，Message 组件统一按 id 标注「已裁剪」）
    const boundaryIds: string[] = []
    for (const m of msgs ?? []) {
      if (m.subtype === 'snip_boundary' && m.snipMetadata?.removedUuids?.length) {
        boundaryIds.push(...m.snipMetadata.removedUuids)
      }
    }
    let snippedIds = st.snippedIds
    if (boundaryIds.length) {
      const merged = Array.from(new Set([...(st.snippedIds[sessionId] ?? []), ...boundaryIds]))
      snippedIds = { ...st.snippedIds, [sessionId]: merged }
    }
    return { messages: { ...st.messages, [sessionId]: msgs }, snippedIds }
  }),
  /** [snip-persist] STOMP message.boundary 实时合并被裁剪消息 id（会话级 snippedIds） */
  markSnipped: (sessionId, ids) => set((st) => {
    if (!ids?.length) return st
    const merged = Array.from(new Set([...(st.snippedIds[sessionId] ?? []), ...ids]))
    return { snippedIds: { ...st.snippedIds, [sessionId]: merged } }
  }),
  setImageCache: (sessionId, images) => set((st) => ({
    imageCache: { ...st.imageCache, [sessionId]: { ...(st.imageCache[sessionId] ?? {}), ...images } },
  })),
  removeMessage: (sessionId, messageId) => set((st) => ({
    messages: {
      ...st.messages,
      [sessionId]: (st.messages[sessionId] ?? []).filter((m) => m.id !== messageId),
    },
  })),
  clearSession: (sessionId) => set((st) => {
    const messages = { ...st.messages }
    const streams = { ...st.streams }
    const conversationIds = { ...st.conversationIds }
    const apiErrors = { ...st.apiErrors }
    delete messages[sessionId]
    delete streams[sessionId]
    delete conversationIds[sessionId]
    delete apiErrors[sessionId]
    return { messages, streams, conversationIds, apiErrors }
  }),
  setConnection: (connection) => set({ connection }),
  setAgentStatus: (agentStatus) => set({ agentStatus }),
  ensureStreamBlock: (sessionId, assistantMessageId, userMessageId) => set((st) => {
    const blocks = st.streams[sessionId] ?? []
    const last = blocks[blocks.length - 1]
    console.debug('[esb]', { blockId: assistantMessageId?.slice(0, 12), uid: userMessageId, existing: last?.assistantMessageId?.slice(0, 12), existingUid: last?.userMessageId })
    if (last && last.assistantMessageId === assistantMessageId) {
      // 【冻结归属】块归属在【建立时确定】——首 chunk 到达时的 userMessageId（对应后端 DB 落库
      //   逐条推进的「位置」语义）。后续 chunk 不覆盖：排队消息 append 后 chunk 可能带新 id，
      //   若覆盖会让用户1 任务中途的工具块被错标排队 id（实时 ≠ DB 顺序的根因）。
      //   仅当建立时 userMessageId 缺失（首 chunk 未带）且后续首次带非空才回填（一次性），之后冻结。
      if (!last.userMessageId && userMessageId) {
        const next = [...blocks]
        next[next.length - 1] = { ...last, userMessageId }
        return { streams: { ...st.streams, [sessionId]: next } }
      }
      return st
    }
    return { streams: { ...st.streams, [sessionId]: [...blocks, { assistantMessageId, userMessageId: userMessageId ?? null, reasoning: '', content: '', toolCalls: [] }] } }
  }),
  appendChunk: (sessionId, assistantMessageId, delta) => set((st) => {
    const blocks = st.streams[sessionId]
    if (!blocks) return st
    const idx = blocks.map((b) => b.assistantMessageId).lastIndexOf(assistantMessageId)
    if (idx < 0) return st
    const next = [...blocks]
    next[idx] = { ...next[idx], content: next[idx].content + delta }
    return { streams: { ...st.streams, [sessionId]: next } }
  }),
  appendReasoning: (sessionId, assistantMessageId, reasoning) => set((st) => {
    const blocks = st.streams[sessionId]
    if (!blocks) return st
    const idx = blocks.map((b) => b.assistantMessageId).lastIndexOf(assistantMessageId)
    if (idx < 0) return st
    const next = [...blocks]
    next[idx] = { ...next[idx], reasoning: next[idx].reasoning + reasoning }
    return { streams: { ...st.streams, [sessionId]: next } }
  }),
  addToolCall: (sessionId, assistantMessageId, tool) => set((st) => {
    const blocks = st.streams[sessionId]
    if (!blocks) return st
    // 精确匹配块 id（后端同源前 tool_call.assistantMessageId=落库 id ≠ 流式 turnAssistantId → 忽略，
    //   避免工具卡片挂错轮；同源改造后自动精确归属）
    const idx = blocks.map((b) => b.assistantMessageId).lastIndexOf(assistantMessageId)
    if (idx < 0) return st
    const next = [...blocks]
    next[idx] = { ...next[idx], toolCalls: [...next[idx].toolCalls, tool] }
    return { streams: { ...st.streams, [sessionId]: next } }
  }),
  fillToolResult: (sessionId, toolCallId, result, isError) => set((st) => {
    const blocks = st.streams[sessionId]
    if (!blocks) return st
    let changed = false
    const next = blocks.map((b) => {
      const ti = b.toolCalls.findIndex((t) => t.id === toolCallId)
      if (ti < 0) return b
      changed = true
      const tc = [...b.toolCalls]
      tc[ti] = { ...tc[ti], result: result ?? tc[ti].result, isError: isError ?? tc[ti].isError }
      return { ...b, toolCalls: tc }
    })
    return changed ? { streams: { ...st.streams, [sessionId]: next } } : st
  }),
  applyMessageUsage: (sessionId, assistantMessageId, meta = {}) => set((st) => {
    // message.usage（消息级完成）：按块 id 定位挂 usage/上下文快照。lastIndexOf 取最新轮；
    //   null id / 匹配不到（纯工具轮该条 assistant 无文本块，或 complete 已清流）→ no-op 不崩溃，
    //   由下一条 assistant message.usage / turn 末 complete meta 兜底。
    if (!assistantMessageId) return st
    const blocks = st.streams[sessionId]
    if (!blocks || blocks.length === 0) return st
    const idx = blocks.map((b) => b.assistantMessageId).lastIndexOf(assistantMessageId)
    if (idx < 0) return st
    const next = [...blocks]
    next[idx] = {
      ...next[idx],
      usage: meta.usage ?? next[idx].usage,
      contextTokensUsed: meta.contextTokensUsed ?? next[idx].contextTokensUsed,
      contextWindow: meta.contextWindow ?? next[idx].contextWindow,
      percentLeft: meta.percentLeft ?? next[idx].percentLeft,
    }
    return { streams: { ...st.streams, [sessionId]: next } }
  }),
  finalizeBlocks: (sessionId, meta = {}) => set((st) => {
    const blocks = st.streams[sessionId]
    if (!blocks || blocks.length === 0) return st
    const msgs = st.messages[sessionId] ?? []
    // 块 → assistant 消息：id = turnAssistantId（后端落库同源后即 DB 权威 id，前端免重拉）。
    //   时间戳/思考耗时补丁：块消息补 createdAt（回落 formatMsgTime → HH:MM）· reasoningDurationMs
    //   由 complete 事件透传（无重拉时消息才不缺这两项时间展示）。
    //   token usage/cost/上下文快照同样由 complete 事件透传（契约：真实 usage + 会话累计花费）。
    const now = new Date().toISOString()
    const blockMsgs: ChatMessageDto[] = blocks.map((b) => ({
      id: b.assistantMessageId, sessionId, role: 'assistant', author: 'nexus',
      content: b.content || null, reasoning: b.reasoning || null,
      toolCalls: b.toolCalls.length > 0 ? b.toolCalls : null,
      finishReason: null, inputTokens: null, outputTokens: null, reasoningDurationMs: meta.reasoningDurationMs ?? null,
      time: null, createdAt: now, toolCallId: null, assistantMessageId: b.assistantMessageId,
      userMessageId: b.userMessageId ?? null, subtype: null, isMeta: false, isApiErrorMessage: false,
      apiError: null, error: null, errorDetails: null, matchedRule: null,
      // 逐块优先：块内 usage/上下文快照（message.usage 实时挂载）→ 回落 complete meta
      //   （turn 累计 usage 只兜底纯工具轮/旧后端无 message.usage 的块）
      usage: b.usage ?? meta.usage ?? null, totalCostUsd: meta.totalCostUsd ?? null,
      modelUsage: meta.modelUsage ?? null,
      contextTokensUsed: b.contextTokensUsed ?? meta.contextTokensUsed ?? null,
      percentLeft: b.percentLeft ?? meta.percentLeft ?? null,
      decodeMs: b.usage?.decode_ms ?? meta.decodeMs ?? null,
      contextWindow: b.contextWindow ?? meta.contextWindow ?? null,
    }))
    // 按 flow 插回（根治「排队消息实时顺序错乱」）：不尾部 append —— 对每个块按其归属的
    //   userMessageId，插入 messages 中最后一条同 flow 消息之后。否则 streaming 里早于排队消息
    //   的 assistant 块（跨轮未落库）会在 complete 时被排到排队消息之后 → 实时 ≠ DB 顺序。
    const ordered = [...msgs]
    for (const bm of blockMsgs) {
      // [cron 去重] 幂等：同 assistantMessageId 已存在（resume 重拉 / 历史已含本块）→ 替换不重插，
      //   根治 cron idle resume + 流式块 + 重拉双通道导致的同一条 assistant 消息重复显示
      const dupIdx = ordered.findIndex((m) => m.id != null && bm.id != null && m.id === bm.id)
      if (dupIdx >= 0) {
        ordered[dupIdx] = bm
        continue
      }
      const flowKey = bm.userMessageId ?? bm.id
      // 找 messages 中最后一条同 flow 的消息（含刚插入的 blockMsgs —— 用累进 ordered 定位）
      let insertAt = ordered.length
      for (let i = ordered.length - 1; i >= 0; i--) {
        if ((ordered[i].userMessageId ?? ordered[i].id) === flowKey) { insertAt = i + 1; break }
      }
      ordered.splice(insertAt, 0, bm)
    }
    const { [sessionId]: _drop, ...rest } = st.streams
    return { streams: rest, messages: { ...st.messages, [sessionId]: ordered } }
  }),
  clearStream: (sessionId) => set((st) => {
    const { [sessionId]: _drop, ...rest } = st.streams
    return { streams: rest }
  }),
  setConversationId: (sessionId, conversationId) => set((st) => ({
    conversationIds: { ...st.conversationIds, [sessionId]: conversationId },
  })),
  enqueuePermission: (req) => set((st) => ({ permissionQueue: [...st.permissionQueue, req] })),
  dequeuePermission: (requestId) => set((st) => ({ permissionQueue: st.permissionQueue.filter(r => r.requestId !== requestId) })),
  expirePermission: (sessionId, requestId) => set((st) => {
    const req = st.permissionQueue.find(r => r.requestId === requestId)
    if (!req) return st
    const msgs = st.messages[sessionId] ?? []
    return {
      permissionQueue: st.permissionQueue.filter(r => r.requestId !== requestId),
      messages: { ...st.messages, [sessionId]: [...msgs, {
        id: `perm-${requestId}`, sessionId, role: 'system', author: 'system',
        content: `工具 ${req.toolName} 请求权限，已超时（自动拒绝）`,
        reasoning: null, toolCalls: null, finishReason: null, inputTokens: null, outputTokens: null,
        reasoningDurationMs: null, time: null, toolCallId: null, assistantMessageId: null, subtype: 'permission_timeout',
        isMeta: false, isApiErrorMessage: false, apiError: null, error: null, errorDetails: null, matchedRule: null,
      }] },
    }
  }),
  setRetry: (retry) => set({ retry }),
  setTokenWarning: (tokenWarning) => set({ tokenWarning }),
  setCompact: (c) => set((st) => ({
    compact: {
      visible: c.visible,
      status: c.status ?? st.compact.status,
      hookType: c.hookType ?? st.compact.hookType,
      pct: c.pct ?? st.compact.pct,
    },
  })),
  addApiError: (sessionId, err) => set((st) => ({
    apiErrors: { ...st.apiErrors, [sessionId]: [...(st.apiErrors[sessionId] ?? []), err] },
  })),
  clearApiErrors: (sessionId) => set((st) => {
    if (!st.apiErrors[sessionId]) return st
    const { [sessionId]: _drop, ...rest } = st.apiErrors
    return { apiErrors: rest }
  }),
  appendMetaUser: (sessionId, id, content, isMeta = true) => set((st) => {
    const msgs = st.messages[sessionId] ?? []
    // 幂等：同 id 已存在（重拉/重复推送）不重复插入
    if (msgs.some((m) => m.id === id)) return st
    const now = new Date().toISOString()
    const metaMsg: ChatMessageDto = {
      id, sessionId, role: 'user', author: 'user', content: content ?? null,
      reasoning: null, toolCalls: null, finishReason: null, inputTokens: null,
      outputTokens: null, reasoningDurationMs: null, time: null, createdAt: now,
      toolCallId: null, assistantMessageId: null, userMessageId: id, subtype: null,
      isMeta, isApiErrorMessage: false, apiError: null, error: null,
      errorDetails: null, matchedRule: null,
    }
    return { messages: { ...st.messages, [sessionId]: [...msgs, metaMsg] } }
  }),
}))

export const useChatStore = createChatStoreCreator()

/** 测试用：返回独立 store 实例（避免测试间状态泄漏）。生产用 useChatStore 单例。 */
export function createChatStore() { return createChatStoreCreator() }
