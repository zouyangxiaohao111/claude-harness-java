import { Fragment, useCallback, useEffect, useMemo, useState } from 'react'
import { api, ApiError } from '@/api/rest'
import { knowledgeApi } from '@/api/knowledge'
import type { KnowledgeBase, ParserEngineInfo } from '@/api/knowledge'
import type { Model } from '@/api/types'
import {
  engineOptionsForGroup,
  enginesForExtensions,
  defaultEngineForGroup,
  parserGroupsWithDynamic,
} from './kbParserGroups'
import { IconSprite } from './IconSprite'

interface Props {
  base: KnowledgeBase
  showToast: (msg: string, type?: 'success' | 'info') => void
  onClose: () => void
  onSaved: () => void
}

/** 分节定义（对齐源侧 KnowledgeBaseEditorModal 的 navGroups）。 */
interface SectionDef {
  key: string
  label: string
  group: string
  /** sprite symbol 的短名（渲染成 `t-icon-{icon}`） */
  icon: string
  /**
   * 仅 FAQ 型知识库显示（源站 `navItems` 里 `if (isFAQ) push({key:'faq'})`）。
   * 反过来的「文档型专属项」用 `docOnly`，见下面的过滤逻辑。
   */
  faqOnly?: boolean
}

/** 分组名与顺序 —— 逐字对齐源站 `nav-group-title`。 */
const GROUPS = ['基础', '索引与解析', '存储与数据', '发布集成', '管理与审计'] as const

/**
 * 「LLM 大语言模型」下拉的可选类型。
 *
 * <p>本仓 `Model.type` 里能当摘要/合成/对话模型的是 `chat` 与 `multimodal`
 * （实测 `/api/v1/models` 只返回这 4 类：chat 10 / multimodal 9 / embedding 1 / rerank 1）。
 * 源站按 `KnowledgeQA` 选；本仓没有该 type，用 `chat + multimodal` 等价表达。
 */
const CHAT_MODEL_TYPES = ['chat', 'multimodal'] as const
/** Embedding 行只认 embedding 类型。 */
const EMBEDDING_MODEL_TYPES = ['embedding'] as const

/** 分块策略（源站 `KBChunkingSettings.vue:290-315`，**4 项**，无 `recursive`）。 */
const CHUNK_STRATEGIES = [
  { value: 'auto', label: '自动' },
  { value: 'heading', label: '按标题切分' },
  { value: 'heuristic', label: '结构感知' },
  { value: 'legacy', label: '按长度切分' },
] as const

/** 滑块刻度（源站 `KBChunkingSettings.vue:268-271` 逐字）。 */
const CHUNK_SIZE_MARKS = [100, 1000, 2000, 4000]
const CHUNK_OVERLAP_MARKS = [0, 250, 500]
const PARENT_CHUNK_SIZE_MARKS = [512, 2048, 4096, 8192]
const CHILD_CHUNK_SIZE_MARKS = [64, 384, 1024, 2048]

/** 分隔符候选（源站 `chunking.separators` 的 8 项）。 */
const SEPARATOR_OPTIONS = [
  { value: '\n\n', label: '双换行 (\n\n)' },
  { value: '\n', label: '单换行 (\n)' },
  { value: '。', label: '中文句号 (。)' },
  { value: '！', label: '感叹号 (！)' },
  { value: '？', label: '问号 (？)' },
  { value: '；', label: '中文分号 (；)' },
  { value: ';', label: '英文分号 (;)' },
  { value: ' ', label: '空格 ( )' },
] as const

/** 图像处理可选模型类型（视觉语言模型）。 */
const MULTIMODAL_MODEL_TYPES = ['multimodal'] as const
/** ASR 可选模型类型（本仓 `/models` 暂无 asr 类型 ⇒ 下拉只有占位，如实反映）。 */
const ASR_MODEL_TYPES = ['asr'] as const

/** 语言提示候选（源站只支持这三种）。 */
const LANGUAGE_OPTIONS = [
  { value: 'zh', label: '中文 (ZH)' },
  { value: 'en', label: '英文 (EN)' },
  { value: 'de', label: '德文 (DE)' },
] as const

/**
 * 分节清单 —— 5 组 13 项，逐字对齐源站导航（真机 + 设计稿
 * `06-知识库设置.html` 一致）。
 *
 * <p>⚠️ 本批只做外壳与导航：13 个面板的内容仍在后续批次补齐。
 * `asr` 目前渲染占位块（`asr_config` 列存在，但解析链路未接线）。
 */
const SECTIONS: SectionDef[] = [
  { key: 'basic', label: '基本信息', group: '基础', icon: 'info-circle' },
  { key: 'models', label: '模型配置', group: '基础', icon: 'control-platform' },
  { key: 'vectorStore', label: '向量存储', group: '基础', icon: 'data-base' },
  /* FAQ 设置：仅 `type === 'faq'` 的库显示（源站 `navItems` 里 `if (isFAQ) push(faq)`）。 */
  { key: 'faq', label: 'FAQ 设置', group: '基础', icon: 'help-circle', faqOnly: true },
  { key: 'parser', label: '解析引擎', group: '索引与解析', icon: 'file-search' },
  { key: 'chunking', label: '分块设置', group: '索引与解析', icon: 'file-copy' },
  { key: 'multimodal', label: '图像处理', group: '索引与解析', icon: 'image' },
  { key: 'asr', label: '音频处理', group: '索引与解析', icon: 'sound' },
  { key: 'graph', label: '知识图谱', group: '索引与解析', icon: 'chart-bubble' },
  { key: 'advanced', label: '高级设置', group: '索引与解析', icon: 'setting' },
  { key: 'storage', label: '存储引擎', group: '存储与数据', icon: 'cloud' },
  { key: 'datasource', label: '数据源', group: '存储与数据', icon: 'cloud-download' },
  { key: 'share', label: '共享管理', group: '发布集成', icon: 'share' },
  { key: 'activity', label: '活动记录', group: '管理与审计', icon: 'history' },
]

/**
 * 分块配置的**类型**与默认值。
 *
 * <p>⚠️ 类型必须覆盖后端 `KbChunkingConfigParser` 认的**全部 7 个键**
 * （`kb/app/chunk/KbChunkingConfigParser.java:59-71`）——
 * 少一个键，UI 里那个控件就读不到值（`TS2339`），或者读到了但写不回去。
 *
 * <p>默认值取自后端常量：`KbSplitterConfig.DEFAULT_CHUNK_SIZE = 512` /
 * `DEFAULT_CHUNK_OVERLAP = 80` / `DEFAULT_PARENT_CHUNK_SIZE = 4096` /
 * `DEFAULT_CHILD_CHUNK_SIZE = 384`。
 * ⚠️ `chunk_overlap` 这里写 50 而不是 80，是因为**本仓 DDL 的列默认值就是 50**
 * （`V257` 抄的是源站 DDL），两者在源站本来就不同 —— 保持原样，见
 * `KbSplitterConfig` 的 javadoc「与知识库默认 JSON 的差异」。
 */
export interface KbChunkingConfig {
  chunk_size: number
  chunk_overlap: number
  split_markers: string[]
  keep_separator: boolean
  /** 分块策略：`auto` / `heading` / `heuristic` / `legacy`（空 = legacy）。 */
  strategy?: string
  /** 令牌上限（0 = 只按字符数）。 */
  token_limit?: number
  /** 语言提示（空 = 自动检测）。 */
  languages?: string[]
  /** 父子分块开关。 */
  enable_parent_child?: boolean
  /** 父块大小（512–8192）。 */
  parent_chunk_size?: number
  /** 子块大小（64–2048）。 */
  child_chunk_size?: number
  /** 按文件类型的解析引擎规则（B0 的后端路由真会消费）。 */
  parser_engine_rules?: Array<{ file_types: string[]; engine: string }>
  /** 表格元数据生成要求（⚠️ 分块器当前**不读**该键，仅落库）。 */
  table_metadata_instructions?: string
}

/** 分块配置默认值（后端 DTO 的缺省同款）。 */
const DEFAULT_CHUNKING: KbChunkingConfig = {
  chunk_size: 512,
  chunk_overlap: 50,
  split_markers: ['\n\n', '\n', '。'],
  keep_separator: true,
}

function parseJson<T>(raw: string | null | undefined, fallback: T): T {
  if (!raw || !raw.trim()) return fallback
  try {
    const v = JSON.parse(raw)
    return (v && typeof v === 'object' ? v : fallback) as T
  } catch {
    return fallback
  }
}

/**
 * 知识库设置页 · 对齐源侧 `KnowledgeBaseEditorModal.vue` + `settings/*.vue`。
 *
 * <h2>⭐ 为什么是「一个弹窗 + 左侧导航」而不是一页一个 tab</h2>
 * 源侧就是这种形态（`SettingsModalShell` + `navGroups`）。设置项有 11 个分节，
 * 摊成 tab 会横向溢出；而且设置是「改完就存」，用户需要能一路扫过去。
 *
 * <h2>⚠️ 保存语义</h2>
 * 全部走 `PATCH /knowledge-bases/{id}`（只传**改动过的**字段）。
 * ⛔ 不能用 PUT —— 那是「提交整张表单」，未传的字段会被当清空。
 */
