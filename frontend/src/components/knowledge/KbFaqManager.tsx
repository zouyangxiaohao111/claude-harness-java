import { useCallback, useEffect, useState } from 'react'
import { ApiError } from '@/api/rest'
import { knowledgeApi } from '@/api/knowledge'
import type { KbFaqEntry, KbFaqEntryInput, KbTag } from '@/api/knowledge'

interface Props {
  baseId: string
  showToast: (msg: string, type?: 'success' | 'info') => void
}

const PAGE_SIZE = 20

/**
 * FAQ 条目管理 · 对齐源侧 `views/knowledge/components/FAQEntryManager.vue`（列表视图）。
 *
 * <h2>⭐ FAQ 库与文档库的区别</h2>
 * 文档库是「上传文件 → 解析 → 切块」；FAQ 库是<b>结构化的问答对</b>：
 * 标准问题 + 相似问法（提升召回）+ 否定问法（排除误命中）+ 多个答案。
 * 所以它没有「解析状态」，只有「启用/推荐」。
 *
 * <h2>⚠️ 三个容易做错的点</h2>
 * <ol>
 *   <li><b>列表接口返回的是 seq_id（数字）不是 UUID</b> —— 删除/改状态都用这个数字 id。</li>
 *   <li><b>PATCH 是三态</b>：字段不传=不改，传 false=显式关。
 *       批量启用/停用要显式传布尔，⛔ 不能靠省略字段。</li>
 *   <li><b>相似问法有多行</b>：用户按行输入，提交前要 trim + 去空行；
 *       空数组不能传 `[""]`（后端会当成一条空问法）。</li>
 * </ol>
 *
 * <h2>类名依据</h2>
 * 逐层照源侧列表视图（`FAQEntryManager.vue:99-420` + `FAQBatchBar.vue:35-46`）：
 * `.faq-manager > .faq-content > .faq-main > .faq-card-area > .faq-filter-bar +
 * .faq-scroll-container > .faq-entry-list > .faq-list-row > .faq-list-check /
 * .faq-list-main(.faq-list-question + .faq-list-tag) / .faq-list-meta(.faq-list-counts +
 * .faq-list-updated) / .faq-list-actions / .faq-list-details`。
 * 批量条用 `.faq-batch-bar__*`；空态用 `.faq-empty-state > .empty-content`。
 *
 * <p>⚠️ 设计稿 09 用的 `.faq-toolbar` / `.faq-list` / `.faq-item` / `.faq-q` / `.faq-a` /
 * `.faq-sim` / `.faq-meta` / `.faq-ops` / `.faq-edit` **只在设计稿的内联 &lt;style&gt; 里定义**，
 * 源站源码与 `styles/kb/*.css` 均 0 条规则（见报告 §5）⇒ 改用源站真类名，
 * 语义一一对应：toolbar→`faq-filter-bar`、list→`faq-entry-list`、item→`faq-list-row`、
 * q→`faq-list-question`、a→`faq-list-details`、sim/meta→`faq-list-counts`/`faq-list-meta`、
 * ops→`faq-list-actions`、edit→`faq-editor-form`。
 */
