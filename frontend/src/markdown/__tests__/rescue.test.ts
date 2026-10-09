import { describe, expect, it } from 'vitest'
import { applyEdits, bulletFix, headingFix, isSepRow, passFence, passGlue, passMarkers, passTable, relLines, repair, tableSplit, PROD_REPAIR_OPTS } from '../rescue.ts'
import { parseGfmWithMath } from '../parse.ts'

describe('rescue · 工具层', () => {
  it('isSepRow 认标准与紧凑分隔行，拒普通行', () => {
    expect(isSepRow('|---|---|')).toBe(true)
    expect(isSepRow('| --- | :---: |')).toBe(true)
    expect(isSepRow('--- | ---')).toBe(true)
    expect(isSepRow('| 端口 | 说明 |')).toBe(false)   // 无破折号
    expect(isSepRow('abc')).toBe(false)               // 无竖线
    expect(isSepRow('|---|x|')).toBe(false)           // 某格非法
  })

  it('relLines 给出每行的相对偏移', () => {
    expect(relLines('ab\ncde\n')).toEqual([
      { off: 0, line: 'ab' },
      { off: 3, line: 'cde' },
      { off: 7, line: '' },
    ])
  })

  it('applyEdits 从后往前应用，偏移互不干扰', () => {
    const src = 'abcdef'
    const out = applyEdits(src, [
      { start: 1, end: 1, text: 'X', pass: 't', kind: 'k', line: '' },
      { start: 4, end: 4, text: 'Y', pass: 't', kind: 'k', line: '' },
    ])
    expect(out).toBe('aXbcdYef')
  })

  it('applyEdits 支持替换（end > start）', () => {
    expect(applyEdits('abc', [
      { start: 0, end: 1, text: 'Z', pass: 't', kind: 'k', line: '' },
    ])).toBe('Zbc')
  })

  it('契约：同 start 的多条编辑按数组逆序落位', () => {
    const src = 'abcdef'
    expect(applyEdits(src, [
      { start: 2, end: 2, text: 'A', pass: 't', kind: 'k', line: '' },
      { start: 2, end: 2, text: 'B', pass: 't', kind: 'k', line: '' },
    ])).toBe('abBAcdef')
  })

  it('契约：编辑区间必须互不重叠（钉当前语义、无契约保证）', () => {
    // 这不是期望的用法，而是把"当前语义"钉死：谁被吞取决于重叠形状。见 applyEdits JSDoc 契约。
    expect(applyEdits('abcdef', [
      { start: 2, end: 4, text: 'X', pass: 't', kind: 'k', line: '' },
      { start: 2, end: 2, text: 'Y', pass: 't', kind: 'k', line: '' },
    ])).toBe('abYXef')
  })
})

describe('rescue · P0 围栏抢救', () => {
  it('行尾粘连的闭围栏提为独立行', () => {
    const src = '```\ncode\nmore```\n'
    const r = passFence(src)
    expect(r.aborted).toBe(false)
    expect(r.src).toBe('```\ncode\nmore\n```\n')
  })

  it('行尾粘连的开围栏也提行', () => {
    expect(passFence('前文```\ncode\n```\n').src).toBe('前文\n```\ncode\n```\n')
  })

  it('平衡闸：提行后仍不闭合 → 整篇放弃（返回原文）', () => {
    const src = '```\ncode```\nmore```\n'
    const r = passFence(src)
    expect(r.aborted).toBe(true)
    expect(r.src).toBe(src)
  })

  it('干净的围栏不动', () => {
    const src = '```js\nconst a = 1\n```\n\n正文\n'
    const r = passFence(src)
    expect(r.src).toBe(src)
    expect(r.edits).toHaveLength(0)
  })

  it('带空格的 "text ```" 不提行（保守）→ 围栏终不闭合、整篇放弃', () => {
    const src = 'text ```\ncode\n```\n'
    const r = passFence(src)
    expect(r.edits).toHaveLength(0)
    expect(r.aborted).toBe(true)
  })

  it('平衡闸：开围栏 4 反引号、粘连闭围栏仅 3 个 → 长度不足不算闭合，整篇放弃', () => {
    const src = 'P3正文````\n代码行\n```\nP6结尾\n'
    const r = passFence(src)
    expect(r.aborted).toBe(true)
    expect(r.src).toBe(src)
  })

  it('一次提交两处提行（多编辑叠加，走 applyEdits 多编辑路径）', () => {
    expect(passFence('```\na```\n\n```\nb```\n').src).toBe('```\na\n```\n\n```\nb\n```\n')
  })
})

