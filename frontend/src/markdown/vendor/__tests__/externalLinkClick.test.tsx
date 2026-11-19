// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, createElement } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MarkdownTextV2 } from '../renderer'
import { isExternalLinkHref } from '@/utils/externalLink'

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

describe('isExternalLinkHref', () => {
  it('http/https/mailto 为外链', () => {
    expect(isExternalLinkHref('https://example.com/a')).toBe(true)
    expect(isExternalLinkHref('http://example.com')).toBe(true)
    expect(isExternalLinkHref('mailto:a@b.com')).toBe(true)
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
