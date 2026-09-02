import { useState } from 'react'
import { FormModal } from '@/components/ui/FormModal'
import { ApiError } from '@/api/rest'
import type { Skill, CreateSkillRequest, UpdateSkillRequest } from '@/api/types'
import type { UseSkills } from '@/hooks/useSkills'

interface SkillsPanelProps {
  /** 真实后端 CRUD API */
  skillsApi: UseSkills
  showToast: (msg: string, type?: 'success' | 'info') => void
}

const EMPTY_SKILL: Skill = {
  id: '',
  name: '',
  description: '',
  enabled: true,
  builtin: false,
  config: null,
}

const EditIcon = () => (
  <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: 12, height: 12 }}>
    <path d="M9 2L12 5L5 12H2V9L9 2Z" />
  </svg>
)

/**
 * SkillsPanel · Phase C1 联调版
 *
 * <p>所有 CRUD 走真实后端 API（useSkills hook）。组件本身不持有 skills 列表副本，
 * 完全从 hook 渲染。
 */
export function SkillsPanel({ skillsApi, showToast }: SkillsPanelProps) {
  const { list, loading, error, createSkill, updateSkill, deleteSkill, toggleSkill } = skillsApi

  const [editing, setEditing] = useState<Skill | null>(null)
  const [adding, setAdding] = useState(false)
  const [pending, setPending] = useState(false)

  // ---- 包装：带错误处理 + toast ----
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
    const s = list.find((x) => x.id === id)
    void wrap(
      () => toggleSkill(id, !currentlyEnabled),
      `${s?.name} ${!currentlyEnabled ? '已启用' : '已停用'}`
    )
  }

  const onRemove = (s: Skill) => {
    if (s.builtin) {
      showToast('内置技能不可删除', 'info')
      return
    }
    if (!confirm(`删除技能 "${s.name}"？`)) return
    void wrap(() => deleteSkill(s.id), '已删除技能')
  }

  const onSaveEdit = (updated: Skill) => {
    const req: UpdateSkillRequest = {
      name: updated.name,
      description: updated.description,
      enabled: updated.enabled,
    }
    void wrap(async () => {
      await updateSkill(updated.id, req)
      setEditing(null)
    }, `已更新: ${updated.name}`)
  }

  const onAdd = (s: Skill) => {
    const req: CreateSkillRequest = {
      name: s.name,
      description: s.description,
      enabled: s.enabled ?? true,
    }
    void wrap(async () => {
      await createSkill(req)
      setAdding(false)
    }, `已添加: ${s.name}`)
  }

  const enabledCount = list.filter((s) => s.enabled).length

  return (
    <div className="skill-list">
      <div className="settings-row" style={{ borderBottom: 'none', paddingBottom: 4 }}>
        <div>
          <div className="settings-row-label">技能管理</div>
          <div className="settings-row-desc">
            Agent 可调用的能力 · 内置技能不可移除 · {enabledCount}/{list.length} 已启用
            {loading && ' · 加载中…'}
            {error && <span style={{ color: 'var(--error)', marginLeft: 8 }}>· {error}</span>}
          </div>
        </div>
        <button
          className="settings-add-btn"
          onClick={() => setAdding(true)}
          disabled={pending}
        >
          + 添加技能
        </button>
      </div>

      {list.length === 0 && !loading && !error && (
        <div className="fm-model-empty" style={{ padding: '32px 12px', textAlign: 'center' }}>
          暂无技能 · 点击「+ 添加技能」开始
        </div>
      )}

      {list.map((s) => (
        <div key={s.id} className={`skill-row ${s.enabled ? '' : 'disabled'}`}>
          <div className="skill-info" onClick={() => setEditing(s)} style={{ cursor: 'pointer' }}>
            <div className="skill-name">
              {s.name}
              {s.builtin && <span className="skill-badge builtin">内置</span>}
            </div>
            <div className="skill-desc">{s.description}</div>
          </div>
          <div className="skill-actions">
            <label className="settings-switch" title={s.enabled ? '停用' : '启用'} onClick={(e) => e.stopPropagation()}>
              <input
                type="checkbox"
                checked={s.enabled}
                onChange={() => onToggle(s.id, s.enabled)}
                disabled={pending}
              />
              <span></span>
            </label>
            <button
              className="skill-edit"
              title="编辑"
              onClick={() => setEditing(s)}
              disabled={pending}
            >
              <EditIcon />
            </button>
            {!s.builtin && (
              <button
                className="skill-remove"
                onClick={() => onRemove(s)}
                title="移除"
                disabled={pending}
              >
                ×
              </button>
            )}
          </div>
        </div>
      ))}

      {editing && (
        <FormModal<Skill>
          title="编辑技能"
          subtitle={editing.builtin ? '内置技能(只读)' : editing.name}
          initial={editing}
          sections={[
            {
              title: '基础信息',
              fields: [
                { type: 'text', name: 'name', label: '名称', placeholder: 'e.g. 数据库查询' },
                { type: 'textarea', name: 'description', label: '描述', placeholder: '一句话说明这个技能做什么', rows: 2 },
              ],
            },
            {
              title: '行为',
              fields: [
                { type: 'toggle', name: 'enabled', label: '启用', hint: '关闭后 Agent 看不到此技能' },
              ],
            },
          ]}
          onSave={onSaveEdit}
          onCancel={() => setEditing(null)}
          destructiveLabel={editing.builtin ? undefined : '移除此技能 · 不可恢复'}
          onDestructive={editing.builtin ? undefined : () => { onRemove(editing); setEditing(null) }}
        />
      )}

      {adding && (
        <FormModal<Skill>
          title="添加技能"
          subtitle="填写后保存，立即可用"
          initial={EMPTY_SKILL}
          sections={[
            {
              title: '基础信息',
              fields: [
                { type: 'text', name: 'name', label: '名称', placeholder: 'e.g. 数据库查询' },
                { type: 'textarea', name: 'description', label: '描述', placeholder: '一句话说明这个技能做什么', rows: 2 },
              ],
            },
            {
              title: '行为',
              fields: [
                { type: 'toggle', name: 'enabled', label: '立即启用' },
              ],
            },
          ]}
          onSave={onAdd}
          onCancel={() => setAdding(false)}
        />
      )}
    </div>
  )
}