describe('rescue · P1 表格抢救', () => {
  it('表头粘在正文行尾时，在第一个 | 前插换行', () => {
    expect(tableSplit('##已压缩|批次 |内容 |', '|---|---|')).toBe(5)
    const r = passTable('##已压缩|批次 |内容 |\n|---|---|\n|1 |a |\n')
    expect(r.src).toBe('##已压缩\n|批次 |内容 |\n|---|---|\n|1 |a |\n')
  })

  it('行首就是 | 的不动（本来就是合法表头）', () => {
    expect(tableSplit('|批次 |内容 |', '|---|---|')).toBe(-1)
    const src = '|批次 |内容 |\n|---|---|\n'
    expect(passTable(src).edits).toHaveLength(0)
  })

  it('下一行不是分隔行的不动', () => {
    expect(tableSplit('正文|含竖线', '继续正文')).toBe(-1)
  })

  it('P1e：容器是活标题时（`## 标题|a |b |`）同样救', () => {
    const src = '## 标题|a |b |\n|---|---|\n'
    expect(passTable(src, false).edits).toHaveLength(0)
    expect(passTable(src, true).src).toBe('## 标题\n|a |b |\n|---|---|\n')
  })

  it('拒绝 setext 形状的左半段（`---|a |b |`）—— 否则前文文字会被变成标题', () => {
    expect(tableSplit('---|a |b |', '|---|---|')).toBe(-1)
    expect(tableSplit('===|a |b |', '|---|---|')).toBe(-1)
    expect(tableSplit('文字|a |b |', '|---|---|')).toBe(2)   // 正常左半段不受影响
  })

  it('拒绝 ATX 空标题形状的左半段（`####|a |b |`）—— 否则凭空多一个空标题', () => {
    expect(tableSplit('####|a |b |', '|---|---|')).toBe(-1)
    expect(tableSplit('#|a |b |', '|---|---|')).toBe(-1)
  })

  it('拒绝制表符分隔的 setext/hr 形状（`---\\t|a |`）', () => {
    expect(tableSplit('---\t|a |b |', '|---|---|')).toBe(-1)
    expect(tableSplit('===\t|a |b |', '|---|---|')).toBe(-1)
  })

  it('不变量：P1 拆行后不产生新的 code 内容', () => {
    const collectCode = (s: string): string[] => {
      const out: string[] = []
      const walk = (n: any): void => {
        if (n.type === 'code') out.push(n.value)
        if (Array.isArray(n.children)) for (const c of n.children) walk(c)
      }
      for (const c of parseGfmWithMath(s).children) walk(c)
      return out.sort()
    }
    const src = '##已压缩|批次 |内容 |\n|---|---|\n|1 |a |\n'
    expect(collectCode(passTable(src).src)).toEqual(collectCode(src))
    const withCode = '```js\nconst a=1\n```\n\n正文|a |b |\n|---|---|\n'
    expect(passTable(withCode).edits).toHaveLength(1)   // 前提：确实改过源码
    expect(collectCode(passTable(withCode).src)).toEqual(collectCode(withCode))
  })

  it('左半段全空白时拒绝（前导空格 / 制表符 + 竖线）', () => {
    expect(tableSplit('  |a |b |', '|---|---|')).toBe(-1)
    expect(tableSplit('\t|a |b |', '|---|---|')).toBe(-1)
  })

  it('分隔行是全文最后一行且无尾随换行时，仍能正确取到块外下一行', () => {
    expect(passTable('## 标题|a |b |\n|---|---|', true).src).toBe('## 标题\n|a |b |\n|---|---|')
  })

  it('headings 臂下，顶层段落仍需被救（targets 拼接的段落那一半）', () => {
    expect(passTable('##已压缩|批次 |内容 |\n|---|---|\n', true).src).toBe('##已压缩\n|批次 |内容 |\n|---|---|\n')
  })

  it('切点落在行内构造内部时跳过：反引号里的竖线不是分隔点', () => {
    // 分隔行格数与表头行不匹配 → 整段仍是 paragraph，行内构造没被吸收进 table；
    // 不加守卫时会在 `a|b` 的竖线处切一刀 → inlineCode 消失、凭空多出一个单格 table。
    const src = '用 `a|b` 分隔\n|---|\n'
    const r = passTable(src)
    expect(r.edits).toHaveLength(0)
    expect(r.src).toBe(src)
  })

  it('切点落在强调内部时跳过：**粗|体** 不被劈成两半', () => {
    const src = '**粗|体** 说明\n|---|\n'
    const r = passTable(src)
    expect(r.edits).toHaveLength(0)
    expect(r.src).toBe(src)
  })
})

