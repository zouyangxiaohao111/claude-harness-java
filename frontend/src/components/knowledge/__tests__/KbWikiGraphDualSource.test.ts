import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

/**
 * `KbWikiGraph` 双数据通路契约（2026-11-11）。
 *
 * <h2>背景</h2>
 * 用户要求「图谱按照 wiki 图谱动画样式来 完整实现」⇒ 关系图谱（实体-关系）
 * 不再用 canvas 星图，改为**复用** Wiki 图谱的 SVG 引擎
 * （力导向 / 集群轮廓 / 标签避让 / pan-zoom / 展开子图 / 抽屉）。
 *
 * <h2>这个测试守什么</h2>
 * 复用引擎最大的风险是**把两套数据搞混**：
 * <ol>
 *   <li>外部接管时仍发 `wikiApi.getGraph` ⇒ 画出的是 Wiki 页面链接图，
 *       与实体-关系图完全不同的两份数据（张冠李戴，且零报错）；</li>
 *   <li>外部节点仍去查 `wikiApi.getPage` ⇒ 实体没有 Wiki 页面，抽屉一直转圈；</li>
 *   <li>视觉方案不传就当 Wiki 的六类用 ⇒ 关系图谱图例出现「综合 / 对比」，
 *       而这两类在实体图里永远不存在（点了没反应 = 假图例）。</li>
 * </ol>
 *
 * <p>⚠️ 这里断言的是**源码层的分支存在性**（纯渲染路径很难在 node 环境跑通：
 * 引擎全是 DOM API）。行为层的验证在真机做（见交付记录）。
 */
const FILE = fileURLToPath(new URL('../KbWikiGraph.tsx', import.meta.url))

function src(): string {
  return readFileSync(FILE, 'utf8').replace(/\r\n?/g, '\n')
}

/** 剥掉注释，避免注释里的字面量造成假绿/假红。 */
function code(): string {
  return src()
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\/\/.*$/gm, '')
}

describe('KbWikiGraph 双数据通路', () => {
  it('⭐⭐ 外部供数时必须跳过 wikiApi.getGraph（否则画出 Wiki 链接图）', () => {
    const s = code()
    const i = s.indexOf('const external = dataOverride !== undefined')
    expect(i, '找不到 external 判据').toBeGreaterThan(0)
    // 内部加载的 effect 里必须先 return
    const j = s.indexOf('if (external) return', i)
    expect(j, '内部加载 effect 缺 `if (external) return` 守卫').toBeGreaterThan(i)
    // 且守卫必须在 getGraph 调用之前
    const k = s.indexOf('.getGraph(', i)
    expect(k).toBeGreaterThan(0)
    expect(j, '守卫必须在 wikiApi.getGraph 之前').toBeLessThan(k)
  })

  it('⭐⭐ 外部节点不得去查 wikiApi.getPage（实体没有 Wiki 页面 ⇒ 抽屉转圈）', () => {
    const s = code()
    const i = s.indexOf('if (renderNodeBody) return')
    expect(i, 'getPage 兜底缺 `if (renderNodeBody) return` 守卫').toBeGreaterThan(0)
    const k = s.indexOf('.getPage(', i)
    expect(k).toBeGreaterThan(i)
    expect(i).toBeLessThan(k)
  })

  it('⭐⭐ dataOverride 传 null 与 undefined 必须区分（加载完 0 节点 ≠ 不接管）', () => {
    const s = code()
    // ✅ 正确写法：与 undefined 比较
    expect(s).toContain('dataOverride !== undefined')
    // ⛔ 真值判断会把「外部加载完但 0 节点（null）」误判成「不接管」⇒ 又去发 wiki 请求
    expect(s, '⛔ 不能用真值判断').not.toContain('!!dataOverride')
    expect(s, '⛔ 不能用 != null 判断').not.toContain('dataOverride != null')
  })

  it('⭐⭐ 视觉方案必须可注入，且不传时回落到 Wiki 六类', () => {
    const s = code()
    expect(s, '缺 schema 回落常量').toContain('WIKI_SCHEMA')
    expect(s, '组件必须从 props 取 schema').toContain('schemaProp ?? WIKI_SCHEMA')
    // 组件体内不得再直接引用模块级常量（否则注入无效）
    const body = s.slice(s.indexOf('export function KbWikiGraph('))
    for (const hard of ['NODE_COLOR[', 'TYPE_LABEL[', 'TYPE_THEME[', 'LEGEND_TYPES.map(', 'new Set(ALL_TYPES)']) {
      expect(body.includes(hard), `组件体内仍硬编码 ${hard} ⇒ 注入的 schema 不生效`).toBe(false)
    }
  })

  it('⭐ schema 必须进 renderGraph 依赖（换方案要重绘）', () => {
    const s = code()
    expect(s).toMatch(/\}, \[data, mode, centerSlug, renderToken, buildAdjacency, applyFilter, schema\]\)/)
  })

  it('⭐ 抽屉正文：给了 renderNodeBody 就用它，优先于 wiki markdown', () => {
    const s = code()
    const i = s.indexOf('{renderNodeBody ? (')
    expect(i).toBeGreaterThan(0)
    const j = s.indexOf(') : drawerBodyHtml ? (', i)
    expect(j, 'renderNodeBody 分支后面必须紧跟 drawerBodyHtml 兜底').toBeGreaterThan(i)
  })
})
