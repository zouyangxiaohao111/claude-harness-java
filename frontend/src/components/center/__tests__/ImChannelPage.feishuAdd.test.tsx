// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * 飞书「添加渠道」两个分支 + 启动连接入口。
 *
 * <h2>WHY（规则九）</h2>
 * <ul>
 *   <li>「已有机器人」分支必须真的可达：此前飞书一律跳扫码面板，手填表单是死代码；</li>
 *   <li>必填只有 App ID / App Secret（长连接不需要验证令牌、加密密钥）；空值和填反要当场拦；</li>
 *   <li>「没有机器人」不提交任何东西：它只是建应用指引；</li>
 *   <li>新建实例不会自动连外网，用户必须点「启动连接」才启动长连接。</li>
 * </ul>
 */

const instancesStore: Array<Record<string, unknown>> = []

const listInstances = vi.fn(async () => instancesStore)
const listChats = vi.fn(async () => [])
const createInstance = vi.fn(async (req: { platform: string; displayName: string; credential: string }) => {
  const created = {
    id: `imc-${instancesStore.length + 1}`,
    platform: req.platform,
    displayName: req.displayName,
    status: 'disconnected',
    statusDetail: null,
    lastActiveAt: null,
    configJson: null,
    createdAt: 't',
    updatedAt: 't',
  }
  instancesStore.push(created)
  return created
})
const startInbound = vi.fn(async (id: string) => ({ instanceId: id, started: true }))
const stopInbound = vi.fn(async (id: string) => ({ instanceId: id, stopped: true }))
const listInbound = vi.fn(async () => [] as Array<{ instanceId: string; platform: string; running: boolean }>)
const removeInstance = vi.fn(async (_id: string) => ({ removed: true }))
const startQr = vi.fn(async (_platform: string) => ({
  sessionId: 'qr-1',
  qrContent: 'https://example.com/auth',
  hint: null,
}))
const pollQr = vi.fn(async (_platform: string, _sessionId: string) => ({
  state: 'PENDING',
  message: null,
  instanceId: undefined,
}))
const testConnection = vi.fn(async (_req: { platform: string; credential: string }) => ({
  ok: true,
  code: '0',
  message: '凭证有效 · 机器人：测试bot · 可见群聊 0',
}))
const syncFeishuChats = vi.fn(async (instanceId: string) => ({
  instanceId,
  total: 0,
  created: 0,
  updated: 0,
  skipped: 0,
  message: '已同步 0 个飞书群（新建 0 / 更新 0 / 跳过 0）',
}))
const oauthStatus = vi.fn(async () => ({ authorized: true, missingScopes: [] as string[] }))
const contactsPermission = vi.fn(async () => ({ granted: true }))
const oauthStart = vi.fn(async (_id: string) => ({
  deviceCode: 'd1',
  userCode: 'U1',
  verificationUriComplete: 'https://example.com/device',
  interval: 5,
  expiresIn: 600,
  browserOpened: false,
}))
const oauthPoll = vi.fn(async (_id: string, _deviceCode: string) => ({
  state: 'PENDING' as const,
  openId: null,
  message: null,
}))
const syncFeishuUserChats = vi.fn(async (id: string) => ({
  instanceId: id, total: 0, created: 0, updated: 0, skipped: 0, message: '已读取 0 个群',
}))
const openExternalUrl = vi.fn(async (_url: string) => true)

vi.mock('@/api/im', () => ({
  imApi: {
    listInstances: () => listInstances(),
    listChats: () => listChats(),
    createInstance: (req: { platform: string; displayName: string; credential: string }) => createInstance(req),
    startInbound: (id: string) => startInbound(id),
    stopInbound: (id: string) => stopInbound(id),
    listInbound: () => listInbound(),
    removeInstance: (id: string) => removeInstance(id),
    startQr: (p: string) => startQr(p),
    pollQr: (p: string, s: string) => pollQr(p, s),
    testConnection: (req: { platform: string; credential: string }) => testConnection(req),
    syncFeishuChats: (id: string) => syncFeishuChats(id),
    oauthStatus: () => oauthStatus(),
    oauthStart: (id: string) => oauthStart(id),
    oauthPoll: (id: string, dc: string) => oauthPoll(id, dc),
    syncFeishuUserChats: (id: string) => syncFeishuUserChats(id),
    contactsPermission: () => contactsPermission(),
  },
}))
vi.mock('@/utils/openExternal', () => ({
  openExternalUrl: (url: string) => openExternalUrl(url),
}))

