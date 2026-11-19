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

// ── 跨窗口（Tauri 独立窗口）补充通道 ────────────────────────────────────────
//
// ⚠️ 上面的 Set 只在**当前 webview** 生效：知识库独立窗口是另一个 webview，
//   主窗口的 `has()` 永远读不到它的 claim（2026-11-18 用户报「两边都弹」）。
//   同理 KB 窗口回答后 `dequeuePermission` 只清它自己的 store，主窗口那份还在 ⇒
//   「KB 已经选了，主会话弹窗不消失」。
//
// 这里用 Tauri 的窗口间事件补齐：
//   · changed  —— 归属变化，主窗口据此重算要不要弹；
//   · answered —— 主窗口把同一条请求从队列删掉（弹窗立刻消失）。
// 非 Tauri（浏览器 / 单测）下全部是空操作，行为与改造前一致。

const EV_CHANGED = 'nexusai:kb-perm-changed'
const EV_ANSWERED = 'nexusai:kb-perm-answered'

async function eventApi() {
  try {
    const { isTauri } = await import('@tauri-apps/api/core')
    if (!isTauri()) return null
    return await import('@tauri-apps/api/event')
  } catch {
    return null
  }
}

/**
 * 广播「某会话的弹窗归属变了」。
 *
 * <p>载荷带 `sessionId + owned`，主窗口按会话精确增删 —— ⛔ 不能只发一个「变了」的
 * 空信号，否则主窗口无从知道是哪条会话、是接管还是释放（会误伤主会话里其它会话）。
 */
export async function broadcastKbOwnershipChanged(
  sessionId: string | null | undefined, owned: boolean,
): Promise<void> {
  if (!sessionId) return
  const api = await eventApi()
  if (!api) return
  try {
    await api.emit(EV_CHANGED, { sessionId, owned })
  } catch { /* 广播失败不影响本窗口 */ }
}

/** 广播「这条请求已经被知识库窗口回答/拒绝了」。 */
export async function broadcastKbAnswered(
  sessionId: string, requestId: string, toolUseId?: string | null,
): Promise<void> {
  if (!sessionId || !requestId) return
  const api = await eventApi()
  if (!api) return
  try {
    // toolUseId 一并带过去：同一 tool_use 的 message + bridge 两条 requestId 不同，
    // 主窗口按 toolUseId 才能把另一条一起清掉（否则左栏黄点残留）。
    await api.emit(EV_ANSWERED, { sessionId, requestId, toolUseId: toolUseId ?? null })
  } catch { /* 广播失败不影响本窗口 */ }
}

/** 主窗口监听：某会话的归属变化（接管 / 释放）。返回取消函数。 */
export async function onKbOwnershipChanged(
  cb: (payload: { sessionId: string; owned: boolean }) => void,
): Promise<() => void> {
  const api = await eventApi()
  if (!api) return () => {}
  try {
    const un = await api.listen<{ sessionId: string; owned: boolean }>(
      EV_CHANGED, (e) => cb(e.payload))
    return () => { void un() }
  } catch {
    return () => {}
  }
}

/** 主窗口监听：KB 窗口已回答某请求。返回取消函数。 */
export async function onKbAnswered(
  cb: (payload: { sessionId: string; requestId: string; toolUseId?: string | null }) => void,
): Promise<() => void> {
  const api = await eventApi()
  if (!api) return () => {}
  try {
    const un = await api.listen<{ sessionId: string; requestId: string; toolUseId?: string | null }>(
      EV_ANSWERED, (e) => cb(e.payload))
    return () => { void un() }
  } catch {
    return () => {}
  }
}