export function KbSettingsModal({ base, showToast, onClose, onSaved }: Props) {
  const [section, setSection] = useState<string>('basic')
  /**
   * 当前库是否为 FAQ 型。
   *
   * <p>源站 `KnowledgeBaseEditorModal.vue` 的 `navItems`：
   * ```
   * items = [basic, models, vectorStore]
   * if (type === 'faq') items.push({key:'faq'})          // FAQ 专属
   * else items.push(parser, chunking, multimodal, asr, storage, graph, advanced)
   * if (edit) items.push(datasource)
   * items.push(share, activity)
   * ```
   * ⇒ **FAQ 库整组没有「索引与解析」和「存储与数据」**（它没有文档解析流水线）。
   */
  const isFaq = base.type === 'faq'
  /** 按类型过滤后的可见导航项（FAQ 库 6 项 / 文档库 12 项）。 */
  const visibleSections = SECTIONS.filter((s) => {
    if (s.faqOnly) return isFaq
    // 文档专属：FAQ 库不显示（源站 else 分支里的那 6 项）
    const DOC_ONLY = new Set(['parser', 'chunking', 'multimodal', 'asr', 'graph', 'advanced', 'storage', 'datasource'])
    return !isFaq || !DOC_ONLY.has(s.key)
  })
  const [busy, setBusy] = useState(false)
  const [form, setForm] = useState<KnowledgeBase>(base)

  const toMessage = (e: unknown) =>
    e instanceof ApiError ? e.userMessage() : e instanceof Error ? e.message : String(e)

  useEffect(() => { setForm(base) }, [base])
  /**
   * 防止停在已消失的分节上（会渲染成空白页）。
   *
   * <p>源站切类型时会把 `currentSection` 打回 `basic`；本仓弹窗每次打开都是新实例，
   * 但**同一弹窗内 base 变了**（父组件重拉后类型不变，实际不会触发）——
   * 仍加这条兜底：`section` 不在可见列表里 ⇒ 回 basic。零成本，免掉一类空白页。
   */
  useEffect(() => {
    const keys = visibleSections.map((s) => s.key)
    setSection((cur) => (keys.includes(cur) ? cur : 'basic'))
  }, [base.type, base.id])

  // Esc 关闭：⚠️ 设置页只有一层，不需要「先退分节」
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const chunking = useMemo(
    () => parseJson<KbChunkingConfig>(form.chunking_config, DEFAULT_CHUNKING), [form.chunking_config])
  /**
   * 索引策略（`wiki_enabled` / `graph_enabled`）。
   *
   * <p><b>⚠️ 旧注释说「KnowledgeBaseDto 不回传 ⇒ 永远读不到」——那是过期的</b>：
   * 后端 `KnowledgeBaseDto` **有** `indexing_strategy` 字段（实测 PATCH
   * `{"graph_enabled":true}` 后 GET 原样读回）。之前读 `null` 导致 checkbox
   * **永远未选中** ⇒ 用户以为没开；而 `KbPostProcessConfig.graphEnabled()` 又要求它开
   * ⇒ **图谱永远不会自动生成**，界面上还看不出原因。
   */
  const indexing = useMemo(
    () => parseJson<{
      vector_enabled?: boolean
      keyword_enabled?: boolean
      wiki_enabled?: boolean
      graph_enabled?: boolean
    }>(form.indexing_strategy, {}),
    [form.indexing_strategy])

  /**
   * 索引策略是否锁定（源站 `isIndexingLocked`，`KnowledgeBaseEditorModal.vue`）。
   *
   * <p>判据 = 该库已有内容。源站用 `hasFiles`（后端返回的文件数）；本仓 DTO 不返回该计数，
   * 用「已解析出分块」的等价信号：`wiki_config.max_pages_per_ingest` 不可判，
   * 故改为**编辑态一律锁定**与源站真机一致的表现 ——
   * 真机上该库有 2 个文档，两张卡实测都是 `is-disabled` + `.locked-tip` 可见。
   * ⚠️ 本仓拿不到「有没有文件」的权威计数时不猜：按「编辑态 = 可能已有内容」锁定，
   * 与源站真机截图一致；若将来 DTO 补 `knowledge_count`，改为 `knowledge_count > 0`。
   */
  const indexingLocked = true

  /** 保存：只发改动过的字段（PATCH 语义）。 */
  const patch = useCallback(async (body: Record<string, unknown>, okMsg = '已保存') => {
    setBusy(true)
    try {
      await knowledgeApi.patchBase(base.id, body)
      showToast(okMsg, 'success')
      onSaved()
    } catch (e) {
      showToast(toMessage(e), 'info')
    } finally {
      setBusy(false)
    }
  }, [base.id, onSaved, showToast])

  const saveBasic = () => {
    const body: Record<string, unknown> = {}
    if (form.name !== base.name) body.name = form.name
    if ((form.description ?? '') !== (base.description ?? '')) {
      body.description = form.description ?? ''
    }
    if (Object.keys(body).length === 0) { showToast('没有改动', 'info'); return }
    void patch(body)
  }

  const saveChunking = (next: Partial<KbChunkingConfig>) => {
    const merged = { ...chunking, ...next }
    void patch({ chunking_config: JSON.stringify(merged) }, '分块配置已保存')
  }

  /**
   * 合并写 `wiki_config`（⚠️ PATCH 该字段是**整体覆盖**，必须 spread 现值）。
   *
   * <p>本仓 `KbWikiConfig` 认的键全集见 `kb/app/wiki/KbWikiConfig.java:34-72`；
   * 未列出的键（`max_pages_per_ingest` / `ingest_*`）必须原样带上，否则会被静默抹掉。
   */
  const withWiki = (next: Record<string, unknown>): string =>
    JSON.stringify({ ...parseJson<Record<string, unknown>>(form.wiki_config, {}), ...next })

  const saveWiki = async (next: Record<string, unknown>, okMsg = 'Wiki 配置已保存') => {
    await patch({ wiki_config: withWiki(next) }, okMsg)
    // 成功即更新脏检查基准（`patch` 失败会抛给 showToast，走到这里表示已落库）
    setSavedWiki((prev) => ({ ...prev, ...(next as Record<string, string | undefined>) }))
  }

  /**
   * 保存索引策略（⚠️ 同样是整体覆盖 ⇒ spread 现值）。
   *
   * <p>源站 RAG 勾选时 `vector_enabled` 与 `keyword_enabled` **两个字段一起翻**
   * （`KnowledgeBaseEditorModal.vue:1306-1308`）；这里照做，否则「勾了 RAG 但关键词索引没开」
   * 会出现检索只走向量、BM25 永远空的分叉。
   */
  const saveIndexing = async (next: Record<string, boolean>) => {
    const merged: Record<string, unknown> = { ...parseJson<Record<string, unknown>>(form.indexing_strategy, {}), ...next }
    if ('vector_enabled' in next) {
      merged.keyword_enabled = next.vector_enabled
    }
    await patch({ indexing_strategy: JSON.stringify(merged) }, '索引策略已保存')
  }

  // ── B4：解析引擎（注册表清单 + 按库规则）───────────────────────────
  /** 后端解析引擎清单（`GET /api/v1/system/parser-engines`，注册表驱动）。 */
  const [parserEngines, setParserEngines] = useState<ParserEngineInfo[]>([])
  const [parserErr, setParserErr] = useState('')
  useEffect(() => {
    let alive = true
    knowledgeApi.listParserEngines()
      .then((l) => { if (alive) setParserEngines(l || []) })
      .catch((e) => { if (alive) setParserErr(e instanceof Error ? e.message : String(e)) })
    return () => { alive = false }
  }, [])

  /**
   * 用户在本弹窗里改过的引擎（key = 分组 key，value = 引擎 `Name`）。
   *
   * <p>初始值从 `chunking_config.parser_engine_rules` 反推：一条规则可覆盖多个扩展名，
   * 这里按「规则里每个扩展名各自映射到该引擎」展开 ⇒ 分组 key 用扩展名而非固定组 key，
   * 这样动态组（如 `DOCM`）也能正确回显。
   */
  const [engineRules, setEngineRules] = useState<Record<string, string>>(() => {
    const cfg = parseJson<{ parser_engine_rules?: Array<{ file_types?: string[]; engine?: string }> }>(
      base.chunking_config, {})
    const out: Record<string, string> = {}
    for (const r of cfg.parser_engine_rules || []) {
      for (const ft of r.file_types || []) {
        if (r.engine) out[String(ft).toLowerCase().replace(/^\./, '')] = r.engine
      }
    }
    return out
  })
  useEffect(() => {
    const cfg = parseJson<{ parser_engine_rules?: Array<{ file_types?: string[]; engine?: string }> }>(
      base.chunking_config, {})
    const out: Record<string, string> = {}
    for (const r of cfg.parser_engine_rules || []) {
      for (const ft of r.file_types || []) {
        if (r.engine) out[String(ft).toLowerCase().replace(/^\./, '')] = r.engine
      }
    }
    setEngineRules(out)
  }, [base.chunking_config])

  /**
   * 保存某组的引擎选择 → 重写整份 `parser_engine_rules`。
   *
   * <p>⚠️ 后端的消费口径（B0 已实现）：规则按**声明顺序**取第一条
   * `file_types` 命中的；未命中走 `@Order` 默认。因此这里把「所有分组的当前选择」
   * 整体重建成规则数组 —— 只发单条会让其它组退回默认（用户会看到别的行被重置）。
   *
   * <p>分组 key → 扩展名：固定组一条规则覆盖整组（源站也是「一个下拉服务整组」）；
   * 动态组是单扩展名，自然一条一条。
   */
  const saveEngineRule = async (extensions: string[], engine: string) => {
    const groups = parserGroupsWithDynamic(parserEngines)
    // 收集「本次改动后」的全量选择：改动组用新值，其余用现值/默认
    const next: Record<string, string> = {}
    for (const g of groups) {
      const key = g.extensions.join(',')
      const cur = engineRules[g.extensions[0]]
        ?? engineRules[key]
        ?? defaultEngineForGroup(enginesForExtensions(parserEngines, g.extensions), g.extensions)
        ?? ''
      const val = g.extensions.join(',') === extensions.join(',') ? engine : cur
      if (val) for (const e of g.extensions) next[e] = val
    }
    const rules = Object.entries(next).map(([ext, eng]) => ({ file_types: [ext], engine: eng }))
    /* ⚠️ 只写「有明确引擎」的组（`next` 里没值的组不落规则）—— 后端对无规则的类型走
       `@Order` 默认顺序，写一条空 engine 的规则反而会让它匹配上却不认识 ⇒ 回落。
       代价：界面上那些「显示默认值但没落规则」的组，用户不改就永远不写库。
       这是有意的（与源站一致：源站也是「只在用户显式选过才存规则」）。 */
    await saveChunkingRaw({ parser_engine_rules: rules }, '解析引擎已保存')
    // 同步回本地状态，避免下一次保存把这次结果覆盖成旧值
    setEngineRules(next)
  }

  /** 保存任意 `chunking_config` 键（整体覆盖 ⇒ 必须 spread 现值）。 */
  const saveChunkingRaw = async (next: Record<string, unknown>, okMsg = '已保存') => {
    const cfg = parseJson<Record<string, unknown>>(form.chunking_config, {})
    await patch({ chunking_config: JSON.stringify({ ...cfg, ...next }) }, okMsg)
  }

  // ── B3：模型下拉 / 向量库 / 存储后端 ──────────────────────────────
  /** 可选模型清单（`GET /api/v1/models`，只保留 enabled）。拉不到就留空 ⇒ 下拉只有「跟随默认」。 */
  const [models, setModels] = useState<Model[]>([])
  useEffect(() => {
    let alive = true
    api<Model[]>('/models')
      .then((list) => { if (alive) setModels((list || []).filter((m) => m.enabled)) })
      .catch(() => { /* 静默：模型列表拿不到不影响其它分节 */ })
    return () => { alive = false }
  }, [])

  /** 向量库实例（`GET /api/v1/vector-stores`）—— 用于把绑定的 id 换成可读名。 */
  const [vectorStores, setVectorStores] = useState<Array<{ id: string; name: string; engine_type?: string }>>([])
  useEffect(() => {
    let alive = true
    knowledgeApi.listVectorStores()
      .then((l) => { if (alive) setVectorStores(l || []) })
      .catch(() => { /* 静默：拿不到就显示「系统默认」 */ })
    return () => { alive = false }
  }, [])

  /** 存储后端实例（`GET /api/v1/storage-backends`）。 */
  const [storageBackends, setStorageBackends] = useState<Array<{ id: string; name: string; is_default?: boolean }>>([])
  useEffect(() => {
    let alive = true
    knowledgeApi.listStorageBackends()
      .then((l) => { if (alive) setStorageBackends(l || []) })
      .catch(() => { /* 静默 */ })
    return () => { alive = false }
  }, [])

  /**
   * 该库是否已有文件 —— 决定 Embedding 与存储实例是否锁定。
   *
   * <p>⚠️ 本仓 `KnowledgeBaseDto` **不返回** `knowledge_count`（源站有）。
   * 不猜数字：真机上这个库有 2 个文档且 Embedding 明确锁定，说明「编辑态通常已有内容」；
   * 但「新建就编辑」的库会误锁。这里用 `generated_profile` 之外唯一可靠的旁证：
   * 后端 `GET /knowledge?knowledge_base_id=&page_size=1` 的 `total`。
   * 拿不到（请求失败）⇒ 按 **false**（不锁），让用户至少能改 —— 锁死比误改更难救。
   */
  const [kbHasFiles, setKbHasFiles] = useState(false)
  useEffect(() => {
    let alive = true
    knowledgeApi.listKnowledge(base.id, { page: 1, page_size: 1 })
      .then((r) => { if (alive) setKbHasFiles((r?.total ?? 0) > 0) })
      .catch(() => { if (alive) setKbHasFiles(false) })
    return () => { alive = false }
  }, [base.id])

  /** 向量库徽章文案（源站 `.vs-badge` 拆分 name / engine 两段）。 */
  const vectorStoreLabel = (() => {
    const bound = form.vector_store_id ? vectorStores.find((v) => v.id === form.vector_store_id) : null
    if (bound) return { name: bound.name, engine: bound.engine_type || '' }
    return { name: '系统默认', engine: form.retriever_engine || '' }
  })()

  /** 保存单个模型/存储字段（走 PATCH，成功即刷新父级）。 */
  const saveModelField = async (
    field: 'summary_model_id' | 'embedding_model_id' | 'storage_backend_id',
    value: string,
  ) => {
    setLocal(field, value)
    await patch({ [field]: value }, '已保存')
  }

  /**
   * 「最后一次成功落库的 wiki_config」—— 只作 textarea 的**脏检查基准**。
   *
   * <p>⚠️ 不能直接跟 `form.wiki_config` 比：`onChange` 已经把它改了，失焦时两边恒等 ⇒
   * 永远不保存。基准必须是「上次真的 PATCH 成功的那份」。
   */
  /**
   * ⚠️ 基准只跟 `base` 走，**不跟 `form`**。
   *
   * <p>踩过的坑：第一版写了 `useEffect(..., [form.wiki_config])` 来「切库时重置基准」，
   * 但 `onChange` 也改 `form.wiki_config` ⇒ 基准跟着同步成新值 ⇒ 失焦时两边恒等 ⇒
   * **永远不保存，且零报错**。`base` 是「已落库的那份」（父组件保存后重拉），才是正确基准。
   */
  const [savedWiki, setSavedWiki] = useState<Record<string, string | undefined>>(() => {
    const w = parseJson<{ content_instructions?: string; extraction_instructions?: string }>(
      base.wiki_config, {})
    return { content_instructions: w.content_instructions, extraction_instructions: w.extraction_instructions }
  })
  useEffect(() => {
    const w = parseJson<{ content_instructions?: string; extraction_instructions?: string }>(
      base.wiki_config, {})
    setSavedWiki({ content_instructions: w.content_instructions, extraction_instructions: w.extraction_instructions })
  }, [base.wiki_config])

  /** AI 描述生成中的忙态（与整体 `busy` 分开：它不影响「保存并关闭」按钮）。 */
  const [profileBusy, setProfileBusy] = useState(false)

  /** 已生成的画像（优先取本地刚生成的，其次取 DTO 的 `generated_profile`）。 */
  const [profileOverride, setProfileOverride] = useState<{
    gist: string
    topics: string[]
    typicalQuestions: string[]
    status?: string
    error?: string
  } | null>(null)
  /** 画像正文（gist）。 */
  const profileText = (() => {
    if (profileOverride !== null) return profileOverride.gist
    const raw = form.generated_profile
    if (!raw) return ''
    const g = parseJson<{ gist?: string }>(raw, {})
    return (g.gist || '').trim()
  })()

  /**
   * 生成 AI 画像描述（`POST /api/v1/knowledge-bases/{id}/profile/generate`）。
   *
   * <p>⚠️ 2026-11-11：后端返回的 `profile` 从**字符串**改成**对象**
   * （`{gist, topics, typical_questions, status, ...}`，照源站 `KnowledgeBaseProfile`）。
   * 此前前端按对象读、后端返字符串 ⇒ 契约错配，按钮拿不到 gist。
   *
   * <p>后端状态：`generated`（真的生成了）/ `unchanged`（聚合未变，沿用旧描述）/
   * `empty`（没有可聚合的内容）/ `failed` / `unavailable` / `unsupported`。
   */
  const generateProfile = async () => {
    setProfileBusy(true)
    try {
      const r = await knowledgeApi.generateProfile(base.id)
      // ⚠️ 兼容旧后端：2026-11-11 之前后端把 profile 返成**字符串**（纯文本 gist），
      //    新版返**对象**。前端优先读对象；读到字符串就当 gist 用。
      //    ⇒ 后端未重启时按钮照样能用（不会出现「点了没反应」）。
      const raw = r.profile as unknown
      const obj = (raw && typeof raw === 'object') ? raw as {
        gist?: string; topics?: string[]; typical_questions?: string[]
        status?: string; error?: string
      } : null
      const gist = (obj?.gist ?? (typeof raw === 'string' ? raw : '') ?? '').trim()
      setProfileOverride({
        gist,
        topics: obj?.topics ?? [],
        typicalQuestions: obj?.typical_questions ?? [],
        status: obj?.status,
        error: obj?.error,
      })
      const msg =
        r.status === 'unchanged' ? '内容未变化，沿用原描述'
        : r.status === 'empty' ? '没有可聚合的文档内容'
        : r.status === 'failed' ? `生成失败：${r.profile?.error || '未知原因'}`
        : r.status === 'unsupported' ? '该知识库类型不支持自动描述'
        : gist ? 'AI 描述已生成'
        : `未生成（状态：${r.status || '未知'}）`
      showToast(msg, gist ? 'success' : 'info')
      if (gist) onSaved()
    } catch (e) {
      showToast(toMessage(e), 'info')
    } finally {
      setProfileBusy(false)
    }
  }

  const setLocal = <K extends keyof KnowledgeBase>(k: K, v: KnowledgeBase[K]) =>
    setForm((f) => ({ ...f, [k]: v }))

  /**
   * Wiki 配置（`auto_generate` / `extraction_granularity` / `synthesis_model_id` /
   * `content_instructions` / `extraction_instructions`）。
   *
   * <p>⚠️ 键名以**后端消费者**为准：`KbWikiConfig`（`kb/app/wiki/KbWikiConfig.java:34-72`）
   * 认的是 `auto_generate` / `extraction_granularity` / `synthesis_model_id` /
   * `content_instructions` / `extraction_instructions` / `max_pages_per_ingest` / `ingest_*`。
   * 旧代码读的是 `auto_generate_enabled` —— **后端从不认这个键**，所以那个开关永远是关的。
   */
  const wikiCfg = parseJson<{
    auto_generate?: boolean
    extraction_granularity?: string
    synthesis_model_id?: string
    content_instructions?: string
    extraction_instructions?: string
  }>(form.wiki_config, {})
  /** 提取粒度三档；空/非法 ⇒ 标准（源站 `KBModelConfig`/`KbWikiConfig.parse` 同款回落）。 */
  const granularity = (() => {
    const g = (wikiCfg.extraction_granularity || '').toLowerCase()
    return g === 'focused' || g === 'exhaustive' ? g : 'standard'
  })()
  /**
   * 图片理解（caption + OCR）· 读侧必须读 `form.image_processing_config`。
   *
   * <p>⚠️ 这个字段本批之前<b>只有写没有读</b>：DDL 有默认值、PATCH 能存，
   * 但入库链根本不看它 ⇒ 开了等于没开、也没有任何日志。
   * 现在后端 `KbPostProcessConfig.imageUnderstanding()` 会读它并派发图片理解任务。
   */
  const imageCfg = parseJson<{
    enable_multimodal?: boolean
    model_id?: string
    description_language?: string
    custom_instructions?: string
    enable_image_vector?: boolean
    image_pipeline?: string
    image_actions?: unknown
  }>(form.image_processing_config, {})
  const imageEnabled = imageCfg.enable_multimodal === true

  /** 图像配置的脏检查基准（只跟 `base` 走，理由同 B2 的 `savedWiki`）。 */
  const [savedImage, setSavedImage] = useState<{ custom_instructions?: string }>(() => {
    const c = parseJson<{ custom_instructions?: string }>(base.image_processing_config, {})
    return { custom_instructions: c.custom_instructions }
  })
  useEffect(() => {
    const c = parseJson<{ custom_instructions?: string }>(base.image_processing_config, {})
    setSavedImage({ custom_instructions: c.custom_instructions })
  }, [base.image_processing_config])

  /** 合并写 `image_processing_config`（整体覆盖 ⇒ 必须 spread）。 */
  const saveImage = async (next: Record<string, unknown>, okMsg = '图像处理配置已保存') => {
    const cfg = parseJson<Record<string, unknown>>(form.image_processing_config, {})
    await patch({ image_processing_config: JSON.stringify({ ...cfg, ...next }) }, okMsg)
    if ('custom_instructions' in next) {
      setSavedImage({ custom_instructions: next.custom_instructions as string })
    }
  }

  // ── B7：高级设置（问题生成 / 自动打标 / 画像 / 表格元数据）──────────
  /** 问题生成配置（后端 `KbPostProcessConfig` 真消费 4 键：enabled/model_id/question_count/custom_instructions）。 */
  const qgen = parseJson<{
    enabled?: boolean; model_id?: string; question_count?: number; custom_instructions?: string
  }>(form.question_generation_config, {})
  const [savedQgen, setSavedQgen] = useState<{ custom_instructions?: string }>(() => {
    const c = parseJson<{ custom_instructions?: string }>(base.question_generation_config, {})
    return { custom_instructions: c.custom_instructions }
  })
  useEffect(() => {
    const c = parseJson<{ custom_instructions?: string }>(base.question_generation_config, {})
    setSavedQgen({ custom_instructions: c.custom_instructions })
  }, [base.question_generation_config])
  const saveQgen = async (next: Record<string, unknown>, okMsg = '问题生成配置已保存') => {
    const cfg = parseJson<Record<string, unknown>>(form.question_generation_config, {})
    await patch({ question_generation_config: JSON.stringify({ ...cfg, ...next }) }, okMsg)
    if ('custom_instructions' in next) {
      setSavedQgen({ custom_instructions: next.custom_instructions as string })
    }
  }

  /** 自动打标配置（后端真消费 4 键：enabled/model_id/max_tags/skip_if_tagged）。 */
  const autotag = parseJson<{
    enabled?: boolean; model_id?: string; max_tags?: number; skip_if_tagged?: boolean
  }>(form.auto_tag_config, {})
  const saveAutotag = async (next: Record<string, unknown>, okMsg = '自动打标配置已保存') => {
    const cfg = parseJson<Record<string, unknown>>(form.auto_tag_config, {})
    await patch({ auto_tag_config: JSON.stringify({ ...cfg, ...next }) }, okMsg)
  }

  /**
   * 画像配置（`profile_config`）。
   *
   * <p>⭐ 2026-11-11：执行链已接通（源站 `knowledgebase_profile.go`），
   * 本仓已实现「聚合文档画像 → 指纹去重 → 调模型 → 写 generated_profile」。
   */
  const profile = parseJson<{ enabled?: boolean; model_id?: string; custom_instructions?: string }>(
    form.profile_config, {})
  const saveProfile = async (next: Record<string, unknown>, okMsg = '画像配置已保存') => {
    const cfg = parseJson<Record<string, unknown>>(form.profile_config, {})
    await patch({ profile_config: JSON.stringify({ ...cfg, ...next }) }, okMsg)
  }
  /** 画像配置的本地态（textarea 边打字边显示，失焦才落库）。 */
  const [profileLocal, setProfileLocalState] = useState<Record<string, unknown>>({})
  useEffect(() => {
    setProfileLocalState({})
  }, [form.profile_config])
  const setProfileLocal = (k: string, v: unknown) =>
    setProfileLocalState((prev) => ({ ...prev, [k]: v }))
  /** 文本域显示值：本地态优先，回落库里已存的。 */
  const profileLocalText = (k: string, fallback: string) => {
    const v = profileLocal[k]
    return typeof v === 'string' ? v : fallback
  }

  /** 表格元数据要求的脏检查基准（同 savedWiki 的教训：只跟 base 走）。 */
  const [savedTableMeta, setSavedTableMeta] = useState<string>(() => {
    const c = parseJson<{ table_metadata_instructions?: string }>(base.chunking_config, {})
    return c.table_metadata_instructions ?? ''
  })
  useEffect(() => {
    const c = parseJson<{ table_metadata_instructions?: string }>(base.chunking_config, {})
    setSavedTableMeta(c.table_metadata_instructions ?? '')
  }, [base.chunking_config])

  // ── B9：FAQ 设置（`faq_config`）───────────────────────────────────
  /**
   * FAQ 索引方式。
   *
   * <p>⭐ `faq_config.index_mode` 后端**真消费**：`KbFaqMetadata.resolveIndexMode`
   * （`kb/app/faq/KbFaqMetadata.java:337-356`）只认 `question_only`（默认）/
   * `question_answer`；消费点 `KbFaqService.java:221,277` → `renderContent(meta, indexMode)`。
   *
   * <p>⛔ `question_index_mode`（combined/separate）**零消费者**，故只展示不写库。
   */
  const faqCfg = parseJson<{ index_mode?: string; question_index_mode?: string }>(
    form.faq_config ?? '', {})
  const faqIndexMode = faqCfg.index_mode === 'question_answer' ? 'question_answer' : 'question_only'
  const faqQuestionIndexMode = faqCfg.question_index_mode === 'combined' ? 'combined' : 'separate'
  const saveFaq = async (next: Record<string, unknown>, okMsg = 'FAQ 配置已保存') => {
    const cfg = parseJson<Record<string, unknown>>(form.faq_config ?? '', {})
    await patch({ faq_config: JSON.stringify({ ...cfg, ...next }) }, okMsg)
  }

  /** ASR 配置（`asr_config`）。⚠️ 后端零消费者，见面板里的警示文案。 */
  const asrCfg = parseJson<{ enabled?: boolean; model_id?: string; language?: string }>(
    form.asr_config, {})
  const saveAsr = async (next: Record<string, unknown>, okMsg = 'ASR 配置已保存') => {
    const cfg = parseJson<Record<string, unknown>>(form.asr_config, {})
    await patch({ asr_config: JSON.stringify({ ...cfg, ...next }) }, okMsg)
  }

  /**
   * 图谱。
   *
   * <p>后端真实判据（`KbPostProcessConfig.graphEnabled`）是
   * `indexing_strategy.graph_enabled` **∧** `extract_config.enabled`
   * **∧** 有 LLM 模型（未配置时回落全局默认）—— 三个都要。
   * 本面板的开关操作 `extract_config.enabled`（「抽什么」），
   * 另一个开关在基本信息 → 索引策略里（「要不要图谱这条索引」）。
   *
   * <p>⭐ 本仓**没有**「图数据库未启用」这个状态：图谱存在本地 SQLite
   * （`kb_graph_entity` / `kb_graph_relation`），不依赖 Neo4j。
   * 原来照抄源站的 `graphAvailable` 禁用态是死代码，2026-11-11 已删。
   */
  const extractCfg = parseJson<{ enabled?: boolean }>(form.extract_config, {})
  const graphOn = extractCfg.enabled === true
  const saveGraph = async (v: boolean) => {
    await patch({ extract_config: JSON.stringify({ ...extractCfg, enabled: v }) }, '图谱配置已保存')
  }

  return (
    /* 源侧 SettingsModalShell.vue:4 —— `.settings-modal-shell.settings-overlay` 两个类缺一不可；
       源侧消费方传 `:z-index="1000"`（KnowledgeBaseEditorModal.vue:4） */
    <div
      className="settings-modal-shell settings-overlay"
      style={{ zIndex: 1000 }}
      onClick={(e) => { if (e.target === e.currentTarget) onClose() }}
    >
      {/* 导航图标走 sprite（`<use href="#t-icon-*">` 是全文档 id 查找）。
          ⚠️ ball 内必须自带一份：设置弹窗可能在没有 KnowledgeDocPanel 的 tab 下打开
          （wiki / graph / gallery / qa），那时别处那份 sprite 并未挂载 ⇒ 图标全空白。 */}
      <IconSprite />
      <div
        className="settings-modal"
        role="dialog"
        aria-modal="true"
        aria-label="知识库设置"
        /* ⚠️ 全局 `globals.css:4219` 的 `.settings-modal` 带 `border: 1px solid rgba(255,255,255,.6)`，
           而 `.kb-scope .settings-modal-shell .settings-modal` 那条没写 border ⇒ 全局那条漏出来。
           源站弹窗 border 为 0，且本批禁止改 `styles/**`（kb css 由脚本生成）⇒ 行内兜底。 */
        style={{ border: '0 none' }}
      >
        {/* 源侧 SettingsModalShell.vue:11 —— close-btn 是 .settings-modal 的直接子元素（绝对定位） */}
        <button type="button" className="close-btn" onClick={onClose} title="关闭（Esc）" aria-label="关闭">
          <svg width="20" height="20" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
            <path d="M15 5L5 15M5 5L15 15" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
          </svg>
        </button>

        <div className="settings-container">
          {/* 左侧导航（源侧 SettingsModalShell.vue:18-39） */}
          <aside className="settings-sidebar">
            {/* ⚠️ 行内 minHeight:0 兜底：`kb-shared.css:90` 的全局 `.kb-scope .sidebar-header`
                带 `min-height:56px`，而 `.kb-scope .settings-modal-shell .sidebar-header`
                只改 padding（16px 14px 12px）没压 min-height ⇒ 实测 h=56，源站是 50。
                本批禁止改 styles/** ⇒ 行内压掉。 */}
            <div className="sidebar-header" style={{ minHeight: 0 }}>
              <h2 className="sidebar-title">知识库设置</h2>
            </div>
            <nav className="settings-nav">
              {GROUPS.map((g) => {
                const items = visibleSections.filter((s) => s.group === g)
                if (items.length === 0) return null
                return (
                  <Fragment key={g}>
                    <div className="nav-group-title">{g}</div>
                    {items.map((s) => (
                      /* ⚠️ 源站是 `<div>`，不是 `<button>`：源站 CSS 没有 `border:0`
                         ⇒ 写成 button 会露出浏览器默认边框。键盘可达性靠 role/tabIndex/onKeyDown 补。 */
                      <div
                        key={s.key}
                        role="button"
                        tabIndex={0}
                        className={`nav-item${section === s.key ? ' active' : ''}`}
                        onClick={() => setSection(s.key)}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter' || e.key === ' ') {
                            e.preventDefault()
                            setSection(s.key)
                          }
                        }}
                        aria-current={section === s.key ? 'page' : undefined}
                      >
                        <svg
                          className={`t-icon t-icon-${s.icon} nav-icon`}
                          width="16"
                          height="16"
                          aria-hidden="true"
                        >
                          <use href={`#t-icon-${s.icon}`} />
                        </svg>
                        {/* ⚠️ 行内 fontSize:14 兜底：`kb-shared.css:105` 的全局
                            `.kb-scope .nav-label` 把字号压到 `--app-text-md`（本仓 13px），
                            而设置弹窗那条只写了 `flex:1` ⇒ 实测比源站小一号（项高 30 而非 31）。 */}
                        <span className="nav-label" style={{ fontSize: 14 }}>{s.label}</span>
                      </div>
                    ))}
                  </Fragment>
                )
              })}
            </nav>
          </aside>

          {/* 源侧 SettingsModalShell.vue:41-51 —— .settings-content > .settings-body > slot，footer 平级 */}
          <div className="settings-content">
            <div className="settings-body">
              <div className="content-wrapper">
                {section === 'basic' && (
                  /* 源站真机 `data-panel="basic"`（2026-11-10 实测 9 个 form-item）：
                     .section > .section-content > .section-header(h3+p) +
                     .section-body > .form-item × 9
                     ⚠️ 源码 HEAD 还有一个「Wiki 自动生成」开关，但**设计稿 06 页与真机
                     （weknora-ui:latest）都没有渲染** ⇒ 按用户口径「设计稿+真机为准」不引入。 */
                  <div className="section">
                    <div className="section-content">
                      <div className="section-header">
                        <h3 className="section-title">基本信息</h3>
                        <p className="section-desc">设置知识库的名称和描述信息</p>
                      </div>
                      <div className="section-body">
                        {/* ① 知识库 ID */}
                        <div className="form-item">
                          <label className="form-label">知识库 ID</label>
                          <p className="form-tip">API 集成时可使用此 ID 指定知识库</p>
                          <div className="kb-id-field">
                            <code className="kb-id-value" title={base.id}>{base.id}</code>
                            <button
                              type="button"
                              className="t-button t-button--variant-text t-button--theme-default t-button--shape-rectangle t-size-s kb-id-copy"
                              title="复制"
                              onClick={() => {
                                void navigator.clipboard?.writeText(base.id)
                                  .then(() => showToast('已复制知识库 ID', 'success'))
                                  .catch(() => showToast('复制失败，请手动选中', 'info'))
                              }}
                            >
                              <span className="t-button__text">复制</span>
                            </button>
                          </div>
                        </div>

                        {/* ② 知识库类型（编辑态两枚全 disabled） */}
                        <div className="form-item">
                          <label className="form-label required">知识库类型</label>
                          <div className="t-radio-group t-radio-group__outline t-size-m">
                            <label className={`t-radio-button${base.type !== 'faq' ? ' t-is-checked' : ''} t-is-disabled`}>
                              <input
                                type="radio"
                                className="t-radio-button__former"
                                checked={base.type !== 'faq'}
                                disabled
                                readOnly
                              />
                              <span className="t-radio-button__input" />
                              <span className="t-radio-button__label">文档</span>
                            </label>
                            <label className={`t-radio-button${base.type === 'faq' ? ' t-is-checked' : ''} t-is-disabled`}>
                              <input
                                type="radio"
                                className="t-radio-button__former"
                                checked={base.type === 'faq'}
                                disabled
                                readOnly
                              />
                              <span className="t-radio-button__input" />
                              <span className="t-radio-button__label">问答</span>
                            </label>
                          </div>
                          <p className="form-tip">
                            FAQ 类型适合结构化问答数据；文档型支持文件解析与分块；Wiki 类型由 LLM 自动构建互链知识页面。
                          </p>
                        </div>

                        {/* ③ 索引策略（两张卡；有内容时整组锁定） */}
                        <div className="form-item">
                          <label className="form-label required">索引策略</label>
                          <p className="form-tip">配置文档上传后的处理管道，可以独立开启或关闭每种索引方式</p>
                          <div className={`indexing-checks${indexingLocked ? ' is-locked' : ''}`}>
                            <div
                              className={`indexing-check-item${indexing.vector_enabled !== false ? ' is-checked' : ''}${indexingLocked ? ' is-disabled' : ''}`}
                              role="checkbox"
                              aria-checked={indexing.vector_enabled !== false}
                              tabIndex={indexingLocked ? -1 : 0}
                              onClick={() => { if (!indexingLocked) void saveIndexing({ vector_enabled: indexing.vector_enabled === false }) }}
                              onKeyDown={(e) => {
                                if (indexingLocked) return
                                if (e.key === 'Enter' || e.key === ' ') {
                                  e.preventDefault()
                                  void saveIndexing({ vector_enabled: indexing.vector_enabled === false })
                                }
                              }}
                            >
                              <label className={`t-checkbox${indexing.vector_enabled !== false ? ' t-is-checked' : ''}${indexingLocked ? ' t-is-disabled' : ''} indexing-check-box`}>
                                <input
                                  type="checkbox"
                                  className="t-checkbox__former"
                                  checked={indexing.vector_enabled !== false}
                                  disabled={indexingLocked}
                                  tabIndex={-1}
                                  readOnly
                                />
                                <span className="t-checkbox__input" />
                                <span className="t-checkbox__label">RAG 检索</span>
                              </label>
                              <p className="indexing-check-desc">对文档进行分块、向量化和关键词索引，支持混合检索</p>
                            </div>
                            <div
                              className={`indexing-check-item${indexing.wiki_enabled === true ? ' is-checked' : ''}${indexingLocked ? ' is-disabled' : ''}`}
                              role="checkbox"
                              aria-checked={indexing.wiki_enabled === true}
                              tabIndex={indexingLocked ? -1 : 0}
                              onClick={() => { if (!indexingLocked) void saveIndexing({ wiki_enabled: indexing.wiki_enabled !== true }) }}
                              onKeyDown={(e) => {
                                if (indexingLocked) return
                                if (e.key === 'Enter' || e.key === ' ') {
                                  e.preventDefault()
                                  void saveIndexing({ wiki_enabled: indexing.wiki_enabled !== true })
                                }
                              }}
                            >
                              <label className={`t-checkbox${indexing.wiki_enabled === true ? ' t-is-checked' : ''}${indexingLocked ? ' t-is-disabled' : ''} indexing-check-box`}>
                                <input
                                  type="checkbox"
                                  className="t-checkbox__former"
                                  checked={indexing.wiki_enabled === true}
                                  disabled={indexingLocked}
                                  tabIndex={-1}
                                  readOnly
                                />
                                <span className="t-checkbox__input" />
                                <span className="t-checkbox__label">
                                  <span className="indexing-check-title">
                                    Wiki 知识库 <span className="indexing-new-badge">NEW</span>
                                  </span>
                                </span>
                              </label>
                              <p className="indexing-check-desc">自动生成互相关联的 Wiki 知识页面，构建结构化知识体系</p>
                            </div>
                            {/* ⭐ 第三张卡：知识图谱（2026-11-11 用户裁定 A）。
                                在此之前 `indexing_strategy.graph_enabled` **零 UI 写入点** ——
                                设置弹窗「知识图谱」分节的开关只写 `extract_config.enabled`，
                                而派发判据是两字段合取 ⇒ 界面建的库永远不抽图。
                                ⚠️ **本卡不受 `indexingLocked` 约束**（不加 is-disabled、tabIndex 恒 0、
                                    点击不看锁）：改 graph_enabled 只影响**后续入库**与**手动重建**，
                                    不会破坏已有向量/关键词索引，因此不必等「清空知识库」。
                                    RAG/Wiki 两张卡仍按原锁定行为（它们会改索引形态）。
                                数据结构与上面两张完全同款，复用既有类名（⛔ 不需要新 CSS）。 */}
                            <div
                              className={`indexing-check-item${indexing.graph_enabled === true ? ' is-checked' : ''}`}
                              role="checkbox"
                              aria-checked={indexing.graph_enabled === true}
                              tabIndex={0}
                              onClick={() => void saveIndexing({ graph_enabled: indexing.graph_enabled !== true })}
                              onKeyDown={(e) => {
                                if (e.key === 'Enter' || e.key === ' ') {
                                  e.preventDefault()
                                  void saveIndexing({ graph_enabled: indexing.graph_enabled !== true })
                                }
                              }}
                            >
                              <label className={`t-checkbox${indexing.graph_enabled === true ? ' t-is-checked' : ''} indexing-check-box`}>
                                <input
                                  type="checkbox"
                                  className="t-checkbox__former"
                                  checked={indexing.graph_enabled === true}
                                  tabIndex={-1}
                                  readOnly
                                />
                                <span className="t-checkbox__input" />
                                <span className="t-checkbox__label">
                                  <span className="indexing-check-title">知识图谱</span>
                                </span>
                              </label>
                              <p className="indexing-check-desc">用 LLM 从文档中抽取实体与关系，构建可检索的知识图谱</p>
                            </div>
                          </div>
                          {indexingLocked && (
                            <p className="locked-tip">
                              <svg className="t-icon t-icon-lock-on locked-tip-icon" width="14" height="14" aria-hidden="true">
                                <use href="#t-icon-lock-on" />
                              </svg>
                              <span>知识库已有内容，RAG 检索与 Wiki 知识库暂不支持调整（改动会破坏已有索引），如需变更请先清空知识库。知识图谱可随时开关。</span>
                            </p>
                          )}
                        </div>

                        {/* ④ 提取粒度（仅 wiki_enabled 时显示，源站 v-if 同款） */}
                        {indexing.wiki_enabled === true && (
                          <div className="form-item">
                            <label className="form-label">提取粒度</label>
                            <p className="form-tip">
                              控制每篇文档抽取的 Wiki 实体/概念数量。粒度越细，索引越聚焦；粒度越粗，索引越详尽
                            </p>
                            <div className="t-radio-group t-radio-group__outline t-size-m granularity-radio-group">
                              {([['focused', '聚焦'], ['standard', '标准'], ['exhaustive', '详尽']] as const).map(([v, label]) => (
                                <label
                                  key={v}
                                  className={`t-radio-button${granularity === v ? ' t-is-checked' : ''}`}
                                  tabIndex={0}
                                  onClick={() => void saveWiki({ extraction_granularity: v })}
                                  onKeyDown={(e) => {
                                    if (e.key === 'Enter' || e.key === ' ') {
                                      e.preventDefault()
                                      void saveWiki({ extraction_granularity: v })
                                    }
                                  }}
                                >
                                  <input
                                    type="radio"
                                    className="t-radio-button__former"
                                    checked={granularity === v}
                                    tabIndex={-1}
                                    readOnly
                                  />
                                  <span className="t-radio-button__input" />
                                  <span className="t-radio-button__label">{label}</span>
                                </label>
                              ))}
                            </div>
                            <p className="form-tip granularity-hint">
                              {granularity === 'focused'
                                ? '聚焦：每篇只抽少量最关键的实体，索引最精简。'
                                : granularity === 'exhaustive'
                                  ? '详尽：尽量抽全实体与关系，索引最完整、耗时最长。'
                                  : '标准：平衡召回与索引体积，适用于多数文档。'}
                            </p>
                          </div>
                        )}

                        {/* ⑤ Wiki 内容生成要求（textarea 4000） */}
                        {indexing.wiki_enabled === true && (
                          <div className="form-item">
                            <label className="form-label">Wiki 内容生成要求</label>
                            <p className="form-tip">
                              控制摘要、页面和首页的表达重点；引用、合并与防幻觉规则由系统固定维护。修改后需重新解析才能影响已有内容。
                            </p>
                            <div className="wiki-instructions-input">
                              <div className="t-textarea">
                                <textarea
                                  className="t-textarea__inner t-is-default t-resize-none"
                                  rows={2}
                                  maxLength={4000}
                                  placeholder="例如：使用法务审阅口吻，优先展示责任主体、时间线和风险提示…"
                                  value={wikiCfg.content_instructions ?? ''}
                                  onChange={(e) => setLocal('wiki_config', withWiki({ content_instructions: e.target.value }))}
                                  /* ⚠️ 必须 onBlur 落库：`onChange` 只改本地 form state，
                                     不调 PATCH ⇒ 用户输入完以为存了、其实一个字都没入库（零报错）。
                                     源站是「保存并关闭」统一提交；本仓面板一律即时保存，故用失焦提交，
                                     并只在值真的变了时才发（避免每次点别处都打一次 PATCH）。 */
                                  onBlur={() => {
                                    if ((wikiCfg.content_instructions ?? '') !== (savedWiki.content_instructions ?? '')) {
                                      void saveWiki({ content_instructions: wikiCfg.content_instructions ?? '' })
                                    }
                                  }}
                                />
                                <div className="t-textarea__info_wrapper t-textarea__info_wrapper_align">
                                  <span className="t-textarea__limit">{(wikiCfg.content_instructions ?? '').length}/4000</span>
                                </div>
                              </div>
                            </div>
                          </div>
                        )}

                        {/* ⑥ Wiki 提取重点（textarea 4000） */}
                        {indexing.wiki_enabled === true && (
                          <div className="form-item">
                            <label className="form-label">Wiki 提取重点</label>
                            <p className="form-tip">说明应重点识别的领域实体和概念，不会替换系统的 JSON 与引用协议。</p>
                            <div className="wiki-instructions-input">
                              <div className="t-textarea">
                                <textarea
                                  className="t-textarea__inner t-is-default t-resize-none"
                                  rows={2}
                                  maxLength={4000}
                                  placeholder="例如：重点识别产品、版本、组织、负责人和关键技术概念…"
                                  value={wikiCfg.extraction_instructions ?? ''}
                                  onChange={(e) => setLocal('wiki_config', withWiki({ extraction_instructions: e.target.value }))}
                                  onBlur={() => {
                                    if ((wikiCfg.extraction_instructions ?? '') !== (savedWiki.extraction_instructions ?? '')) {
                                      void saveWiki({ extraction_instructions: wikiCfg.extraction_instructions ?? '' })
                                    }
                                  }}
                                />
                                <div className="t-textarea__info_wrapper t-textarea__info_wrapper_align">
                                  <span className="t-textarea__limit">{(wikiCfg.extraction_instructions ?? '').length}/4000</span>
                                </div>
                              </div>
                            </div>
                          </div>
                        )}

                        {/* ⑦ 知识库名称 */}
                        <div className="form-item">
                          <label className="form-label required">知识库名称</label>
                          <div className="t-input__wrap">
                            <div className="t-input">
                              <input
                                className="t-input__inner"
                                placeholder="请输入知识库名称"
                                value={form.name}
                                onChange={(e) => setLocal('name', e.target.value)}
                              />
                            </div>
                          </div>
                          <p className="form-tip">最多 255 字符；会显示在知识库列表与问答页头上。</p>
                        </div>

                        {/* ⑧ 知识库描述（200 字） */}
                        <div className="form-item">
                          <label className="form-label">知识库描述</label>
                          <div className="t-textarea">
                            <textarea
                              className="t-textarea__inner t-is-default t-resize-none"
                              rows={2}
                              maxLength={200}
                              placeholder="请输入知识库描述（可选）"
                              value={form.description ?? ''}
                              onChange={(e) => setLocal('description', e.target.value)}
                            />
                            <div className="t-textarea__info_wrapper t-textarea__info_wrapper_align">
                              <span className="t-textarea__limit">{(form.description ?? '').length}/200</span>
                            </div>
                          </div>
                        </div>

                        {/* ⑨ AI 生成描述（画像聚合；接 POST profile/generate） */}
                        <div className="form-item">
                          <label className="form-label">AI 生成描述</label>
                          <div className={`kb-profile-card${profileText ? '' : ' is-empty'}`}>
                            {profileText
                              ? <p className="kb-profile-gist">{profileText}</p>
                              : <p className="kb-profile-empty">尚未生成。上传文档并生成摘要后，点击下方按钮即可生成。</p>}
                            <div className="kb-profile-actions">
                              <button
                                type="button"
                                className="t-button t-button--variant-outline t-button--theme-primary t-button--shape-rectangle t-size-s"
                                disabled={profileBusy}
                                onClick={() => { void generateProfile() }}
                              >
                                <span className="t-button__text">
                                  {profileBusy ? '生成中…' : profileText ? '重新生成 AI 描述' : '生成 AI 描述'}
                                </span>
                              </button>
                            </div>
                          </div>
                          <p className="form-tip">
                            由文档画像聚合生成，不会覆盖上方手写描述；智能体会同时读取两者来判断问题是否该在本知识库中检索。
                          </p>
                        </div>
                      </div>
                    </div>
                  </div>
                )}

                {section === 'models' && (
                  /* 源站真机 `KBModelConfig.vue`：`.kb-model-config > .section-header +
                     .settings-group > .setting-row × 3`，三行全是 model-selector（下拉）。
                     ⚠️ 顺序固定为 LLM → Embedding → Wiki（源站同款），不是「嵌入在前」。 */
                  <SettingSection
                    rootClass="kb-model-config"
                    title="模型配置"
                    desc="为知识库选择合适的 AI 模型"
                  >
                    <Row
                      label={<>LLM 大语言模型 <span className="required">*</span></>}
                      desc="用于摘要、知识图谱、Wiki、问题生成和自动打标；不选则使用全局默认模型"
                    >
                      <ModelSelect
                        value={form.summary_model_id ?? ''}
                        models={models}
                        types={CHAT_MODEL_TYPES}
                        placeholder="使用全局默认"
                        onChange={(v) => void saveModelField('summary_model_id', v)}
                      />
                    </Row>
                    <Row
                      label={<>Embedding 嵌入模型 <span className="required">*</span></>}
                      desc="用于文本向量化的嵌入模型"
                      stacked
                      hint={kbHasFiles ? '知识库中已有文件，无法修改 Embedding 模型' : undefined}
                    >
                      <ModelSelect
                        value={form.embedding_model_id ?? ''}
                        models={models}
                        types={EMBEDDING_MODEL_TYPES}
                        placeholder="自动选用可用的嵌入模型"
                        disabled={kbHasFiles}
                        onChange={(v) => void saveModelField('embedding_model_id', v)}
                      />
                    </Row>
                    <Row label="Wiki 合成模型" desc="不设置时将回退使用摘要模型">
                      <ModelSelect
                        value={wikiCfg.synthesis_model_id ?? ''}
                        models={models}
                        types={CHAT_MODEL_TYPES}
                        placeholder="跟随摘要模型"
                        onChange={(v) => void saveWiki({ synthesis_model_id: v }, 'Wiki 合成模型已保存')}
                      />
                    </Row>
                    {/* ⭐ 2026-11-11 用户要求「界面上添加一句提示（如果是空的话 使用全局默认）」。
                        必须显式写出来：这句以前只是下拉框里的占位文字，用户看不出它是一条真规则。 */}
                    <p className="form-tip">
                      LLM 大语言模型留空时，自动使用「设置 → 模型 → 主模型」里的全局默认模型。
                      摘要、知识图谱、Wiki、AI 问题生成、自动打标都用这个模型。
                    </p>
                  </SettingSection>
                )}

                {section === 'vectorStore' && (
                  /* 源站真机 `KBVectorStoreSettings.vue`：只读一行 `.vs-badge.vs-badge-env`。
                     绑定创建后不可更改（源站 `.locked-hint` / `immutableEdit` 同款语义）。 */
                  <SettingSection
                    rootClass="kb-vector-store-settings"
                    title="向量存储"
                    desc="选择此知识库要写入的向量存储。绑定不可更改 — 如需将现有 KB 迁移到其他存储，请创建新 KB 并重新索引。"
                  >
                    <Row label="已绑定的向量存储" desc="向量存储绑定在创建后无法更改。">
                      <span className="vs-badge vs-badge-env">
                        <span className="vs-badge-name">{vectorStoreLabel.name}</span>
                        <span className="vs-badge-engine">{vectorStoreLabel.engine}</span>
                      </span>
                    </Row>
                  </SettingSection>
                )}

                {section === 'parser' && (
                  /* 源站真机 `KBParserSettings.vue`：`.kb-parser-settings >
                     .section-header + .settings-group > .setting-row × N`（真机 22 组，
                     本仓注册表驱动后 34 组 = 12 固定 + 22 动态）。
                     ⚠️ 旧版这里写的是「PDF 走视觉 / Office 走 Tika」两行说明 ——
                     那是把技术选型当 UI 文案，属于**假对齐**（源站要的是每组一个下拉）。 */
                  <div className="section">
                    <div className="kb-parser-settings">
                      <div className="section-header" style={{ ...SECTION_HEADER_FLAT, margin: '0 0 20px' }}>
                        <h2>解析引擎</h2>
                        <p className="section-description">
                          为不同文件类型选择文档解析引擎。未配置的文件类型将使用内置解析引擎。
                        </p>
                        {parserErr && (
                          <p className="desc" style={{ color: 'var(--td-error-color, #d54941)' }}>
                            解析引擎清单加载失败：{parserErr}
                          </p>
                        )}
                      </div>
                      <div className="settings-group">
                        {parserGroupsWithDynamic(parserEngines).map((g) => {
                          const options = engineOptionsForGroup(parserEngines, g.extensions)
                          const fallback = defaultEngineForGroup(
                            enginesForExtensions(parserEngines, g.extensions), g.extensions)
                          const selected = engineRules[g.key] ?? fallback ?? ''
                          return (
                            <div className="setting-row" key={g.key}>
                              <div className="setting-info">
                                <label className="group-label">
                                  <svg
                                    className={`t-icon t-icon-${g.icon} group-icon`}
                                    width="1em"
                                    height="1em"
                                    viewBox="0 0 24 24"
                                    aria-hidden="true"
                                  >
                                    <use href={`#t-icon-${g.icon}`} />
                                  </svg>
                                  {g.label}
                                </label>
                                <div className="ext-tags">
                                  {g.extensions.map((e) => <span className="ext-tag" key={e}>.{e}</span>)}
                                </div>
                              </div>
                              <div className="setting-control">
                                <div className="parser-control-stack">
                                  {options.length > 0 ? (
                                    <select
                                      className="t-input t-input--suffix"
                                      style={{ width: 280 }}
                                      aria-label={`${g.label} 解析引擎`}
                                      value={selected}
                                      onChange={(e) => void saveEngineRule(g.extensions, e.target.value)}
                                    >
                                      {options.map((o) => (
                                        <option key={o.value} value={o.value}>{o.label}</option>
                                      ))}
                                    </select>
                                  ) : (
                                    <>
                                      <div className="t-input t-is-disabled" style={{ width: 280 }}>
                                        <input
                                          className="t-input__inner"
                                          value=""
                                          placeholder={g.key === 'audiovisual' ? '语音识别（接入中）' : '无可用引擎'}
                                          readOnly
                                          disabled
                                        />
                                      </div>
                                      <div className="no-engine-warning">
                                        {g.key === 'audiovisual'
                                          ? 'ASR 能力规划中，接入后可直接选用'
                                          : '本仓暂不支持该格式'}
                                      </div>
                                    </>
                                  )}
                                </div>
                              </div>
                            </div>
                          )
                        })}
                      </div>
                    </div>
                  </div>
                )}

                {section === 'chunking' && (
                  /* 源站真机 `KBChunkingSettings.vue`：`.kb-chunking-settings >
                     .section-header + .settings-group > .setting-row × 7（+ 高级选项展开后 9）`。
                     4 个滑块 + 1 个策略下拉 + 1 个分隔符 tag 输入 + 1 个父子开关 +
                     2 个 tag/数字（高级区）。 */
                  <SettingSection
                    rootClass="kb-chunking-settings"
                    title="分块设置"
                    desc="控制上传文档在嵌入前的切分方式。默认值适用于大多数场景，仅在检索质量异常时调整。"
                    headerReset={false}
                    headerMargin="0 0 20px"
                  >
                    {/* ① 分块策略 + 测试分块效果 */}
                    <Row label="分块策略" desc="选择文档的分块方式。自动模式会分析每个文档的结构并选择最佳策略。">
                      <div className="setting-control strategy-control">
                        <div className="t-select__wrap" style={{ width: 280 }}>
                          <select
                            className="t-input__inner"
                            style={{ width: '100%', height: 32 }}
                            value={chunking.strategy ?? ''}
                            onChange={(e) => void saveChunkingRaw({ strategy: e.target.value })}
                          >
                            <option value="">选择分块策略（不填则按长度切分）</option>
                            {CHUNK_STRATEGIES.map((o) => (
                              <option key={o.value} value={o.value}>{o.label}</option>
                            ))}
                          </select>
                        </div>
                        <div className="kb-chunking-debug">
                          <span
                            role="button"
                            tabIndex={0}
                            className="debug-trigger"
                            onClick={() => showToast('测试分块效果：本仓暂无该调试接口', 'info')}
                            onKeyDown={(e) => {
                              if (e.key === 'Enter' || e.key === ' ') {
                                e.preventDefault()
                                showToast('测试分块效果：本仓暂无该调试接口', 'info')
                              }
                            }}
                          >
                            测试分块效果
                          </span>
                        </div>
                      </div>
                    </Row>

                    {/* ② 分块大小 */}
                    <Row label="分块大小" desc="每个分块的最大字符数（100-4000）。默认 512 ≈ 中文 300 tokens / 英文 100-130 tokens。FAQ 用 200-400，叙述性长文档用 1000-2000。">
                      <Slider
                        min={100}
                        max={4000}
                        step={1}
                        value={chunking.chunk_size}
                        marks={CHUNK_SIZE_MARKS}
                        unit="字符"
                        onChange={(v) => saveChunking({ chunk_size: v })}
                      />
                    </Row>

                    {/* ③ 分块重叠 */}
                    <Row label="分块重叠" desc="相邻分块之间共享的字符数（0-500）。默认 80 ≈ 分块大小的 15%，符合当前研究推荐。FAQ/结构化数据用 0，长篇叙述用 150-200。">
                      <Slider
                        min={0}
                        max={500}
                        step={1}
                        value={chunking.chunk_overlap}
                        marks={CHUNK_OVERLAP_MARKS}
                        unit="字符"
                        onChange={(v) => saveChunking({ chunk_overlap: v })}
                      />
                    </Row>

                    {/* ④ 分隔符 */}
                    <Row label="分隔符" desc="切分时优先使用的字符或字符串。优先级高的分隔符先尝试；默认顺序优先段落 → 句子 → 标点。">
                      <TagInput
                        value={chunking.split_markers || []}
                        options={SEPARATOR_OPTIONS}
                        placeholder="选择或自定义分隔符"
                        onChange={(v) => void saveChunkingRaw({ split_markers: v })}
                      />
                    </Row>

                    {/* ⑤ 父子分块 */}
                    <Row
                      label="父子分块"
                      desc="两级分块：小的子块用于向量匹配（精准命中），大的父块返回给 LLM（更丰富上下文）。建议用于长文档（>10 页）；短 FAQ 可关闭以节省存储。"
                    >
                      <Switch
                        checked={chunking.enable_parent_child === true}
                        label="父子分块"
                        onChange={(v) => void saveChunkingRaw({ enable_parent_child: v })}
                      />
                    </Row>

                    {/* ⑥ 父块大小（父子开时才有意义） */}
                    <Row label="父块大小" desc="返回给 LLM 的上下文块大小（512-8192）。默认 4096 ≈ 1000 英文 tokens，适合所有现代 LLM 上下文窗口。">
                      <Slider
                        min={512}
                        max={8192}
                        step={1}
                        value={chunking.parent_chunk_size ?? 4096}
                        marks={PARENT_CHUNK_SIZE_MARKS}
                        unit="字符"
                        disabled={chunking.enable_parent_child !== true}
                        onChange={(v) => void saveChunkingRaw({ parent_chunk_size: v })}
                      />
                    </Row>

                    {/* ⑦ 子块大小 */}
                    <Row label="子块大小" desc="用于向量匹配的嵌入块大小（64-2048）。默认 384 ≈ 80 tokens，是 sentence-transformer / BGE 类嵌入模型的最佳点。">
                      <Slider
                        min={64}
                        max={2048}
                        step={1}
                        value={chunking.child_chunk_size ?? 384}
                        marks={CHILD_CHUNK_SIZE_MARKS}
                        unit="字符"
                        disabled={chunking.enable_parent_child !== true}
                        onChange={(v) => void saveChunkingRaw({ child_chunk_size: v })}
                      />
                    </Row>

                    {/* ⑧ 每块 Token 上限（源站在「高级选项」折叠区里，展开后才见） */}
                    <Row label="每块 Token 上限" desc="每个分块的硬性 Token 上限（0-8192）。0 = 关闭（仅按字符数）。">
                      <div className="t-input-number t-size-m t-input-number--normal">
                        <input
                          type="number"
                          className="t-input__inner"
                          style={{ width: 120, height: 32 }}
                          min={0}
                          max={8192}
                          value={chunking.token_limit ?? 0}
                          onChange={(e) => void saveChunkingRaw({ token_limit: Number(e.target.value) })}
                        />
                      </div>
                    </Row>

                    {/* ⑨ 语言提示 */}
                    <Row label="语言提示" desc="限制启发式模式只识别选定的语言（DE/EN/ZH）。留空 = 自动检测。">
                      <TagInput
                        value={chunking.languages || []}
                        options={LANGUAGE_OPTIONS}
                        placeholder="自动检测"
                        onChange={(v) => void saveChunkingRaw({ languages: v })}
                      />
                    </Row>

                    <p className="form-tip">
                      ⚠️ 已有文档不会被重新切块 —— 改了只影响<b>之后</b>上传的文件。
                      要让旧文档生效需逐条「重解析」。
                    </p>
                  </SettingSection>
                )}

                {section === 'multimodal' && (
                  /* 源站真机 `ImageProcessingSettings.vue`：`.kb-multimodal-settings >
                     .section-header + .settings-group`，开关打开后 6 行。 */
                  <SettingSection
                    rootClass="kb-multimodal-settings"
                    title="图像处理配置"
                    desc="配置图像内容理解能力，启用后支持图片等非文本内容的解析和检索"
                    headerReset={false}
                    headerMargin="0 0 20px"
                  >
                    <Row label="多模态功能" desc="启用图片等多模态内容的理解能力">
                      <Switch
                        checked={imageEnabled}
                        label="多模态功能"
                        /* ⚠️ 必须合并：`image_processing_config` 是整体覆盖语义 ⇒
                           只发 enable_multimodal 会把 model_id / image_actions 一起冲掉。 */
                        onChange={(v) => void saveImage({ enable_multimodal: v }, '图像处理开关已保存')}
                      />
                    </Row>

                    {imageEnabled && (
                      <>
                        {/* ⭐ 用户裁定（2026-11-11）：
                            ① 「VLLM 视觉模型 是可以选择的，但是默认选中 使用全局默认」；
                            ② 「单独选择也能其效果」。
                            空值 = 跟随全局 `settings.multimodal_model_name`；选了具体模型
                            ⇒ 后端 `IMAGE_UNDERSTAND` 任务把它透传导给视觉调用器。
                            ⚠️ 选项标签统一 `provider/modelName`（见 ModelSelect）。 */}
                        <Row
                          label="VLLM 视觉模型"
                          desc="留空则跟随全局（设置 → 模型 → 多模态模型）"
                        >
                          <ModelSelect
                            value={imageCfg.model_id ?? ''}
                            models={models}
                            types={MULTIMODAL_MODEL_TYPES}
                            placeholder="使用全局默认"
                            onChange={(v) => void saveImage({ model_id: v }, 'VLLM 视觉模型已保存')}
                          />
                        </Row>

                        <Row label="图片描述语言" desc="留空时跟随文档语言；不再强制使用中文">
                          <div className="t-select__wrap" style={{ width: 280 }}>
                            <select
                              className="t-input__inner"
                              style={{ width: '100%', height: 32 }}
                              value={imageCfg.description_language ?? ''}
                              onChange={(e) => void saveImage({ description_language: e.target.value }, '图片描述语言已保存')}
                            >
                              <option value="">自动跟随文档语言</option>
                              <option value="Chinese">中文</option>
                              <option value="English">English</option>
                              <option value="Korean">한국어</option>
                              <option value="Russian">Русский</option>
                            </select>
                          </div>
                        </Row>

                        <Row
                          label="图片解析要求"
                          desc="补充需要重点识别的视觉信息，OCR 和 Markdown 格式协议保持不变"
                          stacked
                        >
                          <div className="t-textarea" style={{ width: 280 }}>
                            <textarea
                              className="t-textarea__inner t-is-default t-resize-none"
                              rows={2}
                              maxLength={4000}
                              placeholder="例如：重点识别设备铭牌、型号、告警代码和表格中的单位…"
                              value={imageCfg.custom_instructions ?? ''}
                              onChange={(e) => setLocal('image_processing_config',
                                JSON.stringify({ ...imageCfg, custom_instructions: e.target.value }))}
                              onBlur={() => {
                                if ((imageCfg.custom_instructions ?? '') !== (savedImage.custom_instructions ?? '')) {
                                  void saveImage({ custom_instructions: imageCfg.custom_instructions ?? '' })
                                }
                              }}
                            />
                          </div>
                        </Row>

                        <Row
                          label="图片向量检索"
                          desc="开启后，生成图片描述之后再用向量模型直接对图片编码，检索时按图片内容召回描述没写到的信息。需要向量模型支持图片输入；每张图片多一次向量调用，向量检索的候选范围也会放大。只对此后入库或重新解析的文档生效，扫描件 PDF 页不生成"
                        >
                          <Switch
                            checked={imageCfg.enable_image_vector === true}
                            label="图片向量检索"
                            onChange={(v) => void saveImage({ enable_image_vector: v }, '图片向量检索已保存')}
                          />
                        </Row>

                        <Row
                          label="图片解析方案"
                          desc="多模态解析开启后，可以根据需要选择图片的解析方案"
                          stacked
                        >
                          <div className="t-select__wrap" style={{ width: 280 }}>
                            <select
                              className="t-input__inner"
                              style={{ width: '100%', height: 32 }}
                              value={imageCfg.image_pipeline ?? ''}
                              onChange={(e) => void saveImage({ image_pipeline: e.target.value }, '图片解析方案已保存')}
                            >
                              <option value="">默认（逐张描述 + OCR）</option>
                              <option value="attrs">图片属性观察（先观察属性再决定是否 OCR）</option>
                            </select>
                          </div>
                        </Row>
                      </>
                    )}

                    <p className="form-tip">
                      图内文字会一起进索引 ⇒ 检索能命中图片里的内容。改完需对已有文档「重解析」才会补图。
                    </p>
                  </SettingSection>
                )}

                {section === 'asr' && (
                  /* 源站真机 `KBMultimodalSettings.vue`（ASR 分支）：
                     `.kb-multimodal-settings > .section-header + .settings-group`，2 行。 */
                  <SettingSection
                    rootClass="kb-multimodal-settings"
                    title="音频语音识别"
                    desc="配置语音识别（ASR），启用后可上传音频文件并整段转写为文本（常见格式：mp3、wav、m4a、flac、ogg 等）。暂不支持视频上传。"
                    headerReset={false}
                    headerMargin="0 0 20px"
                  >
                    <Row
                      label="启用音频语音识别"
                      desc="启用后可上传音频到知识库，系统自动将语音转写为文本并参与解析与检索"
                    >
                      <Switch
                        checked={asrCfg.enabled === true}
                        label="启用音频语音识别"
                        onChange={(v) => void saveAsr({ enabled: v }, 'ASR 开关已保存')}
                      />
                    </Row>
                    {asrCfg.enabled === true && (
                      <Row
                        label={<>ASR 模型 <span className="required">*</span></>}
                        desc="用于音频中语音转文本的识别模型（如 OpenAI Whisper）"
                      >
                        <ModelSelect
                          value={asrCfg.model_id ?? ''}
                          models={models}
                          types={ASR_MODEL_TYPES}
                          placeholder="请选择 ASR 模型（必选）"
                          onChange={(v) => void saveAsr({ model_id: v }, 'ASR 模型已保存')}
                        />
                      </Row>
                    )}
                    {/* ⚠️ 如实告知：`asr_config` 列存在、能存，但**后端没有任何消费者**
                        （全仓 grep `getAsrConfig()` 只命中 Copy/Dto/Service 三处搬运）
                        ⇒ 存进去不会让音频真的被转写。⛔ 不假装能用。 */}
                    <p className="form-tip" style={{ color: 'var(--td-warning-color, #e37318)' }}>
                      ⚠️ 本仓尚未接入语音识别执行链 —— 这里保存的配置会落库，但<b>不会</b>让音频自动转写。
                      需要转写请先用对话里的语音工具处理，或等 ASR 能力接入。
                    </p>
                  </SettingSection>
                )}

                {section === 'graph' && (
                  /* 源站真机 `GraphSettings.vue`：`.graph-settings > .section-header` +
                     图数据库未启用时 `.graph-disabled` 空态；启用时是「实体-关系提取」开关。 */
                  <div className="section">
                    <div className="graph-settings">
                      <div className="section-header" style={{ ...SECTION_HEADER_FLAT, margin: '0 0 20px' }}>
                        <h2>知识图谱配置</h2>
                        <p className="section-description">
                          配置实体-关系提取功能，自动从文本中抽取实体和关系构建知识图谱（注意：这与 Wiki 知识库中的「页面链接图谱」是两回事——前者是基于 LLM 的实体-关系图，后者是 Wiki 页面之间的引用关系图）
                        </p>
                      </div>
                      {/* ⭐ 2026-11-11：这里原来照抄了源站的「图数据库未启用」禁用态
                          （含一个指向 weknora 文档的「如何启用知识图谱？」按钮）。
                          两条都删了，理由：
                          ① 源站那个提示说的是「没装 Neo4j」——本仓用 SQLite 两张表
                             （kb_graph_entity / kb_graph_relation）存图谱，不需要外部图数据库，
                             也没有「未启用」这个状态（`graphAvailable` 恒 true，那分支本来就是死代码）；
                          ② 那个文档链接指向一份跟本仓实现无关的说明，用户点了只会更困惑
                             （实际已发生：用户被引导去看那份文档）。
                          ⛔ 不要再把源站这套禁用态搬回来。 */}
                      <div className="settings-group">
                        <Row
                          label="实体-关系提取"
                          desc="解析文档时用 LLM 抽取实体和关系，构建可检索的知识图谱"
                        >
                          <Switch
                            checked={graphOn}
                            label="实体-关系提取"
                            onChange={(v) => void saveGraph(v)}
                          />
                        </Row>
                        <p className="form-tip">
                          ⚠️ 需要同时满足三个条件：① 本项开关；②「基本信息 → 索引策略 → 知识图谱」
                          那张卡片（与 RAG / Wiki 并列的第三张）；③ 本知识库配了 LLM 模型
                          （未配置时自动使用全局默认模型）。少一个都不会抽任何东西。
                        </p>
                        {/* ⭐ 显式提示「现在不会抽」：本次修复前正是这个组合（本项开了、索引策略没勾）
                            导致用户以为已开启却零抽取、零日志。⛔ 不要删掉这条提示。 */}
                        {graphOn && indexing.graph_enabled !== true && (
                          <p className="form-tip" style={{ color: 'var(--td-warning-color, #e37318)' }}>
                            ⚠️ 索引策略里的「知识图谱」卡片尚未勾选，当前上传的文档不会抽取图谱。
                            请到「基本信息 → 索引策略」勾上，已有文档需要点图谱页的「重建图谱」补抽。
                          </p>
                        )}
                        <p className="form-tip">
                          图谱数据存在本地数据库里，不需要额外安装图数据库。
                        </p>
                      </div>
                    </div>
                  </div>
                )}

                {section === 'advanced' && (
                  /* 源站真机 `KBAdvancedSettings.vue`：`.kb-advanced-settings >
                     .section-header + .settings-group`。真机展开后 **9 行**
                     （我先前按源码 HEAD 推算的 11 行是错的：源码里「描述模型」「描述指令」
                     两行在当前镜像未渲染）。 */
                  <SettingSection
                    rootClass="kb-advanced-settings"
                    title="高级设置"
                    desc="配置问题生成等高级功能"
                    headerReset={false}
                    headerMargin="0 0 20px"
                  >
                    {/* ① AI 问题生成 */}
                    <Row
                      label="AI 问题生成"
                      desc="解析文档时调用大模型为每个分块生成相关问题，提高检索召回率。启用后会增加文档解析耗时。"
                    >
                      <Switch
                        checked={qgen.enabled === true}
                        label="AI 问题生成"
                        onChange={(v) => void saveQgen({ enabled: v }, '问题生成开关已保存')}
                      />
                    </Row>

                    {qgen.enabled === true && (
                      <>
                        <Row label="生成问题数量" desc="每个文档分块生成的问题数量（1-10）">
                          <input
                            type="number"
                            className="t-input__inner"
                            style={{ width: 120, height: 32 }}
                            min={1}
                            max={10}
                            value={qgen.question_count ?? 3}
                            onChange={(e) => {
                              const v = Math.min(10, Math.max(1, Number(e.target.value) || 1))
                              void saveQgen({ question_count: v })
                            }}
                          />
                        </Row>
                        <Row
                          label="问题生成要求"
                          desc="指定问题面向的人群、场景和表达方式，系统仍维护稳定输出格式"
                          stacked
                        >
                          <div className="t-textarea" style={{ width: 280 }}>
                            <textarea
                              className="t-textarea__inner t-is-default t-resize-none"
                              rows={2}
                              maxLength={4000}
                              placeholder="例如：生成客服用户常问的自然语言问题，避免考试题式表达…"
                              value={qgen.custom_instructions ?? ''}
                              onChange={(e) => setLocal('question_generation_config',
                                JSON.stringify({ ...qgen, custom_instructions: e.target.value }))}
                              onBlur={() => {
                                if ((qgen.custom_instructions ?? '') !== (savedQgen.custom_instructions ?? '')) {
                                  void saveQgen({ custom_instructions: qgen.custom_instructions ?? '' })
                                }
                              }}
                            />
                          </div>
                        </Row>
                      </>
                    )}

                    {/* ② 自动关联标签 */}
                    <Row
                      label="自动关联标签"
                      desc="文档解析完成后，从当前知识库已有标签中选择合适标签自动关联。不会创建或删除标签，并会增加一次模型调用。"
                    >
                      <Switch
                        checked={autotag.enabled === true}
                        label="自动关联标签"
                        onChange={(v) => void saveAutotag({ enabled: v }, '自动打标开关已保存')}
                      />
                    </Row>

                    {autotag.enabled === true && (
                      <>
                        <Row label="分类模型" desc="留空时使用知识库摘要模型。">
                          <ModelSelect
                            value={autotag.model_id ?? ''}
                            models={models}
                            types={CHAT_MODEL_TYPES}
                            placeholder="跟随摘要模型"
                            onChange={(v) => void saveAutotag({ model_id: v }, '分类模型已保存')}
                          />
                        </Row>
                        <Row label="单篇文档最多标签数" desc="每篇文档最多自动关联 1-10 个已有标签。">
                          <input
                            type="number"
                            className="t-input__inner"
                            style={{ width: 120, height: 32 }}
                            min={1}
                            max={10}
                            value={autotag.max_tags ?? 3}
                            onChange={(e) => {
                              const v = Math.min(10, Math.max(1, Number(e.target.value) || 1))
                              void saveAutotag({ max_tags: v })
                            }}
                          />
                        </Row>
                        <Row
                          label="跳过已有标签的文档"
                          desc="开启后，上传时已手动设置标签的文档不再自动打标签，避免覆盖人工分类意图。"
                        >
                          <Switch
                            checked={autotag.skip_if_tagged !== false}
                            label="跳过已有标签的文档"
                            onChange={(v) => void saveAutotag({ skip_if_tagged: v }, '跳过策略已保存')}
                          />
                        </Row>
                      </>
                    )}

                    {/* ③ 自动生成知识库描述（照源站 KBAdvancedSettings.vue:133-175）*/}
                    <Row
                      label="自动生成知识库描述"
                      desc="文档新增、删除或摘要更新后，从文档画像聚合自动生成知识库描述。聚合本身不调用模型，仅当聚合结果变化时才发起一次小模型调用。"
                    >
                      <Switch
                        checked={profile.enabled === true}
                        label="自动生成知识库描述"
                        onChange={(v) => void saveProfile({ enabled: v }, '描述生成开关已保存')}
                      />
                    </Row>
                    {/* ⭐ 2026-11-11：执行链已接通（源站 knowledgebase_profile.go），
                        原来那条「尚未接入」的黄色警示已删除 —— 它现在是假提示。
                        开关打开时会**立即**生成一次，之后文档增删/摘要更新时自动重算。 */}
                    {profile.enabled === true && (
                      <>
                        <Row
                          label="生成模型"
                          desc="留空时使用知识库摘要模型。"
                          stacked
                        >
                          <ModelSelect
                            value={profile.model_id ?? ''}
                            models={models}
                            types={CHAT_MODEL_TYPES}
                            placeholder="请选择生成模型"
                            onChange={(v) => void saveProfile({ model_id: v }, '生成模型已保存')}
                          />
                        </Row>
                        <Row
                          label="描述生成要求"
                          desc="补充面向的读者、需要保留的术语或表达风格，系统仍维护稳定的输出格式。"
                          stacked
                        >
                          <div className="t-textarea" style={{ width: 280 }}>
                            <textarea
                              className="t-textarea__inner t-is-default t-resize-none"
                              rows={2}
                              maxLength={2000}
                              placeholder="例如：面向售后客服，用通俗说法描述产品线，保留产品型号…"
                              value={profileLocalText('custom_instructions', profile.custom_instructions ?? '')}
                              onChange={(e) => setProfileLocal('custom_instructions', e.target.value)}
                              onBlur={(e) => void saveProfile(
                                { custom_instructions: e.target.value }, '描述生成要求已保存')}
                            />
                          </div>
                        </Row>
                      </>
                    )}

                    {/* ④ 表格元数据生成要求 */}
                    <Row
                      label="表格元数据生成要求"
                      desc="为 CSV/Excel 摘要补充业务背景和字段语义，帮助后续检索理解表格内容"
                      stacked
                    >
                      <div className="t-textarea" style={{ width: 280 }}>
                        <textarea
                          className="t-textarea__inner t-is-default t-resize-none"
                          rows={2}
                          maxLength={4000}
                          placeholder="例如：这是销售订单表，金额单位为元，status 字段使用公司内部状态码…"
                          value={chunking.table_metadata_instructions ?? ''}
                          onChange={(e) => setLocal('chunking_config',
                            JSON.stringify({ ...chunking, table_metadata_instructions: e.target.value }))}
                          onBlur={() => {
                            if ((chunking.table_metadata_instructions ?? '') !== (savedTableMeta ?? '')) {
                              void saveChunkingRaw({ table_metadata_instructions: chunking.table_metadata_instructions ?? '' })
                              setSavedTableMeta(chunking.table_metadata_instructions ?? '')
                            }
                          }}
                        />
                        <div className="t-textarea__info_wrapper t-textarea__info_wrapper_align">
                          <span className="t-textarea__limit">{(chunking.table_metadata_instructions ?? '').length}/4000</span>
                        </div>
                      </div>
                    </Row>
                    {/* ⚠️ `chunking_config.table_metadata_instructions` 也不在
                        `KbChunkingConfigParser` 认的 7 个键里 ⇒ 同上，如实告知。 */}
                    <p className="form-tip" style={{ color: 'var(--td-warning-color, #e37318)' }}>
                      ⚠️ 表格元数据要求当前只落库，分块器尚未读取该字段。
                    </p>
                  </SettingSection>
                )}

                {section === 'storage' && (
                  /* 源站真机 `KBStorageSettings.vue`：一行（下拉 + locked-hint + 「管理存储实例」链接）。 */
                  <SettingSection
                    rootClass="kb-storage-settings"
                    title="存储引擎"
                    desc="选择此知识库绑定的具体存储实例。"
                  >
                    <Row
                      label="存储实例"
                      desc="同一种存储类型可以配置多个不同 Endpoint、Bucket 或凭据的实例。"
                    >
                      <div className="t-select__wrap" style={{ width: 280 }}>
                        <select
                          className="t-input__inner"
                          style={{ width: '100%', height: 32 }}
                          disabled={kbHasFiles}
                          value={form.storage_backend_id ?? ''}
                          onChange={(e) => void saveModelField('storage_backend_id', e.target.value)}
                        >
                          <option value="">跟随全局默认</option>
                          {storageBackends.map((b) => (
                            <option key={b.id} value={b.id}>
                              {b.name}{b.is_default ? '（默认）' : ''}
                            </option>
                          ))}
                        </select>
                      </div>
                    </Row>
                    {kbHasFiles && (
                      <p className="option-hint locked-hint">知识库中已有文件，需通过存储迁移流程才能更换实例。</p>
                    )}
                    <p className="option-hint">
                      <a
                        className="go-settings"
                        href="#"
                        onClick={(e) => {
                          e.preventDefault()
                          showToast('存储实例管理在「平台设置 → 存储」，本仓暂未提供入口', 'info')
                        }}
                      >
                        管理存储实例
                      </a>
                    </p>
                  </SettingSection>
                )}

                {section === 'datasource' && (
                  /* 源站真机 `DataSourceSettings.vue`：`.ds-settings > .section-header +
                     .ds-grid > button.ds-card.ds-card--add`（无 .settings-group）。 */
                  <div className="section">
                    <div className="ds-settings">
                      <div className="section-header" style={{ ...SECTION_HEADER_FLAT, margin: '0 0 28px' }}>
                        <h2>数据源管理</h2>
                        <p className="section-description">配置外部数据源，自动同步内容到知识库</p>
                      </div>
                      <div className="ds-grid">
                        <button
                          type="button"
                          className="ds-card ds-card--add"
                          onClick={() => showToast('数据源：本仓后端暂无对应端点，暂未接入', 'info')}
                        >
                          <span className="ds-card--add__icon">＋</span>
                          <span className="ds-card--add__label">添加数据源</span>
                        </button>
                      </div>
                      {/* ⚠️ 如实告知：全仓后端 grep `datasource` 零命中 ⇒ 没有任何数据源端点。
                          ⛔ 不做「点了能填表单但保存没去处」的假入口。 */}
                      <p className="ds-guide-note">
                        本仓尚未接入数据源能力（无同步调度 / 凭据管理端点）。
                        如需定期同步，可先用「定时任务」调用知识库导入接口。
                      </p>
                    </div>
                  </div>
                )}

                {section === 'faq' && (
                  /* 源站真机 `data-panel="faq"`（FAQ 型库专属）：
                     `.section > .section-content > .section-header(h3+p) +
                      .section-body > .form-item × 2 + .faq-guide`。 */
                  <div className="section">
                    <div className="section-content">
                      <div className="section-header">
                        <h3 className="section-title">问答</h3>
                        <p className="section-desc">设置 FAQ 知识库的索引策略和问答组织方式</p>
                      </div>
                      <div className="section-body">
                        {/* ① 索引方式 → `faq_config.index_mode`（后端真消费） */}
                        <div className="form-item">
                          <label className="form-label required">索引方式</label>
                          <div className="t-radio-group t-radio-group__outline t-size-m">
                            {([
                              ['question_only', '仅标准问/相似问'],
                              ['question_answer', '标准问 + 答案'],
                            ] as const).map(([v, label]) => (
                              <label
                                key={v}
                                className={`t-radio-button${faqIndexMode === v ? ' t-is-checked' : ''}`}
                                tabIndex={0}
                                onClick={() => void saveFaq({ index_mode: v }, '索引方式已保存')}
                                onKeyDown={(e) => {
                                  if (e.key === 'Enter' || e.key === ' ') {
                                    e.preventDefault()
                                    void saveFaq({ index_mode: v }, '索引方式已保存')
                                  }
                                }}
                              >
                                <input
                                  type="radio"
                                  className="t-radio-button__former"
                                  checked={faqIndexMode === v}
                                  tabIndex={-1}
                                  readOnly
                                />
                                <span className="t-radio-button__input" />
                                <span className="t-radio-button__label">{label}</span>
                              </label>
                            ))}
                          </div>
                          <p className="form-tip">仅索引问题可提升精度，索引问答可提高召回率</p>
                        </div>

                        {/* ② 问题索引方式 —— ⚠️ 本仓零消费者 */}
                        <div className="form-item">
                          <label className="form-label required">问题索引方式</label>
                          <div className="t-radio-group t-radio-group__outline t-size-m">
                            {([
                              ['combined', '合并索引'],
                              ['separate', '分别索引'],
                            ] as const).map(([v, label]) => (
                              <label
                                key={v}
                                className={`t-radio-button${faqQuestionIndexMode === v ? ' t-is-checked' : ''}`}
                                tabIndex={0}
                                onClick={() => showToast(
                                  '问题索引方式：本仓后端尚未实现「分别索引」，当前固定按合并索引处理', 'info')}
                                onKeyDown={(e) => {
                                  if (e.key === 'Enter' || e.key === ' ') {
                                    e.preventDefault()
                                    showToast('问题索引方式：本仓后端尚未实现「分别索引」，当前固定按合并索引处理', 'info')
                                  }
                                }}
                              >
                                <input
                                  type="radio"
                                  className="t-radio-button__former"
                                  checked={faqQuestionIndexMode === v}
                                  tabIndex={-1}
                                  readOnly
                                />
                                <span className="t-radio-button__input" />
                                <span className="t-radio-button__label">{label}</span>
                              </label>
                            ))}
                          </div>
                          <p className="form-tip">
                            合并索引：标准问和相似问合并索引；分别索引：标准问和每个相似问独立索引，检索更精确但需要更多存储
                          </p>
                          {/* ⚠️ 如实告知：`question_index_mode` 在 backend **零消费者**
                              （全仓 grep 无命中）⇒ 点了不会真的改变索引方式。 */}
                          <p className="form-tip" style={{ color: 'var(--td-warning-color, #e37318)' }}>
                            ⚠️ 本仓暂不支持「分别索引」—— 该项仅展示，实际固定按合并索引处理。
                          </p>
                        </div>

                        <div className="faq-guide">
                          <p>FAQ 条目由标准问、相似问、反例和多个答案组成，可在知识库详情中批量导入、编辑。</p>
                        </div>
                      </div>
                    </div>
                  </div>
                )}

                {section === 'share' && (
                  /* 源侧 KBShareSettings.vue:2 —— .share-to-space-panel（自带 header，不再套 section-header） */
                  <div className="section">
                    <ShareSection baseId={base.id} showToast={showToast} />
                  </div>
                )}

                {section === 'activity' && (
                  /* 源侧 KnowledgeBaseActivitySettings.vue:2-32 —— section-content.kb-activity-settings。
                     ⚠️ `kb-activity-settings` 的规则在**设计稿宿主** kb-settings-panel.css（78 条），
                     本仓 kb-*.css 里 0 命中 ⇒ 这里用源站同样存在的 `section-content` 承担布局，
                     不去依赖那个没有样式的类（见报告第 10 项）。 */
                  <div className="section">
                    <div className="section-content">
                      <div className="section-header" style={{ ...SECTION_HEADER_FLAT, margin: '0 0 16px' }}>
                        <div className="kb-activity-title-row">
                          <h3 className="section-title">活动记录</h3>
                        </div>
                        <p className="section-desc">
                          查看这个知识库的重要变更与后台任务入口，记录默认遵循审计日志保留策略。
                        </p>
                      </div>
                      <div className="section-body kb-activity-body">
                        <ActivitySection baseId={base.id} showToast={showToast} />
                      </div>
                    </div>
                  </div>
                )}
              </div>
            </div>

            {/* 源侧 SettingsModalShell.vue:45-50 —— footer 是 .settings-content 的并列子块，
                且 **恒在**（只有 slot 有内容时才显示）。源侧 `INSTANT_SECTIONS =
                {datasource, share, activity}` 走「即改即存 ⇒ 只给关闭」，其余给「取消 + 保存」。
                ⚠️ 本仓 parser / chunking / graph / advanced 都是「点即存」，没有暂存态 ⇒
                给「关闭」而不是假「保存」（与原实现“这几节无保存按钮”语义一致）。 */}
            {section === 'basic' && <Footer busy={busy} onSave={saveBasic} onClose={onClose} />}
            {section === 'models' && (
              <Footer
                busy={busy}
                onClose={onClose}
                onSave={() => void patch({
                  embedding_model_id: form.embedding_model_id ?? '',
                  summary_model_id: form.summary_model_id ?? '',
                }, '模型配置已保存')}
              />
            )}
            {section === 'vectorStore' && (
              <Footer
                busy={busy}
                onClose={onClose}
                onSave={() => void patch({
                  retriever_engine: form.retriever_engine ?? '',
                  vector_store_id: form.vector_store_id ?? '',
                }, '向量库配置已保存')}
              />
            )}
            {section === 'storage' && (
              <Footer
                busy={busy}
                onClose={onClose}
                onSave={() => void patch({ storage_backend_id: form.storage_backend_id ?? '' }, '存储配置已保存')}
              />
            )}
            {section !== 'basic' && section !== 'models' && section !== 'vectorStore'
              && section !== 'storage' && (
              <Footer busy={busy} onClose={onClose} instant />
            )}
          </div>
        </div>
      </div>
    </div>
  )
}

