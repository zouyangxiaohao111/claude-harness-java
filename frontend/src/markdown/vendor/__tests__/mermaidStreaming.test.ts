import { describe, expect, it } from 'vitest'

import {
  extractMermaidCodes,
  injectCachedMermaidSvg,
  maskMermaidBlocksForStreaming,
  prepareStreamingMermaidMarkdown,
} from '../mermaidStreaming'

/**
 * 意图：流式输出时 ```mermaid 代码块可能只到一半（有开头没结尾），markdown 会把
 * 后续所有正文吞进代码块。因此这里断言的是"未闭合块不得泄漏成代码块语法、
 * 且块外的正文必须原样保留"，而不只是某个占位符字符串出现了。
 */

const LOADING = `<div class="chat-mermaid-block chat-mermaid-block--loading">
  <div class="chat-mermaid-block__header"><span class="chat-mermaid-block__badge">图表</span></div>
  <div class="streaming-mermaid-loading" aria-hidden="true"><span class="streaming-mermaid-loading__skeleton"></span></div>
</div>`

function buildBlock(innerHtml: string, preAttrs = ''): string {
  const attrs = preAttrs ? ` ${preAttrs}` : ''
  return `<div class="chat-mermaid-block">
    <pre class="chat-mermaid-block__canvas mermaid"${attrs}>${innerHtml}</pre>
  </div>`
}

const TWO_DIAGRAMS = `Intro

\`\`\`mermaid
graph TD
    A --> B
\`\`\`

Middle

\`\`\`mermaid
sequenceDiagram
    A->>B: hi
\`\`\`

Done.
`

describe('mermaidStreaming', () => {
  it('extractMermaidCodes returns every complete mermaid fence in order', () => {
    expect(extractMermaidCodes(TWO_DIAGRAMS)).toEqual([
      'graph TD\n    A --> B',
      'sequenceDiagram\n    A->>B: hi',
    ])
  })

  it('maskMermaidBlocksForStreaming hides every complete mermaid fence', () => {
    const masked = maskMermaidBlocksForStreaming(TWO_DIAGRAMS, LOADING)
    expect(masked.includes('```mermaid')).toBe(false)
    expect([...masked.matchAll(/chat-mermaid-block--loading/g)].length).toBe(2)
    expect(masked).toMatch(/Intro/)
    expect(masked).toMatch(/Middle/)
    expect(masked).toMatch(/Done\./)
  })

  it('injectCachedMermaidSvg maps cached SVGs onto loading placeholders 1:1', () => {
    const html = `
    <p>Intro</p>
    ${LOADING}
    <p>Middle</p>
    ${LOADING}
    <p>Done.</p>
  `
    const injected = injectCachedMermaidSvg(
      html,
      ['<svg id="first"></svg>', '<svg id="second"></svg>'],
      buildBlock,
    )
    expect(injected).toMatch(/data-mermaid="cached"[^>]*>\s*<svg id="first">/)
    expect(injected).toMatch(/data-mermaid="cached"[^>]*>\s*<svg id="second">/)
    expect(injected.includes('streaming-mermaid-loading')).toBe(false)
    expect(injected).toMatch(/Intro/)
    expect(injected).toMatch(/Middle/)
    expect(injected).toMatch(/Done\./)
  })

  it('injectCachedMermaidSvg does not swallow a cached diagram when a later skeleton exists', () => {
    const html = `
    <div class="chat-mermaid-block">
      <div class="chat-mermaid-block__header"><span class="chat-mermaid-block__badge">图表</span></div>
      <pre class="chat-mermaid-block__canvas mermaid" data-mermaid="cached"><svg id="kept"></svg></pre>
    </div>
    <p>between</p>
    ${LOADING}
  `
    const injected = injectCachedMermaidSvg(
      html,
      ['<svg id="first"></svg>', '<svg id="second"></svg>'],
      buildBlock,
    )
    expect(injected).toMatch(/<svg id="kept">/)
    expect(injected).toMatch(/between/)
    expect(injected).toMatch(/<svg id="first">/)
    expect(injected.includes('streaming-mermaid-loading')).toBe(false)
  })

  it('injectCachedMermaidSvg fills unrendered mermaid canvases after the stream completes', () => {
    const html = `
    <pre class="chat-mermaid-block__canvas mermaid" id="m-1" data-mermaid="false"><code>graph TD</code></pre>
    <pre class="chat-mermaid-block__canvas mermaid" id="m-2" data-mermaid="false"><code>sequenceDiagram</code></pre>
  `
    const injected = injectCachedMermaidSvg(
      html,
      ['<svg id="first"></svg>', '<svg id="second"></svg>'],
      buildBlock,
    )
    expect(injected).toMatch(/id="m-1" data-mermaid="cached"><svg id="first">/)
    expect(injected).toMatch(/id="m-2" data-mermaid="cached"><svg id="second">/)
    expect(injected.includes('graph TD')).toBe(false)
  })

  it('prepareStreamingMermaidMarkdown masks a trailing incomplete mermaid fence', () => {
    const prepared = prepareStreamingMermaidMarkdown('Hello\n```mermaid\ngraph TD\n  A', LOADING)
    expect(prepared.includes('```mermaid')).toBe(false)
    expect(prepared).toMatch(/chat-mermaid-block--loading/)
    expect(prepared).toMatch(/Hello/)
  })

  // ---- 中文场景补充 ----

  it('注释含中文的 mermaid 块能被完整提取（中文不影响 fence 定位）', () => {
    const content = `说明文字

\`\`\`mermaid
graph TD
    %% 这里是中文注释
    A[开始] --> B[结束]
\`\`\`

结尾文字`
    expect(extractMermaidCodes(content)).toEqual([
      'graph TD\n    %% 这里是中文注释\n    A[开始] --> B[结束]',
    ])

    const masked = maskMermaidBlocksForStreaming(content, LOADING)
    expect(masked.includes('```mermaid')).toBe(false)
    // 块内中文随整块被占位符替换，块外中文必须保留
    expect(masked).not.toMatch(/这里是中文注释/)
    expect(masked).toMatch(/说明文字/)
    expect(masked).toMatch(/结尾文字/)
  })

  it('未闭合块后面紧跟中文正文时，块外中文保留、块内中文随占位符一起被替换', () => {
    const partial = '前面的中文段落\n```mermaid\ngraph TD\n  A[开始] --> B[结束]\n后面还在流的中文'
    const prepared = prepareStreamingMermaidMarkdown(partial, LOADING)
    // 未闭合块之后的内容本就属于代码块，必须整体让位给占位符，
    // 否则浅色代码块样式会包住这些中文。
    expect(prepared.includes('```mermaid')).toBe(false)
    expect(prepared).not.toMatch(/后面还在流的中文/)
    expect(prepared).toMatch(/前面的中文段落/)
    expect(prepared).toMatch(/chat-mermaid-block--loading/)
  })
})
