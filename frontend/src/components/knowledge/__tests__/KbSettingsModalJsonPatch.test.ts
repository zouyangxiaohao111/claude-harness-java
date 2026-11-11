import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

/**
 * 知识库设置弹窗里「JSON 配置开关」的两个静默缺陷（2026-11-09）。
 *
 * <h2>缺陷 A：回显读死值 ⇒ 开关永远显示未开启</h2>
 * `indexing` 原先写死 `parseJson(null, {})`，注释理由是「`KnowledgeBaseDto` 不回传
 * `indexing_strategy`」。**那条注释早过期了** —— DTO 有这个字段，
 * 实测 PATCH 写入 `{"graph_enabled":true}` 后 GET 能原样读回。
 * 后果：checkbox 恒未选中 ⇒ 用户以为没开；而 `KbPostProcessConfig.graphEnabled()`
 * 同时要求 `indexing_strategy.graph_enabled` 与 `extract_config.enabled`
 * ⇒ **图谱永远不会自动生成**，且界面上看不出原因。
 *
 * <h2>缺陷 B：写入只发一个键 ⇒ 冲掉同一 JSON 里的其它键</h2>
 * `PATCH /knowledge-bases/{id}` 的 JSON 配置是**整体覆盖**（后端 `setJsonField`
 * 直接写整串）。原代码 `JSON.stringify({ graph_enabled: v })` 只发一个键 ⇒
 * `wiki_enabled` / `vector_enabled` 被一起抹掉。
 * 实测：先写 `{graph_enabled:true, wiki_enabled:true}`，再按旧写法发
 * `{graph_enabled:false}` ⇒ 回读只剩 `{"graph_enabled": false}`，`wiki_enabled` 没了。
 *
 * <p>五个开关（图谱 / 抽取 / 问题生成 / 自动打标 / Wiki）**同一形态**，
 * 所以断言按「每个 patch 块里必须出现 spread」逐条守，而不是只守一个。
 */

const MODAL = fileURLToPath(new URL('../KbSettingsModal.tsx', import.meta.url))

function src(): string {
  return readFileSync(MODAL, 'utf8').replace(/\r\n?/g, '\n')
}

describe('知识库设置：「启用图谱」必须能回显', () => {
  it('⭐⭐ indexing 必须读 form.indexing_strategy，⛔ 不能读死 null', () => {
    const s = src()
    // 读死 null 的实现（历史缺陷）不能残留
    expect(s, '还读死 null ⇒ 图谱开关永远显示未开启')
      .not.toMatch(/parseJson<\{ graph_enabled\?: boolean; wiki_enabled\?: boolean \}>\(null/)
    // 必须真的从表单值解析
    const i = s.indexOf('const indexing = useMemo(')
    expect(i, '找不到 indexing 的 useMemo').toBeGreaterThan(0)
    const block = s.slice(i, i + 400)
    expect(block, 'indexing 必须读 form.indexing_strategy').toContain('form.indexing_strategy')
  })

  it('⭐ 「DTO 不回传」不能作为**实现理由**残留（已过期）', () => {
    const s = src()
    // ⚠️ 不能只查 `toContain('KnowledgeBaseDto 不回传')` —— 新注释为了说明
    //    「那条旧理由已过期」也会提到这几个字，全文断言会假红。
    //    真正的判据是：**实现**还在读死 null。
    expect(s, '实现仍读死 null ⇒ 图谱开关永远未开启')
      .not.toMatch(/parseJson<\{[^}]*graph_enabled[^}]*\}>\(null/)
  })
})

