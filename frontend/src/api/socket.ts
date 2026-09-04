import { Client, type IMessage, type StompSubscription } from '@stomp/stompjs'
import SockJS from 'sockjs-client'
import type { StreamEvent, MessageChunkEvent, PushedUserMessageEvent, MessageCompleteEvent, MessageUsageEvent, PermissionRequestEvent, ApiRetryEvent, MessageErrorEvent, MessageCancelledEvent, MessageToolCallEvent, MessageToolResultEvent, MessageBoundaryEvent, TokenWarningEvent, AskUserAnswers, AskUserAnnotations } from './types'

export function parseStreamEvent(raw: unknown): StreamEvent {
  const obj = (raw ?? {}) as Record<string, unknown>
  return { ...obj, type: String(obj.type ?? '') } as StreamEvent
}

export const isChunk = (e: StreamEvent): e is MessageChunkEvent => e.type === 'message.chunk'
export const isPushedUser = (e: StreamEvent): e is PushedUserMessageEvent => e.type === 'message.user'
export const isComplete = (e: StreamEvent): e is MessageCompleteEvent => e.type === 'message.complete'
/** 消息级 usage 快照守卫（消息级完成、非 turn 终态 —— isComplete 对 message.usage 恒 false → 不退订） */
export const isMessageUsage = (e: StreamEvent): e is MessageUsageEvent => e.type === 'message.usage'
export const isToolCall = (e: StreamEvent): e is MessageToolCallEvent => e.type === 'message.tool_call'
export const isBoundary = (e: StreamEvent): e is MessageBoundaryEvent => e.type === 'message.boundary'
export const isToolResult = (e: StreamEvent): e is MessageToolResultEvent => e.type === 'message.tool_result'
export const isPermission = (e: StreamEvent): e is PermissionRequestEvent => e.type === 'permission.request'
export const isStatus = (e: StreamEvent): boolean => e.type === 'session.status'
export const isRetry = (e: StreamEvent): e is ApiRetryEvent => e.type === 'api_retry'
export const isTitle = (e: StreamEvent): boolean => e.type === 'session.title'
export const isError = (e: StreamEvent): e is MessageErrorEvent => e.type === 'message.error'
export const isTokenWarning = (e: StreamEvent): e is TokenWarningEvent => e.type === 'token_warning'
export const isCancelled = (e: StreamEvent): e is MessageCancelledEvent => e.type === 'message.cancelled'

/**
 * 创建 STOMP Client（不激活，由 useChatSocket 管理生命周期）。
 * F1：优先原生 WebSocket（/ws），失败/不可用时回退 SockJS（/ws-sockjs，后端 WebSocketConfig 端点）。
 * useSockJS 为一次性 latch：原生 WS 出错/关闭 → 后续重连改走 SockJS（每次新建 client 重新从原生开始）。
 */
export function createSocketClient(): Client {
  let useSockJS = false
  const client = new Client({
    brokerURL: 'ws://localhost:3458/ws',
    reconnectDelay: 2000,
    webSocketFactory: () => {
      if (useSockJS) return new SockJS('http://localhost:3458/ws-sockjs')
      return new WebSocket('ws://localhost:3458/ws')
    },
    // F1：原生 WebSocket 连不上（未开 /ws / 代理拦截）→ 置位，重连走 SockJS
    onWebSocketError: () => { useSockJS = true },
    onWebSocketClose: () => { useSockJS = true },
    onConnect: () => {},
    onStompError: (frame) => {
      console.error('STOMP error', frame.headers, frame.body)
    },
  })
  client.onConnect = () => {}
  return client
}

/** 订阅 stream topic，把 body 解析后回调 onMessage；返回订阅句柄（多会话动态增删 unsubscribe 用） */
export function subscribeStream(client: Client, topic: string, onMessage: (evt: StreamEvent) => void): StompSubscription {
  return client.subscribe(topic, (msg: IMessage) => {
    let parsed: StreamEvent
    try { parsed = parseStreamEvent(JSON.parse(msg.body)) }
    catch { parsed = { type: 'unknown' } }
    onMessage(parsed)
  })
}

export type PermissionKind = 'message' | 'bridge' | 'channel'

/** 发送权限决策，按 kind 路由到对应 destination（message/bridge/channel）。
 *  AskUser 场景额外携带 answers/annotations（仅提供时带上，缺省不发）；
 *  后端 MessagePermissionResponseEvent 已支持解析并合并进 Allow.updatedInput。 */
export function sendPermissionResponse(client: Client, sessionId: string, kind: PermissionKind, requestId: string, decision: 'allow' | 'deny', extra?: { answers?: AskUserAnswers; annotations?: AskUserAnnotations }) {
  const dest = {
    message: `/app/sessions/${sessionId}/permission-response`,
    bridge: `/app/sessions/${sessionId}/permission-bridge-response`,
    channel: `/app/sessions/${sessionId}/permission-channel-response`,
  }[kind]
  const payload: Record<string, unknown> = { requestId, decision }
  if (extra?.answers !== undefined) payload.answers = extra.answers
  if (extra?.annotations !== undefined) payload.annotations = extra.annotations
  client.publish({ destination: dest, body: JSON.stringify(payload) })
}
