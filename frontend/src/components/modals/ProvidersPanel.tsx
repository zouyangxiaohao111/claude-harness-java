import { useRef, useState } from 'react'
import type { Model, Provider } from '@/api/types'
import { Icon } from '@/icons'
import { FormModal, type FormSection } from '@/components/ui/FormModal'
import { ModelFormModal } from '@/components/ui/ModelFormModal'
import { ImportModelsModal } from '@/components/modals/ImportModelsModal'
import { KvEditor } from '@/components/common/KvEditor'
import { brandTagFromName, tagToClass } from '@/data'
import type { UseProviders } from '@/hooks/useProviders'
import { ApiError } from '@/api/rest'
import type { CreateModelRequest, CreateProviderRequest, UpdateModelRequest, UpdateProviderRequest } from '@/api/types'

interface ProvidersPanelProps {
  /** 后端拉取的 providers 列表（已嵌套 models） */
  providers: Provider[]
  /** 真实后端 CRUD API */
  providersApi: UseProviders
  showToast: (msg: string, type?: 'success' | 'info') => void
}

const EMPTY_PROVIDER: Provider = {
  id: '',
  name: '',
  type: 'openai_compatible',
  baseUrl: '',
  apiKeyMasked: '',
  extraHeaders: null,
  enabled: true,
  models: [],
}

const EMPTY_MODEL: Model = {
  id: '',
  name: '',
  alias: null,
  tag: 'DS',
  desc: '',
  type: 'chat',
  maxTokens: 384000,
  temperature: 1.0,
  topP: null,
  think: '',
  /** 模型级额外请求体（JSON 对象字符串）· 空 = 无额外参数 */
  extraBody: '',
  enabled: true,
  maxContextTokens: null,
}

/** L4b-2：新建 provider 的「首个模型」name 预填提示（anthropic → claude-sonnet-4-5；其余留空，由引导文案示例引导） */
const firstModelNameHint = (p: Provider): string => {
  if (p.models.length > 0) return ''
  const isAnthropic = p.type === 'anthropic' || (p.baseUrl ?? '').toLowerCase().includes('api.anthropic.com')
  return isAnthropic ? 'claude-sonnet-4-5' : ''
}

/**
 * 新模型的 tag（提供商标识）。
 *
 * <p>[2026-11-01] 「提供商标识」只读块已从模型弹窗移除 ⇒ tag 不再由表单携带固定默认值
 * （那会给任何 provider 加模型都写成 `DS`）。改按 provider 名推断品牌，
 * 再退化到模型名，最后才用历史默认值。
 */
const tagForNewModel = (providerName: string, modelName: string, fallback: string): string =>
  brandTagFromName(providerName) ?? brandTagFromName(modelName) ?? fallback


/**
 * 协议类型选项（分段控件 · 三种模式）。
 *
 * <p><b>为什么不用原生下拉 + 原始枚举值</b>：此前下拉里直接写着 `openai_compatible` ——
 * 那是给程序看的 wire 值，用户根本不知道它是什么。分段控件给出中文名 + 端点路径，
 * wire 值仍是枚举本身（后端契约不变）。
 *
 * <p><b>三种模式</b>：
 * <ul>
 *   <li><b>OpenAI 兼容</b> —— /v1/chat/completions（绝大多数第三方网关）</li>
 *   <li><b>Anthropic 原生</b> —— /v1/messages（Claude 官方或原生兼容端点）</li>
 *   <li><b>OpenAI Responses</b> —— /v1/responses。
 *       ⚠️ <b>当前后端未实现</b>（后端 {@code ProviderType} 枚举无此值，
 *       {@code ProviderService.toProviderDto} 走 {@code ProviderType.valueOf(...)}
 *       ⇒ 真提交会 500）⇒ 界面上<b>显示但置灰不可选</b>，让用户知道「有这一种、只是还没支持」，
 *       而不是把整条能力从界面上抹掉。</li>
 * </ul>
 */