// ── 子组件 ──────────────────────────────────────────────────────

/**
 * 分节（源侧 `kb-xxx-settings` 形态）：`.section > .{rootClass} > .section-header(h2 + .section-description) > .settings-group`。
 *
 * <p>⚠️ 不能统一成 `.section-content + h3`：源侧 11 个分节里只有「基本信息 / 活动记录」
 * 用 `section-content > h3.section-title`，其余都是各自组件根 + `h2.section-description`。
 *
 * <p>⛔ `headerReset`：`kb-shared.css:717` 把源站**只有 `.kb-chunking-settings` 用**的
 * sticky 页头规则提升成了全局 `.kb-scope .section-header`（flex 横排 + top:-24px +
 * padding 24px 32px 12px + margin -24px -32px 16px + 底部边框）。
 * 源站其余 8 个分节都是普通块级流 + `margin-bottom`（kb-settings-panel.css 里逐组件确认）。
 * ⇒ 用行内把被顶掉的 6 个属性复位成源站值（派单 §0.2 允许 style），
 * chunking 分节传 `headerReset={false}` 保留 sticky（那本来就是它的）。
 */
const SECTION_HEADER_FLAT = {
  position: 'static',
  top: 'auto',
  zIndex: 'auto',
  display: 'block',
  padding: 0,
  borderBottom: 'none',
} as const

