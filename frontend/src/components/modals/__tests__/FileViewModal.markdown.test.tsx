// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { FileViewModal } from '../FileViewModal'
import { projectApi } from '@/api/projects'

/**
 * [2026-11-02 用户实测「项目下面的 md 系列文档没有预览」]
 *
 * <b>WHY（规则九）</b>：本组件原先把**所有**文本文件都丢进 Monaco —— 连只读态也是代码视图。
 * md 是项目里最常看的文档格式，打开满屏 `#` / `**` 源码等于没有预览能力。
 * 现对齐产物侧的三态（预览 / 源码 / 编辑），**默认预览**（渲染后的 Markdown）。
 *
 * <p>本条钉住的是「默认进来就能看到渲染结果」这个意图 —— 只断言「渲染了」很容易，
 * 关键是断言「**没有**落在源码视图」，否则把默认值改回 source 也能通过。
 */
vi.mock('@/api/projects', () => ({
  projectApi: { file: vi.fn(), write: vi.fn() },
}))
vi.mock('@/utils/monaco', () => ({
  monaco: {
    editor: {
      create: vi.fn(() => ({ getValue: () => '', setValue: vi.fn(), updateOptions: vi.fn(), dispose: vi.fn() })),
    },
  },
  monacoLangOf: () => 'markdown',
  MONACO_COMMON_OPTS: {},
}))
vi.mock('@/utils/standalonePreview', () => ({ openStandalone: vi.fn() }))

const MD = '# 标题一\n\n这是一段**加粗**正文。\n\n- 列表项 A\n- 列表项 B\n'

let container: HTMLDivElement
let root: Root

function render(path: string) {
  root = createRoot(container)
  act(() => {
    root.render(<FileViewModal projectId="p1" path={path} close={() => {}} />)
  })
}

async function flush() {
  await act(async () => { await Promise.resolve(); await Promise.resolve() })
}

describe('[2026-11-02] 项目文件 Markdown 默认预览', () => {
  beforeEach(() => {
    (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    vi.mocked(projectApi.file).mockResolvedValue({ content: MD, size: MD.length } as never)
    container = document.createElement('div')
    document.body.appendChild(container)
  })

  afterEach(() => {
    act(() => { root?.unmount() })
    container.remove()
    vi.clearAllMocks()
  })

  it('⭐ .md 打开即渲染（不是源码视图），且提供「预览 / 源码」切换', async () => {
    render('docs/说明.md')
    await flush()

    // 渲染结果：标题变成 h1，加粗变成 strong，列表变成 li
    expect(container.querySelector('h1'), 'Markdown 标题必须被渲染成 h1').not.toBeNull()
    expect(container.querySelector('strong'), '加粗必须被渲染').not.toBeNull()
    expect(container.querySelectorAll('li').length, '列表项必须被渲染').toBe(2)

    // ⛔ 关键：默认**不在** Monaco 源码视图（否则「有预览」是假的）
    expect(container.querySelector('.monaco-box'), '默认不应挂载 Monaco').toBeNull()

    // 三态切换存在
    const modes = Array.from(container.querySelectorAll('.artifact-mode-btn')).map((b) => b.textContent)
    expect(modes).toEqual(['预览', '源码'])
  })

  it('.md 切到「源码」→ 挂载 Monaco；切回「预览」→ 卸载', async () => {
    render('README.md')
    await flush()

    const sourceBtn = Array.from(container.querySelectorAll<HTMLButtonElement>('.artifact-mode-btn'))
      .find((b) => (b.textContent ?? '').includes('源码'))!
    act(() => { sourceBtn.click() })
    await flush()
    expect(container.querySelector('.monaco-box'), '源码态应挂载 Monaco').not.toBeNull()
    expect(container.querySelector('h1'), '源码态不应渲染 Markdown').toBeNull()

    const previewBtn = Array.from(container.querySelectorAll<HTMLButtonElement>('.artifact-mode-btn'))
      .find((b) => (b.textContent ?? '').includes('预览'))!
    act(() => { previewBtn.click() })
    await flush()
    expect(container.querySelector('h1'), '切回预览应重新渲染').not.toBeNull()
  })

  it('非 Markdown 文件（.ts）不显示三态切换 —— 没有「渲染后」形态可言', async () => {
    vi.mocked(projectApi.file).mockResolvedValue({ content: 'export const a = 1', size: 17 } as never)
    render('src/a.ts')
    await flush()

    expect(container.querySelectorAll('.artifact-mode-btn').length, '不应出现 Markdown 三态').toBe(0)
    expect(container.querySelector('.monaco-box'), '代码文件仍走 Monaco').not.toBeNull()
  })
})