describe('rescue · P1e 闸门：拆完**真能成表**才拆（判据来自真解析器，非格数近似）', () => {
  // 语义级判据（顶层节点类型序列 + heading 文本），**刻意不用** rescue.invariant.test.ts 那套
  // isSubsequence + 长度不减：P1e 的失效形态恰好是「插一个字符 + 原表头行被劈开」，
  // 输出仍是输入的超序列、长度也不减 ⇒ 那两条守不住本回归。
  const topTypes = (s: string): string[] =>
    (parseGfmWithMath(s).children as unknown as { type: string }[]).map((c) => c.type)
  const firstNodeText = (s: string): string => {
    const first = parseGfmWithMath(s).children[0] as unknown as { children?: { value?: string }[] }
    return (first.children ?? []).map((c) => c.value ?? '').join('')
  }

  it('S1：`## 参数 | 说明` + 2 格分隔行 → 不拆，heading 文本仍完整是 `参数 | 说明`', () => {
    // 实测（闸门关闭时，本条断言的失败输出）：`table.edits` = [{start:6,end:6,…}]，
    // 产物 `'## 参数 \n| 说明\n|---|---|\n|a|b|\n'` —— heading 文本只剩 `参数`（`| 说明` 被劈走）
    // 且顶层序列仍是 ['heading','paragraph']（表格没成）。
    const src = '## 参数 | 说明\n|---|---|\n|a|b|\n'
    const r = repair(src, PROD_REPAIR_OPTS)
    // 闸门「保持该行原样，不做任何插入」= 抢救对该输入零改动。
    // 注：不写 `not.toContain('## 参数\n')` —— 实测失效形态是插在 `|` **之前**，
    // 拆点前那个空格留在原行尾，产物是 `'## 参数 \n| 说明…'`（带尾空格），
    // 无空格的子串判据会静默假绿。逐字节全等没有这个陷阱。
    expect(r.table.edits).toHaveLength(0)
    expect(r.out).toBe(src)
    expect(firstNodeText(r.out)).toBe('参数 | 说明')               // 标题文本一字不少
    expect(topTypes(r.out)).toEqual(['heading', 'paragraph'])      // 首块仍是 heading，未退化
  })

  it('S2：`## 核心结论 | 指标 | 说明` + 2 格分隔行 → 仍拆成表（行为不得回归）', () => {
    // 对照组：标题内 `|` 数(2) == 分隔行格数(2) → 拆完右半段真能成表。
    const src = '## 核心结论 | 指标 | 说明\n|---|---|\n| a | 1 |\n'
    const out = repair(src, PROD_REPAIR_OPTS).out
    expect(topTypes(out)).toEqual(['heading', 'table'])
    expect(firstNodeText(out)).toBe('核心结论')
  })

  // ---- 转义竖线 `\|`（GFM 里单元格内写「字面竖线」的唯一正确写法）----
  // 闸门曾用「右半段按 `|` 朴素切分的格数 == 分隔行格数」近似：真解析器认 `\|`、朴素切分不认，
  // 于是两个方向都错。以下两条分别钉住这两个方向（回归门）。
  it('R1（方向 1 · 过度拒绝）：`## 表格|指标 \\| 单位|数值` → 拆完真成表，不得被判不成表而不拆', () => {
    // 朴素口径：右半段 `指标 \| 单位|数值` 切出 3 格 vs 分隔行 2 格 ⇒ 「不成表」不拆 ⇒ 回归
    //（基线 a3ee1bb2 该输入出 `['heading','table']`；`\|` 是字面量 ⇒ 真格数 2 == 2）。
    const src = '## 表格|指标 \\| 单位|数值\n|---|---|\n'
    const r = repair(src, PROD_REPAIR_OPTS)
    expect(r.table.edits).toHaveLength(1)
    expect(topTypes(r.out)).toEqual(['heading', 'table'])   // 实测：拆后顶层真出 table
    expect(firstNodeText(r.out)).toBe('表格')
  })

  it('R2（方向 2 · 漏网）：`## 标题|a \\| b` → 真解析器只认 1 格 vs 分隔行 2 格 ⇒ 必须拦住', () => {
    // 朴素口径切出 2 格 vs 2 格 ⇒ 放行 ⇒ 拆完 `['heading','paragraph']`（劈掉表头、表格也没成）。
    // 实测：`\|` 是字面量 ⇒ 真格数 1 ≠ 2 ⇒ 真解析器判不成表。
    const src = '## 标题|a \\| b\n|---|---|\n'
    const r = repair(src, PROD_REPAIR_OPTS)
    expect(r.table.edits).toHaveLength(0)
    expect(r.out).toBe(src)                                  // 零改动（逐字节，不用子串判据）
    expect(topTypes(r.out)).toEqual(['heading', 'paragraph'])
  })

  // ---- 自构边界（各条 `top` 均为本机实测 `parseGfmWithMath(r.out)` 的顶层类型）----
  it('B1 行内代码里的竖线不是分隔点：``## 标题|`a|b`|c`` → 不拆（真解析器：3 格 vs 2 格）', () => {
    // GFM 表格行内代码**不**保护 `|`（要写字面竖线须 `\|`）⇒ 右半段 `` |`a|b`|c `` 真格数 3 ≠ 2。
    // 实测 top = ["heading","paragraph"]；拆了也成不了表（且会劈掉 heading 文本）。
    const src = '## 标题|`a|b`|c\n|---|---|\n'
    const r = repair(src, PROD_REPAIR_OPTS)
    expect(r.table.edits).toHaveLength(0)
    expect(r.out).toBe(src)
    expect(topTypes(r.out)).toEqual(['heading', 'paragraph'])
  })

  it('B2 链接 title 内的转义竖线：`|[t](u "a\\|b")|c` → 拆（真解析器：2 格）', () => {
    // 同 R1 的族（朴素口径切出 3 格 ⇒ 旧闸门过度拒绝）；真解析器认 `\|` ⇒ 2 格 == 2 格 ⇒ 成表。
    const src = '## 标题|[t](u "a\\|b")|c\n|---|---|\n'
    const r = repair(src, PROD_REPAIR_OPTS)
    expect(r.table.edits).toHaveLength(1)
    expect(topTypes(r.out)).toEqual(['heading', 'table'])   // 实测
  })

  it('B3 链接 title 内的裸竖线：`|[t](u "a|b")|c` → 不拆（真解析器：3 格）', () => {
    const src = '## 标题|[t](u "a|b")|c\n|---|---|\n'
    const r = repair(src, PROD_REPAIR_OPTS)
    expect(r.table.edits).toHaveLength(0)
    expect(r.out).toBe(src)
    expect(topTypes(r.out)).toEqual(['heading', 'paragraph'])   // 实测
  })

  it('B4 双竖线：`## 标题||a|b` → 不拆（拆出的右半段含两个竖线 → 真解析器 3 格 vs 2 格）', () => {
    // 这条同时钉住「右半段口径必须从拆点那个 `|` 开始」：批次 B 的近似用 `line.slice(idx+1)`
    // 把拆点自己的 `|` 也算掉了 ⇒ 按 `|a|b` 算 2 格 ⇒ 放行。真拆出的右半段是 `||a|b`（3 格）。
    const src = '## 标题||a|b\n|---|---|\n'
    const r = repair(src, PROD_REPAIR_OPTS)
    expect(r.table.edits).toHaveLength(0)
    expect(r.out).toBe(src)
    expect(topTypes(r.out)).toEqual(['heading', 'paragraph'])   // 实测
  })

  it('B5 单列表格：`## 标题|a` + `|---|` → 拆（真解析器：1 格 == 1 格，真成表）', () => {
    const src = '## 标题|a\n|---|\n'
    const r = repair(src, PROD_REPAIR_OPTS)
    expect(r.table.edits).toHaveLength(1)
    expect(topTypes(r.out)).toEqual(['heading', 'table'])   // 实测
  })

  it('B6 空右半段：`## 标题|` + `|---|` → 不拆（右半段只剩 `|`，真解析器判 paragraph）', () => {
    // 朴素口径：`line.slice(idx+1)` 为空 ⇒ 1 格 == 1 格 ⇒ 放行；真解析器 `'|\n|---|\n'` 是 paragraph。
    const src = '## 标题|\n|---|\n'
    const r = repair(src, PROD_REPAIR_OPTS)
    expect(r.table.edits).toHaveLength(0)
    expect(r.out).toBe(src)
    expect(topTypes(r.out)).toEqual(['heading', 'paragraph'])   // 实测
  })
})

