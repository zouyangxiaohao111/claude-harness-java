import { useState } from 'react'
import { FormModal } from '@/components/ui/FormModal'
import { ApiError } from '@/api/rest'
import type {
  DatabaseConnection,
  DatabaseType,
  CreateDatabaseRequest,
  UpdateDatabaseRequest,
} from '@/api/types'
import type { UseDatabases } from '@/hooks/useDatabases'

interface DatabasePanelProps {
  /** 真实后端 CRUD API */
  databasesApi: UseDatabases
  showToast: (msg: string, type?: 'success' | 'info') => void
}

const TYPE_OPTIONS: { value: DatabaseType; label: string }[] = [
  { value: 'postgres', label: 'PostgreSQL' },
  { value: 'mysql', label: 'MySQL' },
  { value: 'sqlite', label: 'SQLite' },
  { value: 'mongodb', label: 'MongoDB' },
]

const DEFAULT_PORT: Record<DatabaseType, number> = {
  postgres: 5432,
  mysql: 3306,
  sqlite: 0,
  mongodb: 27017,
}

const STATUS_LABEL: Record<string, string> = {
  connected: '已连接',
  disconnected: '未连接',
  error: '错误',
  testing: '测试中',
  unknown: '未知',
}

/** 表单模型：password 在编辑时是空字符串，提交时由用户重新输入才会真正改 */
interface DbFormValue {
  id: string
  name: string
  type: DatabaseType
  host: string
  port: number
  database: string
  user: string
  password: string
}

const emptyForm = (type: DatabaseType = 'postgres'): DbFormValue => ({
  id: '',
  name: '',
  type,
  host: type === 'sqlite' ? '' : '127.0.0.1',
  port: DEFAULT_PORT[type],
  database: '',
  user: '',
  password: '',
})

const toForm = (d: DatabaseConnection): DbFormValue => ({
  id: d.id,
  name: d.name,
  type: d.type,
  host: d.host,
  port: d.port,
  database: d.database,
  user: d.user,
  password: '',
})

const EditIcon = () => (
  <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: 12, height: 12 }}>
    <path d="M9 2L12 5L5 12H2V9L9 2Z" />
  </svg>
)

/**
 * DatabasePanel · Phase C3 联调版
 *
 * <p>所有 CRUD 走真实后端 API（useDatabases hook）。
 * <p>密码永远显示为 ****（后端始终脱敏）。编辑时密码字段留空 — 用户必须重新输入才能改密码。
 * <p>"测试连接" 按钮调真实 /test 端点，把后端返回的 ok/latency/message 推送到 toast。
 */
