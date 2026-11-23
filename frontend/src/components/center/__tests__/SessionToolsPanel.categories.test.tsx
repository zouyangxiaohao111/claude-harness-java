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

// ApiError 只需 instanceof 判断，用轻量替身；api 供「浏览器扩展连接状态」查询
const browserStatus = vi.fn()
vi.mock('@/api/rest', () => ({
  ApiError: class ApiError extends Error {
    userMessage() { return this.message }
  },
  api: (...a: unknown[]) => browserStatus(...a),
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
  browserStatus.mockReset().mockResolvedValue({ connected: true })
  listTools.mockReset().mockResolvedValue(FIXTURE)
  setToolEnabled.mockReset()
  setCategoryEnabled.mockReset().mockResolvedValue(FIXTURE)
})

afterEach(() => {
  act(() => root.unmount())
  container.remove()
})

describe('SessionToolsPanel · 分类分组', () => {
  it('列 CU→KB→BR→IM→SYS（只有 MCP 移出到应用层）', async () => {
    await mount()
    const names = Array.from(container.querySelectorAll('.l1-name'))
      .map((n) => n.textContent)
      .filter((n) => n !== '应用层历史禁用')   // 历史恢复行不是分类
    expect(names).toEqual(['电脑操作', '知识库', '浏览器控制', 'IM 工具', '系统工具'])

    const subs = Array.from(container.querySelectorAll('.l1-sub'))
      .slice(0, 5)
      .map((n) => n.textContent)
    expect(subs[0]).toBe('2 个 · 0 个已启用')   // CU 全部禁用
    expect(subs[1]).toBe('1 个 · 0 个已启用')   // KB 全部禁用
    // BR 空组：按 /browser/status 真值说「扩展已连接 · 工具未开启」（不再写死「需安装」）
    expect(subs[2]).toBe('扩展已连接 · 浏览器工具未开启')
    expect(subs[3]).toBe('2 个 · 1 个已启用')   // IM 半开（自建通讯工具独立成组）
    expect(subs[4]).toBe('3 个 · 2 个已启用')   // SYS 半开
    // ⛔ 只有 MCP 被移出
    expect(names).not.toContain('MCP 工具')
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
    expect(container.querySelectorAll('.l1-item').length).toBe(6) // 5 类 + 历史禁用恢复行
  })

  it('切换到另一类后二级内容随之变化', async () => {
    await mount()
    const items = container.querySelectorAll('.l1-item')
    await act(async () => { (items[0] as HTMLElement).click() })
    expect(container.querySelector('.tools-panel-title')?.textContent).toContain('电脑操作')
    await act(async () => { (items[4] as HTMLElement).click() })
    expect(container.querySelector('.tools-panel-title')?.textContent).toContain('系统工具')
  })
})

describe('SessionToolsPanel · 空组状态（按真实能力/连接状态，不写死）', () => {
  const openCat = async (label: string) => {
    const row = Array.from(container.querySelectorAll('.l1-item'))
      .find((el) => el.querySelector('.l1-name')?.textContent === label) as HTMLElement
    await act(async () => { row.click() })
  }

  it('浏览器扩展未连接 ⇒ 「未连接浏览器扩展」', async () => {
    browserStatus.mockResolvedValue({ connected: false })
    await mount({ onOpenChromePanel: () => {} })
    const br = Array.from(container.querySelectorAll('.l1-item'))
      .find((el) => el.querySelector('.l1-name')?.textContent === '浏览器控制') as HTMLElement
    expect(br.querySelector('.l1-sub')?.textContent).toBe('未连接浏览器扩展')
    await openCat('浏览器控制')
    expect(container.textContent).toContain('未连接浏览器扩展，模型看不到这组工具')
    // 仍然保留检查/安装入口
    expect(container.querySelector('.tools-panel-retry')).not.toBeNull()
  })

  it('浏览器扩展已连接但工具未开启 ⇒ 说明是能力关，不是没装', async () => {
    browserStatus.mockResolvedValue({ connected: true })
    await mount()
    await openCat('浏览器控制')
    expect(container.textContent).toContain('浏览器扩展已连接，但浏览器工具未开启')
  })

  it('状态查不到 ⇒ 「状态未知」而不是断言没装', async () => {
    browserStatus.mockRejectedValue(new Error('boom'))
    await mount()
    const br = Array.from(container.querySelectorAll('.l1-item'))
      .find((el) => el.querySelector('.l1-name')?.textContent === '浏览器控制') as HTMLElement
    expect(br.querySelector('.l1-sub')?.textContent).toBe('浏览器扩展状态未知')
  })

  it('电脑操作 0 工具 ⇒ 「功能未开启」（不是 0 个）', async () => {
    listTools.mockResolvedValue([t('Bash', 'SYS', false, 'Bash（终端）')])
    await mount()
    const cu = Array.from(container.querySelectorAll('.l1-item'))
      .find((el) => el.querySelector('.l1-name')?.textContent === '电脑操作') as HTMLElement
    expect(cu.querySelector('.l1-sub')?.textContent).toBe('功能未开启')
    await openCat('电脑操作')
    expect(container.textContent).toContain('computer-use 能力默认关闭')
  })
})

describe('SessionToolsPanel · 分类开关', () => {
  /** 按一级标签找行（只有 MCP 移出应用层） */
  const l1ByLabel = (label: string) =>
    Array.from(container.querySelectorAll('.l1-item'))
      .find((el) => el.querySelector('.l1-name')?.textContent === label) as HTMLElement

  it('点开关只调 setCategoryEnabled，不打开二级', async () => {
    await mount()
    const sw = l1ByLabel('系统工具').querySelector('.tc-sw') as HTMLElement
    await act(async () => { sw.click() })
    await act(async () => { await Promise.resolve() })

    expect(setCategoryEnabled).toHaveBeenCalledTimes(1)
    expect(setCategoryEnabled.mock.calls[0].slice(0, 3)).toEqual(['sess-1', 'SYS', true])
    // ⛔ 关键：开关不得展开二级
    expect(container.querySelector('.tools-panel'), '点开关不应展开明细').toBeNull()
  })

  it('半开组点开关 → 全部启用', async () => {
    await mount()
    const items = container.querySelectorAll('.l1-item')
    const sw = items[4].querySelector('.tc-sw') as HTMLElement
    await act(async () => { sw.click() })
    await act(async () => { await Promise.resolve() })
    expect(setCategoryEnabled.mock.calls[0].slice(0, 3)).toEqual(['sess-1', 'SYS', true])
  })

  it('CU / KB / BR / IM / SYS 的开关都在（只有 MCP 被移走）', async () => {
    await mount()
    for (const label of ['电脑操作', '知识库', '浏览器控制', 'IM 工具', '系统工具']) {
      expect(l1ByLabel(label).querySelector('.tc-sw'), `${label} 开关丢了`).not.toBeNull()
    }
  })
})

describe('SessionToolsPanel · 应用层分类已移出（历史禁用只恢复）', () => {
  it('⭐ MCP 不出现在分类列表（在输入框「应用」里开关）；CU/BR 回来了', async () => {
    await mount()
    const names = Array.from(container.querySelectorAll('.l1-name')).map((n) => n.textContent)
    expect(names).not.toContain('MCP 工具')
    for (const back of ['浏览器控制', '电脑操作']) {
      expect(names, `${back} 应回到工具面板`).toContain(back)
    }
    // 没有二级面板自动展开（MCP 无处可进）
    expect(container.querySelectorAll('.tools-panel').length).toBe(0)
  })

  it('⭐ 历史会话级禁用 ⇒ 出现「应用层历史禁用」一项，点击只做恢复', async () => {
    await mount()
    // FIXTURE：只有 MCP 有 1 项会话级禁用（CU/BR 已回面板，不算「应用层历史」）
    const row = container.querySelector('[data-testid="app-managed-history"]') as HTMLElement | null
    expect(row, '缺少历史禁用恢复入口').toBeTruthy()
    expect(row!.textContent).toContain('应用层历史禁用')
    expect(row!.textContent).toContain('1 项')

    await act(async () => { row!.click() })
    await act(async () => { await Promise.resolve() })

    // 只对确实有禁用行的分类发「整类启用」；⛔ 不提供禁用路径
    const calls = setCategoryEnabled.mock.calls.map((c) => c.slice(0, 3))
    expect(calls).toEqual([['sess-1', 'MCP', true]])
  })

  it('没有历史禁用 ⇒ 不显示恢复入口（不制造无用按钮）', async () => {
    listTools.mockResolvedValue([
      t('mcp__rea__inspect_plist', 'MCP', false, '读取 plist', 'rea'),
      t('mcp__other__do_thing', 'MCP', false, '其它工具', 'other'),
      t('Bash', 'SYS', false, 'Bash（终端）'),
    ])
    await mount()
    expect(container.querySelector('[data-testid="app-managed-history"]')).toBeNull()
  })
})

describe('SessionToolsPanel · 单工具开关（目标状态语义）', () => {
  it('⭐ 点「禁用」发 enabled=false、点「启用」发 enabled=true', async () => {
    await mount()
    const items = container.querySelectorAll('.l1-item')
    await act(async () => { (items[4] as HTMLElement).click() })

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
    await act(async () => { (items[4] as HTMLElement).click() })
    const rows = Array.from(container.querySelectorAll('.tool-row'))
    const agentRow = rows.find((r) => r.querySelector('.tool-key')?.textContent === 'Agent')
    expect(agentRow, '应能找到 Agent 行').toBeTruthy()
    const btn = agentRow!.querySelector('.toggle-btn') as HTMLButtonElement
    expect(btn.disabled).toBe(true)
    expect(btn.getAttribute('title')).toBe('核心编排工具不可禁用')
  })
})