describe('知识库设置：JSON 配置写入必须合并，⛔ 不能只发一个键', () => {
  /**
   * 每个开关的 patch 块都必须包含 `...parseJson(...)` 展开。
   * 判据取「该 key 的 JSON.stringify 之前 200 字符内有没有 spread」。
   */
  // ⚠️ `indexing_strategy` 的 spread 源是 `...indexing`（已解析的 memo），
  //    其余四个是 `...parseJson(form.xxx)` —— 判据统一写成「必须有 spread」。
  /**
   * ⚠️ 2026-11-10 二次修订（B6/B7）：`extract_config` / `question_generation_config` /
   * `auto_tag_config` 也都从「内联 `JSON.stringify({...})`」抽成了
   * `saveGraph` / `saveQgen` / `saveAutotag` 辅助函数 ⇒ 旧的字面量判据全部假红。
   * 保留在本数组里的只剩仍是内联写法的键。
   */
  const CASES: Array<[string, string, string]> = []

  /**
   * ⚠️ 判据 2026-11-10 改过一次：原判据是「`key: JSON.stringify({` 之后 260 字符内必须有
   * `...parseJson(...)`」。B2 把 `wiki_config` 的内联合并抽成了 `withWiki()` 辅助函数
   * ⇒ 该字面量不再出现，测试假红。
   *
   * <p>改为**语义判据**：合并必须发生在**唯一收口处**（`withWiki` / `saveIndexing`），
   * 且收口处本身必须 spread 现值。两者都要，缺一就是「有人又写回内联单键覆盖」。
   */
  const MERGERS: Array<[string, string, string]> = [
    ['withWiki', 'wiki_config', 'synthesis_model_id / auto_generate / extraction_granularity'],
    ['saveIndexing', 'indexing_strategy', 'wiki_enabled / vector_enabled / graph_enabled'],
    ['saveGraph', 'extract_config', 'model_id 等其它键'],
    ['saveQgen', 'question_generation_config', 'model_id / question_count'],
    ['saveAutotag', 'auto_tag_config', 'max_tags / skip_if_tagged'],
    ['saveImage', 'image_processing_config', 'model_id / image_actions'],
    ['saveAsr', 'asr_config', 'model_id / language'],
    ['saveProfile', 'profile_config', 'model_id / custom_instructions'],
    ['saveChunkingRaw', 'chunking_config', 'chunk_size / split_markers / parser_engine_rules'],
  ]

  for (const [fn, key, lost] of MERGERS) {
    it(`⭐「${key}」合并必须在 ${fn}() 里 spread 现值（否则冲掉 ${lost}）`, () => {
      const s = src()
      const i = s.indexOf(`const ${fn} =`)
      expect(i, `找不到 ${fn} 的合并收口点`).toBeGreaterThan(0)
      /* ⚠️ 窗口必须**截到函数体结束**，不能固定 700 字符 ——
         固定窗口会越界读到下一个函数里的 spread ⇒ 变异「去掉本函数的 spread」仍绿（实测假绿）。
         这里用「从 fn 起点到其后第一个 `\n  }` 」界定函数体。 */
      const bodyEnd = s.indexOf('\n  }', i)
      expect(bodyEnd, `${fn} 函数体边界找不到`).toBeGreaterThan(i)
      const block = s.slice(i, bodyEnd)
      expect(block, `${fn} 只发单键 ⇒ 整体覆盖会抹掉 ${lost}`)
        // spread 源有三种写法：`...cfg`（辅助函数局部变量）/ `...parseJson(form.x)` / `...indexing`
        .toMatch(/\.\.\.(cfg|parseJson|indexing|imageCfg|qgen|autotag|profile|extractCfg|chunking)/)
    })
  }

  it('⭐ 模型/存储字段走 saveModelField（单字段 PATCH，⛔ 不整体覆盖 JSON）', () => {
    const s = src()
    const i = s.indexOf('const saveModelField =')
    expect(i, '找不到 saveModelField').toBeGreaterThan(0)
    const block = s.slice(i, i + 600)
    // 这三个字段是**顶层列**（不是 JSON blob）⇒ 单键 PATCH 是对的，不该 spread
    expect(block).toContain('summary_model_id')
    expect(block).toContain('embedding_model_id')
    expect(block).toContain('storage_backend_id')
    expect(block).toContain('await patch({ [field]: value }')
  })

  it('⭐ 每个 wiki 写入点都必须走 withWiki（⛔ 不能有内联直写覆盖）', () => {
    const s = src()
    // 允许 `withWiki` 自身内部那一处 `JSON.stringify`
    const inline = s.match(/wiki_config:\s*JSON\.stringify\(\{/g) || []
    const constStart = s.indexOf('const withWiki =')
    const constEnd = s.indexOf('const saveWiki', constStart)
    expect(constStart, '找不到 withWiki 辅助函数').toBeGreaterThan(0)
    const helper = s.slice(constStart, constEnd)
    const inHelper = (helper.match(/JSON\.stringify\(\{/g) || []).length
    expect(inline.length, '出现了绕过 withWiki 的内联 wiki_config 整体覆盖').toBe(0)
    expect(inHelper, 'withWiki 里必须有一处 JSON.stringify（合并实现）').toBe(1)
    // 所有 wiki 写入都必须经由 saveWiki → withWiki
    const saveWikiCalls = (s.match(/saveWiki\(\{/g) || []).length
    expect(saveWikiCalls, '没有任何 saveWiki(…) 写入点 ⇒ 面板里的 wiki 开关都不会落库').toBeGreaterThan(0)
  })

  for (const [key, label, lost] of CASES.filter(([k]) => k !== 'wiki_config' && k !== 'indexing_strategy')) {
    it(`⭐「${label}」写入必须 spread 现有值（否则冲掉 ${lost}）`, () => {
      const s = src()
      const needle = `${key}: JSON.stringify({`
      const i = s.indexOf(needle)
      expect(i, `找不到 ${key} 的写入点`).toBeGreaterThan(0)
      const block = s.slice(i, i + 260)
      expect(block, `${key} 只发单键 ⇒ 整体覆盖会抹掉 ${lost}`)
        .toMatch(/\.\.\.(parseJson|indexing)/)
    })
  }
})
