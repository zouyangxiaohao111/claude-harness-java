import { useCallback, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { ApiError } from '@/api/rest'
import { chatApi } from '@/api/chat'
import { scheduleApi } from '@/api/schedules'
import { sessionApi } from '@/api/sessions'
import type { ChatMessageDto, Schedule, ScheduleKind, ScheduleRun, SessionDto } from '@/api/types'
import type { UseSchedules } from '@/hooks/useSchedules'
import { MessageList } from '@/components/center/MessageList'
import { ScanTaskPanel } from '@/components/center/ScanTaskPanel'
import {
  ScheduleAddModal,
  ScheduleEditModal,
  DeleteScheduleModal,
  OwnCapsule,
  capsuleDetail,
  modeOf,
  cronToHuman,
  buildRequest,
} from '@/components/modals/SchedulesPanel'
import type { ScheduleEditFormValue } from '@/components/modals/SchedulesPanel'

interface SchedulesPageProps {
  /** 现有定时任务数据源（useSchedules · 后端 CRUD 全走它，本页不新增请求通道） */
  schedulesApi: UseSchedules
  showToast: (msg: string, type?: 'success' | 'info') => void
  /** 稿子 ⑨ 8.2「去会话」：跳到某条运行记录实际用的会话（A/B/C 形态才有）。缺省则不显示该按钮 */
  onOpenSession?: (sessionId: string) => void
}

/** 时间展示：解析失败回退原文（与 SchedulesPanel 的降级口径一致，不抛错）。 */
function formatDateTime(v: string): string {
  const d = new Date(v)
  if (Number.isNaN(d.getTime())) return v
  return d.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

/** 项目列：boundProject 是绝对路径 ⇒ 取末段展示；无锚按归属回落。 */
function projectLabel(s: Schedule): string {
  const p = s.boundProject?.trim()
  if (p) {
    const seg = p.split(/[\\/]/).filter(Boolean)
    return seg[seg.length - 1] ?? p
  }
  if (s.sessionId) return '会话内'
  return '—'
}

/** 频率列：cron → 人类可读（复用设置页同一实现）；once / interval 各自直出。 */
function frequencyLabel(s: Schedule): string {
  if (s.kind === 'cron') return s.cron ? cronToHuman(s.cron) : '—'
  if (s.kind === 'once') return s.runAt ? formatDateTime(s.runAt) : '—'
  return s.intervalSeconds != null ? `每 ${s.intervalSeconds} 秒` : '—'
}

/**
 * 「已暂停」判定。
 *
 * <p>⚠️ 后端 {@code ScheduleDto} / {@code ScheduleRecord} **没有 enabled / paused 列**
 * （只有 kind / cron / runAt / lastRunAt / lastRunStatus）⇒ 不能凭空造一个开关状态。
 * 这里只做**可判定**的推导：一次性任务过了执行时间即不再触发 = 已暂停（已过期）；
 * cron / interval 任务在被删除前始终运行中。
 * <p>将来后端补了 paused 列，只需改本函数一处（分组与状态点都从它派生）。
 */
function isPausedSchedule(s: Schedule): boolean {
  if (s.kind !== 'once' || !s.runAt) return false
  const t = new Date(s.runAt).getTime()
  return !Number.isNaN(t) && t < Date.now()
}

/**
 * 状态点三态（稿子 ⑧ · 绿=上次成功 / 红=上次失败 / 灰=已暂停）。
 *
 * <p>⚠️ 中断（cancelled）**不算失败**（稿子 ⑩：用户自己点的停止，不需要告警）⇒ 归绿色。
 * 从没跑过的任务也没有失败信号 ⇒ 绿色。
 */
function statusDot(s: Schedule): 'run' | 'err' | 'pause' {
  if (isPausedSchedule(s)) return 'pause'
  const st = (s.lastRunStatus ?? '').toLowerCase()
  if (st === 'error' || st === 'failed') return 'err'
  return 'run'
}

/** 状态文案（与状态点同源） */
function statusText(s: Schedule): string {
  const dot = statusDot(s)
  if (dot === 'pause') return '已暂停'
  if (dot === 'err') return '上次失败'
  return '运行中'
}

/* ------------------------------------------------------------------ */
/*  运行记录（稿子 ⑨）                                                  */
/* ------------------------------------------------------------------ */

/** 运行状态 → 文案（稿子 ⑨：成功 / 失败 / 中断） */
function runStatusText(status: string | undefined | null): string {
  switch ((status ?? '').toLowerCase()) {
    case 'ok':
    case 'success':
      return '成功'
    case 'error':
    case 'failed':
      return '失败'
    case 'cancelled':
    case 'canceled':
      return '中断'
    case 'running':
      return '运行中'
    default:
      return '—'
  }
}

/** 运行状态 → 颜色类（`.run-ok.ok` / `.err` / `.run`） */
function runStatusClass(status: string | undefined | null): string {
  switch ((status ?? '').toLowerCase()) {
    case 'ok':
    case 'success':
      return 'ok'
    case 'error':
    case 'failed':
      return 'err'
    default:
      return 'run'
  }
}

/** 耗时：毫秒 → 人话（稿子 ⑨ 的 `42.3s`） */
function runDuration(ms: number | null | undefined): string {
  if (ms == null || !Number.isFinite(ms)) return '—'
  if (ms < 1000) return `${Math.round(ms)}ms`
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`
  const m = Math.floor(ms / 60000)
  const s = Math.round((ms % 60000) / 1000)
  return `${m}m${s}s`
}

/** 运行时间：今天 / 昨天 / 具体日期（稿子 ⑨ 的 `今天 08:57`） */
function runTimeLabel(v: string | null | undefined): string {
  if (!v) return '—'
  const d = new Date(v)
  if (Number.isNaN(d.getTime())) return v
  const now = new Date()
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const p = (n: number) => String(n).padStart(2, '0')
  const hm = `${p(d.getHours())}:${p(d.getMinutes())}`
  if (d.getTime() >= startOfToday) return `今天 ${hm}`
  if (d.getTime() >= startOfToday - 86400000) return `昨天 ${hm}`
  return `${d.getMonth() + 1}-${p(d.getDate())} ${hm}`
}

/** 按天分组（今天 / 昨天 / 更早） */
function groupRunsByDay(runs: ScheduleRun[]): { label: string; items: ScheduleRun[] }[] {
  const now = new Date()
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const buckets: { label: string; items: ScheduleRun[] }[] = [
    { label: '今天', items: [] },
    { label: '昨天', items: [] },
    { label: '更早', items: [] },
  ]
  for (const r of runs) {
    const t = r.startedAt ? new Date(r.startedAt).getTime() : NaN
    if (!Number.isNaN(t) && t >= startOfToday) buckets[0].items.push(r)
    else if (!Number.isNaN(t) && t >= startOfToday - 86400000) buckets[1].items.push(r)
    else buckets[2].items.push(r)
  }
  return buckets.filter((b) => b.items.length > 0)
}

/** 通知投递结果（后端 `schedule_runs.notify_result_json`） */
interface NotifyResultRow {
  chatId?: string
  chatName?: string
  platform?: string
  ok?: boolean
  error?: string
}

function parseNotifyResults(raw: string | null | undefined): NotifyResultRow[] {
  if (!raw) return []
  try {
    const v = JSON.parse(raw)
    return Array.isArray(v) ? (v as NotifyResultRow[]) : []
  } catch {
    return []
  }
}

/**
 * 主区「定时任务」页（WORK / CODING · 2026-10-28）。
 *
 * <p>设计权威：`定时任务完整交互设计.html` 的 ⑧（任务列表）与 ⑨（运行记录）。
 *
 * <p>数据能力复用现有 `useSchedules`：列表、刷新、创建全部走同一 hook 与后端端点，
 * 添加/编辑表单复用设置页的 {@link ScheduleAddModal} / {@link ScheduleEditModal}。
 * 运行记录读**新端点** `GET /api/v1/schedules/runs/all`（后端 `schedule_runs` 表）。
 */
export function SchedulesPage({ schedulesApi, showToast, onOpenSession }: SchedulesPageProps) {
  const { list, loading, error, activeSessionId, refresh, createSchedule, updateSchedule, deleteSchedule } = schedulesApi
  const [tab, setTab] = useState<'tasks' | 'runs'>('tasks')
  const [query, setQuery] = useState('')
  const [addingKind, setAddingKind] = useState<ScheduleKind | null>(null)
  const [editing, setEditing] = useState<Schedule | null>(null)
  const [runs, setRuns] = useState<ScheduleRun[]>([])
  const [runsLoading, setRunsLoading] = useState(false)
  const [runsError, setRunsError] = useState<string | null>(null)
  const [openRun, setOpenRun] = useState<ScheduleRun | null>(null)
  const [sessions, setSessions] = useState<SessionDto[]>([])
  /** 稿子 ⑦：待确认删除的任务（null = 不弹窗） */
  const [confirmDel, setConfirmDel] = useState<Schedule | null>(null)

  /** 会话标题（A 形态胶囊里的「会话 · 周报整理」） */
  useEffect(() => {
    let alive = true
    void sessionApi.list()
      .then((list2) => { if (alive) setSessions(list2) })
      .catch(() => { /* 拿不到会话列表 ⇒ A 形态胶囊退化成短 id */ })
    return () => { alive = false }
  }, [])

  const sessionTitle = useCallback(
    (id: string) => sessions.find((s) => s.id === id)?.title?.trim() || undefined,
    [sessions],
  )

  const loadRuns = useCallback(async () => {
    setRunsLoading(true)
    setRunsError(null)
    try {
      setRuns(await scheduleApi.allRuns(100))
    } catch (e) {
      setRunsError(e instanceof ApiError ? e.userMessage() : String(e))
    } finally {
      setRunsLoading(false)
    }
  }, [])

  // 运行记录 tab 首次进入时拉一次（⛔ 不轮询：运行记录是低频变化的历史）
  useEffect(() => {
    if (tab === 'runs') void loadRuns()
  }, [tab, loadRuns])

  const matches = (s: Schedule) => {
    const q = query.trim().toLowerCase()
    if (!q) return true
    return [s.name, projectLabel(s), frequencyLabel(s), capsuleDetail(s, sessionTitle) ?? '']
      .some((t) => t.toLowerCase().includes(q))
  }

  const filtered = useMemo(
    () => list.filter(matches),
    // matches 是纯函数（只依赖 query/list/sessions）；把依赖列全，避免每次渲染新函数入依赖。
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [list, query, sessions],
  )

  /** 三组：运行中 → 已暂停 → 最近失败（稿子 ⑧ 的分组顺序；空组不显示标题） */
  const groups = useMemo(() => {
    const running = filtered.filter((s) => statusDot(s) === 'run')
    const paused = filtered.filter((s) => statusDot(s) === 'pause')
    const failed = filtered.filter((s) => statusDot(s) === 'err')
    return [
      { key: 'running', title: '运行中', items: running },
      { key: 'paused', title: '已暂停', items: paused },
      { key: 'failed', title: '最近失败', items: failed },
    ].filter((g) => g.items.length > 0)
  }, [filtered])

  /** 运行记录：按摘要 / 任务名过滤（时间倒序由后端给） */
  const filteredRuns = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return runs
    return runs.filter((r) =>
      [r.scheduleName ?? '', r.outputSummary ?? '', r.error ?? '', r.prompt ?? '']
        .some((t) => t.toLowerCase().includes(q)),
    )
  }, [runs, query])

  const runGroups = useMemo(() => groupRunsByDay(filteredRuns), [filteredRuns])

  /**
   * 稿子 ⑩：任务数达上限（50）时**点「添加自动化」直接提示，不进表单**。
   *
   * <p>50 与后端 `ScheduleService.MAX_JOBS` 同源（对齐 CC `CronCreateTool.ts:25`）——
   * 这里只是前置拦截；后端仍会抛 `MaxJobsExceededException`（409 + `errorCode:"3"`）兜底，
   * 所以即便前端判据漂了也不会落一条超限任务。
   */
  const MAX_JOBS = 50
  const openAdd = () => {
    if (list.length >= MAX_JOBS) {
      showToast(`定时任务已达上限 ${MAX_JOBS} 个，请先删掉一些`, 'info')
      return
    }
    setAddingKind('cron')
  }

  const tabBtn = (key: 'tasks' | 'runs', label: string, icon: ReactNode) => (
    <button
      type="button"
      className={`sch-tab${tab === key ? ' on' : ''}`}
      onClick={() => setTab(key)}
    >
      {icon}
      {label}
    </button>
  )

  const onSaveEdit = async (form: ScheduleEditFormValue) => {
    try {
      // ⛔ 只发该 kind 对应的调度字段（复用设置页同一实现）—— 多发会被后端按错 kind 注册 trigger
      await updateSchedule(form.id, buildRequest(form, editing?.kind ?? 'cron'))
      showToast(`已更新: ${form.name}`, 'success')
      setEditing(null)
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    }
  }

  return (
    <div className="schedules-page">
      <div className="sch-head">
        <div className="sch-tabs">
          {tabBtn('tasks', '定时任务', (
            <svg className="sch-ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round">
              <circle cx="12" cy="12" r="8.4" />
              <path d="M12 7v5l3 2" />
            </svg>
          ))}
          {tabBtn('runs', '运行记录', (
            <svg className="sch-ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round">
              <path d="M4 12a8 8 0 1 0 2.3-5.6" />
              <path d="M4 4v4h4" />
              <path d="M12 8v4l3 2" />
            </svg>
          ))}
        </div>
        <div className="sch-actions">
          <div className="sch-search">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.7} strokeLinecap="round">
              <circle cx="10.7" cy="10.7" r="6.4" />
              <path d="M15.5 15.5L20 20" />
            </svg>
            <input
              value={query}
              placeholder="搜索自动化/记录"
              aria-label="搜索定时任务"
              onChange={(e) => setQuery(e.target.value)}
            />
          </div>
          <button
            type="button"
            className="sch-icon-btn"
            title="刷新"
            aria-label="刷新"
            onClick={() => { void refresh(); if (tab === 'runs') void loadRuns() }}
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.7} strokeLinecap="round" strokeLinejoin="round">
              <path d="M20 11a8 8 0 1 0-2.3 6.3" />
              <path d="M20 4v7h-7" />
            </svg>
          </button>
          <button
            type="button"
            className="sch-btn ghost"
            disabled
            title="批量管理暂未开放（现有后端无暂停/批量接口）"
          >
            批量管理
          </button>
          <button
            type="button"
            className="sch-btn primary"
            onClick={openAdd}
          >
            添加自动化
          </button>
        </div>
      </div>

      <div className="sch-body">
        {(loading || error) && (
          <div className="sch-state-line">
            {loading && <span>加载中…</span>}
            {error && <span className="sch-err">· {error}</span>}
          </div>
        )}

        {tab === 'tasks' ? (
          <>
            {/* 区块 A · 系统任务（消息扫描）：非 AgentTool 任务，只有「配置」一个入口 */}
            <ScanTaskPanel showToast={showToast} />

            {/* 区块 B · AI 任务：到点把一段 prompt 交给 AI 执行（列表与新建入口沿用原样） */}
            <section className="sch-sys-block">
              <div className="sch-sys-blk-h">
                <span className="sch-sys-blk-ic ai">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.7} strokeLinecap="round" strokeLinejoin="round">
                    <path d="M12 3.6l1.85 4.85 4.85 1.85-4.85 1.85L12 17l-1.85-4.85L5.3 10.3l4.85-1.85z" />
                  </svg>
                </span>
                <span className="sch-sys-blk-t">AI 任务</span>
                <span className="sch-sys-blk-c">{filtered.length}</span>
                <span className="sch-sys-blk-sub">到点把一段 prompt 交给 AI 执行 · 这一类才能新建</span>
                <div className="sch-sys-blk-right">
                  <button type="button" className="sch-sys-btn sm" onClick={openAdd}>＋ 新建 AI 任务</button>
                </div>
              </div>

              {list.length === 0 && !loading ? (
                /* 稿子 ⑧ 7.1：空态三层（标题 / 说明 / 例子），不是一句话 */
                <div className="sch-empty">
                  <div>
                    <div className="big">还没有 AI 任务</div>
                    <div>点「＋ 新建 AI 任务」，让 Nexus 按你的时间表自动干活</div>
                    <div style={{ marginTop: 12, fontSize: 11.5, color: 'var(--ink-faint)' }}>
                      例：每天早上整理昨天的聊天记录 · 每小时检查一次备份
                    </div>
                  </div>
                </div>
              ) : filtered.length === 0 ? (
                <div className="sch-empty">
                  {query.trim()
                    ? `没有匹配「${query.trim()}」的 AI 任务`
                    : '还没有 AI 任务'}
                </div>
              ) : (
                <>
                  {groups.map((g) => (
                    <div key={g.key} className="sch-group">
                      <div className="sch-group-head">{g.title} <span>{g.items.length}</span></div>
                      {g.items.map((s) => {
                        const dot = statusDot(s)
                        // 稿子 ⑩：A 形态绑定的会话被删了 ⇒ fire 时会**自动降级为 B（每次新建）**。
                        //   判据必须带 `sessions.length > 0`：会话列表还没加载完时 sessions 为空，
                        //   否则每个 A 形态任务都会被误报成「会话已删除」。
                        const orphanSession = s.sessionMode === 'REUSE'
                          && !!s.sessionId
                          && sessions.length > 0
                          && !sessions.some((x) => x.id === s.sessionId)
                        return (
                          <div
                            key={s.id}
                            className="sch-row"
                            title="点击编辑这个任务"
                            onClick={() => setEditing(s)}
                          >
                            <span className={`sd ${dot}`} />
                            <span className="sch-name" title={s.name}>{s.name}</span>
                            <OwnCapsule mode={modeOf(s)} detail={capsuleDetail(s, sessionTitle)} />
                            <span className="sch-meta">
                              {orphanSession ? '⚠ 会话已删除 · 将每次新建' : frequencyLabel(s)}
                            </span>
                            <span className={`sch-state${dot === 'err' ? ' err' : dot === 'pause' ? '' : ' run'}`}>
                              {statusText(s)}
                            </span>
                            <span className="chev">›</span>
                          </div>
                        )
                      })}
                    </div>
                  ))}
                </>
              )}
            </section>
          </>
        ) : runsLoading && runs.length === 0 ? (
          <div className="sch-empty">加载运行记录…</div>
        ) : runsError ? (
          <div className="sch-empty">运行记录读取失败：{runsError}</div>
        ) : filteredRuns.length === 0 ? (
          <div className="sch-empty">{query.trim() ? '没有匹配的运行记录' : '暂无运行记录'}</div>
        ) : (
          <>
            {runGroups.map((g) => (
              <div key={g.label} className="sch-group">
                <div className="sch-group-head">{g.label} <span>{g.items.length}</span></div>
                {g.items.map((r) => (
                  <div
                    key={r.id}
                    className="run-row"
                    title="点击看这次执行的详情"
                    onClick={() => setOpenRun(r)}
                  >
                    <span className="run-time">{runTimeLabel(r.startedAt)}</span>
                    <span className={`run-ok ${runStatusClass(r.status)}`}>{runStatusText(r.status)}</span>
                    <span className="run-dur">{runDuration(r.durationMs)}</span>
                    <span className="run-body">
                      {r.scheduleName}
                      {r.outputSummary ? ` · ${r.outputSummary}` : r.error ? ` · ${r.error}` : ''}
                    </span>
                    <span className="chev">›</span>
                  </div>
                ))}
              </div>
            ))}
          </>
        )}
      </div>

      {editing && (
        <ScheduleEditModal
          schedule={editing}
          onSave={(form) => void onSaveEdit(form)}
          onCancel={() => setEditing(null)}
          onRemove={() => { setConfirmDel(editing); setEditing(null) }}
        />
      )}

      {/* 稿子 ⑦：删除确认按形态分级（C 会级联删专属会话 ⇒ 文案与按钮都不同） */}
      {confirmDel && (
        <DeleteScheduleModal
          schedule={confirmDel}
          sessionTitle={confirmDel.sessionId ? sessionTitle(confirmDel.sessionId) : undefined}
          runCount={runs.filter((r) => r.scheduleId === confirmDel.id).length}
          onCancel={() => setConfirmDel(null)}
          onConfirm={() => {
            const s = confirmDel
            setConfirmDel(null)
            void deleteSchedule(s.id)
              .then(() => showToast('已删除定时任务', 'success'))
              .catch((e) => showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info'))
          }}
        />
      )}

      {addingKind && (
        <ScheduleAddModal
          kind={addingKind}
          activeSessionId={activeSessionId}
          onChangeKind={setAddingKind}
          onSave={async (req) => {
            // ⚠️ 不在这里吞异常：后端拒绝要抛回弹窗内显示（稿子 ⑤ 4.4）
            await createSchedule(req)
            showToast(`已添加: ${req.name}`, 'success')
            setAddingKind(null)
          }}
          onCancel={() => setAddingKind(null)}
        />
      )}

      {openRun && (
        <RunDetailModal
          run={openRun}
          onClose={() => setOpenRun(null)}
          onOpenSession={onOpenSession}
        />
      )}
    </div>
  )
}

/* ------------------------------------------------------------------ */
/*  运行记录详情弹窗（稿子 ⑨ 8.2 / 8.3）                                  */
/* ------------------------------------------------------------------ */

/**
 * 一次执行的详情。
 *
 * <p>有会话的形态（A/B/C）⇒ 「对话过程」复用主会话的 {@link MessageList}（不重写一套渲染）；
 * D 形态不产生会话 ⇒ 只显示 prompt + 结果摘要。
 */
function RunDetailModal({
  run,
  onClose,
  onOpenSession,
}: {
  run: ScheduleRun
  onClose: () => void
  /** 稿子 ⑨ 8.2「去会话」· 宿主没给（设置页里的弹窗场景）就不渲染该按钮 */
  onOpenSession?: (sessionId: string) => void
}) {
  const [messages, setMessages] = useState<ChatMessageDto[] | null>(null)
  const [msgError, setMsgError] = useState<string | null>(null)
  const [usage, setUsage] = useState<{ input: number; output: number; cost: number } | null>(null)

  const sessionId = run.sessionId ?? null

  useEffect(() => {
    if (!sessionId) { setMessages(null); return }
    let alive = true
    void chatApi.listMessages(sessionId)
      .then((msgs) => { if (alive) setMessages(msgs) })
      .catch((e) => { if (alive) setMsgError(e instanceof ApiError ? e.userMessage() : String(e)) })
    return () => { alive = false }
  }, [sessionId])

  // 成本：后端 schedule_runs **没有** run 级用量列 ⇒ 只能取会话累计（明确标注，不冒充本次）
  useEffect(() => {
    if (!sessionId) { setUsage(null); return }
    let alive = true
    void sessionApi.usage(sessionId)
      .then((u) => {
        if (!alive) return
        setUsage({
          input: u.totals?.inputTokens ?? 0,
          output: u.totals?.outputTokens ?? 0,
          cost: u.totals?.costYuan ?? 0,
        })
      })
      .catch(() => { /* 老后端无该端点 ⇒ 不显示成本 */ })
    return () => { alive = false }
  }, [sessionId])

  const mode = (run.sessionMode === 'NEW_EACH' || run.sessionMode === 'NEW_ONCE' || run.sessionMode === 'NONE')
    ? run.sessionMode
    : 'REUSE'
  const notifyRows = parseNotifyResults(run.notifyResultJson)
  const failed = runStatusClass(run.status) === 'err'

  return (
    <div className="fm-backdrop" onClick={onClose}>
      <div className="fm-modal run-detail" onClick={(e) => e.stopPropagation()}>
        <div className="detail-h">
          <span className={`sd ${failed ? 'err' : runStatusClass(run.status) === 'ok' ? 'run' : 'pause'}`} />
          <span className="t">{run.scheduleName}</span>
          <span className="after">{runTimeLabel(run.startedAt)} · {runDuration(run.durationMs)}</span>
        </div>
        <div className="detail-b">
          <div className="drow">
            <span className="k">归属</span>
            <span className="v">
              <OwnCapsule mode={mode} detail={mode === 'NONE' ? null : (mode === 'REUSE' ? null : 'cron')} />
            </span>
          </div>
          <div className="drow">
            <span className="k">触发方式</span>
            <span className="v">{run.triggerSource === 'manual' ? '手动触发' : 'Quartz 定时触发'}</span>
          </div>
          <div className="drow">
            <span className="k">执行的 prompt</span>
            <span className="v">
              <div className="prompt-box">{run.prompt ?? '—'}</div>
            </span>
          </div>
          <div className="drow">
            <span className="k">对话过程</span>
            <span className="v">
              {!sessionId ? (
                <span className="run-detail-note">—（不绑定会话的形态不产生对话记录，只有 prompt 与结果摘要）</span>
              ) : msgError ? (
                <span className="run-detail-note">读取对话失败：{msgError}</span>
              ) : messages === null ? (
                <span className="run-detail-note">加载对话…</span>
              ) : messages.length === 0 ? (
                <span className="run-detail-note">这个会话还没有消息记录</span>
              ) : (
                <div className="msgflow">
                  <MessageList
                    sessionId={sessionId}
                    messages={messages}
                    onDelete={() => {}}
                  />
                </div>
              )}
            </span>
          </div>
          <div className="drow">
            <span className="k">结果摘要</span>
            <span className="v">
              <div className={`output-box${failed ? ' err' : ''}`}>
                {run.outputSummary ?? run.error ?? '—（没有结果摘要）'}
              </div>
            </span>
          </div>
          <div className="drow">
            <span className="k">通知</span>
            <span className="v">
              {notifyRows.length === 0 ? (
                <span className="run-detail-note">未配置通知目标（或这次没有值得推送的内容）</span>
              ) : (
                notifyRows.map((n, i) => (
                  <span key={n.chatId ?? i} className={n.ok ? 'run-notify ok' : 'run-notify err'}>
                    {n.ok ? `✓ 已投递到 ${n.chatName ?? n.chatId ?? '目标'}` : `✗ 投递失败：${n.error ?? '未知原因'}`}
                  </span>
                ))
              )}
            </span>
          </div>
          <div className="drow">
            <span className="k">成本</span>
            <span className="v" style={{ fontFamily: 'var(--font-mono)', fontSize: 11.5 }}>
              {usage
                ? `会话累计 输入 ${usage.input.toLocaleString('zh-CN')} · 输出 ${usage.output.toLocaleString('zh-CN')} tokens · ¥${usage.cost.toFixed(2)}`
                : '—（后端未记录本次运行的用量）'}
            </span>
          </div>
        </div>
        <div className="fm-footer">
          <span className="left-note">
            {sessionId && onOpenSession
              ? `会话「${run.scheduleName}」就是这次执行写进去的地方`
              : ''}
          </span>
          <span className="spacer"></span>
          <button className="fm-btn" onClick={onClose}>关闭</button>
          {/* 稿子 ⑨ 8.2：A/B/C 形态才有会话 ⇒ D 形态（sessionId 为空）不显示 */}
          {sessionId && onOpenSession && (
            <button
              className="fm-btn primary"
              onClick={() => { onOpenSession(sessionId); onClose() }}
            >
              去会话 →
            </button>
          )}
        </div>
      </div>
    </div>
  )
}
