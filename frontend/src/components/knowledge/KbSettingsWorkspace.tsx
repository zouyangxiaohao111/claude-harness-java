import { useCallback, useEffect, useState } from 'react'
import type { JSX, ReactNode } from 'react'
import { api, ApiError } from '@/api/rest'
import { KB_SETTINGS_NAV, KB_SETTING_NOT_READY } from './kbSettingsTypes'

/**
 * 「空间」组面板 · 按 navKey 渲染 5 个子面板。
 *
 * <h2>5 项的能力盘点（用户 2026-11-08 口径：后端有的则对接，没有就「暂未开发」）</h2>
 * <ul>
 *   <li><b>tenant 空间信息 / members 成员管理</b>：用户体系暂不接入（当前单机单主体），
 *       没有空间实体与成员账号可读写 ⇒ 占位。</li>
 *   <li><b>defaults 默认配置</b>：默认模型由客户端全局设置统一决定，没有空间级默认值 ⇒ 占位。</li>
 *   <li><b>chathistory 消息管理</b>：用户 2026-11-08 明确「暂不迁移」；
 *       后端 `backend/src/main/java` 侧对 chatHistory / 消息管理<b>零命中</b>（确实没有实现）⇒ 占位，
 *       并在 desc 里点明「消息索引与检索配置尚未迁移到本仓」。</li>
 *   <li><b>memory 长期记忆</b>：<b>真接</b>后端（V272 建 6 张表 + `KbMemoryController` 16 端点）。
 *       按源站 12 项形态渲染，其中 1 项可交互、3 项只读、8 项置灰，详见下方常量注释。</li>
 * </ul>
 *
 * <h2>⚠️ 与客户端全局设置是两份，互不影响</h2>
 * 客户端那份是 `components/modals/SettingsModal.tsx` + `MemoryEditorModal.tsx`（AgentLoop 的
 * 记忆文件 / auto-memory 开关）。本组件只服务<b>知识库场景内</b>的设置窗（`.kb-scope` 子树），
 * 端点也不同（本仓用 `/api/v1/kb/memory/settings`，即 {@link KbMemoryController}）。
 *
 * <h2>类名依据</h2>
 * 逐层照源站 `views/settings/MemoryWorkspaceSettings.vue:19-197` 的通用骨架：
 * `.section > .section-header(h2 + .section-description) + .settings-group >
 * .setting-row > .setting-info(label + .desc) + .setting-control`（样式见 `styles/kb/kb-10.css:19-30`）。
 * 只用了派单允许的既有类名，未新增任何 class，也未写 CSS。
 */

/**
 * 长期记忆设置 · 后端契约。
 *
 * <p>字段与 `backend/.../kb/dto/memory/KbMemorySettingsDto.java:13-22` 逐字对应
 * （后端用 `@JsonProperty` 钉死成 snake_case，⛔ 这里不转 camelCase，避免多一层转错就静默丢字段）。
 *
 * <ul>
 *   <li>{@code workspace_enabled}：空间级总开关（本仓只读，无改它的端点）。</li>
 *   <li>{@code user_enabled}：我的开关 —— 也是 `PUT /kb/memory/settings` <b>唯一</b>能写的字段。</li>
 *   <li>{@code effective}：后端算好的 {@code workspace_enabled ∧ user_enabled}（原因见 DTO javadoc
 *       「so 'why is my memory off' has exactly one answer」）。</li>
 *   <li>{@code write_mode} / {@code max_items}：本仓只读（`SettingsRequest` 只有 enabled 一个字段）。</li>
 *   <li>{@code item_count}：当前 active 条目数。</li>
 * </ul>
 */
interface KbMemorySettings {
  workspace_enabled: boolean
  user_enabled: boolean
  effective: boolean
  write_mode: string
  item_count: number
  max_items: number
}

