/**
 * Wiki 图谱布局算法 · 逐行移植自设计稿 04（真机源码 `wikiGraphLayout.ts`）。
 *
 * <h2>真源</h2>
 * `D:/tmp/graph04.js`（= 设计稿 `04-图谱.html` 的 `<script>` 区，2015 行）。
 * 每个函数的源码行号写在函数上方，⛔ 不要按「看起来更合理」改写：
 * 收敛后的节点位置与标签避让都是这些常量的连续函数，任何偏差都会让
 * 最终画面与真机不同（实测少显示 1 个标签）。
 *
 * <h2>纯函数边界</h2>
 * 本文件<b>不碰 DOM</b>、不读写时钟，只做几何 / 图算法。
 * 输入输出都在参数与返回值上 ⇒ 组件重渲染时可安全重算。
 */

/** 布局用的节点（比后端 DTO 多一个 `linkCount`，由边表算出）。 */
export interface GraphNodeLike {
  slug: string
  title: string
  type: string
  /** 与源站 `link_count` 同口径：**有向边原始行**里该节点出现的次数。 */
  linkCount: number
}

/** 布局用的边。 */
export interface GraphEdgeLike {
  source: string
  target: string
}

export interface Point {
  x: number
  y: number
}

/** 力导向仿真节点（会被 {@link stepGraphLayout} 原地修改）。 */
export interface SimNode extends GraphNodeLike {
  x: number
  y: number
  vx: number
  vy: number
  pinned: boolean
  /** 文字标签宽度（写完 DOM 后由 getComputedTextLength 回填）。 */
  labelWidth: number
}

/** buildGraphLayout 产出的放置信息。 */
export interface Placement {
  x: number
  y: number
  /** 所属组件（cluster）的中心，力导向每帧把它当回拉锚点。 */
  center: Point
  /** 节点度（图上可见边数）。 */
  degree: number
  /** BFS 层级（仅用于调试，源站同名字段）。 */
  depth?: number
  /** 无连接节点排架的固定位，不参与仿真。 */
  stationary?: boolean
}

export interface LayoutSection {
  kind: string
  count: number
  left: number
  top: number
  right: number
  bottom: number
}

export interface LayoutCluster {
  x: number
  y: number
  left: number
  right: number
  top: number
  bottom: number
  slugs: string[]
  representative: string
  anchors: string[]
}

export interface LayoutResult {
  placements: Map<string, Placement>
  sections: LayoutSection[]
  clusters: LayoutCluster[]
}

/** 标签避让用的矩形框。 */
export interface LabelBox {
  slug: string
  priority: number
  required?: boolean
  left: number
  right: number
  top: number
  bottom: number
}

/** 节点半径（源站 `graphNodeRadius`，design-04 script:32）。 */
export function graphNodeRadius(linkCount: number): number {
  return Math.max(5, Math.min(20, 4 + Math.sqrt(Math.max(0, linkCount)) * 1.8))
}

/** 点集包围盒（design-04 script:48）。 */
export function graphPointBounds(points: Point[]): {
  left: number
  top: number
  right: number
  bottom: number
} {
  if (!points.length) return { left: 0, top: 0, right: 0, bottom: 0 }
  return {
    left: Math.min(...points.map((p) => p.x)),
    right: Math.max(...points.map((p) => p.x)),
    top: Math.min(...points.map((p) => p.y)),
    bottom: Math.max(...points.map((p) => p.y)),
  }
}

/**
 * 社区划分（Louvain 单层迭代）· `wikiGraphLayout.ts:33`（design-04 script:58）。
 *
 * <p>输入是「每个节点的邻居下标数组」，输出是「每个节点的社区号」。
 * 迭代里权重表会逐层折叠，`membership` 负责把层级编号映射回原始节点。
 */
