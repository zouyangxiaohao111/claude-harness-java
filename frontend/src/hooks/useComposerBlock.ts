import { useMemo } from 'react'
import { useChatStore } from '@/stores/chatStore'
import { usePeerInboxStore } from '@/stores/peerInboxStore'
import { kbPermissionOwnership } from '@/utils/kbPermissionOwnership'

/**
 * [composer-block] 输入框「暂不能发送」的统一判据。
 *
 * <h2>WHY 需要封锁</h2>
 * 两类事情会让「再发一条用户消息」失去意义甚至造成错乱：
 * <ol>
 *   <li><b>待确认的跨会话消息</b>（hold）：必须由用户先接受/拒绝（或批量拒绝）才会投递，
 *       此时继续发送会与待确认内容抢同一轮，用户也分不清哪条进了对话。</li>
 *   <li><b>未处理的权限审批 / Ask 提问</b>：后端工具线程正卡在 {@code future.get()} 上等决策，
 *       整轮没有结束；此时放行发送只会把新消息排进一个卡住的轮次。</li>
 * </ol>
 * 所以只在**当前会话**内封锁「发送」，界面其余部分（面板、滚动、审批卡、切换会话）照旧可用。
 *
 * <h2>数据来源（均为现成真源，⛔ 不自造内存标记）</h2>
 * <ul>
 *   <li>{@link usePeerInboxStore} 的 {@code pendingCount}：口径 = {@code status === 'pending'}，
 *       已读（seenAt）不参与；declined / accepted / expired 等终态天然不计。</li>
 *   <li>{@link useChatStore} 的 {@code permissionQueue}：与 App `currentPermission` 同一份队列，
 *       按 sessionId 精确过滤；知识库窗口正在接管的会话在本窗口没有可操作卡片 ⇒ 不计
 *       （否则会「锁了输入框，却看不到能点的审批卡」）。</li>
 * </ul>
 *
 * <p>⛔ 判据严格按会话归属：别的会话的待办不得封锁本会话（切会话后 store 里旧列表仍会
 * 短暂残留，故先校验 {@code store.sessionId === sessionId}）。审批结束（决策/别处已答/桥接
 * dismiss）会从队列移除 ⇒ 本判据自动回到可发送，无需刷新。
 */
export interface ComposerBlockState {
  /** 统一禁用判据：任一条成立即封锁发送 */
  blocked: boolean
  /** 当前会话待确认（pending）的跨会话消息数 */
  peerPending: number
  /** 当前会话未处理的权限审批 / Ask 提问数 */
  pendingApprovals: number
  /** 禁用原因（提示条 / title / aria 共用同一口径；未禁用为 null） */
  reason: string | null
}

export function useComposerBlock(sessionId: string | null | undefined): ComposerBlockState {
  const peerPending = usePeerInboxStore((s) =>
    sessionId && s.sessionId === sessionId ? s.pendingCount : 0)
  const permissionQueue = useChatStore((s) => s.permissionQueue)
  const pendingApprovals = useMemo(() => {
    if (!sessionId) return 0
    return permissionQueue.reduce(
      (n, r) => (r.sessionId === sessionId && !kbPermissionOwnership.has(r.sessionId) ? n + 1 : n),
      0,
    )
  }, [permissionQueue, sessionId])

  const blocked = peerPending > 0 || pendingApprovals > 0
  const reason = peerPending > 0 && pendingApprovals > 0
    ? '有待确认消息和待审批请求，处理后才能发送'
    : peerPending > 0
      ? '有待确认消息，处理后才能发送'
      : pendingApprovals > 0
        ? '等待审批结果，暂不能发送'
        : null
  return { blocked, peerPending, pendingApprovals, reason }
}
