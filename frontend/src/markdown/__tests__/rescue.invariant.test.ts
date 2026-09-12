import { describe, expect, it } from 'vitest'
import { repair, PROD_REPAIR_OPTS } from '../rescue.ts'
import { isSubsequence } from '../subsequence.ts'

/**
 * 结构不变量套件：纯字符串逻辑（不 import React、不碰 DOM），与渲染层测试分层。
 *
 * **必须与生产同参**：唯一生产调用是 `repair(text, PROD_REPAIR_OPTS)`
 * （`MarkdownText.tsx` 的 `renderSettled`；参数常量单一来源 = `rescue.ts` 的 `PROD_REPAIR_OPTS`，
 * 引用它而非复述字面量，否则生产改参数时本套件会静默跑旧配置）。此前本套件跑 `repair(t)`
 * （默认 `tableHeader: false`），生产唯一启用的 P1e 分支（`split-table-from-heading`）在这里一行都执行不到
 * —— 零覆盖。
 */
describe('[rescue] 结构不变量：repair 只做插入（输出是输入的超序列）', () => {
  // 为什么不是「数 ## 个数」：计数法会把**围栏内容里本就字面的 ##** 也算进去，而 P0 提行会
  // 改变字面量的位置与逃逸形态 → 判据假红/假绿。
  // 本不变量拦的是「删改字符」类重写（删除/替换/重排），不是「正则式重写」整体 ——
  // 当年 patches.ts 的 fixHeadings（`#include` → `# include`）恰恰是纯插入式，本判据拦不住它。
  // 完整立论见设计文档 §7 层 3。
  //
  // 分两组的意义：两组都断言「repair 是否动了源码」这个前提，只是期望相反 ——
  // A 组「应该动」（不动即红），B 组「确实不该动」（动了即红）。混在一组时，空转用例的
  // `length >=` 与「子序列」两条断言会**同时退化为构造性恒真**，白占覆盖额度；
  // 故空转输入（空串 / 纯文本这类「确实不该动」的）一律归 B 组，判据是 `out === t`
  // —— 比 A 组那两条更强，对空转输入才有判别力。
  // 「不动」类输入若已在 `rescue.test.ts` 有同型用例，此处不重复登记（重复用例的判据由
  // 该文件的 `tableSplit` / `headingFix` 负例逐条覆盖），避免同一判据既白占额度又漂移。
  const EDITED: [string, string][] = [
    ['无空格标题', '##一句话结论\n'],
    ['表格粘连', '##已压缩|批次 |内容 |\n|---|---|\n|1 |a |\n'],
    ['围栏+标题叠加', '前文```\ncode\n```\n##标题\n'],
    ['列表粘连', '##标题\n-那个配置表\n'],
    ['单 CR 结尾', '##标题\r'],
    // 以下 3 条补自质量审查实测的零覆盖缺口
    ['闭围栏提行（P0 lift-close-fence）', '```\ncode\nmore```\n'],
    ['标题容器内的表格（P1e split-table-from-heading）', '## 标题|a |b |\n|---|---|\n'],
    // 跨 pass 多编辑：applyEdits 多编辑路径上偏移错位风险最高处
    ['围栏+表格双编辑（P0 提行后再 P1 拆行）', '前文```\ncode\n```\n正文|a |b |\n|---|---|\n'],
  ]
  // B 组覆盖的是「input 类型」维度：未闭合围栏 / 干净文本 / CRLF / 空串 / 纯文本 / 围栏变体 / 已合规标题
  const UNTOUCHED: [string, string][] = [
    ['未闭合围栏（P0 平衡闸放弃）', '前文```\ncode\n'],
    ['干净文本', '# 标题\n\n正文。\n\n| a | b |\n|---|---|\n| 1 | 2 |\n'],
    ['CRLF（P2/P3 在 \\r 行上静默失效，见设计文档 §12 R6）', '##标题\r\n正文\r\n'],
    ['空串', ''],
    ['纯文本', '就是一段普通文字，没有任何标记。\n'],
    ['AAA 代码围栏（围栏行不独占行首 → P0 放弃）', 'AAA ```js\nconst a=1\n```\n'],
    ['~~~ 围栏（未建模，见设计文档 §12 R1）', '~~~js\ncode\n~~~\n'],
    ['已合规标题', '## 已合规\n'],
  ]

  for (const [name, t] of EDITED) {
    it(`A · ${name}`, () => {
      const out = repair(t, PROD_REPAIR_OPTS).out
      expect(out).not.toBe(t)                                // 前提门：本条用例确实改过源码
      expect(out.length).toBeGreaterThanOrEqual(t.length)     // 只增不减（由下行子序列判定蕴含，保留仅为失败可读性）
      expect(isSubsequence(t, out)).toBe(true)                // 原文是输出的子序列
    })
  }

  for (const [name, t] of UNTOUCHED) {
    it(`B · ${name}`, () => {
      // 断言「确实不该动」，比 A 组的「length + 子序列」两条**更强**：
      // 那两条对空转输入同时恒真，本断言对空转输入才有判别力。
      expect(repair(t, PROD_REPAIR_OPTS).out).toBe(t)
    })
  }
})
