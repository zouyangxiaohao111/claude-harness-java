// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { UpdateCenter } from '../UpdateCenter'

/**
 * 更新入口的接线守护（2026-11-18）。
 *
 * <p><b>WHY</b>：手动检查更新现在**只有**一个入口 —— 左下角用户行 → 设置 → 关于 → 「更新」。
 * 该按钮发 `nexusai:check-update` 事件，由本组件监听后转成 `check(true)`。
 * 这条链断掉的话，界面看起来一切正常、点下去却毫无反应（改之前就是死按钮：
 * 事件发了、全仓没有监听者）。同时钉死「右下角悬浮按钮已删除」，
 * 避免以后有人把 fab 加回来变成第二个入口。
 */

const { updateCheck, invoke } = vi.hoisted(() => ({ updateCheck: vi.fn(), invoke: vi.fn() }))

vi.mock('@/api/updater', () => ({
  updateCheck,
  updateDownload: vi.fn(),
  updateInstall: vi.fn(),
  onUpdateProgress: vi.fn(async () => () => {}),
  onUpdateReady: vi.fn(async () => () => {}),
  updaterSourcesFor: () => ['http://127.0.0.1/latest.json'],
  DEFAULT_UPDATER_SOURCES: [],
}))

vi.mock('@tauri-apps/api/core', () => ({ invoke }))
vi.mock('@tauri-apps/api/window', () => ({ getCurrentWindow: () => ({ close: vi.fn() }) }))

let container: HTMLDivElement
let root: Root

beforeEach(() => {
  ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  // 让组件判定为「桌面壳内」（否则直接 return null）
  ;(window as unknown as { __TAURI_INTERNALS__?: unknown }).__TAURI_INTERNALS__ = {}
  invoke.mockResolvedValue('2.0.0')
  updateCheck.mockResolvedValue({
    available: true, version: '2.0.1', notes: '· 修复若干问题',
    published: null, url: 'http://127.0.0.1/app.dmg', sha256: 'x', source: 'http://127.0.0.1/latest.json',
  })
})

afterEach(() => {
  act(() => { root.unmount() })
  container.remove()
  delete (window as unknown as { __TAURI_INTERNALS__?: unknown }).__TAURI_INTERNALS__
  vi.clearAllMocks()
})

function renderCenter() {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  act(() => { root.render(<UpdateCenter appName="nexusai" />) })
}

describe('[更新中心] 设置里的「更新」真的能触发检查', () => {
  it('没点之前不检查；发 nexusai:check-update ⇒ 真的去查了并弹出「发现新版本」', async () => {
    // 挂起检查结果，好把「检查中 → 发现新版本」两个态分开断言（mock 同步 resolve 会压成一帧）
    let resolveCheck!: (v: unknown) => void
    updateCheck.mockImplementation(() => new Promise((r) => { resolveCheck = r }))

    renderCenter()
    expect(updateCheck).not.toHaveBeenCalled()

    await act(async () => { window.dispatchEvent(new Event('nexusai:check-update')) })
    // 手动检查：先进入 checking（这里什么都不用点，是事件接线的证据）
    expect(container.textContent).toContain('检查更新中')
    expect(updateCheck).toHaveBeenCalledTimes(1)
    expect(updateCheck.mock.calls[0][0]).toBe('2.0.0')     // 当前版本由 app_version 读出

    await act(async () => {
      resolveCheck({
        available: true, version: '2.0.1', notes: '· 修复若干问题',
        published: null, url: 'http://127.0.0.1/app.dmg', sha256: 'x', source: 'http://127.0.0.1/latest.json',
      })
      await Promise.resolve()
    })
    expect(container.textContent).toContain('发现新版本')
    expect(container.textContent).toContain('2.0.1')
  })

  it('右下角悬浮按钮已删除（手动入口只保留设置一处）', () => {
    renderCenter()
    expect(container.querySelector('.ud-fab')).toBeNull()
    expect(container.querySelector('.ud-backdrop')).toBeNull()   // idle 时不渲染遮罩
  })
})
