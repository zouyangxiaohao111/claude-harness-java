// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { FormModal, type FormSection } from '../FormModal'

/**
 * [2026-11-01] 可折叠 section（「高级 · 额外请求体」这类高级配置默认收起）。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：用户反馈「高级 · 额外请求应该点开才出现」——
 * 一直摊开会把常用字段推到很远，每次编辑都要滚。锁死三件事：
 * <ol>
 *   <li>默认收起（高级项不占版面）；</li>
 *   <li>点标题展开 / 再点收起；</li>
 *   <li><b>收起不丢值</b> —— 折叠只影响渲染，字段值仍在 state 里，保存时原样带出
 *       （否则用户改完再收起，改动会静默消失）。</li>
 * </ol>
 *
 * <p>变异自证：把 {@code isOpen && layout.map(...)} 改成永远渲染 → 用例 1 红；
 * 把 setExpanded 写成覆盖而非合并 → 用例 2（展开后再收起）红；
 * 折叠时从 state 里删字段 → 用例 3 红。
 */

const SECTIONS: FormSection[] = [
  { title: '基础', fields: [{ type: 'text', name: 'name', label: '名称' }] },
  {
    title: '高级',
    collapsible: true,
    collapsedHint: '按 key 合并',
    fields: [{ type: 'text', name: 'extra', label: '额外' }],
  },
]

let container: HTMLDivElement
let root: Root

function draw(initial: Record<string, any>, onSave = vi.fn()) {
  act(() => {
    root.render(
      <FormModal
        title="测试"
        initial={initial}
        sections={SECTIONS}
        onSave={onSave}
        onCancel={() => {}}
      />,
    )
  })
  return onSave
}

const toggle = () => container.querySelector<HTMLButtonElement>('.fm-section-toggle')!
const saveBtn = () => Array.from(container.querySelectorAll<HTMLButtonElement>('.fm-btn'))
  .find((b) => (b.textContent ?? '').includes('保存'))!

describe('FormModal · 可折叠 section', () => {
  beforeEach(() => {
    ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
  })

  afterEach(() => {
    act(() => root.unmount())
    container.remove()
  })

  it('默认收起：高级字段不渲染，只留标题 + 折叠提示', () => {
    draw({ name: 'a', extra: '' })
    expect(toggle()).toBeTruthy()
    expect(toggle().getAttribute('aria-expanded')).toBe('false')
    expect(container.textContent).toContain('高级')
    expect(container.querySelector('.fm-collapsed-hint')!.textContent).toContain('按 key 合并')
    // 高级字段的 label 不应出现
    expect(container.textContent).not.toContain('额外')
  })

  it('点标题展开 → 再点收起', () => {
    draw({ name: 'a', extra: '' })
    act(() => { toggle().click() })
    expect(toggle().getAttribute('aria-expanded')).toBe('true')
    expect(container.textContent).toContain('额外')

    act(() => { toggle().click() })
    expect(toggle().getAttribute('aria-expanded')).toBe('false')
    expect(container.textContent).not.toContain('额外')
  })

  it('收起不丢值：defaultOpen 默认展开，收起后保存仍原样带出', () => {
    // 用 { defaultOpen: true } 的 section（真实用法：该字段已有非空值时默认展开，用户能立刻看到现状）
    const sections: FormSection[] = [
      {
        title: '高级',
        collapsible: { defaultOpen: true },
        fields: [{ type: 'text', name: 'extra', label: '额外' }],
      },
    ]
    const onSave = vi.fn()
    act(() => {
      root.render(
        <FormModal title="测试" initial={{ extra: '{"reasoning":true}' }} sections={sections}
          onSave={onSave} onCancel={() => {}} />,
      )
    })
    expect(toggle().getAttribute('aria-expanded')).toBe('true')

    // 收起后再保存，值必须还在（折叠只影响渲染，不能删 state）
    act(() => { toggle().click() })
    act(() => { saveBtn().click() })
    expect(onSave).toHaveBeenCalledWith(expect.objectContaining({ extra: '{"reasoning":true}' }))
  })
})
