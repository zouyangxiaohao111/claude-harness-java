// @vitest-environment jsdom
// 数学渲染（KaTeX renderToString + DOMParser→React）需要 DOM；其余 md 测试走 node + SSR。
import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { MarkdownText } from '../MarkdownText.tsx'

const noop = () => {}
function render(text: string, streaming = false) {
  return renderToStaticMarkup(<MarkdownText text={text} streaming={streaming} onRunHtml={noop} />)
}

describe('KaTeX 数学（双语法：settled 出数学、streaming 保持字面）', () => {
  it('$$ 独立行 settled → katex-display', () => {
    const html = render('$$\nx^2\n$$')
    expect(html).toContain('katex-display')
  })
  it('$$ 独立行 streaming（无 math 语法）→ 保持字面、无 katex 崩溃', () => {
    const html = render('$$\nx^2\n$$', true)
    expect(html).not.toContain('katex')
    expect(html).toContain('$')
  })
})
