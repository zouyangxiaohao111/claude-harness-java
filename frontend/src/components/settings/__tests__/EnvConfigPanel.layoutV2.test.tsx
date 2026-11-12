// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { EnvConfigPanel } from '../EnvConfigPanel'
import type { AppSettings, UpdateSettingsRequest } from '@/api/types'

/**
 * [env-v2] 环境配置面板改版后的形态契约（2026-11-12）。
 *
 * 锁定四件改版事实（都是这次重构的实际风险点）：
 *  ① 压缩参数默认收起，点齿轮后才就地展开（不再是页面底部一个大网格）；
 *  ② 联网区「当前搜索 / 当前抓取」状态条随选择变化；
 *  ③ 搜索与抓取是两个**独立**字段：只改搜索不得连带写 webfetchEngine，反之亦然；
 *  ④ 凭据只在失焦且值变化时才提交（避免点进输入框就白写一次）。
 */
vi.mock('@/api/memory', () => ({
  getMemoryConfig: () => Promise.resolve({
    autoMemoryEnabled: true, autoDreamEnabled: false,
    dreamStatus: null, lastConsolidatedAtMs: null,
  }),
  updateMemoryConfig: () => Promise.resolve({}),
}))

function baseSettings(over: Partial<AppSettings> = {}): AppSettings {
  return {
    theme: null, fontSize: null, accent: null, animationsEnabled: null,
    mainModelName: null, fastModelName: null, weakModelName: null, mediumModelName: null,
    strongModelName: null, subagentModelName: null, autoCompactWindow: null,
    fallbackModelName: null, multimodalModelName: null,
    ttsModelName: null, asrModelName: null, autoMemoryEnabled: null, autoMemoryDirectory: null,
    websearchEngine: 'anysearch', webfetchEngine: 'builtin',
    ...over,
  }
}

