import { describe, expect, it } from 'vitest'
import { applyEdits, bulletFix, headingFix, isSepRow, passFence, passMarkers, passTable, relLines, repair, tableSplit } from '../rescue.ts'
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

describe('rescue · P2 标题规则', () => {
  it('CJK 开头、短、无黑名单 → 补空格', () => {
    expect(headingFix('##一句话结论')).toEqual({ fixed: '## 一句话结论' })
    expect(headingFix('###②审查选项')).toMatchObject({ reject: 'not-cjk-or-ordinal' })
    expect(headingFix('##1.内置工具')).toEqual({ fixed: '## 1.内置工具' })
  })

  it('负例：单 # / 拉丁开头 / 超长 / 含 ** / 含行内 ## / 句末标点', () => {
    expect(headingFix('#tag 话题')).toMatchObject({ reject: 'hash-count-1' })
    expect(headingFix('##spec 同步')).toMatchObject({ reject: 'not-cjk-or-ordinal' })
    expect(headingFix('##标题**粗体**')).toMatchObject({ reject: 'contains-**' })
    expect(headingFix('##A##B')).toMatchObject({ reject: 'contains-embedded-hash' })
    expect(headingFix('##回答：不能确认。两篇都没抽全。')).toMatchObject({ reject: 'ends-sentence-punct' })
  })

  it('负例：含竖线 / 右书名号 / 三反引号串（三条判据在真实语料上均有命中）', () => {
    expect(headingFix('##A|B')).toMatchObject({ reject: 'contains-|' })
    expect(headingFix('##A」B')).toMatchObject({ reject: 'contains-」' })
    expect(headingFix('##A```B')).toMatchObject({ reject: 'contains-fence-run' })
  })

  it('长度门边界：30 接受、31 拒绝', () => {
    expect(headingFix('##' + '很'.repeat(30))).toEqual({ fixed: '## ' + '很'.repeat(30) })
    expect(headingFix('##' + '很'.repeat(31))).toMatchObject({ reject: 'len>30' })
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
