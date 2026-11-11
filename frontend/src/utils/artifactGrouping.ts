import type { SessionArtifactDto } from '@/api/artifacts'
import type { ChatMessageDto } from '@/api/types'
import { isUserTurnRow } from '@/stores/messageTurns'

/**
 * 产物「按对话分组」的纯函数（右侧栏「产物」tab · 2026-11-10）。
 *
 * <p>抽成纯函数而不是写在组件里，是为了让「分组/兜底去重」这两条规则可以被单测钉住 ——
 * 它们直接决定用户能否看出「这个文件是哪次对话产出的」，改错了在界面上不容易被发现
 * （只会表现为某个组里少了文件、或同一个文件重复出现）。
 */

/** 一组产物 = 一条用户提问（或「历史产物」兜底组）。 */
export interface ArtifactGroup {
  /** 分组键（turnId = 那条用户消息的 id）；兜底组为 {@link HISTORY_GROUP_KEY}。 */
  key: string
  /** 组头显示的提问原文（已压空白/截断）；兜底组为 null。 */
  question: string | null
  /** 组头时间（提问时间）；取不到为 null。 */
  createdAt: string | null
  /** 该组的产物（时间倒序）。 */
  items: SessionArtifactDto[]
}

/** 归属未知 / 归属键对不上任何真人提问时的组键。 */
export const HISTORY_GROUP_KEY = '__history__'

/** 时间倒序（updatedAt 缺失的行排最后，保持稳定次序）。 */
export function byTimeDesc<T extends { updatedAt: string | null }>(rows: T[]): T[] {
  return [...rows].sort((x, y) => {
    const tx = x.updatedAt ? Date.parse(x.updatedAt) : 0
    const ty = y.updatedAt ? Date.parse(y.updatedAt) : 0
    return ty - tx
  })
}

/**
 * 按「文件」去重（**存量脏数据兜底**）。
 *
 * <p><b>WHY 需要它</b>：后端 V276~V278 的唯一键含轮次，而轮末扫描把「本轮 id」喂给交付物目录里
 * 每一个文件 ⇒ 同一文件每轮插一行。实测某会话 28 个真实文件产生 889 行、横跨 108 个轮次。
 * 后端幂等键已在 V279 修掉，但存量脏行不会自动消失 ⇒ 前端读时去重，否则「按对话分组」
 * 会把同一个文件在几十个组里各显示一次。
 *
 * <p>去重键 = {@code localPath}（来源类条目无路径 → 回落 {@code title}）。
 * 保留的是**时间最新**的那一条（传入前已按时间倒序 ⇒ 先到的胜出）。
 *
 * <p>⚠️ 返回的是**原对象引用**（不造新对象）：{@code ArtifactCards} 是 memo 组件，
 * 每次造新对象会让整个网格重渲染。
 */
export function dedupeByFile(rows: SessionArtifactDto[]): SessionArtifactDto[] {
  const seen = new Map<string, SessionArtifactDto>()
  for (const a of byTimeDesc(rows)) {
    const key = a.localPath || `title:${a.title}`
    if (!seen.has(key)) seen.set(key, a)
  }
  return [...seen.values()]
}

/**
 * 补充的系统注入正文前缀（**产物分组专用**，不回写共用判据）。
 *
 * <p><b>WHY 不并进 {@code isUserTurnRow}</b>：那个判据同时驱动轮次渲染与窗口裁剪，
 * 改它会改变「一条消息算不算开新轮」的全局语义 —— 那是另一个决策面。这里只需要
 * 「别把它当组头文案」这一个更窄的效果。
 *
 * <p>实测依据（真库）：{@code A session-scoped Stop hook is now active…} 这条消息
 * {@code is_meta=0}、正文不以 {@code <} 开头，既有的
 * {@code NOTIFICATION_CONTENT_PREFIXES} 也没列它 ⇒ 会被当成真人提问，导致某个产物组的
 * 组头显示成一串 hook 日志。
 */
const EXTRA_SYSTEM_PREFIXES: ReadonlyArray<string> = [
  'A session-scoped Stop hook',
]

/** 该行是否是系统注入（既有的真人提问判据 + 上面补充的前缀）。 */
function isHumanTurn(m: ChatMessageDto): boolean {
  if (!isUserTurnRow(m)) return false
  const text = (m.content ?? '').trimStart()
  return !EXTRA_SYSTEM_PREFIXES.some((p) => text.startsWith(p))
}

/**
 * 从消息列表算出「轮次键 → 提问原文/时间」。
 *
 * <p><b>⛔ 只收真人提问</b>（复用 {@link isUserTurnRow} 单点真源）：系统注入行
 * （{@code <system-reminder>} / {@code <task-notification>} / 「A session-scoped Stop hook…」）
 * 在 DB 里同样是 role=user，把它们当提问显示会变成「这一轮问的是一串 hook 日志」。
 * 这类轮次不产生条目 ⇒ 其产物自然落到「历史产物」组。
 *
 * <p>提问原文压成单行并截断到 160 字：组头只有两行高度，长提问会挤掉时间与产物数。
 */
export function buildTurnContext(
  messages: ChatMessageDto[],
): Map<string, { question: string; createdAt: string | null }> {
  const out = new Map<string, { question: string; createdAt: string | null }>()
  for (const m of messages) {
    if (!isHumanTurn(m)) continue
    const text = (m.content ?? '').replace(/\s+/g, ' ').trim()
    if (!text) continue   // 纯附件提问没有正文 → 不进映射（否则组头是空白）
    out.set(m.id, {
      question: text.length > 160 ? `${text.slice(0, 160)}…` : text,
      createdAt: m.createdAt ?? m.time ?? null,
    })
  }
  return out
}

/**
 * 产物 → 按对话分组（组间时间倒序，组内时间倒序）。
 *
 * <p>分组键 = {@code sourceMessageId}（= 产出它的那一轮用户消息 id，与后端
 * {@code listByMessage} 同口径）。以下三种情况一律进「历史产物」组，⛔ 不猜归属：
 * <ul>
 *   <li>{@code sourceMessageId} 为 null（V276 之前的历史行）；</li>
 *   <li>归属键在消息列表里找不到（排队消息 id 未回填真实 id 的孤儿键）；</li>
 *   <li>归属键对上的那条消息不是真人提问（系统注入行）。</li>
 * </ul>
 */
export function groupArtifactsByTurn(
  items: SessionArtifactDto[],
  turnContext: Map<string, { question: string; createdAt: string | null }>,
): ArtifactGroup[] {
  const buckets = new Map<string, SessionArtifactDto[]>()
  for (const a of items) {
    const owner = a.sourceMessageId
    const key = owner && turnContext.has(owner) ? owner : HISTORY_GROUP_KEY
    const list = buckets.get(key)
    if (list) list.push(a)
    else buckets.set(key, [a])
  }

  const groups: ArtifactGroup[] = []
  for (const [key, rows] of buckets) {
    const ctx = turnContext.get(key)
    groups.push({
      key,
      question: ctx?.question ?? null,
      createdAt: ctx?.createdAt ?? null,
      items: byTimeDesc(rows),
    })
  }

  // 组间排序：有时间的按时间倒序；「历史产物」组恒排最后（它没有时间语义，
  // 排中间会打断「最近的对话在最上面」这个阅读顺序）。
  groups.sort((x, y) => {
    if (x.key === HISTORY_GROUP_KEY) return 1
    if (y.key === HISTORY_GROUP_KEY) return -1
    const tx = x.createdAt ? Date.parse(x.createdAt) : 0
    const ty = y.createdAt ? Date.parse(y.createdAt) : 0
    return ty - tx
  })
  return groups
}