/**
 * 长期记忆设置端点（相对 `api()` 的 base = `/api/v1`）。
 *
 * <p>⭐⭐ <b>必须带 {@code /kb} 段</b>：后端 `KbMemoryController.java:47` 是
 * {@code @RequestMapping("/api/v1/kb/memory")}。这个 {@code kb} 段是<b>刻意隔离</b> ——
 * `com.nexusai.apis.memory.MemoryController`（AgentLoop 的 `/files` / `/config`）占着
 * `/api/v1/memory`，两套记忆是无关数据。若此处写成 `/memory/settings`，会打到 AgentLoop 那个
 * Controller 上（无该子路径 ⇒ 404），而<b>不会报错到让人看出是路径写错</b>。
 *
 * <p>{@code api()} 的 base 已包含 `/api/v1`（`api/rest.ts:11` `const BASE_URL = API_V1_BASE`），
 * 所以这里只写到 `/kb/memory/settings`。
 */
const MEMORY_SETTINGS_PATH = '/kb/memory/settings'

/** 后端异常 → 可读中文（400 走字段错误，其余走 detail / statusText）。 */
function toMessage(e: unknown): string {
  if (e instanceof ApiError) return e.userMessage()
  if (e instanceof Error) return e.message
  return String(e)
}

/** 从导航契约取显示名，保证右侧标题与左侧导航逐字一致（单一真源，改名不会两边漂移）。 */
function workspaceLabel(navKey: string): string {
  const group = KB_SETTINGS_NAV.find((g) => g.key === 'workspace')
  return group?.items.find((i) => i.key === navKey)?.label ?? '未知设置项'
}

/** 一行设置：`.setting-row > .setting-info(label + .desc) + .setting-control`。 */
function Row({ label, desc, hint, children }: {
  label: string
  desc: string
  /** 第二条说明（源站 `.desc.hint` 的位置；这里复用 `.desc`，不新增类名）。 */
  hint?: string
  children: ReactNode
}) {
  return (
    <div className="setting-row">
      <div className="setting-info">
        <label>{label}</label>
        <p className="desc">{desc}</p>
        {hint && <p className="desc">{hint}</p>}
      </div>
      <div className="setting-control">{children}</div>
    </div>
  )
}

/** 8 个「暂未开发」项的文案与显示条件（顺序 = 源站 `MemoryWorkspaceSettings.vue` 行序）。 */
interface NotReadyRow {
  label: string
  desc: string
  /**
   * 显示条件，逐条对应源站的 `v-if`：
   * <ul>
   *   <li>{@code auto} = 源站 `config.enabled && config.write_mode === 'auto'`
   *       （第 54 / 70 / 88 / 147 / 164 行）；</li>
   *   <li>{@code enabled} = 源站 `config.enabled`（第 106 / 116 / 133 行）。</li>
   * </ul>
   */
  when: 'auto' | 'enabled'
}

/**
 * 源站第 3~10 项（连续 8 行，正好夹在第 2 项与第 11 项之间）。
 *
 * <p>⚠️ <b>有意偏离</b>：源站第 7 项「记忆 Embedding 模型」的条件是
 * `config.enabled && config.vector_recall`（第 116 行），但本仓后端 DTO
 * **没有 `vector_recall` 字段**（`KbMemorySettingsDto` 只有 6 个字段），拿不到该值
 * ⇒ 按 `enabled` 一条条件显示（与派单口径一致）。等后端补上该字段再收紧，⛔ 不编造一个本地假值。
 *
 * <p>文案取自源站 zh-CN i18n（`src/i18n/locales/zh-CN.ts:7099-7135`），保证与源站同义。
 */
