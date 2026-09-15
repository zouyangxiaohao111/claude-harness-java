import { describe, expect, it } from 'vitest'
import { buildCreatePayload } from '../useSchedules'
import type { CreateScheduleRequest } from '@/api/types'

/**
 * §二 scope 选择器的守门测试。
 *
 * <p><b>守什么</b>：`sessionId` 的注入对**两个 scope 都**必须发生。
 * 收口前 SESSION 分支走 `{ ...req, scope }`（不注入 sessionId）—— 只要调用方不自带，
 * 后端 `ScheduleService.create` 就抛 `scope=SESSION requires non-empty 'sessionId'` ⇒ 400。
 * 这条只在「用户能选 SESSION」之后才会被走到，所以是本批新引入路径的**唯一**保险。
 */
describe('buildCreatePayload', () => {
  const base: CreateScheduleRequest = { name: 'n', kind: 'cron', cron: '0 0 * * *' }

  it('SESSION 也必须带活动会话 id（收口前此处不注入 ⇒ 必然 400）', () => {
    const p = buildCreatePayload({ ...base, scope: 'SESSION' }, 'sess-1')
    expect(p.scope).toBe('SESSION')
    expect(p.sessionId).toBe('sess-1')
  })

  it('DURABLE 带活动会话 id', () => {
    const p = buildCreatePayload({ ...base, scope: 'DURABLE' }, 'sess-1')
    expect(p.scope).toBe('DURABLE')
    expect(p.sessionId).toBe('sess-1')
  })

  it('scope 缺省 ⇒ SESSION（对齐 CC durable=false 默认；⛔ 与后端两处缺省同源）', () => {
    expect(buildCreatePayload(base, 'sess-1').scope).toBe('SESSION')
  })

  it('⛔ 调用方自带 sessionId 会被活动会话覆盖（防把任务锚到别的会话/项目）', () => {
    const p = buildCreatePayload({ ...base, scope: 'DURABLE', sessionId: 'stale-other' }, 'sess-1')
    expect(p.sessionId).toBe('sess-1')
  })

  it('无活动会话 ⇒ 两个 scope 都抛（UI 无法从对话框里补救，故前端先拦截）', () => {
    expect(() => buildCreatePayload({ ...base, scope: 'DURABLE' }, null)).toThrow()
    expect(() => buildCreatePayload({ ...base, scope: 'SESSION' }, null)).toThrow()
    expect(() => buildCreatePayload(base, '')).toThrow()
    expect(() => buildCreatePayload(base, undefined)).toThrow()
  })
})
