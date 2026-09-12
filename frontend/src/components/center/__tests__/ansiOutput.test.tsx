// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { MessageList } from '../MessageList'
import { useChatStore } from '@/stores/chatStore'
import type { ChatMessageDto } from '@/api/types'

/**
 * [工具卡折叠回归] AnsiOutput 的「前 8 + 后 8」折叠 / 展开 / 收起。
 *
 * <b>WHY 这组断言必须存在</b>：按钮显隐门控必须是 `hidden > 0`，<b>不能</b>用 headTailCap 的
 * `capped`（= `hidden > 0 && !expanded`）。二者只在<b>展开态</b>分叉 —— 用 `capped` 时展开后
 * 按钮随 `capped=false` 一起消失，卡片<b>永久收不回</b>；而此刻头段恰好渲染全量，所以
 * 「行数 = N」这类断言<b>照样通过</b>（反向实验已证实：把门控改成 `capped`，全量 204 例仍全绿）。
 * 因此本文件真正钉住该缺陷的是两条 ——【展开后按钮仍在】与【能再收起】。
 *
 * 走 MessageList 公开组件驱动真实渲染路径（同目录 MessageList.scroll.test.tsx 的既有做法）：
 * AnsiOutput 是模块私有组件、不从 MessageList 导出，故经「工具卡默认折叠 → 点开」抵达。
 */

const SID = 'sess-ansi'

/** 最小 ChatMessageDto（同 MessageList.scroll.test.tsx 的既有写法，避免每例重复造全字段）。 */
function baseMsg(id: string, role: 'user' | 'assistant', sessionId: string): ChatMessageDto {
  return {
    id, sessionId, role, author: role === 'user' ? '你' : 'nexus', content: '',
    reasoning: null, toolCalls: null, finishReason: null, inputTokens: null, outputTokens: null,
    reasoningDurationMs: null, time: null, toolCallId: null, assistantMessageId: null,
    userMessageId: null, subtype: null, isMeta: false, isApiErrorMessage: false, apiError: null,
    error: null, errorDetails: null, matchedRule: null,
  }
}

/** N 行 `line-0..line-(N-1)` 的工具输出。刻意不带末尾换行：行数即 N，末尾空行裁剪不介入。 */
function outputLines(n: number): string {
  return Array.from({ length: n }, (_, i) => `line-${i}`).join('\n')
}

/** 一条带 Bash 工具调用的助手消息（AnsiOutput 在 ToolCard 的 OUT 区）。 */
function assistantToolMsg(id: string, result: string): ChatMessageDto {
  return {
    ...baseMsg(id, 'assistant', SID),
    toolCalls: [{ id: `t-${id}`, name: 'Bash', arguments: '', result, isError: false }],
  }
}

describe('AnsiOutput 工具卡折叠（前 8 + 后 8）', () => {
  let container: HTMLDivElement
  let root: Root

  beforeEach(() => {
    (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
  })

  afterEach(() => {
    act(() => { root.unmount() })
    container.remove()
  })

  const rows = () => Array.from(container.querySelectorAll('.tc-ansi-line')).map((el) => el.textContent ?? '')
  const toggle = () => container.querySelector<HTMLButtonElement>('.tc-ansi-toggle')
  const toggleText = () => toggle()?.textContent ?? null
  const clickToggle = async () => {
    const btn = toggle()
    expect(btn).not.toBeNull()
    await act(async () => { btn!.click() })
  }

  /** 挂载含 N 行工具输出的消息，并点开工具卡（默认折叠 → AnsiOutput 才渲染）。 */
  async function mount(n: number) {
    const msg = assistantToolMsg('m1', outputLines(n))
    useChatStore.setState({
      messages: { [SID]: [msg] }, hasMore: {},
      streams: {}, streamOrder: {}, streamTicks: {}, extendedWindow: {},
    })
    await act(async () => {
      root.render(
        <MessageList sessionId={SID} messages={[msg]} onDelete={() => {}} conversationId={null}
          onLoadOlder={() => {}} onNearBottomChange={() => {}} />,
      )
    })
    const head = container.querySelector<HTMLButtonElement>('.tool-card .head')
    expect(head).not.toBeNull()
    await act(async () => { head!.click() })
  }

  it('n=17 折叠态：16 行 = 前 8（line-0..7）+ 后 8（line-9..16），按钮标出省略 1 行', async () => {
    await mount(17)
    const r = rows()
    expect(r.length).toBe(16)
    expect(r.slice(0, 8)).toEqual(['line-0', 'line-1', 'line-2', 'line-3', 'line-4', 'line-5', 'line-6', 'line-7'])
    expect(r.slice(8)).toEqual(['line-9', 'line-10', 'line-11', 'line-12', 'line-13', 'line-14', 'line-15', 'line-16'])
    // 被省略的正是中间那 1 行；按钮文案里的数字 = hidden（与改造前同值）
    expect(r).not.toContain('line-8')
    expect(toggleText()).toBe('… 展开其余 1 行')
  })

  it('n=17 展开态：全 17 行，且按钮【仍在】并变为「收起」', async () => {
    await mount(17)
    await clickToggle()
    const r = rows()
    expect(r.length).toBe(17)
    expect(r).toEqual(Array.from({ length: 17 }, (_, i) => `line-${i}`))
    // 判别点：门控若误用 capped，此处按钮已随 capped=false 消失 → 卡片永久收不回
    expect(toggle()).not.toBeNull()
    expect(toggleText()).toBe('收起')
  })

  it('n=17 再收起：回到 16 行与「展开其余 1 行」（展开必须可逆）', async () => {
    await mount(17)
    await clickToggle()
    await clickToggle()
    expect(rows().length).toBe(16)
    expect(toggleText()).toBe('… 展开其余 1 行')
  })

  it('n=32 折叠态：hidden=16，尾 8 行取末尾 line-24..31', async () => {
    await mount(32)
    const r = rows()
    expect(r.length).toBe(16)
    expect(r.slice(0, 8)[0]).toBe('line-0')
    expect(r.slice(8)).toEqual(Array.from({ length: 8 }, (_, i) => `line-${24 + i}`))
    expect(toggleText()).toBe('… 展开其余 16 行')
  })

  it('n=32 展开态：32 行且无重复（尾段只在折叠态渲染，不得与头段重叠）', async () => {
    await mount(32)
    await clickToggle()
    const r = rows()
    expect(r.length).toBe(32)
    expect(new Set(r).size).toBe(32)
    expect(toggle()).not.toBeNull()
  })

  it('n=16 折叠态：恰好不超上限 → 16 行全量、无按钮（hidden=0）', async () => {
    await mount(16)
    expect(rows().length).toBe(16)
    expect(toggle()).toBeNull()
  })

  it('n=10 折叠态：小于上限 → 10 行全量、无按钮（hidden<0 不得渲染成「隐藏 -6 行」）', async () => {
    await mount(10)
    expect(rows().length).toBe(10)
    expect(toggle()).toBeNull()
  })
})