describe('rescue · P1.5 拆行抢救（判据来自全库评估原型；语料行逐字节取自真库）', () => {
  it('表格：行尾 | 且 body 含 | → 首个 | 前拆（拆出的标题段同时补空格）', () => {
    // 真库 msg 92df5bf1 原行（其下一行是 3 格分隔行）
    expect(passGlue('##全部完成 ✅|任务 |状态 |验证 |\n').src)
      .toBe('## 全部完成 ✅\n|任务 |状态 |验证 |\n')
  })

  it('表格 + 分隔行：整套 repair 后仍是 heading + table（P1 先接走；两处拆点相同）', () => {
    const src = '##全部完成 ✅|任务 |状态 |验证 |\n|---|---|---|\n| 打包 | 完成 | ✅ |\n'
    const r = repair(src, PROD_REPAIR_OPTS)
    expect(r.out).toBe('## 全部完成 ✅\n|任务 |状态 |验证 |\n|---|---|---|\n| 打包 | 完成 | ✅ |\n')
    expect(parseGfmWithMath(r.out).children.map((c) => c.type)).toEqual(['heading', 'table'])   // 拆完真成表（P1 闸门）
  })

  it('围栏：``` 前拆（真库 msg 4af6f64e 原行）', () => {
    expect(passGlue('##路径（请核对）```\n').src).toBe('## 路径（请核对）\n```\n')
  })

  it('行内第二标题号：# 后不跟字母数字 → 拆，且两段各自补空格（真库 msg 399f9238 原行）', () => {
    expect(passGlue('##待讨论的6个点###①鉴权（硬阻塞，必须解决）\n').src)
      .toBe('## 待讨论的6个点\n### ①鉴权（硬阻塞，必须解决）\n')
  })

  it('数字列表：1. 前拆（真库 msg e386b363 原行）', () => {
    const src = '##已完成1. ✅ **任务1+2（docx预览修复）**——审查通过（"准备好合并：是"，无严重项）\n'
    expect(passGlue(src).src)
      .toBe('## 已完成\n1. ✅ **任务1+2（docx预览修复）**——审查通过（"准备好合并：是"，无严重项）\n')
  })

  it('连字符列表：- 前拆（真库 msg 728c9d4f 原行）', () => {
    const src = '##对你场景（本地文件 + Tauri桌面）的过滤结论- ❌ **微软 Office Web Viewer**：强制 `?src=`公网 URL，`file://`和内网路径直接报错 → **本地场景排除**\n'
    expect(passGlue(src).src)
      .toBe('## 对你场景（本地文件 + Tauri桌面）的过滤结论\n- ❌ **微软 Office Web Viewer**：强制 `?src=`公网 URL，`file://`和内网路径直接报错 → **本地场景排除**\n')
  })

  it('单井号 + 围栏拆：补空格必须打在**段**上（整行判据会被行尾 ``` 拒绝）（真库 msg 6e81b188 原行）', () => {
    expect(repair('#二、生产阶段：6步，谁产生什么```\n', PROD_REPAIR_OPTS).out)
      .toBe('# 二、生产阶段：6步，谁产生什么\n```\n')
  })
})

