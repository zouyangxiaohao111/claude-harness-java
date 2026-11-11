// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * 系统通知封装的行为契约。
 *
 * <p><b>为什么值得测</b>：这条链路有多处「静默失败」很容易被写坏而无人发现 ——
 * 通知是锦上添花，写坏了不会报错，只是**永远不弹**（用户只会说「没有通知」）。
 * 故这里钉住三条：
 * <ol>
 *   <li>⚠️ **不看窗口可见性**：用户盯着界面等结果时也必须弹
 *       （曾加过 `visibilityState === 'hidden'` 门槛，导致「完成了但没通知」）；</li>
 *   <li>浏览器未授权 ⇒ 不发，且**不**在异步事件里主动 requestPermission
 *       （浏览器要求用户手势，后台调必被静默拒 ⇒ 那行代码等于骗自己）；</li>
 *   <li>已授权 ⇒ 真的 new Notification，且同一 tag 覆盖上一条。</li>
 * </ol>
 */
const { isTauriMock } = vi.hoisted(() => ({ isTauriMock: vi.fn(() => false) }))
vi.mock('@tauri-apps/api/core', () => ({ isTauri: isTauriMock }))

const { pluginMock } = vi.hoisted(() => ({
  pluginMock: {
    isPermissionGranted: vi.fn(async () => true),
    requestPermission: vi.fn(async () => 'granted' as const),
    sendNotification: vi.fn(),
    registerActionTypes: vi.fn(async () => {}),
    // 参数类型显式标注：vi.fn 会从实现推断成「无参」⇒ mockImplementation 传回调时报类型错
    onAction: vi.fn(async (_cb: (e: unknown) => void) => ({ unregister: vi.fn(async () => {}) })),
  },
}))
vi.mock('@tauri-apps/plugin-notification', () => pluginMock)

type NotifyCtor = {
  new (title: string, opts?: NotificationOptions): { onclick: null | (() => void); close: () => void }
  permission: NotificationPermission
  requestPermission: ReturnType<typeof vi.fn>
}

/** 装一个假的 Notification 全局（jsdom 自身不实现它）。 */
function installNotification(permission: NotificationPermission) {
  const instances: { title: string; opts?: NotificationOptions; closed: boolean }[] = []
  class FakeNotification {
    static permission = permission
    static requestPermission = vi.fn(async () => permission)
    onclick: null | (() => void) = null
    private ref: { title: string; opts?: NotificationOptions; closed: boolean }
    constructor(title: string, opts?: NotificationOptions) {
      this.ref = { title, opts, closed: false }
      instances.push(this.ref)
    }
    close() { this.ref.closed = true }
  }
  ;(globalThis as unknown as { Notification: unknown }).Notification = FakeNotification as unknown as NotifyCtor
  return { instances, Fake: FakeNotification as unknown as NotifyCtor }
}

const setVisibility = (v: 'visible' | 'hidden') => {
  Object.defineProperty(document, 'visibilityState', { value: v, configurable: true })
}

let notify: typeof import('../systemNotify')

beforeEach(async () => {
  vi.resetModules()
  isTauriMock.mockReturnValue(false)
  pluginMock.isPermissionGranted.mockResolvedValue(true)
  pluginMock.requestPermission.mockResolvedValue('granted')
  pluginMock.sendNotification.mockClear()
  pluginMock.registerActionTypes.mockClear()
  pluginMock.onAction.mockClear()
  notify = await import('../systemNotify')
})

afterEach(() => {
  delete (globalThis as unknown as { Notification?: unknown }).Notification
  setVisibility('visible')
})

describe('sendSystemNotification · Web 通道', () => {
  it('已授权 → 真的弹通知，且带固定 tag（同会话连续结束不堆一屏）', async () => {
    const { instances } = installNotification('granted')
    const ok = await notify.sendSystemNotification({ title: '本轮已完成', body: '某会话' })
    expect(ok).toBe(true)
    expect(instances).toHaveLength(1)
    expect(instances[0]!.title).toBe('本轮已完成')
    expect(instances[0]!.opts?.body).toBe('某会话')
    expect(instances[0]!.opts?.tag).toBe('nexusai-turn-done')
  })

  it('未授权 → 不发，且**不**主动 requestPermission（异步事件里调必被浏览器静默拒）', async () => {
    const { instances, Fake } = installNotification('denied')
    const ok = await notify.sendSystemNotification({ title: '本轮已完成' })
    expect(ok).toBe(false)
    expect(instances).toHaveLength(0)
    expect(Fake.requestPermission, '⛔ 不得在后台主动请求权限').not.toHaveBeenCalled()
  })

  it('浏览器根本没有 Notification 实现 → 静默 false，不抛', async () => {
    delete (globalThis as unknown as { Notification?: unknown }).Notification
    await expect(notify.sendSystemNotification({ title: '本轮已完成' })).resolves.toBe(false)
  })

  it('空标题 → 不发（部分平台会直接拒绝）', async () => {
    const { instances } = installNotification('granted')
    expect(await notify.sendSystemNotification({ title: '   ' })).toBe(false)
    expect(instances).toHaveLength(0)
  })

  it('⚠️ 指定 tag → 用该 tag（不同语义的通知**不能共用默认 tag**，否则会互相顶掉）', async () => {
    const { instances } = installNotification('granted')
    const ok = await notify.sendSystemNotification({
      title: 'NexusAI · 需要你回答',
      body: '选哪个？',
      tag: 'nexusai-ask-user',
    })
    expect(ok).toBe(true)
    expect(instances[0]!.opts?.tag).toBe('nexusai-ask-user')
  })

  it('⚠️ 提问通知与轮次完成通知的 tag 必须不同（否则提问通知会把「本轮已完成」顶掉）', async () => {
    const { instances } = installNotification('granted')
    await notify.sendSystemNotification({ title: '本轮已完成' })
    await notify.sendSystemNotification({ title: '需要你回答', tag: 'nexusai-ask-user' })
    expect(instances).toHaveLength(2)
    expect(instances[0]!.opts?.tag).toBe('nexusai-turn-done')
    expect(instances[1]!.opts?.tag).toBe('nexusai-ask-user')
    expect(instances[0]!.opts?.tag).not.toBe(instances[1]!.opts?.tag)
  })

  it('空 tag → 回落默认 tag（不传 tag 的调用方行为不变）', async () => {
    const { instances } = installNotification('granted')
    await notify.sendSystemNotification({ title: '本轮已完成', tag: '   ' })
    expect(instances[0]!.opts?.tag).toBe('nexusai-turn-done')
  })
})

