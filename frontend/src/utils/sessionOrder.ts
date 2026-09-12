/**
 * 排序只消费这两个字段。`updatedAt` 运行时可为 `null` —— 后端 `SessionService.toDto` 走
 * `parseDateTime`，解析失败即 `null`（JSON `null`），而前端 `SessionDto.updatedAt?: string`
 * 类型只标了「可省略」、未标 `null`；此处按**运行时实际**放宽（`SessionDto` 仍可赋入）。
 */
export interface SessionOrderKey {
  id: string
  updatedAt?: string | null
}

/**
 * 会话排序的**唯一口径**：`updatedAt` 降序（最近活动在前），同值按 `id` 升序。
 *
 * <p>与后端 {@code GET /sessions} 的 {@code ORDER BY updated_at DESC, id ASC}
 * （backend/src/main/java/com/nexusai/domain/session/SessionService.java list()）是**同一条口径** ——
 * 改动本函数必须同步改后端，否则前端重排的结果会与后端序不一致（那正是本次要修的缺陷：
 * 新建会话「前插到顶部」与 F5 重拉「回落 DB 序」两套口径打架 → 用户看到「顺序不固定」）。
 *
 * <p>**为什么 tie-breaker 用普通字符串比较而非 localeCompare**：id 为 `sess-` 前缀的 ASCII，
 * 普通比较跨环境确定；localeCompare 受 locale 影响、跨环境可能不同。后端 SQLite 对 TEXT 列
 * 也是朴素字节序比较，两者因此逐字节一致。
 *
 * <p>**为什么 updatedAt 也直接做字符串比较（不转 Date）**：`updated_at` 是 TEXT 列，
 * 后端 ORDER BY 走的就是文本序。转成 Date 反而会引入 ms 截断 + 本地时区解析两条与后端不同的口径。
 *
 * <p>⚠️ **前端拿到的字面量与 DB 里的字面量【并不相同】—— 一致性靠的是「两端各自保序、且实测同序」，
 * 不是「两端字面一致」**（此前本注释写成"列内实际写入格式统一…文本序==时间序"，只对 DB 侧成立，
 * 对前端不成立）：
 * <ul>
 *   <li>DB 侧：写入 `OffsetDateTime.now().toString()` → **补零到 9 位**小数
 *       （实测真库 13 行全部形如 `2026-09-08T15:17:25.779593700+08:00`）→ 文本序 == 时间序。</li>
 *   <li>前端侧：消费的是 **Jackson 规范化后**的字符串 → **裁掉尾随零**
 *       （同一行序列化成 `2026-09-08T15:17:25.7795937+08:00`）。真库 13 行里 **12 行**两侧字面不同。</li>
 *   <li>因此同一时刻的两种写法在朴素字符串比较下 **不相等**（`…937+` 的 `+`(0x2B) < `…93700+` 的 `0`(0x30)），
 *       但**都不会造成时序倒置** —— 对任意第三个值，两者落在同一侧。方向仍正确。</li>
 *   <li>**保序前提**：Jackson 规范化只裁尾零、不改前导数字（`sessionOrder.test.ts` 的
 *       「混合精度」用例钉住该前提）。若将来有人改 Jackson 配置（如加 `@JsonFormat`
 *       改输出精度/时区），该用例会显式变红，而不是静默破序。</li>
 * </ul>
 *
 * <p>**为什么参数字段名/类型以 `SessionOrderKey` 为准**：字段名与类型以 `@/api/types` 的
 * `SessionDto` 为唯一权威（`updatedAt?: string`，**ISO 字符串**，不是毫秒数），本接口是它的
 * 结构化子集 → 字段改名/改类型会在此处编译期暴露。
 */
export function compareSessions(a: SessionOrderKey, b: SessionOrderKey): number {
  // updatedAt 可空（后端 parseDateTime 失败 → null）：空值视作空串 → 降序时沉底。
  // 两端都空 → 落到 id 升序，仍是确定序（不返回 0 依赖 Array#sort 稳定性）。
  const ua = a.updatedAt ?? ''
  const ub = b.updatedAt ?? ''
  if (ua > ub) return -1
  if (ua < ub) return 1
  if (a.id < b.id) return -1
  if (a.id > b.id) return 1
  return 0
}

/**
 * 组内「最新活动时间」= 该组各会话 `updatedAt` 的最大值（空值不参与）。
 * 供 {@link compareGroups} 使用（最近活动的组在前）——dsh 的组间序来自 Host 持久化的
 * 手工序，本项目没有那套持久化，故取「组内最新 updatedAt」这一可从数据推导的确定口径。
 */
export function latestUpdatedAt(sessions: Pick<SessionOrderKey, 'updatedAt'>[]): string {
  let latest = ''
  for (const s of sessions) {
    const u = s.updatedAt ?? ''
    if (u > latest) latest = u
  }
  return latest
}

/** {@link compareGroups} 的入参：组键（`mainProjectId ?? '__unbound__'`）+ 该组会话。 */
export interface SessionGroupOrderKey {
  key: string
  sessions: Pick<SessionOrderKey, 'updatedAt'>[]
}

/**
 * **分组标题排序的唯一口径**：`'__unbound__'`（未分组）恒末尾 → 组内最新 `updatedAt` 降序
 * （最近活动的组在前）→ 同值时按组键升序兜底。
 *
 * <p><b>WHY 必须导出而非 inline 在 JSX 里</b>：原实现 inline 在 `SessionList.tsx` 的
 * `.sort(...)` 中、对两个非 unbound 组**恒返回 0** —— 它正是本次缺陷的同一处，却因 inline
 * 而**零测试覆盖**。提取为导出纯函数后可直接测（含全序性）。
 *
 * <p><b>WHY 同值要按组键兜底</b>：与 {@link compareSessions} 的 id 兜底完全同源 —— 同值时
 * 只有偏序，组间序会回落到 `Map` 插入序（随 sessions 传入序漂移），那正是本次要修的缺陷类别。
 * 补组键升序后成为**确定的全序**（与输入顺序无关）。
 *
 * <p>组内最新 `updatedAt` 的口径理由见 {@link latestUpdatedAt}（dsh 的组间持久手工序本项目没有）。
 */
export function compareGroups(a: SessionGroupOrderKey, b: SessionGroupOrderKey): number {
  const aUnbound = a.key === '__unbound__'
  const bUnbound = b.key === '__unbound__'
  if (aUnbound || bUnbound) return aUnbound && bUnbound ? 0 : aUnbound ? 1 : -1
  const ua = latestUpdatedAt(a.sessions)
  const ub = latestUpdatedAt(b.sessions)
  if (ua > ub) return -1
  if (ua < ub) return 1
  return a.key < b.key ? -1 : a.key > b.key ? 1 : 0
}