describe('rescue · P1.5 误拆防护（原型约束：不拆不该拆的）', () => {
  it('颜色值 `#FF7A45`（# 后跟字母数字）不是第二个标题号 → 不拆', () => {
    const short = '##这版基准 `#FF7A45`/`#F97316` 亮橙\n'
    expect(passGlue(short).src).toBe(short)
    // 真库 msg 36e9e71b 原行（长、含 ** 与多处颜色值）
    const real = '##这版的基准是你给的文件**视觉100%沿用你的 `场景选择中心-mock.html`**：`#FF7A45`/`#F97316`亮橙、`#FAF7F1`米白侧栏、`#FDFBF7`画布、三列场景卡、圆角14px、hover上浮。我没有自造任何配色。\n'
    expect(passGlue(real).src).toBe(real)
  })

  it('编号标题 `##2. MCP：…`（数字在 rest 起始位）不是数字列表 → 不拆，但补空格', () => {
    const src = '##2. MCP：`nexusai-in-chrome`（浏览器自动化）\n'
    expect(passGlue(src).src).toBe(src)
    expect(repair(src, PROD_REPAIR_OPTS).out).toBe('## 2. MCP：`nexusai-in-chrome`（浏览器自动化）\n')
  })

  it('行内代码里的 `1. ` 不是拆点（inCodeBefore 判据）', () => {
    const src = '##用法 `a1. b` 说明\n'
    expect(passGlue(src).src).toBe(src)
    expect(repair(src, PROD_REPAIR_OPTS).out).toBe('## 用法 `a1. b` 说明\n')
  })

  it('拆点落在井号正后（`####|a |b |` / `#|a |b |` / `####``` `）→ 不拆（否则拆出 ATX 空标题；与 P1 tableSplit 同款闸）', () => {
    expect(passGlue('####|a |b |\n').src).toBe('####|a |b |\n')
    expect(passGlue('#|a |b |\n').src).toBe('#|a |b |\n')
    expect(passGlue('####```\n').src).toBe('####```\n')
    expect(repair('####|a |b |\n', PROD_REPAIR_OPTS).out).toBe('####|a |b |\n')
  })

  it('围栏内的 `#` 行一律不动（顶层段落判据天然排除，不是裸文本扫描）', () => {
    const src = '```\n# 这是代码注释\n##include <x>\n```\n'
    expect(repair(src, PROD_REPAIR_OPTS).out).toBe(src)
  })

  it('已知残差（登记待裁定）：P0 判整篇不闭合而放弃时，拆出的 ``` 仍会吞后文', () => {
    // ⚠️ 断言的是**当前行为**（原型拆行无平衡闸，P0 的平衡闸只作用于它自己的提行），不是期望行为。
    // 真库 msg 6e81b188：`#二、生产阶段…``` ` 的下一行就是 `① docx_chunker.py …`
    const src = '#二、生产阶段：6步，谁产生什么```\n① docx_chunker.py ←把 Word切成一"块块"\n'
    const r = repair(src, PROD_REPAIR_OPTS)
    expect(r.out).toBe('# 二、生产阶段：6步，谁产生什么\n```\n① docx_chunker.py ←把 Word切成一"块块"\n')
    expect(parseGfmWithMath(r.out).children.map((c) => c.type)).toEqual(['heading', 'code'])
  })
})

