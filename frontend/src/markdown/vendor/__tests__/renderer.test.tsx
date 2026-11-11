// @vitest-environment jsdom
// ⚠️ 必须 jsdom：dompurify 在无 DOM 环境下 `.sanitize` 不存在（返回纯文本兜底），
//    那样测不到真正的净化行为 —— 等于假绿。
import { describe, expect, it } from 'vitest'
import { sanitizeChatHtml } from '../renderer'

/**
 * 安全边界验证 · ⛔ 这条不过就是严重缺陷。
 * 流水线里 escapeMarkdown 是恒等函数，唯一防线就是这个 sanitize。
 */
describe('sanitizeChatHtml · XSS 防线', () => {
  it('script 标签被剥掉', () => {
    const out = sanitizeChatHtml('<p>正常</p><script>alert(1)</script>')
    expect(out).toContain('正常')
    expect(out.toLowerCase()).not.toContain('<script')
  })

  it('onerror 等事件属性被剥掉', () => {
    const out = sanitizeChatHtml('<img src=x onerror="alert(1)">')
    expect(out.toLowerCase()).not.toContain('onerror')
  })

  it('javascript: 协议链接被剥掉', () => {
    const out = sanitizeChatHtml('<a href="javascript:alert(1)">点我</a>')
    expect(out.toLowerCase()).not.toContain('javascript:')
  })

  it('iframe 被剥掉', () => {
    const out = sanitizeChatHtml('<iframe src="http://evil"></iframe>')
    expect(out.toLowerCase()).not.toContain('<iframe')
  })

  it('正常 markdown 产物不被破坏', () => {
    const out = sanitizeChatHtml(
      '<p>文字 <strong>加粗</strong> <code>x=1</code></p><table><tr><td>1</td></tr></table>',
    )
    expect(out).toContain('<strong>')
    expect(out).toContain('<table>')
  })
})
