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

export interface KbCreateSubmit {
  name: string
  description: string
  type: 'document' | 'faq'
  embeddingModelId: string | null
  chunkSize: number
  chunkOverlap: number
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
 *   <li>「向量存储 / 解析引擎 / 图像处理 / 音频处理 / 知识图谱 / 高级设置 / 存储引擎」
 *       这些分组本仓有独立配置页（知识库设置里），**创建时不暴露** ——
 *       画了也没后端字段可写，属于假开关。只保留真正写进 `CreateKnowledgeBaseRequest` 的四节。</li>
 *   <li>知识库类型只有 `文档` / `问答`（后端 `type` 枚举），源侧的 `Wiki` 是本仓的索引策略。</li>
 *   <li>「Wiki 索引」勾选在本仓对应 `type=wiki`，但创建接口暂不支持 ⇒ 灰置 + 提示。</li>
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
  const [vlm, setVlm] = useState(false)
  const [asr, setAsr] = useState(false)
  /**
   * 知识图谱开关 —— **一处状态、两处入口**。
   *
   * <p>⭐ 索引策略第三张卡与「知识图谱」tab 的开关共用它，否则两处显示会打架
   * （用户在 tab 里开了、策略卡还是关的，最终只写其中一个字段）。
   * 提交时它会**同时**写 `indexing_strategy.graph_enabled` 与 `extract_config.enabled`
   * —— 后端判据是这两者的合取（见 `KbPostProcessConfig.graphEnabled`）。
   */
  const [graph, setGraph] = useState(false)
  const [qgen, setQgen] = useState(false)
  const [autotag, setAutotag] = useState(false)
  const [faqSimilar, setFaqSimilar] = useState(true)
  const [faqNegative, setFaqNegative] = useState(true)

  /** 创建时不支持写入的字段，点了如实说明（⛔ 不假装生效）。 */
  const notWiredInCreate = (what: string) =>
    showToast(`${what}：创建接口不支持写入，创建后可在该库的 ⚙ 设置里配置`, 'info')