export function KbFaqManager({ baseId, showToast }: Props) {
  const [items, setItems] = useState<KbFaqEntry[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const [keyword, setKeyword] = useState('')
  const [enabledFilter, setEnabledFilter] = useState<'' | 'true' | 'false'>('')
  const [selected, setSelected] = useState<Set<number>>(new Set())

  const [tags, setTags] = useState<KbTag[]>([])

  // 编辑器
  const [editing, setEditing] = useState<KbFaqEntry | null>(null)
  const [creating, setCreating] = useState(false)

  const toMessage = (e: unknown) =>
    e instanceof ApiError ? e.userMessage() : e instanceof Error ? e.message : String(e)

  const load = useCallback(async (p = page) => {
    setLoading(true)
    try {
      const r = await knowledgeApi.listFaq(baseId, {
        page: p,
        page_size: PAGE_SIZE,
        keyword: keyword.trim() || undefined,
        is_enabled: enabledFilter === '' ? undefined : enabledFilter === 'true',
      })
      setItems(r.data ?? [])
      setTotal(r.total ?? 0)
      setError(null)
    } catch (e) {
      setError(toMessage(e))
      setItems([])
      setTotal(0)
    } finally {
      setLoading(false)
    }
  }, [baseId, keyword, enabledFilter, page])

  useEffect(() => { void load(1) ; setPage(1) }, [baseId, keyword, enabledFilter]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { void load(page) }, [page]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    void (async () => {
      try { setTags(await knowledgeApi.listTags(baseId, false)) } catch { setTags([]) }
    })()
  }, [baseId])

  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE))
  const allSelected = items.length > 0 && items.every((i) => selected.has(i.id))

  const toggleAll = () =>
    setSelected((prev) => (items.every((i) => prev.has(i.id)) ? new Set() : new Set(items.map((i) => i.id))))

  const toggleOne = (id: number) =>
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })

  /** 批量改状态：⭐ 必须显式传布尔（三态语义）。 */
  const batchSetEnabled = async (isEnabled: boolean) => {
    const ids = [...selected]
    if (ids.length === 0) return
    try {
      const r = await knowledgeApi.patchFaq(baseId, ids, { is_enabled: isEnabled })
      showToast(`已${isEnabled ? '启用' : '停用'} ${r.updated} 条`, 'success')
      setSelected(new Set())
      void load()
    } catch (e) {
      showToast(toMessage(e), 'info')
    }
  }

  const batchDelete = async () => {
    const ids = [...selected]
    if (ids.length === 0) return
    if (!window.confirm(`确定删除选中的 ${ids.length} 条 FAQ？`)) return
    try {
      const r = await knowledgeApi.deleteFaq(baseId, ids)
      showToast(`已删除 ${r.deleted} 条`, 'success')
      setSelected(new Set())
      void load()
    } catch (e) {
      showToast(toMessage(e), 'info')
    }
  }

  const removeOne = async (entry: KbFaqEntry) => {
    if (!window.confirm(`删除「${entry.standard_question}」？`)) return
    try {
      await knowledgeApi.deleteFaq(baseId, [entry.id])
      showToast('已删除', 'success')
      void load()
    } catch (e) {
      showToast(toMessage(e), 'info')
    }
  }

  return (
    <div className="faq-manager">
      <div className="faq-content">
        <div className="faq-main">
          <div className="faq-card-area">
            {/* 工具条（源侧 `.faq-filter-bar`）—— 搜索 + 筛选 + 全选 + 计数 + 新建 */}
            <div className="faq-filter-bar">
              <div className="t-input__wrap faq-search-input">
                <div className="t-input t-input--prefix">
                  <span className="t-input__prefix t-input__prefix-icon" aria-hidden="true">⌕</span>
                  <input
                    className="t-input__inner"
                    placeholder="搜索问题或答案…"
                    aria-label="搜索问题或答案"
                    value={keyword}
                    onChange={(e) => setKeyword(e.target.value)}
                  />
                </div>
              </div>

              <div className="faq-filter-bar__filters">
                <select
                  className="t-input"
                  value={enabledFilter}
                  onChange={(e) => setEnabledFilter(e.target.value as '' | 'true' | 'false')}
                  aria-label="按启用状态筛选"
                >
                  <option value="">全部状态</option>
                  <option value="true">已启用</option>
                  <option value="false">已停用</option>
                </select>
                {/* 全选：源侧列表视图的勾选在行内（`.faq-list-check`），
                    整表全选由批次条承接 —— 本仓保留原能力，放在筛选行里。
                    ⚠️ 源侧是 `<t-checkbox>`（渲染成 `<label class="t-checkbox">`）⇒ 也用 label，
                    否则原生 input 被 `.t-checkbox__former` 视觉隐藏后点不到。 */}
                <span className="faq-list-check">
                  <label className={`t-checkbox${allSelected ? ' t-is-checked' : ''}`}>
                    <input
                      type="checkbox"
                      className="t-checkbox__former"
                      checked={allSelected}
                      onChange={toggleAll}
                      aria-label="全选"
                    />
                    <span className="t-checkbox__input" />
                  </label>
                </span>
              </div>

              <div className="faq-filter-bar__trailing">
                <span className="faq-list-meta">共 {total} 条</span>
                <button
                  type="button"
                  className="t-button t-button--variant-base t-button--theme-primary t-size-s"
                  onClick={() => setCreating(true)}
                >
                  <span className="t-button__text">
                    <svg width={14} height={14} viewBox="0 0 24 24" aria-hidden="true" style={{ verticalAlign: '-2px', marginRight: 4 }}><use href="#t-icon-add" /></svg>
                    新建问答
                  </span>
                </button>
              </div>
            </div>

            {error && <div className="kb-err">{error}</div>}

            {loading && items.length === 0 && (
              <div className="faq-load-more">加载中…</div>
            )}

            {!loading && items.length === 0 && (
              /* 源侧空态：`.faq-empty-state > .empty-content > .empty-icon / .empty-text / .empty-desc` */
              <div className="faq-empty-state">
                <div className="empty-content">
                  <div className="empty-icon" aria-hidden="true">▧</div>
                  <div className="empty-text">
                    {keyword.trim() || enabledFilter ? '没有匹配的问答' : '这个 FAQ 库还没有条目'}
                  </div>
                  <div className="empty-desc">
                    {keyword.trim() || enabledFilter
                      ? '换个关键词或清空筛选试试'
                      : 'FAQ 库由「标准问题 + 相似问法 + 答案」组成，命中后直接返回答案'}
                  </div>
                  {!(keyword.trim() || enabledFilter) && (
                    <button
                      type="button"
                      className="t-button t-button--variant-base t-button--theme-primary t-size-s"
                      onClick={() => setCreating(true)}
                    >
                      <span className="t-button__text">新建第一条问答</span>
                    </button>
                  )}
                </div>
              </div>
            )}

            <div className="faq-scroll-container">
              {items.length > 0 && (
                <div className="faq-entry-list">
                  {items.map((it) => (
                    <div className={`faq-list-row is-selectable${selected.has(it.id) ? ' selected' : ''}`} key={it.id}>
                      <div className="faq-list-check">
                        <label className={`t-checkbox${selected.has(it.id) ? ' t-is-checked' : ''}`}>
                          <input
                            type="checkbox"
                            className="t-checkbox__former"
                            checked={selected.has(it.id)}
                            onChange={() => toggleOne(it.id)}
                            aria-label={`选择 ${it.standard_question}`}
                          />
                          <span className="t-checkbox__input" />
                        </label>
                      </div>

                      <div className="faq-list-main">
                        {/* 源侧列表视图的 `.faq-list-question` 是 <button>（点击展开详情）；
                            本仓答案常显、没有展开态 ⇒ 用源侧另一处同语义的静态问题文本类
                            `.faq-question`（网格卡版本，无 cursor:pointer），
                            ⛔ 不用 `.faq-list-question` 以免出现「有手型光标但点了没反应」。 */}
                        <div className="faq-question" title={it.standard_question}>
                          <span>{it.standard_question || '（空）'}</span>
                        </div>
                        <div className="faq-list-tag">
                          {it.tag_name ? (
                            <span className="t-tag t-tag--default t-tag--light t-size-s">
                              <span className="faq-list-tag-text">{it.tag_name}</span>
                            </span>
                          ) : (
                            <span className="t-tag t-tag--default t-tag--light t-size-s">
                              <span className="faq-list-tag-text">未打标签</span>
                            </span>
                          )}
                        </div>
                      </div>

                      <div className="faq-list-meta">
                        <div className="faq-list-counts">
                          <span>{it.similar_questions?.length ?? 0} 相似问法</span>
                          <span>{it.negative_questions?.length ?? 0} 否定问法</span>
                          <span>{it.answers?.length ?? 0} 答案</span>
                        </div>
                        <div className="faq-list-updated">更新于 {it.updated_at || '—'}</div>
                      </div>

                      <div className="faq-list-actions">
                        <span className={`status-item-compact${it.is_enabled ? ' success' : ''}`}>
                          <span className={`status-icon${it.is_enabled ? ' success' : ' warning'}`} aria-hidden="true">●</span>
                          <span>{it.is_enabled ? '已启用' : '已停用'}</span>
                        </span>
                        {/* 源侧这三个动作在 `.more-wrap` 弹出菜单里（`.popup-menu-item`）；
                            本仓一直是行内三个文字按钮（功能保留），用源站自己的文字按钮写法 */}
                        <button
                          type="button"
                          className="t-button t-button--variant-text t-button--theme-default t-size-s"
                          onClick={() => setEditing(it)}
                        >
                          <span className="t-button__text">编辑</span>
                        </button>
                        <button
                          type="button"
                          className="t-button t-button--variant-text t-button--theme-default t-size-s"
                          onClick={async () => {
                            try {
                              await knowledgeApi.patchFaq(baseId, [it.id], { is_enabled: !it.is_enabled })
                              void load()
                            } catch (e) { showToast(toMessage(e), 'info') }
                          }}
                        >
                          <span className="t-button__text">{it.is_enabled ? '停用' : '启用'}</span>
                        </button>
                        <button
                          type="button"
                          className="t-button t-button--variant-text t-button--theme-danger t-size-s"
                          onClick={() => void removeOne(it)}
                        >
                          <span className="t-button__text">删除</span>
                        </button>
                      </div>

                      {/* 答案（源侧 `.faq-list-details`，展开态容器；本仓常显第一条答案） */}
                      <div className="faq-list-details">
                        <div className="faq-list-detail-section">
                          <div className="faq-list-detail-label">答案</div>
                          <ul>
                            <li>{it.answers?.[0] || '（无答案）'}</li>
                          </ul>
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              )}

              {pages > 1 && (
                <div className="faq-load-more">
                  <button
                    type="button"
                    className="t-button t-button--variant-outline t-button--theme-default t-size-s"
                    disabled={page <= 1}
                    onClick={() => setPage(page - 1)}
                  >
                    <span className="t-button__text">上一页</span>
                  </button>
                  <span style={{ margin: '0 12px' }}>{page} / {pages}</span>
                  <button
                    type="button"
                    className="t-button t-button--variant-outline t-button--theme-default t-size-s"
                    disabled={page >= pages}
                    onClick={() => setPage(page + 1)}
                  >
                    <span className="t-button__text">下一页</span>
                  </button>
                </div>
              )}
            </div>
          </div>
        </div>
      </div>

      {/* 批量条（源侧 `components/FAQBatchBar.vue`：`.faq-batch-bar > .faq-batch-bar__inner > __selection + __actions`）*/}
      {selected.size > 0 && (
        <div className="faq-batch-bar" role="region" aria-label={`已选 ${selected.size} 条`}>
          <div className="faq-batch-bar__inner">
            <div className="faq-batch-bar__selection">
              <span className="faq-batch-bar__count">已选 {selected.size} 条</span>
              <button
                type="button"
                className="t-button t-button--variant-text t-button--theme-default t-size-s"
                onClick={() => setSelected(new Set())}
              >
                <span className="t-button__text">取消选择</span>
              </button>
            </div>
            <div className="faq-batch-bar__actions">
              <button type="button" className="t-button t-button--variant-outline t-button--theme-default t-size-s" onClick={() => void batchSetEnabled(true)}>
                <span className="t-button__text">批量启用</span>
              </button>
              <button type="button" className="t-button t-button--variant-outline t-button--theme-default t-size-s" onClick={() => void batchSetEnabled(false)}>
                <span className="t-button__text">批量停用</span>
              </button>
              <button type="button" className="t-button t-button--variant-outline t-button--theme-danger t-size-s" onClick={() => void batchDelete()}>
                <span className="t-button__text">批量删除</span>
              </button>
            </div>
          </div>
        </div>
      )}

      {/* 编辑器 */}
      {(creating || editing) && (
        <FaqEditor
          baseId={baseId}
          entry={editing}
          tags={tags}
          showToast={showToast}
          onClose={() => { setCreating(false); setEditing(null) }}
          onSaved={() => { setCreating(false); setEditing(null); void load() }}
        />
      )}
    </div>
  )
}

/** 新建/编辑弹窗。 */
function FaqEditor({ baseId, entry, tags, showToast, onClose, onSaved }: {
  baseId: string
  entry: KbFaqEntry | null
  tags: KbTag[]
  showToast: (m: string, t?: 'success' | 'info') => void
  onClose: () => void
  onSaved: () => void
}) {
  const [question, setQuestion] = useState(entry?.standard_question ?? '')
  const [similars, setSimilars] = useState((entry?.similar_questions ?? []).join('\n'))
  const [negatives, setNegatives] = useState((entry?.negative_questions ?? []).join('\n'))
  const [answers, setAnswers] = useState((entry?.answers ?? []).join('\n'))
  const [tagId, setTagId] = useState<number | null>(entry?.tag_id || null)
  const [enabled, setEnabled] = useState(entry?.is_enabled ?? true)
  const [recommended, setRecommended] = useState(entry?.is_recommended ?? false)
  const [busy, setBusy] = useState(false)

  const toMessage = (e: unknown) =>
    e instanceof ApiError ? e.userMessage() : e instanceof Error ? e.message : String(e)

  /** 多行文本 → 数组：trim + 去空行（⛔ 不能留空串，后端会当成一条空问法）。 */
  const lines = (s: string) => s.split('\n').map((x) => x.trim()).filter(Boolean)

  const save = async () => {
    const q = question.trim()
    if (!q) { showToast('标准问题不能为空', 'info'); return }
    const ans = lines(answers)
    if (ans.length === 0) { showToast('至少要有一条答案', 'info'); return }

    const body: KbFaqEntryInput = {
      standard_question: q,
      similar_questions: lines(similars),
      negative_questions: lines(negatives),
      answers: ans,
      tag_id: tagId,
      is_enabled: enabled,
      is_recommended: recommended,
    }
    setBusy(true)
    try {
      if (entry) await knowledgeApi.updateFaq(baseId, entry.id, body)
      else await knowledgeApi.createFaq(baseId, body)
      showToast(entry ? '已更新' : '已创建', 'success')
      onSaved()
    } catch (e) {
      showToast(toMessage(e), 'info')
    } finally {
      setBusy(false)
    }
  }

  return (
    /* 外壳沿用本仓既有弹窗（`kb-mini-*` 在 globals.css，本仓通用）；
       表单主体用源侧 `.faq-editor-form` + `.form-item/.form-label/.form-tip` */
    <div className="kb-mini-modal kb-mini-lg" role="dialog" aria-modal="true" aria-label={entry ? '编辑问答' : '新建问答'}>
      <div className="kb-mini-head">
        <strong>{entry ? '编辑问答' : '新建问答'}</strong>
        <button type="button" className="kb-mini-x" onClick={onClose}><svg width={14} height={14} viewBox="0 0 24 24" aria-hidden="true"><use href="#t-icon-close" /></svg></button>
      </div>
      <div className="kb-mini-body faq-editor-form">
        <div className="form-item">
          <label className="form-label required">标准问题</label>
          <div className="t-input__wrap">
            <div className="t-input">
              <input
                className="t-input__inner"
                placeholder="用户最常这样问…"
                value={question}
                onChange={(e) => setQuestion(e.target.value)}
                autoFocus
              />
            </div>
          </div>
        </div>

        <div className="form-item">
          <label className="form-label">相似问法（每行一条）</label>
          <div className="t-textarea">
            <textarea
              className="t-textarea__inner"
              placeholder={'换一种说法\n再换一种'}
              value={similars}
              onChange={(e) => setSimilars(e.target.value)}
              rows={3}
            />
          </div>
        </div>

        <div className="form-item">
          <label className="form-label">否定问法（每行一条，命中则不返回本答案）</label>
          <div className="t-textarea">
            <textarea
              className="t-textarea__inner"
              placeholder="容易混淆但不应命中的问法"
              value={negatives}
              onChange={(e) => setNegatives(e.target.value)}
              rows={2}
            />
          </div>
        </div>

        <div className="form-item">
          <label className="form-label required">答案（每行一条）</label>
          <div className="t-textarea">
            <textarea
              className="t-textarea__inner"
              placeholder="回答内容"
              value={answers}
              onChange={(e) => setAnswers(e.target.value)}
              rows={6}
            />
          </div>
        </div>

        <div className="form-item">
          <label className="form-label">标签</label>
          <select
            className="t-input"
            value={tagId ?? ''}
            onChange={(e) => setTagId(e.target.value ? Number(e.target.value) : null)}
          >
            <option value="">无标签</option>
            {tags.map((t) => (
              <option key={t.id} value={t.id}>{t.name}</option>
            ))}
          </select>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
          <label className={`t-checkbox${enabled ? ' t-is-checked' : ''}`}>
            <input
              type="checkbox"
              className="t-checkbox__former"
              checked={enabled}
              onChange={(e) => setEnabled(e.target.checked)}
            />
            <span className="t-checkbox__input" />
            <span className="t-checkbox__label">启用</span>
          </label>
          <label className={`t-checkbox${recommended ? ' t-is-checked' : ''}`}>
            <input
              type="checkbox"
              className="t-checkbox__former"
              checked={recommended}
              onChange={(e) => setRecommended(e.target.checked)}
            />
            <span className="t-checkbox__input" />
            <span className="t-checkbox__label">推荐</span>
          </label>
        </div>
      </div>
      <div className="kb-mini-foot">
        <button type="button" className="kb-btn ghost" onClick={onClose}>取消</button>
        <button type="button" className="kb-btn primary" disabled={busy} onClick={() => void save()}>
          {busy ? '保存中…' : '保存'}
        </button>
      </div>
    </div>
  )
}
