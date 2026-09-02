import { useState } from 'react'
import { FormModal } from '@/components/ui/FormModal'
import { ApiError } from '@/api/rest'
import type {
  Schedule,
  ScheduleKind,
  CreateScheduleRequest,
  UpdateScheduleRequest,
} from '@/api/types'
import type { UseSchedules } from '@/hooks/useSchedules'

interface SchedulesPanelProps {
  /** 真实后端 CRUD API */
  schedulesApi: UseSchedules
  showToast: (msg: string, type?: 'success' | 'info') => void
}

const KIND_OPTIONS: { value: ScheduleKind; label: string }[] = [
  { value: 'cron', label: 'Cron 表达式' },
  { value: 'once', label: '单次执行' },
  { value: 'interval', label: '固定间隔' },
]

const KIND_LABEL: Record<ScheduleKind, string> = {
  cron: 'Cron',
  once: '单次',
  interval: '间隔',
}

const EditIcon = () => (
  <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: 12, height: 12 }}>
    <path d="M9 2L12 5L5 12H2V9L9 2Z" />
  </svg>
)

/* ------------------------------------------------------------------ */
/*  F29：cron 串 → 人类可读文案（简单实现，未识别回退原文）              */
/* ------------------------------------------------------------------ */

const pad2 = (n: string) => n.padStart(2, '0')

/** 解析 '0/5' / '5' 分钟步进 → 步长；不支持返回 null */
const stepOf = (v: string): number | null => {
  const m = /^(?:\*|0)\/(\d+)$/.exec(v)
  return m ? Number(m[1]) : null
}

/**
 * F29：把 cron 串转人类可读文案。
 * 支持常见 5/6 段 Quartz 模式：每 N 分钟、每天 HH:mm、每月 N 日 HH:mm；
 * 其余（周几组合等）回退显示原文 + 「(未识别调度)」。
 */
export function cronToHuman(cron: string): string {
  const parts = cron.trim().split(/\s+/)
  // 兼容 6 段 Quartz（秒 分 时 日 月 周）：剥掉秒字段退化为 5 段
  const f = parts.length === 6 ? parts.slice(1) : parts
  if (f.length !== 5) return `${cron}（未识别调度）`
  const [min, hour, dom, month, dow] = f
  // 周几字段非通配（'*'/'?'）→ 周几限定属于不支持的模式，回退原文（避免把「每周一」误标成「每天」）
  if (dow !== '*' && dow !== '?') return `${cron}（未识别调度）`
  // 每 N 分钟（如 '0/5' / '*/5'）
  const step = stepOf(min)
  if (step != null) return `每 ${step} 分钟`
  const num = /^\d{1,2}$/
  // 每天 HH:mm（分 时 固定，日/月 通配）
  if (num.test(min) && num.test(hour) && dom === '*' && month === '*') {
    return `每天 ${pad2(hour)}:${pad2(min)}`
  }
  // 每月 N 日 HH:mm（日固定，月通配）
  if (num.test(min) && num.test(hour) && num.test(dom) && month === '*') {
    return `每月 ${Number(dom)} 日 ${pad2(hour)}:${pad2(min)}`
  }
  return `${cron}（未识别调度）`
}

/** 归属短标签（截断显示）· 会话 > agent > 项目；全无 → 全局 */
const shortId = (id?: string | null) => (id && id.length > 10 ? `${id.slice(0, 8)}…` : id ?? '')
function scopeLabel(s: Schedule): string {
  if (s.sessionId) return `会话${s.agentId ? `·${shortId(s.agentId)}` : ''}`
  if (s.agentId) return `agent·${shortId(s.agentId)}`
  if (s.boundProject) return `项目·${s.boundProject}`
  return '全局'
}

/**
 * SchedulesPanel · Phase C4 联调版
 *
 * <p>所有 CRUD 走真实后端 API（useSchedules hook）。
 * <p>command 字段在 v1 不开放给用户（后端写死为 "test"），所以 UI 上不展示。
 * <p>"立即运行" 调真实 /run 端点，toast 显示后端返回的 executed/output。
 */
