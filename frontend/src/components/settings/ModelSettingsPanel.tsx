import { useState } from 'react'
import type { AppSettings, Provider, UpdateSettingsRequest } from '@/api/types'
import { tagToClass } from '@/data'

/** 全局档位角色（含主模型全局默认 · 对话框可临时会话级覆盖） */
type ModelRole = '主模型' | '快速模型' | '子代理' | '弱模型' | '中等模型' | '最强模型' | '降级模型' | '多模态模型' | 'TTS' | 'ASR' | '权限分类器'

const ROLE_INFO: Record<ModelRole, { glyph: string; desc: string }> = {
  '主模型': { glyph: '✦', desc: '对话主力 · 新建会话默认（对话框可临时覆盖）' },
  '快速模型': { glyph: '⚡', desc: '轻量任务 · 命名/摘要/away/hook' },
  '子代理': { glyph: '◎', desc: '子代理运行默认模型' },
  '弱模型': { glyph: '◇', desc: '轻量查询 · 快速回答' },
  '中等模型': { glyph: '◈', desc: '中等任务 · 记忆筛选' },
  '最强模型': { glyph: '◆', desc: '最强复杂任务 · 计划' },
  '降级模型': { glyph: '⇄', desc: '主模型失败时自动降级' },
  '多模态模型': { glyph: '◍', desc: '文本+图像理解（含视觉）' },
  'TTS': { glyph: '◧', desc: '语音合成' },
  'ASR': { glyph: '◔', desc: '语音转文字' },
  '权限分类器': { glyph: '◬', desc: 'auto 权限模式分类器用模型；留空用主循环模型' },
}

/** 全局档位 settings 字段（string 值 · onSaveSettings 可写） */
type ModelSettingsKey =
  | 'mainModelName' | 'subagentModelName' | 'weakModelName' | 'mediumModelName' | 'strongModelName'
  | 'fallbackModelName' | 'multimodalModelName' | 'ttsModelName' | 'asrModelName' | 'classifierModel'
const TIER_FIELD: Partial<Record<ModelRole, ModelSettingsKey>> = {
  '主模型': 'mainModelName',
  '子代理': 'subagentModelName',
  '弱模型': 'weakModelName',
  '中等模型': 'mediumModelName',
  '最强模型': 'strongModelName',
  '降级模型': 'fallbackModelName',
  '多模态模型': 'multimodalModelName',
  'TTS': 'ttsModelName',
  'ASR': 'asrModelName',
  '权限分类器': 'classifierModel',
}

interface Props {
  providers: Provider[]
  settings: AppSettings | null
  onSaveSettings: (req: UpdateSettingsRequest) => Promise<void>
  fastModel: string | null
  pickFast: (providerName: string, modelName: string) => void
  clearFast: () => void
  /** 跳转设置「提供商」tab（footer 管理 Provider） */
  onOpenProviders?: () => void
}

/** 设置页「模型」tab · 全局档位角色配置（主模型在对话框会话级选择，不在此） */
export function ModelSettingsPanel({ providers, settings, onSaveSettings, fastModel, pickFast, clearFast, onOpenProviders }: Props) {
  const [role, setRole] = useState<ModelRole>('快速模型')

  const roleValue = (k: ModelRole): string => {
    const field = TIER_FIELD[k]
    if (field) return settings?.[field] ?? ''
    if (k === '快速模型') return fastModel ?? ''
    return ''
  }

  const applyModel = (providerName: string, modelName: string) => {
    const fullName = `${providerName}/${modelName}`
    if (role === '快速模型') {
      if (fullName !== fastModel) pickFast(providerName, fullName)
    } else if (TIER_FIELD[role]) {
      const key = TIER_FIELD[role]!
      void onSaveSettings({ [key]: fullName } as UpdateSettingsRequest)
    }
  }

  return (
    <div className="ms-panel">
      {/* 角色卡片（对齐 deepseek_html_20260823_8aff29：主/档位 3 列 + 降级单独一行） */}
      {(() => {
        const renderCard = (k: ModelRole) => {
          const isActive = role === k
          const isConfigured = !!roleValue(k)
          return (
            <div
              key={k}
              className={`role-card ${isActive ? 'active' : ''} ${isConfigured ? 'configured-card' : ''}`}
              onClick={() => setRole(k)}
            >
              <div className="role-name">
                <span className="role-glyph">{ROLE_INFO[k].glyph}</span>{k}
              </div>
              <div className="role-desc">{ROLE_INFO[k].desc}</div>
              {isConfigured ? (
                <div className="role-model">
                  <span className="tag" style={{ background: 'var(--accent)' }} />
                  <span className="rm-text">{roleValue(k)}</span>
                  <span className="configured">已配置</span>
                </div>
              ) : (
                <div className="role-empty">未配置</div>
              )}
            </div>
          )
        }
        return (
          <>
            <div className="role-grid">
              {(Object.keys(ROLE_INFO) as ModelRole[]).filter((k) => k !== '降级模型' && k !== '权限分类器').map(renderCard)}
            </div>
            <div className="fallback-grid">
              {renderCard('降级模型')}
            </div>
            <div className="fallback-grid">
              {renderCard('权限分类器')}
            </div>
          </>
        )
      })()}

      <div className="ms-hint">配置「{role}」· {ROLE_INFO[role].desc}</div>

      {/* 模型列表 */}
      {providers.filter((p) => p.enabled).length === 0 && (
        <div className="right-empty">暂无可用 Provider · 去 提供商 添加</div>
      )}
      {providers.map((p) => {
        if (!p.enabled) return null
        return (
          <div key={p.id} className="mp-provider">
            <div className="mp-provider-head">
              <span className="mp-provider-name">{p.name.toUpperCase()}</span>
              <span className="p-type">{p.type}</span>
              <span className="p-count">{p.models.length} 个模型</span>
            </div>
            <div className="mp-models">
              {p.models.map((m) => {
                const fullName = `${p.name}/${m.name}`
                const isBound = roleValue(role) === fullName
                return (
                  <div key={m.id} className={`mp-model ${isBound ? 'current' : ''}`} onClick={() => applyModel(p.name, m.name)}>
                    <span className={`model-tag ${tagToClass(m.tag)}`}>{m.tag}</span>
                    <div className="mp-model-info">
                      <span className="mp-model-name">{fullName} <span className="m-type">{m.type}</span></span>
                      <span className="mp-model-desc">{m.desc}</span>
                    </div>
                    <div className="mp-model-badges">
                      {isBound && <span className="mp-badge">已配置</span>}
                    </div>
                  </div>
                )
              })}
            </div>
          </div>
        )
      })}

      {role === '快速模型' && fastModel && (
        <button className="mp-clear-btn" onClick={clearFast}>清除快速模型（回退到主模型）</button>
      )}

      {/* 底部：管理模型 / Provider（同步复刻 HTML footer） */}
      <div className="ms-footer">
        <span className="manage" onClick={() => onOpenProviders?.()}>管理模型 / Provider →</span>
      </div>
    </div>
  )
}
