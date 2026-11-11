/**
 * 「工具在等你回答」的系统提示（右下角浮层 + 系统通知）判定与去重。
 *
 * <h2>WHY 需要单独一个模块</h2>
 * 提问请求（AskUserQuestion）到达前端有<b>四条路径</b>，全都汇进同一个权限队列：
 * <ol>
 *   <li>实时 message 通道（{@code /permission-requests}，当前会话）—— useChatSocket.ts:329</li>
 *   <li>实时 bridge 通道（{@code /permission-bridge-requests}）—— 同上</li>
 *   <li>实时 channel 通道（{@code /permission-channel-requests}）—— 同上</li>
 *   <li>REST 补拉（{@code GET /sessions/{id}/state} → pendingPermissions）—— useChatSocket.ts:299 / :682</li>
 * </ol>
 * 同一次提问会被<b>重复到达</b>（bridge 与 message 是同一次待审批的两个推送面，
 * 补拉路径还会在切会话 / 重连 / 8 秒兜底轮询时再次捞到同一个 requestId）。
 * ⇒ <b>通知必须按 requestId 去重</b>，否则用户会连着收到 N 条一模一样的系统通知。
 *
 * <h2>判据：为什么看 questions 而不是看工具名</h2>
 * 后端 {@code WebSocketPermissionPrompter} 自己的诊断日志（第 776 行）判定「这是提问」
 * 用的就是 {@code toolInput.has("questions")} ⇒ 前端用<b>同一口径</b>，避免两边判据漂移。
 * ⛔ 不按 {@code toolName === 'AskUserQuestion'} 判定：那只是**当前**的工具名，
 * 将来新增别的提问型工具时会漏判；且 toolName 在三条通道上都可能缺省。
 *
 * <p>另：{@code PermissionBubble} 渲染选择题读的也是 {@code toolInput.questions}
 * （见 PermissionBubble.tsx 的 extractQuestions）⇒ 判据与渲染层天然一致。
 */

/** 提问工具名（仅用于文案兜底；判定本身不依赖它）。 */
export const ASK_USER_QUESTION_TOOL_NAME = 'AskUserQuestion'

/**
 * 从工具入参里取问题列表。
 *
 * <p>入参可能是**对象**也可能是**JSON 字符串**（前端 `PermissionBubble.extractQuestions`
 * 两种都处理）⇒ 这里同样两种都收。
 *
 * @returns 问题数组（取不到 ⇒ 空数组）
 */
export function extractAskQuestions(toolInput: unknown): unknown[] {
  if (toolInput == null) return []
  let input: unknown = toolInput
  if (typeof toolInput === 'string') {
    const trimmed = toolInput.trim()
    if (!trimmed) return []
    try {
      input = JSON.parse(trimmed)
    } catch {
      return []
    }
  }
  if (typeof input !== 'object' || input === null) return []
  const questions = (input as Record<string, unknown>).questions
  return Array.isArray(questions) ? questions : []
}

/** 一条提问请求是否属于「AskUserQuestion 类」（需要用户回答）。 */
export function isAskUserQuestion(toolInput: unknown): boolean {
  return extractAskQuestions(toolInput).length > 0
}

/**
 * 通知文案里的问题摘要（取第一题的问题文本，截断到可读长度）。
 *
 * @returns 摘要文本；取不到 ⇒ null（调用方决定兜底文案）
 */
export function askQuestionSummary(toolInput: unknown, maxLen = 60): string | null {
  const first = extractAskQuestions(toolInput)[0] as Record<string, unknown> | undefined
  const text = typeof first?.question === 'string' ? first.question.trim() : ''
  if (!text) return null
  return text.length > maxLen ? `${text.slice(0, maxLen)}…` : text
}

/**
 * 提问通知的**去重登记表**：已发过通知的 requestId。
 *
 * <p>⛔ 为什么不能靠「队列里没有该项就算新」：四条到达路径之间存在**时间差** ——
 * bridge 事件先到（入队）、message 事件后到（此时队列里已有该项 ⇒ 会被误判成「重复」而跳过）。
 * 反过来若不查表直接发，则同一 requestId 会连发多条。
 * ⇒ 登记表按 <b>requestId</b> 判定「这个请求是否已经通知过」，与队列状态解耦。
 *
 * <p>清理：requestId 是工具调用 id（每次调用唯一）⇒ 只需在数量超阈值时整体清空，
 * 防止长时间运行后无界增长。清理不精确（可能漏掉尚未通知的），但漏掉的代价只是
 * 多发一条通知，不会造成「该通知的没通知」。
 */
const notifiedRequestIds = new Set<string>()

/** 去重表容量上限（超出即整体清空；远大于同时在途的提问数）。 */
const NOTIFY_DEDUP_CAPACITY = 500

/**
 * 标记并返回「这是否是一条**尚未通知过**的提问请求」。
 *
 * @param requestId 请求 id（工具调用 id）
 * @param toolInput 工具入参（判据：questions 非空）
 * @returns true ⇒ 是提问且从未通知过（调用方应发通知）；false ⇒ 不是提问，或已通知过
 */
export function markAskUserQuestionNotified(requestId: string, toolInput: unknown): boolean {
  if (!isAskUserQuestion(toolInput)) return false
  if (!requestId) return false
  if (notifiedRequestIds.has(requestId)) return false
  if (notifiedRequestIds.size >= NOTIFY_DEDUP_CAPACITY) {
    notifiedRequestIds.clear()
  }
  notifiedRequestIds.add(requestId)
  return true
}

/** 清空去重登记表（测试用；生产不需要调用）。 */
export function resetAskUserQuestionNotified(): void {
  notifiedRequestIds.clear()
}