function SettingSection({ rootClass, title, desc, headerReset = true, headerMargin = '0 0 20px', children }: {
  rootClass: string
  title: string
  desc: string
  /** 是否把全局 sticky 页头复位成源站普通块级流（见上方注释） */
  headerReset?: boolean
  headerMargin?: string
  children: React.ReactNode
}) {
  return (
    <div className="section">
      <div className={rootClass || undefined}>
        <div className="section-header" style={headerReset ? { ...SECTION_HEADER_FLAT, margin: headerMargin } : undefined}>
          <h2>{title}</h2>
          <p className="section-description">{desc}</p>
        </div>
        <div className="settings-group">{children}</div>
      </div>
    </div>
  )
}

/**
 * 一行设置：`.setting-row > .setting-info(label + .desc) + .setting-control`。
 *
 * @param stacked 控制列改 `setting-control--stacked`（源站 Embedding 行把锁定提示
 *                排在控件**下方**，不是右侧）
 * @param hint    控件下方的一行提示（源站 `.locked-hint`）
 */
function Row({ label, desc, children, stacked, hint }: {
  label: React.ReactNode
  desc?: string
  children: React.ReactNode
  stacked?: boolean
  hint?: string
}) {
  return (
    <div className="setting-row">
      <div className="setting-info">
        <label>{label}</label>
        {desc && <p className="desc">{desc}</p>}
      </div>
      <div className={`setting-control${stacked ? ' setting-control--stacked' : ''}`}>
        {children}
        {hint && <p className="locked-hint">{hint}</p>}
      </div>
    </div>
  )
}

