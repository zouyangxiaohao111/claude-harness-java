// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, createElement } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MarkdownTextV2, LocalLinkProvider } from '../renderer'
import { isExternalLinkHref } from '@/utils/externalLink'
import { localFilePathOf } from '@/utils/localFileLink'

/** openLocalFile 是异步动态 import 的桌面壳通道；单测只验证「被正确调用」。 */
const openLocalFileMock = vi.hoisted(() => vi.fn(async () => true))
/** 相对路径解析 / 内部预览都是桌面壳通道，统一 mock。 */
const resolveRelativeMock = vi.hoisted(() => vi.fn(async (_rel: string, _dirs: readonly string[]): Promise<string | null> => null))
const openInternallyMock = vi.hoisted(() => vi.fn(async (_path: string): Promise<'opened' | 'too-large' | 'unsupported' | 'failed'> => 'opened'))
vi.mock('@/utils/localFileLink', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/utils/localFileLink')>()
  return {
    ...actual,
    openLocalFile: openLocalFileMock,
    resolveRelativeFilePath: resolveRelativeMock,
    openLocalFileInternally: openInternallyMock,
  }
})

/**
 * 对话正文外链的打开口径（用户 2026-11-18 真机缺陷回归）。
 *
 * <h2>这次修的真机缺陷</h2>
 * Tauri WebView 里点正文里的 URL ⇒ **当前 webview 整页导航**到外站：没有弹窗、
 * 没有地址栏、也没有后退键 ⇒ 用户回不到会话。
 *
 * 判据（不是「能点」）：
 * 1. 左键点外链必须 `preventDefault`（拦住 webview 导航）并交给系统浏览器；
 * 2. 右键外链必须弹客户端菜单，选「在客户端内打开」走新窗口（浏览器环境 = 新标签）；
 * 3. 站内 hash（引用 chip）不能被误拦。
 */

// 非 Tauri 环境（isTauri() === false）⇒ 两条通道都落到 window.open。
vi.mock('@tauri-apps/api/core', () => ({ isTauri: () => false }))

