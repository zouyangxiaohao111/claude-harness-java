// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { failureReasonOf, subagentListOfSession, useSubagentStore } from '../subagentStore'

/** 与 store 内 STORAGE_KEY 同值（loadSaved 用例需要预置 localStorage）。 */
const STORAGE_KEY = 'nexusai-subagents'

/**
 * subagentStore · 统计字段（D2 用量弹窗 / D3 任务卡片的数据源）。
 *
 * <h2>WHY（规则九 · 意图而非行为）</h2>
 * <p>底层事件（/topic/tasks）本来就带 {@code usage{total_tokens, tool_uses, duration_ms}}，但 store
 * 此前只存「身份 + 活动时间线」→ 卡片和弹窗都没有 token/调用次数/时长可显示（这就是 D2/D3 的缺口）。
 * 本组用例钉住：usage 落到 identity 上、别名键（toolUseId/taskId 指向同一 identity）同步、
 * 未知任务 no-op 不凭空造身份、**终态幂等不被统计写入破坏**、会话取清单要按 identity 去重。
 *
 * <h2>RED（改坏哪条红）</h2>
 * <ul>
 *   <li>setUsage 只改 {@code session[taskId]} 不同步别名键 → 「别名键同步」红（点卡片时查到的是旧对象，
 *       stats 恒空）；</li>
 *   <li>subagentListOfSession 不去重（直接 Object.values）→ 「同一 identity 只出现一行」红
 *       （register 把同一对象挂在 toolUseId 与 taskId 两个键下）；</li>
 *   <li>failureReasonOf 把占位 '…' 当原因返回 → 「无原因文本 → null」红（弹窗会多渲染一行 '…'）。</li>
 * </ul>
 */
