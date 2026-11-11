/**
 * 知识库场景设置 · 「数据与扩展」组（5 个面板）。
 *
 * <p><b>为什么一个组件管 5 个面板</b>：这 5 项同属一个导航组、共用同一套
 * `.section > .section-header > .settings-group > .setting-row` 骨架，且都没有跨面板状态
 * （切页即重新拉取，不需要保留表单草稿）。拆成 5 个文件只会让外壳多 5 条 import，
 * 故这里用一个 `switch (navKey)` 分发。
 *
 * <p><b>数据口径</b>（全部实测过，⛔ 不要按「看起来合理」改）：
 * <ul>
 *   <li>向量存储 —— `GET /api/v1/vector-stores/types`
 *       （`backend/.../kb/api/VectorStoreController.java:30`），
 *       数据源 `KbVectorStoreTypes.TypeInfo`（`KbVectorStoreTypes.java:106-112`），
 *       实返字段 camelCase：`type` / `displayName` / `connectionFields[]` / `indexFields[]`；
 *       外层再包一层 `{success, data}`。</li>
 *   <li>解析引擎 —— `GET /api/v1/system/parser-engines`
 *       （`ParserEngineController.java:38,47`），`data[]` 元素键名<b>大写开头</b>
 *       （`ParserEngineInfo.java:26-32` 用 `@JsonProperty` 逐字锁定）。
 *       ⚠️ 前端已有 `knowledgeApi.listParserEngines()`（`api/knowledge.ts:269-271`，已解包 `data`），
 *       ⛔ 不在这份文件里重写 API 层。</li>
 *   <li>存储引擎 —— `GET /api/v1/storage-backends/types`
 *       （`StorageBackendController.java:27`），数据源 `KbStorageBackendTypes.TypeInfo`
 *       （`KbStorageBackendTypes.java:59-65`），实返字段：
 *       `provider` / `displayName` / `configFields[]` / `locallyAvailable`。</li>
 *   <li>沙箱 —— 后端无能力，静态占位。</li>
 *   <li>网络搜索 —— 只读 `settings.websearchEngine`
 *       （`front/src/api/types.ts:486` ← 后端 `SettingsRecord.java:87` → 列 `websearch_engine`）。
 *       用户 2026-11-08 明确「只展示在选择引擎的配置」，故<b>不提供任何编辑控件</b>。</li>
 * </ul>
 *
 * <p>⛔ <b>不新增样式</b>：所有 class 都来自 `styles/kb/kb-shared.css`
 * （`.section` / `.section-header` / `.section-description` / `.settings-group` /
 * `.setting-row` / `.setting-info` / `.setting-control` / `.desc` / `.ext-tags` / `.ext-tag` /
 * `.value-display`，以及 `.setting-row.disabled`）。
 */

import { useEffect, useState } from 'react'
import type { JSX, ReactNode } from 'react'
import { ApiError, api } from '@/api/rest'
import { knowledgeApi } from '@/api/knowledge'
import type { ParserEngineInfo } from '@/api/knowledge'
import { settingsApi } from '@/api/settings'
import { KB_SETTING_NOT_READY } from './kbSettingsTypes'

// ── 端点响应类型（⛔ 字段名照抄后端 record，改了就静默读不到）───────────────

/**
 * 向量库类型的一个配置字段（`KbVectorStoreTypes.Field`，`KbVectorStoreTypes.java:47-58`）。
 *
 * <p>面板只用到 `name` / `required`，其余字段一并声明是为了让类型与后端契约一致，
 * 将来若要展示敏感标记 / 默认值不必再改接口。
 */
interface VectorStoreFieldMeta {
  name: string
  type: string
  required: boolean
  sensitive: boolean
  defaultValue: unknown
  enumValues: string[] | null
  description: string
  immutable: boolean
  min: number | null
  max: number | null
}

/** 一种向量库引擎的类型元数据（`KbVectorStoreTypes.TypeInfo`）。 */
interface VectorStoreTypeMeta {
  type: string
  displayName: string
  connectionFields: VectorStoreFieldMeta[]
  indexFields: VectorStoreFieldMeta[]
}

/** 存储后端的一个配置字段（`KbStorageBackendTypes.Field`，`KbStorageBackendTypes.java:29-36`）。 */
interface StorageFieldMeta {
  name: string
  type: string
  required: boolean
  sensitive: boolean
  defaultValue: unknown
  description: string
}

