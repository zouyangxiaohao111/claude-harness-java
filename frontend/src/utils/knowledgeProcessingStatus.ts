/**
 * 处理时间线头部状态的纯函数（源站 `utils/knowledgeProcessingStatus.ts` 逐字移植）。
 */

export type TimelineStatusInput = {
  parseStatus?: string
  traceStatus?: string
  isLatestAttempt: boolean
}

/** 失败的尝试在「队列等着重试它」期间依然可操作，所以仍要显示错误。 */
export function shouldShowProcessingError(hasError: boolean, parseStatus?: string, traceStatus?: string): boolean {
  return hasError && (parseStatus === 'failed' || traceStatus === 'failed')
}

/**
 * 解析是否处于「仍在推进」的状态（源站 `knowledge-processing-timeline.vue:225-230` 逐字）：
 * `pending | processing | finalizing`。
 *
 * <p>⚠️ 与 {@link parseStatusToTimelineStatus} 语义不同，⛔ 不要合并：
 * 后者是**展示映射**（`completed→done`、`processing→running`），本函数是**是否继续轮询 /
 * 能否取消解析**的行为判据。`finalizing` 是后处理派发窗口（summary / question /
 * graph.chunk[*] 还在产出事件）⇒ 仍算进行中。
 */
export function isPollingStatus(status?: string): boolean {
  return status === 'pending' || status === 'processing' || status === 'finalizing'
}

/**
 * 把知识行级别的解析状态，翻译成处理时间线用的 span 状态词表。
 */
export function parseStatusToTimelineStatus(status?: string): string {
  switch (status) {
    case 'completed':
      return 'done'
    case 'processing':
      return 'running'
    default:
      return status || ''
  }
}

/**
 * 最新一次尝试以<b>知识行</b>为准：根 span 只描述主管线，它可能已经 `done`
 * 而异步富化还在跑、或者后来失败了。历史尝试没有对应的知识行快照，
 * 所以沿用它们自己落库的根 span 状态。
 */
export function resolveTimelineHeaderStatus(input: TimelineStatusInput): string {
  if (input.isLatestAttempt) {
    return parseStatusToTimelineStatus(input.parseStatus) || input.traceStatus || ''
  }
  return input.traceStatus || parseStatusToTimelineStatus(input.parseStatus)
}
