import { useState } from 'react'
import { FormModal } from '@/components/ui/FormModal'
import { ApiError } from '@/api/rest'
import type {
  Schedule,
  ScheduleKind,
  ScheduleScope,
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

/**
 * 生命周期选项 · 文案用**代码核出来的真差别**（生命周期 vs 归属），⛔ 不写「全局 / 会话级」这种
 * 二元对立 —— 两个 scope **都带 sessionId**（后端无条件落库），区别不在「有没有会话」，
 * 而在「会话结束后会怎样」。依据：`ScheduleScope` 枚举 javadoc +
 * `ScheduleService.create`（`cleanupBySession` 只按 scope=SESSION 过滤）+ `CronIdleExecutor`
 * （SESSION 命令要求会话存活，会话已关不消费；DURABLE 照常 fire，headless 代跑）。
 */
const SCOPE_OPTIONS: { value: ScheduleScope; label: string; hint: string }[] = [
  {
    value: 'SESSION',
    label: '仅本会话（会话结束即清理）· 默认',
    hint: '只在这个会话活着时触发；会话一关，任务被自动清理，不再运行。',
  },
  {
    value: 'DURABLE',
    label: '持久（跨重启保留）',
    hint: '会话关闭后仍继续触发（无界面后台运行）；任务归属创建它的会话，用于记录运行上下文。',
  },
]

/** 列表徽标用的短标签（scope 缺省按 DURABLE 兜底，对齐后端 lookupScope） */
const SCOPE_LABEL: Record<ScheduleScope, string> = {
  DURABLE: '持久',
  SESSION: '仅本会话',
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
 * Quartz 周几编号 → 中文：1=周日、2=周一 … 7=周六。
 * cron-6field-contract：与后端契约文本一致，切勿按 ISO 改成 1=周一（会让「周一」显示成「周日」）。
 */
const DOW_LABEL: Record<number, string> = {
  1: '周日',
  2: '周一',
  3: '周二',
  4: '周三',
  5: '周四',
  6: '周五',
  7: '周六',
}

/**
 * cron-6field-contract：解析 dow 字段 → 「每周X」/「每周X至周Y」；非单一/区间返回 null。
 * 仅接受单个 1-7 与 A-B（A≤B）两种形态，逗号列表/步进等一律不识别（宁回退不瞎猜）。
 */
const dowPartOf = (dow: string): string | null => {
  const single = /^([1-7])$/.exec(dow)
  if (single) return `每${DOW_LABEL[Number(single[1])]}`
  const range = /^([1-7])-([1-7])$/.exec(dow)
  if (range) {
    const a = Number(range[1])
    const b = Number(range[2])
    if (a <= b) return `每${DOW_LABEL[a]}至${DOW_LABEL[b]}`
  }
  return null
}

/**
 * F29：把「单个」cron 变体（6 段 Quartz，秒 分 时 日 月 周）转人类可读文案。
 * 支持：每 N 分钟、每天 HH:mm、每月 N 日 HH:mm、每周X（含区间）HH:mm；识别不了返回 null。
 * cron-6field-contract：原实现内联在 cronToHuman，此处抽出以便 `||` 变体逐个复用。
 */
function singleCronToHuman(cron: string): string | null {
  const parts = cron.trim().split(/\s+/)
  // 兼容 6 段 Quartz（秒 分 时 日 月 周）：剥掉秒字段退化为 5 段
  const f = parts.length === 6 ? parts.slice(1) : parts
  if (f.length !== 5) return null
  const [min, hour, dom, month, dow] = f
  const dowWild = dow === '*' || dow === '?'
  const dowLabel = dowWild ? null : dowPartOf(dow)
  // 周几字段非通配且不是单一/区间 → 不支持，回退原文（避免把「每周一」误标成「每天」）
  if (!dowWild && dowLabel == null) return null
  // 每 N 分钟（如 '0/5' / '*/5'）；仅在周几未限定时成立，否则会丢掉周几约束
  if (dowWild) {
    const step = stepOf(min)
    if (step != null) return `每 ${step} 分钟`
  }
  const num = /^\d{1,2}$/
  if (!num.test(min) || !num.test(hour)) return null
  // 周几限定：日字段通配（'*'/'?'）+ 月通配 → 每周X HH:mm
  if (dowLabel != null) {
    // 日字段固定（非 '*'/'?'）+ 周字段固定的双约束 → 不支持（后端变体串里 dom 侧通常写作 '?'）
    if ((dom === '*' || dom === '?') && month === '*') {
      return `${dowLabel} ${pad2(hour)}:${pad2(min)}`
    }
    return null
  }
  // 每天 HH:mm（分 时 固定，日/月通配）
  if (dom === '*' && month === '*') {
    return `每天 ${pad2(hour)}:${pad2(min)}`
  }
  // 每月 N 日 HH:mm（日固定，月通配）
  if (num.test(dom) && month === '*') {
    return `每月 ${Number(dom)} 日 ${pad2(hour)}:${pad2(min)}`
  }
  return null
}

/**
 * F29：把 cron 串转人类可读文案。
 * 支持常见 5/6 段 Quartz 模式：每 N 分钟、每天 HH:mm、每月 N 日 HH:mm、每周X（含区间）HH:mm；
 * cron-6field-contract：日+周双约束时后端存 `||` 连接的变体串（任一变体匹配即触发），
 * 逐个识别后用「 或 」拼接；任一变体识别不了则整体回退原文 + 「（未识别调度）」。
 */
export function cronToHuman(cron: string): string {
  if (cron.includes('||')) {
    const variants = cron.split('||').map((v) => singleCronToHuman(v))
    if (variants.every((v) => v != null)) return variants.join(' 或 ')
    return `${cron}（未识别调度）`
  }
  return singleCronToHuman(cron) ?? `${cron}（未识别调度）`
}

/** 归属短标签（截断显示）· 会话 > agent > 项目；全无 → 无归属 */
const shortId = (id?: string | null) => (id && id.length > 10 ? `${id.slice(0, 8)}…` : id ?? '')
/**
 * 行徽标 = 「生命周期 · 归属」。⛔ 必须同时给出 scope —— 只看归属会把两种任务显示成一样
 * （两 scope **都带 sessionId**），用户就分不清「会话关了这任务还跑不跑」。
 */
function scopeLabel(s: Schedule): string {
  const scope = s.scope === 'SESSION' ? 'SESSION' : 'DURABLE'
  const who = s.sessionId ? `会话${s.agentId ? `·${shortId(s.agentId)}` : ''}`
    : s.agentId ? `agent·${shortId(s.agentId)}`
    : s.boundProject ? `项目·${s.boundProject}`
    : '无归属'
  return `${SCOPE_LABEL[scope]} · ${who}`
}

/**
 * SchedulesPanel · Phase C4 联调版
 *
 * <p>所有 CRUD 走真实后端 API（useSchedules hook）。
 * <p>command 字段在 v1 不开放给用户（后端写死为 "test"），所以 UI 上不展示。
 * <p>"立即运行" 调真实 /run 端点，toast 显示后端返回的 executed/output。
 */
export function SchedulesPanel({ schedulesApi, showToast }: SchedulesPanelProps) {
  const { list, loading, error, canCreate, createSchedule, updateSchedule, deleteSchedule, runNow } = schedulesApi

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
            {/* 无活动会话 ⇒ 两种 scope 都建不了（后端 400）⇒ 置灰按钮 + 就地说明，
                ⛔ 不让用户填完整张表单再吃一次注定失败的报错。 */}
            {!canCreate && (
              <span style={{ marginLeft: 8 }}>· 请先打开一个会话再添加：任务需要归属一个会话</span>
            )}
          </div>
        </div>
        <button
          className="settings-add-btn"
          onClick={() => setAddingKind('cron')}
          disabled={pending || !canCreate}
          title={canCreate ? undefined : '请先打开一个会话再添加定时任务'}
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
  /** 生命周期（用户可选）· 默认 SESSION（与后端两处 `req.scope == null → SESSION` 一致，对齐 CC） */
  scope: ScheduleScope
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
  scope: 'SESSION',
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
    // scope 只在**创建**时提交（⛔ buildRequest 不带它 —— 编辑路径复用同一个 buildRequest，
    //   而 [cwd3 D6] update 带 scope/sessionId 会被后端 400 拒绝）
    const scope: ScheduleScope = v.scope ?? 'SESSION'
    const req = { ...buildRequest(v, kind), scope } as CreateScheduleRequest
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
          <div className="fm-row">
            <div className="fm-field">
              <label className="fm-field-label">任务生命周期</label>
              <select
                className="fm-select"
                value={form.scope}
                onChange={(e) => setForm((p) => ({ ...p, scope: e.target.value as ScheduleScope }))}
              >
                {SCOPE_OPTIONS.map((o) => (
                  <option key={o.value} value={o.value}>{o.label}</option>
                ))}
              </select>
              <div className="fm-field-hint">
                {SCOPE_OPTIONS.find((o) => o.value === form.scope)?.hint}
              </div>
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
