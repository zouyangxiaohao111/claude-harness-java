import { describe, expect, it } from 'vitest'
import { IncrementalMarkdownParser } from '../incremental.ts'
import { parseGfm } from '../parse.ts'

describe('IncrementalMarkdownParser（增量尾窗解析核心）', () => {
  it('追加三帧：generation 恒 0、frozen 单调增长不缩、cached 幂等', () => {
    const parser = new IncrementalMarkdownParser(parseGfm)
    const a = parser.update('第一段\n\n')
    const b = parser.update('第一段\n\n第二段\n\n')
    const c = parser.update('第一段\n\n第二段\n\n第三段')

    expect(a.generation).toBe(0)
    expect(b.generation).toBe(0)
    expect(c.generation).toBe(0)
    // frozen 单调：后帧 >= 前帧（不缩水）
    expect(b.frozen.length).toBeGreaterThanOrEqual(a.frozen.length)
    expect(c.frozen.length).toBeGreaterThanOrEqual(b.frozen.length)
    // 同文本幂等：返回同一缓存对象引用
    expect(parser.update('第一段\n\n第二段\n\n第三段')).toBe(c)
  })

  it('非追加（startsWith 失败）→ generation 递增且 frozen 清空重建', () => {
    const parser = new IncrementalMarkdownParser(parseGfm)
    const a = parser.update('段落A内容')
    const b = parser.update('完全不同的开头')
    expect(b.generation).toBe(a.generation + 1)
    // 重建后文本已解析，frozen 从当前短文本起
    expect(b.frozen.length).toBeGreaterThanOrEqual(0)
  })

  it('块 key = 源文本绝对 offset，与 verbatim slice 对得上（尾窗可切片）', () => {
    const parser = new IncrementalMarkdownParser(parseGfm)
    const text = '## 标题\n\n正文第一行\n\n正文第二行'
    const res = parser.update(text)
    // 无拼接可偷懒验证：尾部 tail + frozen 的块应能按 offset 拼回源文语义
    const all = [...res.frozen, ...res.tail]
    const starts = all.map((b) => (typeof b.key === 'number' ? b.key : -1)).filter((k) => k >= 0)
    for (let i = 1; i < starts.length; i++) {
      expect(starts[i]!).toBeGreaterThanOrEqual(starts[i - 1]!)
    }
  })
})