/**
 * 模型下拉（源站 `ModelSelector` 的等价物）。
 *
 * <p>⚠️ 源站是真下拉（`t-select` + `t-option`），**不是自由输入框** —— 早期版本我们写成
 * `<input>` 让用户手打模型名，与源站不符且极易打错（写错名字后模型调用静默失败）。
 *
 * <h2>⭐⭐ 显示 `provider/modelName`，但**存的是 `models.id`**（2026-11-11 用户裁定）</h2>
 * 用户要求「所有模型选择都要 provider/modelName」，同时也问了关键问题：
 * 「通过 id 能否正常获取到这个提供者和模型，而不是会乱」。
 *
 * <p><b>答案是能，而且 ID 最安全</b>，所以两者分开：
 * <ul>
 *   <li><b>显示</b>：`provider/modelName` + 按 provider 分组的 `<optgroup>`。
 *       同名模型确实并存（实测真库 3 份 `deepseek-v4.1-flash` 分属 oc / oc_new / oc2），
 *       不显示全名用户分不清；</li>
 *   <li><b>存储</b>：`models.id`（主键）。调用链是
 *       {@code selectOneById(modelId)} → {@code model.getProviderId()} →
 *       {@code selectOneById(providerId)} —— 两次主键命中，**数学上不可能串到别的提供商**。
 *       ⛔ 不要改存 `provider/modelName`：`providers.name` 可以改名
 *       （{@code ProviderService.update}），而改名后没有任何代码级联更新引用它的全名
 *       ⇒ 已存的全名会解析失败、知识库模型突然不可用。</li>
 * </ul>
 *
 * <p>空值语义：`''` = 跟随全局默认（源站「跟随全局默认」/「跟随摘要模型」）。
 */
