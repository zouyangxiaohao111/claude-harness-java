// @vitest-environment jsdom
import { act, type ReactElement } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SkillMarketModal } from '../SkillMarketModal'
import { ConnectorCard } from '../../market/ConnectorCard'
import { ConnectorAuthDrawer } from '../../market/ConnectorAuthDrawer'
import type { ConnectorDto } from '@/api/connectors'

/**
 * 连接器 tab（连接器市场 + 授权抽屉）。
 *
 * <p><b>WHY 这几条必须钉住</b>：
 * <ul>
 *   <li><b>惰性拉取</b>：三个既有 SkillMarketModal 测试把 market 侧 mock 成封闭白名单，
 *       连接器请求若挂在弹窗挂载期，会让它们以 `is not a function` 直接红 ——
 *       本文件第一条用例即断言「默认专家 tab 不发连接器请求」。</li>
 *   <li><b>点击不是安装</b>：历史实现连接器卡的「+」调 `installMarketPlugin`（POST /api/plugins/install），
 *       把「连接」当「装插件」，且会让按钮变 ✓ 假装已连接。这里钉住「点卡片/主按钮 → 打开授权抽屉 → start」，
 *       同时断言 `installPlugin` 一次都没被调用。</li>
 *   <li><b>authType 分化</b>：一键授权只对 oauth2 成立，其余几种必须如实说「要填什么」。</li>
 *   <li><b>状态来自后端</b>：已连接态与「已安装 (N)」计数只认 `connected` 字段，不做本地乐观置位。</li>
 * </ul>
 */
const {
  listAgentsMock, listInstalledMock, listInstalledDetailsMock, listCommandsMock, listExternalMock,
  installMock, getProgressMock,
  connListMock, connStartMock, connPollMock, connStatusMock, connCredMock, connTestMock, connDisconnectMock,
  openExternalMock, mcpListMock, mcpCreateMock, mcpRemoveMock, mcpExportMock, mcpImportMock,
} = vi.hoisted(() => ({
  listAgentsMock: vi.fn(),
  listInstalledMock: vi.fn(),
  listInstalledDetailsMock: vi.fn(),
  listCommandsMock: vi.fn(),
  listExternalMock: vi.fn(),
  installMock: vi.fn(),
  getProgressMock: vi.fn(),
  connListMock: vi.fn(),
  connStartMock: vi.fn(),
  connPollMock: vi.fn(),
  connStatusMock: vi.fn(),
  connCredMock: vi.fn(),
  connTestMock: vi.fn(),
  connDisconnectMock: vi.fn(),
  openExternalMock: vi.fn(),
  mcpListMock: vi.fn(),
  mcpCreateMock: vi.fn(),
  mcpRemoveMock: vi.fn(),
  mcpExportMock: vi.fn(),
  mcpImportMock: vi.fn(),
}))

vi.mock('@/api/mcp', () => ({ mcpApi: {
  list: mcpListMock, create: mcpCreateMock, remove: mcpRemoveMock,
  exportServers: mcpExportMock, importJson: mcpImportMock,
} }))

vi.mock('@/api/agent', () => ({ agentApi: { listAgents: listAgentsMock } }))
vi.mock('@/api/market', () => ({
  marketApi: {
    listInstalled: listInstalledMock,
    listInstalledDetails: listInstalledDetailsMock,
    listExternal: listExternalMock,
    installPlugin: installMock,
    getInstallProgress: getProgressMock,
  },
}))
vi.mock('@/api/connectors', () => ({
  connectorsApi: {
    list: connListMock,
    start: connStartMock,
    poll: connPollMock,
    status: connStatusMock,
    bindCredentials: connCredMock,
    test: connTestMock,
    disconnect: connDisconnectMock,
  },
}))
vi.mock('@/api/command', () => ({ commandApi: { list: listCommandsMock } }))
vi.mock('@/api/rest', () => ({ ApiError: class ApiError extends Error {} }))
vi.mock('@/utils/openExternal', () => ({ openExternalUrl: openExternalMock }))

const NOTION = {
  id: 'notion', key: 'notion', name: 'Notion', description: '读写 Notion 页面',
  authType: 'oauth2' as const, scopes: ['read', 'write'], connected: false,
  status: 'unconnected' as const, account: null, expiresAt: null, hidden: false,
  icon: 'notion', color: '#000000',
}
const API_KEY_CONN = {
  id: 'openalex', key: 'openalex', name: 'OpenAlex', description: '学术检索',
  authType: 'apiKey' as const, scopes: null, connected: false,
  status: 'unconnected' as const, account: null, expiresAt: null, hidden: false,
}
const TOKEN_CONN = {
  id: 'github', key: 'github', name: 'GitHub', description: '仓库访问',
  authType: 'personalToken' as const, scopes: null, connected: false,
  status: 'unconnected' as const, account: null, expiresAt: null, hidden: false,
}
const CUSTOM_CONN = {
  id: 'dida', key: 'dida', name: '滴答清单', description: '任务同步',
  authType: 'customFields' as const, scopes: null, connected: false,
  status: 'unconnected' as const, account: null, expiresAt: null, hidden: false,
}
/** 分类用：knowledge / office 两条 oauth2 */
const NOTION_KNOWLEDGE = { ...NOTION, category: 'knowledge' }
const OFFICE_CONN = {
  id: 'office', key: 'office', name: 'Office 连接器', description: '文档协同',
  authType: 'oauth2' as const, category: 'office', scopes: null, connected: false,
  status: 'unconnected' as const, account: null, expiresAt: null, hidden: false,
}
/** 凭据仍在但已失效：需要重新授权 */
const REAUTH_CONN = {
  id: 're', key: 're', name: '待重授权', description: 'token 过期',
  authType: 'oauth2' as const, category: 'knowledge', scopes: null, connected: true,
  status: 'reauth_required' as const, account: 'me@x', expiresAt: null, hidden: false,
}

function noop(): void {}

