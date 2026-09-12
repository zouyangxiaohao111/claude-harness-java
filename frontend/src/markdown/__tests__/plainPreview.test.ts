import { describe, expect, it } from 'vitest'
import { projectPreview } from '../plainPreview.ts'

describe('projectPreview', () => {
  it('只投影前 maxChars 个字符（先截断、后投影）', () => {
    // 窗口正好是 `# 标题\n\n`（6 字符）：标记必须被剥掉，窗口外的长正文不参与
    const src = '# 标题\n\n' + 'x'.repeat(100_000)
    expect(projectPreview(src, 6)).toBe('标题')
  })

  it('关键顺序：截断发生在投影之前（不是先投影全文再截断）', () => {
    // 窗口内是「标题 + 17 个尾随空格」：只剩尾随空白，compactInline 的 trim 把它抹掉，
    // 投影恰好是 '标题'。若顺序写反（先投影全文、后截断），后面的 x 会被折叠成一个空格
    // 接在标题后，截断出来的就是 '标题 xxxx…' —— 断言立刻变红（实测见提交说明）。
    //
    // 注意：不能用「尾部放个 # 标题、断言它不出现」那种写法 —— 实测正反两种实现都通过
    // （反向实现截断的是长串 a，尾部标题本来就在截断点之外），没有鉴别力。
    const src = '# 标题' + ' '.repeat(100) + 'x'.repeat(1000)
    const out = projectPreview(src, 20)
    expect(out).toBe('标题')
    expect(out).not.toContain('x')
  })

  it('空输入与全 thematicBreak 切片均可返回空串（已知边界）', () => {
    expect(projectPreview('', 5000)).toBe('')
    expect(projectPreview('---\n'.repeat(5100), 5000)).toBe('')
  })
})
