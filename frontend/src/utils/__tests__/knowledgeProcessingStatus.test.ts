import { describe, expect, it } from 'vitest'
import {
  parseStatusToTimelineStatus,
  resolveTimelineHeaderStatus,
  shouldShowProcessingError,
} from '../knowledgeProcessingStatus'

/**
 * 逐字移植源站 `utils/knowledgeProcessingStatus.ts` 的判据。
 *
 * 核心语义：最新一次尝试以「知识行」为准 —— 根 span 只描述主管线，
 * 它可能已 done 而异步富化还在跑；历史尝试没有知识行快照，沿用根 span 状态。
 */

describe('parseStatusToTimelineStatus · 知识行状态 → span 词表', () => {
  it('completed → done，processing → running', () => {
    expect(parseStatusToTimelineStatus('completed')).toBe('done')
    expect(parseStatusToTimelineStatus('processing')).toBe('running')
  })

  it('其余状态原样透传（failed / cancelled / pending / finalizing）', () => {
    expect(parseStatusToTimelineStatus('failed')).toBe('failed')
    expect(parseStatusToTimelineStatus('cancelled')).toBe('cancelled')
    expect(parseStatusToTimelineStatus('pending')).toBe('pending')
    expect(parseStatusToTimelineStatus('finalizing')).toBe('finalizing')
  })

  it('缺失 ⇒ 空串，而不是 undefined', () => {
    expect(parseStatusToTimelineStatus(undefined)).toBe('')
    expect(parseStatusToTimelineStatus('')).toBe('')
  })
})

describe('resolveTimelineHeaderStatus · 最新尝试以知识行为准', () => {
  it('最新尝试：知识行 parse_status 优先于根 span', () => {
    // 根 span 已 done，但知识行说还在 processing ⇒ 必须显示 running
    expect(
      resolveTimelineHeaderStatus({
        isLatestAttempt: true,
        parseStatus: 'processing',
        traceStatus: 'done',
      }),
    ).toBe('running')
    // 反向：知识行说完成，根 span 还说 running ⇒ 以知识行为准
    expect(
      resolveTimelineHeaderStatus({
        isLatestAttempt: true,
        parseStatus: 'completed',
        traceStatus: 'running',
      }),
    ).toBe('done')
  })

  it('最新尝试：知识行缺失时回落到根 span', () => {
    expect(
      resolveTimelineHeaderStatus({
        isLatestAttempt: true,
        parseStatus: undefined,
        traceStatus: 'failed',
      }),
    ).toBe('failed')
  })

  it('最新尝试：两边都空 ⇒ 空串', () => {
    expect(resolveTimelineHeaderStatus({ isLatestAttempt: true })).toBe('')
    expect(
      resolveTimelineHeaderStatus({ isLatestAttempt: true, parseStatus: '', traceStatus: '' }),
    ).toBe('')
  })

  it('历史尝试：没有知识行快照，根 span 优先', () => {
    expect(
      resolveTimelineHeaderStatus({
        isLatestAttempt: false,
        parseStatus: 'completed',
        traceStatus: 'running',
      }),
    ).toBe('running')
  })

  it('历史尝试：根 span 缺失时回落到知识行（并做词表映射）', () => {
    expect(
      resolveTimelineHeaderStatus({
        isLatestAttempt: false,
        parseStatus: 'completed',
        traceStatus: undefined,
      }),
    ).toBe('done')
    expect(
      resolveTimelineHeaderStatus({
        isLatestAttempt: false,
        parseStatus: 'processing',
        traceStatus: '',
      }),
    ).toBe('running')
  })
})

describe('shouldShowProcessingError · 队列等重试期间仍可操作', () => {
  it('有错误且（知识行失败 或 根 span 失败）⇒ 显示', () => {
    expect(shouldShowProcessingError(true, 'failed', undefined)).toBe(true)
    expect(shouldShowProcessingError(true, undefined, 'failed')).toBe(true)
    expect(shouldShowProcessingError(true, 'failed', 'failed')).toBe(true)
  })

  it('没错误 ⇒ 不显示（哪怕状态是 failed）', () => {
    expect(shouldShowProcessingError(false, 'failed', 'failed')).toBe(false)
  })

  it('有错误但两边都不是 failed ⇒ 不显示', () => {
    expect(shouldShowProcessingError(true, 'completed', 'done')).toBe(false)
    expect(shouldShowProcessingError(true, 'processing', 'running')).toBe(false)
    expect(shouldShowProcessingError(true, undefined, undefined)).toBe(false)
  })
})