async function flush(rounds = 12) {
  for (let i = 0; i < rounds; i++) {
    await act(async () => { await Promise.resolve() })
  }
}

let container: HTMLDivElement | undefined
let root: Root | undefined

beforeEach(() => {
  ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)

  listAgentsMock.mockReset(); listAgentsMock.mockResolvedValue([])
  listInstalledMock.mockReset(); listInstalledMock.mockResolvedValue([])
  listInstalledDetailsMock.mockReset(); listInstalledDetailsMock.mockResolvedValue([])
  listCommandsMock.mockReset(); listCommandsMock.mockResolvedValue([])
  listExternalMock.mockReset(); listExternalMock.mockResolvedValue(null)
  installMock.mockReset()
  getProgressMock.mockReset(); getProgressMock.mockResolvedValue(undefined)

  connListMock.mockReset(); connListMock.mockResolvedValue({ connectors: [] })
  connStartMock.mockReset()
  connPollMock.mockReset()
  connStatusMock.mockReset()
  connCredMock.mockReset()
  connTestMock.mockReset()
  connDisconnectMock.mockReset()
  openExternalMock.mockReset(); openExternalMock.mockResolvedValue(true)
  mcpListMock.mockReset(); mcpListMock.mockResolvedValue([])
  mcpCreateMock.mockReset()
  mcpRemoveMock.mockReset()
  mcpExportMock.mockReset(); mcpExportMock.mockResolvedValue({ mcpServers: { s1: { type: 'stdio', command: 'npx x' } } })
  mcpImportMock.mockReset()
})

afterEach(() => {
  if (root) act(() => root!.unmount())
  container?.remove()
  vi.useRealTimers()
})

function renderModal() {
  act(() => {
    root!.render(
      <SkillMarketModal
        sessionId="sess-conn"
        currentAgent={null}
        onClose={noop}
        onUseLocalAgent={noop}
        onSummonSkill={noop}
        showToast={noop}
      />,
    )
  })
}

async function openConnectorTab() {
  const tab = Array.from(container!.querySelectorAll('.sm-tab-flat'))
    .find((el) => (el.textContent ?? '').includes('连接器')) as HTMLElement | undefined
  expect(tab, '未找到连接器 tab').toBeTruthy()
  act(() => { tab!.click() })
  await flush()
}

function cards(): HTMLElement[] {
  return Array.from(container!.querySelectorAll('[data-testid="connector-card"]')) as HTMLElement[]
}

/** 单独挂一个组件（不经过「只展示 oauth2」的列表过滤，用来覆盖保留的 authType 分支） */
function mountInto(node: ReactElement): { el: HTMLDivElement; root: Root } {
  const el = document.createElement('div')
  document.body.appendChild(el)
  const r = createRoot(el)
  act(() => { r.render(node) })
  return { el, root: r }
}

function unmountFrom(m: { el: HTMLDivElement; root: Root }) {
  act(() => m.root.unmount())
  m.el.remove()
}

/** 按文字找按钮（抽屉单挂场景用） */
function btnWithText(root: ParentNode, text: string): HTMLButtonElement | undefined {
  return Array.from(root.querySelectorAll('button'))
    .find((b) => (b.textContent ?? '').includes(text)) as HTMLButtonElement | undefined
}

/**
 * 2026-11-23 设计稿：点卡片先打开居中详情弹窗，再点「连接 / 管理连接」进入授权抽屉。
 * 这个 helper 把两步串起来，保持既有抽屉用例的语义。
 */
async function openDrawerOf(card: HTMLElement): Promise<HTMLElement> {
  act(() => card.click())
  await flush()
  const go = container!.querySelector('.cd-primary') as HTMLButtonElement | null
  expect(go, '连接器详情弹窗缺少连接按钮').toBeTruthy()
  act(() => go!.click())
  await flush()
  const drawer = container!.querySelector('[data-testid="connector-drawer"]') as HTMLElement | null
  expect(drawer, '未打开授权抽屉').toBeTruthy()
  return drawer!
}

function cardOf(authType: string): HTMLElement {
  const el = cards().find((c) => c.dataset.authType === authType)
  expect(el, `未找到 authType=${authType} 的连接器卡`).toBeTruthy()
  return el!
}