export function graphCommunities(neighbors: number[][]): number[] {
  const n = neighbors.length
  let membership: number[] = []
  for (let mi = 0; mi < n; mi++) membership.push(mi)
  let weights: Array<Map<number, number>> = neighbors.map((list) => {
    const row = new Map<number, number>()
    list.forEach((j) => row.set(j, (row.get(j) || 0) + 1))
    return row
  })
  let inner: number[] = new Array(n).fill(0)
  for (;;) {
    const size = weights.length
    const degree = weights.map((row, i) => {
      let sum = 0
      row.forEach((w) => {
        sum += w
      })
      return sum + 2 * inner[i]
    })
    const total = degree.reduce((sum, d) => sum + d, 0)
    if (!total) break
    const community: number[] = []
    for (let ci0 = 0; ci0 < size; ci0++) community.push(ci0)
    const tot = degree.slice()
    let movedAny = false
    for (let pass = 0; pass < 32; pass++) {
      let moved = false
      for (let i = 0; i < size; i++) {
        const own = community[i]
        const links = new Map<number, number>()
        weights[i].forEach((w, j) => {
          const c = community[j]
          links.set(c, (links.get(c) || 0) + w)
        })
        tot[own] -= degree[i]
        let best = own
        let bestGain = (links.get(own) || 0) - ((tot[own] * degree[i]) / total)
        const keys: number[] = []
        links.forEach((_w, c) => {
          keys.push(c)
        })
        keys
          .sort((a, b) => a - b)
          .forEach((c) => {
            const gain = (links.get(c) as number) - ((tot[c] * degree[i]) / total)
            if (gain > bestGain + 1e-9) {
              best = c
              bestGain = gain
            }
          })
        tot[best] += degree[i]
        if (best !== own) {
          community[i] = best
          moved = true
          movedAny = true
        }
      }
      if (!moved) break
    }
    if (!movedAny) break
    const renumber = new Map<number, number>()
    community.forEach((c) => {
      if (!renumber.has(c)) renumber.set(c, renumber.size)
    })
    const next: Array<Map<number, number>> = []
    for (let ni = 0; ni < renumber.size; ni++) next.push(new Map<number, number>())
    const nextInner: number[] = new Array(renumber.size).fill(0)
    for (let si = 0; si < size; si++) {
      const sci = renumber.get(community[si]) as number
      nextInner[sci] += inner[si]
      weights[si].forEach((w, j) => {
        const cj = renumber.get(community[j]) as number
        if (sci === cj) nextInner[sci] += w / 2
        else next[sci].set(cj, (next[sci].get(cj) || 0) + w)
      })
    }
    membership = membership.map((c) => renumber.get(community[c]) as number)
    weights = next
    inner = nextInner
    if (renumber.size === size) break
  }
  return membership
}

/** 地标页质量权重（design-04 script:44）。 */
const anchorTypeQuality: Record<string, number> = {
  entity: 1.15,
  concept: 1,
  synthesis: 0.8,
  comparison: 0.8,
  summary: 0.9,
  index: 0.2,
}

/** 社区主题（{@link selectGraphAnchors} 内部用）。 */
interface Theme {
  size: number
  ranked: number[]
  members: Set<number>
}

/**
 * 挑「地标标题」· `wikiGraphLayout.ts:115`（design-04 script:126）。
 *
 * <p>每个连通分量挑 1~5 个代表节点（页数越多预算越大），
 * 用来画集群标题。选法 = 社区规模 × 覆盖度 × 标题可读性，外加
 * 「避免与已选节点冗余」与「摘要页跨度」两条补选。
 */
