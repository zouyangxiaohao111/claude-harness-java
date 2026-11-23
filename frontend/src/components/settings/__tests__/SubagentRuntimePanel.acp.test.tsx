// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SubagentRuntimePanel } from '../SubagentRuntimePanel'
import { cliRuntimeConfigApi } from '@/api/subagentRuntimes'
import type { CliRuntimeConfig } from '@/api/subagentRuntimes'

/**
 * ACP 运行时卡片（P2 后端已打通，前端尾巴）。
 *
 * <h2>WHY（意图验证）</h2>
 * <ul>
 *   <li><b>图标</b>：acp 是已知运行时 ⇒ 用 `public/cli-icons/acp.svg`；未知运行时必须回落
 *       首字母方块（⛔ 不能白框/裂图）。</li>
 *   <li><b>测试按钮三态</b>：ACP 的「进程起来了」≠「协议握手成功」。只看 `ok`/退出码会把
 *       `process_only` 说成成功 —— 用户以为配好了、实际派发必然失败（本仓最忌假成功）。
 *       文案严格按 probeLevel：acp_initialize=握手成功 / process_only=进程已起未握手 / failed=失败:原因。</li>
 *   <li><b>模型/推理控件</b>：ACP 没有通用模型枚举与推理档位语义（后端 supported:false）⇒
 *       ⛔ 不显示点了没用的控件。</li>
 * </ul>
 */

const ACP_CONFIG: CliRuntimeConfig = {
  runtime: 'acp',
  displayName: 'ACP 外部代理',
  tagline: 'Agent Client Protocol · stdio（外部 agent 运行时）',
  configurable: true,
  configDirEnv: null,
  baseUrlEnv: null,
  apiKeyEnvs: ['DEEPSEEK_API_KEY'],
  binEnv: null,
  configDir: null,
  baseUrl: null,
  secrets: {},
  apiKeySet: false,
  bin: null,
  model: null,
  reasoning: null,
  configured: false,
  updatedAt: null,
  supportsReasoning: false,
  reasoningOptions: [],
  reasoningStyle: 'NONE',
  supportsModelList: false,
  fallbackModels: [],
  available: true,
  resolvedPath: 'C:/bin/dsh.exe',
}

const UNKNOWN_CONFIG: CliRuntimeConfig = {
  ...ACP_CONFIG,
  runtime: 'mystery',
  displayName: '未知运行时',
  tagline: null,
  resolvedPath: 'C:/bin/mystery.exe',
}

vi.mock('@/api/subagentRuntimes', () => ({
  subagentRuntimeApi: {
    list: () => Promise.resolve({
      runtimes: [],
      availableCount: 2,
      current: { defaultRuntime: null, defaultAgentType: null, externalEnabled: null, configured: false },
      agents: [],
    }),
  },
  cliRuntimeConfigApi: {
    list: () => Promise.resolve([ACP_CONFIG, UNKNOWN_CONFIG]),
    version: () => Promise.resolve({ runtime: 'acp', available: true, resolvedPath: 'C:/bin/dsh.exe', version: null }),
    models: () => Promise.resolve({ runtime: 'acp', resolvedPath: 'C:/bin/dsh.exe', live: false, models: [] }),
    save: () => Promise.resolve({ ok: true, runtime: 'acp', secrets: {}, apiKeySet: false }),
    test: vi.fn(),
  },
}))

async function flush(ms = 0): Promise<void> {
  await act(async () => { await new Promise((r) => setTimeout(r, ms)) })
}

let host: HTMLDivElement
let root: Root | null = null

beforeEach(() => {
  ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  host = document.createElement('div')
  document.body.appendChild(host)
  root = createRoot(host)
  vi.mocked(cliRuntimeConfigApi.test).mockReset()
})

afterEach(() => {
  act(() => { root?.unmount() })
  root = null
  host.remove()
})

async function renderPanel(): Promise<void> {
  await act(async () => {
    root!.render(
      <SubagentRuntimePanel
        settings={null}
        onSaveSettings={async () => true}
        showToast={() => {}}
        sessionId="sess-1"
      />,
    )
  })
  await flush(10)
}

/** 找到某张卡片的整行点击区 */
function cardHit(title: string): HTMLElement {
  const card = Array.from(host.querySelectorAll('.cli-card'))
    .find((c) => (c.textContent ?? '').includes(title)) as HTMLElement | undefined
  expect(card, `找不到卡片：${title}`).toBeTruthy()
  return card!.querySelector('.cli-card-hit') as HTMLElement
}