describe('EnvConfigPanel v2 形态', () => {
  let container: HTMLDivElement
  let root: Root

  beforeEach(() => {
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
  })

  afterEach(() => {
    act(() => root.unmount())
    container.remove()
    vi.restoreAllMocks()
  })

  async function render(settings: AppSettings | null, onSave: (req: UpdateSettingsRequest) => Promise<boolean>) {
    await act(async () => {
      root.render(<EnvConfigPanel settings={settings} onOpenMemoryEditor={() => {}} onSaveSettings={onSave} />)
      await Promise.resolve()
    })
  }

  /** DeepSeek 供应商卡。凭据字段 2026-11-12 起在卡片内部（设计稿要求），不再是独立一区。 */
  function deepSeekCard(): HTMLElement {
    const card = Array.from(container.querySelectorAll('.envc-eng'))
      .find((e) => e.querySelector('.envc-eng-name')?.textContent?.includes('DeepSeek'))
    if (!card) throw new Error('未找到 DeepSeek 卡')
    return card as HTMLElement
  }

  it('压缩参数默认收起；点齿轮后就地展开', async () => {
    await render(baseSettings(), async () => true)
    const params = container.querySelector('#envc-params-auto') as HTMLElement
    expect(params.classList.contains('is-open')).toBe(false)
    const gear = container.querySelector('.envc-gear[aria-label="参数（4）"]') as HTMLButtonElement
    await act(async () => { gear.click(); await Promise.resolve() })
    expect(params.classList.contains('is-open')).toBe(true)
  })

  it('当前搜索 / 当前抓取 状态条显示 settings 里的值', async () => {
    await render(baseSettings({ websearchEngine: 'deepseek', webfetchEngine: 'deepseek' }), async () => true)
    const bar = container.querySelector('.envc-cur')!.textContent ?? ''
    expect(bar).toContain('DeepSeek')
    expect(bar).not.toContain('AnySearch')
  })

  it('点「设为搜索」只写 websearchEngine，不动 webfetchEngine', async () => {
    const onSave = vi.fn(async (_req: UpdateSettingsRequest) => true)
    await render(baseSettings(), onSave)
    const btn = Array.from(container.querySelectorAll('.envc-usebtn'))
      .find((b) => b.textContent === '设为搜索' && b.closest('.envc-eng')?.textContent?.includes('DuckDuckGo'))
    expect(btn).toBeTruthy()
    await act(async () => { (btn as HTMLButtonElement).click(); await Promise.resolve() })
    expect(onSave).toHaveBeenCalledTimes(1)
    expect(onSave.mock.calls[0][0]).toEqual({ websearchEngine: 'duckduckgo' })
  })

  it('点「设为抓取」只写 webfetchEngine，不动 websearchEngine', async () => {
    const onSave = vi.fn(async (_req: UpdateSettingsRequest) => true)
    await render(baseSettings(), onSave)
    const btn = Array.from(container.querySelectorAll('.envc-usebtn'))
      .find((b) => b.textContent === '设为抓取' && b.closest('.envc-eng')?.textContent?.includes('DeepSeek'))
    expect(btn).toBeTruthy()
    await act(async () => { (btn as HTMLButtonElement).click(); await Promise.resolve() })
    expect(onSave).toHaveBeenCalledTimes(1)
    expect(onSave.mock.calls[0][0]).toEqual({ webfetchEngine: 'deepseek' })
  })

  it('凭据输入失焦且值未变时不提交', async () => {
    const onSave = vi.fn(async (_req: UpdateSettingsRequest) => true)
    await render(baseSettings({ apiKey: 'sk-existing' }), onSave)
    const keyInput = Array.from(container.querySelectorAll('input[type="password"]'))[0] as HTMLInputElement
    await act(async () => { keyInput.focus(); keyInput.blur(); await Promise.resolve() })
    expect(onSave).not.toHaveBeenCalled()
  })

  it('DeepSeek 选中但 key 为空 → 内联 401 警告', async () => {
    await render(baseSettings({ websearchEngine: 'deepseek', apiKey: '' }), async () => true)
    expect(container.textContent).toContain('401')
  })

  /**
   * 凭据文案必须按**实际用途组合**说话。
   *
   * 背景：后端只有一个 apiKey 槽位（SettingsDto.apiKey）。早期实现用
   * `wsEngine === 'deepseek' || wsFetchEngine === 'deepseek'` 判断，于是「搜索选 DeepSeek、
   * 抓取选内置」时会显示「搜索和抓取都用它」—— 假的，抓取根本不读这个 key。
   * 这里锁死：只用搜索时，文案不得声称抓取也用它。
   */
  it('只用搜索走 DeepSeek 时，凭据文案不得声称抓取也用它', async () => {
    await render(
      baseSettings({ websearchEngine: 'deepseek', webfetchEngine: 'builtin', apiKey: 'sk-x' }),
      async () => true,
    )
    const text = deepSeekCard().textContent ?? ''
    expect(text).toContain('只用于搜索')
    expect(text).not.toContain('搜索和抓取都用它')
  })

  it('只用抓取走 DeepSeek 时，凭据文案不得声称搜索也用它', async () => {
    await render(
      baseSettings({ websearchEngine: 'anysearch', webfetchEngine: 'deepseek', apiKey: 'sk-x' }),
      async () => true,
    )
    const text = deepSeekCard().textContent ?? ''
    expect(text).toContain('只用于抓取')
    expect(text).not.toContain('搜索和抓取都用它')
  })

  it('两者都走 DeepSeek 时才可以说「搜索和抓取都用它」', async () => {
    await render(
      baseSettings({ websearchEngine: 'deepseek', webfetchEngine: 'deepseek', apiKey: 'sk-x' }),
      async () => true,
    )
    expect(deepSeekCard().textContent ?? '').toContain('搜索和抓取都用它')
  })

  it('搜索 AnySearch + 抓取 DeepSeek → 提示一个 Base URL 槽位要服务两家', async () => {
    await render(
      baseSettings({ websearchEngine: 'anysearch', webfetchEngine: 'deepseek', apiKey: 'sk-x' }),
      async () => true,
    )
    // 冲突说明挂在 AnySearch 卡的配置区里（它才是与 DeepSeek 撞地址的那一方）
    const text = [...container.querySelectorAll('.envc-eng')]
      .map((e) => e.textContent ?? '')
      .join('\n')
    expect(text).toContain('只有一个地址槽位')
  })
})