/** 一种存储后端的类型元数据（`KbStorageBackendTypes.TypeInfo`）。 */
interface StorageBackendTypeMeta {
  provider: string
  displayName: string
  configFields: StorageFieldMeta[]
  /** 本机此刻能否真正使用（local 恒 true；minio/cos 取决于凭据 + 实现）。 */
  locallyAvailable: boolean
}

/** 三态：加载中 / 就绪 / 失败。⛔ 不用「空数组 + 一个 err 字符串」表示，那会把失败当空态。 */
type PanelState<T> =
  | { phase: 'loading' }
  | { phase: 'ready'; data: T }
  | { phase: 'error'; message: string }

/** 统一错误文案（`ApiError` 优先取后端 detail，见 `api/rest.ts:40-45`）。 */
function toMessage(e: unknown): string {
  if (e instanceof ApiError) return e.userMessage()
  return e instanceof Error ? e.message : String(e)
}

// ── 外壳 ────────────────────────────────────────────────────────────────

/**
 * 「数据与扩展」组面板 · 按 navKey 渲染 5 个子面板之一。
 *
 * <p>`navKey` 取值与 `kbSettingsTypes.ts:90-94` 的导航项一一对应：
 * `vectorstore` / `parser` / `storage` / `sandbox` / `websearch`。
 */
export function KbSettingsDataExt({ navKey }: { navKey: string }): JSX.Element {
  switch (navKey) {
    case 'vectorstore':
      return <VectorStorePanel />
    case 'parser':
      return <ParserPanel />
    case 'storage':
      return <StoragePanel />
    case 'sandbox':
      return <SandboxPanel />
    case 'websearch':
      return <WebSearchPanel />
    default:
      // 防御分支：导航与面板不同步时明确说「没有这一页」，⛔ 不静默渲染空白。
      return (
        <PanelSection title="数据与扩展" desc="该设置项没有对应的面板。">
          <StateRow text={`未知的设置项：${navKey}`} />
        </PanelSection>
      )
  }
}

/**
 * 把全局 sticky 页头复位成源站的普通块级流。
 *
 * <p><b>WHY</b>：`kb-shared.css:722` 把源站**只有 `.kb-chunking-settings` 用**的
 * `position:sticky + padding 24px 32px 12px + margin -24px -32px` 提升成了
 * 全局 `.kb-scope .section-header`。源站其余分节都是普通块级流 + `margin-bottom`。
 * 同一个设置弹窗里的兄弟面板（`KbSettingsModal.tsx:663-670`、
 * `KbCreateModal.tsx:453`）都用这份同样的行内复位 ⇒ 本文件照抄同一份，
 * ⛔ 不改 CSS、不新增 class。
 */
const SECTION_HEADER_FLAT = {
  position: 'static',
  top: 'auto',
  zIndex: 'auto',
  display: 'block',
  padding: 0,
  borderBottom: 'none',
  // ⚠️ margin 必须显式给：sticky 规则里还有 `margin:-24px -32px 16px`，
  // 不复位会把页头拽到容器外（实测 computed = `-24px -32px 28px`）。
  // `0 0 20px` 与兄弟面板 `KbSettingsModal.tsx:672` 的 headerMargin 默认值同值。
  margin: '0 0 20px',
} as const

/** 分节骨架：`.section > .section-header(h2 + p.section-description) + .settings-group`。 */
function PanelSection({ title, desc, children }: {
  title: string
  desc: string
  children: ReactNode
}) {
  return (
    <div className="section">
      <div className="section-header" style={SECTION_HEADER_FLAT}>
        <h2>{title}</h2>
        <p className="section-description">{desc}</p>
      </div>
      <div className="settings-group">{children}</div>
    </div>
  )
}

/** 一行设置：`.setting-row > .setting-info(label + .desc[+ .ext-tags]) + .setting-control`。 */
function Row({ label, desc, tags, disabled, children }: {
  label: ReactNode
  desc?: string
  /** 可选的能力标签（如解析引擎支持的扩展名），渲染进 `.setting-info .ext-tags`。 */
  tags?: string[]
  /** 置灰（如解析引擎不可用）。样式来自 `kb-shared.css:729` 的 `.setting-row.disabled`。 */
  disabled?: boolean
  children: ReactNode
}) {
  return (
    <div className={`setting-row${disabled ? ' disabled' : ''}`}>
      <div className="setting-info">
        <label>{label}</label>
        {desc && <p className="desc">{desc}</p>}
        {tags && tags.length > 0 && (
          <div className="ext-tags">
            {tags.map((t) => <span key={t} className="ext-tag">{t}</span>)}
          </div>
        )}
      </div>
      <div className="setting-control">{children}</div>
    </div>
  )
}