const PROTOCOL_OPTIONS = [
  { value: 'openai_compatible', label: 'OpenAI 兼容', desc: '/v1/chat/completions' },
  { value: 'anthropic', label: 'Anthropic 原生', desc: '/v1/messages' },
  { value: 'openai_response', label: 'OpenAI Responses', desc: '/v1/responses · 暂不支持', disabled: true },
]

/**
 * Base URL 提示的文案表 · 按协议类型取值（新建 / 编辑共用）。
 *
 * <p><b>为什么必须写在界面上</b>：后端<b>从不</b>替用户补或删 `/v1` —— 只 trim 去掉尾部斜杠
 * （`OpenAiSdkProvider.normalizeBaseUrl` / `AnthropicSdkProvider.normalizeBaseUrl`），
 * 路径由各协议<b>固定拼接</b>：OpenAI 兼容 = 地址 + `/chat/completions`，
 * Anthropic = 地址 + `/v1/messages`。⇒ 两种协议要求的结尾格式<b>正好相反</b>，
 * 填错只会拿到 404，而后端文案是「baseUrl 不正确（404）」，看不出该带还是不该带。
 *
 * <p><b>为什么每条都写「请求 {base}/…」</b>：只写「填到 /v1 为止」用户仍要自己拼一次才能确认；
 * 把最终路径摊开，用户拿自己的地址一对照就知道对不对。
 *
 * <p>导出仅为单测能直接对真值表断言 —— 走 UI 需要构造一个 `type: 'openai_response'` 的
 * Provider，而 `Provider.type` 是后端枚举子集（无此值），只能 cast 硬造，那是在测不存在的世界。
 */
export const BASE_URL_HINT: Record<string, string> = {
  openai_compatible: '填到 /v1 为止 · 请求 {base}/chat/completions',
  anthropic: '只填域名、不带 /v1 · 请求 {base}/v1/messages',
  // 后端 ProviderType 尚无此值（当前置灰不可选），文案先备好，避免将来放开时漏一条。
  openai_response: '填到 /v1 为止 · 请求 {base}/responses',
}

/** 未知 / 空类型（新建表单初值、历史数据）按 OpenAI 兼容处理 —— 那也正是后端保存时的推断结果。 */
export const baseUrlHint = (type: unknown): string => {
  const t = typeof type === 'string' ? type : ''
  return BASE_URL_HINT[t] ?? BASE_URL_HINT.openai_compatible
}

/**
 * 「自定义请求头」section（新建 / 编辑共用）。
 *
 * <p>值挂在 FormModal 的 `custom` 字段上（键名对齐 `Provider.extraHeaders`），由 KvEditor 维护；
 * 两个请求构造器再把 `p.extraHeaders` 发出去 —— 少任何一环，用户就改不动这个字段。
 */
const headersSection = (): FormSection => ({
  title: '自定义请求头',
  fields: [
    {
      type: 'custom',
      name: 'extraHeaders',
      label: '请求头',
      hint: '随请求一并发送的额外 HTTP 请求头；值里可写 ${session_id} 表示当前会话 ID',
      // value 是 unknown（FormModal 刻意不用 any 逼出显式收窄）；KvEditor 全空产出 {}
      render: (v, set) => <KvEditor value={v as Record<string, string> | null} onChange={set} />,
    },
  ],
})

/**
 * ProvidersPanel · Phase 7 联调版
 *
 * <p>所有 CRUD 走真实后端 API（useProviders hook）。组件本身不持有 providers 列表副本，
 * 完全从 `providers` prop 渲染（hook 自己管理 state）。
 *
 * <p>Form 数据约定：表单中名为 `apiKeyMasked` 的字段实际承载**原始 key**（用户输入的明文）。
 * Save 时把它当作 `apiKey` 发给后端，由后端脱敏后存库。
 */
