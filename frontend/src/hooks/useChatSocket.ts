import { useEffect, useRef } from 'react'
import { Client, type StompSubscription } from '@stomp/stompjs'
import { useChatStore } from '../stores/chatStore'
import { createSocketClient, subscribeStream, isChunk, isPushedUser, isComplete, isToolCall, isToolResult, isBoundary, isRetry, isError, isCancelled, isStatus, isTokenWarning } from '../api/socket'
import { useSubagentStore } from '../stores/subagentStore'
import { useSkillSurveyStore } from '../components/center/SkillSurvey'
import { TASKS_TOPIC } from '../api/types'
import type { StreamEvent, TaskEvent, SessionStatusEvent, SessionTitleEvent, TeamStatusEvent, TeammateMessageEvent, TodoItem } from '../api/types'
import { useTeamStore } from '../stores/teamStore'
import { useTodoStore } from '../stores/todoStore'
import { teamsApi } from '../api/teams'

/** STOMP skill_improvement.suggestion 事件（对齐 SkillImprovementSuggestionEvent · 轻量信号） */
interface SkillImprovementSuggestionWire {
  type: string
  sessionId?: string | null
  skillName?: string | null
  updateCount?: number | null
}

/**
 * 从 leader inbox 权限请求的 description 中提取 worker 名。
 * 后端事件暂无独立 workerName 字段（LeaderPermissionConfirmBridge 仅透传 description），
 * 这里做启发式提取：匹配 "@worker" 或 "worker「名」" 或 "来自 worker" 前缀；提取不出返回 null。
 */
function extractWorkerName(description: string | null | undefined): string | null {
  if (!description) return null
  const at = /@([A-Za-z0-9_.-]+)/.exec(description)
  if (at) return at[1]
  const quoted = /[「"']([^「"']{1,24})[」"']/.exec(description)
  if (quoted) return quoted[1]
  const from = /来自\s*([A-Za-z0-9_.-]+)/.exec(description)
  if (from) return from[1]
  return null
}

/**
 * 订阅当前团队 topics（方案3：按 lead 会话推送 → /topic/sessions/{leadSessionId}/team-status + /team-messages）。
 * status：created → 刷新详情；deleted → 清空面板；messages → 追加气泡 + 未读计数。
 * 返回订阅句柄，供调用方按需解绑（leadSessionId 变化 / 会话切换 / 断线重连由 onConnect 兜底）。
 */
function subscribeTeamTopics(client: Client, leadSessionId: string) {
  const status = client.subscribe(`/topic/sessions/${leadSessionId}/team-status`, (msg) => {
    try {
      const evt = JSON.parse(msg.body) as TeamStatusEvent
      const st = useTeamStore.getState()
      if (evt.eventType === 'deleted') {
        st.clear()
      } else if (evt.eventType === 'created' || evt.eventType === 'member_joined' || evt.eventType === 'member_left') {
        // created（团队创建）/ member_joined / member_left（成员增删）→ 以响应为准刷新详情 + 保留订阅键
        st.setTeamName(evt.teamName, leadSessionId)
        teamsApi.get(evt.teamName).then(st.setTeam).catch(() => {})
      }
    } catch { /* 非法载荷忽略 */ }
  })
  const messages = client.subscribe(`/topic/sessions/${leadSessionId}/team-messages`, (msg) => {
    try {
      const evt = JSON.parse(msg.body) as TeammateMessageEvent
      useTeamStore.getState().addMessage(evt)
    } catch { /* 非法载荷忽略 */ }
  })
  return { status, messages }
}

/** bridge dismiss 订阅登记：sid -> sub（本地 message 竞速胜出 → 后端推 dismiss → dequeue 残留 bridge 请求）。
 *  模块级（非 hook ref）：subscribePermTopics 是模块级函数；生命周期随 perm 订阅（hook 内增删清理）。 */
const bridgeDismissSubs = new Map<string, StompSubscription>()

/** 订阅单会话 3 种权限 topics（message/bridge/channel）→ 入队。
 *  多会话各订阅各的；弹窗由 App 过滤当前会话（非本会话不默认弹，侧栏黄点提示）。 */
