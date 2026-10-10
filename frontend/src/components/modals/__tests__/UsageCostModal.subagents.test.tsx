// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { UsageCostModal } from '../UsageCostModal'
import { useChatStore } from '@/stores/chatStore'
import { useSubagentStore } from '@/stores/subagentStore'

/**
 * D2 · 用量弹窗「子代理（本会话）」区块（**只新增**，既有区块一字不动）。
 *
 * <h2>WHY（规则九 · 意图而非行为）</h2>
 * <p>用户裁定原话：「只能添加 不能删除现有的 弹窗 只能添加这个功能」。所以本组用例两条腿：
 * ①<b>新区块存在且数据对</b>（每行 = 名字 · 状态 · tokens · 调用次数 · 时长；无记录 → 空态）；
 * ②<b>既有区块仍在</b>（当前会话 / 按模型明细 / 所有会话）—— 防「新增区块时顺手动了老内容」。
 *
 * <h2>RED（改坏哪条红）</h2>
 * <ul>
 *   <li>删掉区块（或只在有数据时渲染空态以外的东西）→ 「无记录 → 暂无子代理记录」红；</li>
 *   <li>把失败原因无条件渲染（占位 '…' 也渲）→ 「无原因文本 → 不显示那行小字」红；</li>
 *   <li>改动/删除既有区块 → 「既有区块仍在」红。</li>
 * </ul>
 */

vi.mock('@/api/sessions', () => ({ sessionApi: { list: vi.fn(() => Promise.resolve([])) } }))
vi.mock('@/api/stats', () => ({ statsApi: { get: vi.fn(() => Promise.resolve(null)) } }))

const SID = 'sess-d2'

async function flush(): Promise<void> {
  await act(async () => { await Promise.resolve(); await Promise.resolve() })
}

describe('D2 · UsageCostModal 子代理（本会话）区块', () => {
  let container: HTMLDivElement
  let root: Root

  beforeEach(() => {
    ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    localStorage.clear()
    useSubagentStore.setState({ sessionId: null, bySession: {} })
    useChatStore.setState({ messages: {}, streams: {}, sessions: [] })
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
  })

  afterEach(() => {
    act(() => { root.unmount() })
    container.remove()
  })

  async function mount(): Promise<void> {
    await act(async () => {
      root.render(<UsageCostModal activeSessionId={SID} onSwitch={() => {}} onClose={() => {}} />)
    })
    await flush()
  }

  it('无记录 → 区块存在 + 空态「暂无子代理记录」（且既有区块仍在 —— 只加不删）', async () => {
    await mount()
    expect(container.textContent).toContain('子代理（本会话）')
    expect(container.textContent).toContain('暂无子代理记录')
    // 只加不删：既有区块逐条仍在
    expect(container.textContent).toContain('当前会话')
    expect(container.textContent).toContain('所有会话')
  })

  it('有记录 → 每行 = 名字 · 状态 · tokens · 调用次数 · 时长（数据源 = task 事件 usage）', async () => {
    useSubagentStore.getState().register('tu-1', 't-1', 'alice', 'local_agent', SID)
    useSubagentStore.getState().setUsage('t-1', { totalTokens: 33600, toolUses: 5, durationMs: 39500 }, SID)
    await mount()

    const text = container.textContent ?? ''
    expect(text).toContain('@alice')
    expect(text).toContain('运行中')
    expect(text).toContain('33.6k tokens · 5 次调用 · 39.5s')
    expect(container.querySelectorAll('.uc-subagent-item').length).toBe(1)
  })

  it('失败行显示失败原因（有原因文本才显示那行小字）', async () => {
    useSubagentStore.getState().register(null, 't-1', 'alice', 'local_agent', SID)
    useSubagentStore.getState().addActivity('t-1', { type: 'failed', text: '权限被拒：Write 被 deny', ts: 1 }, SID)
    await mount()

    expect(container.textContent).toContain('失败')
    expect(container.querySelector('.uc-subagent-reason')?.textContent).toContain('权限被拒：Write 被 deny')
  })

  it('失败但无原因文本（占位 …）→ 不显示原因那行小字（不得渲染占位符）', async () => {
    useSubagentStore.getState().register(null, 't-1', 'alice', 'local_agent', SID)
    useSubagentStore.getState().addActivity('t-1', { type: 'failed', text: '…', ts: 1 }, SID)
    await mount()

    expect(container.textContent).toContain('失败')
    expect(container.querySelector('.uc-subagent-reason')).toBeNull()
  })

  it('无统计数据的行显示「统计不可用」（不得渲染空白行）', async () => {
    useSubagentStore.getState().register(null, 't-1', 'alice', 'local_agent', SID)
    await mount()
    expect(container.textContent).toContain('统计不可用')
  })

  it('[F1] 冷启动（localStorage JSON 往返）后同一 @名字只一行 —— 不得出现 live+陈旧两份', async () => {
    // 盘上形态：同一身份被 JSON 写成两份（taskId / toolUseId 两键各一份副本）
    const identity = {
      name: 'alice', color: '#000', taskType: 'local_agent', taskId: 't-1', status: 'done',
      currentTool: null, usage: { totalTokens: 100, toolUses: 2, durationMs: 5000 },
      activities: [{ type: 'start', text: 'alice', ts: 1 }],
    }
    localStorage.setItem('nexusai-subagents', JSON.stringify({ [SID]: { 't-1': identity, 'tu-1': { ...identity } } }))
    vi.resetModules()
    const freshModal = await import('../UsageCostModal')   // 与 fresh store 同模块图（冷启动）
    await act(async () => {
      root.render(<freshModal.UsageCostModal activeSessionId={SID} onSwitch={() => {}} onClose={() => {}} />)
    })
    await flush()

    expect(container.querySelectorAll('.uc-subagent-item').length).toBe(1)
    expect((container.textContent ?? '').split('@alice').length - 1).toBe(1)
    localStorage.removeItem('nexusai-subagents')
    vi.resetModules()
  })

  it('只列本会话的子代理（别的会话不串台）', async () => {
    useSubagentStore.getState().register(null, 't-1', 'alice', 'local_agent', SID)
    useSubagentStore.getState().register(null, 't-9', 'carol', 'local_agent', 'sess-other')
    await mount()
    expect(container.textContent).toContain('@alice')
    expect(container.textContent).not.toContain('@carol')
  })
})
