import { describe, expect, it } from 'vitest'
import { buildCreatePayload } from '../useSchedules'
import type { CreateScheduleRequest } from '@/api/types'

/**
 * §三 会话形态（V306）后的创建载荷判据。
 *
 * <p><b>守什么</b>：<b>只有 A 形态（REUSE）要 sessionId</b> —— B/C/D 三种形态自带会话语义
 * （每次新建 / 建后绑定 / 压根不产生会话）⇒ <b>无活动会话也能建任务</b>
 * （设计稿 ⑩：⛔ 不再置灰「添加自动化」按钮）。
 *
 * <p><b>为什么 A 形态不能覆盖调用方选的会话</b>：设计稿 ③ 明确「绑到哪个会话」是一个
 * 用户可选的下拉（可与当前活动会话不同）⇒ 收口前「用活动会话覆盖调用方 sessionId」的写法
 * 会把任务锚到错误的会话。
 *
 * <p><b>为什么 scope 恒 SESSION</b>：⛔ 不能给 B/C/D 发 `scope=DURABLE` —— 后端
 * `ScheduleController.create` 对 DURABLE 强制要求「能解析出项目根的 sessionId」⇒ 400。
 * 走 SESSION 进 service，由 `ScheduleService.create` 把非 REUSE 形态**归一**为 DURABLE。
 */
describe('buildCreatePayload', () => {
  const base: CreateScheduleRequest = { name: 'n', kind: 'cron', cron: '0 0 * * *' }

  it('A 形态（REUSE）⇒ 带 sessionId，缺省 scope 是 SESSION', () => {
    const p = buildCreatePayload({ ...base, sessionMode: 'REUSE' }, 'sess-1')
    expect(p.scope).toBe('SESSION')
    expect(p.sessionId).toBe('sess-1')
  })

  it('A 形态 ⇒ 尊重用户在下拉里选的会话（⛔ 不被活动会话覆盖）', () => {
    const p = buildCreatePayload({ ...base, sessionMode: 'REUSE', sessionId: 'picked' }, 'sess-1')
    expect(p.sessionId).toBe('picked')
  })

  it('A 形态没选会话 ⇒ 回落活动会话', () => {
    const p = buildCreatePayload({ ...base, sessionMode: 'REUSE' }, 'sess-1')
    expect(p.sessionId).toBe('sess-1')
  })

  it('A 形态既没选也没活动会话 ⇒ 抛（后端 scope=SESSION requires non-empty sessionId ⇒ 注定 400）', () => {
    expect(() => buildCreatePayload({ ...base, sessionMode: 'REUSE' }, null)).toThrow()
    expect(() => buildCreatePayload({ ...base, sessionMode: 'REUSE' }, undefined)).toThrow()
  })

  it('B/C/D 形态 ⇒ 不带 sessionId（形态自带会话语义）', () => {
    for (const mode of ['NEW_EACH', 'NEW_ONCE', 'NONE'] as const) {
      const p = buildCreatePayload({ ...base, sessionMode: mode }, 'sess-1')
      expect(p.sessionMode).toBe(mode)
      expect(p.sessionId).toBeUndefined()
    }
  })

  it('B/C/D 形态 ⇒ 无活动会话也能建（⛔ 不再前置拦截）', () => {
    expect(() => buildCreatePayload({ ...base, sessionMode: 'NEW_EACH' }, null)).not.toThrow()
    expect(() => buildCreatePayload({ ...base, sessionMode: 'NEW_ONCE' }, undefined)).not.toThrow()
    expect(() => buildCreatePayload({ ...base, sessionMode: 'NONE' }, '')).not.toThrow()
  })

  it('B/C/D 形态 ⇒ scope 仍是 SESSION（⛔ 不发 DURABLE：后端 REST 边界会要求能解析项目根的 sessionId）', () => {
    const p = buildCreatePayload({ ...base, sessionMode: 'NEW_EACH' }, 'sess-1')
    expect(p.scope).toBe('SESSION')
  })

  it('未指定形态 ⇒ 按 A（REUSE）处理（后端 SessionMode 缺省同源）', () => {
    const p = buildCreatePayload(base, 'sess-1')
    expect(p.sessionMode).toBe('REUSE')
    expect(p.scope).toBe('SESSION')
    expect(p.sessionId).toBe('sess-1')
  })
})
