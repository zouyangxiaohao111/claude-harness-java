// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { AstToolBody, parseAstPayload } from '../AstToolBody'
import type { AstEditPayload, AstGrepPayload } from '@/api/types'

/**
 * AST 工具卡正文契约 · ⭐ 这几条是「用户能看懂」的最低要求：
 * <ol>
 *   <li>AstGrep：文件头 + `行:列` + meta 都要在（少一个就无法定位）；</li>
 *   <li>AstEdit：`-` / `+` 两行 + 三态徽标（未应用/已应用/无变更）——预览没写盘时不能显示「已应用」；</li>
 *   <li>每文件 10 条上限 + `… N more`（DOM 密集，超限必须收）；</li>
 *   <li>`parseAstPayload` 对非法 JSON / 工具名不匹配返回 null（回落原有 IN/OUT 渲染）。</li>
 * </ol>
 */

const grepPayload: AstGrepPayload = {
  kind: 'ast_grep',
  pattern: 'foo($A)',
  lang: null,
  scope: 'src',
  filesSearched: 3,
  skip: 0,
  limitReached: false,
  truncated: false,
  totalMatches: 13,
  more: 0,
  files: [{
    path: 'src/A.java',
    language: 'Java',
    matches: Array.from({ length: 13 }, (_, i) => ({
      line: i + 1,
      column: 3,
      endLine: i + 1,
      endColumn: 9,
      text: `foo(${i})`,
      meta: { A: String(i) },
    })),
  }],
}

const editPayload: AstEditPayload = {
  kind: 'ast_edit',
  applied: false,
  ops: [{ pat: 'foo($A)', out: 'bar($A)' }],
  paths: ['src'],
  totalReplacements: 1,
  filesTouched: 1,
  limitReached: false,
  truncated: false,
  more: 0,
  files: [{
    path: 'src/A.java',
    changes: [{ line: 5, column: 9, endLine: 5, endColumn: 16, before: 'foo(1)', after: 'bar(1)' }],
  }],
}

let container: HTMLDivElement
let root: Root

beforeEach(() => {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
})

afterEach(() => {
  act(() => root.unmount())
  container.remove()
})

function render(payload: AstGrepPayload | AstEditPayload) {
  act(() => {
    root.render(<AstToolBody payload={payload} />)
  })
}

describe('AstToolBody', () => {
  it('AstGrep 渲染文件头 / 行:列 / meta，且超过 10 条显示 more', () => {
    render(grepPayload)
    const text = container.textContent ?? ''
    expect(text).toContain('src/A.java')
    expect(text).toContain('1:3')
    expect(text).toContain('meta: A=0')
    expect(text).toContain('foo($A)')
    // 13 条 → 只渲染 10 条 + 「… 3 more」
    expect(container.querySelectorAll('.ast-match')).toHaveLength(10)
    expect(text).toContain('… 3 more')
  })

  it('AstEdit 渲染 -/+ 两行与「未应用」徽标（预览不写盘）', () => {
    render(editPayload)
    const text = container.textContent ?? ''
    expect(text).toContain('未应用')
    expect(text).not.toContain('已应用')
    expect(container.querySelectorAll('.ast-diff-del')).toHaveLength(1)
    expect(container.querySelectorAll('.ast-diff-add')).toHaveLength(1)
    expect(text).toContain('foo(1)')
    expect(text).toContain('bar(1)')
    expect(text).toContain('Total 1 replacements in 1 files')
  })

  it('AstEdit 已应用 / 无变更 徽标切换', () => {
    render({ ...editPayload, applied: true })
    expect(container.textContent).toContain('已应用')
    act(() => root.unmount())
    root = createRoot(container)
    render({ ...editPayload, totalReplacements: 0, files: [], filesTouched: 0 })
    expect(container.textContent).toContain('无变更')
  })
})

describe('parseAstPayload', () => {
  it('只接受工具名与 kind 匹配的合法 JSON', () => {
    expect(parseAstPayload('AstGrep', JSON.stringify(grepPayload))?.kind).toBe('ast_grep')
    expect(parseAstPayload('ast_edit', JSON.stringify(editPayload))?.kind).toBe('ast_edit')
    expect(parseAstPayload('AstGrep', 'not json')).toBeNull()
    expect(parseAstPayload('AstGrep', null)).toBeNull()
    expect(parseAstPayload('Grep', JSON.stringify(grepPayload))).toBeNull()
    expect(parseAstPayload('AstEdit', JSON.stringify(grepPayload))).toBeNull()
  })
})
