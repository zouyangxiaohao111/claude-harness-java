// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MCPPanel } from '../MCPPanel'
import type { UseMcp } from '@/hooks/useMcp'
import type { McpServer } from '@/api/types'

/**
 * MCPPanel · OAuth 配置补全 + 授权/断开。
 *
 * 钉住：字段回显 / falsy 门（全空不下发 oauth）/ 回调地址复制 /
 * 授权轮询（start→系统浏览器→poll→刷新）/ 断开二次确认。
 */
const {
  createMock, updateMock, refreshMock, oauthStartMock, oauthPollMock, oauthLogoutMock,
  doctorRuntimeMock, openExternalMock, writeTextMock, testMock,
} = vi.hoisted(() => ({
  createMock: vi.fn(),
  updateMock: vi.fn(),
  refreshMock: vi.fn(),
  oauthStartMock: vi.fn(),
  oauthPollMock: vi.fn(),
  oauthLogoutMock: vi.fn(),
  doctorRuntimeMock: vi.fn(),
  openExternalMock: vi.fn(),
  writeTextMock: vi.fn(),
  testMock: vi.fn(),
}))

vi.mock('@/api/mcp', () => ({
  mcpApi: {
    list: vi.fn(async () => []),
    create: createMock,
    update: updateMock,
    remove: vi.fn(),
    test: testMock,
    approve: vi.fn(),
    reject: vi.fn(),
    import: vi.fn(),
    start: vi.fn(),
    stop: vi.fn(),
    oauthStart: oauthStartMock,
    oauthPoll: oauthPollMock,
    oauthLogout: oauthLogoutMock,
  },
}))
vi.mock('@/api/doctor', () => ({ doctorApi: { runtime: doctorRuntimeMock } }))
vi.mock('@/utils/openExternal', () => ({ openExternalUrl: openExternalMock }))

const REMOTE = {
  id: 'mcp-1',
  name: 'notion-remote',
  type: 'http',
  url: 'https://mcp.example.com/mcp',
  enabled: true,
  autoStart: false,
  status: 'stopped',
  runtimeStatus: 'needsAuth',
  approvalStatus: 'approved',
  oauth: {
    clientId: 'cid-1',
    callbackPort: '8080',
    authServerMetadataUrl: 'https://auth.example.com/.well-known/x',
    scopes: 'read write',
    authorizationEndpoint: 'https://auth.example.com/authorize',
    tokenEndpoint: 'https://auth.example.com/token',
    xaa: true,
  },
} as unknown as McpServer

const STDIO = {
  id: 'mcp-stdio',
  name: 'local-stdio',
  command: 'codegraph',
  args: ['serve', '--mcp'],
  env: {},
  enabled: true,
  autoStart: true,
  status: 'running',
  runtimeStatus: 'connected',
  approvalStatus: 'approved',
  type: 'stdio',
  oauth: null,
} as unknown as McpServer

function fakeUseMcp(list: McpServer[]): UseMcp {
  return {
    list,
    loading: false,
    error: null,
    refresh: refreshMock,
    createMcp: createMock,
    updateMcp: updateMock,
    deleteMcp: vi.fn(),
    toggleMcp: vi.fn(),
  } as unknown as UseMcp
}

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

  createMock.mockReset()
  updateMock.mockReset()
  refreshMock.mockReset().mockResolvedValue(undefined)
  doctorRuntimeMock.mockReset().mockResolvedValue({ platform: 'win32', runtimes: {}, missingForMcp: [] })
  openExternalMock.mockReset().mockResolvedValue(true)
  oauthStartMock.mockReset()
  oauthPollMock.mockReset()
  oauthLogoutMock.mockReset()
  testMock.mockReset()
  writeTextMock.mockReset().mockResolvedValue(undefined)
  Object.defineProperty(navigator, 'clipboard', { value: { writeText: writeTextMock }, configurable: true })
})

