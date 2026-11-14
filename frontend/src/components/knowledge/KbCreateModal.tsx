import { Fragment, useEffect, useState } from 'react'
import { api } from '@/api/rest'
import { knowledgeApi } from '@/api/knowledge'
import type { ParserEngineInfo } from '@/api/knowledge'
import {
  engineOptionsForGroup,
  enginesForExtensions,
  defaultEngineForGroup,
  parserGroupsWithDynamic,
} from './kbParserGroups'
import type { Model } from '@/api/types'
import { IconSprite } from './IconSprite'
import {
  CHUNK_STRATEGIES,
  CHUNK_SIZE_MARKS,
  CHUNK_OVERLAP_MARKS,
  PARENT_CHUNK_SIZE_MARKS,
  CHILD_CHUNK_SIZE_MARKS,
  SEPARATOR_OPTIONS,
  LANGUAGE_OPTIONS,
  Row,
  Slider,
  Switch,
  TagInput,
} from './KbSettingsModal'

export interface KbCreateSubmit {
  name: string
  description: string
  type: 'document' | 'faq'
  embeddingModelId: string | null
  chunkSize: number
  chunkOverlap: number
  /**
   * 分块配置 JSON（`chunking_config`，**已序列化**）。
   *
   * <p>后端 {@code KbChunkingConfigParser} 认的键名：
   * {@code chunk_size / chunk_overlap / split_markers / strategy /
   * enable_parent_child / parent_chunk_size / child_chunk_size / token_limit / languages}。
   * ⛔ 不传这个字段时后端用 DB 默认值 —— 用户改了滑块也会被静默丢弃（改造前的真实状态）。
   *
   * <p>FAQ 型知识库没有分块流水线 ⇒ 传 null。
   */
  chunkingConfig?: string | null
  /**
   * 索引策略 —— RAG / Wiki / 图谱三个开关（对齐源码
   * `KnowledgeBaseEditorModal.vue:1690-1694` 的 `data.indexing_strategy`）。
   *
   * <p>⚠️ 这三个是**并列能力**，不是互斥类型：一个文档型知识库可以同时开 RAG 与 Wiki。
   * 源侧注释写死「Wiki enablement is carried solely by `indexing_strategy.wiki_enabled`」
   * （`:1675`）⇒ Wiki 不是 `type` 的一个取值。
   *
   * <p>传 `null` = 用户没动过这三项（FAQ 库不显示索引策略，也不该写空对象）。
   */
  indexingStrategy: {
    vector_enabled: boolean
    keyword_enabled: boolean
    wiki_enabled: boolean
    graph_enabled: boolean
  } | null
  /**
   * 抽取配置 JSON（`extract_config`）。
   *
   * <p>⭐ 图谱判据是两字段合取（`KbPostProcessConfig.graphEnabled`）：
   * `indexing_strategy.graph_enabled` ∧ `extract_config.enabled`。
   * 只写前一个 ⇒ 后端 parse 出 `enabled=false` ⇒ 勾了图谱但一个实体都不抽。
   * 本仓后端收 **String**（列是 TEXT 存 JSON）⇒ 这里已序列化好，⛔ api 层别再 stringify。
   */
  extract_config?: string | null
  /**
   * 图片处理三项（V286 顶层列）· 可选。
   *
   * <p>创建弹窗本批**没有**对应控件 ⇒ 不传时由调用方（`KnowledgeBasePage`）按后端默认
   * `true` / `true` / `default` 显式补齐。⚠️ `imagePipeline` 只允许
   * `default` | `smartocr`；它<b>不是</b> `image_processing_config.image_pipeline`
   * （旧键，取值 `""`/`"attrs"`，后端零消费者）。
   */
  imageCaptionEnabled?: boolean
  imageOcrEnabled?: boolean
  imagePipeline?: 'default' | 'smartocr'
  /**
   * Wiki 专属可调项 JSON（`wiki_config`，**已序列化**）。
   *
   * <p>⭐ Wiki 的**开关**不在这里（见 {@link indexingStrategy.wiki_enabled}），
   * 这里只放源站 `wiki_config` 的五个键：`auto_generate` / `synthesis_model_id` /
   * `max_pages_per_ingest` / `extraction_granularity` / `content_instructions` /
   * `extraction_instructions`（`KnowledgeBaseEditorModal.vue:1677-1686`）。
   *
   * <p>FAQ 型没有 Wiki 流水线 ⇒ 传 null。
   */
  wikiConfig?: string | null
  /**
   * 图片处理配置 JSON（`image_processing_config`，**已序列化**）。
   *
   * <p>本仓后端从这段 JSON 读两项：`enable_multimodal` ∧ `model_id`（`KbPostProcessConfig`）。
   * 源站其余键（`description_language` / `custom_instructions` / `enable_image_vector`）
   * 一并落库，供图片理解链路后续消费。
   */
  imageProcessingConfig?: string | null
  /** ASR 配置 JSON（`asr_config`，**已序列化**）：`{enabled, model_id, language}`。 */
  asrConfig?: string | null
  /** 问题生成配置 JSON（`question_generation_config`，**已序列化**）。 */
  questionGenerationConfig?: string | null
  /** 自动打标配置 JSON（`auto_tag_config`，**已序列化**）。 */
  autoTagConfig?: string | null
  /** 知识库画像配置 JSON（`profile_config`，**已序列化**）。 */
  profileConfig?: string | null
  /** FAQ 配置 JSON（`faq_config`，**已序列化**）：`{index_mode, question_index_mode}`。 */
  faqConfig?: string | null
  /** 绑定的向量库实例 ID（`vector_store_id`）；null/空 = 系统默认。 */
  vectorStoreId?: string | null
  /** 绑定的存储实例 ID（`storage_backend_id`）；null/空 = 租户默认。 */
  storageBackendId?: string | null
}

interface Props {
  showToast: (msg: string, type?: 'success' | 'info') => void
  onClose: () => void
  onCreate: (payload: KbCreateSubmit) => Promise<void>
  /** 已存在的知识库（编辑态）*/
  initial?: { name: string; description: string; type: 'document' | 'faq' } | null
}

/**
 * ⚠️ `kb-shared.css:717` 把源站**只有 `.kb-chunking-settings` 用**的 sticky 页头规则
 * 提升成了全局 `.kb-scope .section-header`（flex 横排 + top:-24px + padding 24px 32px 12px
 * + margin -24px -32px 16px + 底部边框）。源站其余分节都是普通块级流。
 * ⇒ 行内把被顶掉的 6 个属性复位成源站值（派单 §0.2 允许 style）。
 */
const SECTION_HEADER_FLAT = {
  position: 'static',
  top: 'auto',
  zIndex: 'auto',
  display: 'block',
  padding: 0,
  borderBottom: 'none',
} as const

/**
 * 模型可选类型（对齐本仓 `Model.type`）。
 *
 * <p>源站用 `KnowledgeQA` / `VLLM` / `ASR` 三种模型类型；本仓 `/api/v1/models`
 * 的枚举是 `chat|multimodal|embedding|...`，因此按语义等价映射：
 * 文本生成 = `chat + multimodal`；视觉 = `multimodal`；语音识别 = `asr`（兼容旧 `audio`）。
 */
const CHAT_MODEL_TYPES = ['chat', 'multimodal'] as const
const VISION_MODEL_TYPES = ['multimodal', 'vision'] as const
const ASR_MODEL_TYPES = ['asr', 'audio'] as const

/** Wiki 抽取粒度三档（文案/提示照抄 `knowledgeEditor.wiki.granularity*`）。 */
const WIKI_GRANULARITY_OPTIONS = [
  { value: 'focused', label: '聚焦', hint: '只抽取文档的主角（如简历 → 人物和项目）。最干净，但可能漏掉次要实体。' },
  { value: 'standard', label: '标准', hint: '抽取主角 + 被详细描述的次要实体/概念。跳过一带而过的通用名词。适合大多数场景。' },
  { value: 'exhaustive', label: '详尽', hint: '抽取所有可识别的命名实体与概念，包括一带而过的技术栈。适合将知识库当作术语表使用。' },
] as const

/**
 * 模型下拉（同 `KbSettingsModal` 内部 `ModelSelect` 的实现口径：显示全名、存 `models.id`）。
 *
 * <p>⛔ 不复用 `KbSettingsModal` 的私有组件（未导出）；控件 DOM 与它逐字一致，
 * 避免两处下拉外观/取值语义漂移。
 */
function ModelSelect({ value, models, types, placeholder, onChange }: {
  value: string
  models: Model[]
  types: readonly string[]
  placeholder: string
  onChange: (v: string) => void
}) {
  const options = models.filter((m) => types.includes(m.type))
  const fullNameOf = (m: Model) => (m.providerName ? `${m.providerName}/${m.name}` : m.name)
  const groups = new Map<string, Model[]>()
  for (const m of options) {
    const key = m.providerName || '其他'
    const arr = groups.get(key)
    if (arr) arr.push(m)
    else groups.set(key, [m])
  }
  const groupEntries = [...groups.entries()]
    .sort((a, b) => a[0].localeCompare(b[0], 'zh-CN'))
    .map(([name, list]) => [name, [...list].sort((a, b) => a.name.localeCompare(b.name))] as const)
  const orphan = value !== '' && !options.some((m) => m.id === value)
  return (
    <div className="t-select__wrap" style={{ width: 280 }}>
      <select
        className="t-input__inner"
        style={{ width: '100%', height: 32 }}
        value={value}
        onChange={(e) => onChange(e.target.value)}
      >
        <option value="">{placeholder}</option>
        {groupEntries.map(([providerName, list]) => (
          <optgroup key={providerName} label={providerName}>
            {list.map((m) => (
              <option key={m.id} value={m.id}>{fullNameOf(m)}</option>
            ))}
          </optgroup>
        ))}
        {orphan && <option value={value}>{value}（已失效）</option>}
      </select>
    </div>
  )
}

/**
 * 可自由输入的标签多选（对齐源站 `t-select multiple creatable`）。
 *
 * <p>⛔ 不用 `KbSettingsModal` 导出的 `TagInput`：它只能从固定选项里挑，
 * 而图谱「关系类型」必须允许用户输入自定义标签（源站 `creatable`）。
 */
