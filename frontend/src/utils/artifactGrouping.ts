import type { SessionArtifactDto } from '@/api/artifacts'
import type { ChatMessageDto } from '@/api/types'
import { NOTIFICATION_CONTENT_PREFIXES, isUserTurnRow } from '@/stores/messageTurns'

/**
 * 产物「按对话分组」的纯函数（右侧栏「产物」tab · 2026-11-10）。
 *
 * <p>抽成纯函数而不是写在组件里，是为了让「分组/兜底去重」这两条规则可以被单测钉住 ——
 * 它们直接决定用户能否看出「这个文件是哪次对话产出的」，改错了在界面上不容易被发现
 * （只会表现为某个组里少了文件、或同一个文件重复出现）。
 *
 * <p><b>提问来源有两级</b>（2026-11-12）：
 * <ol>
 *   <li><b>产物自带字段优先</b>：后端在每条产物上 join 出 {@code turnQuestion / turnCreatedAt}
 *       （见 {@code SessionArtifactDto}）⇒ 与前端加载了多少消息无关；</li>
 *   <li><b>窗口映射回退</b>：{@link buildTurnContext} 从当前消息窗口算 —— 只保留最近 2 轮
 *       （{@code FOLD_DEFAULT_TURNS=2}）⇒ 更早轮次的产物在这里找不到提问。</li>
 * </ol>
 *
 * <p><b>WHY 需要第 1 级</b>：只靠窗口映射时，凡是早于窗口的产物全部落进「历史产物」组 ——
 * 真机表现为「1 个产物组 + 历史产物 3 个」，用户看不出那几个文件是哪次对话产出的。
 * 第 2 级保留是为了兼容**未升级的后端**（新字段可能同时缺失）。
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

/** `<system-reminder>` 注入块的首尾标签（成对判据，与 messageTurns 同口径）。 */
const SYSTEM_REMINDER_OPEN = '<system-reminder>'
const SYSTEM_REMINDER_CLOSE = '</system-reminder>'

/**
 * 一段**正文**是否像真人提问（只判文本，不看 role / isMeta 这类行属性）。
 *
 * <p><b>WHY 需要「只判正文」的版本</b>：产物自带的 {@code turnQuestion} 是一串裸文本，
 * 拿不到 DB 行的 {@code isMeta} / {@code subtype} ⇒ {@link isUserTurnRow} 那套行判据用不上。
 * 但判据必须与它**同一口径**，否则同一条消息「作为消息行」与「作为产物字段」会被判成两种东西。
 *
 * <p>判据（命中任一即为 false）：
 * <ul>
 *   <li>{@code null} / {@code undefined}，或 trim 后为空（纯附件提问没有正文）；</li>
 *   <li>以 {@link NOTIFICATION_CONTENT_PREFIXES} 或 {@link EXTRA_SYSTEM_PREFIXES} 任一为前缀；</li>
 *   <li>整条正文被 {@code <system-reminder>…</system-reminder>} **成对**包裹
 *       （⛔ 不能只判开头：用户正文引用半截标签时开头也会命中）。</li>
 * </ul>
 */
export function isHumanQuestionText(raw: string | null | undefined): boolean {
  if (raw == null) return false
  const text = raw.trim()
  if (!text) return false
  if (text.startsWith(SYSTEM_REMINDER_OPEN) && text.endsWith(SYSTEM_REMINDER_CLOSE)) return false
  if (NOTIFICATION_CONTENT_PREFIXES.some((p) => text.startsWith(p))) return false
  if (EXTRA_SYSTEM_PREFIXES.some((p) => text.startsWith(p))) return false
  return true
}

/**
 * 组头用的提问格式化：压成单行 + 截断到 60 字。
 *
 * <p>⛔ 窗口映射与产物自带字段**共用这一个**（两份规则必然漂移 ⇒ 同一个提问从两条通道
 * 出来会显示成两个样子）。
 *
 * <p><b>为什么是 60</b>：组头只有两行高度（{@code .gc-q} 的 {@code -webkit-line-clamp:2}），
 * 12.5px 字号下一行约容纳 25~28 个中文字 ⇒ 两行容量 ≈ 50~56 字。留少量余量取 60。
 * ⛔ 不要把这里放大到几百字：文本先截断再交给 CSS 裁剪，超出的部分是「截断了但看不见」
 * 的纯浪费；而一旦 {@code -webkit-line-clamp} 在某个布局上下文里失效，整段会直接展开、
 * 撑高卡片头部（用户实测「一下子很多就会出渲染问题」）。JS 截断与 CSS 行数必须同量级。
 */
function normalizeQuestion(text: string): string {
  const flat = text.replace(/\s+/g, ' ').trim()
  return flat.length > 60 ? `${flat.slice(0, 60)}…` : flat
}

/** 该行是否是系统注入（既有的真人提问判据 + 上面补充的前缀）。 */
function isHumanTurn(m: ChatMessageDto): boolean {
  return isUserTurnRow(m) && isHumanQuestionText(m.content)
}

