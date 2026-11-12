// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SubagentRuntimePanel } from '../SubagentRuntimePanel'
import type { AppSettings, UpdateSettingsRequest } from '@/api/types'

vi.mock('@/api/subagentRuntimes', () => ({
  subagentRuntimeApi: {
    list: () => Promise.resolve({
      runtimes: [],
      availableCount: 2,
      current: { defaultRuntime: null, defaultAgentType: null, externalEnabled: null, configured: false },
      agents: [
        { agentType: 'claude-external', runtime: 'claude', description: '', baseRuntime: null, streamFormat: '', source: 'preset', available: true },
        { agentType: 'codex-external', runtime: 'codex', description: '', baseRuntime: null, streamFormat: '', source: 'preset', available: true },
      ],
    }),
  },
  cliRuntimeConfigApi: {
    list: () => Promise.resolve([
      {
        runtime: 'claude', displayName: 'Claude Code', tagline: 'Anthropic 官方 CLI',
        configurable: true, configDirEnv: 'CLAUDE_CONFIG_DIR', baseUrlEnv: 'ANTHROPIC_BASE_URL',
        apiKeyEnvs: ['ANTHROPIC_API_KEY'], binEnv: 'CLAUDE_BIN',
        configDir: null, baseUrl: null, secrets: {}, apiKeySet: false, bin: null,
        model: null, reasoning: null, configured: false, updatedAt: null,
        supportsReasoning: false, reasoningOptions: [], reasoningStyle: 'NONE',
        supportsModelList: false, fallbackModels: ['sonnet'],
        available: true, resolvedPath: 'C:/bin/claude.cmd',
      },
      {
        runtime: 'codex', displayName: 'Codex CLI', tagline: 'OpenAI 官方 CLI',
        configurable: true, configDirEnv: 'CODEX_HOME', baseUrlEnv: 'OPENAI_BASE_URL',
        apiKeyEnvs: ['CODEX_API_KEY'], binEnv: 'CODEX_BIN',
        configDir: null, baseUrl: null, secrets: {}, apiKeySet: false, bin: null,
        model: null, reasoning: null, configured: false, updatedAt: null,
        supportsReasoning: true, reasoningOptions: ['low'], reasoningStyle: 'CODEX_CONFIG',
        supportsModelList: true, fallbackModels: ['gpt-6.1-sol'],
        available: true, resolvedPath: 'C:/bin/codex.cmd',
      },
    ]),
    version: () => Promise.resolve({ runtime: 'codex', available: true, resolvedPath: 'C:/bin/codex.cmd', version: '1.0' }),
    models: () => Promise.resolve({ runtime: 'codex', resolvedPath: 'C:/bin/codex.cmd', live: false, models: ['gpt-6.1-sol'] }),
    save: () => Promise.resolve({ ok: true, runtime: 'codex', secrets: {}, apiKeySet: false }),
    test: () => Promise.resolve({ runtime: 'codex', ok: true, elapsedMs: 1, sample: 'ok', error: null }),
  },
}))

function baseSettings(over: Partial<AppSettings> = {}): AppSettings {
  return {
    theme: null, fontSize: null, accent: null, animationsEnabled: null,
    mainModelName: null, fastModelName: null, weakModelName: null, mediumModelName: null,
    strongModelName: null, subagentModelName: null, autoCompactWindow: null,
    fallbackModelName: null, multimodalModelName: null,
    ttsModelName: null, asrModelName: null, autoMemoryEnabled: null, autoMemoryDirectory: null,
    ...over,
  } as AppSettings
}

describe('SubagentRuntimePanel · 默认外部运行时', () => {
  let container: HTMLDivElement
  let root: Root
  let onSaveSettings: ReturnType<typeof vi.fn<(req: UpdateSettingsRequest) => Promise<boolean>>>
  let showToast: ReturnType<typeof vi.fn<(msg: string, kind?: 'success' | 'info') => void>>

  beforeEach(() => {
    ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    onSaveSettings = vi.fn(async () => true)
    showToast = vi.fn()
  })

  afterEach(() => {
    act(() => root.unmount())
    container.remove()
    vi.restoreAllMocks()
  })

  async function draw(settings: AppSettings) {
    await act(async () => {
      root.render(
        <SubagentRuntimePanel
          settings={settings}
          onSaveSettings={onSaveSettings}
          showToast={showToast}
        />,
      )
      await Promise.resolve()
    })
  }

  /** 某一行的「设为默认」按钮（按 CLI 显示名定位所在卡片）。 */
  function setDefaultButton(displayName: string): HTMLButtonElement {
    const card = Array.from(container.querySelectorAll<HTMLElement>('.cli-card')).find((c) =>
      (c.querySelector('.cli-card-title')?.textContent ?? '') === displayName,
    )
    if (!card) throw new Error(`未找到卡片：${displayName}`)
    const btn = card.querySelector<HTMLButtonElement>('.cli-card-action')
    if (!btn) throw new Error(`卡片 ${displayName} 没有「设为默认」按钮`)
    return btn
  }

  it('点「设为默认」→ 写的是 runtime id（codex），不是 agentType（codex-external）', async () => {
    await draw(baseSettings())

    await act(async () => {
      setDefaultButton('Codex CLI').click()
      await Promise.resolve()
    })

    expect(onSaveSettings).toHaveBeenCalledTimes(1)
    const req = onSaveSettings.mock.calls[0][0]
    expect(req).toEqual({ subagentDefaultRuntime: 'codex' })
    // ⛔ 绝不能是 agentType —— 后端只认 runtime id，传 agentType 一律 400
    expect(req.subagentDefaultRuntime).not.toBe('codex-external')
  })

  it('保存失败（后端 400）→ 不弹成功提示（否则用户以为配好了，DB 纹丝不动）', async () => {
    onSaveSettings = vi.fn(async () => false)
    await draw(baseSettings())

    await act(async () => {
      setDefaultButton('Codex CLI').click()
      await Promise.resolve()
    })

    expect(onSaveSettings).toHaveBeenCalledTimes(1)
    expect(showToast).not.toHaveBeenCalled()
  })

  it('保存成功 → 弹成功提示', async () => {
    await draw(baseSettings())

    await act(async () => {
      setDefaultButton('Codex CLI').click()
      await Promise.resolve()
    })

    expect(onSaveSettings).toHaveBeenCalledTimes(1)
    expect(showToast).toHaveBeenCalledTimes(1)
    expect(String(showToast.mock.calls[0][0])).toContain('Codex CLI')
  })
})
