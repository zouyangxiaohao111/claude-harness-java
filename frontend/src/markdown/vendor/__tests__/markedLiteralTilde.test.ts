import { describe, expect, it } from 'vitest'
import { Marked, marked } from 'marked'
import {
  ensureLiteralSingleTildeOnGlobalMarked,
  literalSingleTildeExtension,
} from '../markedLiteralTilde'

/**
 * 意图：GFM 会把单个 `~` 组成的 `~文本~` 当成删除线，而中文技术文档里 `~` 常表示
 * "约"或区间（`2020~2035`、`~约~ 3 天`）。若被误判，正文会被 <del> 吃掉。
 * 因此这里断言的是"单个 ~ 不得产生 del 标签"，而不只是某个字符串原样出现。
 */
describe('literalSingleTildeExtension', () => {
  const markup = (src: string) => {
    const instance = new Marked()
    instance.use(literalSingleTildeExtension)
    return instance.parse(src)
  }

  it('单个 ~ 包裹的文本不生成 del 标签（`~约~` 保持字面量）', () => {
    const html = markup('~约~ 3 天')
    expect(html).not.toContain('<del>')
    expect(html).toContain('~约~')
  })

  it('区间里的单个 ~ 保持字面量（`2020~2035`）', () => {
    const html = markup('2020~2035')
    expect(html).not.toContain('<del>')
    expect(html).toContain('2020~2035')
  })

  it('英文字符间的单个 ~ 也不生成 del 标签（`a ~b~ c`）', () => {
    const html = markup('a ~b~ c')
    expect(html).not.toContain('<del>')
    expect(html).toContain('a ~b~ c')
  })

  it('成对的双波浪 ~~ 仍然生成 del 标签', () => {
    expect(markup('这是 ~~删除~~ 文本')).toContain('<del>删除</del>')
  })
})

describe('ensureLiteralSingleTildeOnGlobalMarked', () => {
  it('注册到全局 marked 后，单 ~ 字面量 / 双 ~~ 仍为删除线', () => {
    ensureLiteralSingleTildeOnGlobalMarked()

    expect(marked.parse('~约~ 3 天')).not.toContain('<del>')
    expect(marked.parse('这是 ~~删除~~ 文本')).toContain('<del>删除</del>')
  })

  it('重复调用不会重复注册（幂等，不抛错）', () => {
    ensureLiteralSingleTildeOnGlobalMarked()
    expect(() => ensureLiteralSingleTildeOnGlobalMarked()).not.toThrow()
  })
})