afterEach(() => {
  if (root) act(() => root!.unmount())
  container?.remove()
  vi.useRealTimers()
})

function render(list: McpServer[]) {
  act(() => {
    root!.render(<MCPPanel mcpApi={fakeUseMcp(list)} showToast={() => undefined} sessionId="s1" />)
  })
}

async function openRow() {
  const hit = container!.querySelector('.cli-card-hit') as HTMLElement
  expect(hit, '未找到 MCP 行').toBeTruthy()
  act(() => { hit.click() })
  await flush()
}

function byText(text: string, scope: ParentNode = container!): HTMLElement | undefined {
  return Array.from(scope.querySelectorAll('button'))
    .find((b) => (b.textContent ?? '').includes(text)) as HTMLElement | undefined
}

const setter = () => Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!

describe('MCPPanel · OAuth', () => {
  it('编辑表单回显 scopes / 授权端点 / 令牌端点，并展示回调地址', async () => {
    render([REMOTE])
    await flush()
    await openRow()
    act(() => { byText('编辑')!.click() })
    await flush()

    const values = (Array.from(container!.querySelectorAll('input')) as HTMLInputElement[]).map((i) => i.value)
    expect(values).toContain('read write')
    expect(values).toContain('https://auth.example.com/authorize')
    expect(values).toContain('https://auth.example.com/token')
    expect(container!.querySelector('[data-testid="mcp-callback-uri"]')!.textContent)
      .toBe('http://localhost:8080/callback')
  })

  it('回调地址一键复制', async () => {
    render([REMOTE])
    await flush()
    await openRow()
    act(() => { byText('编辑')!.click() })
    await flush()

    const copyBtn = Array.from(container!.querySelectorAll('button'))
      .find((b) => (b.textContent ?? '').trim() === '复制') as HTMLButtonElement
    act(() => { copyBtn.click() })
    await flush()
    expect(writeTextMock).toHaveBeenCalledWith('http://localhost:8080/callback')
  })

  it('falsy 门：OAuth 字段全空时不下发 oauth（老行为不变）', async () => {
    render([])
    await flush()
    act(() => { byText('添加服务器')!.click() })
    await flush()

    // 切到 http（只有远程分支才会组装 oauth）
    const select = container!.querySelector('.mcp-add-form select') as HTMLSelectElement
    const selectSetter = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value')!.set!
    act(() => {
      selectSetter.call(select, 'http')
      select.dispatchEvent(new Event('change', { bubbles: true }))
    })
    await flush()

    const inputs = Array.from(container!.querySelectorAll('.mcp-add-form input')) as HTMLInputElement[]
    const nameInput = inputs.find((i) => (i.placeholder ?? '').includes('filesystem'))!
    const urlInput = inputs.find((i) => (i.placeholder ?? '').includes('mcp.example.com'))!
    expect(nameInput, '未找到服务器名称输入框').toBeTruthy()
    expect(urlInput, '未找到 URL 输入框').toBeTruthy()
    act(() => {
      setter().call(nameInput, 'my-remote')
      nameInput.dispatchEvent(new Event('input', { bubbles: true }))
      setter().call(urlInput, 'https://mcp.example.com/mcp')
      urlInput.dispatchEvent(new Event('input', { bubbles: true }))
    })
    await flush()

    const actions = container!.querySelector('.mcp-add-actions')!
    act(() => { byText('添加服务器', actions)!.click() })
    await flush()
    expect(createMock).toHaveBeenCalledTimes(1)
    expect(createMock.mock.calls[0][0]).toMatchObject({ type: 'http', url: 'https://mcp.example.com/mcp' })
    expect(createMock.mock.calls[0][0].oauth).toBeUndefined()
  })

  it('授权：start → 打开系统浏览器 → 轮询 AUTHORIZED → 刷新列表', async () => {
    vi.useFakeTimers()
    render([REMOTE])
    await flush()
    await openRow()

    oauthStartMock.mockResolvedValue({
      authorizeUrl: 'https://auth.example.com/authorize?x=1',
      state: 'st-mcp',
      redirectUri: 'http://localhost:8080/callback',
      interval: 1,
      expiresIn: 300,
    })
    oauthPollMock.mockResolvedValue({ state: 'AUTHORIZED', message: null })

    const row = container!.querySelector('[data-testid="mcp-oauth-row"]')!
    expect(row, '远程 server 未渲染 OAuth 行').toBeTruthy()
    act(() => { byText('授权', row)!.click() })
    await flush()

    expect(oauthStartMock).toHaveBeenCalledWith('mcp-1')
    expect(openExternalMock).toHaveBeenCalledWith('https://auth.example.com/authorize?x=1')

    await act(async () => { vi.advanceTimersByTime(1000) })
    await flush()

    expect(oauthPollMock).toHaveBeenCalledWith('mcp-1', 'st-mcp')
    expect(refreshMock).toHaveBeenCalled()
    expect(container!.querySelector('[data-testid="mcp-oauth-row"]')!.textContent)
      .toContain('本次授权已完成')
  })

  it('断开：先二次确认，确认后才调 DELETE 并刷新', async () => {
    render([REMOTE])
    await flush()
    await openRow()

    oauthLogoutMock.mockResolvedValue({ revoked: true })
    const row = container!.querySelector('[data-testid="mcp-oauth-row"]')!
    act(() => { byText('断开', row)!.click() })
    await flush()
    expect(oauthLogoutMock).not.toHaveBeenCalled()

    act(() => { byText('确认断开', row)!.click() })
    await flush()
    expect(oauthLogoutMock).toHaveBeenCalledWith('mcp-1')
    expect(refreshMock).toHaveBeenCalled()
  })

  it('保存修改：scopes / 授权端点 / 令牌端点随 oauth 一起提交（接线验证）', async () => {
    render([REMOTE])
    await flush()
    await openRow()
    act(() => { byText('编辑')!.click() })
    await flush()

    updateMock.mockResolvedValue(REMOTE)
    act(() => { byText('保存修改')!.click() })
    await flush()

    expect(updateMock).toHaveBeenCalledTimes(1)
    expect(updateMock.mock.calls[0][1].oauth).toMatchObject({
      clientId: 'cid-1',
      callbackPort: '8080',
      authServerMetadataUrl: 'https://auth.example.com/.well-known/x',
      scopes: 'read write',
      authorizationEndpoint: 'https://auth.example.com/authorize',
      tokenEndpoint: 'https://auth.example.com/token',
      xaa: true,
    })
  })

  it('stdio 行：不出现「OAuth 授权」块，编辑表单也没有 OAuth 段', async () => {
    render([STDIO])
    await flush()
    await openRow()
    expect(container!.querySelector('[data-testid="mcp-oauth-row"]'), 'stdio 行不应有 OAuth 块').toBeNull()

    act(() => { byText('编辑')!.click() })
    await flush()
    expect(container!.textContent).not.toContain('OAuth（可选）')
    expect(container!.querySelector('[data-testid="mcp-callback-uri"]'), 'stdio 表单不应有回调地址').toBeNull()
  })

  it('测试连接：在行详情里显示本次结果与真实工具清单（tools: string[]）', async () => {
    render([REMOTE])
    await flush()
    await openRow()

    testMock.mockResolvedValue({
      ok: true, latencyMs: 18, message: 'ok', details: null,
      tools: ['create_page', 'search_pages'],
    })
    act(() => { byText('测试')!.click() })
    await flush()

    expect(testMock).toHaveBeenCalledWith('mcp-1')
    const res = container!.querySelector('[data-testid="mcp-test-result"]')!
    expect(res.textContent).toContain('通过')
    expect(res.textContent).toContain('18ms')
    const tools = container!.querySelector('[data-testid="mcp-test-tools"]')!
    expect(tools.textContent).toContain('create_page')
    expect(tools.textContent).toContain('search_pages')
  })
})