export function ProvidersPanel({ providersApi, showToast }: ProvidersPanelProps) {
  const { list, loading, error, createProvider, updateProvider, deleteProvider, toggleProvider, createModel, updateModel, deleteModel } = providersApi

  const [editingProvider, setEditingProvider] = useState<Provider | null>(null)
  const [addingProvider, setAddingProvider] = useState(false)
  const [editingModel, setEditingModel] = useState<{ providerId: string; model: Model } | null>(null)
  const [addingModelFor, setAddingModelFor] = useState<{ providerId: string; prefillName: string } | null>(null)
  /** 拉取远端模型并导入（保存提供商后自动打开 · 见 onAddProvider） */
  const [importingModelsFor, setImportingModelsFor] = useState<Provider | null>(null)
  const [pending, setPending] = useState(false)

  // ---- Provider CRUD 包装（带错误处理 + toast） ----
  const wrap = async (fn: () => Promise<unknown>, successMsg: string) => {
    setPending(true)
    try {
      await fn()
      showToast(successMsg, 'success')
    } catch (e) {
      const msg = e instanceof ApiError ? e.userMessage() : String(e)
      showToast(`${msg}`, 'info')
    } finally {
      setPending(false)
    }
  }

  const onToggle = (id: string, currentlyEnabled: boolean) => {
    const p = list.find((x) => x.id === id)
    void wrap(
      () => toggleProvider(id, !currentlyEnabled),
      `${p?.name} ${!currentlyEnabled ? '已启用' : '已停用'}`
    )
  }

  const onRemoveProvider = (id: string) => {
    const p = list.find((x) => x.id === id)
    if (!confirm(`删除提供商 "${p?.name}"？所有关联模型也会被删除。`)) return
    void wrap(() => deleteProvider(id), '已删除提供商')
  }

  // 表单提交：apiKeyMasked 字段是 raw key
  const onSaveProvider = (p: Provider) => {
    const inferred = (p.baseUrl ?? '').toLowerCase().includes('api.anthropic.com') ? 'anthropic' : 'openai_compatible'
    const req: UpdateProviderRequest = {
      name: p.name,
      type: p.type === 'anthropic' ? 'anthropic' : inferred,
      baseUrl: p.baseUrl,
      // 编辑时若 key 字段未被修改（仍为后端掩码 sk-****）则不发送 apiKey，
      // 避免把掩码字符串当明文存库导致 API 401（后端 req.apiKey null 时不更新）
      apiKey: p.apiKeyMasked === editingProvider?.apiKeyMasked ? undefined : p.apiKeyMasked,
      enabled: p.enabled,
      // 自定义请求头：与上面的 apiKey 取舍**无关**（掩码特例只针对 key，不能波及 header）。
      // 清空契约：{} = 显式清空（后端写 NULL）、null/缺字段 = 不触碰。KvEditor 全空产出 {}，
      // 故「删光所有 header」可表达；unset（表单未初始化出该键）仍传 undefined → 不触碰。
      extraHeaders: p.extraHeaders,
    }
    void wrap(async () => {
      await updateProvider(p.id, req)
      setEditingProvider(null)
    }, `已更新: ${p.name}`)
  }

  const onAddProvider = (p: Provider) => {
    // 协议类型：显式 anthropic 用 anthropic；否则按 baseUrl 智能推断（含 api.anthropic.com → anthropic）
    const inferred = (p.baseUrl ?? '').toLowerCase().includes('api.anthropic.com') ? 'anthropic' : 'openai_compatible'
    const req: CreateProviderRequest = {
      name: p.name,
      type: p.type === 'anthropic' ? 'anthropic' : inferred,
      baseUrl: p.baseUrl,
      apiKey: p.apiKeyMasked,    // raw key
      enabled: p.enabled ?? true,
      // 自定义请求头（CreateProviderRequest.extraHeaders 不收 null，全空 → 不发该字段）
      extraHeaders: p.extraHeaders ?? undefined,
    }
    void wrap(async () => {
      const created = await createProvider(req)
      setAddingProvider(false)
      // L4b-2 首启引导信号：带 created 详情（内含 models），引导据此判断是否需补插「添加模型」步
      window.dispatchEvent(new CustomEvent('nexusai:provider-saved', { detail: created }))
      // [2026-11-03 用户裁定] **保存后不自动拉模型** —— 保存就保存，导入是另一个明确动作
      //   （用户原话：「点击导入模型 自动导入 保存就跟之前一样 不要自动触发吧」）。
      //   用户要的是「表单里有个『导入模型』按钮，想用就点」⇒ 按钮在表单的 footer 上，
      //   见 addingProvider 的 footerActions。
      //
      //   ⚠️ 但**点了「导入模型」**时是要保存 + 拉取的（拉取端点按 provider id ⇒ 得先有 id）。
      //   那条路径在 onAddProviderAndImport 里。
      if (importAfterSaveRef.current) {
        importAfterSaveRef.current = false
        setImportingModelsFor(created)
      }
    }, `已添加: ${p.name}`)
  }

  /**
   * 「导入模型」按钮：**先保存提供商 → 立刻打开导入弹窗**（拉取端点按 provider id ⇒ 必须先有 id）。
   *
   * <p>⚠️ 与「保存」按钮的行为差异：保存 = 只存；导入模型 = 存完接着拉清单。
   * 两者都走同一个 `onAddProvider`（避免两套建提供商的逻辑漂移），靠
   * {@link importAfterSaveRef} 这个「一次性意图」标记区分。
   */
  const importAfterSaveRef = useRef(false)
  const onAddProviderAndImport = (p: Provider) => {
    importAfterSaveRef.current = true
    onAddProvider(p)
  }

  // Model CRUD
  const onSaveModel = (_providerId: string, m: Model) => {
    const req: UpdateModelRequest = {
      name: m.name,
      alias: m.alias || null,
      desc: m.desc,
      type: m.type,
      maxTokens: m.maxTokens,
      maxContextTokens: m.maxContextTokens ?? null,
      temperature: m.temperature,
      topP: m.topP,
      think: m.think || null,
      // [2026-10-22] 模型级额外请求体（逐键合并进请求体；空 → null 不写）
      extraBody: m.extraBody || null,
      // ⚠️ 2026-11-14 修复断链：这几项在表单里能填，但此前请求体没带 ⇒ 永远落不了库。
      //    清空（undefined/null）按 0 提交 = 回到默认值（PATCH 语义里 null 是「不改」，
      //    不传 0 就无法把已配的值改回默认）。
      embeddingDimensions: m.embeddingDimensions ?? 0,
      embeddingBatchSize: m.embeddingBatchSize ?? 0,
      maxConcurrency: m.maxConcurrency ?? 0,
      // ⚠️ rerank 两项保持 null（PATCH null = 不改）：0 / 空串在 rerank 里不是「默认值」，
      //    写 0 会被当成真的 topN=0。填了值才落库。
      rerankTopN: m.rerankTopN ?? null,
      rerankInstruct: m.rerankInstruct ?? null,
      enabled: m.enabled,
    }
    void wrap(async () => {
      await updateModel(m.id, req)
      setEditingModel(null)
    }, `已更新模型: ${m.name}`)
  }

  const onAddModel = (providerId: string, m: Model) => {
    const providerName = list.find((x) => x.id === providerId)?.name ?? ''
    const req: CreateModelRequest = {
      name: m.name,
      alias: m.alias || null,
      // [2026-11-01] 提供商标识不再由表单提供 ⇒ 按 provider / 模型名推断品牌
      tag: tagForNewModel(providerName, m.name, m.tag),
      desc: m.desc,
      type: m.type,
      maxTokens: m.maxTokens,
      maxContextTokens: m.maxContextTokens ?? null,
      temperature: m.temperature,
      topP: m.topP,
      think: m.think || null,
      // [2026-10-22] 模型级额外请求体（逐键合并进请求体；空 → null 不写）
      extraBody: m.extraBody || null,
      // ⚠️ 2026-11-14 修复断链：表单可填但此前请求体没带（同更新路径）。
      embeddingDimensions: m.embeddingDimensions ?? null,
      embeddingBatchSize: m.embeddingBatchSize ?? null,
      maxConcurrency: m.maxConcurrency ?? null,
      rerankTopN: m.rerankTopN ?? null,
      rerankInstruct: m.rerankInstruct ?? null,
      enabled: m.enabled,
    }
    void wrap(async () => {
      await createModel(providerId, req)
      setAddingModelFor(null)
      // L4b-2 首启引导信号：模型真实创建成功后派发，引导据此从「添加模型」步进入主模型绑定
      window.dispatchEvent(new CustomEvent('nexusai:model-added'))
    }, `已添加模型: ${m.name}`)
  }

  const onRemoveModel = (_providerId: string, modelId: string) => {
    const m = list.flatMap((p) => p.models).find((x) => x.id === modelId)
    if (!confirm(`删除模型 "${m?.name}"？`)) return
    void wrap(() => deleteModel(modelId), '已删除模型')
  }

  return (
    <div className="provider-list">
      <div className="settings-row" style={{ borderBottom: 'none', paddingBottom: 4 }}>
        <div>
          <div className="settings-row-label">模型提供商</div>
          <div className="settings-row-desc">
            每个提供商的 API 密钥与可用模型
            {loading && ' · 加载中…'}
            {error && <span style={{ color: 'var(--error)', marginLeft: 8 }}>· {error}</span>}
          </div>
        </div>
        <button
          className="settings-add-btn"
          data-tour="add-provider"
          onClick={() => setAddingProvider(true)}
          disabled={pending}
        >
          + 添加提供商
        </button>
      </div>

      {list.length === 0 && !loading && !error && (
        <div className="fm-model-empty" style={{ padding: '32px 12px', textAlign: 'center' }}>
          暂无提供商 · 点击「+ 添加提供商」开始
        </div>
      )}

      {list.map((p) => (
        <div key={p.id} className="provider-card">
          <div className="provider-header">
            <div className="provider-info">
              <div className="provider-logo">{p.name.slice(0, 1).toUpperCase()}</div>
              <div>
                <div className="provider-name">{p.name}</div>
                <div className="provider-meta">
                  {p.baseUrl} key: {p.apiKeyMasked} · {p.models.length} 模型
                </div>
              </div>
            </div>
            <div className={`toggle ${p.enabled ? 'active' : ''}`} onClick={() => onToggle(p.id, p.enabled)} title={p.enabled ? '停用' : '启用'} />
            <button className="icon-btn icon-btn-edit" title="编辑提供商" onClick={() => setEditingProvider(p)}><Icon.Edit /></button>
            <button className="icon-btn icon-btn-del" title="删除提供商" onClick={() => onRemoveProvider(p.id)}>
              <Icon.Trash />
            </button>
          </div>

          {p.models.length === 0 ? (
            <div className="fm-model-empty">该提供商暂无关联模型</div>
          ) : (
            p.models.map((m) => (
              <div key={m.id} className="model-item">
                <div className="model-left">
                  <span className={`model-tag ${tagToClass(m.tag)}`}>{m.tag}</span>
                  <span className="model-name">{m.name}</span>
                </div>
                <div className="model-actions">
                  <button className="icon-btn icon-btn-edit" title="编辑模型" onClick={() => setEditingModel({ providerId: p.id, model: m })}><Icon.Edit /></button>
                  <button className="icon-btn icon-btn-del" title="移除模型" onClick={() => onRemoveModel(p.id, m.id)}>
                    <Icon.Trash />
                  </button>
                </div>
              </div>
            ))
          )}

          <div className="provider-model-actions">
            {/* [2026-11-03] 「从提供商读取」：拉远端清单批量导入（比逐个手填快得多） */}
            <button
              className="add-model-btn"
              title="从提供商读取可用模型并批量导入"
              onClick={() => {
                const target = list.find((x) => x.id === p.id)
                if (target) setImportingModelsFor(target)
              }}
            >从提供商读取</button>
            <button
              className="add-model-btn"
              data-tour="add-model"
              onClick={() => setAddingModelFor({ providerId: p.id, prefillName: firstModelNameHint(p) })}
            >+ 添加模型</button>
          </div>
        </div>
      ))}

      {editingProvider && (
        <FormModal<Provider>
          title="编辑提供商"
          subtitle={editingProvider.name}
          initial={editingProvider}
          sections={[
            {
              title: '基础信息',
              fields: [
                { type: 'text', name: 'name', label: '名称', placeholder: 'e.g. DeepSeek' },
                { type: 'radio-segments', name: 'type', label: '协议类型', options: PROTOCOL_OPTIONS, hint: '决定请求走哪个 API 端点 · baseUrl 含 api.anthropic.com 会自动推断为 Anthropic' },
                { type: 'text', name: 'baseUrl', label: 'Base URL', placeholder: 'https://api.example.com/v1', hint: (v) => baseUrlHint(v.type) },
                { type: 'password', name: 'apiKeyMasked', label: 'API Key', placeholder: 'sk-****abcd', hint: '完整 key，后端脱敏后存储' },
              ],
            },
            {
              title: '行为',
              fields: [
                { type: 'toggle', name: 'enabled', label: '启用', hint: '关闭后该提供商不出现在选择器中' },
              ],
            },
            headersSection(),
          ]}
          onSave={onSaveProvider}
          onCancel={() => setEditingProvider(null)}
          destructiveLabel="移除此提供商 · 不可恢复"
          onDestructive={() => { onRemoveProvider(editingProvider.id); setEditingProvider(null) }}
        />
      )}

      {addingProvider && (
        <FormModal<Provider>
          title="添加提供商"
          subtitle="填写后保存，立即可用"
          initial={EMPTY_PROVIDER}
          sections={[
            {
              title: '基础信息',
              fields: [
                { type: 'text', name: 'name', label: '名称', placeholder: 'e.g. OpenAI' },
                { type: 'radio-segments', name: 'type', label: '协议类型', options: PROTOCOL_OPTIONS, hint: '决定请求走哪个 API 端点 · baseUrl 含 api.anthropic.com 会自动推断为 Anthropic' },
                { type: 'text', name: 'baseUrl', label: 'Base URL', placeholder: 'https://api.openai.com/v1', hint: (v) => baseUrlHint(v.type) },
                { type: 'password', name: 'apiKeyMasked', label: 'API Key', placeholder: 'sk-****abcd' },
              ],
            },
            {
              title: '行为',
              fields: [
                { type: 'toggle', name: 'enabled', label: '立即启用' },
              ],
            },
            headersSection(),
          ]}
          onSave={onAddProvider}
          onCancel={() => setAddingProvider(false)}
          // [2026-11-03] 「导入模型」= 保存后接着拉远端清单并批量导入。
          //   与「保存」并列放在 footer（不是 body 末尾）—— 用户要能一眼看见并点到它。
          footerActions={(values) => (
            <button
              type="button"
              className="fm-btn"
              disabled={pending}
              title="保存这个提供商，并读取它的可用模型清单供你勾选导入"
              onClick={() => onAddProviderAndImport(values)}
            >导入模型</button>
          )}
        />
      )}

      {editingModel && (
        <ModelFormModal
          initial={editingModel.model}
          providerTag={editingModel.model.tag
            || brandTagFromName(list.find((x) => x.id === editingModel.providerId)?.name ?? '')
            || undefined}
          mode="edit"
          onSave={(m) => onSaveModel(editingModel.providerId, m)}
          onCancel={() => setEditingModel(null)}
          onDelete={() => {
            onRemoveModel(editingModel.providerId, editingModel.model.id)
            setEditingModel(null)
          }}
        />
      )}

      {addingModelFor && (
        <ModelFormModal
          initial={{ ...EMPTY_MODEL, name: addingModelFor.prefillName }}
          providerTag={tagForNewModel(
            list.find((x) => x.id === addingModelFor.providerId)?.name ?? '',
            addingModelFor.prefillName,
            EMPTY_MODEL.tag,
          )}
          mode="create"
          onSave={(m) => onAddModel(addingModelFor.providerId, m)}
          onCancel={() => setAddingModelFor(null)}
        />
      )}

      {/* [2026-11-03] 拉取远端模型清单并导入（保存提供商后自动打开；失败仍可退回手动添加） */}
      {importingModelsFor && (
        <ImportModelsModal
          provider={importingModelsFor}
          createModel={createModel}
          onClose={() => setImportingModelsFor(null)}
          showToast={showToast}
        />
      )}
    </div>
  )
}