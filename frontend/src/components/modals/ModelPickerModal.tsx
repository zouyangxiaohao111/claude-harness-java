import { useState } from 'react'
import type { ModelTag, Provider } from '@/api/types'
import { tagToClass } from '@/data'

interface ModelPickerModalProps {
  providers: Provider[]
  /** 当前会话主模型（临时 · 不写全局 settings） */
  activeSessionModelName: string
  close: () => void
  /** 选主模型（App 持久化到会话 model_name，不碰 settings.mainModelName） */
  pickCurrent: (providerName: string, modelName: string, tag: ModelTag) => void
}

const TYPE_FILTERS = ['全部', '对话', '多模态', '视频', '语音/TTS'] as const

/**
 * 会话主模型选择（临时）· 从对话框模型 pill 打开。
 * 只配置当前会话主模型（写 session.model_name），不写全局 settings。
 * 全局模型档位配置在 设置页 → 模型。
 */
export function ModelPickerModal({ providers, activeSessionModelName, close, pickCurrent }: ModelPickerModalProps) {
  const [search, setSearch] = useState('')
  const [typeFilter, setTypeFilter] = useState<string>('全部')

  const filteredModels = (p: Provider) => {
    const q = search.trim().toLowerCase()
    return p.models.filter((m) => {
      if (typeFilter !== '全部') {
        const t = m.type.toLowerCase()
        if (typeFilter === '对话' && !(t.includes('chat') || t.includes('text'))) return false
        if (typeFilter === '多模态' && !t.includes('multimodal')) return false
        if (typeFilter === '视频' && !t.includes('image') && !t.includes('video')) return false
        if (typeFilter === '语音/TTS' && !t.includes('audio') && !t.includes('tts')) return false
      }
      if (q && !m.name.toLowerCase().includes(q) && !(m.desc ?? '').toLowerCase().includes(q)) return false
      return true
    })
  }

  return (
    <div className="mp-backdrop" onClick={close}>
      <div className="mp-modal" onClick={(e) => e.stopPropagation()}>
        <div className="mp-header">
          <span className="mp-title">选择主模型</span>
          <span className="mp-hint">当前会话使用 · 临时配置，不影响全局</span>
          <button className="mp-close" onClick={close}>
            <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: '14px', height: '14px' }}>
              <path d="M3 3L11 11M11 3L3 11" />
            </svg>
          </button>
        </div>

        <div className="mp-body">
          {/* 搜索 + 类型筛选 */}
          <div className="filter-bar">
            <div className="search-box">
              <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5"><circle cx="5.5" cy="5.5" r="3.5" /><path d="M8.5 8.5L11 11" /></svg>
              <input placeholder="搜索模型…" value={search} onChange={(e) => setSearch(e.target.value)} />
            </div>
            <div className="type-tabs">
              {TYPE_FILTERS.map((t) => (
                <span key={t} className={`type-tab ${typeFilter === t ? 'active' : ''}`} onClick={() => setTypeFilter(t)}>
                  {t}
                </span>
              ))}
            </div>
          </div>

          {providers.filter((p) => p.enabled).length === 0 && (
            <div className="right-empty">暂无可用 Provider · 去 设置 → 提供商 添加</div>
          )}

          {providers.map((p) => {
            if (!p.enabled) return null
            const models = filteredModels(p)
            if (models.length === 0) return null
            return (
              <div key={p.id} className="mp-provider">
                <div className="mp-provider-head">
                  <span className="mp-provider-name">{p.name.toUpperCase()}</span>
                  <span className="p-type">{p.type}</span>
                  <span className="p-count">{models.length} 个模型</span>
                </div>
                <div className="mp-models">
                  {models.map((m) => {
                    const fullName = `${p.name}/${m.name}`
                    const isCurrent = fullName === activeSessionModelName
                    return (
                      <div
                        key={m.id}
                        className={`mp-model ${isCurrent ? 'current' : ''}`}
                        onClick={() => pickCurrent(p.name, fullName, m.tag)}
                      >
                        <span className={`model-tag ${tagToClass(m.tag)}`}>{m.tag}</span>
                        <div className="mp-model-info">
                          <span className="mp-model-name">{fullName} <span className="m-type">{m.type}</span></span>
                          <span className="mp-model-desc">{m.desc}</span>
                        </div>
                        <div className="mp-model-badges">
                          {isCurrent && <span className="mp-badge">当前</span>}
                        </div>
                      </div>
                    )
                  })}
                </div>
              </div>
            )
          })}
        </div>

        <div className="mp-footer">
          <span className="manage" onClick={close}>完成</span>
        </div>
      </div>
    </div>
  )
}
