import { useEffect } from 'react'
import type { Client, StompSubscription } from '@stomp/stompjs'
import { onSocketConnect, subscribeStream } from '@/api/socket'
import type { PeerInboxAggregateEvent } from '@/api/peerInbox'
import { usePeerInboxStore } from '@/stores/peerInboxStore'

/**
 * 跨会话待收件**聚合** topic（全局一条订阅，后端已按接收者合并所有会话）。
 *
 * <p>⛔ 不再按会话订阅 `/topic/sessions/{sid}/peer-inbox`：那样每切一次会话就要退订重订，
 * 且丢帧只能靠轮询补。聚合流用 `targetSessionId` 归属、用 `seq` 判连续。
 */
export const PEER_INBOX_TOPIC = '/topic/peer-inbox'

type ClientRef = { current: Client | null }

// ════════════════════════════════════════════════════════════════════════
// 模块级单例订阅（「全局只订一次」的强制点）
//
// <p>订阅生命周期与**会话**无关：切会话不重订、不新增订阅；只有连接（重）建立时
// 重新 attach（STOMP 订阅随连接失效），以及最后一个消费者卸载时退订。
// ════════════════════════════════════════════════════════════════════════
let aggregateRefs = 0
let aggregateClientRef: ClientRef | null = null
let aggregateSubClient: Client | null = null
let aggregateSub: StompSubscription | null = null
let aggregateOffConnect: (() => void) | null = null

function onAggregateFrame(evt: unknown): void {
  usePeerInboxStore.getState().applyAggregateEvent(evt as PeerInboxAggregateEvent)
}

/** 在已连接 client 上挂聚合订阅（同一 client 已有订阅则不重复订）。 */
function attachAggregate(): void {
  const client = aggregateClientRef?.current
  if (!client?.connected) return
  if (aggregateSub && aggregateSubClient === client) return
  try {
    aggregateSub?.unsubscribe()
  } catch {
    /* 旧连接已失效 */
  }
  aggregateSub = null
  aggregateSubClient = null
  try {
    aggregateSub = subscribeStream(client, PEER_INBOX_TOPIC, onAggregateFrame)
    aggregateSubClient = client
  } catch {
    aggregateSub = null
  }
}

/**
 * 一次性补拉：列表（当前会话的角标/提示条真源）+ 全局计数基线。
 *
 * <p>只在「socket 重连」「页面回到前台」调用；⛔ 不再是周期轮询（事件覆盖常规到达）。
 */
function refreshOnce(): void {
  const state = usePeerInboxStore.getState()
  void state.load(state.sessionId)
  void state.reconcileCounts()
}

function handleSocketConnect(): void {
  // 未真正连接（连接回调先于 ready / 测试替身模拟断线）⇒ 不补拉，等下一次 connect。
  if (!aggregateClientRef?.current?.connected) return
  attachAggregate()
  // 重连窗口内的事件可能已丢（STOMP simple broker 不持久）⇒ 补拉一次。
  refreshOnce()
}

function acquireAggregate(clientRef?: ClientRef): () => void {
  if (clientRef) aggregateClientRef = clientRef
  aggregateRefs += 1
  if (aggregateRefs === 1) {
    try {
      aggregateOffConnect = onSocketConnect(handleSocketConnect)
    } catch {
      // 部分测试替身只导出部分 socket API：缺钩子不得让 App 崩（订阅仍可工作）。
      aggregateOffConnect = null
    }
    attachAggregate()
    // 首挂载时已连接：列表由 startup load 负责，这里只补全局计数基线（seq 基线）。
    if (aggregateClientRef?.current?.connected) void usePeerInboxStore.getState().reconcileCounts()
  }
  return () => {
    aggregateRefs = Math.max(0, aggregateRefs - 1)
    if (aggregateRefs > 0) return
    aggregateOffConnect?.()
    aggregateOffConnect = null
    try {
      aggregateSub?.unsubscribe()
    } catch {
      /* 已断线 */
    }
    aggregateSub = null
    aggregateSubClient = null
    aggregateClientRef = null
  }
}

/** 测试辅助：清空模块级订阅状态（生产代码不调用）。 */
export function __resetPeerInboxAggregateForTest(): void {
  aggregateOffConnect?.()
  try {
    aggregateSub?.unsubscribe()
  } catch {
    /* noop */
  }
  aggregateRefs = 0
  aggregateClientRef = null
  aggregateSubClient = null
  aggregateSub = null
  aggregateOffConnect = null
}

/**
 * 待收件列表的刷新编排（与 store 解耦，便于 App 常驻调用）。
 *
 * <p>触发点：会话切换（startup 首载）、socket 重连、页面重新可见；以及全局聚合 topic
 * {@link PEER_INBOX_TOPIC} 的实时事件。**已移除 60s 周期轮询** —— 事件覆盖常规到达，
 * 轮询退化为「重连 / 前台恢复」各一次补拉（丢帧由 seq 缺口触发 counts 对账）。
 *
 * <p>实时通道是**全局**的：不随 sessionId 重订；store 的 `applyAggregateEvent` 按
 * `targetSessionId` 判定是否需要刷新当前会话列表（⛔ 不把别的会话的待收件显示到当前会话）。
 *
 * 不用全局 toast 报错 —— 失败态只落在面板里（不打断用户当前操作）。
 */
export function usePeerInboxFeed(
  sessionId: string | null,
  clientRef?: ClientRef,
): void {
  const load = usePeerInboxStore((s) => s.load)

  useEffect(() => {
    // startup：启动 / 切会话后的**首次**加载 → pending>0 时自动展开面板一次并提示
    // （重连/可见刷新都不传，避免反复弹）。
    void load(sessionId, { startup: true })
  }, [sessionId, load])

  // 全局聚合订阅：依赖**只有 clientRef**（会话切换不得重订/新增订阅）。
  useEffect(() => {
    return acquireAggregate(clientRef)
  }, [clientRef])

  // 页面回到前台：补拉一次（切后台期间事件可能已丢）；⛔ 无周期轮询。
  useEffect(() => {
    const onVisible = () => {
      if (document.visibilityState === 'visible') refreshOnce()
    }
    document.addEventListener('visibilitychange', onVisible)
    return () => document.removeEventListener('visibilitychange', onVisible)
  }, [])
}