const NOT_READY_ROWS: NotReadyRow[] = [
  {
    label: '抽取模型',
    desc: '留空则使用该次会话所用的模型。',
    when: 'auto',
  },
  {
    label: '抽取延迟',
    desc: '一轮对话结束后等待多久再挖掘。等一等可以让一次模型调用覆盖用户连着发的几条消息。',
    when: 'auto',
  },
  {
    label: '抽取最小间隔',
    desc: '同一个人两次挖掘之间至少间隔多久，用来控制成本。间隔内产生的消息不会被丢弃，会顺延到下一次挖掘一并处理。',
    when: 'auto',
  },
  {
    label: '向量召回',
    desc: '除了字面匹配，再按含义匹配。用户换个说法之后，原来那条记忆仍然能被找到——而多数记忆迟早会被换说法。每轮问答多一次向量调用，超时会自动退回字面匹配。',
    when: 'enabled',
  },
  {
    label: 'Embedding 模型',
    desc: '语义召回只使用这一个模型，与各知识库绑定的 Embedding 无关。不选则只按字面匹配。',
    when: 'enabled',
  },
  {
    label: '检索条件化',
    desc: '开启后，记忆会参与查询改写和文档排序，而不只是附加到回答提示里。这是记忆在知识库产品里真正起作用的地方。',
    when: 'enabled',
  },
  {
    label: '兴趣阈值',
    desc: '同一个主题被问到这么多次后，才会作为长期关注记下来。设为 1 会把每个一次性问题都记下来，通常太吵。',
    when: 'auto',
  },
  {
    label: '抽取指令',
    desc: '追加到挖掘提示词里的空间规则，用来表达产品猜不到的策略，例如「永远不要记录客户姓名」。',
    when: 'auto',
  },
]

/**
 * 「长期记忆」面板（源站 `views/settings/MemoryWorkspaceSettings.vue`，全部 378 行已逐行对照）。
 *
 * <h2>12 项落法</h2>
 * <ol>
 *   <li>启用长期记忆 —— <b>真接</b>（读 `user_enabled`，写 `PUT /kb/memory/settings`）。</li>
 *   <li>写入模式 —— <b>只读</b>展示 `write_mode`；源站第 30 行也有 `v-if="config.enabled"`，
 *       这里同样只在开关打开时显示。</li>
 *   <li>抽取模型 / 4. 抽取延迟 / 5. 抽取最小间隔 —— 暂未开发（enabled + auto）。</li>
 *   <li>向量召回 / 7. Embedding 模型 / 8. 检索条件化 —— 暂未开发（enabled）。</li>
 *   <li>兴趣阈值 / 10. 抽取指令 —— 暂未开发（enabled + auto）。</li>
 *   <li>最大条目数 —— <b>只读</b>展示 `max_items`（源站第 181 行 `v-if="config.enabled"`）。</li>
 *   <li>已记忆条目 —— <b>只读</b>展示 `item_count`。⚠️ 源站<b>没有这一行</b>，
 *       是派单新增的第 12 项；`item_count` 在关闭状态下仍有意义（历史已存条目），所以恒显示。</li>
 * </ol>
 */