describe('rescue · P1.5 与下游树的对应（拆行改文本 ⇒ 旧树必须作废）', () => {
  it('拆行 + 多段：P1.5 有编辑时必须弃用 P1 的树（与逐 pass 各 parse 一次的参照链逐字节相同）', () => {
    // 真库 msg 399f9238 第 1 行（要拆）+ 段内后续行 + 末行（P3 的 `-CJK` 靶子）：
    // 拆行把末行的位置整体后移 ⇒ 复用拆行前的树会拿旧 position 切出「`-`」这种半截行。
    const src = '##待讨论的6个点###①鉴权（硬阻塞，必须解决）\n继续行\n-配置表\n'
    const p1 = passTable(passFence(src).src, PROD_REPAIR_OPTS.tableHeader)
    const ps = passGlue(p1.src, p1.root)
    // 参照链：passMarkers 自己 parse 拆后的文本（不复用任何旧树）
    const ref = passMarkers(ps.src).src
    expect(repair(src, PROD_REPAIR_OPTS).out).toBe(ref)
    expect(ref).toBe('## 待讨论的6个点\n### ①鉴权（硬阻塞，必须解决）\n继续行\n- 配置表\n')
    // 前提门 1：本条确实走「P1.5 改了文本、P0/P1 没改」这条路径（旧短路条件成立、新条件不成立）
    expect(passFence(src).src).toBe(src)
    expect(p1.edits).toHaveLength(0)
    expect(ps.edits.length).toBeGreaterThan(0)
    // 前提门 2（反面对照，须可达）：拿拆行前的树配拆行后的文本 → 结果**必须**不同。
    // 否则本用例对「复用旧树」这件事无判别力（变异验证：把 reuseRoot 的 ps 条件去掉，本条必须转红）。
    expect(passMarkers(ps.src, p1.root).src).not.toBe(ref)
  })
})

