/**
 * 用户气泡里的「技能身份」解析（方案 B / DSH 回落）。
 *
 * <p>规则（用户 2026-11-24 定）：
 * <ul>
 *   <li>命令目录里有 <b>displayName</b> ⇒ 气泡内顶部一行 chip（图标 + 中文名，Octop 式）；</li>
 *   <li>没有 displayName ⇒ 正文里的 <code>/name</code> 渲染成内联 chip（DSH 式）；</li>
 *   <li>目录里查不到（普通命令如 /init，或路径 <code>/root/file</code>）⇒ 原样，不加任何标识。</li>
 * </ul>
 *
 * <p>不落库：消息 content 仍是 <code>/name 参数</code>，渲染时按命令名反查目录。
 * 技能被卸载后历史消息自然回落为纯文本。
 */
import { commandApi, type CommandDto } from '@/api/command'

/** 参与匹配的最小目录项形状（测试可传假件）。 */
export interface SkillCatalogEntry {
  name: string
  displayName?: string | null
  icon?: string | null
  description?: string | null
}

export interface LeadingSkill<E extends SkillCatalogEntry> {
  /** 原文里的第一个 token（含 `/`，保留大小写） */
  token: string
  /** 注册名（不含 `/`） */
  name: string
  /** token 之后的剩余文本（已去掉前导空白） */
  rest: string
  entry: E
  /** 是否有可读展示名（决定 Octop chip 行 vs DSH 内联 chip） */
  named: boolean
}

/**
 * 只认**消息开头**的斜杠 token，且后面必须是空白或结尾：
 * 这样 `/root/file`（路径）不会被当成技能 `/root`。
 */
const LEADING_SKILL_RE = /^\s*\/([A-Za-z0-9_:.\-]+)(?=\s|$)/

/**
 * 解析用户消息开头的技能命令并到目录里查元数据。
 *
 * @param content 用户消息正文
 * @param entries 命令目录（{@code GET /api/command} 的 name/displayName/icon）
 * @return 命中返回解析结果；非技能开头 / 目录未命中返回 null
 */
export function matchLeadingSkill<E extends SkillCatalogEntry>(
  content: string,
  entries: readonly E[],
): LeadingSkill<E> | null {
  const m = LEADING_SKILL_RE.exec(content)
  if (!m) return null
  const name = m[1]
  const entry = entries.find((e) => e.name.toLowerCase() === name.toLowerCase())
  if (!entry) return null
  return {
    token: `/${name}`,
    name,
    rest: content.slice(m[0].length).replace(/^\s+/, ''),
    entry,
    named: (entry.displayName ?? '').trim().length > 0,
  }
}

/** 会话级命令目录缓存：同会话的多条消息共享一次请求（消息列表里每条都解析）。 */
const catalogCache = new Map<string, Promise<CommandDto[]>>()

/** 拉取（并缓存）某会话的命令目录；失败回落空数组 —— 渲染层按「无技能标识」处理，不阻塞消息。 */
export function loadSkillCatalog(sessionId: string | null | undefined): Promise<CommandDto[]> {
  const key = sessionId ?? ''
  let p = catalogCache.get(key)
  if (!p) {
    p = commandApi.list(false, sessionId || undefined).then(
      (list) => list ?? [],
      () => [],
    )
    catalogCache.set(key, p)
  }
  return p
}

/** 测试用：清空目录缓存（避免用例之间串数据）。 */
export function resetSkillCatalogCache(): void {
  catalogCache.clear()
}