/** 加载 / 失败 / 空态的一行（不给用户白屏）。 */
function StateRow({ text }: { text: string }) {
  return (
    <div className="setting-row">
      <div className="setting-info">
        <p className="desc">{text}</p>
      </div>
    </div>
  )
}

// ── 1. 向量存储 ──────────────────────────────────────────────────────────

/**
 * 向量存储类型列表 · `GET /api/v1/vector-stores/types`。
 *
 * <p>⚠️ 路径是 `/vector-stores/types`（`VectorStoreController` 的 `@RequestMapping`
 * 是 `/api/v1/vector-stores`），<b>不是</b> `/system/vector-stores/types`。
 * 这是元数据端点，不是实例列表 —— 实例（用户建的那几条）走 `GET /vector-stores`，
 * 本面板按派单只展示「可用的类型」。
 */
function VectorStorePanel() {
  const [state, setState] = useState<PanelState<VectorStoreTypeMeta[]>>({ phase: 'loading' })

  useEffect(() => {
    let alive = true
    setState({ phase: 'loading' })
    api<{ success: boolean; data: VectorStoreTypeMeta[] }>('/vector-stores/types')
      .then((r) => {
        if (alive) setState({ phase: 'ready', data: r?.data ?? [] })
      })
      .catch((e) => {
        if (alive) setState({ phase: 'error', message: toMessage(e) })
      })
    return () => { alive = false }
  }, [])

  return (
    <PanelSection
      title="向量存储"
      desc="可作为知识库向量库实例的引擎类型；具体实例在创建知识库时选择。"
    >
      {state.phase === 'loading' && <StateRow text="正在读取向量存储类型…" />}
      {state.phase === 'error' && <StateRow text={`读取失败：${state.message}`} />}
      {state.phase === 'ready' && (
        state.data.length === 0
          ? <StateRow text="后端未返回任何向量存储类型。" />
          : state.data.map((t) => {
            const requiredCount = t.connectionFields.filter((f) => f.required).length
            return (
              <Row
                key={t.type}
                label={t.displayName}
                desc={`引擎标识：${t.type} · 连接配置 ${t.connectionFields.length} 项（必填 ${requiredCount} 项）· 索引配置 ${t.indexFields.length} 项`}
              >
                <span className="value-display">{t.type}</span>
              </Row>
            )
          })
      )}
    </PanelSection>
  )
}

// ── 2. 解析引擎 ──────────────────────────────────────────────────────────

/**
 * 解析引擎能力列表 · `GET /api/v1/system/parser-engines`。
 *
 * <p>只读展示：引擎的**规则配置**（哪个扩展名走哪个解析器）在「新建知识库」弹窗里，
 * 这里只回答「本机装了哪些解析器、各自支持什么」。
 */
function ParserPanel() {
  const [state, setState] = useState<PanelState<ParserEngineInfo[]>>({ phase: 'loading' })

  useEffect(() => {
    let alive = true
    setState({ phase: 'loading' })
    knowledgeApi.listParserEngines()
      .then((list) => {
        if (alive) setState({ phase: 'ready', data: list })
      })
      .catch((e) => {
        if (alive) setState({ phase: 'error', message: toMessage(e) })
      })
    return () => { alive = false }
  }, [])

  return (
    <PanelSection
      title="解析引擎"
      desc="本机可用的文档解析器；按文件类型选用哪个引擎在创建知识库时配置。"
    >
      {state.phase === 'loading' && <StateRow text="正在读取解析引擎…" />}
      {state.phase === 'error' && <StateRow text={`读取失败：${state.message}`} />}
      {state.phase === 'ready' && (
        state.data.length === 0
          ? <StateRow text="后端未返回任何解析引擎。" />
          : state.data.map((e) => (
            <Row
              key={e.Name}
              // Description 在本仓承载的是「展示名」（ParserEngineInfo 的 JavaDoc 已写明）
              label={e.Description}
              desc={e.Available
                ? `引擎标识：${e.Name}`
                : `引擎标识：${e.Name} · 不可用：${e.UnavailableReason || '未说明原因'}`}
              tags={e.FileTypes}
              disabled={!e.Available}
            >
              <span className="value-display">
                {e.FileTypes.length} 种格式 · {e.Available ? '可用' : '不可用'}
              </span>
            </Row>
          ))
      )}
    </PanelSection>
  )
}