export function SchedulesPanel({ schedulesApi, showToast }: SchedulesPanelProps) {
  const { list, loading, error, createSchedule, updateSchedule, deleteSchedule, runNow } = schedulesApi

  const [editing, setEditing] = useState<Schedule | null>(null)
  const [addingKind, setAddingKind] = useState<ScheduleKind | null>(null)
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

  const onRun = async (s: Schedule) => {
    setPending(true)
    try {
      const r = await runNow(s.id)
      if (r.executed) {
        const output = r.output ? `: ${r.output.slice(0, 80)}` : ''
        showToast(`已运行 ${s.name}${output}`, 'success')
      } else {
        showToast(`已触发 ${s.name}（未执行）`, 'info')
      }
    } catch (e) {
      const msg = e instanceof ApiError ? e.userMessage() : String(e)
      showToast(`${s.name}: ${msg}`, 'info')
    } finally {
      setPending(false)
    }
  }

  const onRemove = (s: Schedule) => {
    if (!confirm(`删除定时任务 "${s.name}"？`)) return
    void wrap(() => deleteSchedule(s.id), '已删除定时任务')
  }

  const onSaveEdit = (form: ScheduleEditFormValue) => {
    const req: UpdateScheduleRequest = buildRequest(form, editing!.kind)
    void wrap(async () => {
      await updateSchedule(form.id, req)
      setEditing(null)
    }, `已更新: ${form.name}`)
  }

  return (
    <div className="schedule-list">
      <div className="settings-row" style={{ borderBottom: 'none', paddingBottom: 4 }}>
        <div>
          <div className="settings-row-label">定时任务</div>
          <div className="settings-row-desc">
            基于 Quartz 的自动化任务
            {loading && ' · 加载中…'}
            {error && <span style={{ color: 'var(--error)', marginLeft: 8 }}>· {error}</span>}
          </div>
        </div>
        <button
          className="settings-add-btn"
          onClick={() => setAddingKind('cron')}
          disabled={pending}
        >
          + 添加调度
        </button>
      </div>

      {list.length === 0 && !loading && !error && (
        <div className="fm-model-empty" style={{ padding: '32px 12px', textAlign: 'center' }}>
          暂无定时任务 · 点击「+ 添加调度」开始
        </div>
      )}

      {list.map((s) => {
        // F29：cron 转人类可读文案（未识别回退原文）；once 按 runAt 渲染保持不变
        const scheduleText =
          s.kind === 'cron' ? (s.cron ? cronToHuman(s.cron) : '—')
          : s.kind === 'once' ? (s.runAt ?? '—')
          : s.intervalSeconds != null ? `每 ${s.intervalSeconds} 秒`
          : '—'
        return (
          <div key={s.id} className="schedule-row">
            <div className="schedule-info" onClick={() => setEditing(s)} style={{ cursor: 'pointer' }}>
              <div className="schedule-name">
                {s.name}
                <span className="schedule-kind">{KIND_LABEL[s.kind]}</span>
                <span
                  style={{ fontSize: 10, fontFamily: 'var(--font-mono)', color: 'var(--ink-faint)', background: 'transparent', border: '1px solid var(--hairline)', padding: '1px 6px', borderRadius: 'var(--r-xs)', marginLeft: 6 }}
                  title={s.sessionId ?? s.agentId ?? s.boundProject ?? '全局定时任务'}
                >
                  {scopeLabel(s)}
                </span>
                <code className="schedule-cron">{scheduleText}</code>
              </div>
              {s.description && <div className="schedule-desc">{s.description}</div>}
              <div className="schedule-runs">
                {s.lastRunAt && (
                  <span>
                    上次: {s.lastRunAt}
                    {s.lastRunStatus && <> · <span className={s.lastRunStatus === 'ok' || s.lastRunStatus === 'success' ? 'ok' : 'err'}>
                      {s.lastRunStatus}
                    </span></>}
                  </span>
                )}
              </div>
            </div>
            <div className="schedule-actions">
              <button
                className="db-test"
                onClick={() => onRun(s)}
                disabled={pending}
                title="立即触发"
              >
                立即运行
              </button>
              <button
                className="skill-edit"
                title="编辑"
                onClick={() => setEditing(s)}
                disabled={pending}
              >
                <EditIcon />
              </button>
              <button
                className="skill-remove"
                onClick={() => onRemove(s)}
                title="删除"
                disabled={pending}
              >
                ×
              </button>
            </div>
          </div>
        )
      })}

      {editing && (
        <FormModal<ScheduleEditFormValue>
          title="编辑定时任务"
          subtitle={editing.name}
          initial={toEditForm(editing)}
          sections={[
            {
              title: '基础信息',
              fields: [
                { type: 'text', name: 'name', label: '任务名', placeholder: 'e.g. 每晚全量备份' },
                { type: 'locked', label: '调度类型', render: () => (
                  <select className="fm-select" disabled value={editing.kind}>
                    {KIND_OPTIONS.map((o) => (
                      <option key={o.value} value={o.value}>{o.label}</option>
                    ))}
                  </select>
                ) },
                { type: 'textarea', name: 'description', label: '描述', rows: 2 },
              ],
            },
            {
              title: '调度',
              fields: scheduleFieldsFor(editing.kind, toEditForm(editing)),
            },
            {
              title: '行为',
              fields: [
                { type: 'textarea', name: 'command', label: '命令（prompt）', rows: 3, placeholder: '任务触发时执行的指令…' },
              ],
            },
          ]}
          onSave={onSaveEdit}
          onCancel={() => setEditing(null)}
          destructiveLabel="移除此任务 · 不可恢复"
          onDestructive={() => { onRemove(editing); setEditing(null) }}
        />
      )}

      {addingKind && (
        <ScheduleAddModal
          kind={addingKind}
          onChangeKind={setAddingKind}
          onSave={async (req) => {
            await wrap(() => createSchedule(req), `已添加: ${req.name}`)
            setAddingKind(null)
          }}
          onCancel={() => setAddingKind(null)}
        />
      )}
    </div>
  )
}