function FreeTagInput({ value, placeholder, onChange }: {
  value: string[]
  placeholder: string
  onChange: (v: string[]) => void
}) {
  const [draft, setDraft] = useState('')
  const add = () => {
    const v = draft.trim()
    if (!v) return
    if (!value.includes(v)) onChange([...value, v])
    setDraft('')
  }
  return (
    <div className="t-tag-input t-tag-input--break-line" style={{ width: 280 }}>
      <div
        className="t-input__wrap"
        style={{ display: 'flex', flexWrap: 'wrap', gap: 4, alignItems: 'center', padding: 4, minHeight: 32 }}
      >
        {value.map((v) => (
          <span key={v} className="t-tag t-tag--default t-tag--light t-size-s" style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
            <span className="t-tag__text">{v}</span>
            <span
              role="button"
              tabIndex={0}
              aria-label={`移除 ${v}`}
              style={{ cursor: 'pointer', lineHeight: 1 }}
              onClick={() => onChange(value.filter((x) => x !== v))}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault()
                  onChange(value.filter((x) => x !== v))
                }
              }}
            >
              ×
            </span>
          </span>
        ))}
        <input
          className="t-input__inner"
          style={{ flex: 1, minWidth: 100, height: 24, border: 'none', background: 'transparent' }}
          value={draft}
          placeholder={value.length ? '＋ 添加' : placeholder}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); add() } }}
          onBlur={add}
        />
      </div>
    </div>
  )
}

/** 数字输入（源站 `t-input-number`，min/max clamp）。 */
function NumberInput({ value, min, max, onChange }: {
  value: number
  min: number
  max: number
  onChange: (v: number) => void
}) {
  return (
    <div className="t-input-number t-size-m t-input-number--normal">
      <input
        type="number"
        className="t-input__inner"
        style={{ width: 120, height: 32 }}
        min={min}
        max={max}
        value={value}
        onChange={(e) => {
          const n = Number(e.target.value)
          if (!Number.isFinite(n)) return
          onChange(Math.min(max, Math.max(min, Math.trunc(n))))
        }}
      />
    </div>
  )
}

/**
 * 「新建知识库」配置弹窗 · 逐项对齐源侧真机
 * （`SettingsModalShell.vue` + `KnowledgeBaseEditorModal.vue`）。
 *
 * <h2>⭐ 为什么是「左侧分组导航 + 右侧分节」而不是一个输入框</h2>
 * 我原来只有一个输入框 —— 但源侧点「新建知识库」打开的是 1080×780 的
 * 配置弹窗，左边 208px 分三组共 11 项导航，右边按「基本信息/模型配置/
 * 向量存储/…」分节滚动。用户明确要求「完全按照这种拔下来」，所以按真机重做。
 *
 * <h2>真机实测尺寸（⛔ 不要凭感觉改）</h2>
 * <pre>
 * 弹窗      1080 × 780，圆角 12px，居中
 * 侧栏      208px，背景 #f9f9f9，右边框 1px #e7e7e7
 * 侧栏标题  padding 16px 14px 12px + 底部 1px 分割线，16px/600
 * 分组标题  12px/600，color rgba(0,0,0,.4)，padding 8px 14px 2px
 * 导航项    padding 6px 12px，圆角 6px，14px；激活 #f3f3f3 背景 + 绿字 #07c05f
 * 右侧内容  padding 24px 32px
 * 节标题    20px/600，描述 14px rgba(0,0,0,.4)，标题下 6px
 * 表单项    下间距 16px；label 15px/500，下 8px
 * 输入框    高 32px，边框 1px #dcdcdc，圆角 3px，内文 14px
 * 页脚      高 57px，右对齐，上边框 1px #e7e7e7，padding 12px 40px
 * 主按钮    32px 高，绿底，圆角 3px，padding 0 15px
 * </pre>
 *
 * <h2>⚠️ 与源侧的差异（本仓后端不具备的，如实收窄而非画空壳）</h2>
 * <ul>
 *   <li>知识库类型只有 `文档` / `问答`（后端 `type` 枚举），源侧的 `Wiki` 是本仓的索引策略。</li>
 *   <li>Wiki / 向量存储 / 解析引擎 / 图像 / 音频 / 图谱 / 高级设置 / 存储引擎
 *       本批均已接进 `CreateKnowledgeBaseRequest` 的新增字段，控件真写入。</li>
 *   <li>源站的图片属性观察面板（`ImagePipelineSettings` + `/image-attrs/schema`）
 *       本仓无注册表接口 ⇒ 不画；图片管线只保留后端真有的 `default` / `smartocr` 下拉。</li>
 *   <li>「AI 生成描述」卡片在源站是**编辑态**动作（需要知识库 ID + 已解析文档）
 *       ⇒ 创建态只放说明，不放按钮。</li>
 * </ul>
 */
