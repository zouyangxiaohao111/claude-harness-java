import { describe, expect, it } from 'vitest'
import {
  PROCESSING_STALL_THRESHOLD_MS,
  STALLED_POLL_INTERVAL_MS,
  shownStall,
  stalledMinutes,
} from '../knowledgeProcessingStall'

// 逐字移植源站 utils/knowledgeProcessingStall.ts 的判据。
// 只把在途（pending/processing/finalizing）且静默超过阈值的文档标成「可能卡住」；
// 没有服务端判定时按普通处理中显示 —— 把排队积压说成卡住会诱使用户去停掉健康解析。

const NOW = Date.parse('2026-11-12T12:00:00.000Z')

/** 距 NOW 之前 minutes 分钟的时间串（负数 = 未来）。 */
function ago(minutes: number): string {
  return new Date(NOW - minutes * 60_000).toISOString()
}

describe('常量', () => {
  it('阈值 20 分钟、卡住时的轮询间隔 15 秒', () => {
    expect(PROCESSING_STALL_THRESHOLD_MS).toBe(20 * 60 * 1000)
    expect(STALLED_POLL_INTERVAL_MS).toBe(15 * 1000)
  })
})

describe('stalledMinutes', () => {
  it('在途三种状态超过阈值 ⇒ 返回向下取整的分钟数', () => {
    for (const status of ['pending', 'processing', 'finalizing']) {
      expect(stalledMinutes({ parse_status: status, last_activity_at: ago(20) }, NOW)).toBe(20)
      expect(stalledMinutes({ parse_status: status, last_activity_at: ago(25.5) }, NOW)).toBe(25)
    }
  })

  it('刚好到阈值就报（判据是 idle >= 阈值）', () => {
    expect(stalledMinutes({ parse_status: 'processing', last_activity_at: ago(19.99) }, NOW)).toBe(0)
    expect(stalledMinutes({ parse_status: 'processing', last_activity_at: ago(20) }, NOW)).toBe(20)
  })

  it('非在途状态 ⇒ 0（已完成/失败/取消的文档再旧也不算卡住）', () => {
    for (const status of ['completed', 'failed', 'cancelled', '', undefined]) {
      expect(stalledMinutes({ parse_status: status, last_activity_at: ago(120) }, NOW)).toBe(0)
    }
  })

  it('活动时间缺失或非法 ⇒ 0（未知不等于卡住）', () => {
    expect(stalledMinutes({ parse_status: 'processing' }, NOW)).toBe(0)
    expect(stalledMinutes({ parse_status: 'processing', last_activity_at: null }, NOW)).toBe(0)
    expect(stalledMinutes({ parse_status: 'processing', last_activity_at: '' }, NOW)).toBe(0)
    expect(stalledMinutes({ parse_status: 'processing', last_activity_at: 'nope' }, NOW)).toBe(0)
  })

  it('活动时间在未来 ⇒ 0（时钟偏差不能产出负分钟）', () => {
    expect(stalledMinutes({ parse_status: 'processing', last_activity_at: ago(-30) }, NOW)).toBe(0)
  })

  it('不传 now 时默认取 Date.now()', () => {
    const justNow = new Date().toISOString()
    expect(stalledMinutes({ parse_status: 'processing', last_activity_at: justNow })).toBe(0)
  })
})

describe('shownStall · 要显示出来的判定', () => {
  it('没有分钟数 ⇒ 空判定（0 或 undefined 都不显示）', () => {
    expect(shownStall('stalled', 0)).toBe('')
    expect(shownStall('stalled', undefined)).toBe('')
    expect(shownStall('queued', 0)).toBe('')
  })

  it('服务端说 queued / stalled 且有分钟数 ⇒ 原样显示', () => {
    expect(shownStall('stalled', 25)).toBe('stalled')
    expect(shownStall('queued', 21)).toBe('queued')
  })

  it('服务端没给判定 / 给了别的值 ⇒ 空（按普通处理中显示）', () => {
    // WHY：把排队积压的文档说成卡住，会诱使用户去停掉一条健康的解析。
    expect(shownStall(undefined, 25)).toBe('')
    expect(shownStall('', 25)).toBe('')
    expect(shownStall('running', 25)).toBe('')
    expect(shownStall('done', 25)).toBe('')
  })
})
