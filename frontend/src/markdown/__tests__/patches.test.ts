import { describe, expect, it } from 'vitest'
import { applySettledPatches, fixAdheredTables, fixHeadings, fixUnclosedCodeBlocks } from '../patches.ts'

describe('patches（settled 前文本修正，自 MessageList 迁出的纯函数回归）', () => {
  it('fixHeadings：无空格 ATX 标题补空格，shebang 与已带空格的不动', () => {
    expect(fixHeadings('##核心')).toBe('## 核心')
    expect(fixHeadings('### 已有空格')).toBe('### 已有空格')
    expect(fixHeadings('#!/usr/bin/env bash\n##下一步')).toBe('#!/usr/bin/env bash\n## 下一步')
  })

  it('fixAdheredTables：标题/表头/分隔行粘连时拆行', () => {
    const src = '##字段|说明|备注\n|---|----|----|\n'
    expect(fixAdheredTables(src)).toBe('##字段\n|说明|备注\n|---|----|----|\n')
  })

  it('fixUnclosedCodeBlocks：奇数个 ``` 全剥，偶数闭合不动', () => {
    expect(fixUnclosedCodeBlocks('```js\ncode\n')).toBe('code\n')
    expect(fixUnclosedCodeBlocks('```js\ncode\n```\n\ntext')).toBe('```js\ncode\n```\n\ntext')
  })

  it('applySettledPatches 顺序 unclosed → adhered → headings 且整链对已知脏输入有效', () => {
    expect(applySettledPatches('##核心\n')).toBe('## 核心\n')
    const unclosed = '```html\n<b>1</b>\n'
    expect(applySettledPatches(unclosed)).toBe('<b>1</b>\n')
  })
})
