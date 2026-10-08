// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { DialogOpsModal } from '../DialogOpsModal'
import type { PivotCandidateDto } from '@/api/types'

/**
 * [dialog-ops-pivot] 守门测试：弹窗候选改吃轻量端点数据（candidates/loading/error/onRetry），
 * 不再依赖聊天窗口 storeMessages（本组件此前 0 覆盖 —— 回归当年未被测出的直接原因）。
 *
 * <p>WHY 真实渲染：断言点在「三态渲染 + 预览投影 + 裁剪计数口径」的接线，不在纯函数里。
 * 手法沿用本仓既有 jsdom 自建 harness（Composer.popEditable.test.tsx 同款），不引入新依赖。
 */

const CANDS: PivotCandidateDto[] = [
  { id: 'u1', createdAt: '2026-10-01T00:00:00Z', previewSource: '# 标题一\n内容一', removedAfter: 40 },
  { id: 'u2', createdAt: '2026-10-01T00:01:00Z', previewSource: '内容二', removedAfter: 20 },
  { id: 'u3', createdAt: '2026-10-01T00:02:00Z', previewSource: '内容三', removedAfter: 1 },
]

interface Harness {
  container: HTMLDivElement
  root: Root
  text: () => string
  clickText: (t: string) => void
  clickSelector: (sel: string) => void
  pressEscape: () => void
  onClose: ReturnType<typeof vi.fn>
  onRetry: ReturnType<typeof vi.fn>
  onTrim: ReturnType<typeof vi.fn>
  onCompact: ReturnType<typeof vi.fn>
}

let currentRoot: Root | null = null

function mount(overrides: Partial<{ loading: boolean; error: string | null; candidates: PivotCandidateDto[]; initialTab: 'compact' | 'trim' }> = {}): Harness {
  ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  const container = document.createElement('div')
  document.body.appendChild(container)
  const root = createRoot(container)
  const onClose = vi.fn()
  const onRetry = vi.fn()
  const onTrim = vi.fn()
  const onCompact = vi.fn()
  currentRoot = root
  act(() => {
    root.render(
      <DialogOpsModal
        candidates={overrides.candidates ?? CANDS}
        loading={overrides.loading ?? false}
        error={overrides.error ?? null}
        onRetry={onRetry}
        initialTab={overrides.initialTab ?? 'trim'}
        onCompact={onCompact}
        onTrim={onTrim}
        onClose={onClose}
      />,
    )
  })
  return {
    container,
    root,
    text: () => container.textContent ?? '',
    clickText: (t: string) => {
      const el = Array.from(container.querySelectorAll('button, .trim-item, .ms-item'))
        .find((b) => (b.textContent ?? '').includes(t))
      if (!el) throw new Error(`未找到含「${t}」的可点击元素`)
      act(() => el.dispatchEvent(new MouseEvent('click', { bubbles: true })))
    },
    clickSelector: (sel: string) => {
      const el = container.querySelector(sel)
      if (!el) throw new Error(`未找到选择器「${sel}」`)
      act(() => el.dispatchEvent(new MouseEvent('click', { bubbles: true })))
    },
    pressEscape: () => {
      // 从内层元素派发（而非 window 直派）：这样 window 上的 capture 监听是否「先于冒泡被调用并
      // stopPropagation」才有可观测意义（见 Esc 用例的 window 冒泡 spy）
      const target = container.querySelector('.dops-modal') ?? container.firstElementChild
      if (!target) throw new Error('未找到可派发 Esc 的内层元素')
      act(() => {
        target.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
      })
    },
    onClose,
    onRetry,
    onTrim,
    onCompact,
  }
}

