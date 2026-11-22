import { describe, expect, it } from 'vitest'
import type { SessionDto } from '@/api/types'
import {
  FAMILY_PALETTE,
  computeDerivedChildCounts,
  computeFamilyColors,
  fnv1aHash,
  resolveFamilyRoot,
} from '../sessionFamily'

/**
 * 左栏「派生会话家族色」纯函数。
 *
 * <b>WHY</b>：颜色是「同族配对」的唯一载体 —— 如果根解析或撞桶处理错了，
 * 左栏会出现两条不相关的会话同色、或同一族两种色，而组件测试很难看出是纯函数算错。
 */

const mk = (id: string, parent?: string): SessionDto =>
  ({ id, derivedFromSessionId: parent ?? null } as unknown as SessionDto)

describe('sessionFamily · 根解析与配色', () => {
  it('派生链（depth 2）全部落到同一个根、同一种颜色', () => {
    const sessions = [mk('a'), mk('b', 'a'), mk('c', 'b')]
    const colors = computeFamilyColors(sessions)
    expect(colors.get('b')?.rootId).toBe('a')
    expect(colors.get('c')?.rootId).toBe('a')
    expect(colors.get('b')?.color).toBe(colors.get('c')?.color)
    // 创建者有子会话 ⇒ 也是家族成员
    expect(colors.get('a')?.rootId).toBe('a')
  })

  it('没有派生的普通会话不在结果里（不染色条）', () => {
    const colors = computeFamilyColors([mk('a'), mk('b', 'a'), mk('plain')])
    expect(colors.has('plain')).toBe(false)
  })

  it('两个家族 → 两种颜色', () => {
    const colors = computeFamilyColors([mk('a'), mk('a1', 'a'), mk('b'), mk('b1', 'b')])
    expect(colors.get('a1')?.color).not.toBe(colors.get('b1')?.color)
  })

  it('创建者已删除（列表里没有父）⇒ 用最近已知祖先 id 定色，结果稳定', () => {
    const sessions = [mk('orphan', 'gone')]
    const first = computeFamilyColors(sessions).get('orphan')
    const second = computeFamilyColors(sessions).get('orphan')
    expect(first?.rootId).toBe('gone')
    expect(first?.color).toBe(second?.color)
    expect(resolveFamilyRoot(sessions[0], new Map())).toBe('gone')
  })

  it('撞桶：两个根命中同一桶时顺延到不同色', () => {
    const buckets = new Map<number, string>()
    let pair: [string, string] | null = null
    for (let i = 0; i < 200 && !pair; i++) {
      const id = `root-${i}`
      const b = fnv1aHash(id) % FAMILY_PALETTE.length
      const prev = buckets.get(b)
      if (prev) pair = [prev, id]
      else buckets.set(b, id)
    }
    expect(pair).toBeTruthy()
    const [r1, r2] = pair!
    const colors = computeFamilyColors([mk(r1), mk(r1 + '-c', r1), mk(r2), mk(r2 + '-c', r2)])
    expect(colors.get(r1 + '-c')?.color).not.toBe(colors.get(r2 + '-c')?.color)
    expect(colors.get(r1 + '-c')?.bucket).not.toBe(colors.get(r2 + '-c')?.bucket)
  })

  it('派生数只统计直接创建者', () => {
    const counts = computeDerivedChildCounts([mk('a'), mk('b', 'a'), mk('c', 'a'), mk('d', 'b')])
    expect(counts.get('a')).toBe(2)
    expect(counts.get('b')).toBe(1)
    expect(counts.get('c')).toBeUndefined()
  })
})
