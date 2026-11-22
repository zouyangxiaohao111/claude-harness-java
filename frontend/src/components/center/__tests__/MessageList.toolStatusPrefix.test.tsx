// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { ToolCallDto } from '@/api/types'
import { ToolCard } from '@/components/center/MessageList'
import {
  CANCEL_MESSAGE_PREFIX,
  INTERRUPT_MESSAGE_FOR_TOOL_USE,
  REJECT_MESSAGE_PREFIX,
  SUBAGENT_REJECT_MESSAGE_PREFIX,
  classifyToolStatus,
} from '@/utils/toolStatus'

/**
 * 工具卡状态分派 · 对齐 CC `UserToolResultMessage.tsx:40-104`。
 *
 * <h2>WHY（意图验证）</h2>
 * <p>后端把「用户取消 / 用户拒绝 / 工具中断」都作为 `is_error=true` 的 tool_result 存下来。
 * 若前端只读 `isError`，这三类会一律显示成「失败」——用户无法区分「我自己停的」「我拒绝的」
 * 与「工具真的报错」。CC 的判据是<b>内容前缀先于 is_error</b>：
 * 取消 → 拒绝 → 中断 → is_error → 成功。本测试钉住该顺序，并保证前置状态不会被 `isError` 覆盖。
 *
 * <h2>RED teeth（变异自检）</h2>
 * <ul>
 *   <li>删掉 CANCEL/REJECT/INTERRUPT 前缀特判 ⇒ cancelled/rejected/interrupted 三例全红
 *       （落到 error，因为后端对这三类都置 is_error=true）；</li>
 *   <li>把前缀特判挪到 is_error 之后 ⇒ 同样全红；</li>
 *   <li>前缀常量与后端不一致（少一个词）⇒ 对应用例红。</li>
 * </ul>
 */

const SID = 'sess-tool-status'

function tool(patch: Partial<ToolCallDto> = {}): ToolCallDto {
  return {
    id: 't-1',
    name: 'Bash',
    arguments: '{"command":"ls"}',
    result: 'ok',
    isError: false,
    ...patch,
  }
}

let host: HTMLDivElement
let root: Root | null = null

beforeEach(() => {
  (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  host = document.createElement('div')
  document.body.appendChild(host)
})

afterEach(() => {
  act(() => { root?.unmount() })
  root = null
  host.remove()
})

async function render(t: ToolCallDto): Promise<HTMLElement> {
  await act(async () => {
    root = createRoot(host)
    root.render(<ToolCard tool={t} matchedRule={null} sessionId={SID} />)
  })
  const status = host.querySelector<HTMLElement>('.tc-status')
  expect(status, '工具卡必须渲染状态图标').not.toBeNull()
  return status!
}

function expectStatus(el: HTMLElement, kind: string, label: string, icon: string): void {
  expect(el.classList.contains(kind), `状态类应为 ${kind}，实际 ${el.className}`).toBe(true)
  expect(el.getAttribute('aria-label')).toBe(label)
  expect(el.textContent).toBe(icon)
}

describe('ToolCard 状态分派（前缀先于 is_error · 对齐 CC）', () => {
  it('取消文案 → cancelled（即使后端 is_error=true）', async () => {
    const el = await render(tool({
      result: `${CANCEL_MESSAGE_PREFIX} STOP what you are doing and wait for the user to tell you how to proceed.`,
      isError: true,
    }))
    expectStatus(el, 'cancelled', '已取消', '⊘')
  })

  it('拒绝文案 → rejected（用户拒绝，不是工具失败）', async () => {
    const el = await render(tool({
      result: `${REJECT_MESSAGE_PREFIX} The tool use was rejected (eg. if it was a file edit, the new_string was NOT written to the file).`,
      isError: true,
    }))
    expectStatus(el, 'rejected', '已拒绝', '⊘')
  })

  it('子代理拒绝文案 → rejected', async () => {
    const el = await render(tool({
      result: `${SUBAGENT_REJECT_MESSAGE_PREFIX} The tool use was rejected (eg. if it was a file edit, the new_string was NOT written to the file).`,
      isError: true,
    }))
    expectStatus(el, 'rejected', '已拒绝', '⊘')
  })

  it('工具中断文案 → interrupted（逐字相等，对齐 CC）', async () => {
    const el = await render(tool({ result: INTERRUPT_MESSAGE_FOR_TOOL_USE, isError: true }))
    expectStatus(el, 'interrupted', '已中断', '⊘')
  })

  it('普通错误 → error（is_error 兜底档）', async () => {
    const el = await render(tool({ result: 'Exit code 1\nboom', isError: true }))
    expectStatus(el, 'error', '失败', '✗')
  })

  it('成功 → done（现有文案与图标不变）', async () => {
    const el = await render(tool({ result: 'ok', isError: false }))
    expectStatus(el, 'done', '已完成', '✓')
  })

  it('分类函数是唯一入口：空结果 + 非 live = interrupted（孤儿卡不永久转圈）', () => {
    expect(classifyToolStatus({ result: null, isError: false, live: false })).toBe('interrupted')
    expect(classifyToolStatus({ result: null, isError: false, live: true })).toBe('running')
    expect(classifyToolStatus({ result: '', isError: false })).toBe('done')
  })
})