function subscribePermTopics(client: Client, sid: string): { message: StompSubscription; bridge: StompSubscription; channel: StompSubscription } {
  const topics = [
    { kind: 'message', topic: `/topic/sessions/${sid}/permission-requests` },
    { kind: 'bridge', topic: `/topic/sessions/${sid}/permission-bridge-requests` },
    { kind: 'channel', topic: `/topic/sessions/${sid}/permission-channel-requests` },
  ] as const
  const subs = {} as { message: StompSubscription; bridge: StompSubscription; channel: StompSubscription }
  topics.forEach(({ kind, topic }) => {
    subs[kind] = client.subscribe(topic, (msg) => {
      const raw = JSON.parse(msg.body)
      const st = useChatStore.getState()
      // TEMP 诊断（联调 §38）：确认权限事件到达 + 订阅键（sessionId 匹配检查）
      console.debug(`[perm] ${kind} event on ${topic}`, { requestId: raw.requestId, toolName: raw.toolName, sessionId: sid })
      // C1 · leader inbox 请求：reason.reason==='leader_inbox'（后端 LeaderPermissionConfirmBridge 推送）；
      //   worker 名优先取后端补的 workerName，未补前从 description 提取（不保证存在）
      const isLeaderInbox = raw.reason?.reason === 'leader_inbox'
      const workerName = isLeaderInbox ? (raw.workerName ?? extractWorkerName(raw.description)) : null
      // bridge 事件字段 = displayInput（后端 BridgePermissionRequestEvent），非 toolInput —— 不修则
      //   AskUserForm extractQuestions 拿不到 questions → 回退「需要权限」通用弹窗（双弹窗根因之一）
      const toolInput = kind === 'bridge' ? (raw.displayInput ?? raw.toolInput) : raw.toolInput
      st.enqueuePermission({ kind, sessionId: sid, requestId: raw.requestId, toolName: raw.toolName, description: raw.description, reason: raw.reason, warning: raw.warning, toolInput, isLeaderInbox, workerName, workerBadgeColor: raw.workerBadgeColor ?? null, timestampMs: raw.timestampMs })
      console.debug('[perm] queued, queueLen=', st.permissionQueue.length, raw.requestId)
      // 后端无限等待用户响应（2026-08-24 移除 30s 自动超时；用户取消会话 → user_abort）
    })
  })
  // 本地 message 竞速胜出 → 后端 WebSocketPermissionPrompter.onResponse → bridgeCallbacks.sendResponse
  //   推 dismiss → 移除对应 bridge 请求（防同一 tool_use 的 bridge 事件残留 → 第二弹窗）
  if (!bridgeDismissSubs.has(sid)) {
    const dismiss = client.subscribe(`/topic/sessions/${sid}/permission-bridge-dismiss`, (msg) => {
      try {
        const payload = JSON.parse(msg.body) as { requestId?: string }
        if (payload.requestId) useChatStore.getState().dequeuePermission(payload.requestId)
      } catch { /* 非法载荷忽略 */ }
    })
    bridgeDismissSubs.set(sid, dismiss)
  }
  return subs
}

/**
 * 建立 STOMP 连接，订阅当前会话的 streamTopic + 权限 topic。
 * 收到事件后分发到 chatStore（chunk 累积 / complete 落库 / 权限入队 / 状态更新）。
 */
