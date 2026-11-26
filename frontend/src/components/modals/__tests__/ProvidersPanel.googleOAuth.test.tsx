// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ProvidersPanel } from '../ProvidersPanel'
import { providerApi } from '@/api/providers'
import type { GoogleOAuthStatus, Provider } from '@/api/types'
import type { UseProviders } from '@/hooks/useProviders'

/**
 * ProvidersPanel · Google 订阅（Gemini Code Assist）登录链。
 *
 * WHY：订阅登录是显式动作 —— 进页面 / 点卡片只读 official/status，⛔ 不自动 ensure / authorize；
 * 轮询以服务端 expiresAt 为截止（不前端硬裁）；切卡 / 退出 / 卸载必须停 timer 且晚到回包不得污染；
 * complete ok ≠ ready，必须再读 status 确认。Google 与 ChatGPT/Claude 的 pending/paste 完全隔离。
 *
 * 变异自证：把 ready 收口里的 `await refresh()` 去掉 → 「ready 收口刷新列表」红。
 */

vi.mock('@/api/providers', () => ({
  providerApi: {
    list: vi.fn(async () => []),
    get: vi.fn(async () => { throw new Error('不应调用 codex 轮询 get') }),
    officialCodex: vi.fn(async () => null),
    listBuiltin: vi.fn(async () => []),
    officialGoogle: vi.fn(async () => null),
    ensureOfficialGoogle: vi.fn(),
    oauthGoogleAuthorize: vi.fn(),
    oauthGoogleComplete: vi.fn(),
    oauthGoogleStatus: vi.fn(),
    oauthGoogleLogout: vi.fn(async () => {}),
  },
}))
const tauriMock = vi.hoisted(() => ({
  is: false,
  openUrl: vi.fn(async (_url: string) => {}),
}))
vi.mock('@tauri-apps/api/core', () => ({ isTauri: () => tauriMock.is }))
vi.mock('@tauri-apps/plugin-opener', () => ({ openUrl: tauriMock.openUrl }))

const AUTH_URL = 'https://accounts.google.com/o/oauth2/v2/auth?client_id=TEST-ONLY&state=test-state'

const GOOGLE_ENTRY: Provider = {
  id: 'g-1',
  name: 'Google',
  type: 'gemini_code_assist',
  baseUrl: 'https://cloudcode-pa.googleapis.com',
  apiKeyMasked: '',
  extraHeaders: null,
  enabled: true,
  models: [],
  authMode: 'api_key',
}

const st = (status: GoogleOAuthStatus['status'], extra?: Partial<GoogleOAuthStatus>): GoogleOAuthStatus =>
  ({ status, oauthProject: null, expiresAt: null, message: null, ...extra })

/** 最小内置品牌快照：让左栏「国际」组存在（OpenAI / Google 常驻卡挂在该组）。 */
const BUILTIN_FIXTURE = [
  {
    key: 'claude', group: '国际', name: 'Claude', type: 'anthropic' as const,
    baseUrl: 'https://api.anthropic.com', subtitle: '', configured: false,
    providerId: null, providerName: null, modelCount: 0,
  },
]

function makeApi(list: Provider[] = []) {
  return {
    list,
    loading: false,
    error: null,
    refresh: vi.fn(async () => {}),
    createProvider: vi.fn(async () => GOOGLE_ENTRY),
    updateProvider: vi.fn(async () => GOOGLE_ENTRY),
    deleteProvider: vi.fn(async () => {}),
    toggleProvider: vi.fn(async () => GOOGLE_ENTRY),
    createModel: vi.fn(async () => {}),
    updateModel: vi.fn(async () => {}),
    deleteModel: vi.fn(async () => {}),
  } as unknown as UseProviders
}

const mocked = vi.mocked(providerApi)

