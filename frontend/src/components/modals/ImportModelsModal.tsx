/**
 * ImportModelsModal — 拉取提供商的远端模型清单并批量导入。
 *
 * <p><b>WHY 存在</b>：用户添加提供商后要一个一个手填模型（每个 7+ 字段），而提供商的
 * `GET {baseUrl}/models` 已经能给出完整清单 —— 此前这个请求只在「测试连接」里跑过一趟，
 * 响应体被丢弃（见 {@code ProviderService.test()}）。本弹窗把那次白跑的响应留下来用。
 *
 * <p><b>设计取舍</b>（2026-11-03 用户裁定）：
 * <ul>
 *   <li><b>方案 B：勾选 + 智能预填</b> —— 拉回来默认全选，预填推断值；
 *       用户可直接「导入 N 个」，也可取消勾选 / 展开改字段。</li>
 *   <li><b>严格二选一</b>：同一个模型 id 若库里已有，该行置灰标「已存在，跳过」，
 *       <b>不覆盖</b>用户的已有配置。</li>
 *   <li><b>接口给的 vs 默认值，要标出来</b>：上下文窗口直接决定压缩阈值（填大了撞上限、
 *       填小了过早压缩），用户看到 1M 会以为是真的 ⇒ 界面上必须区分来源。</li>
 * </ul>
 *
 * <p><b>默认值从哪来</b>：与后端 {@code ModelService.create} 的缺省保持一致
 * （maxContextTokens=1048576 / maxTokens=384000），⛔ 不维护「模型名 → 窗口」映射表
 * —— 那会过期，且与提供商实际部署不一致。
 */
import { useEffect, useMemo, useState } from 'react'
import { providerApi } from '@/api/providers'
import { ApiError } from '@/api/rest'
import type { CreateModelRequest, ModelType, Provider, RemoteModel } from '@/api/types'
import { brandTagFromName, tagToClass } from '@/data'

/** 与后端 ModelService.create 的缺省一致（模型表单的 placeholder 也是这两个值）。 */
const DEFAULT_MAX_CONTEXT_TOKENS = 1_048_576   // 1M
const DEFAULT_MAX_TOKENS = 384_000

/**
 * 类型推断：模型名里带 embed / rerank 线索 ⇒ 对应类型，否则按「对话」。
 *
 * <p>⚠️ 这是**启发式**，故界面上标「未识别」让人确认（见 design 稿 §默认字段表）。
 */
function inferModelType(name: string): { type: ModelType; confident: boolean } {
  const n = name.toLowerCase()
  if (n.includes('embed')) return { type: 'embedding', confident: true }
  if (n.includes('rerank') || n.includes('re-rank')) return { type: 'rerank', confident: true }
  if (n.includes('vision') || n.includes('vl') || n.includes('multimodal')) {
    return { type: 'multimodal', confident: true }
  }
  return { type: 'chat', confident: false }
}

/** 一行待导入的模型（含用户可改的字段）。 */
interface DraftModel {
  /** 远端 id（写库时的 name） */
  id: string
  /** 显示名（默认取远端 name，缺省用 id） */
  alias: string
  type: ModelType
  /** 类型是否由名字推断而来（false = 需人工确认） */
  typeInferred: boolean
  maxTokens: number
  maxContextTokens: number
  /** 上下文窗口是否来自接口（false = 用了默认值） */
  ctxFromApi: boolean
  /** 输出上限是否来自接口 */
  maxFromApi: boolean
  /** 库里已有同名模型 ⇒ 置灰跳过 */
  existing: boolean
  /** 用户是否勾选导入 */
  selected: boolean
}

interface ImportModelsModalProps {
  provider: Provider
  /** 逐个创建模型（复用父级的 createModel，错误处理与 toast 由父级统一管） */
  createModel: (providerId: string, req: CreateModelRequest) => Promise<void>
  onClose: () => void
  showToast: (msg: string, type?: 'success' | 'info') => void
}

type FetchState =
  | { phase: 'loading' }
  | { phase: 'ready'; message: string }
  | { phase: 'failed'; message: string }

