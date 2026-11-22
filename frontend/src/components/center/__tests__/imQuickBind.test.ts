import { describe, expect, it, vi } from 'vitest'
import { quickBindTakeoverMessage, runQuickBind } from '../imQuickBind'

/**
 * [快速绑定] 抢占确认。
 *
 * <p>WHY：点一下「快速绑定」后端就改绑默认会话；若它已经接在别的任务上，
 * 不确认就等于静默抢走别人的接线。这条规则必须只有一处（App 调它）。
 */
describe('[快速绑定] 抢占确认文案', () => {
  it('已绑在别的任务 ⇒ 提示「已被 xxx 占用，切换会解绑其他任务」', () => {
    const msg = quickBindTakeoverMessage(
      { found: true, chatId: 'c1', boundSessionId: 'sess-old', boundSessionTitle: '旧任务' },
      'sess-now',
    )
    expect(msg).toBe('该渠道会话已被「旧任务」占用，切换会解绑其他任务，确定切换吗？')
  })

  it('没有任务标题 ⇒ 回落到会话 id（不编造名字）', () => {
    expect(quickBindTakeoverMessage(
      { found: true, chatId: 'c1', boundSessionId: 'sess-old' },
      'sess-now',
    )).toContain('「sess-old」')
  })

  it('已绑在当前会话 ⇒ 无需确认（重复绑定幂等）', () => {
    expect(quickBindTakeoverMessage(
      { found: true, chatId: 'c1', boundSessionId: 'sess-now', boundSessionTitle: '当前任务' },
      'sess-now',
    )).toBeNull()
  })

  it('未绑定 / 没有可选会话 ⇒ 无需确认', () => {
    expect(quickBindTakeoverMessage({ found: true, chatId: 'c1' }, 'sess-now')).toBeNull()
    expect(quickBindTakeoverMessage({ found: false, message: '先私聊机器人一句' }, 'sess-now')).toBeNull()
    expect(quickBindTakeoverMessage(null, 'sess-now')).toBeNull()
  })
})

/**
 * 快速绑定的完整流程：先 preview，再决定「提示 / 直绑 / 确认 / 报错」。
 * 这些用例覆盖「不写库」的边界 —— 预览没通过时 bind 一次都不能被调。
 */
describe('[快速绑定] 流程分支', () => {
  const deps = (over: Partial<{
    preview: () => Promise<{ found: boolean; chatId?: string; boundSessionId?: string; boundSessionTitle?: string }>
    bind: () => Promise<{ chatName: string }>
    confirm: (msg: string) => Promise<boolean>
  }> = {}) => {
    const bind = vi.fn(over.bind ?? (async () => ({ chatName: '张三' })))
    const confirm = vi.fn(over.confirm ?? (async (_msg: string) => true))
    const preview = vi.fn(over.preview ?? (async () => ({ found: true, chatId: 'c1' })))
    return { preview, bind, confirm }
  }

  it('未找到可用会话 ⇒ 提示且不调用 bind-default', async () => {
    const d = deps({ preview: async () => ({ found: false }) })
    const r = await runQuickBind('imc-1', 'sess-now', d)

    expect(r.ok).toBe(false)
    expect(r.message).toContain('暂无可用会话')
    expect(d.bind).not.toHaveBeenCalled()
    expect(d.confirm).not.toHaveBeenCalled()
  })

  it('未占用 ⇒ 直接绑定', async () => {
    const d = deps()
    const r = await runQuickBind('imc-1', 'sess-now', d)

    expect(r.ok).toBe(true)
    expect(r.message).toContain('已把「张三」接到当前会话')
    expect(d.confirm).not.toHaveBeenCalled()
    expect(d.bind).toHaveBeenCalledWith('imc-1', 'sess-now')
  })

  it('已被别的任务占用 ⇒ 先弹确认，确定后才绑定', async () => {
    const d = deps({
      preview: async () => ({ found: true, chatId: 'c1', boundSessionId: 'sess-old', boundSessionTitle: '旧任务' }),
      confirm: async () => true,
    })
    const r = await runQuickBind('imc-1', 'sess-now', d)

    expect(d.confirm).toHaveBeenCalledTimes(1)
    expect(d.confirm.mock.calls[0][0]).toContain('切换会解绑其他任务')
    expect(d.bind).toHaveBeenCalledWith('imc-1', 'sess-now')
    expect(r.ok).toBe(true)
  })

  it('已占用但用户取消 ⇒ 不调用 bind-default，也不弹成功提示', async () => {
    const d = deps({
      preview: async () => ({ found: true, chatId: 'c1', boundSessionId: 'sess-old', boundSessionTitle: '旧任务' }),
      confirm: async () => false,
    })
    const r = await runQuickBind('imc-1', 'sess-now', d)

    expect(r.ok).toBe(false)
    expect(r.message).toBeNull()
    expect(d.bind).not.toHaveBeenCalled()
  })

  it('预览失败 ⇒ 明确报错且不绑定', async () => {
    const d = deps({ preview: async () => { throw new Error('network') } })
    const r = await runQuickBind('imc-1', 'sess-now', d)

    expect(r.ok).toBe(false)
    expect(r.message).toContain('无法确认')
    expect(d.bind).not.toHaveBeenCalled()
    expect(d.confirm).not.toHaveBeenCalled()
  })
})
