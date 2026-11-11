import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

/**
 * 知识库详情 · 「关系图谱」tab 的入口与渲染（2026-11-11 用户要求）。
 *
 * <h2>用户原话</h2>
 * 「还有关系图谱页面也没有 不是wiki图谱哦」
 *
 * <h2>⚠️ 这里最容易犯的错：把两个「图谱」当成一个</h2>
 * <table>
 *   <tr><th>tab</th><th>数据源</th><th>组件</th></tr>
 *   <tr><td>图谱</td><td>Wiki 页面之间的 <code>[[链接]]</code></td>
 *       <td>{@code WikiPanel initialView="graph"}（源站 {@code WikiBrowser :view="graph"}）</td></tr>
 *   <tr><td>关系图谱</td><td>从文档正文抽出的**实体-关系**（{@code kb_graph_entity}）</td>
 *       <td>{@code GraphPanel}</td></tr>
 * </table>
 * 两者数据源完全不同。历史上曾经「把图谱 tab 接成 GraphPanel」被用户指出接错
 * （见 KnowledgeBasePage 里那段注释），⛔ 不要再合并。
 *
 * <h2>为什么会有这个测试</h2>
 * {@code GraphPanel} 组件本身早就写好了（真后端 + 空态三分 + 搜索 + 重建），
 * 但**没有任何页面挂载它** ⇒ 功能存在、用户看不到。这类「实现了但没接线」
 * 不会报错、不会失败，只能靠测试守。
 */
const PAGE = fileURLToPath(new URL('../KnowledgeBasePage.tsx', import.meta.url))

function src(): string {
  return readFileSync(PAGE, 'utf8').replace(/\r\n?/g, '\n')
}

/** 剥掉块注释与行注释，只看真正会渲染/生效的代码。 */
function code(): string {
  return src()
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\/\/.*$/gm, '')
}

describe('关系图谱 tab', () => {
  it('⭐⭐ 必须有「关系图谱」tab 按钮且可切换', () => {
    const s = code()
    expect(s, '缺 tab 按钮 ⇒ 用户看不到入口').toContain("setDetailTab('entity-graph')")
    expect(s, 'tab 文本必须是「关系图谱」（与 Wiki 的「图谱」区分开）').toContain('关系图谱')
  })

  it('⭐⭐ tab 状态类型必须包含 entity-graph（否则 setState 编译不过或被静默吞）', () => {
    const s = code()
    expect(s).toMatch(/detailTab[\s\S]{0,120}'entity-graph'/)
  })

  it('⭐⭐ 关系图谱 tab 必须挂 GraphPanel —— 不是 WikiPanel', () => {
    const s = code()
    /* ⚠️ 必须锚定**三元的渲染分支**，不能用 indexOf("detailTab === 'entity-graph'")：
       tab 按钮的 className 里也有这个比较 ⇒ 会命中按钮区、拿不到渲染分支（实测假红两次）。 */
    const i = s.indexOf(") : detailTab === 'entity-graph' ? (")
    expect(i, '找不到 entity-graph 的渲染分支').toBeGreaterThan(0)
    // 取该分支到下一个分支之间的代码
    const next = s.indexOf("detailTab === 'gallery'", i)
    const block = s.slice(i, next > i ? next : i + 1200)
    expect(block, '必须渲染 GraphPanel（实体-关系图）').toContain('<GraphPanel')
    expect(block, '⛔ 不能接成 WikiPanel —— 那是 Wiki 页面链接图，用户明确指出不是这个')
      .not.toContain('<WikiPanel')
  })

  it('⭐ 现有「图谱」tab 必须仍然是 WikiPanel 的 graph 视图（不能被改掉）', () => {
    const s = code()
    const i = s.indexOf(") : detailTab === 'graph' ? (")
    expect(i, '找不到 graph 的渲染分支').toBeGreaterThan(0)
    const block = s.slice(i, i + 600)
    expect(block).toContain('<WikiPanel')
    expect(block).toContain('initialView="graph"')
  })

  it('⭐⭐ 三处空态必须带 is-open（否则 kb-04.css 的 display:none 让整页空白）', () => {
    /* 真机踩过：GraphPanel 复用 `.wiki-graph-empty`（该类的默认态是 display:none，
       只有 `.is-open` 才 display:flex），但漏了 is-open ⇒ 「加载中 / 加载失败 / 还没有图谱数据」
       三种空态全被藏起来，用户看到的是**一整片空白**（innerText 有字、屏幕上没字）。 */
    const panel = readFileSync(fileURLToPath(new URL('../GraphPanel.tsx', import.meta.url)), 'utf8')
      .replace(/\/\*[\s\S]*?\*\//g, '')
    const all = panel.match(/className="wiki-reader-empty wiki-graph-empty[^"]*"/g) ?? []
    expect(all.length, '应有 加载中/失败/无数据 三处空态').toBe(3)
    for (const c of all) {
      expect(c, `空态缺 is-open：${c}`).toContain('is-open')
    }
  })

  it('⭐⭐ 重建下拉的文档清单要有来源（否则下拉永远只有占位项）', () => {
    const s = src()
    expect(s, '缺 docOptions state').toContain('docOptions')
    expect(s, '必须用 listKnowledge 的返回值填充，⛔ 不能凭空造').toContain('setDocOptions')
    expect(s, '必须传给 GraphPanel').toContain('knowledgeOptions={docOptions}')
  })
})
