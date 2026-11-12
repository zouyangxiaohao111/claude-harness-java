/**
 * 卡住判定（源站 `utils/knowledgeProcessingStall.ts` 逐字移植）。
 */

// 一个阶段在两次 span 落库之间合法地跑一阵是正常的（大文件的一次 DocReader 调用、
// 单次 LLM 调用），所以这里只把文档标记成「可能卡住」；真正的失败由服务端按自己的阈值判。
export const PROCESSING_STALL_THRESHOLD_MS = 20 * 60 * 1000

// 一旦在途的都看起来卡住了，就改用这个更慢的轮询间隔。
export const STALLED_POLL_INTERVAL_MS = 15 * 1000

const IN_FLIGHT = new Set(['pending', 'processing', 'finalizing'])

export type ProcessingActivity = {
  parse_status?: string
  last_activity_at?: string | null
}

// stalledMinutes 返回一个在途文档多久没有进展；正在推进、已完成、或活动时间未知时返回 0。
export function stalledMinutes(item: ProcessingActivity, now: number = Date.now()): number {
  if (!IN_FLIGHT.has(item.parse_status ?? '') || !item.last_activity_at) return 0
  const last = Date.parse(item.last_activity_at)
  if (Number.isNaN(last)) return 0
  const idle = now - last
  return idle >= PROCESSING_STALL_THRESHOLD_MS ? Math.floor(idle / 60000) : 0
}

export type StallVerdict = 'queued' | 'stalled' | ''

// shownStall 是「要显示出来的」卡住判定：取服务端的 stall_state，且只在文档确实
// 静默超过阈值时。没有判定（没下发，或服务端探不了队列）⇒ 当成普通处理中显示：
// 把一条只是排队积压的文档说成卡住，会诱使用户去停掉它。
export function shownStall(stallState: string | undefined, minutes: number | undefined): StallVerdict {
  if (!minutes) return ''
  return stallState === 'queued' || stallState === 'stalled' ? stallState : ''
}
