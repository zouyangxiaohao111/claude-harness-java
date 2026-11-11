// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { createElement } from 'react'
import { MarkdownTextV2 } from '../renderer'

/**
 * 代码块结构 · 照抄源站（2026-11-11 真机缺陷回归）。
 *
 * <h2>这次修的真机缺陷</h2>
 * V2 初版用 marked 默认 renderer ⇒ 输出裸 `<pre><code>`：
 * - 没有 `.chat-code-block` 容器 ⇒ 源站那套 CSS 选择器全部不匹配 ⇒ **代码块纯白**
 * - 没有 `.chat-code-block__pre{overflow-x:auto}` ⇒ **长代码撑破宽度**
 *
 * 判据不是「有样式」，而是「DNS 结构对得上 CSS 选择器」。
 */
function html(md: string, onRunHtml?: (c: string) => void): string {
  return renderToStaticMarkup(createElement(MarkdownTextV2, { text: md, onRunHtml }))
}

describe('代码块结构（照抄源站 chat-code-block）', () => {
  it('⭐ 有 .chat-code-block 容器（CSS 的挂载点，缺它就是纯白）', () => {
    expect(html('```java\nint a = 1;\n```')).toContain('chat-code-block')
  })

  it('⭐ 有 __pre + overflow 容器类（长代码横向滚动）', () => {
    expect(html('```java\nint a = 1;\n```')).toContain('chat-code-block__pre')
  })

  it('头部含语言徽标 / 复制按钮 / 复制图标（源站三件）', () => {
    const h = html('```java\nint a = 1;\n```')
    expect(h).toContain('chat-code-block__header')
    expect(h).toContain('chat-code-block__lang')
    expect(h).toContain('chat-code-block__copy')
    expect(h).toContain('chat-code-block__copy-icon')
    expect(h).toContain('Java')
  })

  it('⭐ code 带 hljs + language-x（源站类名约定）', () => {
    const h = html('```java\nint a = 1;\n```')
    expect(h).toMatch(/class="hljs language-java"/)
  })

  it('⭐ 真的高亮了（有 hljs- 着色 span）', () => {
    const h = html('```java\nint a = 1;\n```')
    expect(h).toMatch(/class="hljs-/)
  })

  it('未知语言也高亮（highlightAuto 兜底，不掉回纯白）', () => {
    const h = html('```\nSELECT 1;\n```')
    expect(h).toContain('chat-code-block')
    expect(h).toMatch(/class="hljs/)
  })

  it('本仓特有：html 代码块有「运行」按钮（源站没有）', () => {
    const h = html('```html\n<b>x</b>\n```', () => {})
    expect(h).toContain('chat-code-block__run')
    expect(h).toContain('data-html-run')
  })

  it('非 html 语言没有「运行」按钮', () => {
    const h = html('```java\nint a=1;\n```', () => {})
    expect(h).not.toContain('chat-code-block__run')
  })
})