/**
 * 从消息列表算出「轮次键 → 提问原文/时间」（**窗口回退通道**）。
 *
 * <p><b>⛔ 只收真人提问</b>（复用 {@link isUserTurnRow} + {@link isHumanQuestionText} 单点真源）：
 * 系统注入行（{@code <system-reminder>} / {@code <task-notification>} / 「A session-scoped Stop hook…」）
 * 在 DB 里同样是 role=user，把它们当提问显示会变成「这一轮问的是一串 hook 日志」。
 * 这类轮次不产生条目 ⇒ 其产物自然落到「历史产物」组。
 *
 * <p>提问原文压成单行并截断到 60 字（见 {@link normalizeQuestion}）。
 *
 * <p>⚠️ 只覆盖**当前消息窗口**（最近 2 轮）⇒ 更早轮次必须靠产物自带字段
 * （{@link mergeTurnContext}）。
 */
export function buildTurnContext(
  messages: ChatMessageDto[],
): Map<string, { question: string; createdAt: string | null }> {
  const out = new Map<string, { question: string; createdAt: string | null }>()
  for (const m of messages) {
    if (!isHumanTurn(m)) continue
    const text = normalizeQuestion(m.content ?? '')
    if (!text) continue   // 纯附件提问没有正文 → 不进映射（否则组头是空白）
    out.set(m.id, {
      question: text,
      createdAt: m.createdAt ?? m.time ?? null,
    })
  }
  return out
}

/**
 * 把产物自带字段并进窗口映射（**产物自带字段优先，窗口映射回退**）。
 *
 * <p>规则：
 * <ul>
 *   <li>先浅拷贝 {@code windowCtx}（⛔ 不改入参 —— 调用方的 useMemo 结果要能被复用）；</li>
 *   <li>产物 {@code turnQuestion} 合法时**覆盖**同键的窗口值：后端是**全量**真源（`messages`
 *       全表按 `source_message_id` 联查），窗口只是「最近 2 轮」的局部视图 —— 两者冲突时
 *       以全量为准（2026-11-12 用户裁定「默认查全部的 而不是当前窗口」）；</li>
 *   <li>产物没有合法提问（null / hook 日志 / 注入块）时**不动**窗口值 —— 回退而不是清空；</li>
 *   <li>{@code turnCreatedAt} 同理：产物带了就用，没带回落窗口已有时间（⛔ 不能用 null 冲掉）。</li>
 * </ul>
 *
 * <p><b>WHY</b>：窗口只有 2 轮，更早轮次的产物在窗口里找不到提问 ⇒ 现在由后端在产物上带出，
 * 不再依赖窗口大小。
 */
export function mergeTurnContext(
  items: SessionArtifactDto[],
  windowCtx: Map<string, { question: string; createdAt: string | null }>,
): Map<string, { question: string; createdAt: string | null }> {
  const out = new Map(windowCtx)
  for (const a of items) {
    const owner = a.sourceMessageId
    if (!owner) continue
    const raw = a.turnQuestion ?? ''
    if (!isHumanQuestionText(raw)) continue
    // 全量真源覆盖窗口局部视图；时间缺失时保留窗口已有的（不制造 null 回退）。
    const prev = out.get(owner)
    out.set(owner, {
      question: normalizeQuestion(raw),
      createdAt: a.turnCreatedAt ?? prev?.createdAt ?? null,
    })
  }
  return out
}

/**
 * 产物 → 按对话分组（组间时间倒序，组内时间倒序）。
 *
 * <p>分组键 = {@code sourceMessageId}（= 产出它的那一轮用户消息 id，与后端
 * {@code listByMessage} 同口径）。归属判据走 {@link mergeTurnContext} 的合并结果
 * （产物自带 {@code turnQuestion} 优先，窗口映射回退）。以下三种情况一律进「历史产物」组，
 * ⛔ 不猜归属：
 * <ul>
 *   <li>{@code sourceMessageId} 为 null（V276 之前的历史行）；</li>
 *   <li>归属键既不在窗口映射里、产物也没带合法 {@code turnQuestion}（排队消息 id 未回填真实 id
 *       的孤儿键 / 老后端 / 该轮不是真人提问）；</li>
 *   <li>归属键对上的那条消息不是真人提问（系统注入行）。</li>
 * </ul>
 */
export function groupArtifactsByTurn(
  items: SessionArtifactDto[],
  turnContext: Map<string, { question: string; createdAt: string | null }>,
): ArtifactGroup[] {
  // 提问来源两级：产物自带字段优先，窗口映射回退（见模块顶部说明）。
  const ctx = mergeTurnContext(items, turnContext)
  const buckets = new Map<string, SessionArtifactDto[]>()
  for (const a of items) {
    const owner = a.sourceMessageId
    const key = owner && ctx.has(owner) ? owner : HISTORY_GROUP_KEY
    const list = buckets.get(key)
    if (list) list.push(a)
    else buckets.set(key, [a])
  }

  const groups: ArtifactGroup[] = []
  for (const [key, rows] of buckets) {
    const groupCtx = ctx.get(key)
    groups.push({
      key,
      question: groupCtx?.question ?? null,
      createdAt: groupCtx?.createdAt ?? null,
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
