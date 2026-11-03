// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ModelSettingsPanel } from '../ModelSettingsPanel'
import type { AppSettings, ModelTierRole, Provider } from '@/api/types'

/**
 * [model-settings-clear 2026-10-31] 设置页「模型」tab · 档位绑定/解绑交互。
 *
 * <p><b>WHY（规则九 · 测试验证意图而非行为）</b>：
 * 旧形态下 **11 个档位里只有「快速模型」有清除入口**，其余 10 个<b>根本没法解绑</b> ——
 * 用户配错了就只能删掉模型重来。且那个唯一的清除按钮还是坏的（只清本地 state 不落库，
 * 刷新后绑定复活）。本用例锁死新形态必须做到的三件事：
 * <ol>
 *   <li><b>每个已配置的档位都有清除入口</b>（不是只有「快速模型」）；</li>
 *   <li><b>清除单个档位</b>调 onClearTier(role) —— 走独立 DELETE 端点
 *       （PUT 的 merge 契约「null = 不覆盖」表达不了「清空」）；</li>
 *   <li><b>一键清除全部</b>必须先弹确认框，且框里<b>逐条列出</b>将解绑的档位 + 模型全名
 *       （不能是「确定吗？」这种空确认 —— 用户不知道要清什么就不敢点）。</li>
 * </ol>
 *
 * <p>变异自证：把 tier 的清除按钮渲染条件从「已配置」改成「只有 fast」→ 第 1 条红；
 * 把 onClearTier 换成 onClearAllTiers → 第 2 条红；
 * 把确认框里的清单删空 → 第 3 条红。
 */

function baseSettings(over: Partial<AppSettings> = {}): AppSettings {
  return {
    theme: null, fontSize: null, accent: null, animationsEnabled: null,
    mainModelName: null, fastModelName: null, weakModelName: null, mediumModelName: null,
    strongModelName: null, subagentModelName: null, autoCompactWindow: null,
    maxOutputTokens: null, fallbackModelName: null, multimodalModelName: null,
    ttsModelName: null, asrModelName: null, autoMemoryEnabled: null, autoMemoryDirectory: null,
    ...over,
  } as AppSettings
}

const PROVIDER: Provider = {
  id: 'p-1',
  name: 'deepseek',
  type: 'openai_compatible',
  baseUrl: 'https://api.deepseek.com/v1',
  apiKeyMasked: 'sk-****abcd',
  extraHeaders: null,
  enabled: true,
  models: [
    { id: 'm-1', name: 'deepseek-flash', alias: null, tag: 'DS', desc: '视觉 + 文本', type: 'multimodal', maxTokens: 65536, temperature: 1, topP: null, think: null, extraBody: '', enabled: true, maxContextTokens: 1048576 },
    { id: 'm-2', name: 'deepseek-r1', alias: null, tag: 'DS', desc: '推理模型', type: 'chat', maxTokens: 65536, temperature: 1, topP: null, think: null, extraBody: '', enabled: true, maxContextTokens: 1048576 },
  ],
}

