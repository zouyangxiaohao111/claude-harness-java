import { useMemo } from 'react'
import { useChatStore, type PermissionRequestItem } from '@/stores/chatStore'
import { askQuestionSummary, isAskUserQuestion } from '@/utils/askUserNotice'
import { kbPermissionOwnership } from '@/utils/kbPermissionOwnership'

/**
 * 浮层最多显示几条会话（其余折叠成计数条）。
 *
 * <p>WHY 有上限：普通权限请求比提问型常见得多 —— 每个后台会话各一条，且工具线程会
 * **卡住**等用户决策（后端 {@code future.get()} 无限等待）⇒ 多个后台会话同时等授权是常态。
 * 浮层是输入框上方**单行**（{@code .composer-top} 是 flex 且不换行），不限条数会横向
 * 撑破输入框、把右缘的「回到底部 / 停止所有」挤出去。
 */
const MAX_VISIBLE = 3

/**
 * 「有工具在等你」浮层（输入框上方右缘 · 与「回到底部」并排）。
 *
 * <h2>WHY 需要它</h2>
 * 权限请求（AskUserQuestion 提问 + Bash/Edit 等普通授权）会**阻塞整个轮次** ——
 * 后端在 {@code future.get()} 上无限等待（超时已移除），用户不决策，这一轮就永远不结束。
 * 而决策卡片（{@code PermissionBubble}）只渲染在**当前会话**的对话流里 ⇒ 两个看不见的场景：
 * <ul>
 *   <li><b>请求在别的会话里</b>：用户在另一个会话 ⇒ 卡片根本没渲染，只有侧栏一个黄点
 *       （App.tsx 的 pendingSessionIds）。用户不看侧栏就完全不知道；</li>
 *   <li><b>当前会话但消息很长</b>：卡片在对话流末尾，用户可能滚不到那儿。</li>
 * </ul>
 * ⇒ 输入框上方固定一个浮层，任何会话的待决请求一到达就能看见，点一下直达。
 *
 * <h2>显示范围</h2>
 * 只显示**非当前会话**的待决请求：当前会话的卡片已在对话流里（{@code PermissionBubble}），
 * 再提示一遍是重复噪音。
 *
 * <p>⚠️ 「非当前会话」由**渲染时**的 activeSessionId 判定 ⇒ 用户切到该会话的那一刻，
 * 浮层自动消失（会话已切过去 ⇒ 卡片已经在对话流里可见）。
 *
 * <h2>两类请求的判据</h2>
 * <ul>
 *   <li><b>提问型</b>（{@code toolInput.questions} 非空）⇒ 文案「需要你回答」，
 *       悬停显示首题摘要。与后端 {@code WebSocketPermissionPrompter} 自己的诊断日志同口径，
 *       也与 {@code PermissionBubble} 渲染选择题读的字段一致 ⇒ 三处不会漂移。</li>
 *   <li><b>普通权限型</b>（无 questions）⇒ 文案「需要授权：{toolName}」，
 *       悬停显示工具名 + 后端给的描述。</li>
 * </ul>
 *
 * <h2>每个会话只取一条</h2>
 * 同一会话可能同时挂着多条待决请求（多个工具都在等，或同一 tool_use 的 message/bridge 双推）⇒
 * 每会话只展示一条代表项，选法与 {@code App.tsx} 的 {@code currentPermission} 同口径：
 * <b>优先 kind==='message' 通道</b>（本地桌面那条最完整，bridge 的入参字段名不同），
 * 其次按入队先后。
 */
export function PendingPermissionHint({
  activeSessionId,
  onOpenSession,
}: {
  /** 当前会话 id（由 App 传入 —— 它来自 session reducer，不在 chatStore 里）。 */
  activeSessionId: string
  onOpenSession: (sessionId: string) => void
}) {
  const permissionQueue = useChatStore((s) => s.permissionQueue)
  const sessions = useChatStore((s) => s.sessions)

  /**
   * 每个会话一条代表项（优先 message 通道 · 其次入队先后）。
   *
   * <p>⛔ 不按「提问型/普通权限型」筛：两类都会阻塞轮次，都得提示。
   */
  const pending = useMemo(() => {
    const bySession = new Map<string, PermissionRequestItem>()
    for (const r of permissionQueue) {
      if (r.sessionId === activeSessionId) continue
      const cur = bySession.get(r.sessionId)
      if (!cur || (cur.kind !== 'message' && r.kind === 'message')) {
        bySession.set(r.sessionId, r)
      }
    }
    return [...bySession.values()]
  }, [permissionQueue, activeSessionId])

  /**
   * 知识库窗口已接管的会话 ⇒ 本窗口不提示。
   *
   * <p>⛔ 不加这一条：主窗口不渲染该会话的决策卡片（App.tsx 的 currentPermission 同样过滤），
   * 但浮层若仍提示，用户点进去会看到「什么都没发生」—— 提示与实际渲染面必须一致。
   *
   * <p>（在**渲染时**过滤而非放进 useMemo：kbPermissionOwnership 是进程级登记表、非响应式，
   * 只随权限队列变化重算会漏掉「接管之后队列没再变」的情况。）
   */
  const visible = pending.filter((r) => !kbPermissionOwnership.has(r.sessionId))

  if (visible.length === 0) return null

  // 超出上限的会话数（普通权限请求比提问型常见：每个后台会话各一条，且工具线程会卡住等决策
  // ⇒ 多个后台会话同时等授权时很常见）。浮层是输入框上方**单行**（.composer-top 是 flex 且
  // 不换行），不限条数会横向撑破输入框、把右缘的「回到底部/停止所有」挤出去。
  // ⇒ 最多显示 MAX_VISIBLE 条，其余用计数条告知（侧栏黄点仍覆盖全部待决会话）。
  const shown = visible.slice(0, MAX_VISIBLE)
  const overflow = visible.length - shown.length

  return (
    <div className="perm-pending-hint">
      {shown.map((r) => {
        const title = sessions.find((s) => s.id === r.sessionId)?.title?.trim()
        const isAsk = isAskUserQuestion(r.toolInput)
        const toolName = r.toolName || '工具'
        const summary = isAsk
          ? askQuestionSummary(r.toolInput) ?? '有工具在等你回答'
          : permissionSummary(r)
        return (
          <button
            key={r.sessionId}
            className="perm-pending-hint-item"
            title={summary}
            onClick={() => onOpenSession(r.sessionId)}
          >
            <span className="pph-dot" aria-hidden="true" />
            <span className="pph-text">
              <b>{isAsk ? '需要你回答' : `需要授权：${toolName}`}</b>
              <span className="pph-session">{title || '未命名会话'}</span>
            </span>
          </button>
        )
      })}
      {overflow > 0 && (
        <span className="perm-pending-hint-more" title={`另有 ${overflow} 个会话在等你决策（见左栏黄点）`}>
          +{overflow}
        </span>
      )}
    </div>
  )
}

/**
 * 普通权限请求的悬停摘要：工具名 + 后端给的描述（截断到可读长度）。
 *
 * <p>与 {@code askQuestionSummary} 同口径截断（60 字 + 省略号）—— 浮层 tooltip 过长会撑爆布局。
 */
function permissionSummary(r: PermissionRequestItem): string {
  const name = r.toolName || '工具'
  const desc = (r.description ?? '').trim()
  const text = desc ? `${name}：${desc}` : name
  return text.length > 60 ? `${text.slice(0, 60)}…` : text
}