describe('sendSystemNotification · Tauri 通道', () => {
  it('Tauri 环境 → 走插件，不走 Web Notification', async () => {
    isTauriMock.mockReturnValue(true)
    const { instances } = installNotification('granted')
    const ok = await notify.sendSystemNotification({ title: '本轮已完成', body: '会话A' })
    expect(ok).toBe(true)
    expect(pluginMock.sendNotification).toHaveBeenCalledWith({
      title: '本轮已完成',
      body: '会话A',
      // ⚠️ actionTypeId 必须带：未注册 action type 时部分平台**不派发点击事件**
      //   ⇒ 用户点通知什么也不会发生（实测「点击不会把客户端拉到前台」的另一半原因）。
      actionTypeId: 'nexusai-notify',
    })
    expect(instances, '不该同时走 Web 通道').toHaveLength(0)
  })

  it('⚠️ 携带 extra 载荷（点击通知时回读 sessionId）', async () => {
    isTauriMock.mockReturnValue(true)
    installNotification('granted')
    const ok = await notify.sendSystemNotification({
      title: 'NexusAI · 需要你回答',
      extra: { sessionId: 'sess-1' },
    })
    expect(ok).toBe(true)
    const arg = pluginMock.sendNotification.mock.calls[0]![0] as { extra?: Record<string, unknown> }
    expect(arg.extra).toEqual({ sessionId: 'sess-1' })
  })

  it('不传 extra → 载荷字段整个不出现（不塞空对象）', async () => {
    isTauriMock.mockReturnValue(true)
    installNotification('granted')
    await notify.sendSystemNotification({ title: '本轮已完成' })
    const arg = pluginMock.sendNotification.mock.calls[0]![0] as Record<string, unknown>
    expect('extra' in arg).toBe(false)
  })

  it('Tauri 里权限未授予 → 先 requestPermission，仍拒则不发', async () => {
    isTauriMock.mockReturnValue(true)
    installNotification('denied')
    pluginMock.isPermissionGranted.mockResolvedValue(false)
    pluginMock.requestPermission.mockResolvedValue('denied' as never)

    const ok = await notify.sendSystemNotification({ title: '本轮已完成' })
    expect(ok).toBe(false)
    expect(pluginMock.requestPermission).toHaveBeenCalledTimes(1)
    expect(pluginMock.sendNotification).not.toHaveBeenCalled()
  })

  it('插件缺失（import 抛）→ 静默 false，不把异常抛给收口流程', async () => {
    isTauriMock.mockReturnValue(true)
    // 插件 API 抛错（模拟插件损坏 / IPC 断连）⇒ 必须静默 false，不能把异常抛给收口流程
    pluginMock.isPermissionGranted.mockRejectedValueOnce(new Error('plugin broken'))
    await expect(notify.sendSystemNotification({ title: '本轮已完成' })).resolves.toBe(false)
  })
})

describe('onSystemNotificationClick · 订阅通知点击', () => {
  it('Tauri 环境 → 注册 action type 并订阅 onAction', async () => {
    isTauriMock.mockReturnValue(true)
    const off = await notify.onSystemNotificationClick(() => {})
    expect(pluginMock.registerActionTypes).toHaveBeenCalledTimes(1)
    expect(pluginMock.onAction).toHaveBeenCalledTimes(1)
    off()
  })

  it('⚠️ 点击通知 → 回调收到 extra 载荷（App 据此切会话）', async () => {
    isTauriMock.mockReturnValue(true)
    const box: { handler: ((e: unknown) => void) | null } = { handler: null }
    pluginMock.onAction.mockImplementation(async (cb: (e: unknown) => void) => {
      box.handler = cb
      return { unregister: vi.fn(async () => {}) }
    })
    const seen: Record<string, unknown>[] = []
    await notify.onSystemNotificationClick((extra) => seen.push(extra))
    box.handler!({ actionId: 'tap', notification: { extra: { sessionId: 'sess-9' } } })
    expect(seen).toEqual([{ sessionId: 'sess-9' }])
  })

  it('点击通知但没有载荷 → 不回调（调用方自己处理「不知切哪个会话」）', async () => {
    isTauriMock.mockReturnValue(true)
    const box: { handler: ((e: unknown) => void) | null } = { handler: null }
    pluginMock.onAction.mockImplementation(async (cb: (e: unknown) => void) => {
      box.handler = cb
      return { unregister: vi.fn(async () => {}) }
    })
    const seen: Record<string, unknown>[] = []
    await notify.onSystemNotificationClick((extra) => seen.push(extra))
    box.handler!({ actionId: 'tap', notification: null })
    expect(seen).toHaveLength(0)
  })

  it('非 Tauri（浏览器）→ 不订阅，返回空操作函数', async () => {
    isTauriMock.mockReturnValue(false)
    const off = await notify.onSystemNotificationClick(() => {})
    expect(pluginMock.onAction).not.toHaveBeenCalled()
    expect(() => off()).not.toThrow()
  })
})
