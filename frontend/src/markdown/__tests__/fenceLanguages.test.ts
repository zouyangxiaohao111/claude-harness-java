import { describe, expect, it } from 'vitest'
import { FENCE_LANGS } from '../fenceLanguages.ts'

/**
 * 词表不变量 —— 这张表是「info-string 粘连」判据的切点定位依据，出错会**静默误拆**。
 *
 * <p>为什么需要这组测试：词表按可读性分组书写、导出时才排序，很容易在后续扩充时
 * 引入重复、空串、或破坏「最长前缀优先」的降序约束。这些错误**都不会报编译错**，
 * 只会在某条真实输入上把切点放错位置（例如 `sh` 排在 `shell` 前面 ⇒
 * ` ```shellfoo ` 被切成 `sh` + `ellfoo`）。
 */

describe('围栏语言词表 · 不变量', () => {
  it('导出列表按长度降序（最长前缀优先是切点正确性的前提）', () => {
    for (let i = 1; i < FENCE_LANGS.length; i++) {
      expect(
        FENCE_LANGS[i - 1]!.length,
        `第 ${i - 1} 项 "${FENCE_LANGS[i - 1]}" 比第 ${i} 项 "${FENCE_LANGS[i]}" 短，降序被破坏`,
      ).toBeGreaterThanOrEqual(FENCE_LANGS[i]!.length)
    }
  })

  it('无重复条目', () => {
    const seen = new Set<string>()
    const dup: string[] = []
    for (const l of FENCE_LANGS) {
      if (seen.has(l)) dup.push(l)
      seen.add(l)
    }
    expect(dup, `重复条目：${dup.join(', ')}`).toEqual([])
  })

  it('无空串、无首尾空白', () => {
    for (const l of FENCE_LANGS) {
      expect(l.length, '词表不得含空串（空串会匹配任何 info string ⇒ 切点恒在 0）').toBeGreaterThan(0)
      expect(l, `"${l}" 含首尾空白`).toBe(l.trim())
    }
  })

  it('不含通用词（会在正常正文里造成误判）', () => {
    // ⛔ 见 fenceLanguages.ts 维护规则第 1 条
    for (const bad of ['text', 'plain', 'code', 'string', 'number']) {
      expect(FENCE_LANGS, `不应收录通用词 "${bad}"`).not.toContain(bad)
    }
  })

  it('⭐ 前缀关系必须由长度降序解决：长词一定排在它的短前缀之前', () => {
    // 抽几个「一长一短、短的恰是长的前缀」的真实对，逐对断言
    const pairs: Array<[long: string, short: string]> = [
      ['shell', 'sh'],
      ['python', 'py'],
      ['typescript', 'ts'],
      ['javascript', 'js'],
      ['postgresql', 'postgres'],
      ['plantuml', 'plant'],
    ]
    for (const [long, short] of pairs) {
      const iLong = FENCE_LANGS.indexOf(long)
      const iShort = FENCE_LANGS.indexOf(short)
      if (iLong < 0 || iShort < 0) continue // 词表未收录则该对不适用
      expect(iLong, `"${long}" 必须排在 "${short}" 之前`).toBeLessThan(iShort)
    }
  })

  it('常见语言确实在表里（防止有人删表删过头）', () => {
    for (const l of ['tsx', 'ts', 'js', 'javascript', 'typescript', 'python', 'py',
      'bash', 'sh', 'shell', 'json', 'yaml', 'sql', 'java', 'go', 'rust', 'css', 'html']) {
      expect(FENCE_LANGS, `常见语言 "${l}" 缺失`).toContain(l)
    }
  })

  /**
   * ⭐⭐ 最典型的词表失效形态：**短词吃掉长词**。
   *
   * <p>实测打红过：删掉 `c++` 后 ` ```c++ ` 被 `c` 命中 ⇒ 切成 `c` + `++`，
   * 好好的 C++ 代码块变成「语言 c，首行 ++」。
   * 本用例对「表里已有的单/双字符短词」逐个检查：是否存在一个**更长**的合法语言名
   * 以它为前缀、却不在表里 —— 那种情况下短词会抢先命中。
   */
  it('⭐ 不得出现「短词吃掉长词」：存在的短前缀词，其常见长写也必须收录', () => {
    /** 短前缀 → 必须同时收录的长写（缺一即可能被短词截断） */
    const mustPair: Record<string, string[]> = {
      c: ['c++', 'c#', 'cs', 'csharp', 'css', 'coffeescript', 'cobol', 'clojure'],
      r: ['rs', 'rb', 'rst', 'racket'],
      go: ['golang'],
      js: ['json', 'json5', 'jsx', 'javascript'],
      ts: ['tsx', 'tsv', 'tsql', 'typescript'],
      py: ['py3', 'python', 'pyx'],
      sh: ['shell', 'shellscript', 'sql', 'scss', 'svg', 'svelte', 'solidity', 'stylus'],
      md: ['mdx', 'markdown'],
      cs: ['csharp', 'c#'],
      svg: ['svg'],
    }
    for (const [short, longs] of Object.entries(mustPair)) {
      if (!FENCE_LANGS.includes(short)) continue // 短词本身不在表里则不适用
      for (const long of longs) {
        if (long === short) continue
        expect(
          FENCE_LANGS,
          `"${short}" 在表里但 "${long}" 缺失 ⇒ \`\`\`${long} 会被截断成 "${short}" + "${long.slice(short.length)}"`,
        ).toContain(long)
      }
    }
  })
})
