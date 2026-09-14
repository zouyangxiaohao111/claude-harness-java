import { describe, expect, it } from 'vitest'
import { describeIncludeStatusProbeFailure } from '../claudeMd'
import { ApiError } from '../rest'

/**
 * [T15-3] `getIncludeStatus` 探测失败的**分流**（⛔ 不一律静默）。
 *
 * WHY（规则九）：后端审批态按项目分区后，本端点缺 sessionId / 解析不到项目根一律 400
 * （(a) 类「本该有却没有」，按用户裁定「每个会话一定有绑定目录，查不到就是严重 bug」）。
 * 若前端把 400 静默吞掉，该缺陷**结构性不可见**（不弹窗 + 无 toast + 无日志）—— 正是本批要消灭的
 * 失败模式；而网络层错（后端未就绪）属 (b) 类「本就不需要」⇒ 必须静默以不阻塞启动。
 *
 * 反向实验：令 `describeIncludeStatusProbeFailure` 一律 `return null`（= 旧的一律静默）
 * ⇒ 本文件 400 / 5xx / 非 ApiError 三个用例翻红。
 */
describe('[T15-3] describeIncludeStatusProbeFailure 分流', () => {
  it('HTTP 400（会话无绑定项目）⇒ 必须告警（⛔ 不得静默）', () => {
    const msg = describeIncludeStatusProbeFailure(new ApiError('no bound project', { status: 400 }), 'sess-a')
    expect(msg).not.toBeNull()
    expect(msg).toContain('status=400')
    expect(msg).toContain('sess-a')
    expect(msg).toContain('会话无绑定项目')
  })

  it('其余非 2xx（如 500 引擎未接线）⇒ 必须告警', () => {
    const msg = describeIncludeStatusProbeFailure(new ApiError('boom', { status: 500 }), 'sess-a')
    expect(msg).not.toBeNull()
    expect(msg).toContain('status=500')
    expect(msg).toContain('非 2xx')
  })

  it('非 ApiError 异常 ⇒ 必须告警（不静默）', () => {
    expect(describeIncludeStatusProbeFailure(new Error('weird'), 'sess-a')).toContain('非 ApiError')
    expect(describeIncludeStatusProbeFailure('raw string', 'sess-a')).not.toBeNull()
  })

  it('网络层错（ApiError.status === 0，后端未就绪）⇒ 静默（(b) 类，不阻塞启动）', () => {
    const netErr = new ApiError('Network error: fetch failed', { status: 0, title: 'Network Error' })
    expect(describeIncludeStatusProbeFailure(netErr, 'sess-a')).toBeNull()
  })
})
