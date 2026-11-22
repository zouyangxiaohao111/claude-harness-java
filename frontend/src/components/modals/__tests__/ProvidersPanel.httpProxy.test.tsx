// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ProvidersPanel } from '../ProvidersPanel'
import type { Provider } from '@/api/types'
import type { UseProviders } from '@/hooks/useProviders'

/**
 * [保密 2026-11-22 B6] 出站代理脱敏后的前端契约。
 *
 * 后端 GET /settings/http-proxy 只回 `http://user:****@host:port`：
 *  ① 输入框如实显示掩码（不是明文）；
 *  ② 「清空」是显式动作，发 null（三态里的「未配置」）；
 *  ③ 未改动直接「记住」时，掩码形态不得被前端格式校验拦下（userinfo 本就不在 PROXY_PATTERN 里）。
 * 「试一下」用服务端真口令由后端负责（见 SettingsController.testHttpProxy）。
 */
vi.mock('@/api/settings', () => ({
  settingsApi: {
    get: vi.fn(async () => ({})),
    update: vi.fn(async () => ({})),
    clearModelTier: vi.fn(async () => ({})),
    clearAllModelTiers: vi.fn(async () => ({})),
  },
  getHttpProxy: vi.fn(async () => ({ proxy: 'http://user:****@127.0.0.1:7897' })),
  putHttpProxy: vi.fn(async () => ({ proxy: '' })),
  probeHttpProxy: vi.fn(async () => ({ candidates: [] })),
  testHttpProxy: vi.fn(async () => ({ ok: true, latencyMs: 1, error: null })),
}))
vi.mock('@/api/providers', () => ({
  providerApi: { listBuiltin: vi.fn(async () => []) },
}))

import { putHttpProxy, testHttpProxy } from '@/api/settings'

const PROVIDER: Provider = {
  id: 'p-1', name: 'DeepSeek', type: 'openai_compatible',
  baseUrl: 'https://api.deepseek.com/v1', apiKeyMasked: '****abcd',
  extraHeaders: {}, enabled: true, models: [],
}

function makeApi(): UseProviders {
  return {
    list: [PROVIDER], loading: false, error: null,
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

describe('ProvidersPanel · 出站代理脱敏', () => {
  let container: HTMLDivElement
  let root: Root

  beforeEach(() => {
    vi.mocked(putHttpProxy).mockClear()
    vi.mocked(testHttpProxy).mockClear();
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

  async function draw() {
    const api = makeApi()
    await act(async () => {
      root.render(<ProvidersPanel providers={api.list} providersApi={api} showToast={() => {}} />)
      await Promise.resolve()
    })
  }

  function proxyInput(): HTMLInputElement {
    const el = document.getElementById('pv-http-proxy-official') as HTMLInputElement | null
    if (!el) throw new Error('未找到出站代理输入框')
    return el
  }

  function byText(text: string): HTMLButtonElement | undefined {
    return Array.from(container.querySelectorAll('button'))
      .find((b) => (b.textContent ?? '').trim() === text) as HTMLButtonElement | undefined
  }

  it('已配置：输入框显示掩码 + 「清空」按钮；清空发 null', async () => {
    await draw()
    expect(proxyInput().value).toBe('http://user:****@127.0.0.1:7897')
    expect(container.textContent).toContain('已记住')

    const clear = byText('清空')
    expect(clear).toBeTruthy()
    await act(async () => { clear!.click(); await Promise.resolve() })
    expect(putHttpProxy).toHaveBeenCalledWith(null)
  })

  it('未改动直接「记住」：掩码不被格式校验拦下，实测与保存都照发', async () => {
    await draw()
    const save = byText('记住')
    expect(save).toBeTruthy()
    await act(async () => { save!.click(); await Promise.resolve() })
    expect(testHttpProxy).toHaveBeenCalledWith('http://user:****@127.0.0.1:7897')
    expect(putHttpProxy).toHaveBeenCalledWith('http://user:****@127.0.0.1:7897')
    expect(container.textContent).not.toContain('端口写在冒号后面')
  })
})