describe('ProvidersPanel · Google 订阅登录', () => {
  let container: HTMLDivElement
  let root: Root
  let toast: ReturnType<typeof vi.fn<(msg: string, type?: 'success' | 'info') => void>>
  let openSpy: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    vi.useFakeTimers()
    ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    toast = vi.fn()
    openSpy = vi.spyOn(window, 'open').mockImplementation(() => null)
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    vi.clearAllMocks()
    tauriMock.is = false
    tauriMock.openUrl.mockImplementation(async () => {})
    mocked.officialCodex.mockResolvedValue(null)
    mocked.listBuiltin.mockResolvedValue(BUILTIN_FIXTURE)
    mocked.officialGoogle.mockResolvedValue(null)
    mocked.oauthGoogleLogout.mockResolvedValue(undefined)
  })

  afterEach(() => {
    act(() => root.unmount())
    container.remove()
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  function draw(api: UseProviders = makeApi()) {
    act(() => {
      root.render(<ProvidersPanel providers={api.list} providersApi={api} showToast={toast} />)
    })
  }

  /** 冲刷微任务（fake timers 下 promise 链不靠时钟推进）。 */
  async function flush() {
    await act(async () => {
      for (let i = 0; i < 8; i++) await Promise.resolve()
    })
  }

  async function tick(ms = 2000) {
    await act(async () => {
      vi.advanceTimersByTime(ms)
      for (let i = 0; i < 8; i++) await Promise.resolve()
    })
  }

  function byText(sel: string, text: string): HTMLElement {
    const el = Array.from(container.querySelectorAll<HTMLElement>(sel)).find(
      (e) => (e.textContent ?? '').trim() === text,
    )
    if (!el) throw new Error(`未找到 ${sel}：${text}`)
    return el
  }

  function railCard(name: string): HTMLButtonElement {
    const el = Array.from(container.querySelectorAll<HTMLButtonElement>('.pv-rail-card')).find(
      (b) => (b.textContent ?? '').includes(name),
    )
    if (!el) throw new Error(`未找到左栏卡：${name}`)
    return el
  }

  function activeViewText(): string {
    return container.querySelector('.pv-view[data-active="true"]')?.textContent ?? ''
  }

  function click(el: HTMLElement) {
    act(() => el.click())
  }

  /** 走一次显式登录：点卡 → 点登录按钮 → ensure/authorize/openExternal。 */
  async function login(expiresAt = Date.now() + 120000) {
    mocked.ensureOfficialGoogle.mockResolvedValue(GOOGLE_ENTRY)
    mocked.oauthGoogleAuthorize.mockResolvedValue({ url: 'https://accounts.google.com/o/oauth2/v2/auth?x=1', expiresAt })
    click(railCard('Google 订阅'))
    await flush()
    click(byText('button', '登录 Google 订阅'))
    await flush()
  }

  function typePaste(value: string) {
    const ta = container.querySelector<HTMLTextAreaElement>('#google-oauth-paste')
    if (!ta) throw new Error('未找到 Google 粘贴框')
    const setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value')?.set
    act(() => {
      setter?.call(ta, value)
      ta.dispatchEvent(new Event('input', { bubbles: true }))
    })
  }

  /** 指定授权 URL 的显式登录（回归用例用）。 */
  async function loginWithUrl(url: string, expiresAt = Date.now() + 120000) {
    mocked.ensureOfficialGoogle.mockResolvedValue(GOOGLE_ENTRY)
    mocked.oauthGoogleAuthorize.mockResolvedValue({ url, expiresAt })
    click(railCard('Google 订阅'))
    await flush()
    click(byText('button', '登录 Google 订阅'))
    await flush()
  }

  /** 用户能否从 DOM 拿到授权 URL（展示 / href / 输入框值均算）。 */
  function urlReachable(url: string): boolean {
    if (container.innerHTML.includes(url)) return true
    return Array.from(container.querySelectorAll<HTMLInputElement | HTMLTextAreaElement>('input,textarea'))
      .some((el) => (el.value ?? '').includes(url))
  }

  function mockClipboard(impl: (text: string) => Promise<void>) {
    Object.defineProperty(navigator, 'clipboard', { value: { writeText: vi.fn(impl) }, configurable: true })
  }

  it('首次无连接：挂载与点卡都不 ensure / 不 authorize，204 显示未配置 + 常驻风险提示', async () => {
    draw()
    await flush()
    expect(mocked.officialGoogle).toHaveBeenCalledTimes(1)
    expect(mocked.ensureOfficialGoogle).not.toHaveBeenCalled()
    expect(mocked.oauthGoogleAuthorize).not.toHaveBeenCalled()
    expect(railCard('Google 订阅').textContent).toContain('未配置')

    click(railCard('Google 订阅'))
    await flush()
    expect(mocked.ensureOfficialGoogle).not.toHaveBeenCalled()
    expect(activeViewText()).toContain('还没有登录 Google 订阅')
    expect(activeViewText()).toContain('账号可能被暂停或终止')
    expect(byText('button', '登录 Google 订阅')).toBeTruthy()
  })

  it('显式登录：ensure → authorize → 打开外部浏览器；轮询 ready 收口并刷新列表', async () => {
    const api = makeApi()
    mocked.oauthGoogleStatus
      .mockResolvedValueOnce(st('pending'))
      .mockResolvedValue(st('ready', { oauthProject: 'proj-1', expiresAt: Date.now() + 3600000 }))
    draw(api)
    await flush()
    await login()

    expect(mocked.ensureOfficialGoogle).toHaveBeenCalledTimes(1)
    expect(mocked.oauthGoogleAuthorize).toHaveBeenCalledWith('g-1')
    expect(openSpy).toHaveBeenCalledWith('https://accounts.google.com/o/oauth2/v2/auth?x=1', '_blank', 'noopener,noreferrer')
    expect(activeViewText()).toContain('等待浏览器授权')
    // 登录本身会 refresh 一次；ready 收口必须再刷新（变异去掉 ready 里的 refresh → 本断言红）
    const refreshBefore = (api.refresh as ReturnType<typeof vi.fn>).mock.calls.length

    await tick()
    expect(activeViewText()).toContain('等待浏览器授权')
    await tick()
    expect((api.refresh as ReturnType<typeof vi.fn>).mock.calls.length).toBeGreaterThan(refreshBefore)
    expect(toast).toHaveBeenCalledWith('Google 订阅已就绪', 'success')
    expect(activeViewText()).toContain('订阅连接正常')

    const calls = mocked.oauthGoogleStatus.mock.calls.length
    await tick(6000)
    expect(mocked.oauthGoogleStatus.mock.calls.length).toBe(calls)
  })

  it('authorize 失败：落 error 终态，不起轮询', async () => {
    mocked.ensureOfficialGoogle.mockResolvedValue(GOOGLE_ENTRY)
    mocked.oauthGoogleAuthorize.mockRejectedValue(new Error('网络错误'))
    draw()
    await flush()
    click(railCard('Google 订阅'))
    await flush()
    click(byText('button', '登录 Google 订阅'))
    await flush()

    expect(activeViewText()).toContain('登录未完成')
    expect(activeViewText()).toContain('网络错误')
    await tick(10000)
    expect(mocked.oauthGoogleStatus).not.toHaveBeenCalled()
  })

  it('手动完成：整条回调 URL 与裸 code 都 POST complete；ok 后读 status 确认 ready 才刷新', async () => {
    for (const input of ['http://127.0.0.1:43210/oauth2callback?code=abc&state=s1', 'bare-code-xyz']) {
      vi.clearAllMocks()
      mocked.officialCodex.mockResolvedValue(null)
      mocked.listBuiltin.mockResolvedValue(BUILTIN_FIXTURE)
      mocked.officialGoogle.mockResolvedValue(GOOGLE_ENTRY)
      mocked.oauthGoogleStatus.mockResolvedValue(st('logged_out'))
      mocked.oauthGoogleComplete.mockResolvedValue({ ok: true })
      const api = makeApi()
      draw(api)
      await flush()
      click(railCard('Google 订阅'))
      await flush()
      click(byText('button', '登录遇到问题？'))
      typePaste(input)
      mocked.oauthGoogleStatus.mockResolvedValue(st('ready', { oauthProject: 'proj-1' }))
      click(byText('button', '完成登录'))
      await flush()

      expect(mocked.oauthGoogleComplete).toHaveBeenCalledWith('g-1', input)
      expect(api.refresh).toHaveBeenCalled()
      expect(activeViewText()).toContain('订阅连接正常')
      act(() => root.unmount())
      root = createRoot(container)
    }
  })

  it('complete ok 但 status 仍 pending：不展示就绪，按服务端 expiresAt 恢复有界轮询', async () => {
    mocked.officialGoogle.mockResolvedValue(GOOGLE_ENTRY)
    mocked.oauthGoogleStatus.mockResolvedValue(st('logged_out'))
    mocked.oauthGoogleComplete.mockResolvedValue({ ok: true })
    draw()
    await flush()
    click(railCard('Google 订阅'))
    await flush()
    click(byText('button', '登录遇到问题？'))
    typePaste('http://127.0.0.1:43210/oauth2callback?code=abc&state=s1')
    mocked.oauthGoogleStatus.mockResolvedValue(st('pending', { expiresAt: Date.now() + 60000 }))
    click(byText('button', '完成登录'))
    await flush()

    expect(activeViewText()).not.toContain('订阅连接正常')
    expect(activeViewText()).toContain('等待浏览器授权')
    const calls = mocked.oauthGoogleStatus.mock.calls.length
    await tick()
    expect(mocked.oauthGoogleStatus.mock.calls.length).toBeGreaterThan(calls)
  })

  it('轮询 error 收口：显示服务端原因并停轮询', async () => {
    mocked.oauthGoogleStatus.mockResolvedValue(st('error', { message: '账号无订阅资格' }))
    draw()
    await flush()
    await login()
    await tick()
    expect(activeViewText()).toContain('登录未完成')
    expect(activeViewText()).toContain('账号无订阅资格')
    const calls = mocked.oauthGoogleStatus.mock.calls.length
    await tick(6000)
    expect(mocked.oauthGoogleStatus.mock.calls.length).toBe(calls)
  })

  it('超时：以服务端 authorize.expiresAt 为截止，到点落 error 终态并停轮询', async () => {
    mocked.oauthGoogleStatus.mockResolvedValue(st('pending', { expiresAt: Date.now() + 3000 }))
    draw()
    await flush()
    await login(Date.now() + 3000)
    await tick()
    expect(activeViewText()).toContain('等待浏览器授权')
    await tick(2000)
    expect(activeViewText()).toContain('授权等待超时')
    const calls = mocked.oauthGoogleStatus.mock.calls.length
    await tick(10000)
    expect(mocked.oauthGoogleStatus.mock.calls.length).toBe(calls)
  })

  it('卸载停轮询：unmount 后不再查 status', async () => {
    mocked.oauthGoogleStatus.mockResolvedValue(st('pending', { expiresAt: Date.now() + 60000 }))
    draw()
    await flush()
    await login()
    await tick()
    const calls = mocked.oauthGoogleStatus.mock.calls.length
    act(() => root.unmount())
    await act(async () => {
      vi.advanceTimersByTime(10000)
      for (let i = 0; i < 8; i++) await Promise.resolve()
    })
    expect(mocked.oauthGoogleStatus.mock.calls.length).toBe(calls)
    root = createRoot(container)
  })

  it('竞态①：切卡后晚到 ready 不污染新卡（不刷新 / 不 toast / 回来仍是原状态）', async () => {
    let resolveLate!: (v: GoogleOAuthStatus) => void
    const late = new Promise<GoogleOAuthStatus>((r) => { resolveLate = r })
    mocked.oauthGoogleStatus.mockImplementationOnce(() => late).mockResolvedValue(st('pending', { expiresAt: Date.now() + 60000 }))
    const api = makeApi()
    draw(api)
    await flush()
    await login()
    await tick()
    // 登录本身会 refresh 一次（ensure 后刷列表）；基线取登录后的次数，晚到 ready 不得新增
    const refreshCalls = (api.refresh as ReturnType<typeof vi.fn>).mock.calls.length

    click(railCard('OpenAI 订阅'))
    await flush()
    await act(async () => {
      resolveLate(st('ready', { oauthProject: 'proj-late' }))
      for (let i = 0; i < 8; i++) await Promise.resolve()
    })
    expect((api.refresh as ReturnType<typeof vi.fn>).mock.calls.length).toBe(refreshCalls)
    expect(toast).not.toHaveBeenCalledWith('Google 订阅已就绪', 'success')

    click(railCard('Google 订阅'))
    await flush()
    expect(activeViewText()).toContain('等待浏览器授权')
    expect(activeViewText()).not.toContain('订阅连接正常')
  })

  it('竞态②：ready 收口后退出，迟到的 pending 回包不复活轮询、不改已退出状态', async () => {
    let resolveA!: (v: GoogleOAuthStatus) => void
    let resolveB!: (v: GoogleOAuthStatus) => void
    const lateA = new Promise<GoogleOAuthStatus>((r) => { resolveA = r })
    const lateB = new Promise<GoogleOAuthStatus>((r) => { resolveB = r })
    mocked.oauthGoogleStatus
      .mockImplementationOnce(() => lateA)
      .mockImplementationOnce(() => lateB)
      .mockResolvedValue(st('logged_out'))
    draw()
    await flush()
    await login()
    await tick()
    await tick()

    await act(async () => {
      resolveA(st('ready', { oauthProject: 'proj-1' }))
      for (let i = 0; i < 8; i++) await Promise.resolve()
    })
    expect(activeViewText()).toContain('订阅连接正常')
    click(byText('button', '退出登录'))
    await flush()
    expect(mocked.oauthGoogleLogout).toHaveBeenCalledWith('g-1')
    expect(activeViewText()).toContain('还没有登录 Google 订阅')

    const calls = mocked.oauthGoogleStatus.mock.calls.length
    await act(async () => {
      resolveB(st('pending', { expiresAt: Date.now() + 60000 }))
      for (let i = 0; i < 8; i++) await Promise.resolve()
    })
    expect(activeViewText()).toContain('还没有登录 Google 订阅')
    expect(activeViewText()).not.toContain('等待浏览器授权')
    await tick(10000)
    expect(mocked.oauthGoogleStatus.mock.calls.length).toBe(calls)
  })

  it('退出清理：DELETE 后回未登录、刷新列表；挂载 status 失败落 error 终态', async () => {
    mocked.officialGoogle.mockResolvedValue({ ...GOOGLE_ENTRY, authMode: 'google_oauth' })
    mocked.oauthGoogleStatus.mockResolvedValue(st('ready', { oauthProject: 'proj-1' }))
    const api = makeApi()
    draw(api)
    await flush()
    click(railCard('Google 订阅'))
    await flush()
    expect(activeViewText()).toContain('订阅连接正常')
    click(byText('button', '退出登录'))
    await flush()
    expect(mocked.oauthGoogleLogout).toHaveBeenCalledWith('g-1')
    expect(api.refresh).toHaveBeenCalled()
    expect(activeViewText()).toContain('还没有登录 Google 订阅')
    expect(activeViewText()).toContain('未登录')
  })

  it('挂载 status 读取失败：落 error 终态，不悬挂「读取中」', async () => {
    mocked.officialGoogle.mockResolvedValue(GOOGLE_ENTRY)
    mocked.oauthGoogleStatus.mockRejectedValue(new Error('后端不可达'))
    draw()
    await flush()
    click(railCard('Google 订阅'))
    await flush()
    expect(activeViewText()).toContain('登录未完成')
    expect(activeViewText()).toContain('读取登录状态失败')
  })

  // ---- 独立验收红例转回归（sess-9eefc47f REPORT.md D1/D2/D3/E1） ----

  it('回归S1：pending 期间授权 URL 恒有可点击/复制入口（外链 rel noopener noreferrer）', async () => {
    draw()
    await flush()
    await loginWithUrl(AUTH_URL)
    expect(activeViewText()).toContain('等待浏览器授权')
    expect(urlReachable(AUTH_URL)).toBe(true)
    const anchor = Array.from(container.querySelectorAll('a')).find((a) => a.href.includes('accounts.google.com'))
    expect(anchor?.getAttribute('rel') ?? '').toContain('noopener')
    expect(anchor?.getAttribute('rel') ?? '').toContain('noreferrer')
  })

  it('回归S2：Tauri openUrl 抛错 → 不静默回落 window.open，警告 + URL 兜底，保持 pending', async () => {
    tauriMock.is = true
    tauriMock.openUrl.mockRejectedValue(new Error('opener:allow-open-url not permitted'))
    draw()
    await flush()
    await loginWithUrl(AUTH_URL)
    expect(tauriMock.openUrl).toHaveBeenCalledWith(AUTH_URL)
    expect(openSpy).not.toHaveBeenCalled()
    expect(activeViewText()).toContain('等待浏览器授权')
    expect(activeViewText()).not.toContain('登录未完成')
    const hasWarn = /无法打开|失败/.test(activeViewText())
      || toast.mock.calls.some((c) => /失败|打开/.test(String(c[0])))
    expect(hasWarn).toBe(true)
    expect(urlReachable(AUTH_URL)).toBe(true)
  })

  it('回归S3：初始 authorize.expiresAt 是绝对硬截止，status 后滑不续期', async () => {
    mocked.oauthGoogleStatus.mockImplementation(async () => st('pending', { expiresAt: Date.now() + 60000 }))
    draw()
    await flush()
    await loginWithUrl(AUTH_URL, Date.now() + 10000)
    for (let i = 0; i < 5; i++) await tick(2000)
    expect(activeViewText()).toContain('授权等待超时')
    const c1 = mocked.oauthGoogleStatus.mock.calls.length
    for (let i = 0; i < 5; i++) await tick(2000)
    expect(mocked.oauthGoogleStatus.mock.calls.length).toBe(c1)
  })

  it('回归：复制授权链接成功/失败都有真实反馈（失败不假成功）', async () => {
    mockClipboard(async () => {})
    draw()
    await flush()
    await loginWithUrl(AUTH_URL)
    click(byText('button', '复制授权链接'))
    await flush()
    expect(toast).toHaveBeenCalledWith('授权链接已复制', 'success')
    mockClipboard(async () => { throw new Error('denied') })
    click(byText('button', '复制授权链接'))
    await flush()
    expect(toast).toHaveBeenCalledWith('复制失败，请手动选择链接后复制', 'info')
  })

  it('回归：status 请求悬挂时独立到期 timer 收口；迟到 ready 不复活本次 attempt', async () => {
    let resolveHang!: (v: GoogleOAuthStatus) => void
    const hang = new Promise<GoogleOAuthStatus>((r) => { resolveHang = r })
    mocked.oauthGoogleStatus
      .mockImplementationOnce(() => hang)
      .mockResolvedValue(st('ready', { oauthProject: 'p' }))
    const api = makeApi()
    draw(api)
    await flush()
    await loginWithUrl(AUTH_URL, Date.now() + 4000)
    await tick(2000)
    const refreshBefore = (api.refresh as ReturnType<typeof vi.fn>).mock.calls.length
    await tick(2000)
    expect(activeViewText()).toContain('授权等待超时')
    await act(async () => {
      resolveHang(st('ready', { oauthProject: 'p' }))
      for (let i = 0; i < 8; i++) await Promise.resolve()
    })
    expect(activeViewText()).toContain('授权等待超时')
    expect(activeViewText()).not.toContain('订阅连接正常')
    expect((api.refresh as ReturnType<typeof vi.fn>).mock.calls.length).toBe(refreshBefore)
    expect(toast).not.toHaveBeenCalledWith('Google 订阅已就绪', 'success')
  })

  it('回归：初始 expiresAt 已过 → 拒绝，不开浏览器不起轮询', async () => {
    draw()
    await flush()
    await loginWithUrl(AUTH_URL, Date.now() - 1000)
    expect(activeViewText()).toContain('授权链接有效期异常')
    expect(openSpy).not.toHaveBeenCalled()
    await tick(6000)
    expect(mocked.oauthGoogleStatus).not.toHaveBeenCalled()
  })

  it('回归：重新 authorize 替换旧链接；切卡清链接（不串用）', async () => {
    const urlB = 'https://accounts.google.com/o/oauth2/v2/auth?client_id=TEST-ONLY&state=second'
    mocked.ensureOfficialGoogle.mockResolvedValue(GOOGLE_ENTRY)
    mocked.oauthGoogleAuthorize
      .mockResolvedValueOnce({ url: AUTH_URL, expiresAt: Date.now() + 120000 })
      .mockResolvedValue({ url: urlB, expiresAt: Date.now() + 120000 })
    mocked.oauthGoogleStatus.mockResolvedValue(st('pending', { expiresAt: Date.now() + 120000 }))
    draw()
    await flush()
    click(railCard('Google 订阅'))
    await flush()
    click(byText('button', '登录 Google 订阅'))
    await flush()
    expect(urlReachable(AUTH_URL)).toBe(true)
    click(byText('button', '重开一次'))
    await flush()
    expect(urlReachable(urlB)).toBe(true)
    expect(urlReachable(AUTH_URL)).toBe(false)
    click(railCard('OpenAI 订阅'))
    await flush()
    click(railCard('Google 订阅'))
    await flush()
    expect(urlReachable(urlB)).toBe(false)
  })

  it('回归E1：onboarding 不展示已消费链接为可用，文案不承诺「完成后自动就绪」', async () => {
    mocked.oauthGoogleStatus.mockResolvedValue(st('onboarding'))
    draw()
    await flush()
    await loginWithUrl(AUTH_URL)
    await tick()
    expect(activeViewText()).toContain('账号初始化中')
    expect(activeViewText()).not.toContain('完成后自动就绪')
    const anchors = Array.from(container.querySelectorAll('a')).filter((a) => a.href.includes('accounts.google.com'))
    expect(anchors.length).toBe(0)
  })
})