  // 拉真实模型清单（失败静默：下拉留空，用户仍可点「创建」）
  useEffect(() => {
    let alive = true
    api<Model[]>('/models')
      .then((list) => { if (alive) setModels(list.filter((m) => m.enabled)) })
      .catch(() => { /* 静默：模型列表拿不到不影响创建 */ })
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
    setSubmitting(true)
    try {
      await onCreate({
        name: name.trim(),
        description: description.trim(),
        type,
        embeddingModelId: null,
        chunkSize,
        chunkOverlap,
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
        //    FAQ 库没有图谱流水线 ⇒ null，⛔ 不写空对象。
        extract_config: type === 'faq' || !graph ? null : JSON.stringify({ enabled: true }),
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
                  /* 源侧 KBChunkingSettings.vue —— .kb-chunking-settings > .section-header > .settings-group
                     ⚠️ 这一节的 sticky 页头源站**真的这么写**（kb-settings-panel.css）⇒ 保留全局 sticky 规则 */
                  <div className="section">
                    <div className="kb-chunking-settings">
                      <div className="section-header">
                        <h2>分块设置</h2>
                        <p className="section-description">控制文档切分粒度，影响检索召回精度</p>
                      </div>
                      <div className="settings-group">
                        <div className="setting-row">
                          <div className="setting-info">
                            <label>分块大小</label>
                            <p className="desc">默认 512，范围 100–7500。</p>
                          </div>
                          <div className="setting-control">
                            <div className="t-input__wrap">
                              <div className="t-input">
                                <input
                                  className="t-input__inner"
                                  type="number"
                                  min={100}
                                  max={7500}
                                  value={chunkSize}
                                  onChange={(e) => setChunkSize(Number(e.target.value) || 512)}
                                />
                              </div>
                            </div>
                          </div>
                        </div>
                        <div className="setting-row">
                          <div className="setting-info">
                            <label>重叠长度</label>
                            <p className="desc">默认 80，重叠越大上下文越连续。</p>
                          </div>
                          <div className="setting-control">
                            <div className="t-input__wrap">
                              <div className="t-input">
                                <input
                                  className="t-input__inner"
                                  type="number"
                                  min={0}
                                  max={500}
                                  value={chunkOverlap}
                                  onChange={(e) => setChunkOverlap(Number(e.target.value) || 80)}
                                />
                              </div>
                            </div>
                          </div>
                        </div>
                      </div>
                    </div>
                  </div>
                )}

                {/* ── 以下 7 节逐字对齐设计稿 01 页 `#create-modal` 的 data-navpanel ──
                    ⚠️ 本仓创建接口 `CreateKnowledgeBaseRequest` **只接受**
                    name / description / type / chunking_config / embedding_model_id，
                    其余字段（解析引擎/图像/音频/图谱/存储…）创建时写不进去。
                    ⇒ 按设计稿**完整呈现**（用户要求「完整按 HTML」），
                    但控件点击时如实提示「创建后可在此库的 ⚙ 设置里配置」，
                    ⛔ 不假装写入、也不隐藏设计稿有的项。「向量存储」「解析引擎」「存储引擎」
                    这三节在设计稿里本来就是**只读展示**（vs-badge / mock-badge），照抄即可。 */}

                {tab === 'faq' && (
                  /* FAQ 型专属（用户 2026-11-08 规格）：问答库没有分块/解析流水线，
                     只有「条目怎么导入 / 相似问法怎么用」这几个开关。 */
                  <div className="section">
                    <div className="section-header" style={{ ...SECTION_HEADER_FLAT, margin: '0 0 20px' }}>
                      <h2>FAQ 设置</h2>
                      <p className="section-description">结构化问答对：命中标准问题或相似问法时直接返回答案</p>
                    </div>
                    <div className="settings-group">
                      <div className="setting-row">
                        <div className="setting-info">
                          <label>启用相似问法匹配</label>
                          <p className="desc">除标准问题外，用户用同义说法提问也能命中</p>
                        </div>
                        <div className="setting-control">
                          <button
                            type="button"
                            className={`t-switch t-size-m${faqSimilar ? ' t-is-checked' : ''}`}
                            role="switch"
                            aria-checked={faqSimilar}
                            aria-label="启用相似问法匹配"
                            onClick={() => { setFaqSimilar((v) => !v); notWiredInCreate('相似问法匹配') }}
                          >
                            <span className="t-switch__handle" />
                          </button>
                        </div>
                      </div>
                      <div className="setting-row">
                        <div className="setting-info">
                          <label>启用否定问法排除</label>
                          <p className="desc">标注「不是这个问题」的说法，避免误命中</p>
                        </div>
                        <div className="setting-control">
                          <button
                            type="button"
                            className={`t-switch t-size-m${faqNegative ? ' t-is-checked' : ''}`}
                            role="switch"
                            aria-checked={faqNegative}
                            aria-label="启用否定问法排除"
                            onClick={() => { setFaqNegative((v) => !v); notWiredInCreate('否定问法排除') }}
                          >
                            <span className="t-switch__handle" />
                          </button>
                        </div>
                      </div>
                      <div className="setting-row">
                        <div className="setting-info">
                          <label>答案直接返回</label>
                          <p className="desc">命中后跳过 LLM 生成，直接把标准答案返回给用户</p>
                        </div>
                        <div className="setting-control">
                          <span className="ext-tag">已启用（FAQ 库默认行为）</span>
                        </div>
                      </div>
                    </div>
                  </div>
                )}

                {tab === 'vector' && (
                  <div className="section">
                    <div className="section-header" style={{ ...SECTION_HEADER_FLAT, margin: '0 0 20px' }}>
                      <h2>向量存储</h2>
                      <p className="section-description">选择此知识库要写入的向量存储。绑定不可更改。</p>
                    </div>
                    <div className="settings-group">
                      <div className="setting-row">
                        <div className="setting-info">
                          <label>已绑定的向量存储</label>
                          <p className="desc">向量存储绑定在创建后无法更改。</p>
                        </div>
                        <div className="setting-control">
                          <span className="vs-badge vs-badge-env">
                            <span className="vs-badge-name">系统默认</span>
                            <span className="vs-badge-engine">(sqlite)</span>
                          </span>
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
                    {/* ⛔ 不能假装保存：`CreateKnowledgeBaseRequest` 不收解析引擎规则。 */}
                    <p className="desc">创建接口暂不写入解析引擎规则；此处选择仅作预览，不影响入库。</p>
                  </div>
                )}

                {tab === 'image' && (
                  <div className="section">
                    <div className="section-header" style={{ ...SECTION_HEADER_FLAT, margin: '0 0 20px' }}>
                      <h2>图像处理配置</h2>
                      <p className="section-description">配置图像内容理解能力</p>
                    </div>
                    <div className="settings-group">
                      <div className="setting-row">
                        <div className="setting-info">
                          <label>多模态功能</label>
                          <p className="desc">启用图片等多模态内容的理解能力</p>
                        </div>
                        <div className="setting-control">
                          <button
                            type="button"
                            className={`t-switch t-size-m${vlm ? ' t-is-checked' : ''}`}
                            role="switch"
                            aria-checked={vlm}
                            aria-label="多模态功能"
                            onClick={() => { setVlm((v) => !v); notWiredInCreate('多模态功能') }}
                          >
                            <span className="t-switch__handle" />
                          </button>
                        </div>
                      </div>
                    </div>
                  </div>
                )}

                {tab === 'audio' && (
                  <div className="section">
                    <div className="section-header" style={{ ...SECTION_HEADER_FLAT, margin: '0 0 20px' }}>
                      <h2>音频语音识别</h2>
                      <p className="section-description">配置音频内容转写能力</p>
                    </div>
                    <div className="settings-group">
                      <div className="setting-row">
                        <div className="setting-info">
                          <label>启用音频语音识别</label>
                          <p className="desc">启用后可上传音频并自动转写为文本</p>
                        </div>
                        <div className="setting-control">
                          <button
                            type="button"
                            className={`t-switch t-size-m${asr ? ' t-is-checked' : ''}`}
                            role="switch"
                            aria-checked={asr}
                            aria-label="启用音频语音识别"
                            onClick={() => { setAsr((v) => !v); notWiredInCreate('音频语音识别') }}
                          >
                            <span className="t-switch__handle" />
                          </button>
                        </div>
                      </div>
                    </div>
                  </div>
                )}

                {tab === 'graph' && (
                  <div className="section">
                    <div className="section-header" style={{ ...SECTION_HEADER_FLAT, margin: '0 0 20px' }}>
                      <h2>知识图谱</h2>
                      <p className="section-description">从文档中抽取实体与关系</p>
                    </div>
                    <div className="settings-group">
                      <div className="setting-row">
                        <div className="setting-info">
                          <label>启用图谱抽取</label>
                          <p className="desc">每篇文档单独抽取，跨文档不合并</p>
                        </div>
                        <div className="setting-control">
                          <button
                            type="button"
                            className={`t-switch t-size-m${graph ? ' t-is-checked' : ''}`}
                            role="switch"
                            aria-checked={graph}
                            aria-label="启用图谱抽取"
                            /* ⭐ 与索引策略第三张卡同一个 state（两处显示必须一致）。
                               后端 create 已收 extract_config ⇒ 不再是「创建后配置」，
                               ⛔ 不能再弹 notWiredInCreate。 */
                            onClick={() => setGraph((v) => !v)}
                          >
                            <span className="t-switch__handle" />
                          </button>
                        </div>
                      </div>
                    </div>
                  </div>
                )}

                {tab === 'advanced' && (
                  <div className="section">
                    <div className="section-header" style={{ ...SECTION_HEADER_FLAT, margin: '0 0 20px' }}>
                      <h2>高级设置</h2>
                      <p className="section-description">创建后可随时在知识库设置里调整</p>
                    </div>
                    <div className="settings-group">
                      <div className="setting-row">
                        <div className="setting-info">
                          <label>AI 问题生成</label>
                          <p className="desc">为每个分块生成相关问题，提高召回率</p>
                        </div>
                        <div className="setting-control">
                          <button
                            type="button"
                            className={`t-switch t-size-m${qgen ? ' t-is-checked' : ''}`}
                            role="switch"
                            aria-checked={qgen}
                            aria-label="AI 问题生成"
                            onClick={() => { setQgen((v) => !v); notWiredInCreate('AI 问题生成') }}
                          >
                            <span className="t-switch__handle" />
                          </button>
                        </div>
                      </div>
                      <div className="setting-row">
                        <div className="setting-info">
                          <label>自动关联标签</label>
                          <p className="desc">从已有标签中选择合适标签自动关联</p>
                        </div>
                        <div className="setting-control">
                          <button
                            type="button"
                            className={`t-switch t-size-m${autotag ? ' t-is-checked' : ''}`}
                            role="switch"
                            aria-checked={autotag}
                            aria-label="自动关联标签"
                            onClick={() => { setAutotag((v) => !v); notWiredInCreate('自动关联标签') }}
                          >
                            <span className="t-switch__handle" />
                          </button>
                        </div>
                      </div>
                    </div>
                  </div>
                )}

                {tab === 'storage' && (
                  <div className="section">
                    <div className="section-header" style={{ ...SECTION_HEADER_FLAT, margin: '0 0 20px' }}>
                      <h2>存储引擎</h2>
                      <p className="section-description">原文文件保存在哪里</p>
                    </div>
                    <div className="settings-group">
                      <div className="setting-row">
                        <div className="setting-info">
                          <label>存储后端</label>
                          <p className="desc">创建后可迁移</p>
                        </div>
                        <div className="setting-control">
                          <span className="ext-tag">本地磁盘 ~/.nexusai/kb-storage</span>
                        </div>
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
