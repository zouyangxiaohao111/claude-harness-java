import { useState } from 'react'
import type { Model, Provider } from '@/api/types'
import { FormModal } from '@/components/ui/FormModal'
import { ModelFormModal } from '@/components/ui/ModelFormModal'
import { tagToClass } from '@/data'
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
  enabled: true,
  maxContextTokens: null,
}


const EditIcon = () => (
  <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: 12, height: 12 }}>
    <path d="M9 2L12 5L5 12H2V9L9 2Z" />
  </svg>
)

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
  const [addingModelFor, setAddingModelFor] = useState<string | null>(null)
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
    }
    void wrap(async () => {
      await createProvider(req)
      setAddingProvider(false)
    }, `已添加: ${p.name}`)
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
      enabled: m.enabled,
    }
    void wrap(async () => {
      await updateModel(m.id, req)
      setEditingModel(null)
    }, `已更新模型: ${m.name}`)
  }

  const onAddModel = (providerId: string, m: Model) => {
    const req: CreateModelRequest = {
      name: m.name,
      alias: m.alias || null,
      tag: m.tag,
      desc: m.desc,
      type: m.type,
      maxTokens: m.maxTokens,
      maxContextTokens: m.maxContextTokens ?? null,
      temperature: m.temperature,
      topP: m.topP,
      think: m.think || null,
      enabled: m.enabled,
    }
    void wrap(async () => {
      await createModel(providerId, req)
      setAddingModelFor(null)
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
            <button className="icon-btn" title="编辑提供商" onClick={() => setEditingProvider(p)}><EditIcon /></button>
            <button className="icon-btn" title="删除提供商" onClick={() => onRemoveProvider(p.id)}>
              <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: 13, height: 13 }}><path d="M4 4L10 10M10 4L4 10" /></svg>
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
                  <button className="icon-btn" title="编辑模型" onClick={() => setEditingModel({ providerId: p.id, model: m })}><EditIcon /></button>
                  <button className="icon-btn" title="移除模型" onClick={() => onRemoveModel(p.id, m.id)}>
                    <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: 12, height: 12 }}><path d="M4 4L10 10M10 4L4 10" /></svg>
                  </button>
                </div>
              </div>
            ))
          )}

          <button className="add-model-btn" onClick={() => setAddingModelFor(p.id)}>+ 添加模型</button>
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
                { type: 'select', name: 'type', label: '协议类型', options: [
                  { value: 'openai_compatible', label: 'openai_compatible' },
                  { value: 'anthropic', label: 'anthropic' },
                ], hint: 'baseUrl 含 api.anthropic.com 会推断为 anthropic' },
                { type: 'text', name: 'baseUrl', label: 'Base URL', placeholder: 'https://api.example.com/v1' },
                { type: 'password', name: 'apiKeyMasked', label: 'API Key', placeholder: 'sk-****abcd', hint: '完整 key，后端脱敏后存储' },
              ],
            },
            {
              title: '行为',
              fields: [
                { type: 'toggle', name: 'enabled', label: '启用', hint: '关闭后该提供商不出现在选择器中' },
              ],
            },
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
                { type: 'select', name: 'type', label: '协议类型', options: [
                  { value: 'openai_compatible', label: 'openai_compatible' },
                  { value: 'anthropic', label: 'anthropic' },
                ], hint: 'baseUrl 含 api.anthropic.com 会推断为 anthropic' },
                { type: 'text', name: 'baseUrl', label: 'Base URL', placeholder: 'https://api.openai.com/v1' },
                { type: 'password', name: 'apiKeyMasked', label: 'API Key', placeholder: 'sk-****abcd' },
              ],
            },
            {
              title: '行为',
              fields: [
                { type: 'toggle', name: 'enabled', label: '立即启用' },
              ],
            },
          ]}
          onSave={onAddProvider}
          onCancel={() => setAddingProvider(false)}
        />
      )}

      {editingModel && (
        <ModelFormModal
          initial={editingModel.model}
          providerName={list.find((x) => x.id === editingModel.providerId)?.name}
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
          initial={EMPTY_MODEL}
          providerName={list.find((x) => x.id === addingModelFor)?.name}
          onSave={(m) => onAddModel(addingModelFor, m)}
          onCancel={() => setAddingModelFor(null)}
        />
      )}
    </div>
  )
}