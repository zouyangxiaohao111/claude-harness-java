import { describe, expect, it } from 'vitest'
import { cronToHuman } from '../SchedulesPanel'

/**
 * cron-6field-contract：CronCreate 落库的是 6 字段 Quartz 串（秒 分 时 日 月 周）；
 * 日+周双约束时后端存 `||` 连接的变体串（任一变体匹配即触发）。
 *
 * WHY：这些断言钉死的是「后端契约文本 → 人话」的映射语义，而不是字符串本身——
 * dow=1 必须是周日（Quartz 编号），改成 ISO 的 1=周一 会让每月 1 日 且 周一 的任务
 * 在 UI 上显示成「周日」，用户据此判断任务是否写错会得出相反结论。
 */
describe('cronToHuman · 6 字段 Quartz + || 变体', () => {
  it('|| 双约束变体（每月 1 日 + 每周一）→「或」拼接两侧文案', () => {
    expect(cronToHuman('0 0 9 1 * ?||0 0 9 ? * 2')).toBe('每月 1 日 09:00 或 每周一 09:00')
  })

  it('dow 区间 2-6 → 周一至周五', () => {
    expect(cronToHuman('0 0 9 ? * 2-6')).toBe('每周一至周五 09:00')
  })

  it('dow = 1 → 周日（Quartz 编号；防按 ISO 改成「周一」）', () => {
    expect(cronToHuman('0 0 9 ? * 1')).toBe('每周日 09:00')
  })

  it('回归：每 N 分钟', () => {
    expect(cronToHuman('0 */5 * ? * *')).toBe('每 5 分钟')
  })

  it('回归：每天 HH:mm', () => {
    expect(cronToHuman('0 0 9 * * ?')).toBe('每天 09:00')
  })

  it('回归：每月 N 日 HH:mm', () => {
    expect(cronToHuman('0 0 9 1 * ?')).toBe('每月 1 日 09:00')
  })

  it('dow 逗号列表 2,6 → 未识别回退（不支持组合，不瞎猜）', () => {
    expect(cronToHuman('0 0 9 ? * 2,6')).toContain('（未识别调度）')
  })

  it('|| 一侧未识别 → 整体回退原文', () => {
    expect(cronToHuman('0 0 9 1 * ?||乱码')).toContain('（未识别调度）')
  })
})
