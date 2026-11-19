/**
 * [实验 · 块级冻结] 增量渲染的**等价性**判据。
 *
 * <p><b>WHY 这条测试是唯一硬判据</b>：块级冻结允许「前部块复用上帧 HTML」，
 * 一旦逐块解析/后处理与全文管线有任何偏差（列表 tight/loose、表格对齐、跨块
 * 引用定义、引用段落折叠、尾部渐变），用户看到的 markdown 就会和收口态不一致。
 * 所以每一帧都必须与 {@link renderChatMarkdown} 的输出**逐字节相等** ——
 * 只比较「看起来正常」是不够的（那正是两套解析器漂移的老路）。
 *
 * <p>复用判据单独断言：追加文本时前部块必须是同一个对象（真的复用），
 * 否则性能改造只是换了个写法、成本照旧。
 */
import { describe, expect, it } from 'vitest'
import { Renderer } from 'marked'

import {
  renderChatMarkdown,
  renderChatMarkdownIncremental,
  type BlockFreezeCache,
  type RenderChatMarkdownOptions,
} from '../chatMarkdownRenderer'

const opts: RenderChatMarkdownOptions = {
  renderer: new Renderer(),
  escapeMarkdown: (s) => s,
  sanitizeHtml: (s) => s,
  streaming: true,
}

const full = (text: string): string => renderChatMarkdown(text, opts)

/** 逐帧累积的文本序列（每项 = 一次 chunk 增量）。 */
function framesOf(parts: readonly string[]): string[] {
  let acc = ''
  return parts.map((part) => (acc += part))
}

/** 跑完整流，逐帧断言与全量等价，返回最后一帧结果。 */
function runFrames(frames: readonly string[]): {
  cache: BlockFreezeCache | null
  reused: number[]
} {
  let cache: BlockFreezeCache | null = null
  const reused: number[] = []
  for (const text of frames) {
    const result = renderChatMarkdownIncremental(text, opts, cache)
    cache = result.cache
    reused.push(result.reusedBlocks)
    expect(result.html, `帧内容不匹配：${JSON.stringify(text)}`).toBe(full(text))
  }
  return { cache, reused }
}

describe('MarkdownTextV2 块级冻结（实验）', () => {
  it('混合结构逐帧与全量逐字节一致（标题/列表/代码块/表格/引用定义）', () => {
    runFrames(framesOf([
      '# 标题\n\n',
      '第一段正文，带 **加粗** 与 `行内代码`。\n\n',
      '- 列表项 1\n',
      '- 列表项 2\n\n',
      '```ts\nconst a = 1\n',
      'const b = 2\n```\n\n',
      '| 列 A | 列 B |\n',
      '| --- | --- |\n',
      '| 1 | 2 |\n\n',
      '引用定义在后面：见 [文档][ref]。\n\n',
      '[ref]: https://example.com\n',
    ]))
  })

  it('未闭合围栏 / 悬挂列表标记逐帧一致（streamingGuards 仍生效）', () => {
    runFrames(framesOf([
      '正文\n\n',
      '```py\nprint(1)\n',
      '\n',
      '```\n\n',
      '- 悬挂列表\n',
      '  - 子项\n',
    ]))
  })

  it('追加时前部块真的被复用（对象引用相同），尾部才重解析', () => {
    const head = '# 标题\n\n段落一，内容较长以形成独立块。\n\n'
    const first = renderChatMarkdownIncremental(head, opts, null)
    expect(first.reusedBlocks).toBe(0)

    const second = renderChatMarkdownIncremental(head + '段落二，追加的一整段。\n\n', opts, first.cache)
    expect(second.reusedBlocks).toBeGreaterThan(0)
    // 同一对象 ⇒ HTML 字符串与 DOM 都可原样复用
    expect(second.cache.blocks[0]).toBe(first.cache.blocks[0])
    expect(second.html).toBe(full(head + '段落二，追加的一整段。\n\n'))
  })

  it('非追加（文本被替换）时缓存自动失效，结果与全量一致', () => {
    const first = renderChatMarkdownIncremental('旧内容第一段\n\n旧内容第二段\n', opts, null)
    const replaced = '完全不同的新内容\n\n'
    const second = renderChatMarkdownIncremental(replaced, opts, first.cache)
    expect(second.reusedBlocks).toBe(0)
    expect(second.html).toBe(full(replaced))
  })

  it('跨块引用定义仍能解析（links 共享，不退化成字面量）', () => {
    const text = '见 [文档][ref] 与 [另一处][ref]。\n\n[ref]: https://example.com\n'
    let cache: BlockFreezeCache | null = null
    const result = renderChatMarkdownIncremental(text, opts, cache)
    cache = result.cache
    expect(result.html).toBe(full(text))
    expect(result.html).toContain('href="https://example.com"')
  })

  it('空文本与纯空白直接返回空串（不建缓存）', () => {
    expect(renderChatMarkdownIncremental('', opts, null).html).toBe('')
    expect(renderChatMarkdownIncremental('   \n\n', opts, null).html).toBe('')
  })
})
