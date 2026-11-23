// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ImportModelsModal } from '../ImportModelsModal'
import type { Provider } from '@/api/types'

const mocks = vi.hoisted(() => ({ remoteModels: vi.fn() }))
vi.mock('@/api/providers', () => ({ providerApi: { remoteModels: mocks.remoteModels } }))
vi.mock('@/api/rest', () => ({
  ApiError: class ApiError extends Error {
    userMessage() { return this.message }
  },
}))

const PROVIDER = {
  id: 'p1', name: 'ModelScope', baseUrl: 'https://api-inference.modelscope.cn/v1', models: [],
} as unknown as Provider

const SUGGESTED = [
  { id: 'Qwen/Qwen3.5-122B-A10B', name: 'Qwen3.5-122B-A10B', maxInputTokens: 131072 },
  { id: 'ZhipuAI/GLM-5', name: 'GLM-5', maxInputTokens: 131072 },
]

function noop(): void {}
async function flush(n = 12) { for (let i = 0; i < n; i++) await act(async () => { await Promise.resolve() }) }
let root: Root | undefined
let container: HTMLDivElement | undefined

beforeEach(() => {
  (globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
  container = document.createElement('div'); document.body.appendChild(container)
  root = createRoot(container)
  mocks.remoteModels.mockReset()
})
afterEach(() => { if (root) act(() => root!.unmount()); container?.remove() })

async function render() {
  act(() => {
    root!.render(<ImportModelsModal
      provider={PROVIDER}
      suggestedModels={SUGGESTED}
      createModel={async () => {}}
      onClose={noop}
      showToast={noop}
    />)
  })
  await flush()
}

describe('ImportModelsModal 内置建议', () => {
  it('远端为空时载入建议，并标「内置建议」', async () => {
    mocks.remoteModels.mockResolvedValue({ ok: true, models: [], message: 'ok' })
    await render()
    const rows = container!.querySelectorAll('.im-row')
    expect(rows.length).toBe(2)
    expect(container!.textContent).toContain('内置建议')
    expect(container!.textContent).toContain('Qwen3.5-122B-A10B')
  })

  it('远端失败时降级用建议（不伪装成功）', async () => {
    mocks.remoteModels.mockResolvedValue({ ok: false, models: [], message: 'no endpoint' })
    await render()
    expect(container!.querySelectorAll('.im-row').length).toBe(2)
    expect(container!.textContent).toContain('远端清单不可用')
  })

  it('建议可取消勾选（只作建议，不是强制导入）', async () => {
    mocks.remoteModels.mockResolvedValue({ ok: true, models: [], message: 'ok' })
    await render()
    expect(container!.textContent).toContain('2 / 2 已选')
    const box = container!.querySelector('.im-row input[type="checkbox"]') as HTMLInputElement
    expect(box.checked).toBe(true)
    act(() => { box.click() })
    await flush()
    expect(container!.textContent).toContain('1 / 2 已选')
  })
})
