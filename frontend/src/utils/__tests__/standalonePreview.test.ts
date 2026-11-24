import { describe, expect, it } from 'vitest'
import { previewKindOfAttachment } from '../standalonePreview'

/**
 * [PASTE-TXT] 大段粘贴转出的 .txt / .log 必须可预览。
 *
 * <p>WHY：卡片可点开是附件的基本行为；映射回落到 `'file'` 时独立窗与待发预览都会显示
 * 「暂不支持预览该文件类型」—— 用户刚粘贴的日志反而看不了。
 */
describe('previewKindOfAttachment · 纯文本附件', () => {
  it('.txt → text（生成名以 ....txt 结尾同样命中）', () => {
    expect(previewKindOfAttachment({
      filename: 'at org.springframework....txt', type: 'file', mediaType: 'text/plain',
    })).toBe('text')
  })

  it('.log → text', () => {
    expect(previewKindOfAttachment({ filename: 'info.log' })).toBe('text')
  })

  it('.md 仍走 markdown（不被 text 分支抢走）', () => {
    expect(previewKindOfAttachment({ filename: 'notes.md' })).toBe('markdown')
  })
})
