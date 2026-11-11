import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * Wiki tab 接线护栏 · W3。
 *
 * <p><b>WHY 用源码级断言</b>：本仓无 react-router、无 UI 库，tab 的「是否真的接上」
 * 只能靠渲染组件测（那需要 mock 一堆 API）。真正会坏的是<b>接线本身</b>：
 * tab 按钮没绑 state、切库没重置 tab、WikiPanel 没被渲染 —— 这三类都是
 * 「编译通过、运行时静默不对」。
 *
 * <p>⚠️ 断言只锚「属性名 + 第一个语句」，不整行匹配（整行会因格式化假红）。
 */
const KB_PAGE = resolve(__dirname, '../components/knowledge/KnowledgeBasePage.tsx')
const WIKI_PANEL = resolve(__dirname, '../components/knowledge/WikiPanel.tsx')

const kbPage = readFileSync(KB_PAGE, 'utf-8')
const wikiPanel = readFileSync(WIKI_PANEL, 'utf-8')

describe('[W3] Wiki tab 接线', () => {
  it('⭐ 详情页有 docs / wiki tab 的 state（QA-13 后扩到 docs/wiki/graph/qa）', () => {
    // ⚠️ 断言「docs 与 wiki 都在联合类型里」，⛔ 不钉死整串 ——
    //    QA-13 加 'qa' / W7 加 'graph' / 2026-11-11 加 'entity-graph' 都只是扩取值，不该让这条红；
    //    真正要守的是「docs/wiki 两个 tab 仍然存在且能切换」（下一条）。
    //    ⚠️ 联合类型被格式化器折成多行后，`[^>]*` 跨不了换行 ⇒ 用 `[\s\S]*?`（实测踩过）。
    expect(kbPage).toMatch(/useState<\s*'docs'[\s\S]*?'wiki'[\s\S]*?>\('docs'\)/)
  })

  it('⭐ 两个 tab 按钮都绑了 setDetailTab', () => {
    expect(kbPage).toContain("onClick={() => setDetailTab('docs')}")
    expect(kbPage).toContain("onClick={() => setDetailTab('wiki')}")
  })

  it('⭐⭐ 选中 wiki 时真的渲染 WikiPanel（⛔ 不是只换按钮样式）', () => {
    expect(kbPage).toContain("detailTab === 'wiki'")
    expect(kbPage).toContain('<WikiPanel')
    expect(kbPage).toContain('baseId={activeBaseId}')
  })

  it('⭐⭐ 切库必须重置 tab（否则停在上一库的 Wiki）', () => {
    // openBase 里必须同时有 setActiveBaseId 和 setDetailTab('docs')
    const openBaseIdx = kbPage.indexOf('const openBase = ')
    expect(openBaseIdx).toBeGreaterThan(-1)
    const body = kbPage.slice(openBaseIdx, openBaseIdx + 600)
    expect(body).toContain('setActiveBaseId(base.id)')
    expect(body).toContain("setDetailTab('docs')")
    // 时序：必须在 setView 之前（先重置再看详情）
    expect(body.indexOf("setDetailTab('docs')")).toBeLessThan(body.indexOf("setView('detail')"))
  })
})