describe('ModelSettingsPanel · 档位绑定/解绑', () => {
  let container: HTMLDivElement
  let root: Root
  let onBindTier: ReturnType<typeof vi.fn<(role: ModelTierRole, fullName: string) => void>>
  let onBindAllTiers: ReturnType<typeof vi.fn<(fullName: string) => Promise<void>>>
  let onClearTier: ReturnType<typeof vi.fn<(role: ModelTierRole) => Promise<void>>>
  let onClearAllTiers: ReturnType<typeof vi.fn<() => Promise<void>>>

  beforeEach(() => {
    ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    onBindTier = vi.fn()
    onBindAllTiers = vi.fn(async () => {})
    onClearTier = vi.fn(async () => {})
    onClearAllTiers = vi.fn(async () => {})
  })

  afterEach(() => {
    act(() => root.unmount())
    container.remove()
    vi.restoreAllMocks()
  })

  function draw(settings: AppSettings) {
    act(() => {
      root.render(
        <ModelSettingsPanel
          providers={[PROVIDER]}
          settings={settings}
          onBindTier={onBindTier}
          onBindAllTiers={onBindAllTiers}
          onClearTier={onClearTier}
          onClearAllTiers={onClearAllTiers}
        />,
      )
    })
  }

  /** 档位行（按档位中文名定位）。 */
  function tierRow(label: string): HTMLElement {
    const el = Array.from(container.querySelectorAll<HTMLElement>('.ms-tier')).find(
      (e) => (e.querySelector('.ms-tier-name')?.textContent ?? '').startsWith(label),
    )
    if (!el) throw new Error(`未找到档位行：${label}`)
    return el
  }

  it('11 个档位全部列出，且每个已配置的档位都有清除入口（不是只有「快速模型」）', () => {
    draw(baseSettings({
      mainModelName: 'deepseek/deepseek-flash',
      strongModelName: 'deepseek/deepseek-r1',
      fastModelName: 'deepseek/deepseek-flash',
    }))

    expect(container.querySelectorAll('.ms-tier').length).toBe(11)

    // 已配置的 3 个档位都必须有清除按钮
    for (const label of ['主模型', '最强模型', '快速模型']) {
      expect(tierRow(label).querySelector('.ms-tier-clear'), `${label} 缺清除入口`).toBeTruthy()
    }
    // 未配置的档位不该显示清除按钮（没什么可清的）
    expect(tierRow('子代理').querySelector('.ms-tier-clear')).toBeFalsy()
  })

  it('点档位行的清除 → 调 onClearTier 且带上正确的 role', async () => {
    draw(baseSettings({
      mainModelName: 'deepseek/deepseek-flash',
      strongModelName: 'deepseek/deepseek-r1',
    }))

    await act(async () => {
      tierRow('最强模型').querySelector<HTMLButtonElement>('.ms-tier-clear')!.click()
      await Promise.resolve()
    })

    expect(onClearTier).toHaveBeenCalledTimes(1)
    expect(onClearTier).toHaveBeenCalledWith('strong')
    expect(onClearAllTiers).not.toHaveBeenCalled()
  })

  it('点模型 → 调 onBindTier 绑定到「当前选中的档位」（全名 provider/model）', async () => {
    draw(baseSettings())

    // 选中「最强模型」档位
    await act(async () => {
      tierRow('最强模型').click()
      await Promise.resolve()
    })
    // 右列点模型
    const item = Array.from(container.querySelectorAll<HTMLElement>('.ms-pick-item')).find(
      (e) => (e.textContent ?? '').includes('deepseek-r1'),
    )
    expect(item, '模型列表未渲染 deepseek-r1').toBeTruthy()
    await act(async () => {
      item!.click()
      await Promise.resolve()
    })

    expect(onBindTier).toHaveBeenCalledTimes(1)
    expect(onBindTier).toHaveBeenCalledWith('strong', 'deepseek/deepseek-r1')
  })

  it('一键清除全部：先弹确认框，框里逐条列出将解绑的档位 + 模型全名', async () => {
    draw(baseSettings({
      mainModelName: 'deepseek/deepseek-flash',
      strongModelName: 'deepseek/deepseek-r1',
    }))

    const clearAllBtn = container.querySelector<HTMLButtonElement>('.ms-clear-all')
    expect(clearAllBtn, '有已配置档位时应出现「一键清除全部」').toBeTruthy()
    await act(async () => {
      clearAllBtn!.click()
      await Promise.resolve()
    })

    // 确认框在场，且**逐条**列出（不是空确认）
    const confirm = container.querySelector('.ms-confirm')
    expect(confirm, '未弹出确认框').toBeTruthy()
    const rows = Array.from(confirm!.querySelectorAll('.ms-confirm-row')).map((r) => r.textContent ?? '')
    expect(rows.length).toBe(2)
    expect(rows.join(' ')).toContain('主模型')
    expect(rows.join(' ')).toContain('deepseek/deepseek-flash')
    expect(rows.join(' ')).toContain('最强模型')
    expect(rows.join(' ')).toContain('deepseek/deepseek-r1')

    // 此时还没真删
    expect(onClearAllTiers).not.toHaveBeenCalled()
  })

  it('确认框点「确认清除」→ 调 onClearAllTiers', async () => {
    draw(baseSettings({ mainModelName: 'deepseek/deepseek-flash' }))

    await act(async () => {
      container.querySelector<HTMLButtonElement>('.ms-clear-all')!.click()
      await Promise.resolve()
    })
    const ok = container.querySelector<HTMLButtonElement>('.ms-confirm-ok')
    expect(ok).toBeTruthy()
    await act(async () => {
      ok!.click()
      await Promise.resolve()
    })

    expect(onClearAllTiers).toHaveBeenCalledTimes(1)
  })

  it('确认框点「取消」→ 不清任何东西', async () => {
    draw(baseSettings({ mainModelName: 'deepseek/deepseek-flash' }))

    await act(async () => {
      container.querySelector<HTMLButtonElement>('.ms-clear-all')!.click()
      await Promise.resolve()
    })
    const cancel = Array.from(container.querySelectorAll<HTMLButtonElement>('.ms-confirm-foot .fm-btn')).find(
      (b) => (b.textContent ?? '').includes('取消'),
    )
    expect(cancel).toBeTruthy()
    await act(async () => {
      cancel!.click()
      await Promise.resolve()
    })

    expect(onClearAllTiers).not.toHaveBeenCalled()
    expect(container.querySelector('.ms-confirm')).toBeFalsy()
  })

  it('没有任何档位配置时 → 不出现「一键清除全部」', () => {
    draw(baseSettings())
    expect(container.querySelector('.ms-clear-all')).toBeFalsy()
  })

  it('只写不读的档位（TTS/ASR）标注「未生效」；多模态是生效的，不该标', () => {
    draw(baseSettings())
    for (const label of ['TTS', 'ASR']) {
      expect(tierRow(label).querySelector('.ms-tier-flag'), `${label} 应标注未生效`).toBeTruthy()
    }
    // 多模态有真实调用方（LlmAgentLoop / VisionAnalyzeTool / ReadFileTool）⇒ 不得标未生效
    expect(tierRow('多模态模型').querySelector('.ms-tier-flag'), '多模态实际生效，不该标未生效').toBeFalsy()
    expect(tierRow('主模型').querySelector('.ms-tier-flag')).toBeFalsy()
  })

  /**
   * 「一键配置到全部档位」。
   *
   * <p><b>WHY</b>：9 个通用档位（主/最强/快速/子代理/弱/中等/多模态/降级/权限分类器）
   * 逐个绑同一个模型要点 9 次，实际上新手配置时几乎总是全绑同一个。但 TTS / ASR 是
   * **语音专用模型** —— 一键把对话模型写进去只会配错，故必须排除，且界面上要说清楚。
   *
   * <p>变异自证：把 TTS/ASR 加回 APPLY_ALL_ROLES → 第 2 条红（数量变 11）；
   * 去掉确认框直接调 onBindAllTiers → 第 1 条红（用户看不到要覆盖哪些档位）。
   */
  it('模型行的「一键配置」→ 先弹确认框，列出全部通用档位（不含 TTS/ASR）', async () => {
    draw(baseSettings({ strongModelName: 'deepseek/deepseek-r1' }))

    const btn = Array.from(container.querySelectorAll<HTMLButtonElement>('.ms-apply-all')).find(
      (b) => (b.closest('.ms-pick-item')?.textContent ?? '').includes('deepseek-r1'),
    )
    expect(btn, '模型行未渲染「一键配置」按钮').toBeTruthy()
    await act(async () => {
      btn!.click()
      await Promise.resolve()
    })

    const confirm = container.querySelector('.ms-confirm')
    expect(confirm, '未弹出确认框').toBeTruthy()
    const rows = Array.from(confirm!.querySelectorAll('.ms-confirm-row')).map((r) => r.textContent ?? '')
    // 9 个通用档位
    expect(rows.length).toBe(9)
    for (const label of ['主模型', '最强模型', '快速模型', '子代理', '弱模型', '中等模型', '多模态模型', '降级模型', '权限分类器']) {
      expect(rows.join(' ')).toContain(label)
    }
    // TTS / ASR 明确不在范围内
    expect(rows.join(' ')).not.toContain('TTS')
    expect(rows.join(' ')).not.toContain('ASR')
  })

  it('一键配置确认框点「确认配置」→ 调 onBindAllTiers（带模型全名）', async () => {
    draw(baseSettings())

    const btn = Array.from(container.querySelectorAll<HTMLButtonElement>('.ms-apply-all')).find(
      (b) => (b.closest('.ms-pick-item')?.textContent ?? '').includes('deepseek-r1'),
    )
    await act(async () => {
      btn!.click()
      await Promise.resolve()
    })
    const ok = Array.from(container.querySelectorAll<HTMLButtonElement>('.ms-confirm-foot .fm-btn')).find(
      (b) => (b.textContent ?? '').includes('确认配置'),
    )
    expect(ok, '确认框缺「确认配置」按钮').toBeTruthy()
    await act(async () => {
      ok!.click()
      await Promise.resolve()
    })

    expect(onBindAllTiers).toHaveBeenCalledTimes(1)
    expect(onBindAllTiers).toHaveBeenCalledWith('deepseek/deepseek-r1')
    expect(onBindTier).not.toHaveBeenCalled()
  })
})
