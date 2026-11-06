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
})

describe('sendSystemNotification · Tauri 通道', () => {
  it('Tauri 环境 → 走插件，不走 Web Notification', async () => {
    isTauriMock.mockReturnValue(true)
    const { instances } = installNotification('granted')
    const ok = await notify.sendSystemNotification({ title: '本轮已完成', body: '会话A' })
    expect(ok).toBe(true)
    expect(pluginMock.sendNotification).toHaveBeenCalledWith({ title: '本轮已完成', body: '会话A' })
    expect(instances, '不该同时走 Web 通道').toHaveLength(0)
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
    vi.doMock('@tauri-apps/plugin-notification', () => { throw new Error('plugin not installed') })
    vi.resetModules()
    const fresh = await import('../systemNotify')
    await expect(fresh.sendSystemNotification({ title: '本轮已完成' })).resolves.toBe(false)
  })
})
