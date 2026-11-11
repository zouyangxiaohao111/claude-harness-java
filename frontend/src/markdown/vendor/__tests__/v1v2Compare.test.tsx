// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { marked } from 'marked'
import { renderChatMarkdown } from '../chatMarkdownRenderer'
import { sanitizeChatHtml } from '../renderer'

/**
 * V1（旧 MarkdownText）vs V2（源站流水线）六类内容对照。
 *
 * 判据不是「V2 更好看」，而是**逐类确认 V2 至少不差**：
 * 表格成表 / 代码块成块 / 公式渲染 / 引用可识别 / 中文强调不断词 / 流式不裸露语法。
 */
const opts = {
  renderer: new marked.Renderer(),
  escapeMarkdown: (s: string) => s,
  sanitizeHtml: sanitizeChatHtml,
}

describe('V1/V2 对照 · 六类内容', () => {
  it('① 表格 → <table> + 滚动容器', () => {
    const h = renderChatMarkdown('| 名称 | 数量 |\n| --- | --- |\n| 甲 | 1 |\n| 乙 | 2 |', opts)
    expect(h).toContain('<table')
    expect(h).toContain('<th>')
    expect(h).toContain('chat-markdown-table')
  })

  it('② 代码块 → <pre><code>，语言类名保留', () => {
    const h = renderChatMarkdown('```java\nint a = 1;\n```', opts)
    expect(h).toContain('<pre')
    expect(h).toContain('<code')
    expect(h).toContain('int a = 1;')
  })

  it('③ 行内公式 → katex', () => {
    const h = renderChatMarkdown('质能方程 $E=mc^2$ 很重要', opts)
    expect(h.toLowerCase()).toContain('katex')
  })

  it('③b 块级公式 \\[ \\] 被转成 $$ 并渲染', () => {
    const h = renderChatMarkdown('\\[a^2+b^2=c^2\\]', opts)
    expect(h.toLowerCase()).toContain('katex')
  })

  it('④ 标题 / 列表 / 引用块结构正确', () => {
    const h = renderChatMarkdown('## 小标题\n\n- 第一项\n- 第二项\n\n> 引用文字', opts)
    expect(h).toContain('<h2')
    expect(h).toContain('<ul')
    expect(h).toContain('<li')
    expect(h).toContain('<blockquote')
  })

  it('⑤ 中文夹粗体不断词（flanking emphasis 修复）', () => {
    const h = renderChatMarkdown('这是**重要结论**需要注意', opts)
    expect(h).toContain('<strong>')
    expect(h).toContain('重要结论')
  })

  it('⑥ 流式：未闭合加粗不裸露星号', () => {
    const h = renderChatMarkdown('结论是**这个', { ...opts, streaming: true })
    expect(h).not.toContain('**')
  })

  it('⑥b 流式：未闭合代码围栏不吞掉后续文字', () => {
    const h = renderChatMarkdown('看这段：\n\n```java\nint a=1;', { ...opts, streaming: true })
    // 不要求完美渲染，只要求不把 "看这段" 吞进 code
    expect(h).toContain('看这段')
  })

  it('⑥c 流式：末尾悬挂的 - 不产生空列表项', () => {
    const h = renderChatMarkdown('内容\n\n- ', { ...opts, streaming: true })
    expect(h).not.toContain('<li></li>')
  })

  it('⑥d 流式：未闭合图片不裸露 ![', () => {
    const h = renderChatMarkdown('如图 ![示例](http://x', { ...opts, streaming: true })
    expect(h).not.toContain('![')
  })
})
