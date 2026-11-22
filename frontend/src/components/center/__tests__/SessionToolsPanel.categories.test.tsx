// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { SessionToolDto } from '@/api/types'

/**
 * 工具面板两级联动契约 · ⭐ 本组断言必须存在（否则等于没修）：
 *
 * <ol>
 *   <li><b>再点同一行收起二级</b> —— 用户 2026-11-12 原话「右边二级菜单展示可以收起来
 *       现在点击后无法收起」，这是被报过缺陷，缺用例就等于没修；</li>
 *   <li><b>点分类开关不开二级</b> —— 开关与行点击是两个动作。若开关也触发展开，
 *       用户想关掉「电脑操作」反而弹出一个大面板；</li>
 *   <li><b>浏览器组 0 项时开关不可点</b> —— 未装扩展时该组为空，点了也不该发请求；</li>
 *   <li><b>核心三工具不可禁用</b> —— 后端对 Agent/TaskStop/SendMessage 禁用返回 400，
 *       前端必须置灰，否则用户点了必然失败。</li>
 * </ol>
 */

const listTools = vi.fn()
const setToolEnabled = vi.fn()
const setCategoryEnabled = vi.fn()

vi.mock('@/api/sessions', () => ({
  sessionApi: {
    listTools: (...a: unknown[]) => listTools(...a),
    setToolEnabled: (...a: unknown[]) => setToolEnabled(...a),
    setCategoryEnabled: (...a: unknown[]) => setCategoryEnabled(...a),
  },
}))

// ApiError 只需 instanceof 判断，用轻量替身
vi.mock('@/api/rest', () => ({
  ApiError: class ApiError extends Error {
    userMessage() { return this.message }
  },
}))

import { SessionToolsPanel } from '@/components/center/SessionToolsPanel'

let container: HTMLDivElement
let root: Root

function t(
  name: string,
  category: SessionToolDto['category'],
  disabled = false,
  userFacingName = name,
  mcpServer?: string,
): SessionToolDto {
  return { name, userFacingName, category, description: `${name} description`, disabled, mcpServer }
}

const FIXTURE: SessionToolDto[] = [
  // CU：默认禁用
  t('mcp__computer-use__screenshot', 'CU', true, '截取屏幕'),
  t('mcp__computer-use__key', 'CU', true, '按键'),
  // KB：默认禁用
  t('search_knowledge', 'KB', true, '检索知识库'),
  // BR：0 项（未装扩展）
  // MCP：第三方 server（rea 半开 / other 全开）· 一个 server 多工具，需要三级开关
  t('mcp__rea__inspect_plist', 'MCP', false, '读取 plist', 'rea'),
  t('mcp__rea__list_names', 'MCP', true, '列出符号', 'rea'),
  t('mcp__other__do_thing', 'MCP', false, '其它工具', 'other'),
  // IM：自建通讯工具（1 开 1 关 → 半开），独立成组不落进系统工具
  t('SendImAsUser', 'IM', false, '以我本人名义发消息'),
  t('ListImChats', 'IM', true, '列出 IM 会话'),
  // SYS：部分禁用（半开）
  t('Bash', 'SYS', false, 'Bash（终端）'),
  t('Read', 'SYS', true, '读取文件'),
  t('Agent', 'SYS', false, '子代理'),
]

async function mount(props: Partial<React.ComponentProps<typeof SessionToolsPanel>> = {}) {
  await act(async () => {
    root.render(
      <SessionToolsPanel
        sessionId="sess-1"
        showToast={() => {}}
        onClose={() => {}}
        {...props}
      />,
    )
  })
  // 等 listTools 的 promise 落地
  await act(async () => { await Promise.resolve() })
}

