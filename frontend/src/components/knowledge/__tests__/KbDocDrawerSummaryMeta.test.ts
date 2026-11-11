import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

/**
 * 文档抽屉的「摘要 / 自定义元数据」可编辑能力（2026-11-09）。
 *
 * <h2>WHY（用户原话）</h2>
 * 「点开文档 右边抽屉 摘要应该能够生成（重新）可以编辑」
 * + 截图里「自定义元数据」只有一个 `＋`，点了弹「本仓 kb_knowledge 无 metadata 列，暂未接入」。
 *
 * <h2>那两个「暂未接入」都是**错的**，这次一并纠正</h2>
 * <ul>
 *   <li><b>摘要正文有落点</b>：`KbSummaryService` 把摘要写回
 *       `kb_knowledge.description`（源码注释「写回 description / profile」），
 *       ⛔ 不是「只存在分块里、抽屉拿不到」。</li>
 *   <li><b>元数据有列有接口</b>：`kb_knowledge.custom_metadata` 列存在，
 *       `PUT /api/v1/knowledge/{id}` 的 `custom_metadata` 字段也早就在收。</li>
 * </ul>
 *
 * <h2>为什么是源码级断言</h2>
 * `KbDocDrawer` 是 1700+ 行的抽屉组件，jsdom 挂载要 mock 分块接口、预览字节、
 * 助手面板与五种弹层。而被测的是「这几个入口在不在、判据写对没有」。
 * 失效方式全是静默的：按钮在但 handler 是 toast、或者拿 status 当正文用 ——
 * 编译通过、界面看起来正常，只有用户点下去才发现没反应。
 */

const DRAWER = fileURLToPath(new URL('../KbDocDrawer.tsx', import.meta.url))
const API = fileURLToPath(new URL('../../../api/knowledge.ts', import.meta.url))
const SPRITE = fileURLToPath(new URL('../IconSprite.tsx', import.meta.url))

function src(p: string): string {
  return readFileSync(p, 'utf8').replace(/\r\n?/g, '\n')
}

describe('摘要：可重新生成 + 可编辑', () => {
  it('⭐ 抽屉里有「重新生成」与「编辑」两个入口，且都接真实 API', () => {
    const s = src(DRAWER)
    expect(s, '缺重新生成').toContain('regenerateSummary()')
    expect(s, '缺摘要保存').toContain('saveSummary()')
    expect(s, '重新生成必须调真实端点，⛔ 不能只弹 toast')
      .toContain('knowledgeApi.regenerateSummary')
    expect(s, '摘要保存必须调真实端点').toContain('knowledgeApi.updateSummary')
  })

  it('⭐ 摘要正文取自 knowledge.description（⛔ 不是「只存在分块里」）', () => {
    const s = src(DRAWER)
    // 上一版的错误说法必须消失，否则下一个人会照它继续写错
    expect(s, '「正文在分块里」是错的：description 就是摘要正文')
      .not.toContain('摘要正文在分块内容中')
    expect(s).toMatch(/useState\(\(\) => knowledge\.description/)
  })

  it('⭐⭐ 判定用 summary_status，不能用「有没有 description」', () => {
    const s = src(DRAWER)
    // failed 时 description 可能还是上一次的旧值 ⇒ 按正文有无判断会把失败当成功
    expect(s).toContain("r.summary_status === 'completed'")
    expect(s).toContain("r.summary_status === 'failed'")
  })

  it('⭐ 重新生成是同步 LLM 调用 ⇒ 必须有 loading（否则像点了没反应）', () => {
    const s = src(DRAWER)
    expect(s).toContain('summaryRefreshing')
    expect(s, '按钮要有 disabled 兜底，防连点').toMatch(/disabled=\{summaryRefreshing\}/)
  })
})

describe('自定义元数据：可读可写', () => {
  it('⭐⭐ 后端 custom_metadata 是 TEXT 存 JSON 串 ⇒ 前端读要 parse、写要 stringify', () => {
    const api = src(API)
    // 传对象会让后端反序列化失败（KnowledgeDto 那个字段是 String）
    expect(api, 'updateCustomMetadata 必须 JSON.stringify').toMatch(
      /updateCustomMetadata[\s\S]{0,300}JSON\.stringify\(metadata\)/,
    )
    const drawer = src(DRAWER)
    expect(drawer, '读的时候必须 parse').toContain('JSON.parse(raw)')
  })

  it('⭐ 脏 JSON 不能让抽屉白屏（降级为空列表）', () => {
    const s = src(DRAWER)
    const i = s.indexOf('JSON.parse(raw)')
    expect(i).toBeGreaterThan(0)
    const block = s.slice(i - 400, i + 300)
    expect(block, 'parse 必须有 try/catch 兜底').toMatch(/try\s*\{/)
    expect(block).toMatch(/catch/)
  })

  it('⭐ 旧的「本仓无 metadata 列」说法必须消失（它是错的）', () => {
    const s = src(DRAWER)
    expect(s, 'kb_knowledge 有 custom_metadata 列，别再说没有')
      .not.toContain('本仓 kb_knowledge 无 metadata 列')
    expect(s).toContain('knowledgeApi.updateCustomMetadata')
  })

  it('⭐ 数字类型保存前必须校验（Number("abc")=NaN 会被 JSON 写成 null）', () => {
    const s = src(DRAWER)
    expect(s).toMatch(/Number\.isFinite/)
  })

  it('⭐ 条目上限 20（源码 metadataDraft.length < 20）', () => {
    const s = src(DRAWER)
    expect(s).toMatch(/META_MAX_ROWS\s*=\s*20/)
  })
})

describe('图标用官方 tdesign path，⛔ 不是自绘', () => {
  it('⭐ info-circle / add 两个 symbol 必须在 sprite 里（摘要与元数据区要用）', () => {
    const s = src(SPRITE)
    expect(s, '缺 info-circle').toContain('id="t-icon-info-circle"')
    expect(s, '缺 add').toContain('id="t-icon-add"')
  })

  it('⭐ 抽屉里用 <use> 引用 sprite，⛔ 不用字符/自绘形状', () => {
    const s = src(DRAWER)
    expect(s).toContain('href="#t-icon-info-circle"')
    expect(s).toContain('href="#t-icon-add"')
    expect(s).toContain('href="#t-icon-refresh"')
    expect(s).toContain('href="#t-icon-edit"')
  })
})