export function selectGraphAnchors(
  slugs: string[],
  adjacency: Map<string, Set<string>>,
  pages: Map<string, { title: string; type: string }>
): string[] {
  if (!slugs.length) return []
  const order: string[] = []
  slugs.forEach((s) => {
    if (order.indexOf(s) < 0) order.push(s)
  })
  order.sort()
  const index = new Map<string, number>(order.map((slug, i) => [slug, i]))
  const neighbors: number[][] = order.map((slug) => {
    const list: number[] = []
    ;(adjacency.get(slug) || new Set<string>()).forEach((nb) => {
      if (index.has(nb) && nb !== slug) list.push(index.get(nb) as number)
    })
    return list.sort((a, b) => a - b)
  })
  const membership = graphCommunities(neighbors)
  const groups = new Map<number, number[]>()
  membership.forEach((c, i) => {
    const group = groups.get(c) || []
    group.push(i)
    groups.set(c, group)
  })

  const quality = (i: number) => {
    const page = pages.get(order[i])
    const title = landmarkTitle((page && page.title) || '')
    const readable = /[\u4e00-\u9fff]{2}|[a-z]{3}/i.test(title) ? 1 : 0.3
    const typeQ = anchorTypeQuality[(page && page.type) || '']
    return (typeQ === undefined ? 1 : typeQ) * (title.length > 18 ? 0.6 : 1) * readable
  }
  const best = (group: number[], score: (i: number) => number) =>
    group.reduce((top, i) => {
      const a = score(i)
      const b = score(top)
      return a > b + 1e-9 ||
        (Math.abs(a - b) <= 1e-9 && neighbors[i].length > neighbors[top].length)
        ? i
        : top
    })
  const themeList: Theme[] = []
  groups.forEach((group) => {
    const inside = new Set<number>(group)
    const score = (i: number) => {
      const internal = neighbors[i].filter((j) => inside.has(j)).length
      return (internal + 0.5 * (neighbors[i].length - internal) + 1) * quality(i)
    }
    const ranked = group
      .slice()
      .sort(
        (a, b) =>
          score(b) - score(a) || neighbors[b].length - neighbors[a].length || a - b
      )
    themeList.push({ size: group.length, ranked, members: inside })
  })
  const themes = themeList.sort((a, b) => b.size - a.size || a.ranked[0] - b.ranked[0])

  const allIdx = order.map((_v, i) => i)
  const name = best(allIdx, (i) => (neighbors[i].length + 1) * quality(i))
  const home = themes.filter((t) => t.members.has(name))[0]
  const minimum = Math.max(3, themes[0].size * 0.3)
  const candidates = themes.filter((t) => t !== home && t.size >= minimum)

  const closed = (i: number) => {
    const s = new Set<number>([i])
    neighbors[i].forEach((j) => s.add(j))
    return s
  }
  const normalized = (i: number) => {
    const page = pages.get(order[i])
    return landmarkTitle((page && page.title) || '')
      .toLowerCase()
      .replace(/[\s《》""''（）()·・]/g, '')
  }
  const redundant = (i: number, chosen: number[]) =>
    chosen.some((s) => {
      const a = closed(i)
      const b = closed(s)
      let shared = 0
      a.forEach((x) => {
        if (b.has(x)) shared += 1
      })
      if (shared / (a.size + b.size - shared) >= 0.5) return true
      const x = normalized(i)
      const y = normalized(s)
      return !!x && !!y && (x.indexOf(y) >= 0 || y.indexOf(x) >= 0)
    })
  const budget =
    order.length < 16
      ? 1
      : order.length < 50
        ? 2
        : order.length < 100
          ? 3
          : order.length < 200
            ? 4
            : 5
  const bfs = (start: number) => {
    const distance: number[] = new Array(order.length).fill(Infinity)
    distance[start] = 0
    const queue: number[] = [start]
    for (let q = 0; q < queue.length; q++) {
      neighbors[queue[q]].forEach((j) => {
        if (distance[j] !== Infinity) return
        distance[j] = distance[queue[q]] + 1
        queue.push(j)
      })
    }
    return distance
  }
  const nearest = bfs(name)
  const far = nearest.reduce((top, d, i) => (d !== Infinity && d > nearest[top] ? i : top), name)
  const diameter = Math.max(...bfs(far).filter((d) => Number.isFinite(d)))
  const reach = Math.max(2, diameter / budget)
  const selected = [name]
  const open = new Set<Theme>(candidates)
  while (selected.length < budget && open.size) {
    let pick: { theme: Theme; label: number } | null = null
    let bestScore = -1
    open.forEach((theme) => {
      const covered = selected.some(
        (s) =>
          neighbors[s].filter((j) => theme.members.has(j)).length * 2 >= theme.size
      )
      let label: number | undefined
      if (!covered) {
        for (let ri = 0; ri < theme.ranked.length; ri++) {
          if (!redundant(theme.ranked[ri], selected)) {
            label = theme.ranked[ri]
            break
          }
        }
      }
      if (label === undefined) {
        open.delete(theme)
        return
      }
      const score = (Math.sqrt(theme.size) * Math.min(nearest[label], reach)) / reach
      if (
        score > bestScore + 1e-9 ||
        (Math.abs(score - bestScore) <= 1e-9 && pick && nearest[label] > nearest[pick.label])
      ) {
        pick = { theme, label }
        bestScore = score
      }
    })
    if (!pick) break
    const chosen: { theme: Theme; label: number } = pick
    open.delete(chosen.theme)
    selected.push(chosen.label)
    bfs(chosen.label).forEach((d, i) => {
      nearest[i] = Math.min(nearest[i], d)
    })
  }

  const documents = new Set<number>()
  order.forEach((slug, i) => {
    const page = pages.get(slug)
    if (page && page.type === 'summary') documents.add(i)
  })
  if (documents.size >= 2) {
    const spans = (i: number) => {
      if (documents.has(i)) return 0
      return neighbors[i].filter((j) => documents.has(j)).length
    }
    const needed = Math.max(2, Math.ceil((documents.size * 2) / 3))
    const alreadySpans = selected.some((s) => spans(s) >= needed)
    if (!alreadySpans) {
      const weakest = Math.min(...selected.map((s) => neighbors[s].length))
      const prominence = (i: number) => (neighbors[i].length + 1) * quality(i)
      let bridge: number | undefined
      order.forEach((_slug, i) => {
        if (spans(i) < needed || neighbors[i].length < weakest * 0.6) return
        if (redundant(i, selected)) return
        if (
          bridge === undefined ||
          spans(i) > spans(bridge) ||
          (spans(i) === spans(bridge) && prominence(i) > prominence(bridge) + 1e-9)
        )
          bridge = i
      })
      if (bridge !== undefined) selected.push(bridge)
    }
  }
  return selected.map((i) => order[i])
}