export function KbCreateModal({ showToast, onClose, onCreate, initial }: Props) {
  const [tab, setTab] = useState<TabKey>('basic')
  const [name, setName] = useState(initial?.name ?? '')
  const [description, setDescription] = useState(initial?.description ?? '')
  const [type, setType] = useState<'document' | 'faq'>(initial?.type ?? 'document')
  const [rag, setRag] = useState(true)
  const [wiki, setWiki] = useState(false)
  const [chunkSize, setChunkSize] = useState(512)
  const [chunkOverlap, setChunkOverlap] = useState(80)
  /**
   * 分块设置其余 7 项（与设置弹窗同一组）。默认值对齐源站：
   * 策略空 = 按长度切分；分隔符 = 段落 → 句子 → 标点；父子关闭、父 4096 / 子 384；
   * token 上限 0 = 关闭；语言空 = 自动检测。
   */
  const [chunk, setChunk] = useState<{
    strategy: string
    separators: string[]
    enableParentChild: boolean
    parentChunkSize: number
    childChunkSize: number
    tokenLimit: number
    languages: string[]
  }>({
    strategy: '',
    separators: ['\n\n', '\n', '。'],
    enableParentChild: false,
    parentChunkSize: 4096,
    childChunkSize: 384,
    tokenLimit: 0,
    languages: [],
  })
  /** 设计稿 01 页 create-modal 里存在、但本仓**创建接口写不进去**的字段。
   *  ⚠️ 只在本弹窗内维护（点了会记录 + 提示「创建后可配置」），⛔ 不进提交载荷。 */
  const [summaryModel, setSummaryModel] = useState('')
  const [embeddingModel, setEmbeddingModel] = useState('')
  const [wikiModel, setWikiModel] = useState('')
  /**
   * 真实模型清单（`GET /api/v1/models`）。
   *
   * <p>⚠️ 设计稿 01 页的模型配置三行是 `<select>`（**不是输入框**）：
   * <pre>
   * 摘要模型  <select>deepseek-flash / deepseek-v4-pro</select>
   * Embedding <select>自动选用（第一个启用的 embedding）</select>
   * Wiki 合成 <select>跟随摘要模型</select>
   * </pre>
   * 早先版本写成了 `<input>`（可自由输入），与设计稿不符 —— 已按设计稿改回下拉。
   */
  const [models, setModels] = useState<Model[]>([])

  /**
   * Wiki 专属配置（源站 `knowledgeEditor.wiki` 四项 + 合成模型已在「模型配置」节）。
   *
   * <p>⭐ 只在 `wiki` 勾选时显示（源码 `:84/:91/:112/:130` 全是
   * `v-if="!isFAQ && formData.indexingStrategy.wikiEnabled"`）。
   */
  const [wikiCfg, setWikiCfg] = useState<{
    autoGenerate: boolean
    granularity: 'focused' | 'standard' | 'exhaustive'
    contentInstructions: string
    extractionInstructions: string
  }>({ autoGenerate: true, granularity: 'standard', contentInstructions: '', extractionInstructions: '' })

  /** 向量库实例（`GET /api/v1/vector-stores`）：空值 = 系统默认。 */
  const [vectorStores, setVectorStores] = useState<Array<{ id: string; name: string; engine_type?: string }>>([])
  const [vectorStoreId, setVectorStoreId] = useState('')
  /** 存储后端实例（`GET /api/v1/storage-backends`）：空值 = 租户默认。 */
  const [storageBackends, setStorageBackends] = useState<Array<{ id: string; name: string; provider: string; is_default?: boolean }>>([])
  const [storageBackendId, setStorageBackendId] = useState('')

  /** 图像处理（源站 multimodal 节，`v-if` 都挂在「多模态开关」上）。 */
  const [vlm, setVlm] = useState(false)
  const [vlmModelId, setVlmModelId] = useState('')
  const [imageDescLanguage, setImageDescLanguage] = useState('')
  const [imageInstructions, setImageInstructions] = useState('')
  const [imageVector, setImageVector] = useState(false)
  /**
   * 图片管线 —— **顶层列** `image_pipeline`（本仓后端真消费：`KbImageSettings`）。
   * ⚠️ 与 `image_processing_config.image_pipeline`（旧键、`""`/`"attrs"`、零消费者）同名不同义。
   */
  const [imagePipeline, setImagePipeline] = useState<'default' | 'smartocr'>('default')

  /** 音频（ASR）。 */
  const [asr, setAsr] = useState(false)
  const [asrModelId, setAsrModelId] = useState('')

  /**
   * 知识图谱开关 —— **一处状态、两处入口**。
   *
   * <p>⭐ 索引策略第三张卡与「知识图谱」tab 的开关共用它，否则两处显示会打架
   * （用户在 tab 里开了、策略卡还是关的，最终只写其中一个字段）。
   * 提交时它会**同时**写 `indexing_strategy.graph_enabled` 与 `extract_config.enabled`
   * —— 后端判据是这两者的合取（见 `KbPostProcessConfig.graphEnabled`）。
   */
  const [graph, setGraph] = useState(false)
  /** 图谱抽取配置（源站 `GraphSettings.vue`；写进 `extract_config`）。 */
  const [graphInstructions, setGraphInstructions] = useState('')
  const [graphTags, setGraphTags] = useState<string[]>([])
  const [graphText, setGraphText] = useState('')
  const [graphNodes, setGraphNodes] = useState<Array<{ name: string; attributes: string[] }>>([])

  /** 高级设置 · AI 问题生成 4 项。 */
  const [qgen, setQgen] = useState(false)
  const [qgenModelId, setQgenModelId] = useState('')
  const [qgenCount, setQgenCount] = useState(3)
  const [qgenInstructions, setQgenInstructions] = useState('')
  /** 高级设置 · 自动关联标签 4 项。 */
  const [autotag, setAutotag] = useState(false)
  const [autotagModelId, setAutotagModelId] = useState('')
  const [autotagMaxTags, setAutotagMaxTags] = useState(3)
  const [autotagSkipIfTagged, setAutotagSkipIfTagged] = useState(true)
  /** 高级设置 · 自动生成知识库描述 3 项。 */
  const [profileEnabled, setProfileEnabled] = useState(false)
  const [profileModelId, setProfileModelId] = useState('')
  const [profileInstructions, setProfileInstructions] = useState('')
  /** 高级设置 · 表格元数据要求（写进 `chunking_config.table_metadata_instructions`）。 */
  const [tableMetadataInstructions, setTableMetadataInstructions] = useState('')

  /** FAQ 索引方式（源站 `knowledgeEditor.faq` 两组单选）。 */
  const [faqIndexMode, setFaqIndexMode] = useState<'question_only' | 'question_answer'>('question_only')
  /**
   * 问题索引方式 · 默认 `combined`（`separate` 后端零消费者）。
   *
   * <p>⚠️ 与「知识库设置」弹窗同一事实：`question_index_mode` 在 backend 没有实现
   * （设置弹窗 `KbSettingsModal` 的 FAQ 节已如实标注「本仓暂不支持分别索引」）。
   * 创建态旧值默认是 `separate` 且可自由点选 ⇒ 与设置弹窗自相矛盾，
   * 用户选了 `separate` 却拿不到该行为（2026-11-14 用户报「创建和设置不一致」）。
   */
  const [faqQuestionIndexMode, setFaqQuestionIndexMode] = useState<'combined' | 'separate'>('combined')

  // 拉真实模型清单（失败静默：下拉留空，用户仍可点「创建」）
  useEffect(() => {
    let alive = true
    api<Model[]>('/models')
      .then((list) => { if (alive) setModels(list.filter((m) => m.enabled)) })
      .catch(() => { /* 静默：模型列表拿不到不影响创建 */ })
    return () => { alive = false }
  }, [])

  /** 向量库实例清单（`GET /api/v1/vector-stores`）：拿不到就只显示「系统默认」。 */
  useEffect(() => {
    let alive = true
    knowledgeApi.listVectorStores()
      .then((list) => { if (alive) setVectorStores(list || []) })
      .catch(() => { /* 静默：等价于不绑定，后端回落环境变量存储 */ })
    return () => { alive = false }
  }, [])

  /** 存储后端实例清单（`GET /api/v1/storage-backends`）：空值 = 租户默认。 */
  useEffect(() => {
    let alive = true
    knowledgeApi.listStorageBackends()
      .then((list) => {
        if (!alive) return
        // 源站只列 active 实例（`KBStorageSettings.vue` 的 filter）；缺失状态视为可用。
        setStorageBackends((list || []).filter((b) => !b.status || b.status === 'active'))
      })
      .catch(() => { /* 静默：只显示「跟随默认存储」 */ })
    return () => { alive = false }
  }, [])

  /** 解析引擎清单（`GET /api/v1/system/parser-engines` · 后端注册表驱动）。 */
  const [parserEngines, setParserEngines] = useState<ParserEngineInfo[]>([])
  /** 清单加载失败原因（非空 ⇒ 依赖它的组全部退化成「无可用引擎」）。 */
  const [parserErr, setParserErr] = useState('')
  /** 用户在本弹窗里选过的引擎：key = 分组 key，value = 引擎 `Name`。 */
  const [engineRules, setEngineRules] = useState<Record<string, string>>({})

  useEffect(() => {
    let alive = true
    knowledgeApi.listParserEngines()
      .then((list) => { if (alive) setParserEngines(list) })
      .catch((e) => {
        if (alive) setParserErr(e instanceof Error ? e.message : String(e))
      })
    return () => { alive = false }
  }, [])

  const [submitting, setSubmitting] = useState(false)

  const submit = async () => {
    if (!name.trim()) {
      showToast('请填写知识库名称', 'info')
      return
    }
    // 源站 `validateForm` 同款判据：开了多模态就必须选 VLLM 模型。
    if (vlm && !vlmModelId) {
      showToast('请选择 VLLM 视觉模型（必选）', 'info')
      setTab('image')
      return
    }
    setSubmitting(true)
    try {
      /**
       * 解析引擎规则（`chunking_config.parser_engine_rules`）。
       *
       * <p>源站格式 `[{file_types:[...], engine:"..."}]`；本仓后端
       * `KbChunkingConfigParser#parserEngineRulesOf` 按**声明顺序**取第一条命中的规则。
       * ⚠️ 只写「用户显式选过、或本仓能算出默认」的组 —— 空值组不落规则，
       * 让后端继续走 `@Order` 默认路由（写空 engine 的规则反而会命中后回落）。
       */
      const parserEngineRules = type === 'faq'
        ? []
        : parserGroupsWithDynamic(parserEngines).flatMap((g) => {
            const selected = engineRules[g.key]
              ?? defaultEngineForGroup(enginesForExtensions(parserEngines, g.extensions), g.extensions)
              ?? ''
            return selected ? [{ file_types: g.extensions, engine: selected }] : []
          })

      await onCreate({
        name: name.trim(),
        description: description.trim(),
        type,
        embeddingModelId: null,
        chunkSize,
        chunkOverlap,
        // 源站创建时同一个请求就带完整分块配置（`KnowledgeBaseEditorModal.vue` 的 chunking_config）。
        // ⛔ 少传这个字段 = 用户的滑块选择被静默丢弃（后端回落 DB 默认 512/80）。
        chunkingConfig: type === 'faq'
          ? null
          : JSON.stringify({
              chunk_size: chunkSize,
              chunk_overlap: chunkOverlap,
              // 后端两个键都认（`split_markers` 优先、`separators` 兜底）；沿用本仓既有键。
              split_markers: chunk.separators,
              strategy: chunk.strategy,
              enable_parent_child: chunk.enableParentChild,
              parent_chunk_size: chunk.parentChunkSize,
              child_chunk_size: chunk.childChunkSize,
              token_limit: chunk.tokenLimit,
              languages: chunk.languages,
              table_metadata_instructions: tableMetadataInstructions,
              // 空数组不写：后端对无规则的类型走默认路由。
              ...(parserEngineRules.length ? { parser_engine_rules: parserEngineRules } : {}),
            }),
        // ⭐ 只有文档型才有索引策略（源码 `:43` 是 `v-if="!isFAQ"`）。
        //    FAQ 库没有分块/向量/Wiki 这条流水线 ⇒ 传 null，⛔ 不写空对象。
        indexingStrategy: type === 'faq'
          ? null
          : {
              // RAG 勾选 = vector + keyword 同时开（源码 `:1306-1308` 两个字段一起翻）
              vector_enabled: rag,
              keyword_enabled: rag,
              wiki_enabled: wiki,
              graph_enabled: graph,
            },
        // ⭐ 图谱判据的另一半（`extract_config.enabled`）必须同时写，
        //    只写 graph_enabled 的话后端 `graphEnabled()` 的合取恒 false ⇒ 静默不抽。
        //    键名照源站 `buildSubmitData` 的 extract_config（`enabled` / `text` / `tags` /
        //    `nodes` / `relations` / `custom_instructions`）。
        //    FAQ 库没有图谱流水线 ⇒ null，⛔ 不写空对象。
        extract_config: type === 'faq'
          ? null
          : JSON.stringify({
              enabled: graph,
              text: graphText,
              tags: graphTags,
              nodes: graphNodes,
              relations: [],
              custom_instructions: graphInstructions,
            }),
        // ⭐ Wiki 开关只由 indexing_strategy.wiki_enabled 承载；这里只放 Wiki 可调项。
        //    键名照源站 `buildSubmitData`（`:1677-1686`）。
        wikiConfig: type === 'faq'
          ? null
          : JSON.stringify({
              auto_generate: wikiCfg.autoGenerate,
              synthesis_model_id: wikiModel,
              max_pages_per_ingest: 0,
              extraction_granularity: wikiCfg.granularity,
              content_instructions: wikiCfg.contentInstructions,
              extraction_instructions: wikiCfg.extractionInstructions,
            }),
        // 图片处理：本仓后端从这段 JSON 读 `enable_multimodal` + `model_id`
        // （`KbPostProcessConfig#imageUnderstanding`）。其余三个键一并落库。
        // ⚠️ 图片管线方案走**顶层** image_pipeline，⛔ 不写进这段 JSON 的旧键。
        imageProcessingConfig: type === 'faq'
          ? null
          : JSON.stringify({
              enable_multimodal: vlm,
              model_id: vlm ? vlmModelId : '',
              description_language: imageDescLanguage,
              custom_instructions: imageInstructions,
              enable_image_vector: imageVector,
            }),
        asrConfig: JSON.stringify({
          enabled: asr,
          model_id: asr ? asrModelId : '',
          language: '',
        }),
        questionGenerationConfig: JSON.stringify({
          model_id: qgenModelId,
          enabled: qgen,
          question_count: qgenCount,
          custom_instructions: qgenInstructions,
        }),
        autoTagConfig: JSON.stringify({
          enabled: autotag,
          model_id: autotagModelId,
          max_tags: autotagMaxTags,
          skip_if_tagged: autotagSkipIfTagged,
        }),
        profileConfig: JSON.stringify({
          enabled: profileEnabled,
          model_id: profileModelId,
          custom_instructions: profileInstructions,
        }),
        faqConfig: type === 'faq'
          ? JSON.stringify({
              index_mode: faqIndexMode,
              question_index_mode: faqQuestionIndexMode,
            })
          : null,
        // 源站只在用户选了非默认实例时才带这两个 id（空 = 后端用默认）。
        vectorStoreId: vectorStoreId || null,
        storageBackendId: storageBackendId || null,
      })
    } finally {
      setSubmitting(false)
    }
  }

  /** 导航分组（源侧 navGroups 的三组形态）。 */
  /**
   * 导航分组 —— **按知识库类型切换**（用户 2026-11-08 明确规格）：
   *
   *   · 文档型（document）：基础 3 项 + 索引与解析 6 项 + 存储与数据 1 项 = 10 项
   *   · 问答型（faq）    ：基础 3 项 + FAQ 设置 1 项 = 4 项
   *     （问答库没有文档解析/分块/图谱/存储这一整套流水线 ⇒ 那两组不显示）
   *
   * ⚠️ 设计稿 01 页的 `#create-modal` 只画了文档型的 10 项；
   * 「问答型 4 项」是用户给出的规格（截图），按此实现。
   */
  type TabKey =
    | 'basic' | 'model' | 'vector'
    | 'parser' | 'chunk' | 'image' | 'audio' | 'graph' | 'advanced'
    | 'storage' | 'faq'
  const NAV_GROUPS_DOC: Array<{ label: string; items: Array<{ key: TabKey; label: string }> }> = [
    {
      label: '基础',
      items: [
        { key: 'basic', label: '基本信息' },
        { key: 'model', label: '模型配置' },
        { key: 'vector', label: '向量存储' },
      ],
    },
    {
      label: '索引与解析',
      items: [
        { key: 'parser', label: '解析引擎' },
        { key: 'chunk', label: '分块设置' },
        { key: 'image', label: '图像处理' },
        { key: 'audio', label: '音频处理' },
        { key: 'graph', label: '知识图谱' },
        { key: 'advanced', label: '高级设置' },
      ],
    },
    { label: '存储与数据', items: [{ key: 'storage', label: '存储引擎' }] },
  ]
  const NAV_GROUPS_FAQ: Array<{ label: string; items: Array<{ key: TabKey; label: string }> }> = [
    {
      label: '基础',
      items: [
        { key: 'basic', label: '基本信息' },
        { key: 'model', label: '模型配置' },
        { key: 'vector', label: '向量存储' },
      ],
    },
    { label: '问答', items: [{ key: 'faq', label: 'FAQ 设置' }] },
  ]
  const NAV_GROUPS = type === 'faq' ? NAV_GROUPS_FAQ : NAV_GROUPS_DOC

  return (
    /* 源侧 SettingsModalShell.vue:4 —— `.settings-modal-shell.settings-overlay` 两个类缺一不可；
       `:style="{ zIndex }"` 同上（KnowledgeBaseEditorModal 传 1000） */
    <div className="settings-modal-shell settings-overlay" style={{ zIndex: 1000 }}>
      {/* tdesign 图标 sprite（8 个文件类型 symbol）—— 解析引擎节用 <use href="#t-icon-*"> 引用。
          `<use>` 是全局 id 查找，sprite 不必是使用方的祖先，挂在这里即对整个文档生效。 */}
      <IconSprite />
      <div className="settings-modal" role="dialog" aria-modal="true" aria-label={initial ? '编辑知识库' : '新建知识库'}>
        <button type="button" className="close-btn" onClick={onClose} aria-label="关闭">
          <svg width="20" height="20" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
            <path d="M15 5L5 15M5 5L15 15" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
          </svg>
        </button>

        <div className="settings-container">
          <aside className="settings-sidebar">
            <div className="sidebar-header">
              <h2 className="sidebar-title">{initial ? '编辑知识库' : '新建知识库'}</h2>
            </div>
            <nav className="settings-nav">
              {NAV_GROUPS.map((g) => (
                <Fragment key={g.label}>
                  <div className="nav-group-title">{g.label}</div>
                  {g.items.map((it) => (
                    <div
                      key={it.key}
                      className={`nav-item${tab === it.key ? ' active' : ''}`}
                      role="button"
                      tabIndex={0}
                      onClick={() => setTab(it.key)}
                      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') setTab(it.key) }}
                      aria-current={tab === it.key ? 'page' : undefined}
                    >
                      <span className="nav-label">{it.label}</span>
                    </div>
                  ))}
                </Fragment>
              ))}
            </nav>
          </aside>

          {/* .settings-content > .settings-body > .content-wrapper 三层，少一层就没内边距 */}
          <div className="settings-content">
            <div className="settings-body">
              <div className="content-wrapper">
                {tab === 'basic' && (
                  <div className="section">
                    <div className="section-content">
                      {/* ⚠️ kb-shared.css:717 把源站只有 .kb-chunking-settings 用的 sticky 页头
                          提升成了全局 .kb-scope .section-header ⇒ 行内复位成源站普通块级流
                          （源站 `.section-content .section-header{margin-bottom:16px}`） */}
                      <div className="section-header" style={SECTION_HEADER_FLAT}>
                        <h3 className="section-title">基本信息</h3>
                        <p className="section-desc">设置知识库的名称和描述信息</p>
                      </div>
                      <div className="section-body">
                        <div className="form-item">
                          <label className="form-label">知识库类型 <span className="required">*</span></label>
                          <div className="t-radio-group t-radio-group__outline t-size-m">
                            <label className={`t-radio-button${type === 'document' ? ' t-is-checked' : ''}`}>
                              <input
                                type="radio"
                                className="t-radio-button__former"
                                name="kbtype"
                                checked={type === 'document'}
                                onChange={() => { setType('document'); setTab('basic') }}
                              />
                              <span className="t-radio-button__label">文档</span>
                            </label>
                            <label className={`t-radio-button${type === 'faq' ? ' t-is-checked' : ''}`}>
                              <input
                                type="radio"
                                className="t-radio-button__former"
                                name="kbtype"
                                checked={type === 'faq'}
                                onChange={() => { setType('faq'); setTab('basic') }}
                              />
                              <span className="t-radio-button__label">问答</span>
                            </label>
                          </div>
                          <p className="form-tip">
                            FAQ 类型适合结构化问答数据；文档型支持文件解析与分块。
                          </p>
                        </div>

                        {/* ⭐ 索引策略只在**文档型**出现 —— 源码 `:43` 是 `v-if="!isFAQ"`。
                            FAQ 库没有分块/向量/Wiki 这条流水线，露出来就是三个点了没意义的开关。 */}
                        {type !== 'faq' && (
                        <div className="form-item">
                          <label className="form-label">索引策略 <span className="required">*</span></label>
                          <p className="form-tip">配置文档上传后的处理管道，可以独立开启或关闭每种索引方式</p>
                          <div className="indexing-checks">
                            {/* ⭐ 点击必须落在**整张卡片**上（源码 :48-52 / :60-64 都是
                                `<div class="indexing-check-item" @click="toggle...">`）。
                                ⚠️ kb-shared.css 里 `.indexing-check-box{pointer-events:none}`
                                是源站原样 —— 源站故意让 label 不接收事件，靠外层 div 的
                                @click 切换。本仓原来只挂了 input 的 onChange ⇒
                                事件被 pointer-events:none 挡住 ⇒ **两个复选框都点不动**。 */}
                            <div
                              className={`indexing-check-item${rag ? ' is-checked' : ''}`}
                              onClick={() => setRag((v) => !v)}
                              role="checkbox"
                              aria-checked={rag}
                              tabIndex={0}
                              onKeyDown={(e) => {
                                if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setRag((v) => !v) }
                              }}
                            >
                              <label className={`t-checkbox indexing-check-box${rag ? ' t-is-checked' : ''}`}>
                                <input
                                  type="checkbox"
                                  className="t-checkbox__former"
                                  checked={rag}
                                  onChange={(e) => setRag(e.target.checked)}
                                  tabIndex={-1}
                                />
                                <span className="t-checkbox__input" />
                                <span className="t-checkbox__label">RAG 检索</span>
                              </label>
                              <p className="indexing-check-desc">对文档进行分块、向量化和关键词索引，支持混合检索</p>
                            </div>
                            {/* ⭐ Wiki 知识库与 RAG 是**并列的两个开关**，都可勾
                                （源码 KnowledgeBaseEditorModal.vue:60-75 两个 t-checkbox 都可点）。
                                ⚠️ 之前这里是 `is-disabled` + 灰置 + 「创建后可在 Wiki 页开启」——
                                后端 create 已支持 indexing_strategy（本批补的），灰置说明不再成立。 */}
                            <div
                              className={`indexing-check-item${wiki ? ' is-checked' : ''}`}
                              onClick={() => setWiki((v) => !v)}
                              role="checkbox"
                              aria-checked={wiki}
                              tabIndex={0}
                              onKeyDown={(e) => {
                                if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setWiki((v) => !v) }
                              }}
                            >
                              <label className={`t-checkbox indexing-check-box${wiki ? ' t-is-checked' : ''}`}>
                                <input
                                  type="checkbox"
                                  className="t-checkbox__former"
                                  checked={wiki}
                                  onChange={(e) => setWiki(e.target.checked)}
                                  tabIndex={-1}
                                />
                                <span className="t-checkbox__input" />
                                <span className="t-checkbox__label">
                                  <span className="indexing-check-title">
                                    Wiki 知识库
                                    <span className="indexing-new-badge">NEW</span>
                                  </span>
                                </span>
                              </label>
                              <p className="indexing-check-desc">自动生成互相关联的 Wiki 知识页面，构建结构化知识体系</p>
                            </div>
                            {/* ⭐ 第三张卡：知识图谱。与上面两张**完全同款** DOM
                                （indexing-check-item / t-checkbox / indexing-check-title / indexing-check-desc），
                                不加 NEW 徽标（不是新能力，只是之前没有写入入口）。
                                ⚠️ 开关与「知识图谱」tab 共用同一个 state —— 两处必须联动。 */}
                            <div
                              className={`indexing-check-item${graph ? ' is-checked' : ''}`}
                              onClick={() => setGraph((v) => !v)}
                              role="checkbox"
                              aria-checked={graph}
                              tabIndex={0}
                              onKeyDown={(e) => {
                                if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setGraph((v) => !v) }
                              }}
                            >
                              <label className={`t-checkbox indexing-check-box${graph ? ' t-is-checked' : ''}`}>
                                <input
                                  type="checkbox"
                                  className="t-checkbox__former"
                                  checked={graph}
                                  onChange={(e) => setGraph(e.target.checked)}
                                  tabIndex={-1}
                                />
                                <span className="t-checkbox__input" />
                                <span className="t-checkbox__label">
                                  <span className="indexing-check-title">知识图谱</span>
                                </span>
                              </label>
                              <p className="indexing-check-desc">用 LLM 从文档中抽取实体与关系，构建可检索的知识图谱</p>
                            </div>
                          </div>
                        </div>
                        )}

                        {/* ⭐ Wiki 四项只在勾了「Wiki 知识库」时出现 —— 源码
                            `KnowledgeBaseEditorModal.vue:84 / :91 / :112 / :130` 的
                            `v-if="!isFAQ && formData.indexingStrategy.wikiEnabled"`。
                            ⚠️ 这部分是 `wiki_config` 的可调项；Wiki 的**开关**仍是
                            `indexing_strategy.wiki_enabled`。 */}
                        {type !== 'faq' && wiki && (
                          <>
                            <div className="form-item">
                              <label className="form-label">自动生成 Wiki 页面</label>
                              <p className="form-tip">关闭后仍可浏览和手工维护 Wiki 页面，但文档处理不会自动调用 LLM 生成或更新页面</p>
                              <Switch
                                checked={wikiCfg.autoGenerate}
                                label="自动生成 Wiki 页面"
                                onChange={(v) => setWikiCfg((c) => ({ ...c, autoGenerate: v }))}
                              />
                            </div>

                            <div className="form-item">
                              <label className="form-label">提取粒度</label>
                              <p className="form-tip">控制每篇文档抽取的 Wiki 实体/概念数量。粒度越细，索引越聚焦；粒度越粗，索引越详尽</p>
                              <div className="t-radio-group t-radio-group__outline t-size-m">
                                {WIKI_GRANULARITY_OPTIONS.map((o) => (
                                  <label
                                    key={o.value}
                                    className={`t-radio-button${wikiCfg.granularity === o.value ? ' t-is-checked' : ''}`}
                                  >
                                    <input
                                      type="radio"
                                      className="t-radio-button__former"
                                      name="wiki-granularity"
                                      checked={wikiCfg.granularity === o.value}
                                      onChange={() => setWikiCfg((c) => ({ ...c, granularity: o.value }))}
                                    />
                                    <span className="t-radio-button__label">{o.label}</span>
                                  </label>
                                ))}
                              </div>
                              <p className="form-tip">
                                {WIKI_GRANULARITY_OPTIONS.find((o) => o.value === wikiCfg.granularity)?.hint}
                              </p>
                            </div>

                            <div className="form-item">
                              <label className="form-label">Wiki 内容生成要求</label>
                              <p className="form-tip">控制摘要、页面和首页的表达重点；引用、合并与防幻觉规则由系统固定维护。修改后需重新解析才能影响已有内容。</p>
                              <div className="t-textarea">
                                <textarea
                                  className="t-textarea__inner"
                                  rows={3}
                                  maxLength={4000}
                                  placeholder="例如：使用法务审阅口吻，优先展示责任主体、时间线和风险提示…"
                                  value={wikiCfg.contentInstructions}
                                  onChange={(e) => setWikiCfg((c) => ({ ...c, contentInstructions: e.target.value }))}
                                />
                              </div>
                            </div>

                            <div className="form-item">
                              <label className="form-label">Wiki 提取重点</label>
                              <p className="form-tip">说明应重点识别的领域实体和概念，不会替换系统的 JSON 与引用协议。</p>
                              <div className="t-textarea">
                                <textarea
                                  className="t-textarea__inner"
                                  rows={3}
                                  maxLength={4000}
                                  placeholder="例如：重点识别产品、版本、组织、负责人和关键技术概念…"
                                  value={wikiCfg.extractionInstructions}
                                  onChange={(e) => setWikiCfg((c) => ({ ...c, extractionInstructions: e.target.value }))}
                                />
                              </div>
                            </div>
                          </>
                        )}

                        <div className="form-item">
                          <label className="form-label">知识库名称 <span className="required">*</span></label>
                          <div className="t-input__wrap">
                            <div className="t-input">
                              <input
                                className="t-input__inner"
                                placeholder="请输入知识库名称"
                                value={name}
                                onChange={(e) => setName(e.target.value)}
                                autoFocus
                                onKeyDown={(e) => { if (e.key === 'Enter') void submit() }}
                              />
                            </div>
                          </div>
                        </div>

                        <div className="form-item">
                          <label className="form-label">知识库描述</label>
                          <div className="t-textarea">
                            <textarea
                              className="t-textarea__inner"
                              placeholder="请输入知识库描述（可选）"
                              value={description}
                              maxLength={200}
                              onChange={(e) => setDescription(e.target.value)}
                            />
                            <div className="t-textarea__info_wrapper">
                              <span className="t-textarea__limit">{description.length}/200</span>
                            </div>
                          </div>
                        </div>

                        {/* 知识库画像（源站 basic 节末尾的 profile 块）。
                            ⚠️ 源站这个卡片是 `v-if="editorMode === 'edit' && !isFAQ"` ——
                            它的「生成」按钮走 `POST /knowledge-bases/{id}/profile/generate`，
                            需要**已存在的知识库 ID + 已解析文档**；创建态没有 id，点了必然失败。
                            ⇒ 这里只保留说明卡片，⛔ 不画一个创建时点不动的按钮。 */}
                        <div className="form-item">
                          <label className="form-label">AI 生成描述</label>
                          <div className="kb-profile-card is-empty">
                            <p className="kb-profile-empty">
                              创建并上传文档、生成摘要后，可在该知识库的 ⚙ 设置 → 基本信息中生成。
                            </p>
                          </div>
                          <p className="form-tip">由文档画像聚合生成，不会覆盖上方手写描述；智能体会同时读取两者来判断问题是否该在本知识库中检索。</p>
                        </div>
                      </div>
                    </div>
                  </div>
                )}

                {tab === 'model' && (
                  /* ⭐ 逐字对齐设计稿 01 页 `data-navpanel="model"`：
                     三行都是 `<select>`（⛔ 不是输入框），顺序 = 摘要模型 / Embedding / Wiki 合成。
                     选项来自真实 `GET /api/v1/models`（按 type 过滤），空则只有「跟随全局默认」。 */
                  <div className="section">
                    <div className="section-header" style={{ ...SECTION_HEADER_FLAT, margin: '0 0 20px' }}>
                      <h2>模型配置</h2>
                      <p className="section-description">为知识库选择合适的 AI 模型</p>
                    </div>
                    <div className="settings-group">
                      <div className="setting-row">
                        <div className="setting-info">
                          <label><span className="required">*</span>摘要模型</label>
                          <p className="desc">用于总结和摘要的大语言模型</p>
                        </div>
                        <div className="setting-control">
                          <select
                            className="t-input t-input--suffix"
                            aria-label="摘要模型"
                            value={summaryModel}
                            onChange={(e) => setSummaryModel(e.target.value)}
                          >
                            <option value="">跟随全局默认</option>
                            {models
                              .filter((m) => m.type === 'chat' || m.type === 'multimodal')
                              .map((m) => (
                                <option key={m.id} value={m.name}>{m.name}</option>
                              ))}
                          </select>
                        </div>
                      </div>
                      {/* ⭐ Embedding 行只在「开了 RAG 或 Wiki」时显示 —— 源码
                          KBModelConfig.vue:29 `v-if="ragEnabled !== false || wikiEnabled"`。
                          两个都不开时 embedding 毫无用处（既不建向量索引、也不建 Wiki），
                          露出来只是一个必填却不能生效的字段。 */}
                      {(rag || wiki) && (
                      <div className="setting-row">
                        <div className="setting-info">
                          {/* ⭐ 必填/可选按能力走（源码 :33-34）：
                              RAG 要向量检索 ⇒ 必填；**只开 Wiki** ⇒ 可选。 */}
                          <label>
                            {rag ? <span className="required">*</span> : null}
                            Embedding 模型
                            {!rag && wiki ? (
                              <span style={{ color: 'var(--td-text-color-placeholder)', fontWeight: 400 }}>（可选）</span>
                            ) : null}
                          </label>
                          <p className="desc">用于文本向量化的嵌入模型</p>
                        </div>
                        <div className="setting-control">
                          <select
                            className="t-input t-input--suffix"
                            aria-label="Embedding 模型"
                            value={embeddingModel}
                            onChange={(e) => setEmbeddingModel(e.target.value)}
                          >
                            <option value="">自动选用（第一个启用的 embedding）</option>
                            {models
                              .filter((m) => m.type === 'embedding')
                              .map((m) => (
                                <option key={m.id} value={m.name}>{m.name}</option>
                              ))}
                          </select>
                        </div>
                      </div>
                      )}
                      {/* ⭐ Wiki 合成模型只在勾了「Wiki 知识库」时显示 —— 源码
                          KBModelConfig.vue:62 `v-if="wikiEnabled"`。
                          ⚠️ 之前这一行是常显的：没开 Wiki 的用户会看到一个
                          「不设置时将回退使用摘要模型」的字段，但它压根不会被用到。 */}
                      {wiki && (
                      <div className="setting-row">
                        <div className="setting-info">
                          <label>Wiki 合成模型</label>
                          <p className="desc">不设置时将回退使用摘要模型</p>
                        </div>
                        <div className="setting-control">
                          <select
                            className="t-input t-input--suffix"
                            aria-label="Wiki 合成模型"
                            value={wikiModel}
                            onChange={(e) => setWikiModel(e.target.value)}
                          >
                            <option value="">跟随摘要模型</option>
                            {models
                              .filter((m) => m.type === 'chat' || m.type === 'multimodal')
                              .map((m) => (
                                <option key={m.id} value={m.name}>{m.name}</option>
                              ))}
                          </select>
                        </div>
                      </div>
                      )}
                    </div>
                  </div>
                )}

                {tab === 'chunk' && (
                  /* 与「知识库设置」弹窗同一组控件与常量（Row / Slider / TagInput / Switch），
                     ⛔ 不复制一份实现。9 项逐条对齐源站 `KBChunkingSettings.vue`。 */
                  <div className="section">
                    <div className="kb-chunking-settings">
                      <div className="section-header">
                        <h2>分块设置</h2>
                        <p className="section-description">控制文档切分粒度，影响检索召回精度</p>
                      </div>
                      <div className="settings-group">
                        <Row label="分块策略" desc="选择文档的分块方式。自动模式会分析每个文档的结构并选择最佳策略。">
                          <div className="t-select__wrap" style={{ width: 280 }}>
                            <select
                              className="t-input__inner"
                              style={{ width: '100%', height: 32 }}
                              value={chunk.strategy}
                              onChange={(e) => setChunk((c) => ({ ...c, strategy: e.target.value }))}
                            >
                              <option value="">选择分块策略（不填则按长度切分）</option>
                              {CHUNK_STRATEGIES.map((o) => (
                                <option key={o.value} value={o.value}>{o.label}</option>
                              ))}
                            </select>
                          </div>
                        </Row>
                        <Row label="分块大小" desc="每个分块的最大字符数（100-4000）。默认 512 ≈ 中文 300 tokens / 英文 100-130 tokens。">
                          <Slider
                            min={100}
                            max={4000}
                            step={1}
                            value={chunkSize}
                            marks={CHUNK_SIZE_MARKS}
                            unit="字符"
                            onChange={setChunkSize}
                          />
                        </Row>
                        <Row label="分块重叠" desc="相邻分块之间共享的字符数（0-500）。默认 80 ≈ 分块大小的 15%。">
                          <Slider
                            min={0}
                            max={500}
                            step={1}
                            value={chunkOverlap}
                            marks={CHUNK_OVERLAP_MARKS}
                            unit="字符"
                            onChange={setChunkOverlap}
                          />
                        </Row>
                        <Row label="分隔符" desc="切分时优先使用的字符或字符串。优先级高的分隔符先尝试；默认顺序优先段落 → 句子 → 标点。">
                          <TagInput
                            value={chunk.separators}
                            options={SEPARATOR_OPTIONS}
                            placeholder="选择或自定义分隔符"
                            onChange={(v) => setChunk((c) => ({ ...c, separators: v }))}
                          />
                        </Row>
                        <Row
                          label="父子分块"
                          desc="两级分块：小的子块用于向量匹配（精准命中），大的父块返回给 LLM（更丰富上下文）。建议用于长文档（>10 页）。"
                        >
                          <Switch
                            checked={chunk.enableParentChild}
                            label="父子分块"
                            onChange={(v) => setChunk((c) => ({ ...c, enableParentChild: v }))}
                          />
                        </Row>
                        <Row label="父块大小" desc="返回给 LLM 的上下文块大小（512-8192）。默认 4096。">
                          <Slider
                            min={512}
                            max={8192}
                            step={1}
                            value={chunk.parentChunkSize}
                            marks={PARENT_CHUNK_SIZE_MARKS}
                            unit="字符"
                            disabled={!chunk.enableParentChild}
                            onChange={(v) => setChunk((c) => ({ ...c, parentChunkSize: v }))}
                          />
                        </Row>
                        <Row label="子块大小" desc="用于向量匹配的嵌入块大小（64-2048）。默认 384。">
                          <Slider
                            min={64}
                            max={2048}
                            step={1}
                            value={chunk.childChunkSize}
                            marks={CHILD_CHUNK_SIZE_MARKS}
                            unit="字符"
                            disabled={!chunk.enableParentChild}
                            onChange={(v) => setChunk((c) => ({ ...c, childChunkSize: v }))}
                          />
                        </Row>
                        <Row label="每块 Token 上限" desc="每个分块的硬性 Token 上限（0-8192）。0 = 关闭（仅按字符数）。">
                          <div className="t-input-number t-size-m t-input-number--normal">
                            <input
                              type="number"
                              className="t-input__inner"
                              style={{ width: 120, height: 32 }}
                              min={0}
                              max={8192}
                              value={chunk.tokenLimit}
                              onChange={(e) => setChunk((c) => ({ ...c, tokenLimit: Number(e.target.value) || 0 }))}
                            />
                          </div>
                        </Row>
                        <Row label="语言提示" desc="限制启发式模式只识别选定的语言。留空 = 自动检测。">
                          <TagInput
                            value={chunk.languages}
                            options={LANGUAGE_OPTIONS}
                            placeholder="自动检测"
                            onChange={(v) => setChunk((c) => ({ ...c, languages: v }))}
                          />
                        </Row>
                      </div>
                    </div>
                  </div>
                )}

                {/* ── 以下各节逐字对齐源站 `KnowledgeBaseEditorModal` 的 data-navpanel ──
                    创建接口（`CreateKnowledgeBaseRequest`）本批已开放：wiki_config /
                    image_processing_config / asr_config / question_generation_config /
                    auto_tag_config / profile_config / faq_config / vector_store_id /
                    storage_backend_id ⇒ 每节控件都真写进请求体，⛔ 不再有「创建后配置」占位。 */}

                {tab === 'faq' && (
                  /* FAQ 型专属 —— 源站 `KnowledgeBaseEditorModal.vue:278-310`：
                     两组必选单选（索引方式 / 问题索引方式），提交进 `faq_config`。
                     ⚠️ 本仓原来这里是三个自造的开关（相似问法/否定问法/答案直接返回），
                     源站没有对应字段 ⇒ 已按源站换成真实可写入的两组单选。 */
                  <div className="section">
                    <div className="section-header" style={{ ...SECTION_HEADER_FLAT, margin: '0 0 20px' }}>
                      <h2>问答</h2>
                      <p className="section-description">设置 FAQ 知识库的索引策略和问答组织方式</p>
                    </div>
                    <div className="section-body">
                      <div className="form-item">
                        <label className="form-label">索引方式 <span className="required">*</span></label>
                        <div className="t-radio-group t-radio-group__outline t-size-m">
                          {([
                            { value: 'question_only', label: '仅标准问/相似问' },
                            { value: 'question_answer', label: '标准问 + 答案' },
                          ] as const).map((o) => (
                            <label
                              key={o.value}
                              className={`t-radio-button${faqIndexMode === o.value ? ' t-is-checked' : ''}`}
                            >
                              <input
                                type="radio"
                                className="t-radio-button__former"
                                name="faq-index-mode"
                                checked={faqIndexMode === o.value}
                                onChange={() => setFaqIndexMode(o.value)}
                              />
                              <span className="t-radio-button__label">{o.label}</span>
                            </label>
                          ))}
                        </div>
                        <p className="form-tip">仅索引问题可提升精度，索引问答可提高召回率</p>
                      </div>

                      <div className="form-item">
                        <label className="form-label">问题索引方式 <span className="required">*</span></label>
                        <div className="t-radio-group t-radio-group__outline t-size-m">
                          {([
                            { value: 'combined', label: '合并索引' },
                            { value: 'separate', label: '分别索引' },
                          ] as const).map((o) => (
                            /* ⭐ `separate`（分别索引）后端**零消费者** —— 与「知识库设置」弹窗
                               同一事实（那边已标注「本仓暂不支持分别索引」）。
                               ⛔ 创建态不能让它可选：选了也拿不到该行为，两处还会自相矛盾。 */
                            <label
                              key={o.value}
                              className={`t-radio-button${faqQuestionIndexMode === o.value ? ' t-is-checked' : ''}${o.value === 'separate' ? ' t-is-disabled' : ''}`}
                              onClick={o.value === 'separate'
                                ? (e) => {
                                    e.preventDefault()
                                    showToast('问题索引方式：本仓后端尚未实现「分别索引」，当前固定按合并索引处理', 'info')
                                  }
                                : undefined}
                            >
                              <input
                                type="radio"
                                className="t-radio-button__former"
                                name="faq-question-index-mode"
                                checked={faqQuestionIndexMode === o.value}
                                disabled={o.value === 'separate'}
                                onChange={() => setFaqQuestionIndexMode(o.value)}
                              />
                              <span className="t-radio-button__label">{o.label}</span>
                            </label>
                          ))}
                        </div>
                        <p className="form-tip">
                          合并索引：标准问和相似问合并索引；分别索引：标准问和每个相似问独立索引，检索更精确但需要更多存储
                        </p>
                        {/* 与知识库设置弹窗逐字一致：如实告知后端未实现，别让用户以为选了就生效 */}
                        <p className="form-tip" style={{ color: 'var(--td-warning-color, #e37318)' }}>
                          ⚠️ 本仓暂不支持「分别索引」—— 该项仅展示，实际固定按合并索引处理。
                        </p>
                      </div>

                      <p className="form-tip">FAQ 条目由标准问、相似问、反例和多个答案组成，可在知识库详情中批量导入、编辑。</p>
                    </div>
                  </div>
                )}

                {tab === 'vector' && (
                  /* 源站 `KBVectorStoreSettings.vue` create 态：一行下拉，
                     「系统默认」（空值）+ 用户实例。 */
                  <div className="section">
                    <div className="section-header" style={{ ...SECTION_HEADER_FLAT, margin: '0 0 20px' }}>
                      <h2>向量存储</h2>
                      <p className="section-description">选择此知识库要写入的向量存储。绑定不可更改 — 如需将现有 KB 迁移到其他存储，请创建新 KB 并重新索引。</p>
                    </div>
                    <div className="settings-group">
                      <div className="setting-row">
                        <div className="setting-info">
                          <label>向量存储</label>
                          <p className="desc">从全局向量存储配置中选择，或保持系统默认以使用空间的默认检索引擎。</p>
                        </div>
                        <div className="setting-control">
                          <div className="t-select__wrap" style={{ width: 280 }}>
                            <select
                              className="t-input__inner"
                              style={{ width: '100%', height: 32 }}
                              aria-label="向量存储"
                              value={vectorStoreId}
                              onChange={(e) => setVectorStoreId(e.target.value)}
                            >
                              <option value="">系统默认</option>
                              {vectorStores.map((s) => (
                                <option key={s.id} value={s.id}>
                                  {s.name}{s.engine_type ? `（${s.engine_type}）` : ''}
                                </option>
                              ))}
                            </select>
                          </div>
                          <p className="ext-tag" style={{ marginTop: 8 }}>创建后不可更改。如需迁移，请创建一个绑定到目标存储的新 KB 并重新索引。</p>
                        </div>
                      </div>
                    </div>
                  </div>
                )}

                {tab === 'parser' && (
                  /* 源站 KBParserSettings.vue（非 embedded 形态）：
                     .kb-parser-settings > .section-header + .settings-group > .setting-row × N
                     分组与图标前端硬编码（PARSER_GROUPS），下拉选项来自后端注册表。 */
                  <div className="section">
                    <div className="section-header" style={{ ...SECTION_HEADER_FLAT, margin: '0 0 20px' }}>
                      <h2>解析引擎</h2>
                      <p className="section-description">为不同文件类型选择文档解析引擎。未配置的文件类型将使用内置解析引擎。</p>
                      {parserErr && (
                        <p className="desc" style={{ color: 'var(--td-error-color, #d54941)' }}>
                          解析引擎清单加载失败：{parserErr}
                        </p>
                      )}
                    </div>
                    <div className="kb-parser-settings">
                      <div className="settings-group">
                        {/* 固定 12 组 + 动态扩展名组（源站 `KBParserSettings.vue:173-179`）：
                            后端注册表出现未归类的扩展名时自动追加一行，前端不发版。 */}
                        {parserGroupsWithDynamic(parserEngines).map((g) => {
                          const options = engineOptionsForGroup(parserEngines, g.extensions)
                          // 选中值：用户选过 → 用他的；否则回落到「默认引擎」（源站 getDefaultEngine 口径：
                          // 在**可用**候选里找被标 (默认) 的那个）；两者皆空时给空串，
                          // 但**只有 options 非空才渲染 select** —— 实测 Chrome 对不在 options 里的
                          // value 会置 selectedIndex=-1（显示空白），所以不能靠 select 表达「没有」。
                          const fallback = defaultEngineForGroup(
                            enginesForExtensions(parserEngines, g.extensions),
                            g.extensions
                          )
                          const selected = engineRules[g.key] ?? fallback ?? ''
                          return (
                            <div className="setting-row" key={g.key}>
                              <div className="setting-info">
                                <label className="group-label">
                                  {/* 源站是 <t-icon :name>（tdesign sprite）。本仓**没有**该 sprite，
                                      `<use href="#t-icon-*">` 取不到 symbol。⚠️ 实测缺 sprite 的
                                      svg 元素会按 CSS 默认尺寸渲成 300×150 把整行撑高 ⇒ 显式给
                                      1em×1em（跟随 .group-icon 的 font-size），缺失时退化为占位方框
                                      而不是撑高行。⛔ 不自造 SVG path 伪装成源站图标。 */}
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
                                      onChange={(e) =>
                                        setEngineRules((prev) => ({ ...prev, [g.key]: e.target.value }))
                                      }
                                    >
                                      {options.map((o) => (
                                        <option key={o.value} value={o.value}>{o.label}</option>
                                      ))}
                                    </select>
                                  ) : (
                                    /* 无可用引擎：源站是 status="warning" 的 t-select + placeholder
                                       「无可用引擎」+ 一行 `.no-engine-warning`（内含「前往配置 →」链接）。
                                       本仓**没有**解析引擎设置页 ⇒ ⛔ 不写跳转（死链），
                                       只用同款 `.no-engine-warning` 容器放说明文字。

                                       ⚠️ 音频组单独处理：ASR 是已规划的接入项（用户 2026-11-08 明确
                                       「asr 也后续要支持」），所以显示「语音识别（接入中）」占位，
                                       而不是「本仓暂不支持该格式」—— 后者会让用户以为永远不会有。 */
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
                                        {g.key === 'audiovisual' ? 'ASR 能力规划中，接入后可直接选用' : '本仓暂不支持该格式'}
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

                {tab === 'image' && (
                  /* 源站 `KnowledgeBaseEditorModal.vue:341-488` 的 multimodal 节：
                     开关 + 5 行（VLLM / 描述语言 / 解析要求 / 图片向量 / 解析方案）。
                     后四行都在 `v-if="formData.multimodalConfig.enabled"` 里。
                     ⚠️ 源站的图片属性面板（`ImagePipelineSettings` + `/image-attrs/schema`）
                     本仓没有对应注册表接口 ⇒ 不画假控件；只保留后端真有的方案下拉
                     （顶层 `image_pipeline`：default / smartocr）。 */
                  <div className="section">
                    <div className="kb-multimodal-settings">
                      <div className="section-header" style={{ ...SECTION_HEADER_FLAT, margin: '0 0 20px' }}>
                        <h2>图像处理配置</h2>
                        <p className="section-description">配置图像内容理解能力，启用后支持图片等非文本内容的解析和检索</p>
                      </div>
                      <div className="settings-group">
                        <Row label="多模态功能" desc="启用图片等多模态内容的理解能力">
                          <Switch checked={vlm} label="多模态功能" onChange={setVlm} />
                        </Row>

                        {vlm && (
                          <>
                            <Row
                              label={<>VLLM 视觉模型 <span className="required">*</span></>}
                              desc="用于多模态理解的视觉语言模型（必选）"
                            >
                              <ModelSelect
                                value={vlmModelId}
                                models={models}
                                types={VISION_MODEL_TYPES}
                                placeholder="请选择 VLLM 模型（必选）"
                                onChange={setVlmModelId}
                              />
                            </Row>

                            <Row label="图片描述语言" desc="留空时跟随文档语言；不再强制使用中文">
                              <div className="t-select__wrap" style={{ width: 280 }}>
                                <select
                                  className="t-input__inner"
                                  style={{ width: '100%', height: 32 }}
                                  aria-label="图片描述语言"
                                  value={imageDescLanguage}
                                  onChange={(e) => setImageDescLanguage(e.target.value)}
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
                              <div className="t-textarea" style={{ width: '100%' }}>
                                <textarea
                                  className="t-textarea__inner"
                                  rows={3}
                                  maxLength={4000}
                                  placeholder="例如：重点识别设备铭牌、型号、告警代码和表格中的单位…"
                                  value={imageInstructions}
                                  onChange={(e) => setImageInstructions(e.target.value)}
                                />
                              </div>
                            </Row>

                            <Row
                              label="图片向量检索"
                              desc="开启后，生成图片描述之后再用向量模型直接对图片编码，检索时按图片内容召回描述没写到的信息。需要向量模型支持图片输入；每张图片多一次向量调用，向量检索的候选范围也会放大。只对此后入库或重新解析的文档生效，扫描件 PDF 页不生成"
                            >
                              <Switch checked={imageVector} label="图片向量检索" onChange={setImageVector} />
                            </Row>

                            <Row
                              label="图片解析方案"
                              desc="默认：每张图分别调用描述与 OCR。智能：先观察一次（描述 + 是否含文字），只对含文字的图再做 OCR"
                            >
                              <div className="t-select__wrap" style={{ width: 280 }}>
                                <select
                                  className="t-input__inner"
                                  style={{ width: '100%', height: 32 }}
                                  aria-label="图片解析方案"
                                  value={imagePipeline}
                                  onChange={(e) => setImagePipeline(e.target.value as 'default' | 'smartocr')}
                                >
                                  <option value="default">默认</option>
                                  <option value="smartocr">智能（先观察再决定是否 OCR）</option>
                                </select>
                              </div>
                            </Row>
                          </>
                        )}
                      </div>
                    </div>
                  </div>
                )}

                {tab === 'audio' && (
                  /* 源站 `KnowledgeBaseEditorModal.vue:490-532`：开关 + ASR 模型（必选）。 */
                  <div className="section">
                    <div className="kb-multimodal-settings">
                      <div className="section-header" style={{ ...SECTION_HEADER_FLAT, margin: '0 0 20px' }}>
                        <h2>音频语音识别</h2>
                        <p className="section-description">
                          配置语音识别（ASR），启用后可上传音频文件并整段转写为文本（常见格式：mp3、wav、m4a、flac、ogg 等）。暂不支持视频上传。
                        </p>
                      </div>
                      <div className="settings-group">
                        <Row
                          label="启用音频语音识别"
                          desc="启用后可上传音频到知识库，系统自动将语音转写为文本并参与解析与检索"
                        >
                          <Switch checked={asr} label="启用音频语音识别" onChange={setAsr} />
                        </Row>

                        {asr && (
                          <Row
                            label={<>ASR 模型 <span className="required">*</span></>}
                            desc="用于音频中语音转文本的识别模型（如 OpenAI Whisper）"
                          >
                            <ModelSelect
                              value={asrModelId}
                              models={models}
                              types={ASR_MODEL_TYPES}
                              placeholder="请选择 ASR 模型"
                              onChange={setAsrModelId}
                            />
                          </Row>
                        )}
                      </div>
                    </div>
                  </div>
                )}

                {tab === 'graph' && (
                  /* 源站 `settings/GraphSettings.vue`：开关 +（开时）自定义说明 / 关系类型 /
                     示例文本 / 实体列表。本仓 `extract_config` 已收这些键。 */
                  <div className="section">
                    <div className="graph-settings">
                      <div className="section-header" style={{ ...SECTION_HEADER_FLAT, margin: '0 0 20px' }}>
                        <h2>知识图谱配置</h2>
                        <p className="section-description">开启后将自动从文本中提取实体和关系</p>
                      </div>
                      <div className="settings-group">
                        <Row label="启用实体关系提取" desc="开启后将自动从文本中提取实体和关系">
                          <Switch checked={graph} label="启用实体关系提取" onChange={setGraph} />
                        </Row>

                        {graph && (
                          <>
                            <Row
                              label="额外提取要求"
                              desc="补充领域范围、实体筛选和属性提取要求；系统仍负责结构化输出协议"
                              stacked
                            >
                              <div className="t-textarea" style={{ width: '100%' }}>
                                <textarea
                                  className="t-textarea__inner"
                                  rows={3}
                                  maxLength={4000}
                                  placeholder="例如：重点提取合同主体、金额、履约期限和违约责任…"
                                  value={graphInstructions}
                                  onChange={(e) => setGraphInstructions(e.target.value)}
                                />
                              </div>
                            </Row>

                            <Row label="关系类型" desc="定义要提取的关系类型标签，多个标签用逗号分隔">
                              <FreeTagInput
                                value={graphTags}
                                placeholder="输入关系类型，如：工作于、同事、朋友等"
                                onChange={setGraphTags}
                              />
                            </Row>

                            <Row label="示例文本" desc="用于测试实体关系提取的示例文本" stacked>
                              <div className="t-textarea" style={{ width: '100%' }}>
                                <textarea
                                  className="t-textarea__inner"
                                  rows={6}
                                  maxLength={5000}
                                  placeholder="输入一段包含实体和关系的文本..."
                                  value={graphText}
                                  onChange={(e) => setGraphText(e.target.value)}
                                />
                                <div className="t-textarea__info_wrapper">
                                  <span className="t-textarea__limit">{graphText.length}/5000</span>
                                </div>
                              </div>
                            </Row>

                            {graphNodes.length > 0 && (
                              <Row label="实体列表" desc="从文本中提取的实体及其属性" stacked>
                                <div style={{ display: 'flex', flexDirection: 'column', gap: 12, width: '100%' }}>
                                  {graphNodes.map((node, i) => (
                                    <div
                                      key={i}
                                      style={{
                                        border: '1px solid var(--td-component-stroke)',
                                        borderRadius: 6,
                                        padding: 12,
                                        display: 'flex',
                                        flexDirection: 'column',
                                        gap: 8,
                                      }}
                                    >
                                      <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                                        <input
                                          className="t-input__inner"
                                          style={{ flex: 1, height: 32 }}
                                          placeholder="输入实体名称"
                                          value={node.name}
                                          onChange={(e) => setGraphNodes((list) => list.map((n, j) =>
                                            j === i ? { ...n, name: e.target.value } : n))}
                                        />
                                        <button
                                          type="button"
                                          className="t-button t-button--variant-outline t-button--theme-default t-size-s"
                                          onClick={() => setGraphNodes((list) => list.filter((_, j) => j !== i))}
                                        >
                                          <span className="t-button__text">删除</span>
                                        </button>
                                      </div>
                                      {node.attributes.map((attr, ai) => (
                                        <div key={ai} style={{ display: 'flex', gap: 8, alignItems: 'center', paddingLeft: 24 }}>
                                          <input
                                            className="t-input__inner"
                                            style={{ flex: 1, height: 32 }}
                                            placeholder="输入属性值"
                                            value={attr}
                                            onChange={(e) => setGraphNodes((list) => list.map((n, j) =>
                                              j === i
                                                ? { ...n, attributes: n.attributes.map((a, k) => (k === ai ? e.target.value : a)) }
                                                : n))}
                                          />
                                          <button
                                            type="button"
                                            className="t-button t-button--variant-outline t-button--theme-default t-size-s"
                                            onClick={() => setGraphNodes((list) => list.map((n, j) =>
                                              j === i ? { ...n, attributes: n.attributes.filter((_, k) => k !== ai) } : n))}
                                          >
                                            <span className="t-button__text">×</span>
                                          </button>
                                        </div>
                                      ))}
                                      <button
                                        type="button"
                                        className="t-button t-button--variant-outline t-button--theme-default t-size-s"
                                        style={{ alignSelf: 'flex-start' }}
                                        onClick={() => setGraphNodes((list) => list.map((n, j) =>
                                          j === i ? { ...n, attributes: [...n.attributes, ''] } : n))}
                                      >
                                        <span className="t-button__text">添加属性</span>
                                      </button>
                                    </div>
                                  ))}
                                </div>
                              </Row>
                            )}

                            <Row label="管理实体" desc="添加或删除实体节点">
                              <button
                                type="button"
                                className="t-button t-button--variant-base t-button--theme-primary t-size-s"
                                onClick={() => setGraphNodes((list) => [...list, { name: '', attributes: [] }])}
                              >
                                <span className="t-button__text">添加实体</span>
                              </button>
                            </Row>
                          </>
                        )}
                      </div>
                    </div>
                  </div>
                )}

                {tab === 'advanced' && (
                  /* 源站 `settings/KBAdvancedSettings.vue`：问题生成 4 项 / 自动打标 4 项 /
                     知识库描述 3 项 / 表格元数据要求。 */
                  <div className="section">
                    <div className="kb-advanced-settings">
                      <div className="section-header" style={{ ...SECTION_HEADER_FLAT, margin: '0 0 20px' }}>
                        <h2>高级设置</h2>
                        <p className="section-description">配置问题生成等高级功能</p>
                      </div>
                      <div className="settings-group">
                        {/* 源站 `KBAdvancedSettings.vue:10` 是 `v-if="ragEnabled !== false"`：
                            问题生成只对 RAG 索引有用，RAG 关掉时不显示。 */}
                        {rag && (
                          <>
                        <Row label="AI 问题生成" desc="解析文档时调用大模型为每个分块生成相关问题，提高检索召回率。启用后会增加文档解析耗时。">
                          <Switch checked={qgen} label="AI 问题生成" onChange={setQgen} />
                        </Row>

                        {qgen && (
                          <>
                            <Row label="问题生成模型" desc="留空时使用知识库摘要模型。">
                              <ModelSelect
                                value={qgenModelId}
                                models={models}
                                types={CHAT_MODEL_TYPES}
                                placeholder="请选择问题生成模型"
                                onChange={setQgenModelId}
                              />
                            </Row>
                            <Row label="生成问题数量" desc="每个文档分块生成的问题数量（1-10）">
                              <NumberInput value={qgenCount} min={1} max={10} onChange={setQgenCount} />
                            </Row>
                            <Row
                              label="问题生成要求"
                              desc="指定问题面向的人群、场景和表达方式，系统仍维护稳定输出格式"
                              stacked
                            >
                              <div className="t-textarea" style={{ width: '100%' }}>
                                <textarea
                                  className="t-textarea__inner"
                                  rows={3}
                                  maxLength={4000}
                                  placeholder="例如：生成客服用户常问的自然语言问题，避免考试题式表达…"
                                  value={qgenInstructions}
                                  onChange={(e) => setQgenInstructions(e.target.value)}
                                />
                              </div>
                            </Row>
                          </>
                        )}
                          </>
                        )}

                        <Row label="自动关联标签" desc="文档解析完成后，从当前知识库已有标签中选择合适标签自动关联。不会创建或删除标签，并会增加一次模型调用。">
                          <Switch checked={autotag} label="自动关联标签" onChange={setAutotag} />
                        </Row>

                        {autotag && (
                          <>
                            <Row label="分类模型" desc="留空时使用知识库摘要模型。">
                              <ModelSelect
                                value={autotagModelId}
                                models={models}
                                types={CHAT_MODEL_TYPES}
                                placeholder="请选择分类模型"
                                onChange={setAutotagModelId}
                              />
                            </Row>
                            <Row label="单篇文档最多标签数" desc="每篇文档最多自动关联 1-10 个已有标签。">
                              <NumberInput value={autotagMaxTags} min={1} max={10} onChange={setAutotagMaxTags} />
                            </Row>
                            <Row label="跳过已有标签的文档" desc="开启后，上传时已手动设置标签的文档不再自动打标签，避免覆盖人工分类意图。">
                              <Switch
                                checked={autotagSkipIfTagged}
                                label="跳过已有标签的文档"
                                onChange={setAutotagSkipIfTagged}
                              />
                            </Row>
                          </>
                        )}

                        <Row label="自动生成知识库描述" desc="文档新增、删除或摘要更新后，从文档画像聚合自动生成知识库描述。聚合本身不调用模型，仅当聚合结果变化时才发起一次小模型调用。">
                          <Switch checked={profileEnabled} label="自动生成知识库描述" onChange={setProfileEnabled} />
                        </Row>

                        {profileEnabled && (
                          <>
                            <Row label="生成模型" desc="留空时使用知识库摘要模型。">
                              <ModelSelect
                                value={profileModelId}
                                models={models}
                                types={CHAT_MODEL_TYPES}
                                placeholder="请选择生成模型"
                                onChange={setProfileModelId}
                              />
                            </Row>
                            <Row
                              label="描述生成要求"
                              desc="补充面向的读者、需要保留的术语或表达风格，系统仍维护稳定的输出格式。"
                              stacked
                            >
                              <div className="t-textarea" style={{ width: '100%' }}>
                                <textarea
                                  className="t-textarea__inner"
                                  rows={2}
                                  maxLength={2000}
                                  placeholder="例如：面向售后客服，用通俗说法描述产品线，保留产品型号…"
                                  value={profileInstructions}
                                  onChange={(e) => setProfileInstructions(e.target.value)}
                                />
                              </div>
                            </Row>
                          </>
                        )}

                        <Row
                          label="表格元数据生成要求"
                          desc="为 CSV/Excel 摘要补充业务背景和字段语义，帮助后续检索理解表格内容"
                          stacked
                        >
                          <div className="t-textarea" style={{ width: '100%' }}>
                            <textarea
                              className="t-textarea__inner"
                              rows={3}
                              maxLength={4000}
                              placeholder="例如：这是销售订单表，金额单位为元，status 字段使用公司内部状态码…"
                              value={tableMetadataInstructions}
                              onChange={(e) => setTableMetadataInstructions(e.target.value)}
                            />
                          </div>
                        </Row>
                      </div>
                    </div>
                  </div>
                )}

                {tab === 'storage' && (
                  /* 源站 `settings/KBStorageSettings.vue`：存储实例下拉（含 provider 与「默认」标记）。 */
                  <div className="section">
                    <div className="kb-storage-settings">
                      <div className="section-header" style={{ ...SECTION_HEADER_FLAT, margin: '0 0 20px' }}>
                        <h2>存储引擎</h2>
                        <p className="section-description">选择此知识库绑定的具体存储实例。</p>
                      </div>
                      <div className="settings-group">
                        <Row
                          label="存储实例"
                          desc="同一种存储类型可以配置多个不同 Endpoint、Bucket 或凭据的实例。"
                        >
                          <div className="t-select__wrap" style={{ width: 280 }}>
                            <select
                              className="t-input__inner"
                              style={{ width: '100%', height: 32 }}
                              aria-label="存储实例"
                              value={storageBackendId}
                              onChange={(e) => setStorageBackendId(e.target.value)}
                            >
                              <option value="">跟随默认存储</option>
                              {storageBackends.map((b) => (
                                <option key={b.id} value={b.id}>
                                  {b.name}（{b.provider.toUpperCase()}{b.is_default ? ' · 默认' : ''}）
                                </option>
                              ))}
                            </select>
                          </div>
                        </Row>
                      </div>
                    </div>
                  </div>
                )}
              </div>
            </div>

            <div className="settings-footer">
              <div className="settings-footer-actions">
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
                  disabled={submitting}
                  onClick={() => void submit()}
                >
                  <span className="t-button__text">
                    {submitting ? '创建中…' : (initial ? '保存' : '创建知识库')}
                  </span>
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
