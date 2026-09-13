// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ProvidersPanel } from '../ProvidersPanel'
import type { Provider } from '@/api/types'
import type { UseProviders } from '@/hooks/useProviders'

/**
 * ProvidersPanel · 自定义请求头的「发得出去 + 改得动」。
 *
 * WHY（规则 9）：`providers.extra_headers` 这一列、后端 DTO、前端 TS 类型**全都早就存在**，
 * 后端消费链路也已接通，但前端表单没有输入框、两个请求构造器也根本不发这个字段 ——
 * 「类型有、后端有、UI 不发」。用户配了等于白配。
 * 本用例锁死三件事：① 新建的 payload 带 extraHeaders；② 编辑的 payload 带 extraHeaders；
 * ③ 编辑路径既有的「apiKey 掩码不比不发送」特例**不波及** header 字段（两者取舍互不影响）。
 *
 * 变异自证：把 onAddProvider 里的 `extraHeaders: ...` 删掉 → 第 1 条红；
 * 把 onSaveProvider 里的删掉 → 第 2、3 条红；把 header 也接上 `=== apiKeyMasked ? undefined` 的
 * 掩码特例 → 第 3 条红。
 */

/** React 受控 input 需绕过 value tracker 才能触发 onChange（等价 testing-library 的做法）。 */
function typeInto(el: HTMLInputElement, value: string) {
  const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set
  setter?.call(el, value)
  el.dispatchEvent(new Event('input', { bubbles: true }))
}

const PROVIDER: Provider = {
  id: 'p-1',
  name: 'DeepSeek',
  type: 'openai_compatible',
  baseUrl: 'https://api.deepseek.com/v1',
  apiKeyMasked: 'sk-****abcd',
  extraHeaders: { 'x-tenant': 'acme' },
  enabled: true,
  models: [],
}

function makeApi(list: Provider[]) {
  return {
    list,
    loading: false,
    error: null,
    refresh: vi.fn(async () => {}),
    createProvider: vi.fn(async () => PROVIDER),
    updateProvider: vi.fn(async () => PROVIDER),
    deleteProvider: vi.fn(async () => {}),
    toggleProvider: vi.fn(async () => PROVIDER),
    testProvider: vi.fn(async () => ({ ok: true, latencyMs: 1, message: 'ok' })),
    createModel: vi.fn(async () => {}),
    updateModel: vi.fn(async () => {}),
    deleteModel: vi.fn(async () => {}),
  } as unknown as UseProviders
}

describe('ProvidersPanel · 自定义请求头', () => {
  let container: HTMLDivElement
  let root: Root

  beforeEach(() => {
    (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
  })

  afterEach(() => {
    act(() => root.unmount())
    container.remove()
    vi.restoreAllMocks()
  })

  function draw(api: UseProviders) {
    act(() => {
      root.render(<ProvidersPanel providers={api.list} providersApi={api} showToast={() => {}} />)
    })
  }

  function byText(sel: string, text: string): HTMLElement {
    const el = Array.from(container.querySelectorAll<HTMLElement>(sel)).find(
      (e) => (e.textContent ?? '').trim() === text,
    )
    if (!el) throw new Error(`未找到 ${sel}：${text}`)
    return el
  }
  function headerNameInput(): HTMLInputElement {
    const el = container.querySelector<HTMLInputElement>('input[placeholder="请求头名称"]')
    if (!el) throw new Error('未找到请求头名称输入框')
    return el
  }
  function headerValueInput(): HTMLInputElement {
    const el = container.querySelector<HTMLInputElement>('input[placeholder="值"]')
    if (!el) throw new Error('未找到请求头值输入框')
    return el
  }

  it('新建提供商时请求体必须带 extraHeaders', async () => {
    const api = makeApi([])
    draw(api)

    act(() => byText('button', '+ 添加提供商').click())
    act(() => typeInto(container.querySelector<HTMLInputElement>('input[placeholder="e.g. OpenAI"]')!, 'MyGW'))
    act(() => typeInto(container.querySelector<HTMLInputElement>('input[placeholder="https://api.openai.com/v1"]')!, 'https://gw.local/v1'))
    act(() => typeInto(container.querySelector<HTMLInputElement>('input[placeholder="sk-****abcd"]')!, 'sk-live-1'))

    // 表单里的 Headers section 能加行、能填
    act(() => byText('button', '+ 添加一行').click())
    act(() => typeInto(headerNameInput(), 'x-tenant'))
    act(() => typeInto(headerValueInput(), 'acme'))

    await act(async () => {
      byText('button', '保存').click()
      await Promise.resolve()
    })

    expect(api.createProvider).toHaveBeenCalledTimes(1)
    const req = (api.createProvider as unknown as { mock: { calls: unknown[][] } }).mock.calls[0][0]
    expect(req).toMatchObject({ extraHeaders: { 'x-tenant': 'acme' } })
  })

  it('编辑提供商时请求体必须带 extraHeaders（且回显原有值）', async () => {
    const api = makeApi([PROVIDER])
    draw(api)

    act(() => (container.querySelector('button[title="编辑提供商"]') as HTMLButtonElement).click())
    // 回显：provider 的 extraHeaders 变成一行
    expect(headerNameInput().value).toBe('x-tenant')
    expect(headerValueInput().value).toBe('acme')

    act(() => typeInto(headerValueInput(), 'acme-2'))
    await act(async () => {
      byText('button', '保存').click()
      await Promise.resolve()
    })

    expect(api.updateProvider).toHaveBeenCalledTimes(1)
    const [id, req] = (api.updateProvider as unknown as { mock: { calls: unknown[][] } }).mock.calls[0]
    expect(id).toBe('p-1')
    expect(req).toMatchObject({ extraHeaders: { 'x-tenant': 'acme-2' } })
  })

  it('apiKey 掩码未变时不发 apiKey，但 header 照发 —— 两者取舍互不影响', async () => {
    const api = makeApi([PROVIDER])
    draw(api)

    act(() => (container.querySelector('button[title="编辑提供商"]') as HTMLButtonElement).click())
    // 不动 apiKey 输入框（值仍是后端掩码），只改 header
    act(() => typeInto(headerValueInput(), 'changed'))

    await act(async () => {
      byText('button', '保存').click()
      await Promise.resolve()
    })

    const req = (api.updateProvider as unknown as { mock: { calls: unknown[][] } }).mock.calls[0][1] as Record<string, unknown>
    expect(req.apiKey).toBeUndefined()
    expect(req.extraHeaders).toEqual({ 'x-tenant': 'changed' })
  })

  it('apiKey 改了 → 两者都发（header 不被掩码特例顺带吞掉）', async () => {
    const api = makeApi([PROVIDER])
    draw(api)

    act(() => (container.querySelector('button[title="编辑提供商"]') as HTMLButtonElement).click())
    act(() => typeInto(container.querySelector<HTMLInputElement>('input[placeholder="sk-****abcd"]')!, 'sk-rotated'))
    act(() => typeInto(headerValueInput(), 'changed'))

    await act(async () => {
      byText('button', '保存').click()
      await Promise.resolve()
    })

    const req = (api.updateProvider as unknown as { mock: { calls: unknown[][] } }).mock.calls[0][1] as Record<string, unknown>
    expect(req.apiKey).toBe('sk-rotated')
    expect(req.extraHeaders).toEqual({ 'x-tenant': 'changed' })
  })
})