function KbMemoryPanel(): JSX.Element {
  const [data, setData] = useState<KbMemorySettings | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setLoadError(null)
    try {
      // GET /api/v1/kb/memory/settings（KbMemoryController.java:61-64）· 直接返回 DTO，无 {success,data} 信封
      setData(await api<KbMemorySettings>(MEMORY_SETTINGS_PATH))
    } catch (e) {
      setData(null)
      setLoadError(toMessage(e))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void load() }, [load])

  /**
   * 翻转「我的开关」：乐观更新 + 失败回滚。
   *
   * <p>⚠️ 请求体只有 `enabled`（`KbMemoryController.SettingsRequest`，第 294 行）——
   * 后端是 <b>PATCH 语义</b>：不传 = 不改动。⛔ 绝不把 `write_mode` / `max_items` 一起发过去，
   * 那会让前端以为自己能改这两个只读字段。
   */
  const toggleEnabled = async () => {
    if (!data || saving) return
    const prev = data
    const next = !data.user_enabled
    // 乐观更新：开关立刻跟手，避免等一次网络往返
    setData({ ...data, user_enabled: next, effective: data.workspace_enabled && next })
    setSaving(true)
    setSaveError(null)
    try {
      // PUT /api/v1/kb/memory/settings（KbMemoryController.java:74-81）→ 返回更新后的完整设置
      const updated = await api<KbMemorySettings>(MEMORY_SETTINGS_PATH, {
        method: 'PUT',
        body: { enabled: next },
      })
      // 以后端返回为准（effective / item_count 等由后端重算）
      setData(updated)
    } catch (e) {
      // 回滚到点击前的位置，并把失败原因如实说出来（⛔ 不静默假装成功）
      setData(prev)
      setSaveError(toMessage(e))
    } finally {
      setSaving(false)
    }
  }

  const enabled = data?.user_enabled === true
  const isAuto = data?.write_mode === 'auto'
  const notReadyRows = NOT_READY_ROWS.filter((r) => (r.when === 'auto' ? enabled && isAuto : enabled))

  return (
    <div className="section">
      <div className="section-header">
        <h2>{workspaceLabel('memory')}</h2>
        {/* 源站记忆页 description（zh-CN.ts:7101）；本仓单机单主体，把「成员」改述为「你」 */}
        <p className="section-description">让助手跨会话记住你说过的个人信息、偏好、事实与在办事项。</p>
      </div>

      {/* 源站 intro 块（MemoryWorkspaceSettings.vue:11-17）：开关默认关，所以在这里把后果说清楚 */}
      <p className="desc">⚠️ 长期记忆默认关闭。开启后会跨会话保留你与助手的对话要点。</p>

      {loading && <p className="desc">正在加载长期记忆设置…</p>}

      {!loading && loadError && (
        <p className="desc">加载失败：{loadError}（切换到其它设置项再切回可重试）</p>
      )}

      {!loading && !loadError && data && (
        <>
          <div className="settings-group">
            {/* 1 · 启用长期记忆 —— 唯一真接的一行 */}
            <Row
              label="启用长期记忆"
              desc="关闭后你的会话都不会读取或写入记忆。"
              hint={!data.workspace_enabled
                ? '空间级开关当前处于关闭状态：即使这里打开也不会生效（生效 = 空间开关 ∧ 我的开关）。'
                : undefined}
            >
              <button
                type="button"
                className={`t-switch t-size-m${enabled ? ' t-is-checked' : ''}`}
                role="switch"
                aria-checked={enabled}
                aria-label="启用长期记忆"
                disabled={saving}
                onClick={() => void toggleEnabled()}
              >
                <span className="t-switch__handle" />
              </button>
            </Row>

            {/* 2 · 写入模式 —— 只读（PUT 只能改 enabled，改不了 write_mode） */}
            {enabled && (
              <Row
                label="写入模式"
                desc="决定什么内容会被记住。"
                hint={isAuto
                  ? '在此基础上，会话结束后在后台调用一次模型，从你自己说过的话里提炼值得长期保留的内容。'
                  : '只记录你明确说「记住：……」的内容，以及在记忆页手动添加的条目，不额外调用模型。'}
              >
                <span>{writeModeText(data.write_mode)}</span>
              </Row>
            )}

            {/* 3~10 · 暂未开发（条件逐条对齐源站 v-if，见 NOT_READY_ROWS 注释） */}
            {notReadyRows.map((item) => (
              <Row key={item.label} label={item.label} desc={item.desc}>
                <span className="ext-tag">{KB_SETTING_NOT_READY}</span>
              </Row>
            ))}

            {/* 11 · 最大条目数 —— 只读（源站第 181 行 v-if="config.enabled"） */}
            {enabled && (
              <Row
                label="最大条目数"
                desc="超出后按重要度与使用时间归档最低的若干条，归档的记忆仍可在「我的记忆」里查看。"
              >
                <span>{data.max_items} 条</span>
              </Row>
            )}

            {/* 12 · 已记忆条目 —— 只读（派单新增项；关闭态下仍展示历史已存条数） */}
            <Row label="已记忆条目" desc="当前生效的记忆条数（后端 item_count）。">
              <span>{data.item_count} 条</span>
            </Row>
          </div>

          {saveError && <p className="desc">保存失败：{saveError}</p>}
        </>
      )}
    </div>
  )
}