describe('subagentStore · usage 统计（D2/D3 数据源）', () => {
  beforeEach(() => {
    localStorage.clear()
    useSubagentStore.setState({ sessionId: null, bySession: {} })
  })

  it('register 初始无统计（usage 缺省 —— 统计行显示「统计不可用」而不是 0）', () => {
    useSubagentStore.getState().register('tu-1', 't-1', 'alice', 'local_agent', 's1')
    const id = useSubagentStore.getState().bySession['s1']?.['t-1']
    expect(id?.usage ?? null).toBeNull()
  })

  it('setUsage 写入统计（camel 化）+ 同步别名键（toolUseId / taskId 两个键取到同一个带统计的对象）', () => {
    useSubagentStore.getState().register('tu-1', 't-1', 'alice', 'local_agent', 's1')
    useSubagentStore.getState().setUsage('t-1', { totalTokens: 33600, toolUses: 5, durationMs: 39500 }, 's1')

    const byTaskId = useSubagentStore.getState().bySession['s1']?.['t-1']
    const byToolUseId = useSubagentStore.getState().bySession['s1']?.['tu-1']
    expect(byTaskId?.usage).toEqual({ totalTokens: 33600, toolUses: 5, durationMs: 39500 })
    expect(byToolUseId?.usage).toEqual({ totalTokens: 33600, toolUses: 5, durationMs: 39500 })
  })

  it('setUsage 更新不清空已有身份字段、不改运行态（统计与身份是两条独立的写路径）', () => {
    useSubagentStore.getState().register('tu-1', 't-1', 'alice', 'local_agent', 's1')
    useSubagentStore.getState().setUsage('t-1', { totalTokens: 1 }, 's1')
    const id = useSubagentStore.getState().bySession['s1']?.['t-1']
    expect(id?.name).toBe('alice')
    expect(id?.status).toBe('running')
    expect(id?.activities.length).toBe(1)
  })

  it('setUsage 未知任务 → no-op（不崩、不凭空新建身份）', () => {
    useSubagentStore.getState().register('tu-1', 't-1', 'alice', 'local_agent', 's1')
    useSubagentStore.getState().setUsage('不存在', { totalTokens: 1 }, 's1')
    expect(Object.keys(useSubagentStore.getState().bySession['s1'] ?? {})).toEqual(['t-1', 'tu-1'])
  })

  it('subagentListOfSession 取本会话清单并按 identity 去重（toolUseId/taskId 别名只出一行）', () => {
    useSubagentStore.getState().register('tu-1', 't-1', 'alice', 'local_agent', 's1')
    useSubagentStore.getState().register(null, 't-2', 'bob', 'local_agent', 's1')
    useSubagentStore.getState().register(null, 't-9', 'carol', 'local_agent', 's2')   // 别的会话
    const s1 = subagentListOfSession(useSubagentStore.getState().bySession, 's1')
    expect(s1.map((i) => i.name)).toEqual(['alice', 'bob'])
    expect(subagentListOfSession(useSubagentStore.getState().bySession, null)).toEqual([])
  })

  it('[R3-4] onlyIfRunning：终态身份不被进度值覆盖（终态值权威）；运行中身份照常写入', () => {
    useSubagentStore.getState().register(null, 't-1', 'alice', 'local_agent', 's1')
    useSubagentStore.getState().setUsage('t-1', { totalTokens: 33600 }, 's1')            // 终态前的进度值
    useSubagentStore.getState().addActivity('t-1', { type: 'done', text: 'ok', ts: 1 }, 's1')
    useSubagentStore.getState().setUsage('t-1', { totalTokens: 999 }, 's1', { onlyIfRunning: true })
    expect(useSubagentStore.getState().bySession['s1']?.['t-1']?.usage)
      .toEqual({ totalTokens: 33600 })

    // 运行中身份：进度值正常覆盖
    useSubagentStore.getState().register(null, 't-2', 'bob', 'local_agent', 's1')
    useSubagentStore.getState().setUsage('t-2', { totalTokens: 1 }, 's1', { onlyIfRunning: true })
    expect(useSubagentStore.getState().bySession['s1']?.['t-2']?.usage).toEqual({ totalTokens: 1 })
  })

  it('[R3-3] 实时事件清 restored 标志（setUsage / addActivity 均清）', () => {
    useSubagentStore.setState({
      sessionId: null,
      bySession: {
        s1: {
          't-1': {
            name: 'alice', color: '#000', taskId: 't-1', status: 'running', currentTool: null,
            restored: true, activities: [{ type: 'start', text: 'alice', ts: 1 }],
          },
        },
      },
    })
    useSubagentStore.getState().setUsage('t-1', { totalTokens: 5 }, 's1')
    expect(useSubagentStore.getState().bySession['s1']?.['t-1']?.restored).toBe(false)

    useSubagentStore.setState({
      sessionId: null,
      bySession: {
        s1: {
          't-1': {
            name: 'alice', color: '#000', taskId: 't-1', status: 'running', currentTool: null,
            restored: true, activities: [{ type: 'start', text: 'alice', ts: 1 }],
          },
        },
      },
    })
    useSubagentStore.getState().addActivity('t-1', { type: 'progress', text: 'x', ts: 2 }, 's1')
    expect(useSubagentStore.getState().bySession['s1']?.['t-1']?.restored).toBe(false)
  })

  it('[R3-3] loadSaved 冷启动恢复：running 身份打 restored=true，终态身份不打', async () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({
      s1: {
        't-run': { name: 'run', color: '#000', taskId: 't-run', status: 'running', currentTool: null, activities: [] },
        't-done': { name: 'done', color: '#000', taskId: 't-done', status: 'done', currentTool: null, activities: [] },
      },
    }))
    // 重新加载模块 → 触发 loadSaved（模块初始化时执行）
    vi.resetModules()
    const fresh = await import('../subagentStore')
    const session = fresh.useSubagentStore.getState().bySession['s1']
    expect(session?.['t-run']?.restored).toBe(true)
    expect(session?.['t-done']?.restored ?? false).toBe(false)
    localStorage.removeItem(STORAGE_KEY)
    vi.resetModules()
  })

  it('[F1] 冷启动别名重建（该 bug 的成因）：register 落盘两份 JSON 副本 ⇒ 重建后两键必须共享一个对象', async () => {
    // 真链复现：register 把同一对象挂 taskId/toolUseId 两键 → persist 是 JSON.stringify
    //   （JSON 无引用语义 ⇒ 同内容写两份；实测盘上确有两份，见报告证据）
    useSubagentStore.getState().register('tu-1', 't-1', 'alice', 'local_agent', 's1')

    // 冷启动：loadSaved 必须按 taskId 归组重别名（否则两键分叉 ⇒ 弹窗重复行 + 别名不再自愈）
    vi.resetModules()
    const fresh = await import('../subagentStore')
    const session = fresh.useSubagentStore.getState().bySession['s1'] ?? {}
    expect(session['t-1']).toBe(session['tu-1'])                 // 同一对象（===，非内容相等）
    // 弹窗数据源去重后只出一行（重复行症状的直接判据）
    expect(subagentListOfSession(fresh.useSubagentStore.getState().bySession, 's1').length).toBe(1)
    localStorage.removeItem(STORAGE_KEY)
    vi.resetModules()
  })

  it('[F1] 冷启动后别名仍自愈：更新经 toolUseId 键写入时 taskId 键同步（不再永停旧对象）', async () => {
    useSubagentStore.getState().register('tu-1', 't-1', 'alice', 'local_agent', 's1')
    vi.resetModules()
    const fresh = await import('../subagentStore')
    fresh.useSubagentStore.getState().addActivity('tu-1', { type: 'progress', text: 'x', ts: 2 }, 's1')
    const session = fresh.useSubagentStore.getState().bySession['s1'] ?? {}
    expect(session['t-1']?.activities.length).toBe(session['tu-1']?.activities.length)
    expect(session['t-1']?.activities.length).toBe(2)
    localStorage.removeItem(STORAGE_KEY)
    vi.resetModules()
  })

  it('failureReasonOf：取最后一条 failed 活动文本；占位 … / 空 → null（不显示那行小字）', () => {
    useSubagentStore.getState().register(null, 't-1', 'alice', 'local_agent', 's1')
    useSubagentStore.getState().addActivity('t-1', { type: 'failed', text: '权限被拒：Write 工具被 deny', ts: 1 }, 's1')
    expect(failureReasonOf(useSubagentStore.getState().bySession['s1']!['t-1']!))
      .toBe('权限被拒：Write 工具被 deny')

    useSubagentStore.getState().register(null, 't-2', 'bob', 'local_agent', 's1')
    useSubagentStore.getState().addActivity('t-2', { type: 'failed', text: '…', ts: 1 }, 's1')
    expect(failureReasonOf(useSubagentStore.getState().bySession['s1']!['t-2']!)).toBeNull()

    useSubagentStore.getState().register(null, 't-3', 'carol', 'local_agent', 's1')
    expect(failureReasonOf(useSubagentStore.getState().bySession['s1']!['t-3']!)).toBeNull()
  })
})
