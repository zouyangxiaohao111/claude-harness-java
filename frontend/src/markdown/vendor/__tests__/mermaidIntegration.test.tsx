// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { createElement } from 'react'
import { MarkdownTextV2 } from '../renderer'
import { extractMermaidCodes, hasCompleteMermaidBlock, replaceIncompleteMermaidWithPlaceholder } from '../mermaidStreaming'

/**
 * mermaid 接入 · 两段式机制验证。
 *
 * <h2>判据不是「能画图」（那要真浏览器 + 异步），而是「两段式的第一段对不对」</h2>
 * 1. `mermaid` 代码块必须产出 **.chat-mermaid-block 占位结构**（不是普通代码块）
 * 2. 占位块里必须是 **源码**（用户至少看得到内容，不是空白）
 * 3. `data-mermaid="false"` 标记必须在（异步渲染器靠它找目标）
 * 4. 流式工具函数（P3 移植的）能正确识别完整/未闭合块
 */
function html(md: string, streaming = false): string {
  return renderToStaticMarkup(createElement(MarkdownTextV2, { text: md, streaming }))
}

const MERMAID_MD = '```mermaid\ngraph TD\n  A[开始] --> B[结束]\n```'

describe('mermaid · 第一段（同步占位）', () => {
  it('⭐ mermaid 块产出 .chat-mermaid-block（不是 chat-code-block）', () => {
    const h = html(MERMAID_MD)
    expect(h).toContain('chat-mermaid-block')
    expect(h).not.toContain('chat-code-block')
  })

  it('⭐ 占位块带 data-mermaid="false"（异步渲染器的定位标记）', () => {
    expect(html(MERMAID_MD)).toContain('data-mermaid="false"')
  })

  it('⭐ 首屏显示源码而不是空白（渲染失败也能看内容）', () => {
    const h = html(MERMAID_MD)
    expect(h).toContain('graph TD')
    expect(h).toContain('--&gt;')  // > 被转义
  })

  it('有徽标和放大按钮（源站 __header 两件）', () => {
    const h = html(MERMAID_MD)
    expect(h).toContain('chat-mermaid-block__badge')
    expect(h).toContain('chat-mermaid-block__expand')
  })

  it('普通代码块不受影响（仍是 chat-code-block）', () => {
    const h = html('```java\nint a=1;\n```')
    expect(h).toContain('chat-code-block')
    expect(h).not.toContain('chat-mermaid-block')
  })
})

describe('mermaid · 流式守卫（P3 移植的函数）', () => {
  it('完整块能被识别', () => {
    expect(hasCompleteMermaidBlock(MERMAID_MD)).toBe(true)
  })

  it('未闭合块被识别为不完整', () => {
    expect(hasCompleteMermaidBlock('```mermaid\ngraph TD\n  A-->B')).toBe(false)
  })

  it('能提取 mermaid 源码', () => {
    const codes = extractMermaidCodes(MERMAID_MD)
    expect(codes).toHaveLength(1)
    expect(codes[0]).toContain('graph TD')
  })

  it('未闭合块被替换成占位（占位前的正文保留）', () => {
    // ⚠️ 该函数只在「未闭合 mermaid 块位于文本末尾」的流式场景使用
    //    （源站同款实现：未闭合块必然在尾部，后面不可能还有正文）。
    //    所以判据是「未闭合块被替换 + 它前面的正文保留」。
    const out = replaceIncompleteMermaidWithPlaceholder(
      '前面的正文\n\n```mermaid\ngraph TD', '[图表渲染中]')
    expect(out).toContain('前面的正文')
    expect(out).toContain('[图表渲染中]')
    expect(out).not.toContain('graph TD')
  })
})
