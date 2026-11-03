// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { ModelFormModal } from '../ModelFormModal'
import type { Model } from '@/api/types'

/**
 * 模型表单按「类型」切换配置页（用户 2026-10-27 反馈：Embedding 配置默认一直显示）。
 *
 * <p>真组件渲染 + 真 select 切换，锁住：
 * ① chat 只显示生成参数；② embedding 只显示向量嵌入；③ rerank 只显示重排序；
 * ④ 切换类型后页面立即跟着切（section 的 visibleWhen 生效）。
 */
const BASE: Model = {
  id: 'm1', name: 'demo', alias: null, tag: 'DS', desc: '', type: 'chat',
  maxTokens: 1024, temperature: 1, topP: null, think: '', extraBody: '',
  enabled: true, maxContextTokens: 1048576,
  embeddingDimensions: null, embeddingBatchSize: null,
  rerankTopN: null, rerankInstruct: null,
}

let container: HTMLDivElement | undefined
let root: Root | undefined

function render(type: Model['type']) {
  act(() => {
    root!.render(
      <ModelFormModal
        initial={{ ...BASE, type }}
        providerTag="DS"
        onSave={() => {}}
        onCancel={() => {}}
      />,
    )
  })
  return container!.textContent ?? ''
}

beforeEach(() => {
  ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
})

afterEach(() => {
  if (root) act(() => root!.unmount())
  container?.remove()
})

describe('ModelFormModal · 类型切换配置页', () => {
  it('chat 只显示生成参数', () => {
    const text = render('chat')
    expect(text).toContain('生成参数')
    expect(text).not.toContain('向量嵌入（仅 Embedding 类型生效）')
    expect(text).not.toContain('重排序（仅 Rerank 类型生效）')
  })

  it('embedding 只显示向量嵌入', () => {
    const text = render('embedding')
    expect(text).not.toContain('生成参数')
    expect(text).toContain('向量嵌入（仅 Embedding 类型生效）')
    expect(text).not.toContain('重排序（仅 Rerank 类型生效）')
  })

  it('rerank 只显示重排序', () => {
    const text = render('rerank')
    expect(text).not.toContain('生成参数')
    expect(text).not.toContain('向量嵌入（仅 Embedding 类型生效）')
    expect(text).toContain('重排序（仅 Rerank 类型生效）')
  })

  it('切换模型类型 → 配置页即时切换', () => {
    render('chat')
    // [2026-10-31] 模型类型从原生 <select> 改为卡片式单选（radio-cards）——
    //   「切换类型后下方参数区立即跟着换」是本用例锁的意图，交互载体从 select 换成卡片。
    const cards = Array.from(container!.querySelectorAll<HTMLElement>('.fm-radio-item.card'))
    const embedding = cards.find((c) => (c.textContent ?? '').includes('Embedding'))
    expect(embedding, '模型类型选项卡未渲染').toBeTruthy()
    act(() => {
      embedding!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    })
    const text = container!.textContent ?? ''
    expect(text).toContain('向量嵌入（仅 Embedding 类型生效）')
    expect(text).not.toContain('生成参数')
  })

  it('模型类型不再用原生 select（原生下拉把能力类型藏在一行文字后面）', () => {
    render('chat')
    expect(container!.querySelector('.fm-select')).toBeNull()
    // 9 个类型选项全部摊在一屏可见
    expect(container!.querySelectorAll('.fm-radio-item.card').length).toBe(9)
  })
})
