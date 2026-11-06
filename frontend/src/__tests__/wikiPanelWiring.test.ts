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
    //    QA-13 加 'qa' / W7 加 'graph' 都只是扩取值，不该让这条红；
    //    真正要守的是「docs/wiki 两个 tab 仍然存在且能切换」（下一条）。
    expect(kbPage).toMatch(/useState<'docs'[^>]*'wiki'[^>]*>\('docs'\)/)
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
    expect(wikiPanel).toContain('kb-wiki-loading')
    expect(wikiPanel).toContain('WikiEmpty')
    expect(wikiPanel).toContain('还没有 Wiki 页面')
  })

  it('⭐ 回滚走 API 而不是本地改（版本必须落库）', () => {
    expect(wikiPanel).toContain('wikiApi.revert(')
    expect(wikiPanel).toContain('wikiApi.revisions(')
  })

  it('⭐ 图谱复用已有画布组件（⛔ 不另写一套）', () => {
    expect(wikiPanel).toContain("from './KnowledgeGraphCanvas'")
    expect(wikiPanel).toContain('<KnowledgeGraphCanvas')
  })

  it('⭐ Lint 面板展示健康分与问题', () => {
    expect(wikiPanel).toContain('healthTone(')
    expect(wikiPanel).toContain('WIKI_ISSUE_TYPE_LABEL')
    expect(wikiPanel).toContain('lint.issues.length')
  })
})
