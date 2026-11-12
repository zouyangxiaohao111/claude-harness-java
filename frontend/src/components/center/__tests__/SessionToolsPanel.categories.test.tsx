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
): SessionToolDto {
  return { name, userFacingName, category, description: `${name} description`, disabled }
}

const FIXTURE: SessionToolDto[] = [
  // CU：默认禁用
  t('mcp__computer-use__screenshot', 'CU', true, '截取屏幕'),
  t('mcp__computer-use__key', 'CU', true, '按键'),
  // KB：默认禁用
  t('search_knowledge', 'KB', true, '检索知识库'),
  // BR：0 项（未装扩展）
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
  it('4 类按 CU→KB→BR→SYS 顺序渲染，并显示 N 个 · M 个已启用', async () => {
    await mount()
    const names = Array.from(container.querySelectorAll('.l1-name')).map((n) => n.textContent)
    expect(names).toEqual(['电脑操作', '知识库', '浏览器控制', '系统工具'])

    const subs = Array.from(container.querySelectorAll('.l1-sub')).map((n) => n.textContent)
    expect(subs[0]).toBe('2 个 · 0 个已启用')   // CU 全部禁用
    expect(subs[1]).toBe('1 个 · 0 个已启用')   // KB 全部禁用
    expect(subs[2]).toBe('需安装 Chrome 扩展')  // BR 空组
    expect(subs[3]).toBe('3 个 · 2 个已启用')   // SYS 半开
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
    expect(container.querySelectorAll('.l1-item').length).toBe(4)
  })

  it('切换到另一类后二级内容随之变化', async () => {
    await mount()
    const items = container.querySelectorAll('.l1-item')
    await act(async () => { (items[0] as HTMLElement).click() })
    expect(container.querySelector('.tools-panel-title')?.textContent).toContain('电脑操作')
    await act(async () => { (items[3] as HTMLElement).click() })
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
    const sw = items[3].querySelector('.tc-sw') as HTMLElement
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

describe('SessionToolsPanel · 核心工具保护', () => {
  it('Agent 的禁用按钮是 disabled（后端会 400）', async () => {
    await mount()
    const items = container.querySelectorAll('.l1-item')
    await act(async () => { (items[3] as HTMLElement).click() })
    const rows = Array.from(container.querySelectorAll('.tool-row'))
    const agentRow = rows.find((r) => r.querySelector('.tool-key')?.textContent === 'Agent')
    expect(agentRow, '应能找到 Agent 行').toBeTruthy()
    const btn = agentRow!.querySelector('.toggle-btn') as HTMLButtonElement
    expect(btn.disabled).toBe(true)
    expect(btn.getAttribute('title')).toBe('核心编排工具不可禁用')
  })
})
