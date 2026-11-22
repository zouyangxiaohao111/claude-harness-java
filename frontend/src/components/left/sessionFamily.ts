import type { SessionDto } from '@/api/types'

/**
 * 左栏「派生会话家族色」的纯函数。
 *
 * <b>家族</b> = 沿 `derivedFromSessionId` 上溯到没有来源的那个会话（SessionCreate 的
 * 派生链，深度上限 2）。同一家族共用一个色相：创建者与它派生出的会话、以及派生会话
 * 再派生的孙会话，颜色一致 —— 这样左栏里平铺的两条会话能靠颜色看出「是一支的」。
 *
 * <p>⚠️ 只用于展示：颜色不参与排序、分组归属或权限判断。
 */

/** 家族色盘：刻意避开红/橙/绿（错误 / 等待 / 完成的状态语义色）。 */
export const FAMILY_PALETTE = [
  '#4A6CF7', // 靛蓝
  '#0A9AA8', // 青
  '#8B5CF6', // 紫
  '#E05C8A', // 粉
  '#5B7FA6', // 钢蓝
  '#A9713F', // 古铜
  '#9B5DE5', // 梅紫
  '#2F8FBF', // 湖蓝
] as const

export interface FamilyColor {
  /** 家族根会话 id（创建链最上游；创建者已删时用最近已知祖先 id） */
  rootId: string
  /** 家族色（#RRGGBB） */
  color: string
  /** 色盘桶位（0..FAMILY_PALETTE.length-1），测试/排查用 */
  bucket: number
}

/** FNV-1a 32 位哈希：稳定、无依赖，同 id 永远同值。 */
export function fnv1aHash(input: string): number {
  let hash = 0x811c9dc5
  for (let i = 0; i < input.length; i++) {
    hash ^= input.charCodeAt(i)
    hash = (hash + (hash << 1) + (hash << 4) + (hash << 7) + (hash << 8) + (hash << 24)) >>> 0
  }
  return hash >>> 0
}

/** 沿 derivedFromSessionId 上溯到根；父不在列表里（已删）时用该父 id 作根，颜色保持不变。 */
export function resolveFamilyRoot(session: SessionDto, byId: Map<string, SessionDto>): string {
  let cur = session
  const seen = new Set<string>([session.id])
  while (cur.derivedFromSessionId) {
    const parentId = cur.derivedFromSessionId
    const parent = byId.get(parentId)
    // 创建者已删除（列表里没有）：用它的 id 定色 —— 删会话不级联清来源，颜色仍稳定
    if (!parent) return parentId
    // 脏数据防环：回到走过的节点就停
    if (seen.has(parent.id)) return parent.id
    seen.add(parent.id)
    cur = parent
  }
  return cur.id
}

/**
 * 计算每个「家族成员」的颜色。
 *
 * <p>成员 = 有 `derivedFromSessionId` 的会话（派生会话），或至少派生了 1 个会话的
 * 创建者（根）。普通会话不在返回的 Map 里。
 *
 * <p>撞桶处理：两个家族哈希到同一桶时，按根 id 排序线性探测下一个空桶 —— 同一批会话
 * 的分配结果稳定（集合不变就不会换色）。
 */
export function computeFamilyColors(sessions: SessionDto[]): Map<string, FamilyColor> {
  const byId = new Map(sessions.map((s) => [s.id, s]))
  const childCount = new Map<string, number>()
  for (const s of sessions) {
    const parent = s.derivedFromSessionId
    if (parent) childCount.set(parent, (childCount.get(parent) ?? 0) + 1)
  }

  const rootBySession = new Map<string, string>()
  for (const s of sessions) {
    if (s.derivedFromSessionId) {
      rootBySession.set(s.id, resolveFamilyRoot(s, byId))
    } else if ((childCount.get(s.id) ?? 0) > 0) {
      rootBySession.set(s.id, s.id)
    }
  }
  if (rootBySession.size === 0) return new Map()

  const rootIds = [...new Set(rootBySession.values())].sort()
  const used = new Set<number>()
  const colorByRoot = new Map<string, FamilyColor>()
  for (const rootId of rootIds) {
    let bucket = fnv1aHash(rootId) % FAMILY_PALETTE.length
    let guard = 0
    while (used.has(bucket) && guard < FAMILY_PALETTE.length) {
      bucket = (bucket + 1) % FAMILY_PALETTE.length
      guard++
    }
    used.add(bucket)
    colorByRoot.set(rootId, { rootId, color: FAMILY_PALETTE[bucket], bucket })
  }

  const result = new Map<string, FamilyColor>()
  for (const [sessionId, rootId] of rootBySession) {
    const fam = colorByRoot.get(rootId)
    if (fam) result.set(sessionId, fam)
  }
  return result
}

/** 每个会话的派生数（创建者显示「分支图标 + N」用）。 */
export function computeDerivedChildCounts(sessions: SessionDto[]): Map<string, number> {
  const counts = new Map<string, number>()
  for (const s of sessions) {
    const parent = s.derivedFromSessionId
    if (parent) counts.set(parent, (counts.get(parent) ?? 0) + 1)
  }
  return counts
}
