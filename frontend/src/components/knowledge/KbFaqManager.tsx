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
 * FAQ 条目管理 · 对齐源侧 `components/FAQEntryManager.vue`。
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
    <div className="kb-faq">
      {/* 工具条 */}
      <div className="kb-faq-bar">
        <input
          className="kb-input kb-doc-search"
          placeholder="搜索问题或答案…"
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
        />
        <select
          className="kb-select"
          value={enabledFilter}
          onChange={(e) => setEnabledFilter(e.target.value as '' | 'true' | 'false')}
          aria-label="按启用状态筛选"
        >
          <option value="">全部状态</option>
          <option value="true">已启用</option>
          <option value="false">已停用</option>
        </select>
        <span className="kb-faq-count">共 {total} 条</span>
        <button type="button" className="kb-btn primary" onClick={() => setCreating(true)}>
          ＋ 新建问答
        </button>
      </div>

      {/* 批量条 */}
      {selected.size > 0 && (
        <div className="kb-batch-bar">
          <span className="kb-batch-count">已选 {selected.size} 条</span>
          <button type="button" className="kb-btn ghost" onClick={() => void batchSetEnabled(true)}>批量启用</button>
          <button type="button" className="kb-btn ghost" onClick={() => void batchSetEnabled(false)}>批量停用</button>
          <button type="button" className="kb-btn ghost danger" onClick={() => void batchDelete()}>批量删除</button>
        </div>
      )}

      {error && <div className="kb-err">{error}</div>}

      {/* 列表 */}
      {loading && items.length === 0 && <div className="kb-wiki-loading">加载中…</div>}

      {!loading && items.length === 0 && (
        <div className="kb-empty-cards">
          <div className="kb-empty-title">
            {keyword.trim() || enabledFilter ? '没有匹配的问答' : '这个 FAQ 库还没有条目'}
          </div>
          <div className="kb-empty-sub">
            {keyword.trim() || enabledFilter
              ? '换个关键词或清空筛选试试'
              : 'FAQ 库由「标准问题 + 相似问法 + 答案」组成，命中后直接返回答案'}
          </div>
          {!(keyword.trim() || enabledFilter) && (
            <button type="button" className="kb-btn primary" onClick={() => setCreating(true)}>
              新建第一条问答
            </button>
          )}
        </div>
      )}

      {items.length > 0 && (
        <div className="kb-faq-list">
          <div className="kb-faq-row kb-faq-head">
            <span className="kb-faq-check">
              <input type="checkbox" checked={allSelected} onChange={toggleAll} aria-label="全选" />
            </span>
            <span className="kb-faq-q">标准问题</span>
            <span className="kb-faq-sim">相似问法</span>
            <span className="kb-faq-tag">标签</span>
            <span className="kb-faq-state">状态</span>
            <span className="kb-faq-ops">操作</span>
          </div>

          {items.map((it) => (
            <div className="kb-faq-row" key={it.id}>
              <span className="kb-faq-check">
                <input
                  type="checkbox"
                  checked={selected.has(it.id)}
                  onChange={() => toggleOne(it.id)}
                  aria-label={`选择 ${it.standard_question}`}
                />
              </span>
              <span className="kb-faq-q" title={it.standard_question}>
                <span className="kb-faq-qtext">{it.standard_question || '（空）'}</span>
                <span className="kb-faq-answer">
                  {it.answers?.[0] || '（无答案）'}
                </span>
              </span>
              <span className="kb-faq-sim" title={(it.similar_questions ?? []).join(' / ')}>
                {it.similar_questions?.length
                  ? `${it.similar_questions.length} 条`
                  : '—'}
              </span>
              <span className="kb-faq-tag">
                {it.tag_name ? <span className="kb-tag-chip">{it.tag_name}</span> : '—'}
              </span>
              <span className={`kb-faq-state${it.is_enabled ? ' is-on' : ''}`}>
                <i className="kb-dot" />
                {it.is_enabled ? '已启用' : '已停用'}
              </span>
              <span className="kb-faq-ops">
                <button type="button" className="kb-link" onClick={() => setEditing(it)}>编辑</button>
                <button
                  type="button"
                  className="kb-link"
                  onClick={async () => {
                    try {
                      await knowledgeApi.patchFaq(baseId, [it.id], { is_enabled: !it.is_enabled })
                      void load()
                    } catch (e) { showToast(toMessage(e), 'info') }
                  }}
                >
                  {it.is_enabled ? '停用' : '启用'}
                </button>
                <button type="button" className="kb-link danger" onClick={() => void removeOne(it)}>删除</button>
              </span>
            </div>
          ))}
        </div>
      )}

      {/* 分页 */}
      {pages > 1 && (
        <div className="kb-faq-pager">
          <button type="button" className="kb-btn ghost" disabled={page <= 1} onClick={() => setPage(page - 1)}>上一页</button>
          <span>{page} / {pages}</span>
          <button type="button" className="kb-btn ghost" disabled={page >= pages} onClick={() => setPage(page + 1)}>下一页</button>
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
    <div className="kb-mini-modal kb-mini-lg" role="dialog" aria-modal="true" aria-label={entry ? '编辑问答' : '新建问答'}>
      <div className="kb-mini-head">
        <strong>{entry ? '编辑问答' : '新建问答'}</strong>
        <button type="button" className="kb-mini-x" onClick={onClose}>✕</button>
      </div>
      <div className="kb-mini-body kb-faq-editor">
        <label className="kb-mini-label">标准问题 <em>*</em></label>
        <input
          className="kb-input"
          placeholder="用户最常这样问…"
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          autoFocus
        />

        <label className="kb-mini-label">相似问法（每行一条）</label>
        <textarea
          className="kb-input kb-mini-textarea kb-faq-textarea"
          placeholder={'换一种说法\n再换一种'}
          value={similars}
          onChange={(e) => setSimilars(e.target.value)}
          rows={3}
        />

        <label className="kb-mini-label">否定问法（每行一条，命中则不返回本答案）</label>
        <textarea
          className="kb-input kb-mini-textarea kb-faq-textarea"
          placeholder="容易混淆但不应命中的问法"
          value={negatives}
          onChange={(e) => setNegatives(e.target.value)}
          rows={2}
        />

        <label className="kb-mini-label">答案（每行一条）<em>*</em></label>
        <textarea
          className="kb-input kb-mini-textarea"
          placeholder="回答内容"
          value={answers}
          onChange={(e) => setAnswers(e.target.value)}
          rows={6}
        />

        <div className="kb-faq-editor-row">
          <div>
            <label className="kb-mini-label">标签</label>
            <select
              className="kb-select"
              value={tagId ?? ''}
              onChange={(e) => setTagId(e.target.value ? Number(e.target.value) : null)}
            >
              <option value="">无标签</option>
              {tags.map((t) => (
                <option key={t.id} value={t.id}>{t.name}</option>
              ))}
            </select>
          </div>
          <label className="kbs-inline">
            <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
            <span>启用</span>
          </label>
          <label className="kbs-inline">
            <input type="checkbox" checked={recommended} onChange={(e) => setRecommended(e.target.checked)} />
            <span>推荐</span>
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