/**
 * 初始坐标 · `wikiGraphLayout.ts:232`（design-04 script:277）。
 *
 * <p>⛔ 不能预烘焙：源站的力导向从这里的输出起跑，起点变了收敛结果就变。
 * 连通分量各自做 BFS 树形展开 → 用 500px 网格装箱摆到画布上 →
 * 无连接节点最后排成货架。
 *
 * @param prior 增量渲染时的「上一轮坐标」，给定时只用它，不重新装箱
 */
export function buildGraphLayout(
  nodes: GraphNodeLike[],
  edges: GraphEdgeLike[],
  width: number,
  height: number,
  prior?: Map<string, { x: number; y: number; pinned?: boolean }>
): LayoutResult {
  prior = prior || new Map()
  const nodeBySlug = new Map<string, GraphNodeLike>(nodes.map((n) => [n.slug, n]))
  const adjacency = new Map<string, Set<string>>(nodes.map((n) => [n.slug, new Set<string>()]))
  edges.forEach((e) => {
    if (e.source === e.target || !nodeBySlug.has(e.source) || !nodeBySlug.has(e.target)) return
    adjacency.get(e.source)!.add(e.target)
    adjacency.get(e.target)!.add(e.source)
  })
  const components: string[][] = []
  const unlinked: string[] = []
  const offscreen: string[] = []
  const visited = new Set<string>()
  const slugsSorted: string[] = []
  nodeBySlug.forEach((_v, k) => {
    slugsSorted.push(k)
  })
  slugsSorted.sort()
  slugsSorted.forEach((slug) => {
    if (visited.has(slug)) return
    visited.add(slug)
    if (!adjacency.get(slug)!.size) {
      ;((nodeBySlug.get(slug) as GraphNodeLike).linkCount > 0 ? offscreen : unlinked).push(slug)
      return
    }
    const component = [slug]
    for (let i = 0; i < component.length; i++) {
      adjacency.get(component[i])!.forEach((neighbor) => {
        if (visited.has(neighbor)) return
        visited.add(neighbor)
        component.push(neighbor)
      })
    }
    components.push(component.sort())
  })
  components.sort((a, b) => b.length - a.length || (a[0] < b[0] ? -1 : 1))

  const aspect = Math.min(1.8, Math.max(0.8, width / Math.max(height, 1)))
  const stretch = Math.sqrt(aspect)
  const goldenAngle = Math.PI * (3 - Math.sqrt(5))
  const clusters: Array<{
    x: number
    y: number
    left: number
    right: number
    top: number
    bottom: number
    slugs: string[]
    representative: string
    anchors: string[]
  }> = []
  const seeds = new Map<string, Point>()
  const depthBySlug = new Map<string, number>()
  const cellSize = 500
  const cells = new Map<string, Array<{ left: number; right: number; top: number; bottom: number }>>()
  const keysFor = (bounds: { left: number; right: number; top: number; bottom: number }) => {
    const keys: string[] = []
    for (let gx = Math.floor(bounds.left / cellSize); gx <= Math.floor(bounds.right / cellSize); gx++) {
      for (let gy = Math.floor(bounds.top / cellSize); gy <= Math.floor(bounds.bottom / cellSize); gy++) {
        keys.push(gx + ',' + gy)
      }
    }
    return keys
  }
  const pageIndex = new Map<string, { type: string; title: string }>()
  nodes.forEach((n) => {
    pageIndex.set(n.slug, { type: n.type, title: n.title })
  })

  components.forEach((slugs) => {
    const representative = slugs
      .slice()
      .sort((a, b) => adjacency.get(b)!.size - adjacency.get(a)!.size || (a < b ? -1 : 1))[0]
    const local = new Map<string, { x: number; y: number; heading: number; depth: number }>()
    local.set(representative, { x: 0, y: 0, heading: 0, depth: 0 })
    const queue: string[] = [representative]
    for (let cursor = 0; cursor < queue.length; cursor++) {
      const parentSlug = queue[cursor]
      const parent = local.get(parentSlug) as { x: number; y: number; heading: number; depth: number }
      const children: string[] = []
      adjacency.get(parentSlug)!.forEach((s) => {
        if (!local.has(s)) children.push(s)
      })
      children.sort()
      children.forEach((slug, i) => {
        const heading =
          parent.heading +
          (cursor === 0
            ? i * goldenAngle
            : ((i + 0.5) / children.length - 0.5) * Math.PI * 0.8)
        const distance = 150 + (children.length > 6 ? Math.sqrt(i) * 30 : (i % 3) * 12)
        local.set(slug, {
          x: parent.x + Math.cos(heading) * distance,
          y: parent.y + Math.sin(heading) * distance,
          heading,
          depth: parent.depth + 1,
        })
        queue.push(slug)
      })
    }
    const mean = { x: 0, y: 0 }
    local.forEach((point) => {
      mean.x += point.x / local.size
      mean.y += point.y / local.size
    })
    local.forEach((point, slug) => {
      seeds.set(slug, { x: point.x - mean.x, y: point.y - mean.y })
      depthBySlug.set(slug, point.depth)
    })
    const extent = graphPointBounds(slugs.map((s) => seeds.get(s) as Point))
    const padding = 160
    let x = 0
    let y = 0
    let bounds = { left: 0, right: 0, top: 0, bottom: 0 }
    for (let attempt = 0; ; attempt++) {
      const distanceA = Math.sqrt(attempt) * 48
      x = Math.cos(attempt * goldenAngle) * distanceA * stretch
      y = (Math.sin(attempt * goldenAngle) * distanceA) / stretch
      bounds = {
        left: x + extent.left - padding,
        right: x + extent.right + padding,
        top: y + extent.top - padding,
        bottom: y + extent.bottom + padding,
      }
      const neighbours = new Set<{ left: number; right: number; top: number; bottom: number }>()
      keysFor(bounds).forEach((key) => {
        ;(cells.get(key) || []).forEach((c) => neighbours.add(c))
      })
      let free = true
      neighbours.forEach((c) => {
        if (
          !(
            bounds.right <= c.left ||
            bounds.left >= c.right ||
            bounds.bottom <= c.top ||
            bounds.top >= c.bottom
          )
        )
          free = false
      })
      if (free) break
    }
    const cluster = {
      x,
      y,
      left: bounds.left,
      right: bounds.right,
      top: bounds.top,
      bottom: bounds.bottom,
      slugs,
      representative,
      anchors: selectGraphAnchors(slugs, adjacency, pageIndex) as string[],
    }
    clusters.push(cluster)
    keysFor(bounds).forEach((key) => {
      const bucket = cells.get(key) || []
      bucket.push(cluster)
      cells.set(key, bucket)
    })
  })

  const left = Math.min(...[0].concat(clusters.map((c) => c.left)))
  const top = Math.min(...[0].concat(clusters.map((c) => c.top)))
  const placements = new Map<string, Placement>()
  const sections: LayoutSection[] = []
  let right = 0
  let bottom = 0
  clusters.forEach((cluster) => {
    cluster.x -= left
    cluster.y -= top
    cluster.left -= left
    cluster.right -= left
    cluster.top -= top
    cluster.bottom -= top
    const center = { x: cluster.x, y: cluster.y }
    cluster.slugs.forEach((slug) => {
      const point = seeds.get(slug) as Point
      placements.set(slug, {
        x: center.x + point.x,
        y: center.y + point.y,
        center,
        degree: adjacency.get(slug)!.size,
        depth: depthBySlug.get(slug),
      })
    })
    right = Math.max(right, cluster.right)
    bottom = Math.max(bottom, cluster.bottom)
  })

  const gap = 80
  for (const pair of [
    ['offscreen', offscreen] as const,
    ['unlinked', unlinked] as const,
  ]) {
    const kind = pair[0]
    const slugs = pair[1]
    if (!slugs.length) continue
    const shelfWidth = right || Math.sqrt(slugs.length * 180 * 100 * aspect)
    const columns = Math.min(slugs.length, Math.max(3, Math.floor(shelfWidth / 180)))
    const shelfTop = bottom ? bottom + gap : 0
    const sectionWidth = columns * 180
    const sectionHeight = Math.ceil(slugs.length / columns) * 100 + 50
    const shelfLeft = Math.max(0, (right - sectionWidth) / 2)
    slugs.forEach((slug, i) => {
      const point = {
        x: shelfLeft + (i % columns) * 180 + 90,
        y: shelfTop + 70 + Math.floor(i / columns) * 100,
      }
      placements.set(slug, { x: point.x, y: point.y, center: point, degree: 0, stationary: true })
    })
    sections.push({
      kind,
      count: slugs.length,
      left: shelfLeft,
      top: shelfTop,
      right: shelfLeft + sectionWidth,
      bottom: shelfTop + sectionHeight,
    })
    bottom = shelfTop + sectionHeight
    right = Math.max(right, shelfLeft + sectionWidth)
  }

  const dx = (width - right) / 2
  const dy = (height - bottom) / 2
  placements.forEach((placement) => {
    placement.x += dx
    placement.y += dy
  })
  const centerObjects: Point[] = []
  placements.forEach((p) => {
    if (centerObjects.indexOf(p.center) < 0) centerObjects.push(p.center)
  })
  centerObjects.forEach((center) => {
    center.x += dx
    center.y += dy
  })
  clusters.forEach((cluster) => {
    cluster.x += dx
    cluster.y += dy
    cluster.left += dx
    cluster.right += dx
    cluster.top += dy
    cluster.bottom += dy
  })
  sections.forEach((section) => {
    section.left += dx
    section.right += dx
    section.top += dy
    section.bottom += dy
  })

  if (prior.size) {
    components.forEach((slugs) => {
      const existing: Point[] = []
      slugs.forEach((slug) => {
        if (prior.has(slug)) existing.push(prior.get(slug) as Point)
      })
      if (!existing.length) return
      const center = {
        x: existing.reduce((sum, p) => sum + p.x, 0) / existing.length,
        y: existing.reduce((sum, p) => sum + p.y, 0) / existing.length,
      }
      slugs.forEach((slug, i) => {
        let neighbor: string | undefined
        adjacency.get(slug)!.forEach((nb) => {
          if (neighbor === undefined && prior.has(nb)) neighbor = nb
        })
        const anchor =
          prior.get(slug) || (neighbor !== undefined ? (prior.get(neighbor) as Point) : center)
        const angle = i * Math.PI * (3 - Math.sqrt(5))
        const point = prior.has(slug)
          ? (anchor as Point)
          : { x: anchor.x + Math.cos(angle) * 90, y: anchor.y + Math.sin(angle) * 90 }
        placements.set(slug, {
          x: point.x,
          y: point.y,
          center,
          degree: adjacency.get(slug)!.size,
          depth: depthBySlug.get(slug),
        })
      })
    })
    prior.forEach((point, slug) => {
      const placement = placements.get(slug)
      if (placement) {
        placement.x = point.x
        placement.y = point.y
      }
    })
  }
  return {
    placements,
    sections: prior.size ? [] : sections,
    clusters: prior.size ? [] : clusters,
  }
}