export function ImportModelsModal({ provider, createModel, onClose, showToast }: ImportModelsModalProps) {
  const [state, setState] = useState<FetchState>({ phase: 'loading' })
  const [drafts, setDrafts] = useState<DraftModel[]>([])
  const [expanded, setExpanded] = useState<Set<string>>(new Set())
  const [importing, setImporting] = useState(false)
  const [filter, setFilter] = useState('')

  // ---- 拉取（挂载即发）----
  //
  // ⚠️ **不能用 `fetchedRef` 守卫「只发一次」**：React StrictMode 下 effect 会跑两遍
  //   （setup → cleanup → setup）。第一遍把守卫置 true，cleanup 又把 alive 置 false，
  //   第二遍见守卫已是 true 直接 return ⇒ **请求永远发不出去**（实测：弹窗永远停在
  //   「正在从提供商读取可用模型…」，performance 里一条 /api 请求都没有）。
  //
  //   正确做法：**每次 effect 都发请求**，用 cleanup 里的 `alive=false` 丢弃过期响应。
  //   StrictMode 的第二遍会发出**第二个**请求 —— 这是可接受的（幂等只读 GET），
  //   且第一遍的响应会被 alive 守卫丢弃。
  useEffect(() => {
    let alive = true
    providerApi
      .remoteModels(provider.id)
      .then((res) => {
        if (!alive) return
        if (!res.ok) {
          setState({ phase: 'failed', message: res.message || '读取失败' })
          return
        }
        const existingNames = new Set(provider.models.map((m) => m.name))
        const list: DraftModel[] = res.models.map((rm: RemoteModel) => {
          const inferred = inferModelType(rm.id)
          const exists = existingNames.has(rm.id)
          return {
            id: rm.id,
            alias: rm.name ?? rm.id,
            type: inferred.type,
            typeInferred: !inferred.confident,
            maxTokens: rm.maxOutputTokens ?? DEFAULT_MAX_TOKENS,
            maxContextTokens: rm.contextWindow ?? DEFAULT_MAX_CONTEXT_TOKENS,
            ctxFromApi: rm.contextWindow != null,
            maxFromApi: rm.maxOutputTokens != null,
            existing: exists,
            selected: !exists,
          }
        })
        setDrafts(list)
        setState({ phase: 'ready', message: res.message || `读取到 ${list.length} 个模型` })
      })
      .catch((e) => {
        if (!alive) return
        const msg = e instanceof ApiError ? e.userMessage() : String(e)
        setState({ phase: 'failed', message: msg })
      })
    return () => { alive = false }
    // ⚠️ provider.models 只在**导入后**才变（createModel 会刷新列表）⇒ 那时重拉是想要的
    //   （刷新「已存在」标记）。但它每次都是**新数组**（store 每次 setList 都造新的）
    //   ⇒ 依赖它会导致弹窗开着时反复重拉。故只用 provider.id 触发拉取，
    //   「已存在」标记在**打开弹窗那一刻**快照即可（导入过程中不会变）。
  }, [provider.id])

  const providerTag = brandTagFromName(provider.name) ?? 'other'

  const visible = useMemo(() => {
    const q = filter.trim().toLowerCase()
    if (!q) return drafts
    return drafts.filter((d) => d.id.toLowerCase().includes(q) || d.alias.toLowerCase().includes(q))
  }, [drafts, filter])

  const selectable = useMemo(() => drafts.filter((d) => !d.existing), [drafts])
  const selectedCount = selectable.filter((d) => d.selected).length
  const skippedCount = drafts.length - selectable.length

  const patch = (id: string, p: Partial<DraftModel>) => {
    setDrafts((prev) => prev.map((d) => (d.id === id ? { ...d, ...p } : d)))
  }

  const setAll = (on: boolean) => {
    setDrafts((prev) => prev.map((d) => (d.existing ? d : { ...d, selected: on })))
  }

  const onlyChat = () => {
    setDrafts((prev) => prev.map((d) => (d.existing ? d : { ...d, selected: d.type === 'chat' || d.type === 'text' })))
  }

  const toggleExpand = (id: string) => {
    setExpanded((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  const doImport = async () => {
    const targets = selectable.filter((d) => d.selected)
    if (targets.length === 0) return
    setImporting(true)
    const failed: string[] = []
    for (const d of targets) {
      const req: CreateModelRequest = {
        name: d.id,
        alias: d.alias || null,
        tag: providerTag,
        desc: '',
        type: d.type,
        maxTokens: d.maxTokens,
        maxContextTokens: d.maxContextTokens,
        temperature: null,
        topP: null,
        think: null,
        extraBody: null,
        enabled: true,
      }
      try {
        await createModel(provider.id, req)
      } catch {
        failed.push(d.id)
      }
    }
    setImporting(false)
    if (failed.length > 0) {
      showToast(`已导入 ${targets.length - failed.length} 个 · ${failed.length} 个失败：${failed.slice(0, 3).join(', ')}`, 'info')
    } else {
      showToast(`已导入 ${targets.length} 个模型`, 'success')
    }
    onClose()
  }

  return (
    <div className="fm-backdrop" onClick={onClose}>
      <div className="fm-modal im-modal" onClick={(e) => e.stopPropagation()}>
        <div className="fm-header">
          <span className="fm-title">导入模型</span>
          <span className="fm-subtitle">{provider.name} · {provider.baseUrl}</span>
        </div>

        <div className="fm-body">
          {state.phase === 'loading' && (
            <div className="im-status">
              <span className="im-spin" aria-hidden />
              <span>正在从提供商读取可用模型…</span>
            </div>
          )}

          {state.phase === 'failed' && (
            <>
              <div className="im-status error">✗ {state.message}</div>
              <div className="fm-field-hint">
                该提供商可能不支持模型列表接口，或 API Key 只对推理端点有权限。
                你仍然可以在「提供商」列表里点「+ 添加模型」手动添加。
              </div>
            </>
          )}

          {state.phase === 'ready' && (
            <>
              <div className="im-status ok">✓ {state.message}</div>

              {drafts.length === 0 ? (
                <div className="fm-model-empty">该提供商未返回任何模型</div>
              ) : (
                <>
                  <div className="im-toolbar">
                    <button type="button" className="fm-btn" onClick={() => setAll(true)}>全选</button>
                    <button type="button" className="fm-btn" onClick={() => setAll(false)}>全不选</button>
                    <button type="button" className="fm-btn" onClick={onlyChat}>仅对话模型</button>
                    <input
                      className="fm-input im-search"
                      placeholder="搜索模型"
                      value={filter}
                      onChange={(e) => setFilter(e.target.value)}
                    />
                    <span className="im-count">
                      {selectedCount} / {selectable.length} 已选
                      {skippedCount > 0 && ` · ${skippedCount} 个已存在`}
                    </span>
                  </div>

                  <div className="im-list">
                    {visible.map((d) => (
                      <div key={d.id} className={`im-row${d.selected && !d.existing ? ' on' : ''}${d.existing ? ' skipped' : ''}`}>
                        <div className="im-row-main">
                          <input
                            type="checkbox"
                            checked={d.selected && !d.existing}
                            disabled={d.existing}
                            onChange={(e) => patch(d.id, { selected: e.target.checked })}
                            aria-label={`选择 ${d.id}`}
                          />
                          <span className={`model-tag ${tagToClass(providerTag)}`}>{providerTag}</span>
                          <span className="im-name" title={d.id}>{d.alias}</span>
                          <span className="im-id">{d.id}</span>
                          <span className="im-type">{d.typeInferred ? '类型待确认' : d.type}</span>
                          {d.existing
                            ? <span className="im-badge">已存在，跳过</span>
                            : <span className="im-badge src">上下文 {d.ctxFromApi ? '接口' : '默认'} · 输出 {d.maxFromApi ? '接口' : '默认'}</span>}
                          <button
                            type="button"
                            className="im-expand"
                            onClick={() => toggleExpand(d.id)}
                            aria-expanded={expanded.has(d.id)}
                          >{expanded.has(d.id) ? '收起' : '改字段'}</button>
                        </div>

                        {expanded.has(d.id) && (
                          <div className="im-edit">
                            <div className="im-grid">
                              <label className="fm-field">
                                <span className="fm-field-label">模型名称</span>
                                <input className="fm-input mono" value={d.id} readOnly />
                              </label>
                              <label className="fm-field">
                                <span className="fm-field-label">显示名</span>
                                <input
                                  className="fm-input"
                                  value={d.alias}
                                  onChange={(e) => patch(d.id, { alias: e.target.value })}
                                />
                              </label>
                              <label className="fm-field">
                                <span className="fm-field-label">类型</span>
                                <select
                                  className="fm-select"
                                  value={d.type}
                                  onChange={(e) => patch(d.id, { type: e.target.value as ModelType, typeInferred: false })}
                                >
                                  <option value="chat">Chat（对话）</option>
                                  <option value="text">Text（补全）</option>
                                  <option value="vision">Vision（图像）</option>
                                  <option value="multimodal">Multimodal（多模态）</option>
                                  <option value="embedding">Embedding（向量）</option>
                                  <option value="rerank">Rerank（重排）</option>
                                </select>
                              </label>
                              <label className="fm-field">
                                <span className="fm-field-label">
                                  max tokens
                                  <span className={`im-src ${d.maxFromApi ? 'api' : 'def'}`}>{d.maxFromApi ? '接口' : '默认值'}</span>
                                </span>
                                <input
                                  className="fm-input"
                                  type="number"
                                  value={d.maxTokens}
                                  onChange={(e) => patch(d.id, { maxTokens: Number(e.target.value) || DEFAULT_MAX_TOKENS })}
                                />
                              </label>
                              <label className="fm-field">
                                <span className="fm-field-label">
                                  上下文窗口
                                  <span className={`im-src ${d.ctxFromApi ? 'api' : 'def'}`}>{d.ctxFromApi ? '接口' : '默认值'}</span>
                                </span>
                                <input
                                  className="fm-input"
                                  type="number"
                                  value={d.maxContextTokens}
                                  onChange={(e) => patch(d.id, { maxContextTokens: Number(e.target.value) || DEFAULT_MAX_CONTEXT_TOKENS })}
                                />
                              </label>
                            </div>
                            {!d.ctxFromApi && (
                              <div className="fm-field-hint">
                                该提供商未返回窗口信息，已用默认值 1M。各服务端默认不同，建议自行确认。
                              </div>
                            )}
                          </div>
                        )}
                      </div>
                    ))}
                  </div>
                </>
              )}
            </>
          )}
        </div>

        <div className="fm-footer">
          <span className="spacer"></span>
          <button className="fm-btn" onClick={onClose} disabled={importing}>
            {state.phase === 'failed' ? '关闭' : '取消'}
          </button>
          <button
            className="fm-btn primary"
            onClick={() => void doImport()}
            disabled={importing || selectedCount === 0}
          >
            {importing ? '导入中…' : `导入 ${selectedCount} 个模型`}
          </button>
        </div>
      </div>
    </div>
  )
}
