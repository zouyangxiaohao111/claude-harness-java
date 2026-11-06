import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { toCitations, toWebSources, toggleScopeIds } from '../KbQaPanel'

/**
 * 知识库问答面板 · QA-13。
 *
 * <h2>守两类静默缺陷</h2>
 * ① **事件键名不对称**：`citations` 事件是 `@JsonProperty` 钉死的 snake_case，
 *    REST 实体是驼峰。直接当同一类型用 ⇒ 每一栏变 `undefined`（引用标题全空、不报错）。
 * ② **范围被清空**：空数组会被后端解释成「全部可检索库」⇒ 用户以为只查一个库，
 *    实际查了全部。这是最难发现的一类越权，必须在 UI 层就挡住。
 *
 * <h2>另一组是源码级护栏</h2>
 * 页面接线（tab 存在、渲染面板、走 RAG 端点而非主对话端点）在 jsdom 里挂载
 * `KnowledgeBasePage` 要 mock 一整套 API；被测逻辑只是「一个 state 分支 + 一个三元」，
 * 失效方式却是静默的 ⇒ 同仓先例 `App.knowledgeBaseWiring.test.ts` 用源码断言。
 */

const PAGE_TSX = fileURLToPath(
  new URL('../../../components/knowledge/KnowledgeBasePage.tsx', import.meta.url)
)
const PANEL_TSX = fileURLToPath(new URL('../KbQaPanel.tsx', import.meta.url))
const CSS = fileURLToPath(new URL('../../../styles/globals.css', import.meta.url))

function src(p: string): string {
  return readFileSync(p, 'utf8').replace(/\r\n?/g, '\n')
}

describe('toCitations（事件 → REST 形状）', () => {
  it('⭐ snake_case 字段全部映射到驼峰（漏一个就是「该列恒空」）', () => {
    const out = toCitations({
      citations: [
        {
          ref_index: 2,
          chunk_id: 'c-1',
          knowledge_id: 'k-1',
          knowledge_base_id: 'kb-1',
          knowledge_title: '污水治理导则',
          snippet: '正文片段',
          chunk_index: 7,
        },
      ],
    })
    expect(out).toHaveLength(1)
    expect(out[0]).toEqual({
      refIndex: 2,
      chunkId: 'c-1',
      knowledgeId: 'k-1',
      knowledgeBaseId: 'kb-1',
      knowledgeTitle: '污水治理导则',
      snippet: '正文片段',
      chunkIndex: 7,
    })
    // 反向判据：不能留下任何 snake_case 键（说明是「原样透传」而不是「转换」）
    expect(Object.keys(out[0])).not.toContain('ref_index')
    expect(Object.keys(out[0])).not.toContain('chunk_id')
  })

  it('缺字段 ⇒ 落默认值而不是 undefined（undefined 会让渲染层崩）', () => {
    const out = toCitations({ citations: [{ chunk_id: 'c-2' }] })
    expect(out[0].refIndex).toBe(0)
    expect(out[0].knowledgeTitle).toBe('')
    expect(out[0].chunkIndex).toBe(null)
  })

  it('载荷缺失 / citations 非数组 ⇒ 空列表（⛔ 不抛）', () => {
    expect(toCitations(null)).toEqual([])
    expect(toCitations(undefined)).toEqual([])
    expect(toCitations({})).toEqual([])
    expect(toCitations({ citations: undefined })).toEqual([])
  })
})

describe('toWebSources', () => {
  it('两种键名都认（webSources / web_sources）', () => {
    expect(toWebSources({ webSources: [{ title: 'A', url: 'https://a' }] })).toHaveLength(1)
    expect(toWebSources({ web_sources: [{ title: 'B', url: 'https://b' }] })).toHaveLength(1)
  })

  it('缺失 ⇒ 空列表', () => {
    expect(toWebSources(null)).toEqual([])
    expect(toWebSources({})).toEqual([])
  })
})

describe('toggleScopeIds', () => {
  it('追加新库', () => {
    expect(toggleScopeIds(['a'], 'b')).toEqual(['a', 'b'])
  })

  it('再点一次取消', () => {
    expect(toggleScopeIds(['a', 'b'], 'a')).toEqual(['b'])
  })

  it('⭐⭐ 最后一个库删不掉（空范围 = 后端查全部，属于越权）', () => {
    expect(toggleScopeIds(['a'], 'a')).toEqual(['a'])
  })

  it('同一库连点两次回到原状（且不会产生重复项）', () => {
    const start = ['a']
    const once = toggleScopeIds(start, 'b')
    expect(once).toEqual(['a', 'b'])
    const twice = toggleScopeIds(once, 'b')
    expect(twice).toEqual(['a'])
    // 反向判据：任何阶段都不允许出现重复
    expect(new Set(once).size).toBe(once.length)
    expect(new Set(twice).size).toBe(twice.length)
  })
})

describe('页面接线（源码级）', () => {
  it('详情页 tab 联合类型含 qa', () => {
    // ⚠️ 断言「联合类型里包含 qa」，⛔ 不是逐字匹配整个字面量 ——
    // 新增 tab（如 gallery）时不该让这条误红。
    const m = src(PAGE_TSX).match(/useState<([^>]*)>\(\s*'docs'\s*\)/)
    expect(m).not.toBeNull()
    expect(m![1]).toContain("'qa'")
  })

  it('渲染 KbQaPanel 且传入全部知识库（跨库问答入口）', () => {
    const page = src(PAGE_TSX)
    expect(page).toContain('<KbQaPanel')
    expect(page).toContain('bases={bases}')
  })

  it('⭐ 走 RAG 端点，⛔ 不是主对话的 /sessions/{id}/messages', () => {
    const panel = src(PANEL_TSX)
    expect(panel).toContain('knowledgeApi.askQa')
    expect(panel).not.toContain('/messages`')
    expect(panel).not.toContain('processUserMessage')
  })

  it('订阅 citations 事件（阶段 12 的流中推引用）', () => {
    expect(src(PANEL_TSX)).toContain("t === 'citations'")
  })

  it('样式类真的有定义（JSX 写了 class 而 CSS 零定义 ⇒ 无样式裸元素）', () => {
    const css = src(CSS)
    expect(css).toContain('.kb-qa {')
    expect(css).toContain('.kb-qa-scope-chip')
    expect(css).toContain('.kb-qa-citation-snippet')
  })
})