/**
 * 力导向单帧 · `wikiGraphLayout.ts:450`（design-04 script:504）。
 *
 * <p>斥力 `12000*alpha/max(d²,100)`（空间网格 reach=300）、
 * 弹簧 `(dist-140)*0.003*alpha/sqrt(min(degree))`、
 * 阻尼 0.6、限速 20、碰撞最小距 `r(a)+r(b)+16`（3 轮 X 轴剪枝）、
 * 组件整体向 center 回拉 `0.03*alpha`。
 */
export function stepGraphLayout(
  nodes: SimNode[],
  edges: GraphEdgeLike[],
  placements: Map<string, { center: Point; degree: number; stationary?: boolean }>,
  alpha: number,
  retained: Set<string>
): void {
  const fixed = (n: SimNode) =>
    n.pinned || retained.has(n.slug) || !!((placements.get(n.slug) || {}).stationary)
  if (nodes.every(fixed)) return
  const reach = 300
  const buckets = new Map<string, SimNode[]>()
  const nodeMap = new Map<string, SimNode>(nodes.map((n) => [n.slug, n]))
  for (let i = 0; i < nodes.length; i++) {
    const n = nodes[i]
    const cx = Math.floor(n.x / reach)
    const cy = Math.floor(n.y / reach)
    for (let gx = cx - 1; gx <= cx + 1; gx++) {
      for (let gy = cy - 1; gy <= cy + 1; gy++) {
        const list = buckets.get(gx + ',' + gy) || []
        for (let k = 0; k < list.length; k++) {
          const other = list[k]
          let dx = n.x - other.x
          let dy = n.y - other.y
          let distance = Math.hypot(dx, dy)
          if (distance > reach) continue
          if (distance < 0.001) {
            dx = 1
            dy = 0
            distance = 1
          }
          const force = (12000 * alpha) / Math.max(distance * distance, 100)
          const fx = (dx / distance) * force
          const fy = (dy / distance) * force
          if (!fixed(n)) {
            n.vx += fx
            n.vy += fy
          }
          if (!fixed(other)) {
            other.vx -= fx
            other.vy -= fy
          }
        }
      }
    }
    const key = cx + ',' + cy
    const bucket = buckets.get(key) || []
    bucket.push(n)
    buckets.set(key, bucket)
  }
  const pairs = new Set<string>()
  for (let ei = 0; ei < edges.length; ei++) {
    const e = edges[ei]
    const s = nodeMap.get(e.source)
    const t = nodeMap.get(e.target)
    if (!s || !t || s === t) continue
    const pk = JSON.stringify([s.slug, t.slug].sort())
    if (pairs.has(pk)) continue
    pairs.add(pk)
    const ddx = t.x - s.x
    const ddy = t.y - s.y
    const dist = Math.hypot(ddx, ddy) || 1
    const degree = Math.min(
      (placements.get(s.slug) || {}).degree || 1,
      (placements.get(t.slug) || {}).degree || 1
    )
    const sf = ((dist - 140) * 0.003 * alpha) / Math.sqrt(Math.max(1, degree))
    if (!fixed(s)) {
      s.vx += (ddx / dist) * sf
      s.vy += (ddy / dist) * sf
    }
    if (!fixed(t)) {
      t.vx -= (ddx / dist) * sf
      t.vy -= (ddy / dist) * sf
    }
  }
  for (let ni = 0; ni < nodes.length; ni++) {
    const m = nodes[ni]
    if (fixed(m)) continue
    m.vx *= 0.6
    m.vy *= 0.6
    const speed = Math.hypot(m.vx, m.vy)
    if (speed > 20) {
      m.vx *= 20 / speed
      m.vy *= 20 / speed
    }
    m.x += m.vx
    m.y += m.vy
  }
  for (let pass = 0; pass < 3; pass++) {
    const sorted = nodes.slice().sort((a, b) => a.x - b.x)
    for (let si = 0; si < sorted.length; si++) {
      const a = sorted[si]
      for (let sj = si + 1; sj < sorted.length; sj++) {
        const b = sorted[sj]
        if (b.x - a.x > 64) break
        let cdx = b.x - a.x
        let cdy = b.y - a.y
        let cd = Math.hypot(cdx, cdy)
        const minimum = graphNodeRadius(a.linkCount) + graphNodeRadius(b.linkCount) + 16
        if (cd >= minimum || (fixed(a) && fixed(b))) continue
        if (cd < 0.001) {
          cdx = 1
          cdy = 0
          cd = 1
        }
        const shift = (minimum - cd) / (fixed(a) || fixed(b) ? 1 : 2)
        if (!fixed(a)) {
          a.x -= (cdx / cd) * shift
          a.y -= (cdy / cd) * shift
        }
        if (!fixed(b)) {
          b.x += (cdx / cd) * shift
          b.y += (cdy / cd) * shift
        }
      }
    }
  }
  const groups = new Map<string, SimNode[]>()
  for (let gi = 0; gi < nodes.length; gi++) {
    const node = nodes[gi]
    const center = (placements.get(node.slug) || {}).center
    if (!center) continue
    const gk = center.x + ',' + center.y
    const group = groups.get(gk) || []
    group.push(node)
    groups.set(gk, group)
  }
  groups.forEach((group) => {
    if (group.some(fixed)) return
    let sx = 0
    let sy = 0
    for (let q = 0; q < group.length; q++) {
      sx += group[q].x
      sy += group[q].y
    }
    const parts = (placements.get(group[0].slug) || {}).center as Point
    const ddx2 = (parts.x - sx / group.length) * 0.03 * alpha
    const ddy2 = (parts.y - sy / group.length) * 0.03 * alpha
    for (let q2 = 0; q2 < group.length; q2++) {
      group[q2].x += ddx2
      group[q2].y += ddy2
    }
  })
}

