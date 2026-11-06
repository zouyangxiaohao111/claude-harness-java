import { useCallback, useEffect, useMemo, useState } from 'react'
import { ApiError } from '@/api/rest'
import { knowledgeApi } from '@/api/knowledge'
import type { KnowledgeBase } from '@/api/knowledge'

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
}

const GROUPS = ['基本信息', '处理设置', '数据', '集成', '管理'] as const

/**
 * 分节清单 —— **只列本仓后端真正支持的**。
 *
 * <p>源侧有 5 组 16 节（含 multimodal / asr / faq / activity 等）。
 * 这里逐条核对过 `KnowledgeBaseService.patch` 支持的 12 个 JSON 字段与
 * `KnowledgeBaseAdvancedController` 的端点：
 * <ul>
 *   <li>✅ 基本信息 / 模型 / 向量库 / 分块 / 图谱 / 高级 / 存储 / 数据源 / 分享 / 活动</li>
 *   <li>⛔ 多模态 / ASR：本仓有 `image_processing_config` / `asr_config` 列，
 *       但**没有对应的解析能力接线**（OSS 见 W8-O-19/O-20）⇒ 不做假开关</li>
 * </ul>
 */
const SECTIONS: SectionDef[] = [
  { key: 'basic', label: '基本信息', group: '基本信息' },
  { key: 'models', label: '模型配置', group: '基本信息' },
  { key: 'vectorStore', label: '向量库', group: '基本信息' },
  { key: 'parser', label: '解析器', group: '处理设置' },
  { key: 'chunking', label: '分块设置', group: '处理设置' },
  { key: 'graph', label: '图谱抽取', group: '处理设置' },
  { key: 'advanced', label: '后处理', group: '处理设置' },
  { key: 'storage', label: '存储后端', group: '数据' },
  { key: 'datasource', label: '数据源', group: '数据' },
  { key: 'share', label: '分享', group: '集成' },
  { key: 'activity', label: '活动记录', group: '管理' },
]

