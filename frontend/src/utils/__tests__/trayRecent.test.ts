// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * 托盘「最近任务」的前端侧接线。
 *
 * <p><b>为什么值得测</b>：这条链路有三处「静默失败」——写坏了不会报错，
 * 只是托盘里**永远没有最近任务**（用户只会说「没看到」）：
 * <ol>
 *   <li>浏览器环境误发 IPC（会抛，被 catch 吞掉，看起来像没实现）；</li>
 *   <li>去重判据写错 ⇒ 要么每次都发（流式期间 sessions 频繁换引用 → IPC 风暴），
 *       要么永远不发（第一次之后再也不更新）；</li>
 *   <li>点击事件里的空 / 非法 payload 直接调回调 ⇒ 宿主拿空 id 去切会话。</li>
 * </ol>
 */
const { isTauriMock, invokeMock, listenMock, handlers } = vi.hoisted(() => {
  const handlers = new Map<string, (e: { payload: unknown }) => void>()
  return {
    isTauriMock: vi.fn(() => true),
    invokeMock: vi.fn(async () => undefined),
    listenMock: vi.fn(async (event: string, cb: (e: { payload: unknown }) => void) => {
      handlers.set(event, cb)
      return () => { handlers.delete(event) }
    }),
    handlers,
  }
})

vi.mock('@tauri-apps/api/core', () => ({ isTauri: isTauriMock, invoke: invokeMock }))
vi.mock('@tauri-apps/api/event', () => ({ listen: listenMock }))

let tray: typeof import('../trayRecent')

beforeEach(async () => {
  vi.resetModules()
  isTauriMock.mockReturnValue(true)
  invokeMock.mockClear()
  listenMock.mockClear()
  handlers.clear()
  tray = await import('../trayRecent')
})

describe('updateTrayRecent', () => {
  it('Tauri 环境 → 真的调 invoke，并只推前 5 条（与 Rust 的 TRAY_RECENT_LIMIT 对齐）', async () => {
    const list = Array.from({ length: 8 }, (_, i) => ({ id: `sess-${i}`, title: `任务${i}` }))
    const ok = await tray.updateTrayRecent(list)
    expect(ok).toBe(true)
    expect(invokeMock).toHaveBeenCalledTimes(1)
    const [cmd, args] = invokeMock.mock.calls[0] as unknown as [string, { tasks: unknown[] }]
    expect(cmd).toBe('update_tray_recent')
    expect(args.tasks).toHaveLength(5)
    expect(args.tasks[0]).toEqual({ id: 'sess-0', title: '任务0' })
  })

  it('内容没变 → 不再发 IPC（流式期间 sessions 引用频繁变化，不去重会打爆 IPC）', async () => {
    const list = [{ id: 'sess-1', title: '任务' }]
    expect(await tray.updateTrayRecent(list)).toBe(true)
    // 同一内容的新数组（模拟 store 换引用）
    expect(await tray.updateTrayRecent([{ id: 'sess-1', title: '任务' }])).toBe(false)
    expect(invokeMock).toHaveBeenCalledTimes(1)
  })

  it('内容变了（标题改名）→ 必须重发（否则托盘里永远显示旧标题）', async () => {
    await tray.updateTrayRecent([{ id: 'sess-1', title: '旧标题' }])
    const ok = await tray.updateTrayRecent([{ id: 'sess-1', title: '新标题' }])
    expect(ok).toBe(true)
    expect(invokeMock).toHaveBeenCalledTimes(2)
  })

  it('当前会话变了 → 必须重发（列表内容不变，但子菜单里的 ✓ 要跟着移）', async () => {
    const list = [
      { id: 'sess-1', title: '任务一' },
      { id: 'sess-2', title: '任务二' },
    ]
    expect(await tray.updateTrayRecent(list, 'sess-1')).toBe(true)
    // 同一个列表、只切了当前会话 ⇒ 去重签名必须把 currentId 算进去，否则勾选不跟着动
    expect(await tray.updateTrayRecent(list, 'sess-2')).toBe(true)
    expect(invokeMock).toHaveBeenCalledTimes(2)
    const calls = invokeMock.mock.calls as unknown as [unknown, { currentId: string | null }][]
    expect(calls[calls.length - 1]![1].currentId).toBe('sess-2')
  })

  it('currentId 缺省 → 传 null（Rust 侧全部不打勾）', async () => {
    await tray.updateTrayRecent([{ id: 'sess-1', title: '任务' }])
    const args = (invokeMock.mock.calls as unknown as [unknown, { currentId: string | null }][])[0]![1]
    expect(args.currentId).toBeNull()
  })

  it('非 Tauri（浏览器 3000）→ 不发 IPC，返回 false', async () => {
    isTauriMock.mockReturnValue(false)
    expect(await tray.updateTrayRecent([{ id: 'sess-1', title: '任务' }])).toBe(false)
    expect(invokeMock).not.toHaveBeenCalled()
  })

  it('IPC 抛错（托盘未建 / 命令未注册）→ 静默 false，不把异常抛给调用方', async () => {
    invokeMock.mockRejectedValueOnce(new Error('command not found'))
    await expect(tray.updateTrayRecent([{ id: 'sess-1', title: '任务' }])).resolves.toBe(false)
  })
})

describe('onTraySwitchSession', () => {
  it('收到会话 id → 回调被调用', async () => {
    const cb = vi.fn()
    await tray.onTraySwitchSession(cb)
    handlers.get('tray://switch-session')!({ payload: 'sess-abc' })
    expect(cb).toHaveBeenCalledWith('sess-abc')
  })

  it('空 / 非字符串 payload → 不回调（⛔ 不能让宿主拿空 id 去切会话）', async () => {
    const cb = vi.fn()
    await tray.onTraySwitchSession(cb)
    const h = handlers.get('tray://switch-session')!
    h({ payload: '' })
    h({ payload: '   ' })
    h({ payload: null })
    h({ payload: 42 })
    expect(cb).not.toHaveBeenCalled()
  })

  it('返回的取消函数真的解除监听', async () => {
    const cb = vi.fn()
    const off = await tray.onTraySwitchSession(cb)
    off()
    expect(handlers.has('tray://switch-session')).toBe(false)
  })

  it('非 Tauri 环境 → 不订阅，返回 no-op', async () => {
    isTauriMock.mockReturnValue(false)
    const off = await tray.onTraySwitchSession(vi.fn())
    expect(listenMock).not.toHaveBeenCalled()
    expect(() => off()).not.toThrow()
  })
})