describe('[W3] WikiPanel 关键行为', () => {
  it('⭐⭐ 正文必须走 renderWikiMarkdown（模型输出不能直接塞 innerHTML）', () => {
    expect(wikiPanel).toContain('renderWikiMarkdown(')
    expect(wikiPanel).toContain('dangerouslySetInnerHTML')
    // 且 html 变量来自 renderWikiMarkdown
    expect(wikiPanel).toContain('renderWikiMarkdown(activePage.content)')
  })

  it('⭐⭐ 内部链接点击必须被拦截（否则会跳出页面）', () => {
    expect(wikiPanel).toContain('onBodyClick')
    expect(wikiPanel).toContain('e.preventDefault()')
    expect(wikiPanel).toContain('WIKI_LINK_ATTR')
  })

  it('⭐ 空库与「加载中」是两个不同状态', () => {
    // 2026-11-10 对齐源站：空态文案照抄源站 i18n（zh-CN.ts:5824-5826）
    //   emptyTitle='暂无 Wiki 页面' / emptyDesc='上传文档并启用 Wiki 后将自动生成知识页面'
    //   selectPageHint='从左侧选择一个页面查看内容'
    // 加载态文案照源站 `knowledgeEditor.wikiBrowser.loading` = '加载中…'
    // ⛔ 旧锚点 `kb-wiki-loading` 是本仓自造的类名，实现里从来就没有 ⇒ 旧断言恒红。
    //    判据意图（空库 ≠ 加载中，两者不同分支）改用**真实存在的分支锚点**守。
    expect(wikiPanel).toContain('if (loading) {')
    expect(wikiPanel).toContain('加载中…')
    expect(wikiPanel).toContain('WikiEmpty')
    expect(wikiPanel).toContain('暂无 Wiki 页面')
    expect(wikiPanel).toContain('上传文档并启用 Wiki 后将自动生成知识页面')
  })

  it('⭐ 侧栏「知识/摘要」是内容分类筛选器（对齐源站 setActiveTab）', () => {
    // 2026-11-10 对齐源站 WikiBrowser.vue：
    //   :260-262  @click="setActiveTab(tab.type)" + active 类名跟随 activeTab
    //   :1284     CONTENT_TABS = ['knowledge','summary']
    //   :1319-24  知识 tab 计数 = 四类非 summary 求和（不含 summary）
    //   :1777     setActiveTab 只换侧栏列表，**不改动右侧已选中的页**
    expect(wikiPanel).toContain("useState<'knowledge' | 'summary'>")
    expect(wikiPanel).toContain("setActiveTab('knowledge')")
    expect(wikiPanel).toContain("setActiveTab('summary')")
    // 按 page_type 过滤（不是 slug 前缀）
    expect(wikiPanel).toContain("p.page_type === 'summary'")
    expect(wikiPanel).toContain("p.page_type !== 'summary'")
    // 计数：知识 tab 排除 summary
    expect(wikiPanel).not.toContain('{pages.length}')
  })

  it('⭐ 默认展示索引（系统视图，与选中页互斥）', () => {
    // 2026-11-10 对齐源站 WikiBrowser.vue：
    //   :1005   activeSystemView = ref<'' | 'index'>('')
    //   :2779   openIndexView(): selectedPage = null + activeSystemView = 'index'
    //   :2980-91 加载后默认 openIndexView()（注释：「The index is the natural landing view」）
    //   :247-51 侧栏置顶入口 .wiki-nav-item > svg.wiki-nav-icon + .wiki-nav-text「索引」
    expect(wikiPanel).toContain("systemView, setSystemView] = useState<'' | 'index'>")
    expect(wikiPanel).toContain('openIndexView')
    expect(wikiPanel).toContain('wikiApi.getIndex(baseId)')
    expect(wikiPanel).toContain('wiki-nav-item')
    expect(wikiPanel).toContain('wiki-nav-text')
    expect(wikiPanel).toContain('wiki-index-body')
    // 索引与选中页互斥：选中普通页要退出系统视图
    expect(wikiPanel).toContain("setSystemView('')")
  })

  it('⭐ 回滚走 API 而不是本地改（版本必须落库）', () => {    expect(wikiPanel).toContain('wikiApi.revert(')
    expect(wikiPanel).toContain('wikiApi.revisions(')
  })

  it('⭐ 图谱走设计稿 04 的 SVG 实现（⛔ 不另一套 canvas 图）', () => {
    // 2026-11-09 用户裁定「直接照 04 页做」：图谱视图 = KbWikiGraph（SVG 力导向 + 480px 抽屉）。
    // 旧断言守的是「复用 KnowledgeGraphCanvas」，那条决策已被设计稿覆盖 ⇒ 改锚新组件。
    expect(wikiPanel).toContain("from './KbWikiGraph'")
    expect(wikiPanel).toContain('<KbWikiGraph')
    expect(wikiPanel).not.toContain('<KnowledgeGraphCanvas')
  })

  it('⭐ Lint 面板展示健康分与问题', () => {
    expect(wikiPanel).toContain('healthTone(')
    expect(wikiPanel).toContain('WIKI_ISSUE_TYPE_LABEL')
    expect(wikiPanel).toContain('lint.issues.length')
  })
})