async function openAcp(): Promise<void> {
  await act(async () => {
    cardHit('ACP 外部代理').dispatchEvent(new MouseEvent('click', { bubbles: true }))
  })
  await flush(10)
}

async function clickTest(): Promise<void> {
  const btn = host.querySelector('.cli-config .cli-test-btn') as HTMLElement
  expect(btn, '找不到测试按钮').toBeTruthy()
  await act(async () => { btn.dispatchEvent(new MouseEvent('click', { bubbles: true })) })
  await flush(10)
}

function testResultText(): string {
  const el = host.querySelector('.cli-test-result') as HTMLElement | null
  return (el?.textContent ?? '').trim()
}

describe('SubagentRuntimePanel · ACP 卡片', () => {
  it('acp 走品牌图标；未知运行时回落首字母方块', async () => {
    await renderPanel()

    const acpIcon = host.querySelector('.cli-icon-acp') as HTMLImageElement | null
    expect(acpIcon, 'acp 图标缺失（known 列表没加 acp？）').toBeTruthy()
    expect(acpIcon!.tagName).toBe('IMG')
    expect(acpIcon!.getAttribute('src')).toBe('/cli-icons/acp.svg')

    // 未知 runtime：不裂图、不空白 ⇒ 首字母方块
    const fallback = host.querySelector('.cli-icon-fallback') as HTMLElement | null
    expect(fallback, '未知运行时没有回落首字母').toBeTruthy()
    expect(fallback!.textContent).toBe('M')
  })

  it('acp 卡片展开后不显示模型 / 推理控件（后端 supported:false，⛔ 假控件）', async () => {
    await renderPanel()
    await openAcp()

    const titles = Array.from(host.querySelectorAll('.cli-config .cli-field-label'))
      .map((n) => (n.textContent ?? '').trim())
    expect(titles.some((t) => t.startsWith('模型')), `不应有模型控件：${titles.join('|')}`).toBe(false)
    expect(titles.some((t) => t.startsWith('推理强度')), `不应有推理控件：${titles.join('|')}`).toBe(false)
    // 但配置面板本身要在（测试按钮/保存仍在）
    expect(host.querySelector('.cli-config .cli-test-btn')).not.toBeNull()
  })

  it('测试按钮：acp_initialize ⇒ 「ACP 握手成功」', async () => {
    vi.mocked(cliRuntimeConfigApi.test).mockResolvedValue({
      runtime: 'acp', ok: true, elapsedMs: 42, sample: null, error: null, probeLevel: 'acp_initialize',
    })
    await renderPanel()
    await openAcp()
    await clickTest()

    expect(testResultText()).toContain('ACP 握手成功')
    expect(host.querySelector('.cli-test-result')!.classList.contains('is-ok')).toBe(true)
  })

  it('测试按钮：process_only ⇒ 「进程已起，未完成握手」（不冒充成功）', async () => {
    vi.mocked(cliRuntimeConfigApi.test).mockResolvedValue({
      runtime: 'acp', ok: true, elapsedMs: 900, sample: null, error: null, probeLevel: 'process_only',
    })
    await renderPanel()
    await openAcp()
    await clickTest()

    expect(testResultText()).toContain('进程已起，未完成握手')
    // ⛔ 后端 ok=true 也不能当成功：进程起来 ≠ 握手成功
    expect(host.querySelector('.cli-test-result')!.classList.contains('is-ok')).toBe(false)
  })

  it('测试按钮：failed ⇒ 「失败：原因」', async () => {
    vi.mocked(cliRuntimeConfigApi.test).mockResolvedValue({
      runtime: 'acp', ok: false, elapsedMs: 12, sample: null, error: 'initialize 超时', probeLevel: 'failed',
    })
    await renderPanel()
    await openAcp()
    await clickTest()

    expect(testResultText()).toBe('失败：initialize 超时')
    expect(host.querySelector('.cli-test-result')!.classList.contains('is-bad')).toBe(true)
  })

  it('测试请求本身失败（后端未放行 acp 的 400）⇒ 同样是「失败：原因」，不静默', async () => {
    vi.mocked(cliRuntimeConfigApi.test).mockRejectedValue(new Error("未知的外部运行时 'acp'"))
    await renderPanel()
    await openAcp()
    await clickTest()

    expect(testResultText()).toContain('失败：')
    expect(testResultText()).toContain("未知的外部运行时 'acp'")
  })
})