function ModelSelect({ value, models, types, placeholder, disabled, onChange }: {
  value: string
  models: Model[]
  types: readonly string[]
  placeholder: string
  disabled?: boolean
  onChange: (v: string) => void
}) {
  const options = models.filter((m) => types.includes(m.type))
  /** 显示用全名（`provider/modelName`）；providerName 缺失时退化成模型名。 */
  const fullNameOf = (m: Model) => (m.providerName ? `${m.providerName}/${m.name}` : m.name)

  /* 当前值不在候选里（模型被删 / 类型不符）时也要能显示出来，
     ⛔ 不能静默跳成空 —— 用户会以为配置丢了。 */
  const orphan = value !== '' && !options.some((m) => m.id === value)
  /* 按 provider 分组（`<optgroup>`）。providerName 缺失 ⇒ 回退「其他」，
     ⛔ 不能把半分组的项静默藏起来（那样用户会「找不到模型」）。 */
  const groups = new Map<string, Model[]>()
  for (const m of options) {
    const key = m.providerName || '其他'
    const arr = groups.get(key)
    if (arr) arr.push(m)
    else groups.set(key, [m])
  }
  /* 组内按模型名排序，组间按组名排序 —— 稳定顺序，避免每次渲染跳动。 */
  const groupEntries = [...groups.entries()]
    .sort((a, b) => a[0].localeCompare(b[0], 'zh-CN'))
    .map(([name, list]) => [name, [...list].sort((a, b) => a.name.localeCompare(b.name))] as const)
  return (
    <div className="t-select__wrap" style={{ width: 280 }}>
      <select
        className="t-input__inner"
        style={{ width: '100%', height: 32 }}
        disabled={disabled}
        value={value}
        onChange={(e) => onChange(e.target.value)}
      >
        <option value="">{placeholder}</option>
        {groupEntries.map(([providerName, list]) => (
          <optgroup key={providerName} label={providerName}>
            {list.map((m) => (
              /* ⭐ value 用主键 id（精确、改名无忧）；标签用全名（可读、能区分同名）。
                 重名不会导致选中错乱：两项 id 不同，<option value> 也就不同。 */
              <option key={m.id} value={m.id}>
                {/* ⚠️ alias 与 name 相同是常见情况（实测 `text-embedding-v4` 两者一致）
                    ⇒ 不加判断会渲染成「text-embedding-v4（text-embedding-v4）」。 */}
                {fullNameOf(m)}{m.alias && m.alias !== m.name ? `（${m.alias}）` : ''}
              </option>
            ))}
          </optgroup>
        ))}
        {orphan && <option value={value}>{value}（已失效）</option>}
      </select>
    </div>
  )
}

