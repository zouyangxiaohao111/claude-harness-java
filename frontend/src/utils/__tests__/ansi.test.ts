import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { parseAnsiLines } from '../ansi'

/**
 * [批 NUL1] ansi.ts 第 73 行 INERT_CONTROL 的字符类必须以 `\xNN` 转义书写。
 *
 * <b>WHY 这组断言必须存在</b>：该正则原先把控制字符写成**字面控制字节**（其中一个是 NUL）。
 * git 只看前 8000 字节里有没有 NUL —— 一旦有，整个文件被判为二进制，`git diff` 只肯显示
 * `Bin N -> M bytes`，这个文件便**永久无法 diff / review / 合并**。
 * 改成转义后，**被剔除的字符集合一个都不能变**：多删一个字符会让终端回放静默丢字，
 * 少删一个则会把不可见控制符渲染进 DOM（文件头注释第 4 行的既定承诺）。
 *
 * 因此这里断言的是**「剔除谁 / 保留谁」这一行为**，而不是「正则字符串长什么样」——
 * 任何一处转义写错（例如把 `\x0b` 写成 `\x0c`）都会直接改变剔除行为，本文件即变红。
 * 另附两条结构守卫，钉住「文件里不再有 NUL」与「字符类以转义书写」。
 */

/** 走公开 API 的真实投影路径：单行输入 → 该行所有 span 拼接后的纯文本。 */
function project(input: string): string {
  return parseAnsiLines(input)[0].map(span => span.text).join('')
}

describe('INERT_CONTROL：无显示意义的 C0 / DEL 必须被剔除', () => {
  it('0x00-0x07 段：NUL 与 BEL 被剔除', () => {
    for (const cp of [0x00, 0x01, 0x07]) {
      expect(project(`a${String.fromCharCode(cp)}b`), `0x${cp.toString(16)} 应被剔除`).toBe('ab')
    }
  })

  it('0x0b-0x1a 段：VT / FF / SUB 被剔除', () => {
    for (const cp of [0x0b, 0x0c, 0x1a]) {
      expect(project(`a${String.fromCharCode(cp)}b`), `0x${cp.toString(16)} 应被剔除`).toBe('ab')
    }
  })

  it('0x1c-0x1f 段：FS / GS / RS / US 被剔除', () => {
    for (const cp of [0x1c, 0x1d, 0x1e, 0x1f]) {
      expect(project(`a${String.fromCharCode(cp)}b`), `0x${cp.toString(16)} 应被剔除`).toBe('ab')
    }
  })

  it('0x7f（DEL）被剔除', () => {
    expect(project('a\u007fb')).toBe('ab')
  })
})

describe('INERT_CONTROL：有意义的字符一个都不能误删', () => {
  it('Tab / 空格 / ~ 保留（区间下界必须停在 0x0b，否则会连 Tab 一起吞掉）', () => {
    expect(project('a\tb')).toBe('a\tb')
    expect(project('a b')).toBe('a b')
    expect(project('a~b')).toBe('a~b')
  })

  it('回车走行回放而不是被当惰性字符剔除（100%\\rOK 须渲染成 OK0%）', () => {
    // WHY: \r 属于 NEEDS_REPLAY 的列缓冲回放路径，语义上**不是**无显示字符；
    // 若被 INERT_CONTROL 收走，终端覆盖写会整段丢失。
    expect(project('abc\rX')).toBe('Xbc')
    expect(project('100%\rOK')).toBe('OK0%')
  })

  it('拉丁字母 / 数字 / 汉字原样保留', () => {
    expect(project('aZ9中')).toBe('aZ9中')
  })
})

describe('结构守卫：INERT_CONTROL 的转义写法', () => {
  const src = readFileSync(fileURLToPath(new URL('../ansi.ts', import.meta.url)), 'utf8')

  it('INERT_CONTROL 的字符类以 \\xNN 转义书写', () => {
    expect(src).toContain('const INERT_CONTROL = /[\\x00-\\x07\\x0b-\\x1a\\x1c-\\x1f\\x7f]/g')
  })
})
