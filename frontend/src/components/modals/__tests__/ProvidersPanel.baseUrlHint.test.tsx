// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ProvidersPanel, baseUrlHint as hintForType } from '../ProvidersPanel'
import type { Provider } from '@/api/types'
import type { UseProviders } from '@/hooks/useProviders'

/**
 * ProvidersPanel · Base URL 提示随「协议类型」变。
 *
 * WHY（用户 2026-11-12 裁定）：后端从不补也不删 `/v1`（只 trim 尾部斜杠），路径由协议固定拼接 ——
 * OpenAI 兼容 = 地址 + `/chat/completions`，Anthropic = 地址 + `/v1/messages`。
 * ⇒ 两条规则**正好相反**，写死一句提示必然误导其中一半用户。
 *
 * 锁死四件事：① 新建默认协议显示 OpenAI 兼容文案；② 切到 Anthropic 后文案跟着换；
 * ③ 编辑既有 Anthropic provider 时直接显示 Anthropic 文案（不是先显示默认再纠正）；
 * ④ Responses 也备好文案（该协议当前置灰不可选，但文案表不能缺）。
 *
 * 变异自证：把 hint 从 `(v) => baseUrlHint(v.type)` 改成写死的字符串 → 第 2、3 条红。
 */

const OPENAI_HINT = '填到 /v1 为止 · 请求 {base}/chat/completions'
const ANTHROPIC_HINT = '只填域名、不带 /v1 · 请求 {base}/v1/messages'
const RESPONSES_HINT = '填到 /v1 为止 · 请求 {base}/responses'

const PROVIDER: Provider = {
  id: 'p-1',
  name: 'DeepSeek',
  type: 'openai_compatible',
  baseUrl: 'https://api.deepseek.com/v1',
  apiKeyMasked: 'sk-****abcd',
  extraHeaders: null,
  enabled: true,
  models: [],
}

const ANTHROPIC_PROVIDER: Provider = {
  ...PROVIDER,
  id: 'p-2',
  name: 'Claude',
  type: 'anthropic',
  baseUrl: 'https://api.anthropic.com',
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

describe('ProvidersPanel · Base URL 提示按协议切换', () => {
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

  /** Base URL 输入框所在字段的提示文字（`.fm-field` 内的 `.fm-field-hint`）。 */
  function renderedHint(placeholder: string): string {
    const input = container.querySelector<HTMLInputElement>(`input[placeholder="${placeholder}"]`)
    if (!input) throw new Error(`未找到 Base URL 输入框（placeholder=${placeholder}）`)
    const field = input.closest('.fm-field')
    if (!field) throw new Error('Base URL 输入框不在 .fm-field 内')
    return (field.querySelector('.fm-field-hint')?.textContent ?? '').trim()
  }

  /** 点协议分段里的某一项（按主标题文本找，副标题是端点说明）。 */
  function pickProtocol(label: string) {
    const seg = Array.from(container.querySelectorAll<HTMLButtonElement>('.fm-segment')).find(
      (b) => (b.querySelector('.fm-segment-label')?.textContent ?? '').trim() === label,
    )
    if (!seg) throw new Error(`未找到协议分段：${label}`)
    act(() => seg.click())
  }

  it('新建：默认 OpenAI 兼容 → 提示「填到 /v1 为止 · 请求 {base}/chat/completions」', () => {
    draw(makeApi([]))
    act(() => byText('button', '+ 添加提供商').click())

    expect(renderedHint('https://api.openai.com/v1')).toBe(OPENAI_HINT)
  })

  it('新建：切到 Anthropic 原生 → 提示变成「只填域名、不带 /v1 · 请求 {base}/v1/messages」', () => {
    draw(makeApi([]))
    act(() => byText('button', '+ 添加提供商').click())
    pickProtocol('Anthropic 原生')

    expect(renderedHint('https://api.openai.com/v1')).toBe(ANTHROPIC_HINT)
  })

  it('新建：切回 OpenAI 兼容 → 提示还原（不是单向切换）', () => {
    draw(makeApi([]))
    act(() => byText('button', '+ 添加提供商').click())
    pickProtocol('Anthropic 原生')
    pickProtocol('OpenAI 兼容')

    expect(renderedHint('https://api.openai.com/v1')).toBe(OPENAI_HINT)
  })

  it('编辑：既有 Anthropic provider 一打开就显示 Anthropic 文案（不先显示默认值）', () => {
    const api = makeApi([ANTHROPIC_PROVIDER])
    draw(api)

    act(() => (container.querySelector('button[title="编辑提供商"]') as HTMLButtonElement).click())

    expect(renderedHint('https://api.example.com/v1')).toBe(ANTHROPIC_HINT)
  })

  it('文案表：Responses 也有自己的条目，未知类型兜底到 OpenAI 兼容', () => {
    // Responses 当前置灰不可选，且 Provider.type 是后端枚举子集（无此值）⇒ 走真值表而不是造 Provider。
    expect(hintForType('openai_response')).toBe(RESPONSES_HINT)
    expect(hintForType(undefined)).toBe(OPENAI_HINT)
    expect(hintForType('')).toBe(OPENAI_HINT)
    expect(hintForType(null)).toBe(OPENAI_HINT)
  })
})