describe('SkillMarketModal · 连接器市场', () => {
  it('惰性拉取：默认停在专家 tab 时不请求连接器目录，切到连接器 tab 才拉一次', async () => {
    renderModal()
    await flush()
    expect(connListMock).not.toHaveBeenCalled()

    await openConnectorTab()
    expect(connListMock).toHaveBeenCalledTimes(1)
  })

  it('空态：没有连接器时给出空文案', async () => {
    renderModal()
    await flush()
    await openConnectorTab()
    expect(container!.textContent).toContain('暂无可用连接器')
    expect(cards()).toHaveLength(0)
  })

  it('已连接态：状态胶囊显示「已连接」，顶栏计数为已连接数，主按钮变「管理」', async () => {
    connListMock.mockResolvedValue({
      connectors: [
        { ...NOTION, connected: true, status: 'connected', account: 'me@notion' },
        API_KEY_CONN,
      ],
    })
    renderModal()
    await flush()
    await openConnectorTab()

    const notionCard = cardOf('oauth2')
    expect(notionCard.querySelector('[data-testid="connector-status"]')!.textContent).toContain('已连接')
    expect(notionCard.querySelector('.mc-btn')!.textContent).toContain('管理')
    // 非 oauth2 条目现在是「凭证卡」（分档渲染），仍在列表里；但不计入「已连接」
    expect(cards()).toHaveLength(2)
    expect(cardOf('apiKey').dataset.tier).toBe('credentialOnly')
    // 顶栏按钮在连接器 tab 显示「已连接 (N)」（原来是「已安装」且点了无效果）
    expect(container!.querySelector('.sm-installed-pill')!.textContent).toContain('已连接 (1)')
  })

  it('分档渲染：oauth2=supported / 凭证型=credentialOnly（明示运行需外部适配）/ 未知=unavailable 置灰不可点', async () => {
    const UNKNOWN = { ...NOTION, id: 'ldap', key: 'ldap', name: 'LDAP', authType: 'ldap' as unknown as 'oauth2' }
    connListMock.mockResolvedValue({ connectors: [NOTION, API_KEY_CONN, UNKNOWN] })
    renderModal()
    await flush()
    await openConnectorTab()
    expect(cards()).toHaveLength(3)

    expect(cardOf('oauth2').dataset.tier).toBe('supported')
    const cred = cardOf('apiKey')
    expect(cred.dataset.tier).toBe('credentialOnly')
    expect(cred.querySelector('[data-testid="connector-tier-note"]')!.textContent).toContain('运行需外部适配')
    expect(cred.querySelector('.mc-btn')!.textContent).toContain('填 API Key')

    const un = cardOf('ldap')
    expect(un.dataset.tier).toBe('unavailable')
    expect(un.classList.contains('is-disabled')).toBe(true)
    const unBtn = un.querySelector('.mc-btn') as HTMLButtonElement
    expect(unBtn.disabled).toBe(true)
    expect(unBtn.textContent).toContain('暂不支持')
    // ⛔ 不可用条目不能打开抽屉（没有可点动作）
    act(() => { un.click() })
    await flush()
    expect(container!.querySelector('[data-testid="connector-drawer"]')).toBeNull()
  })

  it('legacy 分支已砍：无 authType 的条目不再渲染，也不会触发插件安装', async () => {
    // 连接器列表只渲染后端 catalog 条目（恒有 authType）；历史 legacy 分支按裁定删除 ——
    // 这类脏数据即使混进列表也只会被过滤掉，⛔ 不会变成「安装插件」的动作
    connListMock.mockResolvedValue({
      connectors: [NOTION, { ...API_KEY_CONN, id: 'legacy-x', key: 'legacy-x', authType: null }],
    })
    renderModal()
    await flush()
    await openConnectorTab()

    expect(cards()).toHaveLength(1)
    expect(cards()[0].dataset.authType).toBe('oauth2')
    expect(installMock).not.toHaveBeenCalled()
  })

  it('authType 分化（分支代码保留）：一键授权 / 填 API Key / 粘贴 Token / 配置字段 各自不同', () => {
    const cases: Array<[ConnectorDto, string]> = [
      [NOTION, '一键授权'],
      [API_KEY_CONN, '填 API Key'],
      [TOKEN_CONN, '粘贴 Token'],
      [CUSTOM_CONN, '配置字段'],
    ]
    for (const [connector, label] of cases) {
      const m = mountInto(<ConnectorCard connector={connector} onOpen={noop} />)
      try {
        const btn = m.el.querySelector('.mc-btn') as HTMLElement
        expect(btn.textContent, `authType=${connector.authType} 的主按钮文案不对`).toContain(label)
        // 分档标记（supported / credentialOnly）
        expect(m.el.querySelector('[data-testid="connector-card"]')!.getAttribute('data-tier'))
          .toBe(connector.authType === 'oauth2' ? 'supported' : 'credentialOnly')
      } finally {
        unmountFrom(m)
      }
    }
  })

  it('点击卡片打开授权抽屉并发起 start —— 全程不调插件安装', async () => {
    connListMock.mockResolvedValue({ connectors: [NOTION] })
    connStartMock.mockResolvedValue({
      authorizeUrl: 'https://auth.example.com/authorize?x=1',
      state: 'st-abc',
      redirectUri: 'http://localhost:4567/callback',
      interval: 1,
      expiresIn: 300,
    })
    renderModal()
    await flush()
    await openConnectorTab()

    const drawer = await openDrawerOf(cardOf('oauth2'))

    const startBtn = Array.from(drawer.querySelectorAll('button'))
      .find((b) => (b.textContent ?? '').includes('开始授权')) as HTMLButtonElement
    expect(startBtn).toBeTruthy()
    act(() => { startBtn.click() })
    await flush()

    expect(connStartMock).toHaveBeenCalledTimes(1)
    expect(connStartMock.mock.calls[0][0]).toBe('notion')
    // 授权页交给系统浏览器，而不是应用内窗口
    expect(openExternalMock).toHaveBeenCalledWith('https://auth.example.com/authorize?x=1')
    // 回调地址可见（给用户去平台后台登记）
    expect(container!.querySelector('[data-testid="connector-callback"]')!.textContent)
      .toBe('http://localhost:4567/callback')
    // ⛔ 连接器不是插件：安装链一次都没被触碰
    expect(installMock).not.toHaveBeenCalled()
  })

  it('轮询到 AUTHORIZED 后拉 status 回写卡片（状态以服务端为准）', async () => {
    vi.useFakeTimers()
    connListMock.mockResolvedValue({ connectors: [NOTION] })
    connStartMock.mockResolvedValue({
      authorizeUrl: 'https://auth.example.com/authorize?x=1',
      state: 'st-abc',
      redirectUri: 'http://localhost:4567/callback',
      interval: 1,
      expiresIn: 300,
    })
    connPollMock.mockResolvedValue({ state: 'AUTHORIZED', message: null, account: 'me@notion' })
    connStatusMock.mockResolvedValue({
      connected: true, authType: 'oauth2', account: 'me@notion', expiresAt: null, missingScopes: [],
    })

    renderModal()
    await flush()
    await openConnectorTab()
    const drawer = await openDrawerOf(cardOf('oauth2'))
    const startBtn = Array.from(drawer.querySelectorAll('button'))
      .find((b) => (b.textContent ?? '').includes('开始授权')) as HTMLButtonElement
    act(() => { startBtn.click() })
    await flush()

    // 轮询间隔 1s
    await act(async () => { vi.advanceTimersByTime(1000) })
    await flush()

    expect(connPollMock).toHaveBeenCalledTimes(1)
    expect(connPollMock.mock.calls[0]).toEqual(['notion', 'st-abc'])
    expect(connStatusMock).toHaveBeenCalledWith('notion')

    act(() => { drawer.querySelector('.mc-x')!.dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    await flush()
    expect(cardOf('oauth2').querySelector('[data-testid="connector-status"]')!.textContent).toContain('已连接')
  })

  it('测试连接：真实调用后端并显示本次结果文字（含时延）', async () => {
    connListMock.mockResolvedValue({
      connectors: [{ ...NOTION, connected: true, status: 'connected', account: 'me@notion' }],
    })
    connTestMock.mockResolvedValue({
      ok: true, message: '已连到 Notion', latencyMs: 42, probeLevel: 'mcp_initialize',
      tools: [{ name: 'query_pages', description: '检索页面' }],
    })
    renderModal()
    await flush()
    await openConnectorTab()

    const drawer = await openDrawerOf(cardOf('oauth2'))
    const testBtn = Array.from(drawer.querySelectorAll('button'))
      .find((b) => (b.textContent ?? '').includes('测试连接')) as HTMLButtonElement
    expect(testBtn).toBeTruthy()
    act(() => { testBtn.click() })
    await flush()

    expect(connTestMock).toHaveBeenCalledWith('notion')
    const res = container!.querySelector('[data-testid="connector-probe"]')!
    expect(res.textContent).toContain('探测成功')
    expect(res.textContent).toContain('连接正常，获取以下工具列表（共 1 个）')
    expect(res.textContent).toContain('MCP 握手成功')
    expect(res.textContent).toContain('42ms')
    expect(res.className).not.toContain('fail')
    const items = res.querySelectorAll('.mc-probe-item')
    expect(items).toHaveLength(1)
    expect(items[0].querySelector('.mc-probe-idx')!.textContent).toBe('1')
    expect(items[0].querySelector('.mc-probe-name')!.textContent).toBe('query_pages')
  })

  it('probeLevel=connect_only：不出现成功文案，明确「仅连通，未完成 MCP 握手」', async () => {
    // 后端说得很清楚：HTTP 通了但 MCP 握手失败（401/协议版本）⇒ 不能显示「连接成功」
    connTestMock.mockResolvedValue({
      ok: false, message: 'HTTP 401 Unauthorized', latencyMs: 12, probeLevel: 'connect_only',
    })
    const m = mountInto(
      <ConnectorAuthDrawer
        connector={{ ...NOTION, connected: true, status: 'connected', account: 'me@notion' }}
        onClose={noop}
        onStatusChange={vi.fn()}
        showToast={noop}
      />,
    )
    try {
      const btn = Array.from(m.el.querySelectorAll('button'))
        .find((b) => (b.textContent ?? '').includes('测试连接')) as HTMLButtonElement
      expect(btn, '未找到测试连接按钮').toBeTruthy()
      act(() => { btn.click() })
      await flush()

      expect(connTestMock).toHaveBeenCalledWith('notion')
      const res = m.el.querySelector('[data-testid="connector-probe"]')!
      expect(res.textContent).toContain('仅连通，未完成 MCP 握手')
      expect(res.textContent).toContain('HTTP 401')
      expect(res.textContent).toContain('12ms')
      // ⛔ 不许出现成功字样（「成功」「连接正常」「MCP 握手成功」都不行）
      expect(res.textContent).not.toContain('成功')
      expect(res.textContent).not.toContain('连接正常')
      expect(res.className).toContain('fail')
    } finally {
      unmountFrom(m)
    }
  })

  it('非 oauth：填凭证走 POST /credentials（不冒充授权）', async () => {
    connCredMock.mockResolvedValue({
      connected: true, authType: 'apiKey', account: 'openalex', expiresAt: null, missingScopes: null,
    })
    const onStatusChange = vi.fn()
    const m = mountInto(
      <ConnectorAuthDrawer connector={API_KEY_CONN} onClose={noop} onStatusChange={onStatusChange} showToast={noop} />,
    )
    try {
      const input = m.el.querySelector('input[data-field="apiKey"]') as HTMLInputElement
      expect(input, '未渲染 API Key 输入框').toBeTruthy()

      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!
      act(() => {
        setter.call(input, 'sk-live-1')
        input.dispatchEvent(new Event('input', { bubbles: true }))
      })
      await flush()

      const saveBtn = Array.from(m.el.querySelectorAll('button'))
        .find((b) => (b.textContent ?? '').includes('保存并连接')) as HTMLButtonElement
      expect(saveBtn).toBeTruthy()
      act(() => { saveBtn.click() })
      await flush()

      expect(connCredMock).toHaveBeenCalledTimes(1)
      expect(connCredMock.mock.calls[0][0]).toBe('openalex')
      expect(connCredMock.mock.calls[0][1]).toBe('apiKey')
      expect(connCredMock.mock.calls[0][2]).toEqual({ apiKey: 'sk-live-1' })
      expect(onStatusChange).toHaveBeenCalledWith('openalex', expect.objectContaining({ connected: true }))
      expect(connStartMock).not.toHaveBeenCalled()
    } finally {
      unmountFrom(m)
    }
  })

  it('分类 pill：按 ConnectorDto.category 筛选（office → 办公）', async () => {
    connListMock.mockResolvedValue({ connectors: [NOTION_KNOWLEDGE, OFFICE_CONN] })
    renderModal()
    await flush()
    await openConnectorTab()
    expect(cards()).toHaveLength(2)

    const officePill = Array.from(container!.querySelectorAll('.mc-cat'))
      .find((b) => (b.textContent ?? '').trim() === '办公协作') as HTMLButtonElement
    expect(officePill, '未渲染分类 pill「办公协作」').toBeTruthy()
    act(() => { officePill.click() })
    await flush()

    expect(cards()).toHaveLength(1)
    expect(cards()[0].dataset.connectorKey).toBe('office')
  })

  it('「需授权」筛选：包含未连接与 reauth_required，且状态文案为「需重新授权」', async () => {
    connListMock.mockResolvedValue({
      connectors: [{ ...NOTION, connected: true, status: 'connected' }, OFFICE_CONN, REAUTH_CONN],
    })
    renderModal()
    await flush()
    await openConnectorTab()
    expect(cards()).toHaveLength(3)

    const needsPill = Array.from(container!.querySelectorAll('.mc-cat'))
      .find((b) => (b.textContent ?? '').trim() === '需授权') as HTMLButtonElement
    expect(needsPill).toBeTruthy()
    act(() => { needsPill.click() })
    await flush()

    const keys = cards().map((c) => c.dataset.connectorKey).sort()
    expect(keys).toEqual(['office', 're'])
    const reauthCard = cards().find((c) => c.dataset.connectorKey === 're')!
    expect(reauthCard.querySelector('[data-testid="connector-status"]')!.textContent).toContain('需重新授权')

    // 顶栏「已连接」按钮 = 只看可用已连接（reauth_required 的凭据已失效，不算）
    const connPill = container!.querySelector('.sm-installed-pill') as HTMLButtonElement
    expect(connPill.textContent).toContain('已连接 (1)')
    act(() => { connPill.click() })
    await flush()
    expect(cards()).toHaveLength(1)
    expect(cards()[0].dataset.connectorKey).toBe('notion')
  })

  it('测试连接返回 tools[] 时列出工具清单（兼容两种形状）', async () => {
    connTestMock.mockResolvedValue({
      ok: true, message: 'OK', latencyMs: 30, probeLevel: 'mcp_initialize',
      // 连接器形状 {name,description} + MCP 形状 string 混在同一字段：都要能渲染
      tools: [{ name: 'create_page', description: '新建页面' }, 'search'],
    })
    const m = mountInto(
      <ConnectorAuthDrawer
        connector={{ ...NOTION, connected: true, status: 'connected' }}
        onClose={noop}
        onStatusChange={vi.fn()}
        showToast={noop}
      />,
    )
    try {
      const btn = Array.from(m.el.querySelectorAll('button'))
        .find((b) => (b.textContent ?? '').includes('测试连接')) as HTMLButtonElement
      act(() => { btn.click() })
      await flush()

      const list = m.el.querySelector('[data-testid="connector-probe-list"]')!
      expect(list).toBeTruthy()
      expect(list.textContent).toContain('create_page')
      expect(list.textContent).toContain('新建页面')
      expect(list.textContent).toContain('search')
    } finally {
      unmountFrom(m)
    }
  })

  it('supportTier 优先：后端标 unavailable 的 api_key 仍置灰禁点（不被 authType 误判为可存凭据）', async () => {
    const GATEWAY = {
      ...API_KEY_CONN,
      id: 'qq-music', key: 'qq-music', name: 'QQ 音乐',
      supportTier: 'unavailable' as const,
      unavailableReason: '需要网关适配，本轮未实现',
    }
    connListMock.mockResolvedValue({ connectors: [GATEWAY] })
    renderModal()
    await flush()
    await openConnectorTab()

    const card = cards()[0]
    expect(card.dataset.tier).toBe('unavailable')
    expect(card.classList.contains('is-disabled')).toBe(true)
    // 展示后端给的原因，而不是自造文案
    expect(card.querySelector('[data-testid="connector-tier-note"]')!.textContent).toContain('网关适配')
    const btn = card.querySelector('.mc-btn') as HTMLButtonElement
    expect(btn.disabled).toBe(true)
    expect(btn.textContent).toContain('暂不支持')
    act(() => { card.click() })
    await flush()
    expect(container!.querySelector('[data-testid="connector-drawer"]')).toBeNull()
  })

  it('图标：已知 key 渲染本地打包图标，未知 key 回落首字母（不热链远程）', async () => {
    connListMock.mockResolvedValue({
      connectors: [NOTION, { ...NOTION, id: 'weird', key: 'weird', name: 'Weird', icon: null }],
    })
    renderModal()
    await flush()
    await openConnectorTab()

    const notionCard = container!.querySelector('[data-connector-key="notion"]') as HTMLElement
    const img = notionCard.querySelector('img.mc-logo') as HTMLImageElement
    expect(img, '已知 key 未渲染图标').toBeTruthy()
    const src = img.getAttribute('src') ?? ''
    expect(src).toBeTruthy()
    expect(src.startsWith('http')).toBe(false)

    const weirdCard = container!.querySelector('[data-connector-key="weird"]') as HTMLElement
    expect(weirdCard.querySelector('img.mc-logo')).toBeNull()
    expect(weirdCard.querySelector('.mc-ico-fallback')!.textContent).toBe('W')
  })

  it('结构样式类齐备：mc-card-top / 44 图标容器 / 标题 / 分类 chip / 描述', async () => {
    connListMock.mockResolvedValue({ connectors: [NOTION_KNOWLEDGE] })
    renderModal()
    await flush()
    await openConnectorTab()

    const card = cards()[0]
    expect(card.classList.contains('mc-card')).toBe(true)
    expect(card.querySelector('.mc-card-top')).toBeTruthy()
    expect(card.querySelector('.mc-ico-lg')).toBeTruthy()
    expect(card.querySelector('.mc-title')!.textContent).toContain('Notion')
    expect(card.querySelector('.mc-chip')!.textContent).toContain('笔记与知识')
    expect(card.querySelector('.mc-desc')).toBeTruthy()
  })

  it('两子 tab + 唯一「自定义连接器」入口：已启用只显示已连接，点击打开弹窗', async () => {
    connListMock.mockResolvedValue({
      connectors: [{ ...NOTION, connected: true, status: 'connected' }, OFFICE_CONN],
    })
    mcpListMock.mockResolvedValue([
      { id: 'm1', name: 'my-server', type: 'http', runtimeStatus: 'connected', status: 'running' },
      { id: 'm2', name: 'notion', type: 'http', runtimeStatus: 'stopped', status: 'stopped' },
    ])
    renderModal()
    await flush()
    await openConnectorTab()
    expect(cards()).toHaveLength(2)

    const tabOf = (label: string) => Array.from(container!.querySelectorAll('.mc-cat'))
      .find((b) => (b.textContent ?? '').trim() === label) as HTMLButtonElement

    act(() => { tabOf('已启用').click() })
    await flush()
    expect(cards()).toHaveLength(1)
    expect(cards()[0].dataset.connectorKey).toBe('notion')

    // ⛔ 只保留一个「自定义连接器」入口（旧的「+ 添加连接器」不再出现在连接器页）
    const customButtons = Array.from(container!.querySelectorAll('button'))
      .filter((b) => (b.textContent ?? '').includes('自定义连接器'))
    expect(customButtons, '连接器页「自定义连接器」按钮必须恰好 1 个').toHaveLength(1)

    act(() => { customButtons[0].click() })
    await flush()
    expect(container!.querySelector('[data-testid="custom-connector-dialog"]')).toBeTruthy()
    expect(mcpListMock).toHaveBeenCalledTimes(1)
    const rows = Array.from(container!.querySelectorAll('[data-testid="custom-connector-row"]'))
    // notion 是目录里的 key（由连接器授权产生）⇒ 自定义里排除
    expect(rows).toHaveLength(1)
    expect(rows[0].textContent).toContain('my-server')
    expect(rows[0].textContent).toContain('已连接')
  })

  it('探测成功但无工具：面板显示「连接正常，但未发现可用工具」', async () => {
    connTestMock.mockResolvedValue({
      ok: true, message: 'ok', latencyMs: 8, probeLevel: 'mcp_initialize', tools: [],
    })
    const m = mountInto(
      <ConnectorAuthDrawer
        connector={{ ...NOTION, connected: true, status: 'connected' }}
        onClose={noop}
        onStatusChange={vi.fn()}
        showToast={noop}
      />,
    )
    try {
      act(() => { btnWithText(m.el, '测试连接')!.click() })
      await flush()
      const probe = m.el.querySelector('[data-testid="connector-probe"]')!
      expect(probe.textContent).toContain('探测成功')
      expect(probe.textContent).toContain('连接正常，但未发现可用工具')
      expect(probe.querySelector('[data-testid="connector-probe-list"]')).toBeNull()
    } finally {
      unmountFrom(m)
    }
  })

  it('头部状态胶囊：未连接 → 未连接；已连接 → 已授权', async () => {
    connListMock.mockResolvedValue({
      connectors: [NOTION, { ...OFFICE_CONN, id: 'off2', key: 'off2', connected: true, status: 'connected' }],
    })
    renderModal()
    await flush()
    await openConnectorTab()

    const drawer1 = await openDrawerOf(container!.querySelector('[data-connector-key="notion"]') as HTMLElement)
    expect(drawer1.querySelector('[data-testid="drawer-status"]')!.textContent).toContain('未连接')
    act(() => { (drawer1.querySelector('.mc-x') as HTMLElement).click() })
    await flush()

    const drawer2 = await openDrawerOf(container!.querySelector('[data-connector-key="off2"]') as HTMLElement)
    expect(drawer2.querySelector('[data-testid="drawer-status"]')!.textContent).toContain('已授权')
  })

  it('ESC 关闭抽屉', async () => {
    connListMock.mockResolvedValue({ connectors: [NOTION] })
    renderModal()
    await flush()
    await openConnectorTab()
    await openDrawerOf(cardOf('oauth2'))

    act(() => { window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' })) })
    await flush()
    expect(container!.querySelector('[data-testid="connector-drawer"]')).toBeNull()
  })

  it('reauth_required：头部「需重新授权」+ 失效提示 + 主 CTA「重新授权」', async () => {
    connListMock.mockResolvedValue({
      connectors: [{ ...NOTION, connected: true, status: 'reauth_required' }],
    })
    renderModal()
    await flush()
    await openConnectorTab()
    const drawer = await openDrawerOf(cardOf('oauth2'))

    expect(drawer.querySelector('[data-testid="drawer-status"]')!.textContent).toContain('需重新授权')
    expect(container!.querySelector('[data-testid="connector-reauth"]')!.textContent).toContain('授权已失效')
    expect(btnWithText(container!.querySelector('[data-testid="connector-drawer"]')!, '重新授权')).toBeTruthy()
  })

  it('后端 authHint / docUrl 有值才渲染（没有不伪造）', async () => {
    const WITH_HINT = {
      ...NOTION,
      authHint: '去平台创建应用后复制 Client ID',
      docUrl: 'https://example.com/connector-doc',
    }
    const m = mountInto(
      <ConnectorAuthDrawer connector={WITH_HINT} onClose={noop} onStatusChange={vi.fn()} showToast={noop} />,
    )
    try {
      expect(m.el.querySelector('[data-testid="connector-auth-hint"]')!.textContent).toContain('去平台创建应用')
      act(() => { btnWithText(m.el, '查看获取说明')!.click() })
      await flush()
      expect(openExternalMock).toHaveBeenCalledWith('https://example.com/connector-doc')
    } finally {
      unmountFrom(m)
    }

    const m2 = mountInto(
      <ConnectorAuthDrawer connector={NOTION} onClose={noop} onStatusChange={vi.fn()} showToast={noop} />,
    )
    try {
      expect(m2.el.querySelector('[data-testid="connector-auth-hint"]')).toBeNull()
      expect(btnWithText(m2.el, '查看获取说明')).toBeUndefined()
    } finally {
      unmountFrom(m2)
    }
  })

  it('全量 30 张：请求带 includeHidden=true，hidden 条目置灰并标「内部/企业」', async () => {
    const many = Array.from({ length: 30 }, (_, i) => ({
      ...NOTION, id: `c${i}`, key: `c${i}`, name: `连接器 ${i}`, hidden: i >= 28,
    }))
    connListMock.mockResolvedValue({ connectors: many })
    renderModal()
    await flush()
    await openConnectorTab()

    // 列表请求必须带 includeHidden（否则 qcc/tencent-ardot 被后端隐藏，只剩 28）
    expect(connListMock).toHaveBeenCalledWith(true)
    expect(cards()).toHaveLength(30)

    const hidden = cards().filter((c) => c.dataset.hidden === 'true')
    expect(hidden).toHaveLength(2)
    expect(hidden[0].dataset.tier).toBe('unavailable')
    expect(hidden[0].textContent).toContain('内部/企业')
    const hiddenBtn = hidden[0].querySelector('.mc-btn') as HTMLButtonElement
    expect(hiddenBtn.disabled).toBe(true)
    expect(hiddenBtn.textContent).toContain('暂不支持')
  })

  it('卡片分区固定：标题/描述/提示均带省略兜底，长文本不撑破布局', async () => {
    const LONG = { ...NOTION, description: '很长的连接器说明'.repeat(30) }
    connListMock.mockResolvedValue({ connectors: [LONG, API_KEY_CONN] })
    renderModal()
    await flush()
    await openConnectorTab()

    const card = cards()[0]
    expect(card.querySelector('.mc-title')!.getAttribute('title')).toBe('Notion')
    expect(card.querySelector('.mc-desc')!.getAttribute('title')).toContain('很长的连接器说明')
    expect(card.querySelector('.mc-chip-row')).toBeTruthy()
    expect(card.querySelector('.mc-foot .mc-btn')).toBeTruthy()
    // supported 卡无提示文字（不靠平铺长原因撑卡片）
    expect(card.querySelector('.mc-tier-note')).toBeNull()

    const cred = cardOf('apiKey')
    const note = cred.querySelector('[data-testid="connector-tier-note"]')!
    expect(note.getAttribute('title')).toBeTruthy()
  })

  it('抽屉结构：authHint → 文档链接 → 连接配置 → 探测面板（不再有独立状态框）', async () => {
    const WITH = { ...NOTION, authHint: '先到平台创建应用', docUrl: 'https://example.com/d' }
    connTestMock.mockResolvedValue({
      ok: true, message: 'ok', latencyMs: 5, probeLevel: 'mcp_initialize', tools: [],
    })
    const m = mountInto(
      <ConnectorAuthDrawer
        connector={{ ...WITH, connected: true, status: 'connected' }}
        onClose={noop}
        onStatusChange={vi.fn()}
        showToast={noop}
      />,
    )
    try {
      expect(m.el.querySelector('[data-testid="drawer-statusline"]')).toBeNull()
      expect(m.el.querySelector('[data-testid="connector-line-hint"]')).toBeTruthy()
      expect(m.el.textContent).toContain('连接配置')

      act(() => { btnWithText(m.el, '测试连接')!.click() })
      await flush()

      const hint = m.el.querySelector('[data-testid="connector-auth-hint"]')!
      const sec = Array.from(m.el.querySelectorAll('.mc-sec')).find((e) => e.textContent === '连接配置')!
      const probe = m.el.querySelector('[data-testid="connector-probe"]')!
      expect(sec).toBeTruthy()
      // Node.DOCUMENT_POSITION_FOLLOWING = 4
      expect(hint.compareDocumentPosition(sec) & 4).toBe(4)
      expect(sec.compareDocumentPosition(probe) & 4).toBe(4)
    } finally {
      unmountFrom(m)
    }
  })

  it('官方徽标：卡片与抽屉显示「官方 MCP」', async () => {
    const OFFICIAL = { ...NOTION, official: true }
    connListMock.mockResolvedValue({ connectors: [OFFICIAL] })
    renderModal()
    await flush()
    await openConnectorTab()
    expect(cards()[0].textContent).toContain('官方 MCP')

    const drawer = await openDrawerOf(cardOf('oauth2'))
    expect(drawer.textContent).toContain('官方 MCP')
  })

  it('authMode=bearer：单个 Token 输入 + Bearer 说明', async () => {
    const m = mountInto(
      <ConnectorAuthDrawer
        connector={{ ...NOTION, authType: 'personalToken', authMode: 'bearer', supportTier: 'credentialOnly' }}
        onClose={noop} onStatusChange={vi.fn()} showToast={noop}
      />,
    )
    try {
      const input = m.el.querySelector('input[data-field="token"]') as HTMLInputElement
      expect(input, 'bearer 模式应有 Token 输入').toBeTruthy()
      expect(input.type).toBe('password')
      expect(m.el.textContent).toContain('Authorization: Bearer')
      expect(m.el.querySelectorAll('input[data-field]')).toHaveLength(1)
    } finally {
      unmountFrom(m)
    }
  })

  it('authMode=header：显示请求头名（X-Lark-MCP-UAT）', async () => {
    const m = mountInto(
      <ConnectorAuthDrawer
        connector={{
          ...NOTION, authType: 'personalToken', authMode: 'header',
          authHeaderName: 'X-Lark-MCP-UAT', supportTier: 'credentialOnly',
        }}
        onClose={noop} onStatusChange={vi.fn()} showToast={noop}
      />,
    )
    try {
      expect(m.el.querySelector('input[data-field="token"]')).toBeTruthy()
      expect(m.el.textContent).toContain('X-Lark-MCP-UAT')
      expect(m.el.textContent).toContain('请求头 X-Lark-MCP-UAT')
    } finally {
      unmountFrom(m)
    }
  })

  it('authMode=query：Key 输入并提示会拼到 URL', async () => {
    const m = mountInto(
      <ConnectorAuthDrawer
        connector={{ ...NOTION, authType: 'apiKey', authMode: 'query', supportTier: 'credentialOnly' }}
        onClose={noop} onStatusChange={vi.fn()} showToast={noop}
      />,
    )
    try {
      expect(m.el.querySelector('input[data-field="apiKey"]')).toBeTruthy()
      expect(m.el.textContent).toContain('查询参数')
      expect(m.el.textContent).toContain('MCP 地址')
    } finally {
      unmountFrom(m)
    }
  })

  it('后端 credentialFields 优先：按声明字段渲染（含非必填）', async () => {
    const m = mountInto(
      <ConnectorAuthDrawer
        connector={{
          ...NOTION, authType: 'customFields', supportTier: 'credentialOnly',
          credentialFields: [
            { key: 'base_url', label: 'Base URL', placeholder: 'https://example.com', required: true },
            { key: 'api_key', label: 'API Key', fieldType: 'password', secret: true, required: false },
          ],
        }}
        onClose={noop} onStatusChange={vi.fn()} showToast={noop}
      />,
    )
    try {
      expect(m.el.querySelector('input[data-field="base_url"]')).toBeTruthy()
      const secret = m.el.querySelector('input[data-field="api_key"]') as HTMLInputElement
      expect(secret).toBeTruthy()
      expect(secret.type).toBe('password')
      expect(m.el.textContent).toContain('Base URL')
    } finally {
      unmountFrom(m)
    }
  })

  it('自定义连接器 JSON 模式：非法 JSON 禁存；合法才可导入', async () => {
    mcpImportMock.mockResolvedValue({ added: 1, updated: 0, removed: 0, unchanged: 0 })
    connListMock.mockResolvedValue({ connectors: [] })
    renderModal()
    await flush()
    await openConnectorTab()
    const openBtn = Array.from(container!.querySelectorAll('button'))
      .find((b) => (b.textContent ?? '').includes('自定义连接器'))!
    act(() => { openBtn.click() })
    await flush()

    const jsonTab = Array.from(container!.querySelectorAll('.mc-dlg-tabs button'))
      .find((b) => (b.textContent ?? '').includes('JSON')) as HTMLButtonElement
    act(() => { jsonTab.click() })
    await flush()
    // 导出结果进编辑框
    expect(mcpExportMock).toHaveBeenCalledTimes(1)
    const area = container!.querySelector('[data-testid="custom-connector-json"]') as HTMLTextAreaElement
    expect(area.value).toContain('mcpServers')

    const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')!.set!
    const saveBtn = () => container!.querySelector('[data-testid="custom-connector-json-save"]') as HTMLButtonElement

    act(() => { setter.call(area, '{ bad json'); area.dispatchEvent(new Event('input', { bubbles: true })) })
    await flush()
    expect(saveBtn().disabled).toBe(true)
    expect(container!.querySelector('[data-testid="custom-connector-json-err"]')).toBeTruthy()

    act(() => {
      setter.call(area, JSON.stringify({ mcpServers: { probe: { type: 'http', url: 'https://x' } } }))
      area.dispatchEvent(new Event('input', { bubbles: true }))
    })
    await flush()
    expect(saveBtn().disabled).toBe(false)

    act(() => { saveBtn().click() })
    await flush()
    expect(mcpImportMock).toHaveBeenCalledTimes(1)
    expect(mcpImportMock.mock.calls[0][0]).toMatchObject({
      mcpServers: { probe: { type: 'http', url: 'https://x' } },
      deleteMissing: false,
    })
  })

  it('自定义连接器表单模式：stdio 必填 Command，提交走 mcpApi.create', async () => {
    connListMock.mockResolvedValue({ connectors: [] })
    mcpCreateMock.mockResolvedValue({ id: 'n1', name: 'probe' })
    renderModal()
    await flush()
    await openConnectorTab()
    act(() => {
      ;(Array.from(container!.querySelectorAll('button'))
        .find((b) => (b.textContent ?? '').includes('自定义连接器')) as HTMLButtonElement).click()
    })
    await flush()

    const formTab = Array.from(container!.querySelectorAll('.mc-dlg-tabs button'))
      .find((b) => (b.textContent ?? '').includes('表单')) as HTMLButtonElement
    act(() => { formTab.click() })
    await flush()

    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!
    const nameInput = container!.querySelector('input[data-field="name"]') as HTMLInputElement
    const cmdInput = Array.from(container!.querySelectorAll('.mc-dlg input')) as HTMLInputElement[]
    const command = cmdInput.find((i) => (i.placeholder ?? '').includes('server-filesystem'))!
    act(() => {
      setter.call(nameInput, 'probe'); nameInput.dispatchEvent(new Event('input', { bubbles: true }))
      setter.call(command, 'npx -y some-server'); command.dispatchEvent(new Event('input', { bubbles: true }))
    })
    await flush()

    const addBtn = Array.from(container!.querySelectorAll('.mc-dlg .mc-df button'))
      .find((b) => (b.textContent ?? '').trim() === '添加') as HTMLButtonElement
    act(() => { addBtn.click() })
    await flush()
    expect(mcpCreateMock).toHaveBeenCalledTimes(1)
    expect(mcpCreateMock.mock.calls[0][0]).toMatchObject({ name: 'probe', type: 'stdio', command: 'npx -y some-server' })
  })

  it('三类卡片共用 market-card-base（与连接器卡同一套样式/字体）', async () => {
    listExternalMock.mockResolvedValue({
      name: 'zjky-market',
      plugins: [
        { name: 'exp1', displayName: '专家一', description: 'x', category: 'expert', tags: [] },
        { name: 'sk1', displayName: '技能一', description: 'x', category: 'skill', tags: [] },
      ],
    })
    connListMock.mockResolvedValue({ connectors: [NOTION] })
    renderModal()
    await flush()
    // 专家（默认 tab）
    expect(container!.querySelector('.sm-expert-card.market-card-base')).toBeTruthy()
    // 技能
    const skillTab = Array.from(container!.querySelectorAll('.sm-tab-flat'))
      .find((b) => (b.textContent ?? '').includes('技能')) as HTMLElement
    act(() => { skillTab.click() })
    await flush()
    expect(container!.querySelector('.sk-card.market-card-base')).toBeTruthy()
    // 连接器
    await openConnectorTab()
    expect(container!.querySelector('.mc-card')).toBeTruthy()
  })
})