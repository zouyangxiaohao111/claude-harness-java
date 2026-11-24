import { describe, expect, it } from 'vitest'
import {
  PASTE_TEXT_ATTACHMENT_LIMIT,
  pasteTextFileName,
  shouldConvertPastedText,
} from '../pasteTextAttachment'

/**
 * 大段粘贴 → txt 附件的判据（对齐 DSH paste-as-text-attachment）。
 *
 * <p>阈值是**严格大于** 10000（DSH 文档：「only pastes longer than this value are converted」），
 * 且按 JS {@code String.length}（UTF-16 码元）计 —— 用中文串验证码元口径，避免改成字节数后测试仍绿。
 */
describe('shouldConvertPastedText', () => {
  it('恰好 10000 字符 → 不转换（严格大于才转）', () => {
    expect(shouldConvertPastedText('a'.repeat(PASTE_TEXT_ATTACHMENT_LIMIT), 0)).toBe(false)
  })

  it('10001 字符 → 转换', () => {
    expect(shouldConvertPastedText('a'.repeat(PASTE_TEXT_ATTACHMENT_LIMIT + 1), 0)).toBe(true)
  })

  it('超过阈值但剪贴板同时带文件 → 不转换（交给既有文件通道）', () => {
    expect(shouldConvertPastedText('a'.repeat(PASTE_TEXT_ATTACHMENT_LIMIT + 1), 1)).toBe(false)
  })

  it('按 UTF-16 码元计长：中文与 ASCII 同口径', () => {
    // 5001 个中文 = 5001 个码元（UTF-8 下会是 15003 字节；本判据不按字节）
    expect(shouldConvertPastedText('错'.repeat(5001), 0)).toBe(false)
    expect(shouldConvertPastedText('错'.repeat(10001), 0)).toBe(true)
  })
})

describe('pasteTextFileName', () => {
  it('取第一段非空行（跳过开头空行）', () => {
    expect(pasteTextFileName('\n\nat org.springframework.Foo\n\tat bar')).toBe(
      'at org.springframework.Foo.txt',
    )
  })

  it('首行超过 30 字符 → 截断并追加 ....（DSH 附件名观感）', () => {
    expect(pasteTextFileName('x'.repeat(40) + '\nrest')).toBe('x'.repeat(30) + '....txt')
  })

  it('非法文件名字符替换为 _', () => {
    expect(pasteTextFileName('C:\\logs/info.log: permission')).toBe('C__logs_info.log_ permission.txt')
  })

  it('截断后的尾部点去掉（Windows 会吞尾部点，避免同名碰撞）', () => {
    // 30 字符处正好是点 → slice 后 "a...a."，去尾点后再追加 ....
    expect(pasteTextFileName('a'.repeat(29) + '.' + 'b'.repeat(10))).toBe('a'.repeat(29) + '....txt')
  })

  it('全空白内容 → 回落 pasted.txt', () => {
    expect(pasteTextFileName('   \n\t\n')).toBe('pasted.txt')
  })
})
