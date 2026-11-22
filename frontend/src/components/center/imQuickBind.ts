/** 快速绑定预览（与后端 /im/chats/bind-default/preview 同形）。 */
export interface QuickBindPreview {
  found: boolean
  chatId?: string
  chatName?: string
  boundSessionId?: string
  boundSessionTitle?: string
  message?: string
}

/**
 * [快速绑定] 需要确认的文案；不需要确认时返回 null。
 *
 * <p>WHY：点「快速绑定」时后端会自己挑默认会话；若那条会话已经接在**别的任务**上，
 * 直接改绑会静默抢走对方的接线 ⇒ 必须先问。
 * 文案必须让人一眼看出「会解绑别人的任务」，所以把后果写进正文。
 * 已绑在**当前会话**上 ⇒ 无需确认（重复绑定幂等）。
 */
export function quickBindTakeoverMessage(
  preview: QuickBindPreview | null | undefined,
  currentSessionId: string | null | undefined,
): string | null {
  if (!preview?.found || !preview.boundSessionId) return null
  if (currentSessionId && preview.boundSessionId === currentSessionId) return null
  const who = preview.boundSessionTitle || preview.boundSessionId
  return `该渠道会话已被「${who}」占用，切换会解绑其他任务，确定切换吗？`
}

/** 快速绑定的可注入依赖（测试用；生产由 App 传 imApi + window.confirm）。 */
export interface QuickBindDeps {
  preview: (instanceId: string) => Promise<QuickBindPreview>
  bind: (instanceId: string, sessionId: string) => Promise<{ chatName: string }>
  confirm: (message: string) => Promise<boolean>
}

export interface QuickBindResult {
  ok: boolean
  /** 需要 toast 的文案；null = 用户主动取消，不要弹 */
  message: string | null
  type: 'success' | 'info'
}

/**
 * 快速绑定的完整流程（预览 → 必要时确认 → 绑定）。
 *
 * <p>为什么要先 preview：`bind-default` 本身不做二次确认，直接调就会静默改绑
 * 其他任务的接线。三条硬规则：
 * <ol>
 *   <li>{@code found=false} ⇒ 提示「暂无可用会话」，**不调** bind；</li>
 *   <li>预览失败 ⇒ 明确报错，**不绑定**（不能猜）；</li>
 *   <li>已被别的任务占用 ⇒ 必须用户确认，取消**不调** bind。</li>
 * </ol>
 */
export async function runQuickBind(
  instanceId: string,
  sessionId: string,
  deps: QuickBindDeps,
): Promise<QuickBindResult> {
  let preview: QuickBindPreview
  try {
    preview = await deps.preview(instanceId)
  } catch {
    return { ok: false, message: '无法确认该会话的绑定状态，请稍后重试', type: 'info' }
  }
  if (!preview?.found) {
    return { ok: false, message: '该渠道暂无可用会话', type: 'info' }
  }
  const takeover = quickBindTakeoverMessage(preview, sessionId)
  if (takeover && !(await deps.confirm(takeover))) {
    return { ok: false, message: null, type: 'info' }
  }
  try {
    const r = await deps.bind(instanceId, sessionId)
    return { ok: true, message: `已把「${r.chatName}」接到当前会话`, type: 'success' }
  } catch (e) {
    return {
      ok: false,
      message: e instanceof Error ? e.message : '快速绑定失败',
      type: 'info',
    }
  }
}
