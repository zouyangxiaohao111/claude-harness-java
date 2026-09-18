import { createHash } from 'node:crypto'
import { describe, expect, it } from 'vitest'
import { md5Hex } from '../md5'

/**
 * 手写 MD5 的正确性守卫 —— **不是自证**，两层独立证据：
 * <ol>
 *   <li><b>RFC 1321 附录 A.5 公布向量</b>（7 条）：钉住「这是 MD5，不是别的哈希」；</li>
 *   <li><b>与 Node `crypto.createHash('md5')` 逐字节差分</b>：钉住「在任意输入上都对」——
 *       含 55/56/57 与 63/64/65 这些**填充与分块边界**（手写实现最容易错的地方：
 *       长度字节写错位置、多算/少算一整块、把位长当字节长）。</li>
 * </ol>
 *
 * <b>WHY 两条都要</b>：只留公布向量 ⇒ 7 条固定输入全对也可能从第 2 块起就崩；
 * 只留差分 ⇒ 差分本身不说明「这是 MD5」（且若两侧同错则无人发现）。
 * 差分用 `node:crypto`（本测试跑在 node 环境，`sourceHygiene.test.ts` 已有 `node:fs` 先例）。
 */

const utf8 = (s: string) => new Uint8Array(Buffer.from(s, 'utf8'))
const nodeMd5 = (bytes: Uint8Array) => createHash('md5').update(Buffer.from(bytes)).digest('hex')

describe('md5Hex · RFC 1321 附录 A.5 公布向量', () => {
  const VECTORS: [string, string][] = [
    ['', 'd41d8cd98f00b204e9800998ecf8427e'],
    ['a', '0cc175b9c0f1b6a831c399e269772661'],
    ['abc', '900150983cd24fb0d6963f7d28e17f72'],
    ['message digest', 'f96b697d7cb7938d525a2f31aaf161d0'],
    ['abcdefghijklmnopqrstuvwxyz', 'c3fcd3d76192e4007dfb496cca67e13b'],
    ['ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789', 'd174ab98d277d9f5a5611c2c9f419d9f'],
    ['12345678901234567890123456789012345678901234567890123456789012345678901234567890', '57edf4a22be3c955ac49da2e2107b67a'],
  ]

  for (const [text, want] of VECTORS) {
    it(`${JSON.stringify(text).slice(0, 30)} → ${want}`, () => {
      expect(md5Hex(utf8(text))).toBe(want)
    })
  }
})

describe('md5Hex · 与 node:crypto 差分（含填充/分块边界）', () => {
  /** 55/56/57 = 单块内填充刚好够 / 差一个 / 跨块；63/64/65 = 恰好一整块 / 多一块 / 跨两块。 */
  const BOUNDARY_LENGTHS = [0, 1, 54, 55, 56, 57, 63, 64, 65, 118, 119, 120, 127, 128, 129, 1000]

  for (const len of BOUNDARY_LENGTHS) {
    it(`${len} 字节（内容 = 递增字节）`, () => {
      const bytes = new Uint8Array(len)
      for (let i = 0; i < len; i += 1) bytes[i] = i & 0xff
      expect(md5Hex(bytes)).toBe(nodeMd5(bytes))
    })
  }

  it('全 0x00 / 全 0xff 输入（位取反与符号位最容易踩的两个极端）', () => {
    for (const len of [55, 56, 64, 65, 200]) {
      for (const filler of [0x00, 0xff]) {
        const bytes = new Uint8Array(len).fill(filler)
        expect(md5Hex(bytes), `len=${len} filler=${filler}`).toBe(nodeMd5(bytes))
      }
    }
  })

  it('对拍 200 组确定性伪随机输入（不是只对拍我自己挑的几组）', () => {
    // 自备 LCG：不用 Math.random ⇒ 红了能原样复现（随机种子会让失败无法重放）
    let seed = 0x2f6e2b1
    const next = () => (seed = (seed * 1103515245 + 12345) & 0x7fffffff)
    for (let t = 0; t < 200; t += 1) {
      const len = next() % 300
      const bytes = new Uint8Array(len)
      for (let i = 0; i < len; i += 1) bytes[i] = next() & 0xff
      expect(md5Hex(bytes), `第 ${t} 轮 len=${len}`).toBe(nodeMd5(bytes))
    }
  })

  it('视图（subarray）按**视图范围**哈希，不受底层 buffer 其余内容影响', () => {
    const backing = new Uint8Array([9, 9, 1, 2, 3, 9, 9])
    expect(md5Hex(backing.subarray(2, 5))).toBe(nodeMd5(new Uint8Array([1, 2, 3])))
  })
})

describe('md5Hex · 去重键需要的两条性质', () => {
  it('同内容 ⇒ 同哈希（哪怕来自不同 Uint8Array 实例）', () => {
    expect(md5Hex(utf8('同一张图的内容'))).toBe(md5Hex(utf8('同一张图的内容')))
  })

  it('不同内容 ⇒ 不同哈希（本批修的正是「同名不同图被误判重复」）', () => {
    expect(md5Hex(utf8('截图甲'))).not.toBe(md5Hex(utf8('截图乙')))
  })
})