// ── 3. 存储引擎 ──────────────────────────────────────────────────────────

/**
 * 存储后端类型列表 · `GET /api/v1/storage-backends/types`。
 *
 * <p>⚠️ 路径是 `/storage-backends/types`（`@RequestMapping` 为 `/api/v1/storage-backends`），
 * 同样没有 `system/` 前缀。
 */
function StoragePanel() {
  const [state, setState] = useState<PanelState<StorageBackendTypeMeta[]>>({ phase: 'loading' })

  useEffect(() => {
    let alive = true
    setState({ phase: 'loading' })
    api<{ success: boolean; data: StorageBackendTypeMeta[] }>('/storage-backends/types')
      .then((r) => {
        if (alive) setState({ phase: 'ready', data: r?.data ?? [] })
      })
      .catch((e) => {
        if (alive) setState({ phase: 'error', message: toMessage(e) })
      })
    return () => { alive = false }
  }, [])

  return (
    <PanelSection
      title="存储引擎"
      desc="知识库原始文件的落盘位置；具体实例在创建知识库时绑定。"
    >
      {state.phase === 'loading' && <StateRow text="正在读取存储引擎类型…" />}
      {state.phase === 'error' && <StateRow text={`读取失败：${state.message}`} />}
      {state.phase === 'ready' && (
        state.data.length === 0
          ? <StateRow text="后端未返回任何存储引擎类型。" />
          : state.data.map((b) => (
            <Row
              key={b.provider}
              label={b.displayName}
              desc={`提供方标识：${b.provider} · 配置项 ${b.configFields.length} 项`}
            >
              <span className="value-display">
                {b.locallyAvailable ? '本机可用' : '本机不可用'}
              </span>
            </Row>
          ))
      )}
    </PanelSection>
  )
}

// ── 4. 沙箱 ──────────────────────────────────────────────────────────────

/** 沙箱 · 后端无此能力，静态占位（文案取自共享契约 `KB_SETTING_NOT_READY`）。 */
function SandboxPanel() {
  return (
    <div className="section">
      <div className="section-header" style={SECTION_HEADER_FLAT}>
        <h2>沙箱</h2>
        <p className="section-description">成员自己的技能与沙箱密钥。</p>
      </div>
      <div className="settings-group">
        <div className="setting-row">
          <div className="setting-info">
            <label>沙箱配置</label>
            <p className="desc">后端尚未提供该能力。</p>
          </div>
          <div className="setting-control">
            <span className="ext-tag">{KB_SETTING_NOT_READY}</span>
          </div>
        </div>
      </div>
    </div>
  )
}

// ── 5. 网络搜索 ──────────────────────────────────────────────────────────

/**
 * 网络搜索 · 只读展示「环境配置」里选的引擎。
 *
 * <p>字段来源：`settingsApi.get()` → `AppSettings.websearchEngine`
 * （`front/src/api/types.ts:486`）；后端列 `settings.websearch_engine`
 * （`backend/.../repository/settings/entity/SettingsRecord.java:87`）。
 * ⛔ 本面板不出编辑控件 —— 用户 2026-11-08 明确「只需要展示」。
 */
function WebSearchPanel() {
  const [state, setState] = useState<PanelState<string | null>>({ phase: 'loading' })

  useEffect(() => {
    let alive = true
    setState({ phase: 'loading' })
    settingsApi.get()
      .then((s) => {
        if (alive) setState({ phase: 'ready', data: s?.websearchEngine ?? null })
      })
      .catch((e) => {
        if (alive) setState({ phase: 'error', message: toMessage(e) })
      })
    return () => { alive = false }
  }, [])

  // 空串 / null 一律按「未知」显示：读取成功但没配置值，不等于配置成了空引擎。
  const engine = state.phase === 'ready' && state.data && state.data.trim()
    ? state.data.trim()
    : '未知'

  return (
    <PanelSection
      title="网络搜索"
      desc="WebSearch 工具使用的搜索引擎（只读展示）。"
    >
      {state.phase === 'loading' && <StateRow text="正在读取当前配置…" />}
      {state.phase === 'error' && <StateRow text={`读取失败：${state.message}`} />}
      {state.phase === 'ready' && (
        <Row
          label="当前搜索引擎"
          desc="配置入口在客户端的「环境配置」；未配置时后端按 anysearch 兜底。"
        >
          <span className="value-display">{engine}</span>
        </Row>
      )}
    </PanelSection>
  )
}