/**
 * 集群轮廓（凸包 + 闭合三次样条）· `wikiGraphLayout.ts:589`（design-04 script:599）。
 *
 * <p>真机 16 节点坐标复算：逐字符比对只差 1 处末位舍入（Math.round）。
 */
export function graphClusterOutline(points: Array<{ x: number; y: number; radius: number }>, scale?: number): string {
  if (!points.length) return ''
  const zoom = Number.isFinite(scale) && (scale as number) > 0 ? (scale as number) : 1
  const padding = Math.min(22 / zoom, 60)
  const samples = 24
  const expanded: Point[] = []
  points.forEach((p) => {
    const radius = Math.max(0, p.radius || 0) + padding
    for (let i = 0; i < samples; i++) {
      expanded.push({
        x: p.x + Math.cos((i * 2 * Math.PI) / samples) * radius,
        y: p.y + Math.sin((i * 2 * Math.PI) / samples) * radius,
      })
    }
  })
  expanded.sort((a, b) => a.x - b.x || a.y - b.y)
  const unique = expanded.filter(
    (p, i) => !i || p.x !== expanded[i - 1].x || p.y !== expanded[i - 1].y
  )
  const cross = (o: Point, a: Point, b: Point) =>
    (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
  const half = (list: Point[]) => {
    const hull: Point[] = []
    for (let i = 0; i < list.length; i++) {
      const point = list[i]
      while (hull.length > 1 && cross(hull[hull.length - 2], hull[hull.length - 1], point) <= 0)
        hull.pop()
      hull.push(point)
    }
    hull.pop()
    return hull
  }
  const hull = half(unique).concat(half(unique.slice().reverse()))
  const at = (i: number) => hull[(i + hull.length) % hull.length]
  const fmt = (p: Point) =>
    Math.round(p.x * 10) / 10 + ',' + (Math.round(p.y * 10) / 10)
  const tangent = (from: Point, to: Point, length: number) => {
    const dx = to.x - from.x
    const dy = to.y - from.y
    const norm = Math.hypot(dx, dy) || 1
    return { x: (dx / norm) * length, y: (dy / norm) * length }
  }
  const path = ['M' + fmt(hull[0])]
  hull.forEach((p, i) => {
    const next = at(i + 1)
    const reachLen = Math.hypot(next.x - p.x, next.y - p.y) * 0.36
    const out = tangent(at(i - 1), next, reachLen)
    const into = tangent(p, at(i + 2), reachLen)
    path.push(
      'C' +
        fmt({ x: p.x + out.x, y: p.y + out.y }) +
        ' ' +
        fmt({ x: next.x - into.x, y: next.y - into.y }) +
        ' ' +
        fmt(next)
    )
  })
  return path.join(' ') + ' Z'
}

/**
 * 贪心屏幕空间标签避让 · `wikiGraphLayout.ts:541`（design-04 script:653）。
 *
 * <p>`required` 的标签即使重叠也要显示（选中 / 悬停 / 集群标题）。
 */
export function visibleGraphLabels(labels: LabelBox[]): Set<string> {
  const visible = new Set<string>()
  const occupied = new Map<string, LabelBox[]>()
  const cells = (label: LabelBox) => {
    const keys: string[] = []
    for (let x = Math.floor(label.left / 160); x <= Math.floor(label.right / 160); x++) {
      for (let y = Math.floor(label.top / 32); y <= Math.floor(label.bottom / 32); y++) {
        keys.push(x + ',' + y)
      }
    }
    return keys
  }
  labels
    .slice()
    .sort((a, b) => Number(!!b.required) - Number(!!a.required) || b.priority - a.priority)
    .forEach((label) => {
      const keys = cells(label)
      const overlaps = keys.some((key) =>
        (occupied.get(key) || []).some(
          (other) =>
            label.left < other.right &&
            label.right > other.left &&
            label.top < other.bottom &&
            label.bottom > other.top
        )
      )
      if (overlaps && !label.required) return
      visible.add(label.slug)
      keys.forEach((key) => {
        const bucket = occupied.get(key) || []
        bucket.push(label)
        occupied.set(key, bucket)
      })
    })
  return visible
}

/**
 * 初始视口 · `wikiGraphLayout.ts:574`（design-04 script:686）。
 *
 * <p>⚠️ `scale = clamp(fit, 0.4, 0.85)` —— 上界 0.85（曾误写成 2，
 * 让 ego 模式算出 1.235，节点被放大 45%，邻图看起来「不对」）。
 */
export function initialGraphViewport(
  points: Point[],
  width: number,
  height: number,
  focus?: Point | null
): { scale: number; x: number; y: number } {
  if (!points.length) return { scale: 1, x: width / 2, y: height / 2 }
  const left = Math.min(...points.map((p) => p.x))
  const right = Math.max(...points.map((p) => p.x))
  const top = Math.min(...points.map((p) => p.y))
  const bottom = Math.max(...points.map((p) => p.y))
  const fit = Math.min(
    Math.max(100, width - 120) / Math.max(100, right - left),
    Math.max(100, height - 120) / Math.max(100, bottom - top)
  )
  const scale = Math.max(0.4, Math.min(0.85, fit))
  const center = fit < 0.4 && focus ? focus : { x: (left + right) / 2, y: (top + bottom) / 2 }
  return { scale, x: width / 2 - center.x * scale, y: height / 2 - center.y * scale }
}

/**
 * 摘要页名的可读标题 · `wikiGraphLayout.ts:92`（design-04 script:702）。
 *
 * <p>去掉 ` - Summary` 后缀、扩展名与末尾的 `(1)`。
 */
export function landmarkTitle(title: string): string {
  const name = String(title)
    .replace(/\s*-\s*Summary$/i, '')
    .replace(
      /\.(pdf|docx?|pptx?|xlsx?|csv|md|txt|html?|json|ya?ml|xml|png|jpe?g)$/i,
      ''
    )
    .replace(/\s*[(（]\d+[)）]$/, '')
    .trim()
  return name || title
}
