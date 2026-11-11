import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

/**
 * 知识库设置 · 图谱与「全局默认模型」回落（2026-11-11 用户裁定 A）。
 *
 * <h2>为什么要有这一组</h2>
 * 用户反馈「想开启知识图谱，结果提示我去看文档」，排查后发现是两个互相独立的问题：
 *
 * <ol>
 *   <li><b>误导的文档入口</b>：界面上「如何启用知识图谱？」按钮指向源站文档
 *       （讲的是装 Neo4j）。本仓用 SQLite 两张表，不需要外部图数据库 ⇒ 该按钮必须删掉，
 *       否则会把用户引到一份与实现无关的文档上。</li>
 *   <li><b>真正的卡点</b>：{@code KbPostProcessConfig.graphEnabled()} 是三重合取
 *       （两个开关 ∧ 有 LLM 模型）。而设置里「LLM 大语言模型」只写了占位文案
 *       「跟随全局默认」，后端却**没有**这个回落 ⇒ 未选模型的知识库永远不抽图，
 *       且零日志零报错。修法是 resolve 支持 fallbackChatModel。</li>
 * </ol>
 *
 * <p>守三件事：① 误导按钮已删；② 界面明确写出「空 ⇒ 全局默认」；③ 后端真的有回落。
 */

const MODAL = fileURLToPath(new URL('../KbSettingsModal.tsx', import.meta.url))
const CONFIG = fileURLToPath(new URL(
  '../../../../../backend/src/main/java/com/nexusai/kb/app/ingest/KbPostProcessConfig.java',
  import.meta.url))
const STAGE = fileURLToPath(new URL(
  '../../../../../backend/src/main/java/com/nexusai/kb/app/ingest/KbPostProcessStage.java',
  import.meta.url))

function read(p: string): string {
  return readFileSync(p, 'utf8').replace(/\r\n?/g, '\n')
}

/**
 * 剥掉块注释与行注释，只看**真正会被渲染**的代码。
 *
 * <p>⚠️ 必须剥：我在删除按钮的位置留了说明「为什么删、原来指向哪份文档」的注释，
 * 里面会原样出现「如何启用知识图谱」这些被禁的字面量 ⇒ 不剥就是假红。
 * 反过来，判「界面上还有没有这句话」也必须以渲染代码为准 —— 注释里写着不算。
 */
function code(p: string): string {
  return read(p)
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\/\/.*$/gm, '')
}

describe('图谱分区 · 不再指向源站文档', () => {
  it('⛔ 不得保留「如何启用知识图谱？」按钮（指向装 Neo4j 的文档，与我们实现无关）', () => {
    const s = code(MODAL)
    expect(s, '该按钮会把用户引到一份讲「装 Neo4j」的文档上').not.toContain('如何启用知识图谱')
  })

  it('⛔ 界面不得再出现「知识图谱数据库未启用」的误导文案', () => {
    const s = code(MODAL)
    expect(s).not.toContain('知识图谱数据库未启用')
  })

  it('⭐ 必须说明「图谱数据在本地、不需额外装图数据库」', () => {
    const s = read(MODAL)
    expect(s, '用户需要知道不用装 Neo4j').toContain('不需要额外安装图数据库')
  })

  it('⭐ 三个条件都要写出来（两个开关 + 有 LLM 模型），不能只说两个', () => {
    const s = code(MODAL)
    const i = s.indexOf('实体-关系提取')
    expect(i).toBeGreaterThan(0)
    const block = s.slice(i, i + 900)
    expect(block, '漏掉「配了 LLM 模型」这个条件 ⇒ 用户开了却不抽且查不到原因')
      .toMatch(/LLM 模型|全局默认模型/)
  })
})

describe('「空 ⇒ 使用全局默认」必须名副其实', () => {
  it('⭐ 界面必须显式写出这条规则（不能只靠下拉占位文字）', () => {
    const s = code(MODAL)
    expect(s, '缺「留空时自动使用全局默认模型」的说明').toContain('留空时')
    expect(s, '必须点明全局默认来自「主模型」档位').toContain('主模型')
  })

  it('⭐⭐ 后端 resolve 必须支持 fallbackChatModel 回落（否则那句文案是空话）', () => {
    const s = read(CONFIG)
    expect(s, '缺 resolve(kb, fallbackChatModel) 重载').toContain('String fallbackChatModel')
    // 回落必须发生在「知识库值为空」时，并且赋值给 summaryModel
    expect(s).toMatch(/summaryModel\.isEmpty\(\)\s*&&\s*fallbackChatModel/)
  })

  it('⭐⭐ 派发侧必须真的把全局默认传进去（只加参数不接线 = 白做）', () => {
    const s = read(STAGE)
    expect(s, 'resolve 调用必须带 fallback').toMatch(/KbPostProcessConfig\.resolve\(kb,\s*globalChatModel\(\)\)/)
    expect(s, '必须有 settings.main_model_name 的读取点').toContain('getMainModelName')
  })

  it('⭐⭐ 另外三个同源服务（摘要/问题生成/自动打标）也要接回落', () => {
    const base = '../../../../../backend/src/main/java/com/nexusai/kb/app/ingest/'
    for (const f of ['KbSummaryService.java', 'KbQuestionGenerationService.java', 'KbAutoTagService.java']) {
      const p = fileURLToPath(new URL(base + f, import.meta.url))
      const s = read(p)
      expect(s, `${f} 未接全局默认回落 ⇒ 该功能仍会因空模型静默失效`)
        .toContain('getMainModelName')
    }
  })
})