describe('[dialog-ops-pivot] DialogOpsModal 候选三态与裁剪口径', () => {
  // 每个用例自管挂载（三态参数不同）；afterEach 统一卸载 + 清 DOM
  afterEach(() => {
    act(() => {
      currentRoot?.unmount()
    })
    currentRoot = null
    document.body.innerHTML = ''
  })

  it('loading 态：显示「正在加载」且不渲染列表，取消键常驻可关', () => {
    const h = mount({ loading: true })
    expect(h.text()).toContain('正在加载')
    expect(h.container.querySelectorAll('.trim-item').length).toBe(0)
    h.clickSelector('.ms-cancel')
    expect(h.onClose).toHaveBeenCalledTimes(1)
  })

  it('error 态：显示错误与重试按钮，点重试触发 onRetry，取消键常驻可关', () => {
    const h = mount({ error: '网络错误' })
    expect(h.text()).toContain('网络错误')
    h.clickText('重试')
    expect(h.onRetry).toHaveBeenCalledTimes(1)
    h.clickSelector('.ms-cancel')
    expect(h.onClose).toHaveBeenCalledTimes(1)
  })

  it('空态：无候选时显示「暂无可选消息」且不渲染列表，取消键常驻可关', () => {
    const h = mount({ candidates: [] })
    expect(h.text()).toContain('暂无可选消息')
    expect(h.container.querySelectorAll('.trim-item').length).toBe(0)
    expect(h.container.querySelectorAll('.ms-item').length).toBe(0)
    h.clickSelector('.ms-cancel')
    expect(h.onClose).toHaveBeenCalledTimes(1)
  })

  it('Enter：重试按钮上的 Enter 放行（浏览器默认激活），其余元素仍被 capture 拦截', () => {
    const h = mount({ error: '网络错误' })
    const retry = h.container.querySelector('.dops-retry')
    if (!retry) throw new Error('未找到 .dops-retry')
    const ev = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true })
    act(() => { retry.dispatchEvent(ev) })
    expect(ev.defaultPrevented).toBe(false)      // 放行：Enter 激活是浏览器默认动作

    const modal = h.container.querySelector('.dops-modal')
    if (!modal) throw new Error('未找到 .dops-modal')
    const ev2 = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true })
    act(() => { modal.dispatchEvent(ev2) })
    expect(ev2.defaultPrevented).toBe(true)      // 非重试元素：原拦截逻辑保留
  })

  it('正常态：候选逐条渲染（markdown 投影成一行预览），选中后显示该条 removedAfter 计数', () => {
    const h = mount()
    expect(h.container.querySelectorAll('.trim-item').length).toBe(3)
    expect(h.text()).toContain('标题一')          // projectPreview 剥 markdown 标记后仍含正文
    expect(h.text()).not.toContain('# 标题一')     // 标记不泄漏为字面
    h.clickText('内容二')                          // 选中 u2（removedAfter = 20）
    expect(h.text()).toContain('将删除 20 条消息')
    h.clickText('内容一')                          // 选中 u1（removedAfter = 40；计含自身）
    expect(h.text()).toContain('将删除 40 条消息')
    expect(h.text()).toContain('共 40 条')         // trimCount > 1 副行
  })

  it('压缩 tab（生产入口）：点候选后点确认触发 onCompact(选中 id, 方向)', () => {
    const h = mount({ initialTab: 'compact' })
    expect(h.container.querySelectorAll('.ms-item').length).toBe(3)
    h.clickText('内容二')                          // 选中 u2
    h.clickSelector('.ms-confirm')
    expect(h.onCompact).toHaveBeenCalledTimes(1)
    expect(h.onCompact).toHaveBeenCalledWith('u2', 'from')
  })

  it('Esc 关闭：内层元素派发时 capture 阶段拦截（window 冒泡 spy 不被触及）并触发 onClose', () => {
    const h = mount()
    const bubbleSpy = vi.fn()
    window.addEventListener('keydown', bubbleSpy)
    try {
      h.pressEscape()
      expect(bubbleSpy).not.toHaveBeenCalled()   // capture 先于冒泡 + stopPropagation 的直接证据
    } finally {
      window.removeEventListener('keydown', bubbleSpy)
    }
    expect(h.onClose).toHaveBeenCalledTimes(1)
  })
})