;(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true

let container: HTMLDivElement
let root: Root

beforeEach(() => {
  openLocalFileMock.mockClear()
  resolveRelativeMock.mockClear()
  resolveRelativeMock.mockResolvedValue(null)
  openInternallyMock.mockClear()
  openInternallyMock.mockResolvedValue('opened')
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
})

afterEach(() => {
  act(() => root.unmount())
  container.remove()
  vi.restoreAllMocks()
})

async function mount(text: string): Promise<void> {
  await act(async () => {
    root.render(createElement(MarkdownTextV2, { text }))
  })
}

/** 带相对路径解析上下文（baseDirs + 失败提示）挂载。 */
async function mountWithLinks(
  text: string,
  baseDirs: string[],
  onUnresolved?: (href: string, reason: string) => void,
): Promise<void> {
  await act(async () => {
    root.render(
      createElement(LocalLinkProvider, { value: { baseDirs, onUnresolved } },
        createElement(MarkdownTextV2, { text })),
    )
  })
}

/** 点击后等异步链跑完（resolve → 内部打开 至少两跳微任务）。 */
async function clickAndFlush(anchor: Element): Promise<MouseEvent> {
  const click = new MouseEvent('click', { bubbles: true, cancelable: true })
  await act(async () => {
    anchor.dispatchEvent(click)
    await Promise.resolve()
    await Promise.resolve()
    await Promise.resolve()
  })
  return click
}

describe('isExternalLinkHref', () => {
  it('http/https/mailto/tel 为外链', () => {
    expect(isExternalLinkHref('https://example.com/a')).toBe(true)
    expect(isExternalLinkHref('http://example.com')).toBe(true)
    expect(isExternalLinkHref('mailto:a@b.com')).toBe(true)
    expect(isExternalLinkHref('tel:+8613800000000')).toBe(true)
  })

  it('hash / 相对路径 / 自定义 scheme 不算外链', () => {
    expect(isExternalLinkHref('#kb-cite-1')).toBe(false)
    expect(isExternalLinkHref('/docs')).toBe(false)
    expect(isExternalLinkHref('sandbox:/x')).toBe(false)
    expect(isExternalLinkHref('')).toBe(false)
  })
})

describe('MarkdownTextV2 外链交互', () => {
  it('⭐ 左键点外链：preventDefault（拦住 webview 导航）+ 系统浏览器打开', async () => {
    const openSpy = vi.spyOn(window, 'open').mockImplementation(() => null)
    await mount('看 [文档](https://example.com/doc)')
    const anchor = container.querySelector('a')
    expect(anchor).not.toBeNull()

    const click = new MouseEvent('click', { bubbles: true, cancelable: true })
    await act(async () => {
      anchor!.dispatchEvent(click)
      // openLinkInBrowser 是 async（动态 import）⇒ flush 一次微任务
      await Promise.resolve()
    })

    expect(click.defaultPrevented).toBe(true)
    expect(openSpy).toHaveBeenCalledWith('https://example.com/doc', '_blank', 'noopener')
  })

  it('⭐ 裸 URL 自动识别为链接时同样走系统浏览器', async () => {
    const openSpy = vi.spyOn(window, 'open').mockImplementation(() => null)
    await mount('参考 https://example.com/raw 这篇')
    const anchor = container.querySelector('a')
    expect(anchor?.getAttribute('href')).toBe('https://example.com/raw')

    const click = new MouseEvent('click', { bubbles: true, cancelable: true })
    await act(async () => {
      anchor!.dispatchEvent(click)
      await Promise.resolve()
    })

    expect(click.defaultPrevented).toBe(true)
    expect(openSpy).toHaveBeenCalledWith('https://example.com/raw', '_blank', 'noopener')
  })

  it('⭐ 右键外链：弹菜单，「在客户端内打开」另起窗口', async () => {
    const openSpy = vi.spyOn(window, 'open').mockImplementation(() => null)
    await mount('[文档](https://example.com/x)')
    const anchor = container.querySelector('a')!

    const contextmenu = new MouseEvent('contextmenu', {
      bubbles: true, cancelable: true, clientX: 10, clientY: 10,
    })
    await act(async () => { anchor.dispatchEvent(contextmenu) })

    expect(contextmenu.defaultPrevented).toBe(true)
    const menu = document.querySelector('.external-link-menu')
    expect(menu?.textContent).toContain('在浏览器中打开')
    expect(menu?.textContent).toContain('在客户端内打开（新窗口）')

    const item = Array.from(menu!.querySelectorAll<HTMLElement>('.ctx-menu-item'))
      .find((el) => el.textContent?.includes('在客户端内打开'))
    expect(item).toBeDefined()

    await act(async () => {
      item!.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }))
      await Promise.resolve()
    })

    expect(openSpy).toHaveBeenCalledWith('https://example.com/x', '_blank', 'noopener')
    // 选完即关
    expect(document.querySelector('.external-link-menu')).toBeNull()
  })

  it('站内 hash 链接不拦（引用 chip 的原有交互不受影响）', async () => {
    const openSpy = vi.spyOn(window, 'open').mockImplementation(() => null)
    await mount('[▤ 文档](#kb-cite-1 "提示")')
    const anchor = container.querySelector('a')!
    expect(anchor.getAttribute('href')).toBe('#kb-cite-1')

    const click = new MouseEvent('click', { bubbles: true, cancelable: true })
    await act(async () => {
      anchor.dispatchEvent(click)
      await Promise.resolve()
    })

    expect(click.defaultPrevented).toBe(false)
    expect(openSpy).not.toHaveBeenCalled()
  })
})

/**
 * 用户 2026-11-26 真机缺陷：模型把产物写成 `file:///D:/...`，
 * DOMPurify 剥掉 href ⇒ 链接仍是蓝色、点下去什么也不发生。
 */
describe('MarkdownTextV2 本地文件链接', () => {
  it('localFilePathOf：file:// 与盘符路径解出本地路径，外链/相对路径返回 null', () => {
    expect(localFilePathOf('file:///D:/apps/a/x.html')).toBe('D:/apps/a/x.html')
    expect(localFilePathOf('file:///D:/a%20b/x.html')).toBe('D:/a b/x.html')
    expect(localFilePathOf('D:/apps/x.html')).toBe('D:/apps/x.html')
    expect(localFilePathOf('D:\\apps\\x.html')).toBe('D:\\apps\\x.html')
    expect(localFilePathOf('https://example.com/x')).toBeNull()
    expect(localFilePathOf('/docs/a.md')).toBeNull()
    expect(localFilePathOf('#kb-cite-1')).toBeNull()
  })

  it('⭐ file:// 链接：href 不再保留但路径进 data-local-file，点击交系统默认程序', async () => {
    await mount('[打开入口页](file:///D:/apps/NexusAI/data/x.html)')
    const anchor = container.querySelector('a')!
    // href 被净化器剥掉（这正是「看着可点、点不动」的根因）；真实路径改挂 data-*
    expect(anchor.getAttribute('href')).toBeNull()
    expect(anchor.getAttribute('data-local-file')).toBe('D:/apps/NexusAI/data/x.html')

    const click = new MouseEvent('click', { bubbles: true, cancelable: true })
    await act(async () => {
      anchor.dispatchEvent(click)
      await Promise.resolve()
    })

    expect(click.defaultPrevented).toBe(true)
    expect(openLocalFileMock).toHaveBeenCalledWith('D:/apps/NexusAI/data/x.html')
  })

  it('无协议盘符路径同样识别为本地文件', async () => {
    await mount('[主业务链图](D:/apps/NexusAI/data/x-main.html)')
    const anchor = container.querySelector('a')!
    expect(anchor.getAttribute('data-local-file')).toBe('D:/apps/NexusAI/data/x-main.html')
    expect(openLocalFileMock).not.toHaveBeenCalled()
  })
})