describe('rescue · P2 标题规则（判据 = 原型 healHeading，2026-10-09 放宽）', () => {
  it('CJK 开头、短 → 补空格（编号标题同样补：数字起始只挡拆行，不挡补空格）', () => {
    expect(headingFix('##一句话结论')).toEqual({ fixed: '## 一句话结论' })
    expect(headingFix('##1.内置工具')).toEqual({ fixed: '## 1.内置工具' })
  })

  it('放宽：旧四道限制误杀的真标题一律补空格（真库语料逐字节）', () => {
    // 单井号（旧 `hash-count-1` 拒绝；`#tag 话题` 一并放行 —— 与 `#五、…` 同族，判据不区分拉丁/中文）
    expect(headingFix('#五、完整的"来源→被谁读→得到什么"')).toEqual({ fixed: '# 五、完整的"来源→被谁读→得到什么"' })
    expect(headingFix('#tag 话题')).toEqual({ fixed: '# tag 话题' })
    // 首字符非 CJK、非编号（旧 `not-cjk-or-ordinal` 拒绝）
    expect(headingFix('##★一个遗留待你拍')).toEqual({ fixed: '## ★一个遗留待你拍' })
    expect(headingFix('##spec 同步')).toEqual({ fixed: '## spec 同步' })
    // 含右书名号（旧 `contains-」` 拒绝）
    expect(headingFix('##A」B')).toEqual({ fixed: '## A」B' })
    // 含双星号（旧 `contains-**` 拒绝）
    expect(headingFix('##标题**粗体**')).toEqual({ fixed: '## 标题**粗体**' })
    // `？` 结尾（旧 `ends-sentence-punct` 拒绝）
    expect(headingFix('###②审查选项要不要暴露给用户？')).toEqual({ fixed: '### ②审查选项要不要暴露给用户？' })
    // 超长（旧 `len>30` 拒绝）
    expect(headingFix('##' + '很'.repeat(31))).toEqual({ fixed: '## ' + '很'.repeat(31) })
    // 长 + 双星 + 右书名号三者同时命中（真库 msg d83e48d8 原行，旧判据三道限制全挂）
    expect(headingFix('##你问的「⑧是AI还是机器」——**机器，纯代码，一句AI判断都没有**'))
      .toEqual({ fixed: '## 你问的「⑧是AI还是机器」——**机器，纯代码，一句AI判断都没有**' })
    // `：`/`；` 结尾（旧 `ends-sentence-punct` 拒绝；2026-10-09 用户裁定改为治）
    expect(headingFix('##结果：**还没完**——硬强制那个执行者**仍在跑**，但它**已落盘的部分我看过了、方向全对**：'))
      .toEqual({ fixed: '## 结果：**还没完**——硬强制那个执行者**仍在跑**，但它**已落盘的部分我看过了、方向全对**：' })
    expect(headingFix('##待你决策HTML功能在独立分支上，有三种整合方式：'))
      .toEqual({ fixed: '## 待你决策HTML功能在独立分支上，有三种整合方式：' })
    // 端到端（repair 链）同样治 —— 这两条走 P2 补空格臂（真库 msg 0ab3b675 原行）
    expect(repair('##结果：**还没完**——硬强制那个执行者**仍在跑**，但它**已落盘的部分我看过了、方向全对**：\n', PROD_REPAIR_OPTS).out)
      .toBe('## 结果：**还没完**——硬强制那个执行者**仍在跑**，但它**已落盘的部分我看过了、方向全对**：\n')
  })

  it('负例（三类拒绝）：单井号+数字 / 含句号 / 残余粘连', () => {
    // `#6` 是引用编号，不是标题（真库 msg 47a87117 原行）
    expect(headingFix('#6确实已改（L862 `tool_check_progress`，L867注释写明"显式列出未覆盖chunk"）。读完整实现确认四项真实状态。'))
      .toMatchObject({ reject: 'single-hash-digit' })
    // 含句号 → 像正文（真库 msg 5bb30c40 原行；句号不在结尾也算）
    expect(headingFix('##环境：已具备`docker29.4.3`（WSL2 backend running）、`python3.14.4`、`uv0.11.7`、`git`都在。可以跑。（风险点：Python3.14很新，terminal-bench可能有兼容问题，必要时用3.12。）'))
      .toMatchObject({ reject: 'contains-。' })
    expect(headingFix('##回答：不能确认。两篇都没抽全。')).toMatchObject({ reject: 'contains-。' })
    // 残余粘连三道（该由 P1/P1.5 先拆开再判）
    expect(headingFix('##A|B')).toMatchObject({ reject: 'contains-|' })
    expect(headingFix('##A##B')).toMatchObject({ reject: 'contains-embedded-hash' })
    expect(headingFix('##A```B')).toMatchObject({ reject: 'contains-fence-run' })
  })

  it('带空格的合法标题不动（无 marker）', () => {
    expect(headingFix('## 已合规')).toMatchObject({ reject: 'no-marker' })
  })
})

describe('rescue · P3 列表规则', () => {
  it('- 后紧跟 CJK → 补空格', () => {
    expect(bulletFix('-那个配置表')).toEqual({ fixed: '- 那个配置表' })
  })
  it('负例：- 后非 CJK 不动', () => {
    expect(bulletFix('-9/9轮：')).toMatchObject({ reject: 'not-cjk' })
    expect(bulletFix('-27个 M')).toMatchObject({ reject: 'not-cjk' })
  })

  it('bulletFix：非 - 开头的行返回 no-marker', () => {
    expect(bulletFix('普通正文')).toMatchObject({ reject: 'no-marker' })
  })
})

