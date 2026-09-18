/**
 * MD5（RFC 1321）—— **只服务附件去重键**，不是安全用途（判重用，无抗碰撞要求）。
 *
 * <b>为什么手写而不是用平台 WebCrypto</b>（三条）
 * <ol>
 *   <li>WebCrypto **根本不提供 MD5** —— `crypto.subtle.digest` 只认 SHA-1/256/384/512，
 *       想用它就得偏离「按字节 md5」这条用户裁定；</li>
 *   <li>`crypto.subtle` 只在**安全上下文**（https / localhost / `tauri.localhost`）存在。
 *       一旦前端被以裸 http 提供，`crypto.subtle` 就是 `undefined` —— 拿它当唯一实现会让
 *       「附件去重」这一步直接抛错，**连附件都加不进来**：一个判重小功能拖垮主链路；</li>
 *   <li>本函数**同步**：字节读回来即可算键，不引入额外 await 点（去重判定的时序因此保持确定）。</li>
 * </ol>
 *
 * <b>正确性不自证</b>：`__tests__/md5.test.ts` 同时钉两层 ——
 * ① RFC 1321 附录 A.5 的公布向量；② 与 Node `crypto.createHash('md5')` 的**逐字节差分**
 * （覆盖 55/56/57/63/64/65 等分块与填充边界，随机长度对拍）。
 */

/** 每轮左移位数（RFC 1321 §3.4）。 */
const SHIFT = new Uint8Array([
  7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
  5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
  4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
  6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
])

/**
 * `K[i] = floor(abs(sin(i + 1)) × 2^32)`（RFC 1321 §3.4）。
 * 用同一个式子**现算**而不是抄 64 个魔数：抄写是本文件唯一可能出错又看不出来的地方。
 */
const K = new Uint32Array(64)
for (let i = 0; i < 64; i += 1) K[i] = Math.floor(Math.abs(Math.sin(i + 1)) * 4294967296)

/** 32 位循环左移。 */
function rotl(x: number, n: number): number {
  return ((x << n) | (x >>> (32 - n))) >>> 0
}

/**
 * 字节 → 32 位小写十六进制 md5（32 字符）。
 *
 * 输入是 `Uint8Array`（附件字节），不是字符串 —— 本仓只对**附件内容**判重；
 * 将来若要哈希字符串，请先显式 UTF-8 编码，不要在调用点隐式转换。
 */
export function md5Hex(bytes: Uint8Array): string {
  const len = bytes.length
  // 填充：0x80 + 若干 0x00，使总长 ≡ 56 (mod 64)，末尾 8 字节小端写**位**长度
  const padded = new Uint8Array(((len + 8) >> 6 << 6) + 64)
  padded.set(bytes)
  padded[len] = 0x80
  const dv = new DataView(padded.buffer)
  const bitLen = len * 8
  dv.setUint32(padded.length - 8, bitLen >>> 0, true)
  dv.setUint32(padded.length - 4, Math.floor(bitLen / 4294967296), true)

  let a0 = 0x67452301
  let b0 = 0xefcdab89
  let c0 = 0x98badcfe
  let d0 = 0x10325476
  for (let off = 0; off < padded.length; off += 64) {
    let a = a0
    let b = b0
    let c = c0
    let d = d0
    for (let i = 0; i < 64; i += 1) {
      let f: number
      let g: number
      if (i < 16) {
        f = (b & c) | (~b & d)
        g = i
      } else if (i < 32) {
        f = (d & b) | (~d & c)
        g = (5 * i + 1) % 16
      } else if (i < 48) {
        f = b ^ c ^ d
        g = (3 * i + 5) % 16
      } else {
        f = c ^ (b | ~d)
        g = (7 * i) % 16
      }
      const tmp = d
      d = c
      c = b
      // f 可能是负数（int32 位模式），但按 2^32 取模后位模式一致；和 < 2^53 故浮点精确
      const sum = (a + f + K[i] + dv.getUint32(off + g * 4, true)) >>> 0
      b = (b + rotl(sum, SHIFT[i])) >>> 0
      a = tmp
    }
    a0 = (a0 + a) >>> 0
    b0 = (b0 + b) >>> 0
    c0 = (c0 + c) >>> 0
    d0 = (d0 + d) >>> 0
  }

  const out = new Uint8Array(16)
  const odv = new DataView(out.buffer)
  odv.setUint32(0, a0, true)
  odv.setUint32(4, b0, true)
  odv.setUint32(8, c0, true)
  odv.setUint32(12, d0, true)
  let hex = ''
  for (const b of out) hex += b.toString(16).padStart(2, '0')
  return hex
}