export function DatabasePanel({ databasesApi, showToast }: DatabasePanelProps) {
  const { list, loading, error, createDatabase, updateDatabase, deleteDatabase, testDatabase } = databasesApi

  const [editing, setEditing] = useState<DatabaseConnection | null>(null)
  const [adding, setAdding] = useState(false)
  const [pending, setPending] = useState(false)

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

  const onRemove = (d: DatabaseConnection) => {
    if (!confirm(`删除数据库连接 "${d.name}"？`)) return
    void wrap(() => deleteDatabase(d.id), '已删除数据库连接')
  }

  const onTest = async (d: DatabaseConnection) => {
    setPending(true)
    try {
      const r = await testDatabase(d.id)
      const latency = r.latencyMs != null ? ` (${r.latencyMs}ms)` : ''
      if (r.ok) {
        showToast(`${d.name}: ${r.message}${latency}`, 'success')
      } else {
        showToast(`${d.name}: ${r.message}`, 'info')
      }
    } catch (e) {
      const msg = e instanceof ApiError ? e.userMessage() : String(e)
      showToast(`${d.name}: ${msg}`, 'info')
    } finally {
      setPending(false)
    }
  }

  const onSaveEdit = (form: DbFormValue) => {
    const req: UpdateDatabaseRequest = {
      name: form.name,
      type: form.type,
      host: form.host,
      port: form.port,
      database: form.database,
      user: form.user,
    }
    if (form.password.trim() !== '') {
      req.password = form.password
    }
    void wrap(async () => {
      await updateDatabase(form.id, req)
      setEditing(null)
    }, `已更新: ${form.name}`)
  }

  const onAdd = (form: DbFormValue) => {
    if (form.password.trim() === '') {
      showToast('请输入密码', 'info')
      return
    }
    const req: CreateDatabaseRequest = {
      name: form.name,
      type: form.type,
      host: form.host,
      port: form.port,
      database: form.database,
      user: form.user,
      password: form.password,
    }
    void wrap(async () => {
      await createDatabase(req)
      setAdding(false)
    }, `已添加: ${form.name}`)
  }

  return (
    <div className="db-list">
      <div className="settings-row" style={{ borderBottom: 'none', paddingBottom: 4 }}>
        <div>
          <div className="settings-row-label">数据库连接 (供 AI 工具使用)</div>
          <div className="settings-row-desc">
            Agent 可查询的外部数据库 · 密码永远脱敏
            {loading && ' · 加载中…'}
            {error && <span style={{ color: 'var(--error)', marginLeft: 8 }}>· {error}</span>}
          </div>
        </div>
        <button
          className="settings-add-btn"
          onClick={() => setAdding(true)}
          disabled={pending}
        >
          + 添加数据库
        </button>
      </div>

      {list.length === 0 && !loading && !error && (
        <div className="fm-model-empty" style={{ padding: '32px 12px', textAlign: 'center' }}>
          暂无数据库连接 · 点击「+ 添加数据库」开始
        </div>
      )}

      {list.map((d) => (
        <div key={d.id} className={`db-row ${d.status === 'connected' ? '' : 'disabled'}`}>
          <div className="db-info" onClick={() => setEditing(d)} style={{ cursor: 'pointer' }}>
            <div className="db-name">
              {d.name}
              <span className="db-type">{d.type}</span>
            </div>
            <div className="db-detail">
              <code>{d.host}:{d.port || '—'} / {d.database}</code>
              <span className="db-user">user: {d.user || '(空)'}</span>
              <span className="db-user">password: {d.passwordMasked}</span>
            </div>
            {d.lastError && (
              <div className="db-error" style={{ color: 'var(--error)', fontSize: 11, marginTop: 2 }}>
                ✗ {d.lastError}
              </div>
            )}
          </div>
          <div className="db-meta">
            <span className={`db-status ${d.status}`}>
              {STATUS_LABEL[d.status] ?? d.status}
            </span>
            <button
              className="db-test"
              onClick={() => onTest(d)}
              disabled={pending}
              title="测试连接"
            >
              测试
            </button>
            <button
              className="skill-edit"
              title="编辑"
              onClick={() => setEditing(d)}
              disabled={pending}
            >
              <EditIcon />
            </button>
            <button
              className="skill-remove"
              onClick={() => onRemove(d)}
              title="移除"
              disabled={pending}
            >
              ×
            </button>
          </div>
        </div>
      ))}

      {editing && (
        <FormModal<DbFormValue>
          title="编辑数据库连接"
          subtitle={editing.name}
          initial={toForm(editing)}
          sections={[
            {
              title: '基础信息',
              fields: [
                { type: 'text', name: 'name', label: '连接名称' },
                { type: 'select', name: 'type', label: '类型', options: TYPE_OPTIONS },
              ],
            },
            {
              title: '连接信息',
              rows: [
                [{ type: 'text', name: 'host', label: '主机' }, { type: 'number', name: 'port', label: '端口' }],
                [{ type: 'text', name: 'database', label: '数据库' }, { type: 'text', name: 'user', label: '用户' }],
                [{ type: 'password', name: 'password', label: '密码（留空表示不修改）' }],
              ],
            },
          ]}
          onSave={onSaveEdit}
          onCancel={() => setEditing(null)}
          destructiveLabel="移除此连接 · 不可恢复"
          onDestructive={() => { onRemove(editing); setEditing(null) }}
        />
      )}

      {adding && (
        <FormModal<DbFormValue>
          title="添加数据库连接"
          subtitle="保存后可测试连通性"
          initial={emptyForm()}
          sections={[
            {
              title: '基础信息',
              fields: [
                { type: 'text', name: 'name', label: '连接名称', placeholder: 'e.g. 生产 DB' },
                { type: 'select', name: 'type', label: '类型', options: TYPE_OPTIONS },
              ],
            },
            {
              title: '连接信息',
              rows: [
                [{ type: 'text', name: 'host', label: '主机' }, { type: 'number', name: 'port', label: '端口' }],
                [{ type: 'text', name: 'database', label: '数据库' }, { type: 'text', name: 'user', label: '用户' }],
                [{ type: 'password', name: 'password', label: '密码' }],
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