beforeEach(() => {
  (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  listTools.mockReset().mockResolvedValue(FIXTURE)
  setToolEnabled.mockReset()
  setCategoryEnabled.mockReset().mockResolvedValue(FIXTURE)
})

afterEach(() => {
  act(() => root.unmount())
  container.remove()
})

describe('SessionToolsPanel · 分类分组', () => {
  it('6 类按 CU→KB→BR→MCP→IM→SYS 顺序渲染，并显示 N 个 · M 个已启用', async () => {
    await mount()
    const names = Array.from(container.querySelectorAll('.l1-name')).map((n) => n.textContent)
    expect(names).toEqual(['电脑操作', '知识库', '浏览器控制', 'MCP 工具', 'IM 工具', '系统工具'])

    const subs = Array.from(container.querySelectorAll('.l1-sub')).map((n) => n.textContent)
    expect(subs[0]).toBe('2 个 · 0 个已启用')   // CU 全部禁用
    expect(subs[1]).toBe('1 个 · 0 个已启用')   // KB 全部禁用
    expect(subs[2]).toBe('需安装 Chrome 扩展')  // BR 空组
    expect(subs[3]).toBe('3 个 · 2 个已启用')   // MCP 半开（third-party 工具不再混进系统工具）
    expect(subs[4]).toBe('2 个 · 1 个已启用')   // IM 半开（自建通讯工具独立成组）
    expect(subs[5]).toBe('3 个 · 2 个已启用')   // SYS 半开
  })
})

describe('SessionToolsPanel · 二级展开与收起', () => {
  it('点行展开二级', async () => {
    await mount()
    const items = container.querySelectorAll('.l1-item')
    await act(async () => { (items[0] as HTMLElement).click() })
    expect(container.querySelector('.tools-panel')).not.toBeNull()
    expect(container.querySelector('.tools-panel-title')?.textContent).toContain('电脑操作')
  })

  it('⭐ 再点同一行收起二级（用户报缺陷 · 缺此用例等于没修）', async () => {
    await mount()
    const items = container.querySelectorAll('.l1-item')
    await act(async () => { (items[0] as HTMLElement).click() })
    expect(container.querySelector('.tools-panel'), '首次点击应展开').not.toBeNull()

    await act(async () => { (items[0] as HTMLElement).click() })
    expect(container.querySelector('.tools-panel'), '再点同一行应收起').toBeNull()
    // 收起后该行不再有 sel 高亮
    expect(container.querySelectorAll('.l1-item.sel').length).toBe(0)
  })

  it('点 × 收起二级（一级保持）', async () => {
    await mount()
    const items = container.querySelectorAll('.l1-item')
    await act(async () => { (items[0] as HTMLElement).click() })
    const closeBtn = container.querySelector('.tools-panel-close') as HTMLButtonElement
    expect(closeBtn).not.toBeNull()
    await act(async () => { closeBtn.click() })
    expect(container.querySelector('.tools-panel')).toBeNull()
    // 一级目录仍在
    expect(container.querySelectorAll('.l1-item').length).toBe(6)
  })

  it('切换到另一类后二级内容随之变化', async () => {
    await mount()
    const items = container.querySelectorAll('.l1-item')
    await act(async () => { (items[0] as HTMLElement).click() })
    expect(container.querySelector('.tools-panel-title')?.textContent).toContain('电脑操作')
    await act(async () => { (items[5] as HTMLElement).click() })
    expect(container.querySelector('.tools-panel-title')?.textContent).toContain('系统工具')
  })
})

describe('SessionToolsPanel · 分类开关', () => {
  it('点开关只调 setCategoryEnabled，不打开二级', async () => {
    await mount()
    const sw = container.querySelector('.l1-item .tc-sw') as HTMLElement
    await act(async () => { sw.click() })
    await act(async () => { await Promise.resolve() })

    expect(setCategoryEnabled).toHaveBeenCalledTimes(1)
    expect(setCategoryEnabled.mock.calls[0].slice(0, 3)).toEqual(['sess-1', 'CU', true])
    // ⛔ 关键：开关不得展开二级
    expect(container.querySelector('.tools-panel'), '点开关不应展开明细').toBeNull()
  })

  it('半开组点开关 → 全部启用', async () => {
    await mount()
    const items = container.querySelectorAll('.l1-item')
    const sw = items[5].querySelector('.tc-sw') as HTMLElement
    await act(async () => { sw.click() })
    await act(async () => { await Promise.resolve() })
    expect(setCategoryEnabled.mock.calls[0].slice(0, 3)).toEqual(['sess-1', 'SYS', true])
  })

  it('浏览器组 0 项时开关不可点（不发请求）', async () => {
    await mount()
    const items = container.querySelectorAll('.l1-item')
    const sw = items[2].querySelector('.tc-sw') as HTMLElement
    expect(sw.className).toContain('empty')
    await act(async () => { sw.click() })
    await act(async () => { await Promise.resolve() })
    expect(setCategoryEnabled).not.toHaveBeenCalled()
  })
})

describe('SessionToolsPanel · MCP 三级开关（总类 → server → 单工具）', () => {
  /** 按一级标签找行（⛔ 不写死下标：MCP 插入后下标会漂） */
  const l1ByLabel = (label: string) =>
    Array.from(container.querySelectorAll('.l1-item'))
      .find((el) => el.querySelector('.l1-name')?.textContent === label) as HTMLElement
  /** 二级分组头（`.tools-panel-list > div > .tool-row`） */
  const serverHeaders = () =>
    Array.from(container.querySelectorAll('.tools-panel-list > .tool-row')) as HTMLElement[]
  const headerOf = (server: string) =>
    serverHeaders().find((h) => h.querySelector('.tool-name')?.textContent === server)!
  /** 当前列表面板里出现的工具名（第二层只有 server 行，第三层才是工具）。 */
  const listKeyTexts = () =>
    Array.from(container.querySelectorAll('.tools-panel-list .tool-key'))
      .map((n) => n.textContent)

  it('⭐ 展开 MCP → 只列 server（第二层），点 server 进第三层才看到工具', async () => {
    await mount()
    await act(async () => { l1ByLabel('MCP 工具').click() })

    const names = serverHeaders().map((h) => h.querySelector('.tool-name')?.textContent)
    expect(names).toEqual(['other', 'rea'])
    expect(headerOf('rea').querySelector('.tool-key')?.textContent).toBe('1 / 2 已启用')
    // 第二层不铺工具行：一个 server 可能带 255 个工具，不能一口气全渲染
    expect(listKeyTexts()).toEqual(['1 / 1 已启用', '1 / 2 已启用'])

    await act(async () => { headerOf('rea').click() })
    expect(listKeyTexts()).toContain('mcp__rea__inspect_plist')
    expect(listKeyTexts()).not.toContain('mcp__other__do_thing')
    expect(container.querySelector('.crumb-cur')?.textContent).toBe('rea')
  })

  it('⭐ server 开关 → PATCH {category:MCP, mcpServer:rea}（只关这一个 server）', async () => {
    await mount()
    await act(async () => { l1ByLabel('MCP 工具').click() })

    const sw = headerOf('rea').querySelector('.tc-sw') as HTMLElement
    expect(sw.className).toContain('mixed')
    await act(async () => { sw.click() })
    await act(async () => { await Promise.resolve() })

    expect(setCategoryEnabled).toHaveBeenCalledTimes(1)
    expect(setCategoryEnabled.mock.calls[0]).toEqual(['sess-1', 'MCP', true, 'rea'])
    // ⛔ server 开关不得进入第三层（只切开关，不改视图）
    expect(container.querySelector('.crumb-cur')).toBeNull()
  })

  it('一级 MCP 开关 → PATCH {category:MCP}（不带 mcpServer = 整个 MCP 类）', async () => {
    await mount()
    const sw = l1ByLabel('MCP 工具').querySelector('.tc-sw') as HTMLElement
    await act(async () => { sw.click() })
    await act(async () => { await Promise.resolve() })

    expect(setCategoryEnabled).toHaveBeenCalledTimes(1)
    expect(setCategoryEnabled.mock.calls[0].slice(0, 3)).toEqual(['sess-1', 'MCP', true])
  })

  it('第三层面包屑返回 server 列表（不再是折叠语义）', async () => {
    await mount()
    await act(async () => { l1ByLabel('MCP 工具').click() })
    await act(async () => { headerOf('rea').click() })
    expect(container.querySelector('.crumb-cur')?.textContent).toBe('rea')

    await act(async () => { (container.querySelector('.crumb-root') as HTMLElement).click() })
    expect(container.querySelector('.crumb-cur')).toBeNull()
    expect(serverHeaders().map((h) => h.querySelector('.tool-name')?.textContent))
      .toEqual(['other', 'rea'])
  })
})

describe('SessionToolsPanel · 单工具开关（目标状态语义）', () => {
  it('⭐ 点「禁用」发 enabled=false、点「启用」发 enabled=true', async () => {
    await mount()
    const items = container.querySelectorAll('.l1-item')
    await act(async () => { (items[5] as HTMLElement).click() })

    const rowOf = (name: string) =>
      Array.from(container.querySelectorAll('.tool-row'))
        .find((r) => r.querySelector('.tool-key')?.textContent === name)!

    // 当前可用（disabled=false）→ 按钮「禁用」→ 目标 enabled=false
    setToolEnabled.mockResolvedValue({ ...FIXTURE.find((f) => f.name === 'Bash')!, disabled: true })
    await act(async () => { (rowOf('Bash').querySelector('.toggle-btn') as HTMLButtonElement).click() })
    await act(async () => { await Promise.resolve() })
    expect(setToolEnabled.mock.calls[0].slice(0, 3)).toEqual(['sess-1', 'Bash', false])

    // 当前已禁用（disabled=true）→ 按钮「启用」→ 目标 enabled=true
    setToolEnabled.mockResolvedValue({ ...FIXTURE.find((f) => f.name === 'Read')!, disabled: false })
    await act(async () => { (rowOf('Read').querySelector('.toggle-btn') as HTMLButtonElement).click() })
    await act(async () => { await Promise.resolve() })
    expect(setToolEnabled.mock.calls[1].slice(0, 3)).toEqual(['sess-1', 'Read', true])
  })
})

describe('SessionToolsPanel · 核心工具保护', () => {
  it('Agent 的禁用按钮是 disabled（后端会 400）', async () => {
    await mount()
    const items = container.querySelectorAll('.l1-item')
    await act(async () => { (items[5] as HTMLElement).click() })
    const rows = Array.from(container.querySelectorAll('.tool-row'))
    const agentRow = rows.find((r) => r.querySelector('.tool-key')?.textContent === 'Agent')
    expect(agentRow, '应能找到 Agent 行').toBeTruthy()
    const btn = agentRow!.querySelector('.toggle-btn') as HTMLButtonElement
    expect(btn.disabled).toBe(true)
    expect(btn.getAttribute('title')).toBe('核心编排工具不可禁用')
  })
})
