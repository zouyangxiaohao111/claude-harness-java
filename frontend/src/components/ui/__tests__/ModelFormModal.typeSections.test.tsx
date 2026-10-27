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
        providerName="Provider"
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

  it('切换 select 类型 → 配置页即时切换', () => {
    render('chat')
    const select = container!.querySelector('.fm-select') as HTMLSelectElement
    expect(select).toBeTruthy()
    act(() => {
      select.value = 'embedding'
      select.dispatchEvent(new Event('change', { bubbles: true }))
    })
    const text = container!.textContent ?? ''
    expect(text).toContain('向量嵌入（仅 Embedding 类型生效）')
    expect(text).not.toContain('生成参数')
  })
})