/* ------------------------------------------------------------------ */
/*  内部：编辑表单类型 + 转换                                            */
/* ------------------------------------------------------------------ */

interface ScheduleEditFormValue {
  id: string
  name: string
  cron: string
  intervalSeconds: number
  runAt: string
  description: string
  /** 任务命令（prompt）· 后端 Schedule.command 接受，UI 现在可编辑 */
  command: string
}

const toEditForm = (s: Schedule): ScheduleEditFormValue => ({
  id: s.id,
  name: s.name,
  cron: s.cron ?? '',
  intervalSeconds: s.intervalSeconds ?? 0,
  runAt: s.runAt ? s.runAt.slice(0, 16) : '',
  description: s.description,
  command: s.command ?? '',
})

const scheduleFieldsFor = (kind: ScheduleKind, _form: ScheduleEditFormValue | AddFormValue) => {
  if (kind === 'cron') {
    // F29：详情附当前 cron 的人类可读文案（未识别时 cronToHuman 已回退原文）
    const raw = _form.cron?.trim()
    const human = raw ? cronToHuman(raw) : ''
    const base = '分 时 日 月 周 — 例: 0 2 * * * = 每天凌晨 2 点'
    return [
      { type: 'mono' as const, name: 'cron', label: 'cron 表达式', placeholder: '0 2 * * *', hint: raw ? `${base} · 当前: ${human}` : base },
    ]
  }
  if (kind === 'once') {
    return [
      { type: 'text' as const, name: 'runAt', label: '执行时间', placeholder: '2025-12-31T23:59', hint: 'ISO-8601，本地时区' },
    ]
  }
  return [
    { type: 'number' as const, name: 'intervalSeconds', label: '间隔（秒）', min: 1, hint: '例: 3600 = 每小时' },
  ]
}

const buildRequest = (form: ScheduleEditFormValue | AddFormValue, kind: ScheduleKind): CreateScheduleRequest | UpdateScheduleRequest => {
  const req: CreateScheduleRequest = {
    name: form.name,
    kind,
    description: form.description,
  }
  // 命令（prompt）可编辑后随请求提交；空值不发送（后端保留默认/旧值）
  if (form.command?.trim()) req.command = form.command.trim()
  if (kind === 'cron') {
    req.cron = (form as any).cron
  } else if (kind === 'interval') {
    req.intervalSeconds = (form as any).intervalSeconds
  } else if (kind === 'once') {
    const v = (form as any).runAt
    if (v) req.runAt = new Date(v).toISOString()
  }
  return req
}

/* ------------------------------------------------------------------ */
/*  内部：添加表单（自定义 modal，支持 kind 切换）                            */
/* ------------------------------------------------------------------ */

interface AddFormValue {
  name: string
  description: string
  // dispatch by kind
  cron: string
  intervalSeconds: number
  runAt: string
  /** 任务命令（prompt）· 后端 Schedule.command 接受，UI 现在可编辑 */
  command: string
}

const emptyAddForm = (kind: ScheduleKind): AddFormValue => ({
  name: '',
  description: '',
  cron: '0 0 * * *',
  intervalSeconds: 3600,
  runAt: '',
  command: '',
  ...(kind === 'cron' ? { cron: '0 0 * * *' } : {}),
  ...(kind === 'interval' ? { intervalSeconds: 3600 } : {}),
})