describe('rescue · P2/P3 只作用在段落内部的行首，且不吞后续行', () => {
  it('标题只取该行：同段落后续正文保持独立', () => {
    const src = '##一句话结论\n后续正文没有空行\n'
    expect(passMarkers(src).src).toBe('## 一句话结论\n后续正文没有空行\n')
  })
  it('行中的 ## 不受影响（无 marker）', () => {
    const src = '前文提到 ##A##B 这种写法\n'
    expect(passMarkers(src).edits).toHaveLength(0)
  })
})

describe('rescue · rejects 诊断（看到了但不处理，仅供调参/日志）', () => {
  it('rejects 记录两类「看到了但不处理」：判据拒绝 vs 设计有意不做', () => {
    const r = passMarkers('1.内容\n-9/9轮：\n')
    expect(r.rejects).toEqual([
      { pass: 'ordered', reason: 'out-of-scope', line: '1.内容', lineIndex: 0 },
      { pass: 'bullet', reason: 'not-cjk', line: '-9/9轮：', lineIndex: 1 },
    ])
  })
})

describe('rescue · repair 编排', () => {
  it('顺序 P0 → P1 → P2，且各 pass 结果叠加', () => {
    const src = '##一句话结论|a |b |\n|---|---|\n```\ncode\n'
    const r = repair(src)
    expect(r.out).toBe('## 一句话结论\n|a |b |\n|---|---|\n```\ncode\n')
  })

  it('未闭合围栏被平衡闸挡住时，其余 pass 照常工作', () => {
    const src = '##标题\n正文```\n'
    const r = repair(src)
    expect(r.out).toBe('## 标题\n正文```\n')
  })

  it('幂等：对已修补文本再跑一次不变', () => {
    const once = repair('##一句话结论\n').out
    expect(repair(once).out).toBe(once)
  })

  it('干净文本零改动', () => {
    const clean = '# 标题\n\n正文段落。\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n- 列表项\n'
    const r = repair(clean)
    expect(r.out).toBe(clean)
    expect(r.fence.edits).toHaveLength(0)
    expect(r.table.edits).toHaveLength(0)
    expect(r.markers.edits).toHaveLength(0)
  })

  it('P0 成功提行后，P1/P2 的偏移仍落在各自输入上（跨 pass 偏移回归门）', () => {
    const src = '前文```\ncode\n```\n##标题\n'
    expect(passFence(src).aborted).toBe(false)          // 前提：P0 真的改了文本
    const r = repair(src)
    expect(r.out).toBe('前文\n```\ncode\n```\n## 标题\n')
    expect(r.fence.edits.map((e) => [e.pass, e.kind])).toEqual([['fence', 'lift-open-fence']])
    expect(r.markers.edits.map((e) => [e.pass, e.kind])).toEqual([['marker', 'heading']])
  })

  it('复用 P1 解析树的短路不改输出（与逐 pass 各 parse 一次逐字节相同）', () => {
    const src = '##一句话结论\n正文\n'
    const r = repair(src)
    // 前提：短路条件成立（P0 未改文本、P1 无编辑），P1 的树被带出来了
    expect(r.fence.src).toBe(src)
    expect(r.table.edits).toHaveLength(0)
    // 对照：不给 root，passMarkers 自己 parse 一次 —— 结果必须逐字节相同
    expect(r.out).toBe(passMarkers(passTable(passFence(src).src).src).src)
    expect(r.markers.rejects).toEqual(passMarkers(r.table.src).rejects)
  })

  // 与上一条配对：上一条钉「短路条件成立」的臂，本条钉「条件不成立」的臂 ——
  // 上一条的输入恰好让条件成立，故 else 分支（复用被跳过）曾零覆盖。
  it('M3 短路：p1.edits.length > 0 时不复用 P1 的树（有判别力的差分）', () => {
    const src = '正文|a |b |\n|---|---|\n##标题\n'
    const r = repair(src)
    // 逐 pass 参照链（不复用任何树的等价实现）
    const ref = passMarkers(passTable(passFence(src).src).src).src
    expect(r.out).toBe(ref)
    expect(r.out).toBe('正文\n|a |b |\n|---|---|\n##标题\n')
  })
})
