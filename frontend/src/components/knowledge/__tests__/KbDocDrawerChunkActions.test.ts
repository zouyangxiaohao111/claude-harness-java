import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

/**
 * 分块区的四处「点了没反应 / 和源站不一致」（2026-11-10 用户实测指出）。
 *
 * <h2>用户原话</h2>
 * 「现在这个抽屉 图标无法交互了 比如打不开父级上下文；辅助召回片段怎么没生成；
 *  启用关闭无法关闭开启；以及『393 字 / 第 2 段 + 第 3 段 等 3 处』这个在原测是没有的」
 *
 * <h2>四条各自真因</h2>
 * <ol>
 *   <li><b>父块打不开</b>：源侧 `v-if="chunk.hasParent"`（`hasParent = !!item.parent_chunk_id`），
 *       没有父块时按钮**根本不渲染**。本仓无条件渲染 ⇒ 点了只弹 toast，看着像坏了。</li>
 *   <li><b>辅助召回没生成</b>：真机日志 `[问句] 模型调用失败 … 400 but you passed model-0823a98d`
 *       —— 把 `models.id` 当模型名发出去，与摘要/图谱/Wiki 同源。已由后端修。</li>
 *   <li><b>启停不能切</b>：本仓写成只读 `<span aria-disabled>`，没有 onClick；
 *       源侧是 `<t-switch @change="toggleChunkEnabled">` 真能切。</li>
 *   <li><b>「第 2 段 + 第 3 段 等 3 处」</b>：源侧 `getChunkMeta` 只拼 `char_count 字`
 *       与 `token_count tokens`，头部没有溯源摘要 —— 那是本仓自加的。</li>
 * </ol>
 */

const DRAWER = fileURLToPath(new URL('../KbDocDrawer.tsx', import.meta.url))

function src(): string {
  return readFileSync(DRAWER, 'utf8').replace(/\r\n?/g, '\n')
}

describe('分块头部：父块按钮按源站条件渲染', () => {
  it('⭐⭐ 没有 parent_chunk_id 时不渲染父块按钮（源站 v-if="chunk.hasParent"）', () => {
    const s = src()
    const i = s.indexOf("title=\"查看父块上下文\"")
    expect(i).toBeGreaterThan(0)
    // 往前 600 字符内必须有 `{c.parent_chunk_id && (`
    const before = s.slice(Math.max(0, i - 600), i)
    expect(before, '父块按钮没有条件渲染 ⇒ 无父块时点了没反应的死按钮')
      .toContain('c.parent_chunk_id &&')
  })
})

describe('分块头部：启停开关必须真的能切', () => {
  it('⭐⭐ 开关按源站结构是 div.t-switch，但必须真的可点（role/onClick/键盘）', () => {
    const s = src()
    const i = s.indexOf('t-switch t-size-s${c.is_enabled')
    expect(i, '找不到开关').toBeGreaterThan(0)
    const block = s.slice(Math.max(0, i - 400), i + 1200)
    // 源站 DOM 是 `<div class="t-switch t-size-s">` + handle + content
    expect(block, '开关不是 div（源站是 div，不是 button）').toContain('<div')
    expect(block, '缺 t-switch__content（源站 DOM 里有）').toContain('t-switch__content')
    // 但必须自己给可点能力 —— 否则就是个哑 div
    expect(block, '缺 role="switch"').toContain('role="switch"')
    expect(block, '缺 onClick').toContain('onClick={() =>')
    expect(block, '缺键盘可达（Enter/Space）').toContain('onKeyDown')
    expect(block, '仍在标题里承认不支持切换').not.toContain('本页暂不支持切换启停')
  })

  it('⭐⭐ toggleChunkEnabled 必须真调后端（⛔ 只改本地 state 会「看着停用、搜索照样命中」）', () => {
    const s = src()
    const i = s.indexOf('const toggleChunkEnabled')
    expect(i).toBeGreaterThan(0)
    const block = s.slice(i, i + 900)
    expect(block).toContain('knowledgeApi.updateChunk(')
    expect(block).toContain('is_enabled')
    // 失败必须让用户知道
    expect(block).toContain("showToast(`切换失败")
  })
})

describe('分块头部：不渲染空 chunk-meta', () => {
  it('⭐⭐ 完全不输出 chunk-meta 节点（用户要求「这个也不要了」）', () => {
    const s = src()
    // 源站那个 span 是 0×0 的空节点；用户明确要求去掉 ⇒ 本仓不输出它。
    // ⚠️ 注释里可以提到 chunk-meta（用于说明为什么不做），所以只查 JSX 里的标签。
    expect(s, '仍在渲染 <span className="chunk-meta"')
      .not.toMatch(/<span\s+className="chunk-meta"/)
    expect(s, '仍在显示字数').not.toMatch(/\{c\.content\?\.length \?\? 0\} 字/)
  })
})

describe('分块头部：不显示源站没有的溯源摘要', () => {
  it('⭐⭐ 头部不得渲染 locSummary（源站只有「片段 N」+「N 字」）', () => {
    const s = src()
    const i = s.indexOf('<div className="chunk-heading">')
    expect(i).toBeGreaterThan(0)
    // ⚠️ 取 chunk-heading **之后**到 chunk-header-right 之前这一段：
    //    chunk-header-right 在 chunk-heading 之后（不是之前），反着切会拿到整块。
    const right = s.indexOf('className="chunk-header-right"', i)
    expect(right, '找不到 chunk-header-right').toBeGreaterThan(i)
    const head = s.slice(i, right)
    expect(head, 'chunk-heading 里仍有 locSummary（源站没有这一项）')
      .not.toContain('{locSummary}')
  })

  it('⭐ 溯源入口保留为图标按钮（信息不丢，只是不挤在头部）', () => {
    const s = src()
    expect(s).toContain('openLocators(c, i)')
    expect(s).toContain('查看溯源定位')
  })
})

describe('开关样式：不得覆盖 tdesign 原生尺寸（真机 26×16）', () => {
  const CSS = fileURLToPath(new URL('../../../styles/kb/kb-02.css', import.meta.url))

  it('⭐⭐ kb-02.css 不许写死 .t-switch 的 width/height', () => {
    const css = readFileSync(CSS, 'utf8').replace(/\r\n?/g, '\n')
    // 真机读数：`.t-switch.t-size-s` = 26×16 / handle 12×12 / radius 999px
    // （由 kb-tdesign.css 的 tdesign 原生规则给出，取 --td-comp-size-xxxs=16px）。
    // 本仓曾写死 30×18 + handle 14×14 + translateX(12px)，会盖掉那 9 条原生规则。
    const blocks = css.match(/\.t-switch[^{]*\{[^}]*\}/g) ?? []
    const sized = blocks.filter((b) => /width:\s*\d+px/.test(b) || /height:\s*\d+px/.test(b))
    expect(sized, `这些规则写死了开关尺寸（真机是 26×16）：${sized.length} 条`)
      .toHaveLength(0)
  })

  it('⭐ 分块头的开关仍声明 flex-shrink:0（只保留这一条布局约束）', () => {
    const css = readFileSync(CSS, 'utf8').replace(/\r\n?/g, '\n')
    expect(css).toContain('chunk-header-right .t-switch')
    expect(css).toMatch(/chunk-header-right \.t-switch\s*\{\s*flex-shrink:\s*0/)
  })
})
