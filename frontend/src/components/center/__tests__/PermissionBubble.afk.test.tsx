// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { PermissionBubble } from '@/components/center/PermissionBubble'
import type { PermissionRequestItem } from '@/stores/chatStore'

/**
 * AskUserQuestion 的 AFK 倒计时（[V333]）。
 *
 * <p><b>WHY 这组断言必须存在</b>（对齐 CC exe 2.1.328 取证）：
 * <ol>
 *   <li><b>默认不计时</b>：后端不下发 afkTimeoutMs（设置=never）时，卡片必须和改造前完全一致 ——
 *       持久显示、永不自动放行。这条是回归底线。</li>
 *   <li><b>只在阈值内显示剩余秒数</b>：CC {@code showCountdown = 剩余*1000 <= 阈值}，
 *       不是全程倒计时；提前显示会让用户以为一直快到了。</li>
 *   <li><b>超时 = allow，且只提交已答</b>：未作答的问题留空（不替你选），并带
 *       {@code afkTimeoutMs + isByCountdown} 供后端出「没人回答」文案。</li>
 *   <li><b>卸载/隐藏必须停表</b>：计时只在可见卡片上跑；后台补发一次 allow 会把没人确认的
 *       选择题当作用户同意，这是安全事故级的行为。</li>
 * </ol>
 */

let container: HTMLDivElement
let root: Root | null = null

beforeEach(() => {
  (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  container = document.createElement('div')
  document.body.appendChild(container)
})

afterEach(() => {
  if (root) act(() => root!.unmount())
  root = null
  container.remove()
  vi.useRealTimers()
})

function askRequest(extra: Partial<PermissionRequestItem> = {}): PermissionRequestItem {
  return {
    kind: 'message',
    sessionId: 'sess-afk',
    requestId: 'req-afk',
    toolName: 'AskUserQuestion',
    toolInput: {
      questions: [
        {
          question: 'Which library should we use?',
          header: 'Library',
          options: [
            { label: 'date-fns', description: 'Modern date library' },
            { label: 'dayjs', description: 'Lightweight' },
          ],
        },
        {
          question: 'Which runtime?',
          header: 'Runtime',
          options: [
            { label: 'node', description: 'Node.js' },
            { label: 'bun', description: 'Bun' },
          ],
        },
      ],
    },
    ...extra,
  }
}

function render(request: PermissionRequestItem, onDecision: (...args: unknown[]) => void) {
  root = createRoot(container)
  act(() => {
    root!.render(<PermissionBubble request={request} onDecision={onDecision as never} />)
  })
}

describe('[V333] AskUserQuestion AFK 倒计时', () => {
  it('默认（无 afkTimeoutMs）⇒ 不计时、不自动放行、无倒计时文本', () => {
    vi.useFakeTimers()
    const onDecision = vi.fn()
    render(askRequest(), onDecision)

    // 跨过任何「默认 60s」可能的误实现（默认必须完全不计时，而不是换个默认时长）
    act(() => { vi.advanceTimersByTime(90_000) })

    expect(onDecision).not.toHaveBeenCalled()
    expect(container.querySelector('[data-testid="afk-countdown"]')).toBeNull()
  })

  it('阈值内才显示剩余秒数；到点自动 allow 并带 afk 载荷', () => {
    vi.useFakeTimers()
    const onDecision = vi.fn()
    render(askRequest({ afkTimeoutMs: 5000, afkCountdownMs: 2000 }), onDecision)

    // 剩余 5s > 阈值 2s ⇒ 不显示
    expect(container.querySelector('[data-testid="afk-countdown"]')).toBeNull()

    // 走到剩余 1s（t=4000）⇒ 进入阈值，显示
    act(() => { vi.advanceTimersByTime(4000) })
    expect(container.querySelector('[data-testid="afk-countdown"]')?.textContent).toContain('剩余 1 秒')

    // t=5000 到点 ⇒ allow，只提交已答（本题一题没答 ⇒ 空 answers）
    act(() => { vi.advanceTimersByTime(1000) })
    expect(onDecision).toHaveBeenCalledTimes(1)
    const [requestId, decision, answers, , , , , afk] = onDecision.mock.calls[0]
    expect(requestId).toBe('req-afk')
    expect(decision).toBe('allow')
    expect(answers).toEqual({})
    expect(afk).toEqual({ afkTimeoutMs: 5000, isByCountdown: true })
  })

  it('部分作答 + 超时 ⇒ 只提交已答，未答留空（不填默认值）', () => {
    vi.useFakeTimers()
    const onDecision = vi.fn()
    render(askRequest({ afkTimeoutMs: 3000, afkCountdownMs: 1000 }), onDecision)

    // 只勾第一题的第一个选项
    const firstRadio = container.querySelector('input[type="radio"]') as HTMLInputElement
    act(() => { firstRadio.click() })

    act(() => { vi.advanceTimersByTime(3000) })

    expect(onDecision).toHaveBeenCalledTimes(1)
    const [, decision, answers] = onDecision.mock.calls[0]
    expect(decision).toBe('allow')
    expect(answers).toEqual({ 'Which library should we use?': 'date-fns' })
    // 未答的第二题不得出现（更不能出现默认项）
    expect(Object.keys(answers as Record<string, string>)).toHaveLength(1)
  })

  it('卡片卸载后计时器停止，不会在后台补发一次 allow', () => {
    vi.useFakeTimers()
    const onDecision = vi.fn()
    render(askRequest({ afkTimeoutMs: 3000, afkCountdownMs: 1000 }), onDecision)

    act(() => { vi.advanceTimersByTime(2000) })
    act(() => { root!.unmount() })
    root = null

    act(() => { vi.advanceTimersByTime(10_000) })
    expect(onDecision).not.toHaveBeenCalled()
  })

  it('页面切换到隐藏 ⇒ 停表；回到前台也不重启', () => {
    vi.useFakeTimers()
    const onDecision = vi.fn()
    render(askRequest({ afkTimeoutMs: 3000, afkCountdownMs: 1000 }), onDecision)

    act(() => {
      Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' })
      document.dispatchEvent(new Event('visibilitychange'))
    })
    // 恢复可见（不得重启计时）
    act(() => {
      Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' })
      document.dispatchEvent(new Event('visibilitychange'))
    })

    act(() => { vi.advanceTimersByTime(10_000) })
    expect(onDecision).not.toHaveBeenCalled()
  })
})