/** 分块配置默认值（后端 DTO 的缺省同款）。 */
const DEFAULT_CHUNKING = {
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
  const [busy, setBusy] = useState(false)
  const [form, setForm] = useState<KnowledgeBase>(base)

  const toMessage = (e: unknown) =>
    e instanceof ApiError ? e.userMessage() : e instanceof Error ? e.message : String(e)

  useEffect(() => { setForm(base) }, [base])

  // Esc 关闭：⚠️ 设置页只有一层，不需要「先退分节」
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const chunking = useMemo(
    () => parseJson(form.chunking_config, DEFAULT_CHUNKING), [form.chunking_config])
  const indexing = useMemo(
    () => parseJson<{ graph_enabled?: boolean; wiki_enabled?: boolean }>(null, {}), [])

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

  const saveChunking = (next: Partial<typeof DEFAULT_CHUNKING>) => {
    const merged = { ...chunking, ...next }
    void patch({ chunking_config: JSON.stringify(merged) }, '分块配置已保存')
  }

  const setLocal = <K extends keyof KnowledgeBase>(k: K, v: KnowledgeBase[K]) =>
    setForm((f) => ({ ...f, [k]: v }))

  return (
    <div className="kbs-overlay" onClick={(e) => { if (e.target === e.currentTarget) onClose() }}>
      <div className="kbs-dialog" role="dialog" aria-modal="true" aria-label="知识库设置">
        <div className="kbs-head">
          <div>
            <div className="kbs-title">知识库设置</div>
            <div className="kbs-sub">{base.name}</div>
          </div>
          <button type="button" className="kbs-close" onClick={onClose} title="关闭（Esc）">✕</button>
        </div>

        <div className="kbs-body">
          {/* 左侧导航（源侧 SettingsModalShell 形态） */}
          <nav className="kbs-nav">
            {GROUPS.map((g) => {
              const items = SECTIONS.filter((s) => s.group === g)
              if (items.length === 0) return null
              return (
                <div key={g} className="kbs-nav-group">
                  <div className="kbs-nav-label">{g}</div>
                  {items.map((s) => (
                    <button
                      key={s.key}
                      type="button"
                      className={`kbs-nav-item${section === s.key ? ' on' : ''}`}
                      onClick={() => setSection(s.key)}
                    >
                      {s.label}
                    </button>
                  ))}
                </div>
              )
            })}
          </nav>

          {/* 右侧分节 */}
          <div className="kbs-content">
            {section === 'basic' && (
              <Section title="基本信息" desc="名称与描述会显示在知识库列表和问答页头上。">
                <Row label="名称" desc="最多 255 字符">
                  <input
                    className="kb-input kbs-wide"
                    value={form.name}
                    onChange={(e) => setLocal('name', e.target.value)}
                  />
                </Row>
                <Row label="描述" desc="一句话说明这个库装什么">
                  <input
                    className="kb-input kbs-wide"
                    value={form.description ?? ''}
                    onChange={(e) => setLocal('description', e.target.value)}
                  />
                </Row>
                <Row label="类型" desc="创建后不可修改（改为 FAQ 会让条目变孤儿）">
                  <span className="kbs-static">{base.type}</span>
                </Row>
                <Row label="知识库 ID" desc="接口与脚本里用的标识">
                  <code className="kbs-code">{base.id}</code>
                </Row>
                <Footer busy={busy} onSave={saveBasic} />
              </Section>
            )}

            {section === 'models' && (
              <Section title="模型配置" desc="嵌入模型决定向量维度；摘要模型用于文档摘要与后处理。">
                <Row label="嵌入模型" desc="⚠️ 有文档后不建议更换（维度变了要全量重索引）">
                  <input
                    className="kb-input kbs-wide"
                    placeholder="如 deepseek/embedding-xxx（留空 = 用全局默认）"
                    value={form.embedding_model_id ?? ''}
                    onChange={(e) => setLocal('embedding_model_id', e.target.value)}
                  />
                </Row>
                <Row label="摘要模型" desc="用于文档摘要 / 图谱抽取 / 问题生成">
                  <input
                    className="kb-input kbs-wide"
                    placeholder="如 deepseek/deepseek-flash（留空 = 用全局默认）"
                    value={form.summary_model_id ?? ''}
                    onChange={(e) => setLocal('summary_model_id', e.target.value)}
                  />
                </Row>
                <Footer
                  busy={busy}
                  onSave={() => void patch({
                    embedding_model_id: form.embedding_model_id ?? '',
                    summary_model_id: form.summary_model_id ?? '',
                  }, '模型配置已保存')}
                />
              </Section>
            )}

            {section === 'vectorStore' && (
              <Section title="向量库" desc="默认跟随全局设置；单库覆盖用于隔离检索驱动。">
                <Row label="检索驱动" desc="sqlite（内置）/ postgres / milvus 等，取决于本机已装驱动">
                  <input
                    className="kb-input kbs-wide"
                    placeholder="留空 = 用全局默认驱动"
                    value={form.retriever_engine ?? ''}
                    onChange={(e) => setLocal('retriever_engine', e.target.value)}
                  />
                </Row>
                <Row label="向量库实例" desc="留空 = 用全局默认实例">
                  <input
                    className="kb-input kbs-wide"
                    placeholder="vector_store_id"
                    value={form.vector_store_id ?? ''}
                    onChange={(e) => setLocal('vector_store_id', e.target.value)}
                  />
                </Row>
                <Footer
                  busy={busy}
                  onSave={() => void patch({
                    retriever_engine: form.retriever_engine ?? '',
                    vector_store_id: form.vector_store_id ?? '',
                  }, '向量库配置已保存')}
                />
              </Section>
            )}

            {section === 'parser' && (
              <Section title="解析器" desc="按文件类型选择解析方式；PDF 默认走视觉，Word/Excel 走 Tika。">
                <Row label="可解析类型" desc="本仓当前支持的扩展名">
                  <div className="kbs-chips">
                    {['pdf', 'docx', 'doc', 'xlsx', 'xls', 'pptx', 'md', 'txt', 'csv', 'html'].map((t) => (
                      <span key={t} className="kbs-chip">{t}</span>
                    ))}
                  </div>
                </Row>
                <Row label="PDF 解析" desc="有图页面走视觉模型；纯文本走文本层">
                  <span className="kbs-static">视觉（vision）+ 文本层</span>
                </Row>
                <Row label="Office 解析" desc="Word / Excel / PPT 统一走 Apache Tika 2.x">
                  <span className="kbs-static">Tika 2.9.2</span>
                </Row>
                <p className="kbs-note">
                  ⚠️ 解析器选择是<b>进程级配置</b>，不是按库存的 —— 所以这一页只有说明，没有开关。
                </p>
              </Section>
            )}

            {section === 'chunking' && (
              <Section title="分块设置" desc="决定文档被切成多大的片段；改完对新文档生效。">
                <Row label="分块大小" desc="字符数，建议 256–1024">
                  <input
                    type="number"
                    className="kb-input kbs-num"
                    value={chunking.chunk_size}
                    min={64}
                    max={7500}
                    onChange={(e) => saveChunking({ chunk_size: Number(e.target.value) })}
                  />
                </Row>
                <Row label="重叠字符" desc="相邻块重叠，避免切断语义">
                  <input
                    type="number"
                    className="kb-input kbs-num"
                    value={chunking.chunk_overlap}
                    min={0}
                    max={2048}
                    onChange={(e) => saveChunking({ chunk_overlap: Number(e.target.value) })}
                  />
                </Row>
                <Row label="分隔符" desc="按优先级依次尝试；JSON 数组">
                  <input
                    className="kb-input kbs-wide"
                    value={JSON.stringify(chunking.split_markers)}
                    onChange={(e) => {
                      try {
                        const arr = JSON.parse(e.target.value)
                        if (Array.isArray(arr)) saveChunking({ split_markers: arr })
                      } catch { /* 输入中途不合法，不落库 */ }
                    }}
                  />
                </Row>
                <Row label="保留分隔符" desc="切开后把分隔符留在块尾">
                  <input
                    type="checkbox"
                    checked={chunking.keep_separator}
                    onChange={(e) => saveChunking({ keep_separator: e.target.checked })}
                  />
                </Row>
                <p className="kbs-note">
                  ⚠️ 已有文档不会被重新切块 —— 改了只影响<b>之后</b>上传的文件。
                  要让旧文档生效需逐条「重解析」。
                </p>
              </Section>
            )}

            {section === 'graph' && (
              <Section title="图谱抽取" desc="开启后，文档入库时抽取实体与关系，供检索的第二路证据使用。">
                <Row label="启用图谱" desc="需同时开启索引策略的 graph_enabled">
                  <input
                    type="checkbox"
                    checked={!!indexing.graph_enabled}
                    onChange={(e) => void patch({
                      indexing_strategy: JSON.stringify({ graph_enabled: e.target.checked }),
                    }, '图谱开关已保存')}
                  />
                </Row>
                <Row label="抽取配置" desc="extract_config.enabled 为 true 才会真正抽取">
                  <label className="kbs-inline">
                    <input
                      type="checkbox"
                      checked={parseJson<{ enabled?: boolean }>(form.extract_config, {}).enabled === true}
                      onChange={(e) => void patch({
                        extract_config: JSON.stringify({ enabled: e.target.checked }),
                      }, '抽取配置已保存')}
                    />
                    <span>启用实体抽取</span>
                  </label>
                </Row>
                <p className="kbs-note">
                  ⚠️ 两者是<b>合取</b>关系（源侧 `IsGraphEnabled`）：任一为假都不抽。
                  改完需对已有文档「重解析」才会生成图谱。
                </p>
              </Section>
            )}

            {section === 'advanced' && (
              <Section title="后处理" desc="入库完成后的增强步骤；每一步都需要配置对应模型才会执行。">
                <Row label="自动生成问题" desc="为每块生成检索用的问题，提升召回">
                  <label className="kbs-inline">
                    <input
                      type="checkbox"
                      checked={parseJson<{ enabled?: boolean }>(form.question_generation_config, {}).enabled === true}
                      onChange={(e) => void patch({
                        question_generation_config: JSON.stringify({ enabled: e.target.checked }),
                      }, '问题生成已保存')}
                    />
                    <span>启用</span>
                  </label>
                </Row>
                <Row label="自动打标签" desc="模型读正文给出标签">
                  <label className="kbs-inline">
                    <input
                      type="checkbox"
                      checked={parseJson<{ enabled?: boolean }>(form.auto_tag_config, {}).enabled === true}
                      onChange={(e) => void patch({
                        auto_tag_config: JSON.stringify({ enabled: e.target.checked }),
                      }, '自动打标已保存')}
                    />
                    <span>启用</span>
                  </label>
                </Row>
                <Row label="Wiki 自动生成" desc="把文档合成为 Wiki 页">
                  <label className="kbs-inline">
                    <input
                      type="checkbox"
                      checked={parseJson<{ auto_generate_enabled?: boolean }>(form.wiki_config, {}).auto_generate_enabled === true}
                      onChange={(e) => void patch({
                        wiki_config: JSON.stringify({ auto_generate_enabled: e.target.checked }),
                      }, 'Wiki 开关已保存')}
                    />
                    <span>启用</span>
                  </label>
                </Row>
                <p className="kbs-note">⚠️ 这三项都要求已配置<b>摘要模型</b>，否则开关打开也不会执行。</p>
              </Section>
            )}

            {section === 'storage' && (
              <Section title="存储后端" desc="原文文件存在哪里；留空 = 用全局默认。">
                <Row label="存储后端" desc="local / minio / cos 等，取决于已配置的后端">
                  <input
                    className="kb-input kbs-wide"
                    placeholder="留空 = 用全局默认存储"
                    value={form.storage_backend_id ?? ''}
                    onChange={(e) => setLocal('storage_backend_id', e.target.value)}
                  />
                </Row>
                <Footer
                  busy={busy}
                  onSave={() => void patch({ storage_backend_id: form.storage_backend_id ?? '' }, '存储配置已保存')}
                />
              </Section>
            )}

            {section === 'datasource' && (
              <Section title="数据源" desc="从外部系统（数据库 / 网页等）同步内容进知识库。">
                <div className="kbs-empty">
                  <div className="kbs-empty-t">尚未配置数据源</div>
                  <div className="kbs-empty-s">
                    数据源需要额外的同步调度与凭据管理，当前版本<b>未接入</b>。
                    <br />如需定期同步，请先用「定时任务」调用导入接口。
                  </div>
                </div>
              </Section>
            )}

            {section === 'share' && (
              <Section title="分享" desc="把这个知识库分享到共享空间（供团队其他成员使用）。">
                <ShareSection baseId={base.id} showToast={showToast} />
              </Section>
            )}

            {section === 'activity' && (
              <Section title="活动记录" desc="谁在什么时候改了这个知识库。">
                <ActivitySection baseId={base.id} showToast={showToast} />
              </Section>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}

// ── 子组件 ──────────────────────────────────────────────────────

function Section({ title, desc, children }: {
  title: string; desc: string; children: React.ReactNode
}) {
  return (
    <div className="kbs-section">
      <div className="kbs-section-head">
        <h3>{title}</h3>
        <p>{desc}</p>
      </div>
      <div className="kbs-rows">{children}</div>
    </div>
  )
}

function Row({ label, desc, children }: {
  label: string; desc?: string; children: React.ReactNode
}) {
  return (
    <div className="kbs-row">
      <div className="kbs-row-info">
        <label>{label}</label>
        {desc && <p>{desc}</p>}
      </div>
      <div className="kbs-row-ctl">{children}</div>
    </div>
  )
}

function Footer({ busy, onSave }: { busy: boolean; onSave: () => void }) {
  return (
    <div className="kbs-footer">
      <button type="button" className="kb-btn primary" disabled={busy} onClick={onSave}>
        {busy ? '保存中…' : '保存'}
      </button>
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
    <div>
      {loading && <div className="kbs-empty-s">加载中…</div>}
      {!loading && shares.length === 0 && (
        <div className="kbs-empty">
          <div className="kbs-empty-t">还没有分享</div>
          <div className="kbs-empty-s">分享后其他成员可在「共享空间」里看到。</div>
          <button
            type="button"
            className="kb-btn primary"
            style={{ marginTop: 12 }}
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
            ＋ 分享到共享空间
          </button>
        </div>
      )}
      {!loading && shares.length > 0 && (
        <div className="kbs-share-list">
          {shares.map((s) => (
            <div key={s.id} className="kbs-share-row">
              <span>共享空间</span>
              <span className="kbs-chip">{s.permission}</span>
              <span className="kbs-share-note">
                {s.effective === false ? '（接收方未接入，暂不生效）' : ''}
              </span>
              <button
                type="button"
                className="kb-link danger"
                onClick={async () => {
                  try {
                    await knowledgeApi.deleteShare(baseId, s.id)
                    void load()
                  } catch (e) {
                    showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
                  }
                }}
              >
                取消分享
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

/** 活动记录分节（后端 GET /knowledge-bases/{id}/activity）。 */
function ActivitySection({ baseId, showToast }: {
  baseId: string; showToast: (m: string, t?: 'success' | 'info') => void
}) {
  const [rows, setRows] = useState<Array<Record<string, unknown>>>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    void (async () => {
      try {
        const list = await knowledgeApi.listActivity(baseId)
        if (!cancelled) setRows(list as never)
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

  if (loading) return <div className="kbs-empty-s">加载中…</div>
  if (rows.length === 0) {
    return (
      <div className="kbs-empty">
        <div className="kbs-empty-t">暂无活动记录</div>
        <div className="kbs-empty-s">上传、解析、编辑等操作会记录在这里。</div>
      </div>
    )
  }
  return (
    <div className="kbs-activity">
      {rows.map((r, i) => (
        <div key={i} className="kbs-activity-row">
          <span className="kbs-activity-time">{String(r.created_at ?? r.create_time ?? '')}</span>
          <span className="kbs-activity-act">{String(r.action ?? r.type ?? '')}</span>
          <span className="kbs-activity-desc">{String(r.detail ?? r.message ?? '')}</span>
        </div>
      ))}
    </div>
  )
}
