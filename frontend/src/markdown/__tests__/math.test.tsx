// @vitest-environment jsdom
// 数学渲染（KaTeX renderToString + DOMParser→React）需要 DOM；其余 md 测试走 node + SSR。
import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { MarkdownText } from '../MarkdownText.tsx'

const noop = () => {}
function render(text: string, streaming = false) {
  return renderToStaticMarkup(<MarkdownText text={text} streaming={streaming} onRunHtml={noop} />)
}

// ⚠ [stream-raw] 2026-09-18：本 describe 的接线测试只覆盖**收口臂**（settled → KaTeX）；
//   streaming 那条断言仍然为真但语义变空 —— 流式臂现在整体为原文纯文本直出（不解析 markdown、
//   自然也谈不上「无 math 语法」），已由 `streamRaw.test.tsx` 的逐字符契约覆盖。断言本身不改。
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
