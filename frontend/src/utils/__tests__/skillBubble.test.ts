import { describe, expect, it } from 'vitest'
import { matchLeadingSkill } from '../skillBubble'

/**
 * 用户气泡技能标识的判据（用户 2026-11-24 定）：
 * 有 displayName → Octop chip 行；没有 → DSH 内联 `/name`；目录未命中 → 原样。
 */
const CATALOG = [
  { name: 'deep-research', displayName: '深度研究', icon: 'https://x/icon.png', description: '调研' },
  { name: 'archify', displayName: null, icon: null, description: '图示' },
  { name: 'zjky:design', displayName: '设计链', icon: null, description: null },
]

describe('matchLeadingSkill', () => {
  it('有 displayName → named（走 Octop chip 行）', () => {
    const m = matchLeadingSkill('/deep-research 帮我核对材料', CATALOG)
    expect(m).not.toBeNull()
    expect(m!.named).toBe(true)
    expect(m!.token).toBe('/deep-research')
    expect(m!.rest).toBe('帮我核对材料')
    expect(m!.entry.displayName).toBe('深度研究')
  })

  it('无 displayName → 非 named（走 DSH 内联 chip）', () => {
    const m = matchLeadingSkill('/archify 画个架构图', CATALOG)
    expect(m!.named).toBe(false)
    expect(m!.rest).toBe('画个架构图')
  })

  it('带命名空间的命令名（zjky:design）可命中', () => {
    expect(matchLeadingSkill('/zjky:design 开始', CATALOG)!.entry.name).toBe('zjky:design')
  })

  it('只有命令没有参数 → rest 为空', () => {
    expect(matchLeadingSkill('/archify', CATALOG)!.rest).toBe('')
  })

  it('目录未命中（普通命令/未知）→ null，不加标识', () => {
    expect(matchLeadingSkill('/init', CATALOG)).toBeNull()
    expect(matchLeadingSkill('/unknown-cmd 参数', CATALOG)).toBeNull()
  })

  it('路径开头不能被误判成技能（/root/file 不是 /root）', () => {
    expect(matchLeadingSkill('/root/file.txt 看这个', [{ name: 'root' }])).toBeNull()
  })

  it('技能命令必须在消息开头（正文中间的 /name 不处理）', () => {
    expect(matchLeadingSkill('请用 /archify 画图', CATALOG)).toBeNull()
  })

  it('前导空白允许（粘贴/换行后的命令）', () => {
    expect(matchLeadingSkill('  \n/archify 画图', CATALOG)!.named).toBe(false)
  })
})
