import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

/**
 * [批 NUL1] 源码卫生守卫：源文件里不得出现「非法的字面控制字节」。
 *
 * <b>WHY 这条守卫必须存在</b>：源码里混入不可见控制字节是极容易复现的写入事故 ——
 * 批 NUL1 施工期间，光是我自己就在写文件路径上<b>三次</b>把 `\x7f` / `\x00` 打成了字面字节，
 * 其中一次把字面 NUL 写进了新建的测试文件。后果分轻重两档：
 * <ul>
 *   <li><b>含 NUL</b>：git 只看前 8000 字节里有没有 NUL —— 有一个，整个文件就退化成
 *       `Bin N -> M bytes`，该文件<b>永久无法 diff / review / 合并</b>（本批修的就是这个）；</li>
 *   <li><b>含其它控制字节</b>：文件仍可 diff，但控制字符在编辑器里不可见，
 *       改一处看不见的字符就能改掉正则语义，review 时无从察觉。</li>
 * </ul>
 *
 * ⭐ 判据是<b>纯字节级</b>的（直接读 Buffer 取每个字节），<b>刻意不用</b>字符串/正则匹配 ——
 * 字符串层面正是转义最容易被混淆的地方（`\x00` 六个字符与一个 NUL 字节在正则里长得两样，
 * 在字符串里却都能"匹配到"）。用字节判，绕开这一层。
 *
 * ⚠️ 本守卫拦不住什么（如实登记）：
 * <ul>
 *   <li><b>写入侧的转义吞并</b> —— 编辑器/工具链把 `\x00` 六字符还原成真 NUL 或反过来，
 *       发生在写盘之前，本守卫只能在写盘<b>之后</b>看结果，抓不到过程；</li>
 *   <li><b>本次列出的文件之外</b> —— 只扫 {@link FILES} 列到的路径。加文件是改一行的事，
 *       但不加就不扫（后端 main 下另有 2 个文件含 `0x0b`，测试下另有 9 个含 `0x02`，均未纳入）；</li>
 *   <li><b>非控制类不可见字符</b> —— 零宽空格、双向控制符、U+00A0 等不在 `b < 0x20 / 0x7f`
 *       判据内（ansi.ts 第 85 行 ZERO_WIDTH 就<b>合法地</b>使用字面零宽码点，属有意为之）。</li>
 * </ul>
 */

/** 待守卫的源文件，相对本测试文件所在目录。 */
const FILES = ['../ansi.ts']

/**
 * 判据：`b < 0x20` 且不是 Tab(0x09)/LF(0x0a)/CR(0x0d)，或 `b === 0x7f`（DEL）⇒ 命中即非法。
 * 纯字节级，返回命中处的字节偏移。
 */
function offenders(buf: Uint8Array): number[] {
  const bad: number[] = []
  for (let i = 0; i < buf.length; i += 1) {
    const b = buf[i]
    if (b === 0x7f || (b < 0x20 && b !== 0x09 && b !== 0x0a && b !== 0x0d)) bad.push(i)
  }
  return bad
}

describe('源码卫生：不得含非法字面控制字节', () => {
  it('扫描器本身有效（否则守卫会静默失效，永远绿）', () => {
    // WHY: 一条永远返回「无命中」的守卫比没有守卫更坏 —— 它给人虚假的安全感。
    // 这里正反两面都钉住：该命中要命中，Tab/LF/CR 要放过。
    expect(offenders(Buffer.from([0x41, 0x00, 0x42]))).toEqual([1])
    expect(offenders(Buffer.from([0x41, 0x1b, 0x42]))).toEqual([1])
    expect(offenders(Buffer.from([0x41, 0x7f, 0x42]))).toEqual([1])
    expect(offenders(Buffer.from([0x41, 0x09, 0x0a, 0x0d, 0x42]))).toEqual([])
    expect(offenders(Buffer.from('a中Z9', 'utf8'))).toEqual([])
  })

  for (const rel of FILES) {
    it(`${rel} 不含非法字面控制字节`, () => {
      const buf = readFileSync(fileURLToPath(new URL(rel, import.meta.url)))
      const bad = offenders(buf)
      const detail = bad
        .map(i => `offset ${i}=0x${buf[i].toString(16).padStart(2, '0')}`)
        .join(', ')
      expect(bad, `命中 ${bad.length} 处: ${detail}`).toEqual([])
    })
  }
})