function ScheduleAddModal({
  kind,
  onChangeKind,
  onSave,
  onCancel,
}: {
  kind: ScheduleKind
  onChangeKind: (k: ScheduleKind) => void
  onSave: (req: CreateScheduleRequest) => Promise<void>
  onCancel: () => void
}) {
  // 父级通过 `kind` 跟踪调度类型；切换类型时清空具体调度字段。
  // 用 `key` 重置 FormModal 内部状态。
  const [form, setForm] = useState<AddFormValue>(emptyAddForm(kind))

  const onSubmit = async (v: AddFormValue) => {
    setForm(v)
    const req = buildRequest(v, kind) as CreateScheduleRequest
    await onSave(req)
  }

  return (
    <div className="fm-backdrop" onClick={onCancel}>
      <div className="fm-modal" onClick={(e) => e.stopPropagation()}>
        <div className="fm-header">
          <span className="fm-status-dot"></span>
          <span className="fm-title">添加调度</span>
          <span className="fm-subtitle">支持 cron / 单次 / 间隔</span>
        </div>
        <div className="fm-body">
          <div className="fm-section-title"><span>基础信息</span></div>
          <div className="fm-row">
            <div className="fm-field">
              <label className="fm-field-label">任务名</label>
              <input
                className="fm-input"
                type="text"
                value={form.name}
                placeholder="e.g. 每晚全量备份"
                onChange={(e) => setForm((p) => ({ ...p, name: e.target.value }))}
                autoFocus
              />
            </div>
            <div className="fm-field">
              <label className="fm-field-label">调度类型</label>
              <select
                className="fm-select"
                value={kind}
                onChange={(e) => {
                  const k = e.target.value as ScheduleKind
                  setForm(emptyAddForm(k))
                  onChangeKind(k)
                }}
              >
                {KIND_OPTIONS.map((o) => (
                  <option key={o.value} value={o.value}>{o.label}</option>
                ))}
              </select>
            </div>
          </div>
          <div className="fm-row">
            <div className="fm-field">
              <label className="fm-field-label">描述</label>
              <textarea
                className="fm-textarea"
                rows={2}
                value={form.description}
                onChange={(e) => setForm((p) => ({ ...p, description: e.target.value }))}
              />
            </div>
          </div>

          <div className="fm-section-title"><span>调度</span></div>
          {kind === 'cron' && (
            <div className="fm-row">
              <div className="fm-field">
                <label className="fm-field-label">cron 表达式</label>
                <input
                  className="fm-input mono"
                  type="text"
                  value={form.cron}
                  placeholder="0 2 * * *"
                  onChange={(e) => setForm((p) => ({ ...p, cron: e.target.value }))}
                />
                <div className="fm-field-hint">分 时 日 月 周 — 例: 0 2 * * * = 每天凌晨 2 点</div>
              </div>
            </div>
          )}
          {kind === 'once' && (
            <div className="fm-row">
              <div className="fm-field">
                <label className="fm-field-label">执行时间</label>
                <input
                  className="fm-input mono"
                  type="text"
                  value={form.runAt}
                  placeholder="2025-12-31T23:59"
                  onChange={(e) => setForm((p) => ({ ...p, runAt: e.target.value }))}
                />
                <div className="fm-field-hint">ISO-8601，本地时区</div>
              </div>
            </div>
          )}
          {kind === 'interval' && (
            <div className="fm-row">
              <div className="fm-field">
                <label className="fm-field-label">间隔（秒）</label>
                <input
                  className="fm-input mono"
                  type="number"
                  min={1}
                  value={form.intervalSeconds}
                  onChange={(e) => setForm((p) => ({ ...p, intervalSeconds: Number(e.target.value) || 0 }))}
                />
                <div className="fm-field-hint">例: 3600 = 每小时</div>
              </div>
            </div>
          )}

          <div className="fm-section-title"><span>行为</span></div>
          <div className="fm-row">
            <div className="fm-field">
              <label className="fm-field-label">命令（prompt）</label>
              <textarea
                className="fm-textarea mono"
                rows={3}
                value={form.command}
                placeholder="任务触发时执行的指令…"
                onChange={(e) => setForm((p) => ({ ...p, command: e.target.value }))}
              />
            </div>
          </div>
        </div>
        <div className="fm-footer">
          <span className="spacer"></span>
          <button className="fm-btn" onClick={onCancel}>取消</button>
          <button className="fm-btn primary" onClick={() => onSubmit(form)}>保存</button>
        </div>
      </div>
    </div>
  )
}
