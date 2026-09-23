// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * `LaunchGate` 错误卡的日志路径文案（appName 通道 2026-09-23）。
 *
 * <b>WHY（规则九 · 测试验证意图而非行为）</b>：这条提示是<b>用户在后端起不来时唯一能顺着走的路</b>
 * （「请查看 ~/.{dir}/logs/backend.log 了解原因」）。目录名改成由 appName 派生后，有两类静默错误
 * 只能靠渲染断言抓住：
 * <ol>
 *   <li><b>模板拼接写坏</b>（多余空格 / 少一个斜杠）⇒ 提示指向一个不存在的路径，用户照提示找不到任何东西；</li>
 *   <li><b>回落失效</b>：首装即失败（缓存两层都空）时必须仍是 `.nexusai`（与落地前逐字相同），
 *       否则会给出一条凭空猜出来的目录名。</li>
 * </ol>
 *
 * <p>⚠️ 本页出现在<b>后端起不来</b>时 ⇒ `GET /settings` 结构上拉不到，只能读 {@link appNameCache}
 * 的缓存；故两个用例分别钉「无缓存」与「有缓存」两态。取模块实例用 `vi.resetModules()`
 * （缓存有内存层）⇒ 断言与用例顺序无关。
 */

/** 就绪超时（LaunchGate READY_TIMEOUT_MS=30_000）之后一档，确保已进 error 卡。 */
const PAST_TIMEOUT_MS = 31_000

let container: HTMLDivElement
let root: Root

beforeEach(() => {
  (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  vi.resetModules()
  vi.useFakeTimers()
  localStorage.clear()
  // 后端起不来：探活永远失败，只能等超时进错误卡
  vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new Error('backend down'))))
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
})

afterEach(() => {
  act(() => root.unmount())
  container.remove()
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

/** 渲染 LaunchGate 并推进到错误卡；返回新取的（同一 registry 的）模块对。 */
async function renderErrorCard() {
  const gate = await import('@/components/startup/LaunchGate')
  const cache = await import('@/utils/appNameCache')
  act(() => root.render(<gate.LaunchGate><div>主界面</div></gate.LaunchGate>))
  await act(async () => {
    await vi.advanceTimersByTimeAsync(PAST_TIMEOUT_MS)
  })
  return cache
}

function hintText(): string {
  return container.querySelector('.cl-errHint')?.textContent ?? ''
}

describe('[appName 通道 2026-09-23] LaunchGate 错误卡的日志路径', () => {
  it('无任何缓存（首装即失败）⇒ 逐字回落 ~/.nexusai/logs/backend.log（整段文案逐字相同，不止子串）', async () => {
    await renderErrorCard()
    // 整段比对而非子串：拼接写坏（多空格 / 少斜杠 / 换行错位）也必须被抓到。
    // 期望串 = 改造前该 <div> 的 textContent（两行文本由 <br/> 相连、之间无分隔符）。
    expect(hintText()).toBe('请查看 ~/.nexusai/logs/backend.log 了解原因；如持续失败，请关闭应用后重新打开。')
  })

  it('有缓存（此前成功加载过 nexusai-scene）⇒ 路径跟着 appName 走', async () => {
    const cache = await renderErrorCard()
    expect(hintText()).toContain('~/.nexusai/logs/backend.log')

    // 缓存由 App 在 GET /settings 成功后写入 ⇒ 这里直接写，模拟「上次运行成功过」
    cache.rememberAppName('nexusai-scene')
    await act(async () => {
      root.render(<div />)
      await Promise.resolve()
    })
    const gate = await import('@/components/startup/LaunchGate')
    act(() => root.render(<gate.LaunchGate><div>主界面</div></gate.LaunchGate>))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(PAST_TIMEOUT_MS)
    })
    expect(hintText()).toContain('~/.nexusai-scene/logs/backend.log')
  })
})
