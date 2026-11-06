import { describe, expect, it } from 'vitest'
import { buildUploadFileName } from '@/api/knowledge'

/**
 * 目录上传名构造 · 子文件夹的**唯一**来源。
 *
 * <h2>⭐⭐ 为什么这是关键路径</h2>
 * 知识库的文件夹树是从 `kb_knowledge.folder_path` **派生**的，
 * 后端**没有「新建空文件夹」接口**（对齐源侧：文件夹树纯派生）。
 * 所以用户能不能看到子文件夹，完全取决于这里是否把目录拼进了 `fileName`。
 *
 * <p>这条曾经是断的：后端 `createFromFile` 把 `folder_path` 写死成 `""`
 * （2026-11-06 修），前端也没有目录上传入口 ⇒ 用户「从来看不到子文件夹」。
 */
describe('buildUploadFileName', () => {
  it('单文件上传到根 ⇒ undefined（保持旧行为，请求逐字节不变）', () => {
    expect(buildUploadFileName({ name: 'a.md' }, '')).toBeUndefined()
  })

  it('⭐ 目录选择 ⇒ 用 webkitRelativePath 拼出目录（含被选目录本身）', () => {
    expect(
      buildUploadFileName({ name: 'day-one.md', webkitRelativePath: 'handbook/onboarding/day-one.md' }, '')
    ).toBe('handbook/onboarding/day-one.md')
  })

  it('⭐ 目标文件夹 + 目录选择 ⇒ 目标在前，webkit 目录在后', () => {
    expect(
      buildUploadFileName({ name: 'tokens.md', webkitRelativePath: 'design/tokens.md' }, 'handbook')
    ).toBe('handbook/design/tokens.md')
  })

  it('只选目标文件夹（单文件）⇒ 目标/文件名', () => {
    expect(buildUploadFileName({ name: 'b.md' }, '规范')).toBe('规范/b.md')
  })

  it('目标文件夹带多余斜杠 ⇒ 规范成单斜杠', () => {
    expect(buildUploadFileName({ name: 'c.md' }, '/规范//运维/')).toBe('规范/运维/c.md')
  })

  it('⛔ 文件名本身不重复出现在目录段（slice(-1) 保护）', () => {
    const got = buildUploadFileName({ name: 'x.md', webkitRelativePath: 'a/x.md' }, '')
    expect(got).toBe('a/x.md')
    // 目录段只有 a，不含 x.md
    expect(got!.split('/').slice(0, -1)).toEqual(['a'])
  })

  it('深层目录完整保留（不截断父级）', () => {
    expect(
      buildUploadFileName({ name: 'f.txt', webkitRelativePath: 'a/b/c/d/f.txt' }, '')
    ).toBe('a/b/c/d/f.txt')
  })
})
