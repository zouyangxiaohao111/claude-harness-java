// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { TerminalPanel } from '@/components/right/TerminalPanel'
import { terminalApi, type TerminalInfo } from '@/api/terminal'

/**
 * 意图（为什么不只是「断言调了什么」）：
 * - 无终端时**绝不能自动创建** —— 用户 2026-10-29 裁定「终端只由用户手动创建，Agent 只读」。
 *   若回归成挂载即 create，用户会莫名被起一个进程（后端真起 PTY），单测必须拦住。
 * - 关闭 tab 必须真的调 DELETE 并从界面移除：否则 PTY 变孤儿进程、UI 还挂着死终端。
 */

/** xterm 实例登记（jsdom 无法真渲染 canvas，只桩掉并记录调用） */
const termInstances: Array<{
  open: ReturnType<typeof vi.fn>
  write: ReturnType<typeof vi.fn>
  clear: ReturnType<typeof vi.fn>
  dispose: ReturnType<typeof vi.fn>
  loadAddon: ReturnType<typeof vi.fn>
  onData: ReturnType<typeof vi.fn>
  cols: number
  rows: number
}> = []

vi.mock('@xterm/xterm', () => {
  class FakeTerminal {
    cols = 80
    rows = 24
    loadAddon = vi.fn()
    open = vi.fn()
    write = vi.fn()
    clear = vi.fn()
    focus = vi.fn()
    dispose = vi.fn()
    onData = vi.fn(() => ({ dispose: () => {} }))
    constructor() { termInstances.push(this as never) }
  }
  return { Terminal: FakeTerminal }
})

vi.mock('@xterm/addon-fit', () => ({
  FitAddon: class { fit = vi.fn() },
}))

vi.mock('@/api/socket', () => ({
  onSocketConnect: () => () => {},
}))

vi.mock('@/api/terminal', () => ({
  terminalApi: {
    list: vi.fn(),
    create: vi.fn(),
    input: vi.fn(),
    resize: vi.fn(),
    close: vi.fn(),
    snapshot: vi.fn(),
  },
}))

const info = (over: Partial<TerminalInfo> = {}): TerminalInfo => ({
  id: 'term-1',
  shell: 'powershell.exe',
  cwd: 'D:\\proj',
  cols: 80,
  rows: 24,
  alive: true,
  startedAt: '2026-10-29T00:00:00Z',
  ...over,
})