/**
 * 用户 2026-11-27 追问：相对路径能不能内部打开、右键能不能二选一。
 *
 * 裁定：绝对路径 → 系统默认程序；相对路径 → 客户端内预览（交付物目录 → 项目根探测）；
 * 两者右键都给「客户端内打开 / 浏览器中打开」。**任何非 http/hash 的目标都不许让 WebView 导航**
 * —— 否则整页跳到 `tauri.localhost/...`（用户描述的「打开也是 tauri.app 啥啥啥」）。
 */
describe('MarkdownTextV2 相对路径分发（2026-11-27）', () => {
  it('⭐ 相对路径命中基准目录 → 客户端内预览，且拦住 webview 导航', async () => {
    resolveRelativeMock.mockResolvedValue('D:/repo/deliverables/ops-x.html')
    await mountWithLinks('[打开](ops-x.html)', ['D:/repo/deliverables'])
    const anchor = container.querySelector('a')!
    const click = await clickAndFlush(anchor)

    expect(click.defaultPrevented).toBe(true)
    expect(resolveRelativeMock).toHaveBeenCalledWith('ops-x.html', ['D:/repo/deliverables'])
    expect(openInternallyMock).toHaveBeenCalledWith('D:/repo/deliverables/ops-x.html')
  })

  it('相对路径找不到 → 拦下不导航 + 提示 not-found', async () => {
    const onUnresolved = vi.fn()
    await mountWithLinks('[打开](missing.html)', ['D:/repo/deliverables'], onUnresolved)
    const anchor = container.querySelector('a')!
    const click = await clickAndFlush(anchor)

    expect(click.defaultPrevented).toBe(true)
    expect(onUnresolved).toHaveBeenCalledWith('missing.html', 'not-found')
    expect(openInternallyMock).not.toHaveBeenCalled()
  })

  it('自定义协议（sandbox:）被净化器剥掉 href ⇒ 点击绝不整页导航', async () => {
    const onUnresolved = vi.fn()
    await mountWithLinks('[打开](sandbox:/mnt/data/x.html)', ['D:/repo'], onUnresolved)
    const anchor = container.querySelector('a')!
    // DOMPurify 只放行 http/https/mailto/tel → sandbox: 的 href 整个被剥
    expect(anchor.getAttribute('href')).toBeNull()
    const click = await clickAndFlush(anchor)
    // 没有 href 本来就不会导航；关键是不能把 webview 带走
    expect(click.defaultPrevented).toBe(false)
    expect(onUnresolved).not.toHaveBeenCalled()
  })

  it('html 过大 → 回退系统默认程序（不丢能力）', async () => {
    resolveRelativeMock.mockResolvedValue('D:/repo/big.html')
    openInternallyMock.mockResolvedValue('too-large')
    await mountWithLinks('[打开](big.html)', ['D:/repo'])
    const anchor = container.querySelector('a')!
    await clickAndFlush(anchor)

    expect(openLocalFileMock).toHaveBeenCalledWith('D:/repo/big.html')
  })

  it('右键本地链接：菜单给「客户端内打开 / 浏览器中打开」两项', async () => {
    await mountWithLinks('[打开](ops-x.html)', ['D:/repo'])
    const anchor = container.querySelector('a')!
    await act(async () => {
      anchor.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true }))
    })
    const items = Array.from(document.querySelectorAll('.external-link-menu .ctx-menu-item'))
      .map((el) => el.textContent?.trim())
    expect(items).toEqual(['在客户端内打开', '在浏览器中打开'])
  })
})
