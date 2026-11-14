/**
 * 「这条会话的权限 / AskUserQuestion 弹窗由知识库窗口接管」的进程级登记表。
 *
 * <p><b>为什么需要</b>：主窗口 `App.tsx` 的 PermissionBubble 只按
 * {@code r.sessionId === activeSessionId} 过滤；当知识库窗口开着的那条会话，同时也是
 * 主窗口的活动会话时，同一个请求会在**两个窗口各弹一次**（2026-11-14 用户报
 * 「知识库选择了，主会话还在」）。
 *
 * <p><b>做法</b>：知识库窗口在挂载/切换会话时 {@link #claim}，卸载/切走时 {@link #release}；
 * 主窗口渲染前先问 {@link #has}。主窗口没有开启知识库窗口时集合恒为空 ⇒ 行为与改造前逐字一致。
 *
 * <p>⛔ 不用 zustand store：这只是一条「谁在显示」的进程内事实，不需要触发重渲染；
 * 放进 store 反而会让 permissionQueue 的订阅面变大。
 */
const owned = new Set<string>()

export const kbPermissionOwnership = {
  /** 知识库窗口开始接管该会话的弹窗。 */
  claim(sessionId: string | null | undefined): void {
    if (sessionId) owned.add(sessionId)
  },
  /** 知识库窗口不再显示该会话（切走 / 关闭）。 */
  release(sessionId: string | null | undefined): void {
    if (sessionId) owned.delete(sessionId)
  },
  /** 主窗口判据：该会话的弹窗是否已由知识库窗口负责。 */
  has(sessionId: string | null | undefined): boolean {
    return !!sessionId && owned.has(sessionId)
  },
}