import { ImChannelPage } from '../ImChannelPage'

describe('[im-channel] 添加飞书：两个分支', () => {
  let container: HTMLDivElement
  let root: Root | null = null
  const showToast = vi.fn()

  beforeEach(() => {
    (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    instancesStore.length = 0
    container = document.createElement('div')
    document.body.appendChild(container)
  })

  afterEach(() => {
    act(() => root?.unmount())
    container.remove()
    root = null
    vi.clearAllMocks()
  })

  async function render() {
    root = createRoot(container)
    await act(async () => {
      root!.render(<ImChannelPage showToast={showToast} onOpenSession={() => undefined} sessions={[]} />)
    })
  }

  const byText = (selector: string, text: string) =>
    Array.from(container.querySelectorAll(selector)).find((n) => (n.textContent ?? '').includes(text)) as
      | HTMLElement
      | undefined

  const click = async (el: HTMLElement | undefined) => {
    expect(el).toBeTruthy()
    await act(async () => {
      el!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    })
  }

  /** 打开「添加渠道 → 飞书」，落在分支选择页。 */
  async function openFeishu() {
    await render()
    await click(byText('button', '添加渠道'))
    await click(byText('.imc-pick', '飞书'))
  }

  /** 只取抽屉里的输入框（页面背景的「微信数据目录」也是 .imc-inp）。 */
  const inputs = () =>
    Array.from(container.querySelectorAll('.imc-db input.imc-inp')) as HTMLInputElement[]

  /** 给受控 input 赋值：走 React 原生 setter，否则 onChange 不触发。 */
  async function type(el: HTMLInputElement, value: string) {
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value')!.set!
    await act(async () => {
      setter.call(el, value)
      el.dispatchEvent(new Event('input', { bubbles: true }))
    })
  }

  const lastToast = () => {
    const calls = showToast.mock.calls
    return (calls.length > 0 ? calls[calls.length - 1] : [])[0] as string | undefined
  }

  it('飞书先给两个选项；「已有机器人」只出现 App ID / App Secret 两个必填框', async () => {
    await openFeishu()

    expect(byText('.imc-pick', '已有机器人')).toBeTruthy()
    expect(byText('.imc-pick', '我没有机器人')).toBeTruthy()

    await click(container.querySelector('[data-branch="existing"]') as HTMLElement)

    // 只有两个必填项：验证令牌 / 加密密钥 / 显示名都不该出现在表单里
    expect(inputs()).toHaveLength(2)
    const labels = Array.from(container.querySelectorAll('.imc-db .imc-flab')).map((n) => n.textContent ?? '')
    expect(labels).toEqual(['App ID', 'App Secret'])
  })

  it('已有机器人：空值提交被拦下，不建实例', async () => {
    await openFeishu()
    await click(container.querySelector('[data-branch="existing"]') as HTMLElement)

    await click(byText('.imc-df button', '保存并连接'))

    expect(lastToast()).toContain('请填完所有必填项')
    expect(createInstance).not.toHaveBeenCalled()
  })

  it('已有机器人：App ID 不是 cli_ 开头 ⇒ 提示格式，不建实例', async () => {
    await openFeishu()
    await click(container.querySelector('[data-branch="existing"]') as HTMLElement)
    await type(inputs()[0], 'not-an-app-id')
    await type(inputs()[1], 'secret-value')

    await click(byText('.imc-df button', '保存并连接'))

    expect(lastToast()).toContain('App ID 格式不对')
    expect(createInstance).not.toHaveBeenCalled()
  })

  it('已有机器人：填对后建实例，凭证明细只含两项，列表出现该渠道', async () => {
    await openFeishu()
    await click(container.querySelector('[data-branch="existing"]') as HTMLElement)
    await type(inputs()[0], ' cli_abc123 ')
    await type(inputs()[1], ' s3cret ')

    await click(byText('.imc-df button', '保存并连接'))

    expect(createInstance).toHaveBeenCalledTimes(1)
    const req = createInstance.mock.calls[0][0]
    expect(req.platform).toBe('feishu')
    const credential = JSON.parse(req.credential)
    expect(credential).toEqual({ appId: 'cli_abc123', appSecret: 's3cret' })
    // 说「已保存，正在自动连接」：后端建实例即自动启动，不用再点一次
    expect(lastToast()).toContain('已保存')
    expect(lastToast()).toContain('自动连接')
    // 抽屉关闭、列表刷新后能看到该渠道
    expect(byText('.imc-card', '飞书')).toBeTruthy()
  })

  it('没有机器人：只给指引、不提交任何东西', async () => {
    await openFeishu()
    await click(container.querySelector('[data-branch="create"]') as HTMLElement)

    const guideText = container.textContent ?? ''
    expect(guideText).toContain('长连接')
    expect(guideText).toContain('不用填任何网址')
    expect(guideText).toContain('我已建好，回去填凭证')
    expect(createInstance).not.toHaveBeenCalled()
  })

  it('分支间切换保留已填内容：「我已建好」回到表单时值还在', async () => {
    await openFeishu()
    // 先在「已有机器人」填一个 App ID
    await click(container.querySelector('[data-branch="existing"]') as HTMLElement)
    await type(inputs()[0], 'cli_keep_me')
    // 走「看创建步骤」再到指引页，最后点「我已建好」回来
    await click(byText('button', '还没有机器人？查看创建步骤'))
    await click(byText('button', '我已建好，回去填凭证'))

    expect(inputs()).toHaveLength(2)
    expect(inputs()[0].value).toBe('cli_keep_me')
  })

  it('未连接的渠道卡片有「启动连接」，点了才调用入站启动接口', async () => {
    instancesStore.push({
      id: 'imc-fs-1',
      platform: 'feishu',
      displayName: '飞书',
      status: 'disconnected',
      statusDetail: '已停止',
      lastActiveAt: null,
      configJson: null,
      createdAt: 't',
      updatedAt: 't',
    })
    await render()

    await click(byText('button', '启动连接'))

    expect(startInbound).toHaveBeenCalledWith('imc-fs-1')
  })

  it('已连接：显示「断开连接」，点了调 stop 而不是 start', async () => {
    instancesStore.push({
      id: 'imc-fs-run',
      platform: 'feishu',
      displayName: '飞书',
      status: 'connected',
      statusDetail: null,
      lastActiveAt: null,
      configJson: null,
      createdAt: 't',
      updatedAt: 't',
    })
    // 入站监听正在跑（与握手状态无关）⇒ 按钮必须翻转成「断开连接」
    listInbound.mockResolvedValueOnce([{ instanceId: 'imc-fs-run', platform: 'feishu', running: true }])
    await render()

    expect(byText('button', '启动连接')).toBeUndefined()
    const stopBtn = byText('button', '断开连接')
    // 与「移除机器人」一致的边框红框，不能退化成 ghost 纯文字
    expect(stopBtn!.className).toContain('danger')
    expect(stopBtn!.className).not.toContain('ghost')
    await click(stopBtn)

    expect(stopInbound).toHaveBeenCalledWith('imc-fs-run')
    expect(startInbound).not.toHaveBeenCalled()
  })

  it('连接失败（status=error）时卡片仍在，显示原因并可重新连接', async () => {
    instancesStore.push({
      id: 'imc-fs-err',
      platform: 'feishu',
      displayName: '飞书',
      status: 'error',
      statusDetail: '飞书长连接失败：handshake did not complete',
      lastActiveAt: null,
      configJson: null,
      createdAt: 't',
      updatedAt: 't',
    })
    await render()

    // 失败态不能被「已接入」过滤掉：原因要读得到，按钮要能点
    const card = byText('.imc-card', '飞书长连接失败')
    expect(card).toBeTruthy()
    expect(card!.textContent).toContain('异常')
    await click(byText('button', '重新连接'))
    expect(startInbound).toHaveBeenCalledWith('imc-fs-err')
  })

  it('通讯录权限未开通：只给中性说明，不留「去开通」死链', async () => {
    contactsPermission.mockResolvedValueOnce({ granted: false })
    instancesStore.push({
      id: 'imc-fs-contacts',
      platform: 'feishu',
      displayName: '飞书',
      status: 'connected',
      statusDetail: null,
      lastActiveAt: null,
      configJson: null,
      createdAt: 't',
      updatedAt: 't',
    })
    await render()

    // 该权限只影响对方显示名：说明清楚即可，不能再给一个在 WebView 里打不开的按钮
    expect(container.textContent).toContain('不影响收发')
    expect(byText('button', '去开通')).toBeUndefined()
  })

  it('测试连接：真的调用后端探测（不建实例），并展示飞书返回结果', async () => {
    await openFeishu()
    await click(container.querySelector('[data-branch="existing"]') as HTMLElement)
    await type(inputs()[0], 'cli_probe123')
    await type(inputs()[1], 'probe-secret')

    await click(byText('.imc-df button', '测试连接'))

    expect(testConnection).toHaveBeenCalledTimes(1)
    const req = testConnection.mock.calls[0][0]
    expect(req.platform).toBe('feishu')
    expect(JSON.parse(req.credential)).toEqual({ appId: 'cli_probe123', appSecret: 'probe-secret' })
    // 结果来自后端（含机器人名），不是前端硬编码的「连接成功」
    expect(lastToast()).toContain('凭证有效')
    expect(createInstance).not.toHaveBeenCalled()
  })

  it('同步会话：已授权且权限齐全 ⇒ 默认按本人全部群同步（不再多一个按钮）', async () => {
    pushFeishu('imc-fs-sync')
    await render()

    await click(byText('button', '同步会话'))

    expect(syncFeishuUserChats).toHaveBeenCalledWith('imc-fs-sync')
    expect(syncFeishuChats).not.toHaveBeenCalled()
    expect(byText('button', '读取全部会话')).toBeUndefined()
  })

  it('同步会话：未授权时退回机器人所在群，并在结果里说明范围', async () => {
    oauthStatus.mockResolvedValueOnce({ authorized: false, missingScopes: [] })
    pushFeishu('imc-fs-sync-bot')
    await render()

    await click(byText('button', '同步会话'))

    expect(syncFeishuChats).toHaveBeenCalledWith('imc-fs-sync-bot')
    expect(syncFeishuUserChats).not.toHaveBeenCalled()
    expect(lastToast()).toContain('机器人所在的群')
  })

  it('「移除机器人」是红框危险按钮，不再退化成纯文字（ghost）', async () => {
    instancesStore.push({
      id: 'imc-fs-del',
      platform: 'feishu',
      displayName: '飞书',
      status: 'connected',
      statusDetail: null,
      lastActiveAt: null,
      configJson: null,
      createdAt: 't',
      updatedAt: 't',
    })
    await render()

    const btn = byText('button', '移除机器人')
    expect(btn).toBeTruthy()
    // 与相邻按钮同样有边框盒子；danger 提供红框红字（见 imChannel.css）
    expect(btn!.className).toContain('danger')
    expect(btn!.className).not.toContain('ghost')
  })

  it('移除机器人要二次确认；确认后调删除实例（不是只断开）', async () => {
    instancesStore.push({
      id: 'imc-fs-del2',
      platform: 'feishu',
      displayName: '飞书',
      status: 'connected',
      statusDetail: null,
      lastActiveAt: null,
      configJson: null,
      createdAt: 't',
      updatedAt: 't',
    })
    await render()

    await click(byText('button', '移除机器人'))
    // 弹窗把「移除」与「断开连接」的区别写清楚
    expect(container.textContent).toContain('移除机器人「飞书」？')
    expect(container.textContent).toContain('只想临时断开、以后再连请用「断开连接」')

    await click(byText('.imc-dlg button', '移除机器人'))

    expect(removeInstance).toHaveBeenCalledWith('imc-fs-del2')
    expect(lastToast()).toContain('已移除机器人')
  })

  it('详情里「断开连接」只断开连接，不删除实例', async () => {
    instancesStore.push({
      id: 'imc-fs-detail',
      platform: 'feishu',
      displayName: '飞书',
      status: 'connected',
      statusDetail: null,
      lastActiveAt: null,
      configJson: null,
      createdAt: 't',
      updatedAt: 't',
    })
    // 打开「管理」会再拉一次状态 ⇒ 两次都要返回「在跑」
    listInbound
      .mockResolvedValueOnce([{ instanceId: 'imc-fs-detail', platform: 'feishu', running: true }])
      .mockResolvedValueOnce([{ instanceId: 'imc-fs-detail', platform: 'feishu', running: true }])
    await render()

    await click(byText('button', '管理'))
    const disconnectBtn = byText('.imc-df button', '断开连接')
    expect(disconnectBtn).toBeTruthy()
    // 用户要求：与「移除机器人」同样给红框样式
    expect(disconnectBtn!.className).toContain('danger')
    await click(disconnectBtn)

    expect(stopInbound).toHaveBeenCalledWith('imc-fs-detail')
    expect(removeInstance).not.toHaveBeenCalled()
  })

  it('打开「管理」会重拉状态，不再停在启动时写下的「正在连接…」', async () => {
    const connecting = {
      id: 'imc-fs-live',
      platform: 'feishu',
      displayName: '飞书',
      status: 'disconnected',
      statusDetail: '正在连接飞书长连接…',
      lastActiveAt: null,
      configJson: null,
      createdAt: 't',
      updatedAt: 't',
    }
    const connected = {
      ...connecting,
      status: 'connected',
      statusDetail: '飞书长连接已连接',
    }
    listInbound
      .mockResolvedValueOnce([{ instanceId: 'imc-fs-live', platform: 'feishu', running: true }])
      .mockResolvedValueOnce([{ instanceId: 'imc-fs-live', platform: 'feishu', running: true }])
    listInstances.mockResolvedValueOnce([connecting]).mockResolvedValueOnce([connected])
    await render()

    expect(container.textContent).toContain('正在连接飞书长连接…')
    await click(byText('button', '管理'))
    // 打开抽屉会重新拉一次实例；握手结果回写后要能看到
    await act(async () => { await Promise.resolve(); await Promise.resolve() })

    expect(container.textContent).toContain('飞书长连接已连接')
  })

  // ── 飞书「本人身份」权限清单 + 读取全部会话 ─────────────────

  function pushFeishu(id: string) {
    instancesStore.push({
      id,
      platform: 'feishu',
      displayName: '飞书',
      status: 'connected',
      statusDetail: null,
      lastActiveAt: null,
      configJson: null,
      createdAt: 't',
      updatedAt: 't',
    })
  }

  const ALL_SCOPES = [
    'im:chat:read',
    'im:message',
    'im:message.send_as_user',
    'im:message.group_msg:get_as_user',
    'im:message.p2p_msg:get_as_user',
    'search:message',
  ]

  it('未授权：点「去授权」直接进权限清单页（不插中间选择页）', async () => {
    oauthStatus.mockResolvedValueOnce({ authorized: false, missingScopes: [] })
    pushFeishu('imc-fs-direct')

    await render()
    await click(byText('button', '去授权（浏览器同意）'))

    const text = container.textContent ?? ''
    expect(text).toContain('飞书权限清单')
    for (const s of ALL_SCOPES) expect(text).toContain(s)
    expect(text).toContain('管理员需要在控制台开启的')
    // 清单页本身不发起 OAuth（避免一进来就弹浏览器）
    expect(oauthStart).not.toHaveBeenCalled()
  })

  it('卡片不再单独放「权限清单」按钮（与授权入口重复）', async () => {
    pushFeishu('imc-fs-nodup')

    await render()

    expect(byText('button', '权限清单')).toBeUndefined()
  })

  it('授权页用系统默认浏览器打开（不用 Tauri 应用内窗口）', async () => {
    oauthStatus.mockResolvedValueOnce({ authorized: false, missingScopes: [] })
    pushFeishu('imc-fs-inapp')

    await render()
    await click(byText('button', '去授权（浏览器同意）'))
    // 清单页里点「去授权页点同意」才发起，并用默认浏览器打开授权页
    await click(byText('.imc-db button', '去授权页点同意'))
    await act(async () => { await Promise.resolve(); await Promise.resolve() })

    expect(openExternalUrl).toHaveBeenCalledWith('https://example.com/device')
  })

  it('授权失败只在下方提示，不整页替换（保留链接/用户码）', async () => {
    oauthStatus.mockResolvedValueOnce({ authorized: false, missingScopes: [] })
    oauthStart.mockRejectedValueOnce(new Error('network down'))
    pushFeishu('imc-fs-authfail')

    await render()
    await click(byText('button', '去授权（浏览器同意）'))
    await click(byText('.imc-db button', '去授权页点同意'))
    await act(async () => { await Promise.resolve(); await Promise.resolve() })

    const text = container.textContent ?? ''
    // 说明文字仍在（不是整页只剩错误）
    expect(text).toContain('想让 AI 以')
    expect(text).toContain('授权失败：network down')
  })

  it('权限清单：列出 5 个 scope、两类说明与管理员入口', async () => {
    oauthStatus.mockResolvedValueOnce({ authorized: true, missingScopes: ['im:chat:read'] })
    pushFeishu('imc-fs-perm')

    await render()
    // 缺 scope 时授权按钮就是清单入口（不再单独放「权限清单」按钮）
    await click(byText('button', '补权限 / 重新授权'))

    const text = container.textContent ?? ''
    for (const s of ALL_SCOPES) expect(text).toContain(s)
    expect(text).toContain('程序不能代开')
    expect(text).toContain('第一步 · 控制台开通')
    expect(text).toContain('第二步 · 本人授权')
  })

  it('权限清单：列出管理员需要配置的项目（不只是 5 个 scope）', async () => {
    oauthStatus.mockResolvedValueOnce({ authorized: true, missingScopes: ['im:chat:read'] })
    pushFeishu('imc-fs-admin')

    await render()
    await click(byText('button', '补权限 / 重新授权'))

    const text = container.textContent ?? ''
    expect(text).toContain('管理员需要在控制台开启的')
    expect(text).toContain('版本管理与发布')
    expect(text).toContain('事件与回调')
    expect(text).toContain('通讯录权限范围')
  })

  it('权限清单：打开管理页走统一外链封装（不是 window.open）', async () => {
    oauthStatus.mockResolvedValueOnce({ authorized: true, missingScopes: ['im:chat:read'] })
    pushFeishu('imc-fs-link')

    await render()
    await click(byText('button', '补权限 / 重新授权'))
    await click(byText('.imc-db button', '打开权限管理页'))

    expect(openExternalUrl).toHaveBeenCalledWith('https://open.feishu.cn/app')
  })

  it('权限清单：每个 scope 可一键复制', async () => {
    const writeText = vi.fn(async (_t: string) => undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    oauthStatus.mockResolvedValueOnce({ authorized: true, missingScopes: ['im:chat:read'] })
    pushFeishu('imc-fs-copy')

    await render()
    await click(byText('button', '补权限 / 重新授权'))
    await click(container.querySelector('[aria-label="复制 im:chat:read"]') as HTMLElement)

    expect(writeText).toHaveBeenCalledWith('im:chat:read')
  })

  it('权限清单：重新检测后全部满足 ⇒ 提示「权限已就绪」', async () => {
    oauthStatus
      .mockResolvedValueOnce({ authorized: true, missingScopes: ['im:chat:read'] })
      .mockResolvedValueOnce({ authorized: true, missingScopes: [] })
    pushFeishu('imc-fs-recheck')

    await render()
    await click(byText('button', '补权限 / 重新授权'))
    expect(container.textContent).toContain('上次授权缺少 1 项')

    await click(byText('.imc-db button', '重新检测'))
    await act(async () => { await Promise.resolve(); await Promise.resolve() })

    expect(container.textContent).toContain('权限已就绪')
    // 用户报「重新检测没用」⇒ 必须有可见的检测结果文字
    expect(container.textContent).toContain('已重新检测')
  })

  it('已授权且权限齐全：点「重新授权」也先进清单页，不直接弹浏览器', async () => {
    pushFeishu('imc-fs-ready')

    await render()
    await click(byText('button', '已授权 · 重新授权'))
    await act(async () => { await Promise.resolve(); await Promise.resolve() })

    // 用户 2026-11-23：入口只开清单；真正授权由清单页内的按钮发起
    expect(container.textContent).toContain('飞书权限清单')
    expect(oauthStart).not.toHaveBeenCalled()
  })

  it('「一键创建应用」走官方授权流程（新建应用，不是探测已有）', async () => {
    await openFeishu()
    await click(container.querySelector('[data-branch="create"]') as HTMLElement)
    await click(byText('button', '一键创建应用（自动获取凭证）'))

    expect(startQr).toHaveBeenCalledWith('feishu')
  })
})