describe('TerminalPanel', () => {
  let container: HTMLDivElement
  let root: Root

  beforeEach(() => {
    ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    termInstances.length = 0
    vi.mocked(terminalApi.list).mockReset()
    vi.mocked(terminalApi.create).mockReset()
    vi.mocked(terminalApi.close).mockReset()
    vi.mocked(terminalApi.snapshot).mockReset()
    vi.mocked(terminalApi.snapshot).mockResolvedValue({ lines: 'PS> ready' })
    vi.mocked(terminalApi.resize).mockResolvedValue(undefined)
    vi.mocked(terminalApi.input).mockResolvedValue(undefined)
    vi.mocked(terminalApi.close).mockResolvedValue(undefined)
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
  })

  afterEach(() => {
    act(() => { root.unmount() })
    container.remove()
  })

  /** 挂载 + 把 list/snapshot 的 Promise 链跑完（渲染 → then → finally → re-render） */
  async function renderPanel() {
    await act(async () => {
      root.render(<TerminalPanel sessionId="s1" />)
    })
    await act(async () => { await new Promise((r) => setTimeout(r, 0)) })
  }

  function clickText(text: string) {
    const btn = Array.from(container.querySelectorAll('button')).find((b) => (b.textContent ?? '').includes(text))
    expect(btn, `找不到按钮：${text}`).toBeTruthy()
    return act(async () => { btn!.dispatchEvent(new MouseEvent('click', { bubbles: true })) })
  }

  it('无终端：显示空态与「新建终端」，且**不自动**调用 create', async () => {
    vi.mocked(terminalApi.list).mockResolvedValue([])
    await renderPanel()

    expect(container.querySelector('.term-empty')).not.toBeNull()
    expect(container.textContent).toContain('当前会话还没有终端')
    expect(container.textContent).toContain('新建终端')
    // 用户裁定：终端只由用户手动创建 —— 挂载不得产生任何终端
    expect(terminalApi.create).not.toHaveBeenCalled()
    expect(terminalApi.list).toHaveBeenCalledWith('s1')
  })

  it('有终端：渲染标签（shell 短名）与 xterm 容器', async () => {
    vi.mocked(terminalApi.list).mockResolvedValue([info()])
    await renderPanel()

    const tab = container.querySelector('.term-tab')
    expect(tab).not.toBeNull()
    expect(tab!.querySelector('.nm')?.textContent).toBe('PowerShell')
    expect(container.querySelector('.term-screen')).not.toBeNull()
    // 一个终端 = 一个 xterm 实例（切换 tab 不销毁重建）
    expect(termInstances).toHaveLength(1)
    expect(termInstances[0].open).toHaveBeenCalledTimes(1)
    // 首次 attach：先 snapshot 回放
    expect(terminalApi.snapshot).toHaveBeenCalledWith('s1', 'term-1')
  })

  it('点「新建终端」→ 调 create 一次并切到新终端', async () => {
    vi.mocked(terminalApi.list).mockResolvedValue([])
    const created = info({ id: 'term-2', shell: 'cmd.exe' })
    vi.mocked(terminalApi.create).mockResolvedValue(created)

    await renderPanel()
    await clickText('新建终端')
    await act(async () => { await new Promise((r) => setTimeout(r, 0)) })

    expect(terminalApi.create).toHaveBeenCalledTimes(1)
    expect(terminalApi.create).toHaveBeenCalledWith('s1')
    expect(container.querySelectorAll('.term-tab')).toHaveLength(1)
    expect(container.querySelector('.term-tab .nm')?.textContent).toBe('cmd')
  })

  it('STOMP：订阅会话终端 topic，data 写入 xterm，closed 帧标记已退出', async () => {
    // 复用 App 的既有连接：组件只 subscribe，不新开连接（topic = /topic/sessions/{sid}/terminal/{tid}）
    const subs: Array<{ topic: string; cb: (m: { body: string }) => void }> = []
    const fakeClient = {
      connected: true,
      subscribe: (topic: string, cb: (m: { body: string }) => void) => {
        subs.push({ topic, cb })
        return { unsubscribe: vi.fn() }
      },
    }
    vi.mocked(terminalApi.list).mockResolvedValue([info()])
    await act(async () => {
      root.render(<TerminalPanel sessionId="s1" clientRef={{ current: fakeClient as never }} />)
    })
    await act(async () => { await new Promise((r) => setTimeout(r, 0)) })

    expect(subs).toHaveLength(1)
    expect(subs[0].topic).toBe('/topic/sessions/s1/terminal/term-1')

    // 数据帧 → term.write 原样写入（不重新编码/拼接）
    await act(async () => { subs[0].cb({ body: JSON.stringify({ terminalId: 'term-1', data: 'hi\r\n', closed: false }) }) })
    expect(termInstances[0].write).toHaveBeenCalledWith('hi\r\n')

    // 退出帧 → 提示 + 状态改为已退出（输入由 closed 标记禁用）
    await act(async () => { subs[0].cb({ body: JSON.stringify({ terminalId: 'term-1', closed: true, exitCode: 0 }) }) })
    await act(async () => { await new Promise((r) => setTimeout(r, 0)) })
    expect(container.querySelector('.term-status')?.textContent).toContain('已退出')
    expect(container.textContent).toContain('输入已禁用')
  })

  it('点 tab 上的 × → 调 close 一次并从界面移除（最后一个 → 回空态）', async () => {
    vi.mocked(terminalApi.list).mockResolvedValue([info()])
    await renderPanel()

    const x = container.querySelector('[aria-label="关闭终端 PowerShell"]')
    expect(x).not.toBeNull()
    await act(async () => { (x as HTMLElement).dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    await act(async () => { await new Promise((r) => setTimeout(r, 0)) })

    expect(terminalApi.close).toHaveBeenCalledTimes(1)
    expect(terminalApi.close).toHaveBeenCalledWith('s1', 'term-1')
    expect(container.querySelector('.term-tab')).toBeNull()
    expect(container.querySelector('.term-empty')).not.toBeNull()
    // 实例随关闭销毁（不留后台订阅/定时器）
    expect(termInstances[0].dispose).toHaveBeenCalledTimes(1)
  })
})
