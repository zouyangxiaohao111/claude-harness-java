import { useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { ApiError } from '@/api/rest'
import type { Schedule, ScheduleKind } from '@/api/types'
import type { UseSchedules } from '@/hooks/useSchedules'
import { ScheduleAddModal, cronToHuman } from '@/components/modals/SchedulesPanel'

interface SchedulesPageProps {
  /** 现有定时任务数据源（useSchedules · 后端 CRUD 全走它，本页不新增请求通道） */
  schedulesApi: UseSchedules
  showToast: (msg: string, type?: 'success' | 'info') => void
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
 * 主区「定时任务」页（WORK / CODING · 2026-10-28）。
 *
 * <p>设计权威：`.zjkycode/brainstorm/left-sidebar/content/nexusai-workspace.html` 的
 * `#schedules`（tabs 定时任务 / 运行记录 · 搜索 / 刷新 / 批量管理 / 添加自动化 ·
 * 运行中 / 已暂停分组）。
 *
 * <p>数据能力复用现有 `useSchedules`：列表、刷新、创建全部走同一 hook 与后端端点，
 * 添加表单复用设置页的 {@link ScheduleAddModal} —— ⛔ 不发明第二套后端或第二份表单。
 * 运行记录没有独立后端端点 ⇒ 用现有 `lastRunAt / lastRunStatus` 派生（只读展示，不伪造执行历史）。
 */
export function SchedulesPage({ schedulesApi, showToast }: SchedulesPageProps) {
  const { list, loading, error, canCreate, refresh, createSchedule } = schedulesApi
  const [tab, setTab] = useState<'tasks' | 'runs'>('tasks')
  const [query, setQuery] = useState('')
  const [addingKind, setAddingKind] = useState<ScheduleKind | null>(null)

  const matches = (s: Schedule) => {
    const q = query.trim().toLowerCase()
    if (!q) return true
    return [s.name, projectLabel(s), frequencyLabel(s)]
      .some((t) => t.toLowerCase().includes(q))
  }

  const filtered = useMemo(
    () => list.filter(matches),
    // matches 是纯函数（只依赖 query/list）；把 query 作为依赖即可，避免每次渲染新函数入依赖。
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [list, query],
  )

  const running = filtered.filter((s) => !isPausedSchedule(s))
  const paused = filtered.filter(isPausedSchedule)

  /** 运行记录：没有独立运行历史端点 ⇒ 由每个任务最近一次运行派生（时间倒序）。 */
  const runs = useMemo(
    () => list
      .filter((s) => s.lastRunAt)
      .filter((s) => {
        const q = query.trim().toLowerCase()
        if (!q) return true
        return [s.name, projectLabel(s)].some((t) => t.toLowerCase().includes(q))
      })
      .sort((a, b) => new Date(b.lastRunAt as string).getTime() - new Date(a.lastRunAt as string).getTime()),
    [list, query],
  )

  const openAdd = () => {
    if (!canCreate) {
      showToast('请先打开一个会话再添加定时任务：任务需要归属一个会话', 'info')
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
            onClick={() => void refresh()}
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
            title={canCreate ? undefined : '请先打开一个会话再添加定时任务'}
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
          list.length === 0 && !loading ? (
            <div className="sch-empty">暂无定时任务 · 点击「添加自动化」开始</div>
          ) : filtered.length === 0 ? (
            <div className="sch-empty">
              {query.trim() ? `没有匹配「${query.trim()}」的定时任务` : '暂无定时任务 · 点击「添加自动化」开始'}
            </div>
          ) : (
            <>
              {running.length > 0 && (
                <div className="sch-group">
                  <div className="sch-group-head">运行中 <span>{running.length}</span></div>
                  {running.map((s) => (
                    <div key={s.id} className="sch-row">
                      <span className="sd run" />
                      <span className="sch-name" title={s.name}>{s.name}</span>
                      <span className="sch-meta">{projectLabel(s)}</span>
                      <span className="sch-meta">{frequencyLabel(s)}</span>
                      <span className="sch-state run">运行中</span>
                    </div>
                  ))}
                </div>
              )}
              {paused.length > 0 && (
                <div className="sch-group">
                  <div className="sch-group-head">已暂停 <span>{paused.length}</span></div>
                  {paused.map((s) => (
                    <div key={s.id} className="sch-row">
                      <span className="sd pause" />
                      <span className="sch-name" title={s.name}>{s.name}</span>
                      <span className="sch-meta">{projectLabel(s)}</span>
                      <span className="sch-meta">{frequencyLabel(s)}</span>
                      <span className="sch-state">已暂停</span>
                    </div>
                  ))}
                </div>
              )}
            </>
          )
        ) : runs.length === 0 ? (
          <div className="sch-empty">{query.trim() ? '没有匹配的运行记录' : '暂无运行记录'}</div>
        ) : (
          <div className="sch-group">
            <div className="sch-group-head">最近运行 <span>{runs.length}</span></div>
            {runs.map((s) => {
              const ok = s.lastRunStatus === 'ok' || s.lastRunStatus === 'success'
              // 后端 lastRunStatus 只有 'ok' | 'error' | null（ScheduleDto 注释）⇒ 中文二分，不直出英文。
              const statusText = ok ? '成功' : s.lastRunStatus === 'error' ? '失败' : (s.lastRunStatus ?? '—')
              return (
                <div key={s.id} className="sch-row">
                  <span className={`sd ${ok ? 'run' : 'pause'}`} />
                  <span className="sch-name" title={s.name}>{s.name}</span>
                  <span className="sch-meta">{s.lastRunAt ? formatDateTime(s.lastRunAt) : '—'}</span>
                  <span className="sch-meta">{projectLabel(s)}</span>
                  <span className={`sch-state${ok ? ' run' : ''}`}>{statusText}</span>
                </div>
              )
            })}
          </div>
        )}
      </div>

      {addingKind && (
        <ScheduleAddModal
          kind={addingKind}
          onChangeKind={setAddingKind}
          onSave={async (req) => {
            try {
              await createSchedule(req)
              showToast(`已添加: ${req.name}`, 'success')
              setAddingKind(null)
            } catch (e) {
              const msg = e instanceof ApiError ? e.userMessage() : String(e)
              showToast(msg, 'info')
            }
          }}
          onCancel={() => setAddingKind(null)}
        />
      )}
    </div>
  )
}