/** 写入模式取值 → 中文（'explicit_only' / 'auto' 见 `KbMemoryTypes.java:91-93`）。 */
function writeModeText(mode: string): string {
  switch (mode) {
    case 'explicit_only':
      return '仅显式记录'
    case 'auto':
      return '自动提炼'
    default:
      // 未知取值如实显示原值，⛔ 不猜成某一档
      return mode || '—'
  }
}

/** 4 个占位面板的静态文案（无需加载数据，所以不走 useEffect）。 */
interface WorkspacePlaceholderCopy {
  /** 分区语义说明（`.section-description`）。 */
  description: string
  /** 具体子项名（`label`）。 */
  subItem: string
  /** 为什么不可用（`.desc`）—— 用户要的是「诚实说明」，不是四个字了事。 */
  subDesc: string
}

const WORKSPACE_PLACEHOLDER_COPY: Record<string, WorkspacePlaceholderCopy> = {
  tenant: {
    // 源站 tenant.sectionDescription（zh-CN.ts）
    description: '查看空间的详细配置信息。',
    subItem: '空间 ID、名称与存储配额',
    subDesc: '用户体系暂未接入（当前为单机单主体），没有空间实体可展示或修改这些信息。',
  },
  members: {
    // 源站 tenantMember.sectionDescription
    description: '邀请伙伴加入当前空间并分配角色。',
    subItem: '成员账号与角色',
    subDesc: '用户体系暂未接入（当前为单机单主体），没有成员账号与角色可邀请、调整或移除。',
  },
  defaults: {
    // 源站 defaultSettings.description
    description: '集中管理本空间各项功能默认使用的模型和搜索引擎。',
    subItem: '空间级默认模型与默认搜索引擎',
    subDesc: '当前为单机单主体，默认模型由客户端全局设置统一决定，没有空间级默认值可单独保存。',
  },
  chathistory: {
    // 源站 chatHistorySettings.description（zh-CN.ts）；后一句是本仓的现状说明（派单要求写明）
    description: '配置聊天历史知识库，将对话消息自动向量化索引，实现语义搜索。消息索引与检索配置尚未迁移到本仓。',
    subItem: '启用消息索引、Embedding 模型与索引统计',
    subDesc: '后端未实现对话消息的索引与检索（无对应端点），因此暂不提供启用开关、模型选择与已索引消息统计。',
  },
}

/**
 * 「空间」组面板入口。
 *
 * @param navKey 组内项 key：tenant / members / defaults / chathistory / memory
 */
export function KbSettingsWorkspace({ navKey }: { navKey: string }): JSX.Element {
  // memory 是唯一真接的项：交给独立子组件（它自己有 hooks，所以只能在这里 return 组件，不能内联）
  if (navKey === 'memory') return <KbMemoryPanel />

  const copy = WORKSPACE_PLACEHOLDER_COPY[navKey]

  // 兜底：navKey 不属于「空间」组时给一个可读的空面板，⛔ 不抛异常、不返回 null（否则白屏）
  if (!copy) {
    return (
      <div className="section">
        <div className="section-header">
          <h2>未知设置项</h2>
          <p className="section-description">当前 key「{navKey}」不属于「空间」组。</p>
        </div>
      </div>
    )
  }

  return (
    <div className="section">
      <div className="section-header">
        <h2>{workspaceLabel(navKey)}</h2>
        <p className="section-description">{copy.description}</p>
      </div>
      <div className="settings-group">
        <div className="setting-row">
          <div className="setting-info">
            <label>{copy.subItem}</label>
            <p className="desc">{copy.subDesc}</p>
          </div>
          <div className="setting-control">
            <span className="ext-tag">{KB_SETTING_NOT_READY}</span>
          </div>
        </div>
      </div>
    </div>
  )
}