export function useChatSocket(
  sessionId: string | null,
  /** 所有活跃流式会话 → streamTopic（sendMessage 登记 / complete·cancel 明确移除 · 多会话并行订阅：
   *   切走会话不取消订阅，原会话 chunk/complete 持续接收直到结束，状态准确） */
  activeStreams: Record<string, string>,
  showToast?: (msg: string, type?: 'success' | 'info') => void,
  /** 会话生命周期明确信号：complete / cancelled 到达 → 回调 App 移除 activeStreams。
   *   对齐 Harness 事件驱动确定性——不用「streams 从有到无」推断移除（推断与登记竞争 = 卡住根因）。
   *   topic 参数：事件来源的 streamTopic，App 侧校验当前登记匹配才删（防旧 turn 终止误删新 turn 登记） */
  onSessionDone?: (sessionId: string, topic?: string) => void,
  /** 排队命令被后端消费（queue.drained）→ 回调 App：append 正式气泡 + 登记新 streamTopic。
   *   drained[].uuid = 后端 user 消息 id（DB 权威），前端气泡 id 用它 → GET 刷新不重复 */
  onQueueDrained?: (sessionId: string, drained: { uuid?: string; content: string }[]) => void,
  /** 排队命令快照变化（queue.changed）→ 回调 App：刷新排队框（useCommandQueue.setQueued） */
  onQueueChanged?: (sessionId: string, commands: { content: string; mode: string; isEditable: boolean; isMeta?: boolean }[]) => void,
) {
  const clientRef = useRef<Client | null>(null)
  const setConnection = useChatStore((s) => s.setConnection)
  /** 当前会话团队 leadSessionId（STOMP 订阅键 · TeamPanel 从 teamContext 写入） */
  const leadSessionId = useTeamStore((s) => s.leadSessionId)
  // 多会话订阅：sessionId -> stream sub / permission subs（activeStreams 增删时动态增删）
  const activeStreamsRef = useRef(activeStreams)
  activeStreamsRef.current = activeStreams
  // onSessionDone 经 ref 转发（effect/闭包不捕获过期回调）
  const onSessionDoneRef = useRef(onSessionDone)
  onSessionDoneRef.current = onSessionDone
  // onQueueDrained 经 ref 转发
  const onQueueDrainedRef = useRef(onQueueDrained)
  onQueueDrainedRef.current = onQueueDrained
  // onQueueChanged 经 ref 转发
  const onQueueChangedRef = useRef(onQueueChanged)
  onQueueChangedRef.current = onQueueChanged
  /** 流式订阅登记：sid -> sub（会话级单 topic /topic/sessions/{sid}/stream 常驻 · 不随消息退订） */
  const streamSubsRef = useRef<Map<string, StompSubscription>>(new Map())
  const permSubsRef = useRef<Map<string, { message: StompSubscription; bridge: StompSubscription; channel: StompSubscription }>>(new Map())
  /** 当前会话常驻 team topics 订阅（创建团队后立即收 created） */
  const teamSubsRef = useRef<{ status: StompSubscription; messages: StompSubscription } | null>(null)
  /** 团队实际会话（leadSessionId ≠ 当前会话时）的 team topics 订阅（动态重订） */
  const teamLeadSubsRef = useRef<{ status: StompSubscription; messages: StompSubscription } | null>(null)
  /** 常驻订阅会话 status topic（/topic/sessions/{sid}/status）· 收 session.title（title 生成在 complete 后、
   *   stream topic 已退订会丢 → 治「新建会话 title 不显示」）+ session.status。不随 activeStreams 取消。 */
  const statusSubsRef = useRef<Map<string, StompSubscription>>(new Map())
  /** 会话级常驻订阅登记：sid -> { token, queue }（token-warning / queue 按 sid 常驻 ——
   *   切走后原会话排队消费（queue.drained）/ 压缩警告仍需收；不随 sessionId 重订，同 stream/perm 模式）。 */
  const sessionLevelSubsRef = useRef<Map<string, { token: StompSubscription; queue: StompSubscription }>>(new Map())
  /** 当前会话 scoped 订阅句柄（team/todo/skill —— 随 sessionId 切换重订；
   *   stream/perm/status/token-warning/queue 按 sid 常驻不在此列）。sessionId 变化时先退订再订阅，client 单例不重建。 */
  const sessionScopedSubsRef = useRef<StompSubscription[]>([])
  /** 最新 sessionId（client 单例 onConnect 需读最新值订阅 scoped topics） */
  const sessionIdRef = useRef(sessionId)
  sessionIdRef.current = sessionId

  // 订阅当前会话 scoped topics（team/todo/skill · 当前会话 UI 状态）· 返回句柄数组供退订
  function subscribeCurrentSessionScoped(client: Client, sid: string): StompSubscription[] {
    const subs: StompSubscription[] = []
    // Team 协作 topics（方案3：按 lead 会话订阅）。当前 sid 常驻订阅——创建团队的会话 = 当前会话，
    //   团队创建瞬间 leadSessionId 尚未写入 store，若只靠 leadSessionId 订阅会漏 created 事件。
    //   lead 会话的 team 订阅由独立 leadSessionId effect 负责（不在此，避免双订）。
    const activeTeamSub = subscribeTeamTopics(client, sid)
    subs.push(activeTeamSub.status, activeTeamSub.messages)
    teamSubsRef.current = { status: activeTeamSub.status, messages: activeTeamSub.messages }
    // Todo 清单（整体替换语义）
    subs.push(client.subscribe(`/topic/sessions/${sid}/todos`, (msg) => {
      let raw: Record<string, unknown>
      try { raw = JSON.parse(msg.body) } catch { return }
      if (raw.type !== 'todo.update') return
      const todoKey = typeof raw.todoKey === 'string' ? raw.todoKey : sid
      const list = Array.isArray(raw.todos) ? (raw.todos as TodoItem[]) : []
      useTodoStore.getState().setTodos(todoKey, list)
    }))
    // 会话级事件 / skill_improvement.suggestion
    subs.push(client.subscribe(`/topic/sessions/${sid}`, (msg) => {
      const raw = JSON.parse(msg.body) as SkillImprovementSuggestionWire
      if (raw.type === 'skill_improvement.suggestion') {
        useSkillSurveyStore.getState().show({
          skillName: raw.skillName ?? '', updateCount: raw.updateCount ?? 0,
          sessionId: raw.sessionId ?? sid,
        })
      }
    }))
    return subs
  }

  // 退订当前会话 scoped topics（sessionId 切换时 · lead 会话 team 由 leadSessionId effect 管理）
  function unsubscribeCurrentSessionScoped() {
    for (const sub of sessionScopedSubsRef.current) sub.unsubscribe()
    sessionScopedSubsRef.current = []
    teamSubsRef.current = null
  }

  /** 订阅会话级常驻 topics（token-warning / queue · 按 sid 常驻，切走不退订）：
   *   queue.drained（排队消息消费）与 token-warning（压缩警告）都是会话级事件，
   *   原会话切走后仍需收到 → 按 sid 登记，不随 sessionId 重订（对齐 stream/perm/status 模式）。 */
  function subscribeSessionLevel(client: Client, sid: string) {
    if (sessionLevelSubsRef.current.has(sid)) return
    const token = client.subscribe(`/topic/sessions/${sid}/token-warning`, (msg) => {
      let raw: Record<string, unknown>
      try { raw = JSON.parse(msg.body) } catch { return }
      const st = useChatStore.getState()
      if (raw.suppressed) {
        st.setTokenWarning(null)
      } else {
        st.setTokenWarning({
          type: 'token_warning', sessionId: sid, suppressed: false,
          tokenUsage: raw.tokenUsage as number | undefined,
          contextWindow: raw.contextWindow as number | undefined,
          percentLeft: raw.percentLeft as number | undefined,
        })
      }
    })
    const queue = client.subscribe(`/topic/sessions/${sid}/queue`, (msg) => {
      let raw: Record<string, unknown>
      try { raw = JSON.parse(msg.body) } catch { return }
      const toQueued = (c: Record<string, unknown>) => ({
        content: String(c.content ?? ''), mode: String(c.mode ?? 'prompt'),
        isEditable: String(c.mode ?? 'prompt') === 'prompt' && !c.isMeta, isMeta: !!c.isMeta,
      })
      if (raw.type === 'queue.changed') {
        const list = Array.isArray(raw.commands) ? (raw.commands as Record<string, unknown>[]) : []
        onQueueChangedRef.current?.(sid, list.map(toQueued))
      } else if (raw.type === 'queue.drained') {
        const drained = Array.isArray(raw.drained) ? (raw.drained as Record<string, unknown>[]) : []
        const remaining = Array.isArray(raw.commands) ? (raw.commands as Record<string, unknown>[]) : []
        // 排队条刷新（remaining 移除已消费）+ 气泡 append 回调（App 立即 append 用户2 气泡，
        //   渲染按 userMessageId 分组自动排到当前 assistant 工具轮之后 —— 工具边界插入，不延后到 complete）
        onQueueChangedRef.current?.(sid, remaining.map(toQueued))
        onQueueDrainedRef.current?.(sid, drained.map((d) => ({
          uuid: d.uuid != null ? String(d.uuid) : undefined, content: String(d.content ?? ''),
        })))
      }
    })
    sessionLevelSubsRef.current.set(sid, { token, queue })
  }

  /**
   * 为会话订阅 stream topic（幂等 · 已订阅则跳过）。
   *
   * <p>cron/Ask 实时流修复：stream topic 改为「当前会话常驻订阅」。根因 —— 后端 cron 定时任务
   * 触发 / AskUserQuestion 应答后由 CronIdleExecutor 主动启动 agent_loop 推流到
   * /topic/sessions/{sid}/stream，但原 {@link syncAllStreams} 只在 activeStreams 有该会话登记时
   * 才订阅、无活跃流即退订 → 前端未订阅 → 收不到 chunk → 打字机不显示，F5 重拉才有。
   * 当前会话常驻订阅后：后端主动推流（cron/Ask 续跑）前端实时收到。complete 到达时
   * onSessionDone 幂等移除 activeStreams（不在则 no-op），常驻订阅保留供下次主动推流。
   */
  function ensureSessionStream(client: Client, sid: string) {
    if (!sid || streamSubsRef.current.has(sid)) return
    const topic = `/topic/sessions/${sid}/stream`
    console.debug('[sub] stream subscribe（当前会话常驻）', { sid, topic })
    streamSubsRef.current.set(sid, subscribeStream(client, topic, (evt) => dispatchEvent(evt, topic)))
  }

  // ---- STOMP client 单例（mount 一次创建，sessionId 变化不重建 → 原会话订阅常驻不丢）----
  useEffect(() => {
    // STOMP 断线/关闭时清空订阅登记（断线后所有 STOMP subscription 随连接失效；map 残留会让
    //   onConnect 重连时的 ensureSessionStream/subscribeStatusTopic/subscribeSessionLevel 等幂等
    //   判断误判「已订阅」→ 永不重订 → 该会话 chunk/complete 事件永久丢失（打字机卡死 / resume
    //   会话无打字机 / 无法停止）。@stomp/stompjs 断线只重连连接、不自动重订阅，必须由 onConnect 重建。）
    const clearSubscriptions = () => {
      sessionScopedSubsRef.current = []
      sessionLevelSubsRef.current.clear()
      streamSubsRef.current.clear()
      permSubsRef.current.clear()
      bridgeDismissSubs.clear()
      teamSubsRef.current = null
      teamLeadSubsRef.current = null
      statusSubsRef.current.clear()
    }
    const client = createSocketClient()
    client.onDisconnect = () => { setConnection('disconnected'); clearSubscriptions() }
    client.onWebSocketClose = () => { setConnection('disconnected'); clearSubscriptions() }
    client.onConnect = () => {
      setConnection('connected')
      // 当前会话 scoped 订阅（用 ref 读最新 sessionId）
      const sid = sessionIdRef.current
      if (sid) {
        sessionScopedSubsRef.current = subscribeCurrentSessionScoped(client, sid)
        // 会话级常驻订阅（token-warning / queue）
        subscribeSessionLevel(client, sid)
        // C1 · 当前会话常驻订阅权限 topics（leader 会话 idle 时 worker 发权限请求也能收到）
        if (!permSubsRef.current.has(sid)) permSubsRef.current.set(sid, subscribePermTopics(client, sid))
        // 常驻订阅会话 status topic（session.title/session.status）
        subscribeStatusTopic(client, sid)
        // cron/Ask 实时流：当前会话 stream topic 常驻订阅（后端主动推流也能收到）
        ensureSessionStream(client, sid)
      }
      // 多会话订阅：对每个活跃会话订阅 stream + 权限 topics（动态增删 · 见 syncAllStreams）
      syncAllStreams(client)
      // 后台任务 SDK 事件通道（/topic/tasks）：drain 出站为 JSON 数组；tool_use_summary 为单对象
      client.subscribe(TASKS_TOPIC, (msg) => {
        const raw = JSON.parse(msg.body)
        const events: TaskEvent[] = Array.isArray(raw) ? raw : [raw]
        events.forEach(handleTaskEvent)
      })
    }
    client.activate()
    clientRef.current = client
    setConnection('connecting')
    return () => {
      // client 单例 unmount 时清理（会话级 scoped 订阅 + 按 sid 常驻订阅）
      for (const sub of sessionScopedSubsRef.current) sub.unsubscribe()
      sessionScopedSubsRef.current = []
      for (const { token, queue } of sessionLevelSubsRef.current.values()) { token.unsubscribe(); queue.unsubscribe() }
      sessionLevelSubsRef.current.clear()
      streamSubsRef.current.clear()
      permSubsRef.current.clear()
      for (const sub of bridgeDismissSubs.values()) sub.unsubscribe()
      bridgeDismissSubs.clear()
      teamSubsRef.current = null
      teamLeadSubsRef.current = null
      for (const sub of statusSubsRef.current.values()) sub.unsubscribe()
      statusSubsRef.current.clear()
      void client.deactivate()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // 子代理历史按会话分区（localStorage 持久化 · 切会话恢复对应历史）+ 当前会话 scoped 订阅切换。
  //   client 单例不重建 → 原会话 stream/perm/status 订阅常驻；仅重订当前会话 team/todo/token/queue/skill。
  useEffect(() => {
    useSubagentStore.getState().setSession(sessionId)
    const client = clientRef.current
    if (!client?.connected) return
    if (!sessionId) return
    // 切换 scoped 订阅：先退订旧会话的 team/todo/token/queue/skill，再订阅新会话
    unsubscribeCurrentSessionScoped()
    sessionScopedSubsRef.current = subscribeCurrentSessionScoped(client, sessionId)
    // C1 · 新会话常驻订阅权限 topics
    if (!permSubsRef.current.has(sessionId)) {
      permSubsRef.current.set(sessionId, subscribePermTopics(client, sessionId))
    }
    // 常驻订阅会话 status topic
    subscribeStatusTopic(client, sessionId)
    // cron/Ask 实时流：新会话 stream topic 常驻订阅
    ensureSessionStream(client, sessionId)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionId])

  // activeStreams 变化（新会话发送登记 / 会话 complete 移除）→ 动态增删 stream + 权限订阅
  useEffect(() => {
    const client = clientRef.current
    if (!client?.connected) return
    syncAllStreams(client)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeStreams])

  /** 动态增删活跃会话的 stream + 权限订阅（onConnect 首次 + activeStreams 变化时）：
   *  切走会话不取消订阅，原会话事件持续接收直到 complete → 多会话并行状态准确。
   *  会话级单 topic：stream topic = /topic/sessions/{sid}/stream 恒定，无 per-message 轮换；
   *  按 activeStreams 集合增删会话级订阅（有活跃消息流才订阅，complete 后由 App 移除登记→退订）。 */
  function syncAllStreams(client: Client) {
    if (!client.connected) return
    const active = activeStreamsRef.current
    // 新增：活跃会话尚未订阅 → 订阅会话级 stream + 权限 + 会话级常驻（queue/token）topics
    for (const sid of Object.keys(active)) {
      if (!streamSubsRef.current.has(sid)) {
        const topic = `/topic/sessions/${sid}/stream`
        // TEMP 诊断：确认会话级单 topic 订阅建立（sid 对应 B 会话应出现）
        console.debug('[sub] stream subscribe', { sid, topic })
        streamSubsRef.current.set(sid, subscribeStream(client, topic, (evt) => dispatchEvent(evt, topic)))
      }
      if (!permSubsRef.current.has(sid)) {
        console.debug('[sub] perm subscribe', { sid })
        permSubsRef.current.set(sid, subscribePermTopics(client, sid))
      }
      // 会话级常驻订阅（queue.drained 排队消费 / token-warning 压缩警告）· 按 sid 常驻，切走不退订
      subscribeSessionLevel(client, sid)
    }
    // 移除：非活跃会话的订阅（当前会话常驻权限订阅不在此移除 —— C1 leader idle 也要收权限；
    //   当前会话 stream 常驻订阅也不在此移除 —— cron/Ask 主动推流依赖它）
    for (const [sid, sub] of streamSubsRef.current) {
      if (!active[sid] && sid !== sessionIdRef.current) { sub.unsubscribe(); streamSubsRef.current.delete(sid) }
    }
    for (const [sid, subs] of permSubsRef.current) {
      if (!active[sid] && sid !== sessionId) {
        subs.message.unsubscribe(); subs.bridge.unsubscribe(); subs.channel.unsubscribe()
        permSubsRef.current.delete(sid)
        bridgeDismissSubs.get(sid)?.unsubscribe()
        bridgeDismissSubs.delete(sid)
      }
    }
  }

  // leadSessionId 变化（创建/解散/会话切换）→ 动态重订「团队实际会话」teams topics；
  //   当前会话常驻订阅（teamSubsRef）不受影响（创建团队后当前会话立即收 created）。
  //   断线重连由 onConnect 兜底（重建当前会话订阅 + lead 订阅）。
  useEffect(() => {
    const client = clientRef.current
    teamLeadSubsRef.current?.status.unsubscribe()
    teamLeadSubsRef.current?.messages.unsubscribe()
    teamLeadSubsRef.current = null
    if (!client?.connected || !leadSessionId) return
    // leadSessionId 已由当前会话常驻订阅覆盖 → 仅当指向其他会话才额外订阅
    const subs = subscribeTeamTopics(client, leadSessionId)
    teamLeadSubsRef.current = { status: subs.status, messages: subs.messages }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [leadSessionId])

  /** 常驻订阅会话 status topic（/topic/sessions/{sid}/status）· 复用 dispatchEvent 分发（session.title/status）。
   *   对齐 SessionTitleEvent.java 注释约定「前端两条 topic 都应监听」——stream topic 随 activeStreams 取消，
   *   complete 后 maybeGenerateTitle 才推 title，必须由常驻 status 订阅接住。 */
  function subscribeStatusTopic(client: Client, sid: string) {
    if (statusSubsRef.current.has(sid)) return
    const topic = `/topic/sessions/${sid}/status`
    console.debug('[status-topic] subscribe', { sid, topic })
    statusSubsRef.current.set(sid, client.subscribe(topic, (msg) => {
      try {
        const evt = JSON.parse(msg.body) as StreamEvent
        dispatchEvent(evt, topic)
      } catch { /* 非法载荷忽略 */ }
    }))
  }

  /** /topic/tasks 事件：task_started/task_progress/task_notification → 本地通知；其余暂不映射 */
  function handleTaskEvent(evt: TaskEvent) {
    // 后端 d973edad 新增顶层 type='task.notification'（结构化终态直推 · 含空闲路径）：
    //   更新任务状态活动历史 + toast（对齐 CC 任务完成通知）；按会话过滤（契约 §二）
    if (evt.type === 'task.notification') {
      const taskId = evt.taskId ?? null
      if (taskId) {
        useSubagentStore.getState().addActivity(taskId, {
          type: evt.status === 'failed' ? 'failed' : evt.status === 'stopped' ? 'stopped' : 'done',
          text: evt.summary ?? '…',
          ts: Date.now(),
        }, evt.sessionId ?? sessionIdRef.current ?? undefined)
      }
      if (showToast) showToast(`任务${evt.status === 'failed' ? '失败' : evt.status === 'stopped' ? '已停止' : '完成'}：${evt.summary ?? '…'}`, 'info')
      return
    }
    if (evt.type !== 'system') return
    switch (evt.subtype) {
      case 'task_started': {
        const taskId = evt.task_id ?? evt.uuid ?? null
        const detail = evt.description ?? evt.task_type
        // [1a 类型过滤] 只登记真子代理身份（local_agent/in_process_teammate/remote_agent）——
        //   local_bash 等非子代理后台任务不进「子代理运行状况」面板（联调确认 bash 不该出现在该 tab；
        //   与决策 A 后端补发终态事件配套，bash 完成/滞留不再污染子代理面板）。
        const isSubagentType = evt.task_type === 'local_agent'
          || evt.task_type === 'in_process_teammate'
          || evt.task_type === 'remote_agent'
        // SUB-10：登记子代理身份（tool_use_id 为主 join key，消息侧用 toolCallId 查）；
        //   仅在有真实 taskId/toolUseId 时入表，避免 'unknown' 撞键污染
        if (isSubagentType && (evt.description || evt.task_type) && (taskId || evt.tool_use_id)) {
          useSubagentStore.getState().register(evt.tool_use_id ?? null, taskId ?? 'no-id', evt.description ?? evt.task_type ?? '子代理', evt.task_type, evt.session_id ?? sessionIdRef.current ?? undefined)
        }
        // 子代理启动 → 居中 toast（对齐「打开会话」提示 · 非贴左横幅）；状态仍进任务 tab 卡片
        if (showToast && detail && isSubagentType) showToast(`子代理启动：${detail}`, 'info')
        break
      }
      case 'task_progress': {
        // 写入子代理活动历史（任务 tab 点开时间线可见）；不弹 toast（避免刷屏）
        const taskId = evt.task_id ?? evt.uuid ?? null
        if (taskId) {
          useSubagentStore.getState().addActivity(taskId, { type: 'progress', text: evt.summary ?? '…', toolName: evt.last_tool_name, ts: Date.now() }, evt.session_id ?? sessionIdRef.current ?? undefined)
        }
        break
      }
      case 'task_notification': {
        const taskId = evt.task_id ?? evt.uuid ?? null
        // 终态写入活动历史（保留卡片供查看，不 forget）
        if (taskId) {
          useSubagentStore.getState().addActivity(taskId, {
            type: evt.status === 'failed' ? 'failed' : evt.status === 'stopped' ? 'stopped' : 'done',
            text: evt.summary ?? evt.output_file ?? '…',
            ts: Date.now(),
          }, evt.session_id ?? sessionIdRef.current ?? undefined)
        }
        // 任务终态 → 居中 toast（对齐「打开会话」提示）
        if (showToast) showToast(`任务${evt.status === 'failed' ? '失败' : evt.status === 'stopped' ? '已停止' : '完成'}：${evt.summary ?? evt.output_file ?? '…'}`, 'info')
        break
      }
      // session_state_changed 暂不映射通知
    }
  }

  function dispatchEvent(evt: StreamEvent, topic?: string) {
    const st = useChatStore.getState()
    // TEMP 诊断（多会话联调）：事件流全量 + tool_call 块 id 匹配检查
    console.debug('[stream]', evt.type, { sessionId: evt.sessionId ?? sessionId, assistantMessageId: (evt as { assistantMessageId?: string }).assistantMessageId })
    // 多会话分发：事件归属会话优先取 evt.sessionId（切走会话的事件仍落到原会话 streams）
    const sid = evt.sessionId ?? sessionId ?? ''
    if (isPushedUser(evt)) {
      // cron/Ask 后台落库的 user 消息（isMeta=true 占位）→ 进 messages 保持 flow 顺序（group 渲染跳过不显示）
      if (evt.id) st.appendMetaUser(sid, evt.id, evt.content ?? null)
    } else if (isChunk(evt)) {
      // 契约 #1：chunk.assistantMessageId = turnAssistantId（每轮真实稳定 id）→ 按轮惰性建块累积。
      //   'msg-stream' 兜底兼容旧后端（未改前所有 chunk 共享该 id → 归入同一条流式块）。
      const blockId = evt.assistantMessageId ?? 'msg-stream'
      // userMessageId 透传 → StreamBlock 记录所属 flow（前端按此分组锚定消息链）
      // [打字机性能] 移除每 chunk 磁盘日志（debugLog=writeTextFile 串行写盘 · 后端高频推 chunk 时
      //   阻塞 STOMP 处理 → 打字机滞后「后端完成前端还没打完」）；console.debug 保留（devtools 未开零成本）
      console.debug('[chunk] uid', { blockId, uid: evt.userMessageId, sid })
      st.ensureStreamBlock(sid, blockId, evt.userMessageId)
      if (evt.delta) st.appendChunk(sid, blockId, evt.delta)
      if (evt.reasoning) {
        // 过滤流式累积残留的 null 串（cleanReasoning 同源，展示层不再出现 nullnull…）
        const cleaned = evt.reasoning.replace(/(?:null)+/g, '')
        if (cleaned.trim()) st.appendReasoning(sid, blockId, cleaned)
      }
    } else if (isToolCall(evt)) {
      // TEMP 诊断：tool_call 目标 id vs 现有流式块 ids（确认是否同源匹配）
      console.debug('[tool_call] match?', { toolName: evt.toolName, callId: evt.toolCallId, target: evt.assistantMessageId, blockIds: (st.streams[sid] ?? []).map((b) => b.assistantMessageId) })
      // 契约 #6：回放推 tool_call → 按块 id 精确挂工具卡片（arguments Map → JSON 字符串对齐 ToolCallDto.arguments）。
      //   后端同源前 tool_call.assistantMessageId=落库 id ≠ 流式 turnAssistantId → 匹配不到块则不挂（不挂错轮）；
      //   后端落库 id 统一 turnAssistantId 后自动精确归属，前端无需改动。
      st.addToolCall(sid, evt.assistantMessageId ?? 'msg-stream', {
        id: evt.toolCallId ?? null,
        name: evt.toolName ?? null,
        arguments: evt.arguments ? JSON.stringify(evt.arguments) : null,
        result: null,
        isError: null,
      })
    } else if (isToolResult(evt)) {
      // 契约 #6：按 toolCallId 匹配卡片填 result/isError（跨块遍历）
      st.fillToolResult(sid, evt.toolCallId ?? '', evt.result ?? null, evt.isError ?? null)
    } else if (isBoundary(evt)) {
      // [snip-persist] Snip 裁剪边界（实时）：removedUuids → 会话 snippedIds → 消息右上角「已裁剪」
      //   （F5 由 setMessages 从 GET /messages 的 boundary 消息解析，同一集合）
      if (evt.removedUuids?.length) {
        st.markSnipped(sid, evt.removedUuids)
      }
    } else if (isComplete(evt)) {
      // 契约 #2/#5：complete 收口 → 流式块直接转消息（id=turnAssistantId，后端落库同源后即 DB 权威 id，免重拉）。
      //   透传 complete 的思考耗时（无重拉时块消息才不缺 reasoningDurationMs）
      st.finalizeBlocks(sid, {
        reasoningDurationMs: evt.reasoningDurationMs ?? null,
        // 契约（token usage/cost 上报）：complete 事件常驻携带真实 usage + 会话累计花费 + 上下文快照 → 透传到消息展示
        usage: evt.usage ?? null,
        totalCostUsd: evt.total_cost_usd ?? null,
        modelUsage: evt.modelUsage ?? null,
        contextTokensUsed: evt.contextTokensUsed ?? null,
        percentLeft: evt.percentLeft ?? null,
        // F4 t/s 速度：decode_ms（usage.decode_ms · 首 token→完成）与 contextWindow 透传到块消息
        decodeMs: evt.usage?.decode_ms ?? null,
        contextWindow: evt.contextWindow ?? null,
      })
      // 重试横幅清除：complete = 本轮 LLM 调用成功（api_retry 重试后成功）→ 隐藏「正在重试」
      //   横幅（对齐 CC：重试成功即消失；此前仅 onClose 手动关闭，残留导致「已完成仍显示重试」）
      st.setRetry(null)
      // 会话 token/金额汇总（底部 footer · F5 恢复源）：complete 事件携带会话累计（total_cost_usd=累计金额、
      //   modelUsage=各模型累计 token）→ 覆盖更新当前会话 SessionDto（非累加 —— 后端已是跨 turn 累计值）
      if (evt.total_cost_usd != null || evt.modelUsage != null) {
        let totalTokens = 0
        if (evt.modelUsage) {
          for (const m of Object.values(evt.modelUsage)) {
            totalTokens += (m.inputTokens ?? 0) + (m.outputTokens ?? 0)
          }
        }
        if (evt.total_cost_usd != null || totalTokens > 0) {
          st.updateSessionUsage(sid, {
            // 0/无 usage 的失败轮不得覆盖：cost 只在 >0 时更新（避免把已持久化累计清成 0）
            totalCostYuan: evt.total_cost_usd != null && evt.total_cost_usd > 0 ? evt.total_cost_usd : undefined,
            totalTokens: totalTokens > 0 ? totalTokens : undefined,
          })
        }
      }
      // 生命周期明确信号：通知 App 移除 activeStreams → 取消该会话订阅（无推断竞争）。
      //   带 topic：App 侧校验当前登记的 topic 匹配才删（防旧 turn 终止事件误删刚登记的新 turn）
      onSessionDoneRef.current?.(sid, topic)
    } else if (isRetry(evt)) {
      st.setRetry({ attempt: evt.attempt, maxRetries: evt.maxRetries, retryDelayMs: evt.retryDelayMs })
    } else if (isError(evt)) {
      // message.error → 对话流助手位置错误卡（对齐 CC assistant API error 展示 · 不再走顶部通知栏）
      st.addApiError(sid, {
        userMessageId: evt.userMessageId ?? null,
        assistantMessageId: evt.assistantMessageId ?? null,
        code: evt.code ?? null,
        message: evt.message ?? '模型调用失败',
      })
    } else if (isCancelled(evt)) {
      // 已取消 → 清流 + 居中 toast（对齐「已刷新对话」等通知 · 不再走左侧通知栏）
      st.clearStream(sid)
      onSessionDoneRef.current?.(sid, topic)
      if (showToast) showToast('已取消', 'info')
    } else if (isStatus(evt)) {
      // session.status → 驱动 StreamHeader 状态点；非法值回落 idle（防御后端脏数据）
      const raw = (evt as SessionStatusEvent).status
      const status = raw === 'thinking' || raw === 'streaming' ? raw : 'idle'
      st.setAgentStatus(status)
    } else if (isTokenWarning(evt)) {
      // 压缩警告抑制态（契约）：suppressed=true（压缩成功）→ 隐藏；false（新压缩）→ 恢复显示
      st.setTokenWarning(evt.suppressed ? null : evt)
    } else if (evt.type === 'session.title') {
      // 会话标题生成完成（后端 maybeGenerateTitle 推送）→ 同步会话列表 title
      const title = (evt as SessionTitleEvent).title
      console.debug('[status-topic] title event', { sid, title, topic, hasSession: st.sessions.some((s) => s.id === sid) })
      if (title) {
        st.setSessions(st.sessions.map((s) => (s.id === sid ? { ...s, title } : s)))
      }
    }
  }

  return { clientRef }
}