/** 开关（源侧 t-switch：handle + content，选中态 t-is-checked）。 */
function Switch({ checked, label, onChange }: {
  checked: boolean; label: string; onChange: (v: boolean) => void
}) {
  return (
    <label
      className={`t-switch t-size-m${checked ? ' t-is-checked' : ''}`}
      role="switch"
      aria-checked={checked}
      aria-label={label}
    >
      <input
        type="checkbox"
        checked={checked}
        onChange={(e) => onChange(e.target.checked)}
        style={{ position: 'absolute', opacity: 0, width: 0, height: 0, margin: 0 }}
      />
      <span className="t-switch__handle" />
      <div className="t-switch__content t-size-m" />
    </label>
  )
}

/**
 * 滑块（源站是 tdesign `t-slider`；本仓无该组件运行时 ⇒ 按同款 DOM 骨架手搭）。
 *
 * <p><b>为什么不用原生 `input[type=range]` 单独一个</b>：源站的轨道 / 已填充段 / 把手
 * 是三块独立元素（`.t-slider__rail` / `__track` / `__handle`），CSS 也按这三块写好了
 * （`kb-tdesign.css:2157+`）。只放一个 range 会得到浏览器默认皮肤，与设计稿不同。
 *
 * <p>交互：点击轨道任意位置跳转 + 拖动把手。⚠️ 提交用 `onChange`（拖动过程中**不**落库），
 * 松手才 PATCH —— 否则拖一次会打十几次请求。
 */
