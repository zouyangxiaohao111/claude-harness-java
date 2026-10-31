// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { artifactOpenActionOf, artifactRawReachable, openArtifactRawInBrowser } from '@/utils/artifactOpen'

/**
 * 意图：
 * - raw 端点是**后端并行开发中**的契约，还没上线时返回 404 ⇒ 直接把用户丢到 404 页面毫无意义
 *   ⇒ 必须先探状态码，不通就返回 false 让调用方降级（复制路径 + 提示），⛔ 不白屏。
 * - 端点可用时：地址必须是**绝对**的（打包版 BASE_URL 已是绝对地址；dev 下相对路径要补 origin），
 *   且交给系统浏览器（Tauri openUrl / 浏览器 window.open）。
 */
describe('artifactRawReachable', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('200 ⇒ 可达', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response('x', { status: 200 }))
    await expect(artifactRawReachable('s1', 'a1')).resolves.toBe(true)
  })

  it('404 ⇒ 不可达（后端还没上线这个端点）', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response('not found', { status: 404 }))
    await expect(artifactRawReachable('s1', 'a1')).resolves.toBe(false)
  })

  it('网络异常 ⇒ 不可达（不抛给调用方崩掉 UI）', async () => {
    vi.mocked(fetch).mockRejectedValue(new Error('network down'))
    await expect(artifactRawReachable('s1', 'a1')).resolves.toBe(false)
  })
})

describe('openArtifactRawInBrowser', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
    vi.stubGlobal('open', vi.fn())
  })
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('端点可用 ⇒ 打开绝对地址（不传 onopener ⇒ 新标签不反向控制本页）', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response('x', { status: 200 }))
    const openSpy = vi.spyOn(window, 'open').mockImplementation(() => null)
    const ok = await openArtifactRawInBrowser('s 1', 'a 2')
    expect(ok).toBe(true)
    expect(openSpy).toHaveBeenCalledTimes(1)
    const url = openSpy.mock.calls[0][0] as string
    expect(url.startsWith('http')).toBe(true)
    expect(url).toContain('/sessions/s%201/artifacts/a%202/raw')
    expect(openSpy.mock.calls[0][1]).toBe('_blank')
    expect(openSpy.mock.calls[0][2]).toBe('noopener')
    openSpy.mockRestore()
  })

  it('端点 404 ⇒ 不打开浏览器，返回 false 让调用方降级（⛔ 不把用户丢到 404 页）', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response('not found', { status: 404 }))
    const openSpy = vi.spyOn(window, 'open').mockImplementation(() => null)
    const ok = await openArtifactRawInBrowser('s1', 'a1')
    expect(ok).toBe(false)
    expect(openSpy).not.toHaveBeenCalled()
    openSpy.mockRestore()
  })
})

describe('artifactOpenActionOf（kind → 打开方式 · 用户裁定的分发口径）', () => {
  it('html ⇒ 走我们现有的 html 预览（源码查看/编辑/保存/运行）', () => {
    expect(artifactOpenActionOf('a.html')).toEqual({ kind: 'view-html' })
    expect(artifactOpenActionOf('a.htm')).toEqual({ kind: 'view-html' })
  })

  it('docx/xlsx/pdf/图片 ⇒ 走**现有渲染**（独立预览窗）', () => {
    expect(artifactOpenActionOf('a.docx')).toEqual({ kind: 'standalone', previewType: 'docx' })
    expect(artifactOpenActionOf('a.xlsx')).toEqual({ kind: 'standalone', previewType: 'xlsx' })
    expect(artifactOpenActionOf('a.pdf')).toEqual({ kind: 'standalone', previewType: 'pdf' })
    expect(artifactOpenActionOf('a.png')).toEqual({ kind: 'standalone', previewType: 'image' })
  })

  it('md / 其它文本 ⇒ Markdown 预览 + 编辑保存', () => {
    expect(artifactOpenActionOf('a.md')).toEqual({ kind: 'preview-text' })
    expect(artifactOpenActionOf('a.json')).toEqual({ kind: 'preview-text' })
  })

  it('其它二进制（pptx/zip…）⇒ 提示暂不支持 + 复制路径（不假装能预览）', () => {
    expect(artifactOpenActionOf('a.pptx')).toEqual({ kind: 'unsupported' })
    expect(artifactOpenActionOf('a.zip')).toEqual({ kind: 'unsupported' })
  })
})
