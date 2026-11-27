/**
 * 正文本地链接的路径判定（2026-11-27）。
 *
 * <p>覆盖两条分发前提：什么算「相对路径」、按基准目录拼出来的候选路径长什么样。
 * 解析成功与否依赖 Tauri fs，本文件只测纯函数（不碰桌面壳）。
 */
import { describe, expect, it } from 'vitest'
import { candidatePathsFor, dirnameOf, isRelativeFilePath, localFilePathOf } from '../localFileLink'

describe('isRelativeFilePath', () => {
  it('无协议的普通路径算相对路径', () => {
    expect(isRelativeFilePath('ops-x.html')).toBe(true)
    expect(isRelativeFilePath('docs/a.md')).toBe(true)
    expect(isRelativeFilePath('./a.md')).toBe(true)
    expect(isRelativeFilePath('/docs/a.md')).toBe(true)
    expect(isRelativeFilePath('..\\out\\a.html')).toBe(true)
  })

  it('站内 hash / 绝对本地路径 / 外链 / 自定义协议都不算', () => {
    expect(isRelativeFilePath('#kb-cite-1')).toBe(false)
    expect(isRelativeFilePath('file:///D:/a.html')).toBe(false)
    expect(isRelativeFilePath('D:/a.html')).toBe(false)
    expect(isRelativeFilePath('D:\\a.html')).toBe(false)
    expect(isRelativeFilePath('\\\\server\\share\\a.html')).toBe(false)
    expect(isRelativeFilePath('https://example.com/a')).toBe(false)
    expect(isRelativeFilePath('mailto:a@b.com')).toBe(false)
    expect(isRelativeFilePath('sandbox:/mnt/data/a.html')).toBe(false)
    expect(isRelativeFilePath('')).toBe(false)
  })
})

describe('candidatePathsFor', () => {
  it('按基准目录顺序生成，分隔符跟随基准', () => {
    expect(candidatePathsFor('ops-x.html', ['D:/repo/deliverables', 'D:\\proj'])).toEqual([
      'D:/repo/deliverables/ops-x.html',
      'D:\\proj\\ops-x.html',
    ])
  })

  it('去掉 ./ 与前导斜杠，丢弃空基准', () => {
    expect(candidatePathsFor('./docs/a.md', ['/base/', ''])).toEqual(['/base/docs/a.md'])
  })

  it('去掉锚点与查询串，解 URL 转义', () => {
    expect(candidatePathsFor('a%20b.html#top?x=1', ['D:/r'])).toEqual(['D:/r/a b.html'])
  })
})

describe('dirnameOf', () => {
  it('同时识别 Windows 与 POSIX 分隔符', () => {
    expect(dirnameOf('D:\\apps\\x\\a.html')).toBe('D:\\apps\\x')
    expect(dirnameOf('D:/apps/x/a.html')).toBe('D:/apps/x')
    expect(dirnameOf('a.html')).toBe('a.html')
  })

  it('与 localFilePathOf 配合：绝对路径取目录后再拼 deliverables', () => {
    const dir = dirnameOf(localFilePathOf('file:///D:/apps/x/artifacts/s/a.html')!)
    expect(dir).toBe('D:/apps/x/artifacts/s')
  })
})