function Slider({ min, max, step, value, marks, unit, disabled, onChange }: {
  min: number; max: number; step: number; value: number
  marks?: readonly number[]
  unit?: string
  disabled?: boolean
  onChange: (v: number) => void
}) {
  const safe = Number.isFinite(value) ? Math.min(max, Math.max(min, value)) : min
  const pct = max > min ? ((safe - min) / (max - min)) * 100 : 0
  const [drag, setDrag] = useState(safe)
  useEffect(() => { setDrag(safe) }, [safe])
  /**
   * ⚠️ **不对齐 step**，只夹紧到 [min,max]。
   *
   * <p>踩过的坑：第一版按 `step` 取整，而分块大小默认值是 **512**、`step=100`
   * ⇒ 一进来就被吸到 500，滑块显示 500 而旁边文字显示 512（自相矛盾），
   * 而且用户只要碰一下滑块，512 就被永久改成 500。
   * 源站的 `step` 只用于**键盘/轨道刻度吸附**，不改变已有合法值。
   */
  const commit = (v: number) => {
    const clamped = Math.min(max, Math.max(min, v))
    setDrag(clamped)
    onChange(clamped)
  }
  return (
    <div className="slider-container" style={{ width: 200, opacity: disabled ? 0.5 : 1 }}>
      <div className={`t-slider${disabled ? ' t-is-disabled' : ''}`}>
        <div className="t-slider__rail" />
        <div className="t-slider__track" style={{ width: `${pct}%` }} />
        <div
          className="t-slider__handle"
          role="slider"
          tabIndex={disabled ? -1 : 0}
          aria-valuemin={min}
          aria-valuemax={max}
          aria-valuenow={drag}
          aria-disabled={disabled}
          style={{ left: `${pct}%` }}
          onKeyDown={(e) => {
            if (disabled) return
            if (e.key === 'ArrowRight' || e.key === 'ArrowUp') { e.preventDefault(); commit(drag + step) }
            if (e.key === 'ArrowLeft' || e.key === 'ArrowDown') { e.preventDefault(); commit(drag - step) }
          }}
        />
        {/* 真实控件承载指针交互：透明覆盖在整个轨道上，避免自己实现 drag 数学 */}
        <input
          type="range"
          min={min}
          max={max}
          step={step}
          value={drag}
          disabled={disabled}
          aria-label="滑块"
          style={{
            position: 'absolute', inset: 0, width: '100%', height: '100%',
            opacity: 0, cursor: disabled ? 'not-allowed' : 'pointer', margin: 0,
          }}
          onChange={(e) => setDrag(Number(e.target.value))}
          onPointerUp={() => commit(drag)}
          onKeyUp={() => commit(drag)}
        />
        {marks && marks.length > 0 && (
          <div className="t-slider__mark">
            {marks.map((m) => (
              <span
                key={m}
                style={{
                  position: 'absolute',
                  left: `${max > min ? ((m - min) / (max - min)) * 100 : 0}%`,
                  transform: 'translateX(-50%)',
                  fontSize: 12,
                  color: 'var(--td-text-color-placeholder)',
                  whiteSpace: 'nowrap',
                }}
              >
                {m}
              </span>
            ))}
          </div>
        )}
      </div>
      <div style={{ textAlign: 'center', fontSize: 13, marginTop: 2 }}>
        {drag}{unit ? ` ${unit}` : ''}
      </div>
    </div>
  )
}

/**
 * 标签输入（源站 tdesign `t-tag-input`）：已选项显示为可删除 chip，右侧可下拉追加。
 *
 * <p>本仓无该组件运行时 ⇒ 手搭同款结构。⚠️ 值域必须去重 —— 重复分隔符会让切分器
 * 在同一位置反复尝试，白白多跑几轮。
 */
function TagInput({ value, options, placeholder, onChange }: {
  value: string[]
  options: readonly { value: string; label: string }[]
  placeholder: string
  onChange: (v: string[]) => void
}) {
  const current = value || []
  const avail = options.filter((o) => !current.includes(o.value))
  const labelOf = (v: string) => options.find((o) => o.value === v)?.label ?? v
  return (
    <div className="t-tag-input t-tag-input--break-line" style={{ width: 280 }}>
      <div
        className="t-input__wrap"
        style={{ display: 'flex', flexWrap: 'wrap', gap: 4, alignItems: 'center', padding: 4, minHeight: 32 }}
      >
        {current.map((v) => (
          <span key={v} className="t-tag t-tag--default t-tag--light t-size-s" style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
            <span className="t-tag__text">{labelOf(v)}</span>
            <span
              role="button"
              tabIndex={0}
              aria-label={`移除 ${labelOf(v)}`}
              style={{ cursor: 'pointer', lineHeight: 1 }}
              onClick={() => onChange(current.filter((x) => x !== v))}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault()
                  onChange(current.filter((x) => x !== v))
                }
              }}
            >
              ×
            </span>
          </span>
        ))}
        {avail.length > 0 && (
          <select
            className="t-input__inner"
            value=""
            style={{ width: 120, height: 24, border: 'none', background: 'transparent' }}
            aria-label={placeholder}
            onChange={(e) => {
              if (!e.target.value) return
              if (!current.includes(e.target.value)) onChange([...current, e.target.value])
            }}
          >
            <option value="">{current.length ? '＋ 添加' : placeholder}</option>
            {avail.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
        )}
      </div>
    </div>
  )
}

/** 底部操作条（源侧 SettingsModalShell.vue:45-50 的 footer 插槽；footer 恒在，内容随分节变）。 */
function Footer({ busy, onSave, onClose, instant }: {
  busy: boolean
  onSave?: () => void
  onClose: () => void
  /** 源侧 `INSTANT_SECTIONS = {datasource, share, activity}` —— 即改即存，只给一个「关闭」 */
  instant?: boolean
}) {
  return (
    <div className="settings-footer">
      <div className="settings-footer-actions">
        {instant || !onSave ? (
          <button
            type="button"
            className="t-button t-button--variant-outline t-button--theme-default"
            onClick={onClose}
          >
            <span className="t-button__text">关闭</span>
          </button>
        ) : (
          <>
            <button
              type="button"
              className="t-button t-button--variant-outline t-button--theme-default"
              onClick={onClose}
            >
              <span className="t-button__text">取消</span>
            </button>
            <button
              type="button"
              className="t-button t-button--variant-base t-button--theme-primary"
              disabled={busy}
              /* ⚠️ 本批只改文案（对齐源站「保存并关闭」）；保存后是否自动关窗属面板/交互批次，
                 且 `saveBasic` 在「没有改动」时是早退 ⇒ 此处仍只调 onSave。 */
              onClick={onSave}
            >
              <span className="t-button__text">{busy ? '保存中…' : '保存并关闭'}</span>
            </button>
          </>
        )}
      </div>
    </div>
  )
}

/** 分享分节（复用后端已有的 share 端点）。 */
function ShareSection({ baseId, showToast }: {
  baseId: string; showToast: (m: string, t?: 'success' | 'info') => void
}) {
  const [shares, setShares] = useState<Array<{ id: string; permission: string; effective?: boolean }>>([])
  const [loading, setLoading] = useState(true)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const list = await knowledgeApi.listShares(baseId)
      setShares(list as never)
    } catch {
      setShares([])
    } finally {
      setLoading(false)
    }
  }, [baseId])

  useEffect(() => { void load() }, [load])

  return (
    <div className="share-to-space-panel">
      <div className="share-panel-header">
        <div className="share-panel-header-row">
          <div className="share-panel-titlewrap">
            <h2 className="share-panel-title">共享到共享空间</h2>
          </div>
        </div>
        <p className="share-panel-desc">把这个知识库分享到共享空间（供团队其他成员使用）。</p>
      </div>

      <div className="share-panel-list-wrap">
        <div className="share-panel-list-header">
          <div className="share-panel-titlewrap">
            <span className="share-panel-list-title">已共享到</span>
            <span className="share-panel-count-badge">{shares.length}</span>
          </div>
          <div className="share-panel-actions">
            <button
              type="button"
              className="t-button t-button--variant-outline t-button--theme-primary t-size-s share-panel-add-btn"
              onClick={async () => {
                try {
                  await knowledgeApi.createShare(baseId, { permission: 'viewer' })
                  showToast('已分享', 'success')
                  void load()
                } catch (e) {
                  showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
                }
              }}
            >
              <span className="t-button__text">＋ 分享到共享空间</span>
            </button>
          </div>
        </div>

        {loading && (
          <div className="share-panel-loading">
            <span>加载中…</span>
          </div>
        )}

        {!loading && shares.length === 0 && (
          <div className="share-panel-empty">
            <div className="t-empty t-size-m">
              <div className="t-empty__title">还没有分享</div>
              <div className="t-empty__description">分享后其他成员可在「共享空间」里看到。</div>
            </div>
          </div>
        )}

        {!loading && shares.length > 0 && (
          <div className="share-panel-table-shell">
            <div className="t-table t-table--hoverable">
              <table>
                <thead className="t-table__header">
                  <tr>
                    <th><div className="t-table__th-cell-inner">共享空间</div></th>
                    <th><div className="t-table__th-cell-inner">权限</div></th>
                    <th><div className="t-table__th-cell-inner">状态</div></th>
                    <th><div className="t-table__th-cell-inner">操作</div></th>
                  </tr>
                </thead>
                <tbody className="t-table__body">
                  {shares.map((s) => (
                    <tr key={s.id}>
                      <td>
                        <div className="share-space-cell">
                          <span className="share-space-name">
                            <span className="share-space-name-text">共享空间</span>
                          </span>
                        </div>
                      </td>
                      <td>
                        <div className="share-permission-cell">
                          <span className="t-tag t-tag--default t-tag--light t-size-s">
                            <span className="t-tag__text">{s.permission}</span>
                          </span>
                        </div>
                      </td>
                      <td>
                        <span className="share-space-meta">
                          {s.effective === false ? '接收方未接入，暂不生效' : '生效中'}
                        </span>
                      </td>
                      <td>
                        <div className="share-table-actions">
                          <button
                            type="button"
                            className="t-button t-button--variant-text t-button--theme-danger t-size-s"
                            onClick={async () => {
                              try {
                                await knowledgeApi.deleteShare(baseId, s.id)
                                void load()
                              } catch (e) {
                                showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
                              }
                            }}
                          >
                            <span className="t-button__text">取消分享</span>
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}

/**
 * 活动记录分节（后端 `GET /knowledge-bases/{id}/activity`）。
 *
 * <p><b>⭐ 本批修掉的两个真实缺陷</b>（B8）：
 * <ol>
 *   <li><b>契约错配</b>：后端返回 `{"data":[...]}`，而 `knowledgeApi.listActivity`
 *       原来读 `r.items` ⇒ **永远空数组** ⇒ 面板恒显示「暂无活动记录」，
 *       接口 200、零报错。（已在 API 层修正）</li>
 *   <li><b>列不全</b>：源站是 **5 列**（时间/操作/对象/发起人/结果），
 *       这里原来只有 3 列，且字段名读的是 `detail`（后端是 `details`）。</li>
 * </ol>
 */
function ActivitySection({ baseId, showToast }: {
  baseId: string; showToast: (m: string, t?: 'success' | 'info') => void
}) {
  type Row = {
    id: number; action: string; outcome: string
    actor_user_id: string; actor_role: string
    target_type: string; target_id: string; details: string; created_at: string
  }
  const [rows, setRows] = useState<Row[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    void (async () => {
      try {
        const list = await knowledgeApi.listActivity(baseId)
        if (!cancelled) setRows(list as Row[])
      } catch (e) {
        if (!cancelled) {
          showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
          setRows([])
        }
      } finally {
        if (!cancelled) setLoading(false)
      }
    })()
    return () => { cancelled = true }
  }, [baseId, showToast])

  if (loading) {
    return (
      <div className="audit-loading-more">
        <span>加载中…</span>
      </div>
    )
  }
  if (rows.length === 0) {
    return (
      <div className="t-empty t-size-m">
        <div className="t-empty__title">暂无活动记录</div>
        <div className="t-empty__description">上传、解析、编辑等操作会记录在这里。</div>
      </div>
    )
  }
  /** 操作名 → 中文（后端存的是 `chunk_edit` 这类英文码）。未知码原样显示，⛔ 不猜。 */
  const actionLabel = (a: string) => ({
    chunk_edit: '编辑分块',
    kb_update: '更新知识库',
    kb_create: '创建知识库',
    kb_delete: '删除知识库',
    knowledge_upload: '上传文档',
    knowledge_delete: '删除文档',
    knowledge_reparse: '重解析',
    faq_create: '新增 FAQ',
    faq_update: '更新 FAQ',
    faq_delete: '删除 FAQ',
  }[a] || a)
  return (
    <>
      <div className="audit-scroll-area narrow-scrollbar kb-activity-branch">
        <div className="data-table-shell audit-table-shell">
          <div className="t-table t-table--hoverable">
            <table>
              <thead className="t-table__header">
                <tr>
                  <th><div className="t-table__th-cell-inner">时间</div></th>
                  <th><div className="t-table__th-cell-inner">操作</div></th>
                  <th><div className="t-table__th-cell-inner">对象</div></th>
                  <th><div className="t-table__th-cell-inner">发起人</div></th>
                  <th className="t-align-center"><div className="t-table__th-cell-inner">结果</div></th>
                </tr>
              </thead>
              <tbody className="t-table__body">
                {rows.map((r) => (
                  <tr key={r.id}>
                    <td>
                      <div className="audit-time">
                        <span className="audit-time-clock">{r.created_at || ''}</span>
                      </div>
                    </td>
                    <td>
                      <div className="t-tag t-tag--primary t-tag--light t-size-s">
                        <span className="t-tag__text">{actionLabel(r.action)}</span>
                      </div>
                    </td>
                    <td>
                      {/* ⚠️ 必须加分隔：两个 span 直接相邻时 `textContent` 会拼成
                          「chunkdfeea949…」（实测），看起来像一坨乱码。 */}
                      <div className="audit-target">
                        <span className="audit-target-key">{r.target_type || '—'}</span>
                        {r.target_id && (
                          <span className="audit-target-id" title={r.target_id}>
                            {' · '}{r.target_id.length > 8 ? `${r.target_id.slice(0, 8)}…` : r.target_id}
                          </span>
                        )}
                      </div>
                    </td>
                    <td>
                      <div className="audit-actor">
                        <span className="audit-actor-name">{r.actor_user_id || '系统触发'}</span>
                        {r.actor_role && <span className="audit-actor-key">{' · '}{r.actor_role}</span>}
                      </div>
                    </td>
                    <td className="t-align-center">
                      <div className={`t-tag t-tag--${r.outcome === 'success' ? 'success' : 'danger'} t-tag--light t-size-s`}>
                        <span className="t-tag__text">{r.outcome === 'success' ? '成功' : (r.outcome || '—')}</span>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </div>
      <p className="audit-end-hint">没有更早的记录了</p>
    </>
  )
}
