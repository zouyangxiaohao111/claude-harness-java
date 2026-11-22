import { useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { FormModal } from '@/components/ui/FormModal'
import { Icon } from '@/icons'
import { ApiError } from '@/api/rest'
import { sessionApi } from '@/api/sessions'
import { projectApi } from '@/api/projects'
import type { ProjectDto } from '@/api/projects'
import { imApi } from '@/api/im'
import type { ImChat, ImInstance, ImPlatform } from '@/api/im'
import { agentApi } from '@/api/agent'
import type {
  Schedule,
  ScheduleKind,
  SessionMode,
  SessionDto,
  AgentListItem,
  CreateScheduleRequest,
  UpdateScheduleRequest,
} from '@/api/types'
import type { UseSchedules } from '@/hooks/useSchedules'

interface SchedulesPanelProps {
  /** 真实后端 CRUD API */
  schedulesApi: UseSchedules
  showToast: (msg: string, type?: 'success' | 'info') => void
}

const KIND_LABEL: Record<ScheduleKind, string> = {
  cron: 'Cron',
  once: '单次',
  interval: '间隔',
}

/** 归属胶囊里的调度类型短词（稿子 ⑧ 的胶囊文案用的是小写 `cron`） */
const KIND_SHORT: Record<ScheduleKind, string> = {
  cron: 'cron',
  once: '单次',
  interval: '间隔',
}

/**
 * 生命周期（scope）选项 —— ⛔ [V306] **已从 UI 移除**。
 *
 * <p>设计稿 ③ 把「任务生命周期（仅本会话 / 持久）」换成「会话形态 A/B/C/D」卡片网格：
 * 用户要选的是「这次执行用哪个会话」，不是「任务活多久」——后者由形态**推导**出来
 * （A 跟会话走 · B/C/D 跟任务走 ⇒ 后端把非 REUSE 形态的 scope 归一为 DURABLE）。
 * ⛔ 但 scope 字段本身**没删**（后端仍用它判生命周期），只是不再是用户可选项。
 */

/* ------------------------------------------------------------------ */
/*  会话形态（A/B/C/D）· 稿子 ③                                         */
/* ------------------------------------------------------------------ */

/** 归属胶囊的字母徽标（稿子 ⑧：A 蓝 / B 绿 / C 紫 / D 灰） */
export const MODE_BADGE: Record<SessionMode, 'A' | 'B' | 'C' | 'D'> = {
  REUSE: 'A',
  NEW_EACH: 'B',
  NEW_ONCE: 'C',
  NONE: 'D',
}

/** 归属胶囊的形态词（稿子 ⑧ 的胶囊文案） */
export const MODE_LABEL: Record<SessionMode, string> = {
  REUSE: '会话',
  NEW_EACH: '每次新建',
  NEW_ONCE: '专属会话',
  NONE: '不绑会话',
}

interface ModeMeta {
  /** 卡片名（稿子 ③） */
  name: string
  /** 卡片副句（稿子 ③） */
  desc: string
  /** 明细行的副标题（稿子 ③ 的 `md-sub`） */
  sub: string
  /** 明细行的 2×2 网格（标签 / 值） */
  grid: [string, string][]
}

/** 四种形态的卡片文案与明细行（稿子 ③ 逐字对齐） */
export const MODE_META: Record<SessionMode, ModeMeta> = {
  REUSE: {
    name: '绑定已有会话',
    desc: '写进你选的那个会话，能读到它全部历史',
    sub: '— 寄生在你挑的会话里',
    grid: [
      ['结果写进', '选中的会话'],
      ['能读历史', '该会话全部历史'],
      ['会话活到', '跟那个会话走'],
      ['项目目录', '该会话绑定的项目'],
    ],
  },
  NEW_EACH: {
    name: '每次触发都新建',
    desc: '全新上下文，不读历史',
    sub: '— 每次一个一次性会话',
    grid: [
      ['结果写进', '每次新建的会话'],
      ['能读历史', '零历史（全新）'],
      ['会话活到', '30 天后自动清理'],
      ['项目目录', '<安装目录>/cron'],
      ['渠道 / 模式', '必须选渠道；自动开 Brief 与无人值守，Ask 禁用'],
    ],
  },
  NEW_ONCE: {
    // 「长期助手」不是「任务的一种形态」，而是「一个真会话 + 一个闹钟」：
    // 它会出现在左栏、能选专家、能绑渠道、你能随时打开接着聊；
    // 定时只是「到点把它叫醒」这一件事。
    name: '长期助手',
    desc: '建一个真会话，到点叫醒它',
    sub: '— 一个常驻会话 + 定时唤醒',
    grid: [
      ['结果写进', '这个助手自己的会话'],
      ['能读历史', '累积在它自己的上下文里'],
      ['会话活到', '任务删除时才删'],
      ['项目目录', '<安装目录>/cron'],
      ['渠道 / 模式', '必须选渠道；自动开 Brief 与无人值守，Ask 禁用'],
    ],
  },
  NONE: {
    name: '不绑定会话',
    desc: '后台静默执行，最省资源',
    sub: '— 后台静默，不产生任何会话',
    grid: [
      ['结果写进', '哪儿都不写'],
      ['能读历史', '零历史（全新）'],
      ['会话活到', '—（不产生会话）'],
      ['项目目录', '无（回落进程目录）'],
      ['渠道 / 模式', '必须选渠道（结果只发渠道）；Brief 与无人值守随执行生效，Ask 禁用'],
    ],
  },
}

/**
 * 归属胶囊（稿子 ⑧ · `.own` + `.own.A|B|C|D` + `.kk`）。
 *
 * <p>形态来自后端 `ScheduleDto.sessionMode`（缺省 REUSE ⇒ 存量行显示 A）。
 * `detail` 是形态后面的具体位置：A ⇒ 会话名 · B/C ⇒ 调度类型 · D ⇒ 无。
 */
export function OwnCapsule({ mode, detail }: { mode: SessionMode; detail?: string | null }) {
  const b = MODE_BADGE[mode]
  const text = detail ? `${MODE_LABEL[mode]} · ${detail}` : MODE_LABEL[mode]
  return (
    <span className={`own ${b}`}>
      <span className="kk">{b}</span>
      {text}
    </span>
  )
}

/** 读侧形态：后端缺省/脏值一律按 REUSE（对齐后端 `SessionMode.parseOrDflt`） */
export function modeOf(s: Pick<Schedule, 'sessionMode'>): SessionMode {
  const m = s.sessionMode
  return m === 'NEW_EACH' || m === 'NEW_ONCE' || m === 'NONE' ? m : 'REUSE'
}

/* ------------------------------------------------------------------ */
/*  「专家」（主线程 agent）· V317                                       */
/* ------------------------------------------------------------------ */

/**
 * 该形态能否指定「专家」。
 *
 * <p>只有**任务自己建会话**的两种形态（B 每次新建 / C 新建后绑定）能指定 ——
 * 专家最终写进 `sessions.main_thread_agent`，而 A（复用用户自己的会话）不该被任务改掉
 * 用户的持久设置，D（不产生会话）压根没有会话可写。
 * ⛔ 与后端 `ScheduleService.validateMainThreadAgent` 同一判据（前端只做展示与预筛）。
 */
export function modeAcceptsExpert(mode: SessionMode): boolean {
  return mode === 'NEW_EACH' || mode === 'NEW_ONCE'
}

/**
 * 该形态是否必须配置渠道（与后端 ScheduleService.validateChannelPrecondition 同口径）。
 *
 * <p>B/C/D 执行时没有「人正开着会话」可应答，结果只能靠渠道送达 ⇒ 至少要选一个聊天。
 */
export function modeNeedsChannel(mode: SessionMode): boolean {
  return mode === 'NEW_EACH' || mode === 'NEW_ONCE' || mode === 'NONE'
}

/**
 * 「专家」下拉（V317 · 主线程 agent）。
 *
 * <p>数据源**复用会话专家选择器同一个 API**（{@code agentApi.listAgents} → GET /agents/list）
 * ⇒ ⛔ 不新造专家清单接口（两套来源必然漂移：用户在技能市场看到的专家 ≠ 任务能选的专家）。
 *
 * <p>值 = agentType 串：后端 `sessions.main_thread_agent` 存的就是它
 * （与 `SessionService.update` 写会话列同一口径）。
 *
 * <p>⚠️ 任务上存着的专家若已从 registry 消失，仍要显示出来（标「已不存在」）——
 * 否则用户打开编辑框只看到一个空下拉，看不出自己之前选过什么。
 */
export function ExpertPicker({
  value,
  onChange,
  sessionId,
}: {
  value: string
  onChange: (v: string) => void
  /** 取列表时的会话视图（可空 = 进程默认视图） */
  sessionId?: string | null
}) {
  const [agents, setAgents] = useState<AgentListItem[]>([])
  const [loading, setLoading] = useState(true)
  const [open, setOpen] = useState(false)

  useEffect(() => {
    let alive = true
    void agentApi
      .listAgents(sessionId ?? '')
      .then((list) => { if (alive) setAgents(list ?? []) })
      .catch(() => { if (alive) setAgents([]) })
      .finally(() => { if (alive) setLoading(false) })
    return () => { alive = false }
  }, [sessionId])

  const current = agents.find((a) => a.agentType === value)
  // 任务上存着的专家若已从 registry 消失，仍要能看见并改掉它
  const ghost = value && !current ? value : null

  const chips: { label: string; desc: string; key: string }[] = [
    { key: '', label: '不指定', desc: '按任务默认方式跑（不套用专家）' },
    ...(ghost ? [{ key: ghost, label: ghost, desc: '这个专家已经不存在了 —— 建议换一个' }] : []),
    ...agents.map((a) => ({ key: a.agentType, label: a.agentType, desc: a.whenToUse || '（这个专家没写用途说明）' })),
  ]
  const chosen = chips.find((c) => c.key === (value ?? '')) ?? chips[0]

  return (
    <>
      {/* 默认只显示一行「专家 · xxx」⇒ 点它才弹选择器。
          常驻铺开整张列表会把「执行时绑到哪个会话」这一节撑得很长，
          而大多数任务并不指定专家。 */}
      <div className="np-chans">
        <span
          className={`chan${value ? ' on' : ''}`}
          // 未指定时用虚线框：空态要「一眼看出这里可以点」，
          // 和已选的实线药丸形成对比（用户原话：虚线框 ＋ 这个很好）
          style={value ? undefined : { borderStyle: 'dashed', color: 'var(--ink-subtle)' }}
          role="button"
          tabIndex={0}
          title={value ? `当前专家：${value} · 点击更换` : '选择专家'}
          onClick={(e) => { e.preventDefault(); e.stopPropagation(); setOpen(true) }}
          onKeyDown={(e) => {
            if (e.key === 'Enter' || e.key === ' ') {
              e.preventDefault(); e.stopPropagation(); setOpen(true)
            }
          }}
        >
          {value ? `专家 · ${chosen.label}` : '＋ 选择专家'}
          {value && (
            <span
              className="x"
              role="button"
              aria-label="清空专家"
              onClick={(e) => { e.preventDefault(); e.stopPropagation(); onChange('') }}
            >
              ×
            </span>
          )}
        </span>
      </div>

      {open && (
        <div
          style={{
            position: 'fixed', inset: 0, zIndex: 600,
            background: 'rgba(0,0,0,.35)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
          }}
          onClick={() => setOpen(false)}
        >
          <div
            style={{
              width: 480, maxHeight: '68vh', display: 'flex', flexDirection: 'column',
              background: 'var(--canvas)', borderRadius: 12, boxShadow: '0 12px 40px rgba(0,0,0,.18)',
            }}
            onClick={(e) => e.stopPropagation()}
          >
            <div style={{ padding: '14px 16px 10px', fontWeight: 600 }}>选择专家</div>
            <div style={{ overflowY: 'auto', padding: '0 12px 12px' }}>
              {loading ? (
                <div className="fm-field-hint">加载专家列表…</div>
              ) : (
                <div className="fm-radio-cards" style={{ gridTemplateColumns: '1fr' }}>
                  {chips.map((c) => {
                    const on = c.key === (value ?? '')
                    return (
                      <div
                        key={c.key || '__none'}
                        className={`fm-radio-item card${on ? ' on' : ''}`}
                        role="radio"
                        aria-checked={on}
                        tabIndex={0}
                        onClick={() => { onChange(c.key); setOpen(false) }}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter' || e.key === ' ') {
                            e.preventDefault(); onChange(c.key); setOpen(false)
                          }
                        }}
                      >
                        <span className="fm-radio-dot" />
                        <span className="fm-radio-name">{c.label}</span>
                        <span className="fm-radio-desc">{c.desc}</span>
                      </div>
                    )
                  })}
                </div>
              )}
            </div>
          </div>
        </div>
      )}
    </>
  )
}
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
  // ⚠️ 日字段写 '?'（Quartz 的「本字段无特定值」）也要当通配 —— 设计稿 ② 的默认 cron
  //   `0 57 8 ? * *`（秒 分 时 日 月 周）正是这个形态，否则人话预览会退化成「未识别调度」。
  if ((dom === '*' || dom === '?') && month === '*') {
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
 * 胶囊里的「具体位置」：A ⇒ 绑的会话（无会话列表时退化成短 id）· B/C ⇒ 调度类型 · D ⇒ 无。
 *
 * <p>⚠️ 不再展示 scope：scope 已不是用户可选项（见 {@link MODE_META}），展示它只会把
 * 「实现细节」当成「归属」—— 四种形态里只有 A 的 scope 是 SESSION。
 */
export function capsuleDetail(
  s: Schedule,
  sessionTitle?: (id: string) => string | undefined,
): string | null {
  const mode = modeOf(s)
  if (mode === 'REUSE') {
    if (!s.sessionId) return null
    return sessionTitle?.(s.sessionId) ?? shortId(s.sessionId)
  }
  if (mode === 'NONE') return null
  return KIND_SHORT[s.kind]
}

/**
 * SchedulesPanel · Phase C4 联调版
 *
 * <p>所有 CRUD 走真实后端 API（useSchedules hook）。
 * <p>command 字段在 v1 不开放给用户（后端写死为 "test"），所以 UI 上不展示。
 * <p>"立即运行" 调真实 /run 端点，toast 显示后端返回的 executed/output。
 */
export function SchedulesPanel({ schedulesApi, showToast }: SchedulesPanelProps) {
  const { list, loading, error, activeSessionId, createSchedule, updateSchedule, deleteSchedule, runNow } = schedulesApi

  const [editing, setEditing] = useState<Schedule | null>(null)
  const [addingKind, setAddingKind] = useState<ScheduleKind | null>(null)
  const [pending, setPending] = useState(false)
  /** 稿子 ⑦：待确认删除的任务（null = 不弹窗） */
  const [confirmDel, setConfirmDel] = useState<Schedule | null>(null)

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

  const onRemove = (s: Schedule) => setConfirmDel(s)

  /** 稿子 ⑦：四种形态删除后果不同 ⇒ 走自定义弹窗（C 会级联删专属会话） */
  const doRemove = () => {
    const s = confirmDel
    if (!s) return
    setConfirmDel(null)
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
          title="添加定时任务"
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
                <span style={{ marginLeft: 6 }}>
                  <OwnCapsule mode={modeOf(s)} detail={capsuleDetail(s)} />
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
                <Icon.Edit />
              </button>
              <button
                className="skill-remove"
                onClick={() => onRemove(s)}
                title="删除"
                disabled={pending}
              >
                <Icon.Trash />
              </button>
            </div>
          </div>
        )
      })}

      {editing && (
        <ScheduleEditModal
          schedule={editing}
          onSave={onSaveEdit}
          onCancel={() => setEditing(null)}
          onRemove={() => { onRemove(editing); setEditing(null) }}
        />
      )}

      {addingKind && (
        <ScheduleAddModal
          kind={addingKind}
          activeSessionId={activeSessionId}
          onChangeKind={setAddingKind}
          onSave={async (req) => {
            // ⚠️ 不包 wrap：后端拒绝要抛回弹窗内显示（稿子 ⑤ 4.4），
            //    wrap 会吞掉异常 ⇒ 既看不到原因，弹窗还会被误关
            await createSchedule(req)
            showToast(`已添加: ${req.name}`, 'success')
            setAddingKind(null)
          }}
          onCancel={() => setAddingKind(null)}
        />
      )}

      {confirmDel && (
        <DeleteScheduleModal
          schedule={confirmDel}
          onCancel={() => setConfirmDel(null)}
          onConfirm={doRemove}
        />
      )}
    </div>
  )
}

/**
 * 编辑任务弹窗（稿子 ⑥）。
 *
 * <p><b>哪些能改</b>：调度类型（kind 建任务时锁定，⛔ 不能改 —— 改 kind 等于换任务）、
 * 会话形态 ✅、通知目标 ✅、cron/时间/间隔/命令 ✅。
 *
 * <p><b>哪些不能改</b>：`scope` / `sessionId` —— [cwd3 D6] 创建后不可变（后端非 null 即 400，
 * 防「先建任意任务再改成 DURABLE」绕过会话锚）。
 *
 * <p>⚠️ [V306] 改**会话形态**会自动解除旧的会话绑定（后端把 `session_id` 置 null）：
 * 那个会话不会被删（左栏还找得到），只是这个任务不再往里写。所以不要把「形态」和「会话」
 * 当成两个独立开关 —— 形态变了，绑定的会话就换了。
 */
export function ScheduleEditModal({
  schedule,
  onSave,
  onCancel,
  onRemove,
}: {
  schedule: Schedule
  onSave: (form: ScheduleEditFormValue) => void
  onCancel: () => void
  onRemove: () => void
}) {
  // [V330] 与新建弹窗同一份判据（modeNeedsChannel）：B/C/D 无渠道时保存前拦下。
  const [channelError, setChannelError] = useState<string | null>(null)
  return (
    <FormModal<ScheduleEditFormValue>
      title="编辑定时任务"
      subtitle={schedule.name}
      initial={toEditForm(schedule)}
      sections={[
        {
          title: '基础信息',
          fields: [
            { type: 'text', name: 'name', label: '任务名', placeholder: 'e.g. 每晚全量备份' },
            { type: 'locked', label: '调度类型', render: () => (
              <KindCards
                value={schedule.kind}
                onChange={() => {}}
                examples={kindExamplesOf(schedule)}
                disabled
              />
            ) },
            // ⛔ [2026-11-17 用户裁定] 「描述」已删（纯死字段，见添加弹窗同处注释）
          ],
        },
        {
          title: '调度',
          fields: scheduleFieldsFor(schedule.kind, toEditForm(schedule)),
        },
        {
          title: '执行时绑到哪个会话',
          fields: [
            {
              type: 'custom',
              name: 'sessionMode',
              label: '会话形态',
              render: (value, set) => {
                const m = (value as SessionMode) ?? 'REUSE'
                return (
                  <>
                    <ModeCards value={m} onChange={(x) => set(x)} />
                    <ModeDetail mode={m} />
                    {m === 'NONE' && <ModeWarnD />}
                  </>
                )
              },
            },
          ],
        },
        {
          // [V317] 「专家」只在新建会话的形态下出现 —— A 复用用户自己的会话（改了就是改用户的设置）、
          //   D 压根不产生会话（没地方放专家）。visibleWhen 读的是**当前表单值** ⇒ 切形态即显隐。
          title: '用哪个专家',
          visibleWhen: (values) => modeAcceptsExpert(values.sessionMode as SessionMode),
          fields: [
            {
              type: 'custom',
              name: 'mainThreadAgent',
              label: '专家',
              render: (value, set) => (
                <ExpertPicker
                  value={(value as string) ?? ''}
                  onChange={(v) => set(v)}
                  sessionId={schedule.sessionId}
                />
              ),
            },
          ],
        },
        {
          // 顺序：先定「跑在哪个会话 / 用哪个专家」，再写要它做什么 ——
          // prompt 是「让这个专家做的事」，专家在前读起来才顺。
          title: '行为',
          fields: [
            { type: 'textarea', name: 'command', label: '命令（prompt）', rows: 3, placeholder: '任务触发时执行的指令…' },
          ],
        },
        {
          // 「绑定已有会话」不配通知渠道：结果直接落那个会话里，主体是会话的 agent
          //（= Claude 模式）；B/C/D 必须有渠道（后端同口径）。
          title: '执行完成后通知 *',
          visibleWhen: (values) => modeNeedsChannel(values.sessionMode as SessionMode),
          fields: [
            {
              type: 'custom',
              name: 'notify',
              label: '通知目标',
              hint: '首次新建 = 绑定渠道：你在该渠道说话会接着这个会话继续；每次新建 = 只把结果发给你（接不上上一轮）。',
              render: (value, set) => {
                const v = (value as { chats: string[]; channelIds: string[] })
                  ?? { chats: [], channelIds: [] }
                return (
                  <>
                    <NotifyPicker
                      chats={v.chats}
                      channelIds={v.channelIds}
                      onChatsChange={(c) => { setChannelError(null); set({ ...v, chats: c }) }}
                      onChannelIdsChange={(ids) => set({ ...v, channelIds: ids })}
                    />
                    {channelError && <div className="fm-field-hint error">{channelError}</div>}
                  </>
                )
              },
            },
          ],
        },
      ]}
      onSave={(v) => {
        const chats = (v.notify as { chats?: string[] } | undefined)?.chats ?? []
        if (modeNeedsChannel(v.sessionMode) && chats.length === 0) {
          setChannelError('需先配置渠道：至少勾选一个聊天（结果从这里送达）')
          return
        }
        setChannelError(null)
        onSave(v)
      }}
      onCancel={onCancel}
      destructiveLabel="移除此任务 · 不可恢复"
      onDestructive={onRemove}
    />
  )
}

/* ------------------------------------------------------------------ */
/*  内部：编辑表单类型 + 转换                                            */
/* ------------------------------------------------------------------ */

export interface ScheduleEditFormValue {
  id: string
  name: string
  cron: string
  intervalSeconds: number
  runAt: string
  description: string
  /** 任务命令（prompt）· 后端 Schedule.command 接受，UI 现在可编辑 */
  command: string
  /** [V306] 执行形态（A/B/C/D）· 可改（后端 update 已支持） */
  sessionMode: SessionMode
  /**
   * [V317] 任务指定的「专家」（主线程 agent）· 空串 = 不指定。
   *
   * <p>只有 B/C 形态有效；A/D 形态保存时一律发空串（= 清掉旧值）——
   * 后端 update 的 null 语义是「不改」，不发就会把旧专家留在行上。
   */
  mainThreadAgent: string
  /**
   * [V306] 通知目标 —— 打包成一个键，因为 FormModal 的 custom 字段只给 `(value, set)`，
   * 拿不到「同一表单里的另一个键」；两个值拆成两个键会让 NotifyPicker 看不到彼此。
   */
  notify: { chats: string[]; channelIds: string[] }
}

const toEditForm = (s: Schedule): ScheduleEditFormValue => ({
  id: s.id,
  name: s.name,
  cron: s.cron ?? '',
  intervalSeconds: s.intervalSeconds ?? 0,
  runAt: s.runAt ? s.runAt.slice(0, 16) : '',
  description: s.description,
  command: s.command ?? '',
  sessionMode: s.sessionMode ?? 'REUSE',
  mainThreadAgent: s.mainThreadAgent ?? '',
  notify: {
    chats: s.notifyChats ?? [],
    channelIds: s.notifyChannelIds ?? [],
  },
})

const scheduleFieldsFor = (kind: ScheduleKind, _form: ScheduleEditFormValue) => {
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

/**
 * 编辑请求体 · **只发该 kind 对应的那一个调度字段**。
 *
 * <p>⛔ 别把 cron / intervalSeconds / runAt 三个都发：后端 update 会按 kind 重新注册
 * Quartz trigger，多余字段会让「间隔任务」被按 cron 注册。
 */
export const buildRequest = (form: ScheduleEditFormValue, kind: ScheduleKind): CreateScheduleRequest | UpdateScheduleRequest => {
  const req: CreateScheduleRequest = {
    name: form.name,
    kind,
    description: form.description,
  }
  // 命令（prompt）可编辑后随请求提交；空值不发送（后端保留默认/旧值）
  if (form.command?.trim()) req.command = form.command.trim()
  // [V306] 执行形态 / 通知目标（后端 update 已支持这两个字段；空数组 = 清空 ⇒ 不通知）
  req.sessionMode = form.sessionMode
  req.notifyChats = form.notify?.chats ?? []
  req.notifyChannelIds = form.notify?.channelIds ?? []
  // [V317] 专家：B/C 形态带选中的 agentType；A/D 形态发空串 = 清掉旧值。
  //   ⚠️ 必须**显式发**：后端 update 的 null 语义是「不改」⇒ 不发就等于留着旧专家。
  req.mainThreadAgent = modeAcceptsExpert(form.sessionMode)
    ? (form.mainThreadAgent ?? '').trim()
    : ''
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
/*  内部：添加表单（自定义 modal · 稿子 ①②③④）                          */
/* ------------------------------------------------------------------ */

interface AddFormValue {
  name: string
  description: string
  /** 调度类型（cron / once / interval） */
  kind: ScheduleKind
  cron: string
  intervalSeconds: number
  runAt: string
  /** 任务内容（prompt）· 后端 Schedule.command */
  command: string
  /** 会话形态（A/B/C/D）· 稿子 ③ */
  sessionMode: SessionMode
  /**
   * [V317] 任务指定的「专家」（主线程 agent）· 空串 = 不指定。
   * 只有 B/C 形态有效；切到 A/D 形态时清空（那个形态没有专家的载体）。
   */
  mainThreadAgent: string
  /** A 形态绑定的会话 id（其它形态不用） */
  sessionId: string
  /** 通知目标：聊天 id（`im_chat.id`）· 稿子 ④ */
  notifyChats: string[]
  /** 通知目标：冗余渠道 id */
  notifyChannelIds: string[]
}

/** 稿子 ②：三种调度类型的卡片文案 */
const KIND_CARD_META: Record<ScheduleKind, { name: string; desc: string }> = {
  cron: { name: 'Cron 周期', desc: '按日历规律重复' },
  once: { name: '单次执行', desc: '到点跑一次就结束' },
  interval: { name: '固定间隔', desc: '每隔一段时间跑一次' },
}

/** 稿子 ② 的三个线性图标（循环箭头 / 日历 / 时钟） */
const KIND_ICON: Record<ScheduleKind, ReactNode> = {
  cron: (
    <svg className="kc-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round">
      <path d="M21 12a9 9 0 1 1-2.6-6.4" />
      <path d="M21 3v5h-5" />
      <path d="M12 8v4l2.5 1.5" />
    </svg>
  ),
  once: (
    <svg className="kc-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round">
      <rect x="3.5" y="5" width="17" height="15.5" rx="2.5" />
      <path d="M8 2.8v3.6M16 2.8v3.6M3.5 10h17" />
      <circle cx="12" cy="15.5" r="1.6" fill="currentColor" stroke="none" />
    </svg>
  ),
  interval: (
    <svg className="kc-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round">
      <circle cx="12" cy="12" r="8.6" />
      <path d="M12 7.4V12l3 1.8" />
      <path d="M3.4 12H6M18 12h2.6" />
    </svg>
  ),
}

/** 稿子 ②：右上角对勾 */
const IconCheck = () => (
  <svg className="kc-check" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.4} strokeLinecap="round" strokeLinejoin="round">
    <path d="M20 6L9 17l-5-5" />
  </svg>
)

/** 间隔秒数 → 人话（稿子 ② 的示例 chip 文案） */
function humanInterval(sec: number): string {
  if (!Number.isFinite(sec) || sec <= 0) return '—'
  if (sec < 60) return `每 ${sec} 秒`
  if (sec < 3600) return `每 ${Math.round(sec / 60)} 分钟`
  const h = sec / 3600
  return `每 ${Number.isInteger(h) ? h : h.toFixed(1)} 小时`
}

/** 一次执行时间 → chip 文案（MM-DD HH:mm） */
function humanRunAt(runAt: string): string {
  if (!runAt) return '—'
  const d = new Date(runAt)
  if (Number.isNaN(d.getTime())) return runAt
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

/** 三种类型的示例 chip（随当前输入实时变） */
function kindExamplesOf(f: {
  kind: ScheduleKind
  cron: string | null
  intervalSeconds: number | null
  runAt: string | null
}): Record<ScheduleKind, string> {
  const raw = f.cron?.trim()
  return {
    cron: raw ? cronToHuman(raw) : '—',
    once: humanRunAt(f.runAt ?? ''),
    interval: humanInterval(f.intervalSeconds ?? 0),
  }
}

/**
 * 调度类型卡片（稿子 ② · `.kind-card` / `.kc-ico` / `.kc-name` / `.kc-desc` / `.kc-eg` / `.kc-check`）。
 *
 * <p>取代原生 `<select>`：三种类型本质差异只有「什么时候跑」，卡片用图标锚定语义、
 * 用等宽示例 chip 给出具体形态。
 */
export function KindCards({
  value,
  onChange,
  examples,
  disabled,
}: {
  value: ScheduleKind
  onChange: (k: ScheduleKind) => void
  examples: Record<ScheduleKind, string>
  disabled?: boolean
}) {
  const order: ScheduleKind[] = ['cron', 'once', 'interval']
  return (
    <div className="kind-cards" role="radiogroup" aria-label="调度类型">
      {order.map((k) => {
        const on = k === value
        return (
          <div
            key={k}
            className={`kind-card${on ? ' on' : ''}${disabled ? ' dis' : ''}`}
            role="radio"
            aria-checked={on}
            tabIndex={disabled ? -1 : 0}
            onClick={() => { if (!disabled) onChange(k) }}
            onKeyDown={(e) => {
              if (disabled) return
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault()
                onChange(k)
              }
            }}
          >
            <IconCheck />
            {KIND_ICON[k]}
            <div className="kc-name">{KIND_CARD_META[k].name}</div>
            <div className="kc-desc">{KIND_CARD_META[k].desc}</div>
            <div className="kc-eg">{examples[k]}</div>
          </div>
        )
      })}
    </div>
  )
}

/**
 * 会话形态卡片（稿子 ③）。
 *
 * <p>⚠️ 用**仓库现成**的 `.fm-radio-cards` / `.fm-radio-item.card` / `.fm-radio-dot` /
 * `.fm-radio-name` / `.fm-radio-desc` —— ⛔ 不自造一套单选卡片（两套视觉语言会打架）。
 */
export function ModeCards({
  value,
  onChange,
  modes,
}: {
  value: SessionMode
  onChange: (m: SessionMode) => void
  /** 只显示这些形态（缺省全部 4 种）。IM 扫描规则传 3 种 —— 它不给 NONE（看不到结果没意义）。 */
  modes?: SessionMode[]
}) {
  const order: SessionMode[] = modes ?? ['REUSE', 'NEW_EACH', 'NEW_ONCE', 'NONE']
  return (
    <div
      className="fm-radio-cards"
      style={{ gridTemplateColumns: 'repeat(2, 1fr)' }}
      role="radiogroup"
      aria-label="会话形态"
    >
      {order.map((m) => {
        const on = m === value
        const meta = MODE_META[m]
        return (
          <div
            key={m}
            className={`fm-radio-item card${on ? ' on' : ''}`}
            role="radio"
            aria-checked={on}
            tabIndex={on ? 0 : -1}
            onClick={() => onChange(m)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault()
                onChange(m)
              }
            }}
          >
            <span className="fm-radio-dot" />
            <span className="fm-radio-name">{meta.name}</span>
            <span className="fm-radio-desc">{meta.desc}</span>
          </div>
        )
      })}
    </div>
  )
}

/**
 * 形态明细行（稿子 ③ · `.mode-detail` / `.md-head` / `.md-badge` / `.md-grid`）。
 *
 * <p>⚠️ **明细行独立于卡片网格**（不进 grid）⇒ 换选形态时网格不重排（稿子 ① 的三个设计要点之一）。
 */
export function ModeDetail({ mode }: { mode: SessionMode }) {
  const meta = MODE_META[mode]
  const b = MODE_BADGE[mode]
  return (
    <div className="mode-detail">
      <div className="md-head">
        <span className={`md-badge ${b}`}>{b}</span>
        <span className="md-title">{meta.name}</span>
        <span className="md-sub">{meta.sub}</span>
      </div>
      <div className="md-grid">
        {meta.grid.map(([k, v]) => (
          <div key={k}><b>{k}</b> {v}</div>
        ))}
      </div>
    </div>
  )
}

/** 稿子 ③ 3.4：D 形态的黄色取舍提示 */
function ModeWarnD() {
  return (
    <div className="banner warn">
      <svg className="bi" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}>
        <path d="M12 3l9.5 16.5H2.5L12 3z" />
        <path d="M12 9v4M12 16.5h.01" />
      </svg>
      <div>
        <b>选了这一种，事后看不到推理过程。</b>
        运行记录里只有 prompt + 结果摘要，点不开完整对话。如果你想事后能翻过程，选「新建后绑定住」。
      </div>
    </div>
  )
}

/* ------------------------------------------------------------------ */
/*  删除确认 · 稿子 ⑦（四种形态的删除后果不同）                            */
/* ------------------------------------------------------------------ */

/**
 * 稿子 ⑦ 删除确认弹窗。
 *
 * <p><b>为什么不能只用一句 `confirm()`</b>：四种形态删掉的**东西不一样** ——
 * C（新建后绑定住）会<b>级联删掉专属会话和它的全部执行记录</b>（不可恢复），
 * B（每次触发都新建）的历史会话反而全部保留、只保留 30 天。用同一句「删除定时任务？」，
 * 用户在 C 上根本不知道自己顺手删掉了一整段对话。
 *
 * <p>所以 C 的**按钮文案**也随之变成「连同会话一起删除」（动作与后果对齐），
 * 其余三种仍是「删除任务」。
 *
 * @param schedule   待删任务（只要 name / sessionMode / sessionId）
 * @param sessionTitle A·C 形态对应会话的标题（拿不到则省略会话名那一行）
 * @param runCount   C 形态的历史执行次数（拿不到则省略该行）
 */
export function DeleteScheduleModal({
  schedule,
  sessionTitle,
  runCount,
  onCancel,
  onConfirm,
}: {
  schedule: Pick<Schedule, 'name' | 'sessionMode' | 'sessionId'>
  sessionTitle?: string
  runCount?: number
  onCancel: () => void
  onConfirm: () => void
}) {
  const mode = modeOf(schedule)
  const isC = mode === 'NEW_ONCE'

  return (
    <div className="fm-backdrop" onClick={onCancel}>
      <div className="fm-modal" style={{ maxWidth: 520 }} onClick={(e) => e.stopPropagation()}>
        <div className="fm-header">
          <span className="fm-status-dot"></span>
          <span className="fm-title">{isC ? '连同会话一起删除？' : '删除定时任务'}</span>
          <span className="fm-subtitle">{MODE_BADGE[mode]} · {MODE_META[mode].name}</span>
        </div>
        <div className="fm-body">
          <div className="fm-field">
            <p style={{ margin: 0 }}>
              删除「<b>{schedule.name}</b>」？
            </p>
            {isC && (
              <>
                <p style={{ margin: '8px 0 0' }}>
                  这个任务会<strong>连同它的专属会话一起删除</strong>。
                </p>
                <div className="banner warn" style={{ marginTop: 12 }}>
                  <svg className="bi" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}>
                    <path d="M12 3l9.5 16.5H2.5L12 3z" />
                    <path d="M12 9v4M12 16.5h.01" />
                  </svg>
                  <div>
                    {schedule.sessionId && (
                      <div>会话「{sessionTitle || schedule.sessionId}」</div>
                    )}
                    {typeof runCount === 'number' && runCount > 0 && (
                      <div>该会话 <b>{runCount}</b> 次执行记录</div>
                    )}
                    <div>
                      会话里的对话内容会一起消失，无法恢复。
                      若想保留，先去左栏把那个会话另存。
                    </div>
                  </div>
                </div>
              </>
            )}
            {mode === 'NEW_EACH' && (
              <p style={{ margin: '8px 0 0', color: 'var(--text-2)' }}>
                只删除这个定时任务。之前执行产生的会话会<strong>保留</strong>
                （30 天后自动清理），运行记录也会保留。
              </p>
            )}
            {mode === 'REUSE' && (
              <p style={{ margin: '8px 0 0', color: 'var(--text-2)' }}>
                只删任务，<strong>不动那个会话</strong>（会话是你自己的，可能还在聊）。
              </p>
            )}
            {mode === 'NONE' && (
              <p style={{ margin: '8px 0 0', color: 'var(--text-2)' }}>
                只删任务，运行记录保留。
              </p>
            )}
          </div>
        </div>
        <div className="fm-footer">
          <span className="left-note">
            {isC ? '此操作不可恢复' : '删除后可在上方重新添加'}
          </span>
          <span className="spacer"></span>
          <button className="fm-btn" onClick={onCancel}>取消</button>
          <button className={`fm-btn ${isC ? 'danger' : 'primary'}`} onClick={onConfirm}>
            {isC ? '连同会话一起删除' : '删除任务'}
          </button>
        </div>
      </div>
    </div>
  )
}

/* ------------------------------------------------------------------ */
/*  通知目标两级选择器（稿子 ④）                                          */
/* ------------------------------------------------------------------ */

const PLATFORM_LABEL: Record<ImPlatform, string> = {
  wechat: '微信',
  feishu: '飞书',
  wecom: '企微',
  dingtalk: '钉钉',
}

/** 平台图标（稿子 ④ 给了微信 / 飞书；企微沿用稿子 ⑥ 的圆环；钉钉用其闪电标记） */
const PLATFORM_ICON: Record<ImPlatform, ReactNode> = {
  wechat: (
    <svg className="ci" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.7}>
      <path d="M21 11.5a8.4 8.4 0 0 1-9 8.4 8.5 8.5 0 0 1-3.8-.9L3 21l1.9-5.2A8.4 8.4 0 0 1 12 3.1a8.4 8.4 0 0 1 9 8.4z" />
    </svg>
  ),
  feishu: (
    <svg className="ci" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.7}>
      <path d="M15 3a6 6 0 0 1 6 6v3a6 6 0 0 1-6 6h-2l-4 3v-3a6 6 0 0 1-4-6V9a6 6 0 0 1 6-6z" />
    </svg>
  ),
  wecom: (
    <svg className="ci" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.7}>
      <circle cx="12" cy="12" r="9" />
      <path d="M8 12h8" />
    </svg>
  ),
  dingtalk: (
    <svg className="ci" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.7}>
      <path d="M13 2L4 14h6l-1 8 9-12h-6l1-8z" />
    </svg>
  ),
}

/**
 * 「你自己」那条的判定。
 *
 * <p>⚠️ **判据只能是「该渠道下最近活跃的那条 p2p 私聊」** —— `im_chat` 表**没有**
 * 「这条对端是不是你」的标记（凭证不出后端，peer_id 是平台侧账号 id，前端无从比对），
 * ⛔ 不能靠 peerName/peerId 猜身份。其余 p2p 仍以普通行展示（可照常勾选），只是不标「你自己」。
 */
function isSelfChat(c: ImChat, selfId: string | null): boolean {
  return c.chatKind === 'p2p' && c.id === selfId
}

/**
 * 通知目标选择器（稿子 ④ · 两级：先渠道、再聊天；两级都可多选）。
 *
 * <p>「通知本人」= 选你自己那条 p2p 私聊（机器人 ↔ 你），它排在最前并标注「和机器人的私聊」。
 * 一个都不勾 = 不通知（结果只在运行记录里看）。
 */
export function NotifyPicker({
  chats,
  channelIds,
  onChatsChange,
  onChannelIdsChange,
}: {
  chats: string[]
  channelIds: string[]
  onChatsChange: (v: string[]) => void
  onChannelIdsChange: (v: string[]) => void
}) {
  const [instances, setInstances] = useState<ImInstance[]>([])
  const [allChats, setAllChats] = useState<ImChat[]>([])
  const [loading, setLoading] = useState(true)
  const [err, setErr] = useState<string | null>(null)
  /** 「＋」是否展开未选渠道列表（默认收起 ⇒ 没选时只看到一个＋） */
  const [pickerOpen, setPickerOpen] = useState(false)
  /** 是否展开「具体会话」明细（默认收起：聊天可能很多） */
  const [chatsOpen, setChatsOpen] = useState(false)

  useEffect(() => {
    let alive = true
    void (async () => {
      try {
        const [ins, chs] = await Promise.all([imApi.listInstances(), imApi.listChats()])
        if (!alive) return
        setInstances(ins)
        setAllChats(chs)
      } catch (e) {
        if (!alive) return
        setErr(e instanceof ApiError ? e.userMessage() : String(e))
      } finally {
        if (alive) setLoading(false)
      }
    })()
    return () => { alive = false }
  }, [])

  // ⚠️ 不过滤 status：`connected` 只表示**入站**长连接在线，与「能不能发消息」无关
  //    （真机上 disconnected 的实例照样 SENT）。按它过滤会把用户配好的渠道藏起来 ——
  //    真机表现为「IM 里明明绑了 3 个，这里只看到一个、加完一个就再没有 ＋」。
  const usable = instances

  /** 某个渠道下的聊天（按「自己那条 → 其它私聊 → 群」排序） */
  const chatsOf = (instanceId: string): { list: ImChat[]; selfId: string | null } => {
    const list = allChats.filter((c) => c.instanceId === instanceId)
    const p2p = list
      .filter((c) => c.chatKind === 'p2p')
      .sort((a, b) => (b.lastMessageAt ?? '').localeCompare(a.lastMessageAt ?? ''))
    const groups = list.filter((c) => c.chatKind !== 'p2p')
    const self = p2p[0] ?? null
    return { list: self ? [self, ...p2p.slice(1), ...groups] : groups, selfId: self?.id ?? null }
  }

  const toggleChannel = (id: string) => {
    if (channelIds.includes(id)) {
      onChannelIdsChange(channelIds.filter((x) => x !== id))
      // 收起渠道 ⇒ 该渠道下选中的聊天一并取消（否则会留下看不见的隐藏选择）
      const { list } = chatsOf(id)
      const ids = new Set(list.map((c) => c.id))
      onChatsChange(chats.filter((x) => !ids.has(x)))
    } else {
      onChannelIdsChange([...channelIds, id])
    }
  }

  const toggleChat = (id: string) => {
    onChatsChange(chats.includes(id) ? chats.filter((x) => x !== id) : [...chats, id])
  }

  const chosen = allChats.filter((c) => chats.includes(c.id))

  if (loading) {
    return <div className="np-empty">加载渠道…</div>
  }
  if (err) {
    return <div className="np-empty">读取渠道失败：{err}</div>
  }
  if (instances.length === 0) {
    return (
      <div className="np-empty">
        还没有绑定任何渠道。去设置 → 外部渠道 绑定后，这里就能选了。
      </div>
    )
  }

  return (
    <div className="np-group">
      <div className="np-chans">
        {usable.filter((i) => channelIds.includes(i.id)).map((i) => {
          const on = true
          return (
            <span
              key={i.id}
              className={`chan${on ? ' on' : ''}`}
              role="button"
              tabIndex={0}
              title={on ? `移除 ${i.displayName}` : `添加 ${i.displayName}`}
              // stopPropagation/preventDefault：这些 span 在 FormModal 的 <label> 里，
              // label 的默认行为会把点击转成「聚焦第一个 input」⇒ 点了像没反应。
              onClick={(e) => { e.preventDefault(); e.stopPropagation(); toggleChannel(i.id) }}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault(); e.stopPropagation(); toggleChannel(i.id)
                }
              }}
            >
              {PLATFORM_ICON[i.platform]}
              {i.displayName}
              {/* 已选的给一个显式 ×：光靠「再点一次取消」没人看得出来 */}
              {on && <span className="x" aria-label={`移除 ${i.displayName}`}>×</span>}
            </span>
          )
        })}
        {/* 一个「＋」入口：不选就只显示它，选了几个就显示几个药丸。
            未连接的渠道**不列**（选不了；带上反而让人以为能加）。 */}
        {usable.length > 0 && (
          <span
            className="chan"
            style={{ borderStyle: 'dashed', color: 'var(--ink-subtle)' }}
            role="button"
            tabIndex={0}
            title="添加通知渠道"
            onClick={(e) => { e.preventDefault(); e.stopPropagation(); setPickerOpen((v) => !v) }}
            onKeyDown={(e) => {
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault(); e.stopPropagation(); setPickerOpen((v) => !v)
              }
            }}
          >
            ＋ 添加通知渠道
          </span>
        )}

        {usable.length === 0 && (
          <span className="np-empty" style={{ padding: 0 }}>
            没有可用的渠道 —— 渠道未连接时发不出去
          </span>
        )}
      </div>

      {/* 聊天明细默认**不铺开**：一个渠道可能有几十个聊天，铺开会把弹窗撑得没法用。
          默认只说明「不选就发到机器人本身」，想看/改具体会话再点开。 */}
      {channelIds.length > 0 && (
        <div
          className="np-sum"
          role="button"
          tabIndex={0}
          style={{ cursor: 'pointer', userSelect: 'none' }}
          onClick={(e) => { e.preventDefault(); e.stopPropagation(); setChatsOpen((v) => !v) }}
          onKeyDown={(e) => {
            if (e.key === 'Enter' || e.key === ' ') {
              e.preventDefault(); e.stopPropagation(); setChatsOpen((v) => !v)
            }
          }}
        >
          {chatsOpen ? '收起具体会话 ▴' : '不指定具体会话 ⇒ 发到机器人自己的会话　（点这里选具体会话 ▾）'}
        </div>
      )}

      {/* 点「＋」弹窗选渠道 —— 直接把未选项铺在药丸行里会让人以为「自动选了第一个」，
          尤其只有一个渠道时根本分不清「点 ＋ 加了」和「弹出来让我选」。 */}
      {pickerOpen && (
        <div
          style={{
            position: 'fixed', inset: 0, zIndex: 600, background: 'rgba(0,0,0,.35)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
          }}
          onClick={() => setPickerOpen(false)}
        >
          <div
            style={{
              width: 420, maxHeight: '60vh', display: 'flex', flexDirection: 'column',
              background: 'var(--canvas)', borderRadius: 12, boxShadow: '0 12px 40px rgba(0,0,0,.18)',
            }}
            onClick={(e) => e.stopPropagation()}
          >
            <div style={{ padding: '14px 16px 10px', fontWeight: 600 }}>通知到哪个机器人</div>
            <div style={{ overflowY: 'auto', padding: '0 12px 12px' }}>
              <div className="fm-radio-cards" style={{ gridTemplateColumns: '1fr' }}>
                {usable.map((i) => {
                  const on = channelIds.includes(i.id)
                  return (
                    <div
                      key={'npc-' + i.id}
                      className={`fm-radio-item card${on ? ' on' : ''}`}
                      role="checkbox"
                      aria-checked={on}
                      tabIndex={0}
                      onClick={(e) => {
                        e.preventDefault(); e.stopPropagation()
                        toggleChannel(i.id)
                      }}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter' || e.key === ' ') {
                          e.preventDefault(); e.stopPropagation(); toggleChannel(i.id)
                        }
                      }}
                    >
                      <span className="fm-radio-dot" />
                      <span className="fm-radio-name">{i.displayName}</span>
                      <span className="fm-radio-desc">
                        {on ? '已选 · 再点一次取消' : '点一下加入通知目标'}
                        {i.status !== 'connected' ? '（入站未连接，不影响发消息）' : ''}
                      </span>
                    </div>
                  )
                })}
              </div>
            </div>
            <div style={{ padding: '0 16px 14px', textAlign: 'right' }}>
              <button className="fm-btn" type="button" onClick={() => setPickerOpen(false)}>完成</button>
            </div>
          </div>
        </div>
      )}

      {chatsOpen && channelIds.map((cid) => {
        const inst = instances.find((i) => i.id === cid)
        if (!inst) return null
        const { list, selfId } = chatsOf(cid)
        const picked = list.filter((c) => chats.includes(c.id)).length
        return (
          <div key={cid} className="np-panel">
            <div className="np-panel-h">
              {inst.displayName}
              <span className="np-count">{list.length === 0 ? '0 个聊天' : `已选 ${picked} / ${list.length}`}</span>
            </div>
            {list.length === 0 ? (
              <div className="np-empty">
                这个渠道还没有同步到任何聊天。
                <br />
                等对方给这个号发一条消息，或去「IM 频道 › 任务」手动发起一次。
              </div>
            ) : (
              list.map((c) => {
                const on = chats.includes(c.id)
                const self = isSelfChat(c, selfId)
                return (
                  <div
                    key={c.id}
                    className={`np-chat${on ? ' on' : ''}`}
                    role="checkbox"
                    aria-checked={on}
                    tabIndex={0}
                    onClick={() => toggleChat(c.id)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggleChat(c.id) }
                    }}
                  >
                    <span className="np-box" />
                    <span className="np-name">{self ? '你自己' : (c.peerName ?? c.peerId)}</span>
                    <span className="np-kind">
                      {self ? '和机器人的私聊' : c.chatKind === 'group' ? `群 · ${c.memberCount ?? 0} 人` : '私聊'}
                    </span>
                  </div>
                )
              })
            )}
          </div>
        )
      })}

      {chosen.length === 0 ? (
        channelIds.length === 0 ? (
          <div className="np-sum"><span className="none">不通知 —— 结果只在「运行记录」里看</span></div>
        ) : (
          <div className="np-sum">
            默认通知到 {channelIds.map((cid) => instances.find((i) => i.id === cid)?.displayName).filter(Boolean).join('、')}
            {' '}机器人本身
            <span className="none">　（点上面的机器人可以改成通知具体会话）</span>
          </div>
        )
      ) : (
        <div className="np-sum">
          通知到{' '}
          {chosen.map((c, i) => (
            <span key={c.id}>
              {i > 0 && '、'}
              <b>{c.peerName ?? c.peerId}</b>（{PLATFORM_LABEL[c.platform]}）
            </span>
          ))}
        </div>
      )}
    </div>
  )
}

/* ------------------------------------------------------------------ */
/*  添加任务弹窗（稿子 ①）                                               */
/* ------------------------------------------------------------------ */

/** 稿子 ②：cron 默认值 = 每天 08:57（避开整点） */
const DEFAULT_CRON = '0 57 8 ? * *'

const emptyAddForm = (kind: ScheduleKind): AddFormValue => ({
  name: '',
  description: '',
  kind,
  cron: DEFAULT_CRON,
  intervalSeconds: 3600,
  runAt: '',
  command: '',
  // 稿子 ① 的默认态 = C 形态（新建后绑定住）
  sessionMode: 'NEW_ONCE',
  mainThreadAgent: '',
  sessionId: '',
  notifyChats: [],
  notifyChannelIds: [],
})

/** 间隔快捷预设（稿子 ② 2.3） */
const INTERVAL_PRESETS: { label: string; seconds: number }[] = [
  { label: '5 分钟', seconds: 300 },
  { label: '30 分钟', seconds: 1800 },
  { label: '1 小时', seconds: 3600 },
  { label: '6 小时', seconds: 21600 },
  { label: '24 小时', seconds: 86400 },
]

/** cron 解析是否成功（`cronToHuman` 认不出来 ⇒ 回退文案带「（未识别调度）」） */
function cronParsable(cron: string): boolean {
  const raw = cron.trim()
  if (!raw) return false
  return !cronToHuman(raw).includes('未识别调度')
}

export function ScheduleAddModal({
  kind,
  onChangeKind,
  onSave,
  onCancel,
  activeSessionId,
}: {
  kind: ScheduleKind
  onChangeKind: (k: ScheduleKind) => void
  onSave: (req: CreateScheduleRequest) => Promise<void>
  onCancel: () => void
  /** 当前活动会话 · A 形态下拉的初值 */
  activeSessionId?: string | null
}) {
  const [form, setForm] = useState<AddFormValue>(() => emptyAddForm(kind))
  const [sessions, setSessions] = useState<SessionDto[]>([])
  /** 项目名映射（会话选择器按项目分组用；拿不到就退化成「其它项目」） */
  const [projects, setProjects] = useState<ProjectDto[]>([])
  const [showErrors, setShowErrors] = useState(false)
  const [saving, setSaving] = useState(false)
  /** 稿子 ⑤ 4.4：后端拒绝（如任务数已达上限）在**弹窗内**显示原因，不弹完就关掉 */
  const [saveError, setSaveError] = useState<string | null>(null)

  // 会话下拉的候选（A 形态）· 按最近活跃倒序
  useEffect(() => {
    let alive = true
    void sessionApi.list()
      .then((list) => {
        if (!alive) return
        setSessions(list)
        // A 形态的默认选中 = 当前活动会话（稿子 ③ 3.1）
        setForm((p) => (p.sessionMode === 'REUSE' && !p.sessionId && activeSessionId
          ? { ...p, sessionId: activeSessionId }
          : p))
      })
      .catch(() => { /* 会话列表拿不到 ⇒ A 形态下拉为空（提示用户去左栏打开一个会话） */ })
    // 项目名只用于会话选择器的分组头；拿不到就退化成「其它项目」，不影响选择
    void projectApi.list()
      .then((ps) => { if (alive) setProjects(ps) })
      .catch(() => { /* 分组头退化，不阻断 */ })
    return () => { alive = false }
  }, [activeSessionId])

  const set = (patch: Partial<AddFormValue>) => setForm((p) => ({ ...p, ...patch }))

  const examples = kindExamplesOf(form)
  const cronBad = form.kind === 'cron' && !cronParsable(form.cron)
  const oncePast = form.kind === 'once' && !!form.runAt && new Date(form.runAt).getTime() < Date.now()
  const nameBad = !form.name.trim()
  const commandBad = !form.command.trim()
  const sessionBad = form.sessionMode === 'REUSE' && !form.sessionId
  // [V330] B/C/D 必须有渠道（后端同口径）：没有渠道时保存被拦下，并指向下方通知区。
  const channelBad = modeNeedsChannel(form.sessionMode) && form.notifyChats.length === 0

  const onSubmit = async () => {
    setShowErrors(true)
    setSaveError(null)
    if (nameBad || commandBad || sessionBad || channelBad || cronBad || oncePast) return
    setSaving(true)
    try {
      await onSave(buildAddRequest(form))
    } catch (e) {
      // 稿子 ⑤ 4.4：后端原因（如 MAX_JOBS 上限）留在弹窗里，别只闪一个 toast 就把用户填的一整屏冲掉
      setSaveError(e instanceof ApiError ? e.userMessage() : String(e))
    } finally {
      setSaving(false)
    }
  }

  const summary = `${KIND_CARD_META[form.kind].name} · ${examples[form.kind]} · ${MODE_META[form.sessionMode].name}`
  /** 稿子 ⑤ 4.1：底部实时摘要 —— 有必填项没填时改说「还有 N 项未填」，比「已选…」更有用 */
  const missingCount = [nameBad, commandBad, sessionBad, channelBad].filter(Boolean).length

  return (
    <div className="fm-backdrop" onClick={onCancel}>
      <div className="fm-modal" onClick={(e) => e.stopPropagation()}>
        <div className="fm-header">
          <span className="fm-status-dot"></span>
          <span className="fm-title">添加调度</span>
          <span className="fm-subtitle">cron / 单次 / 间隔</span>
        </div>
        <div className="fm-body">
          {/* ---- 基础信息 ---- */}
          <div className="fm-section">
            <div className="fm-section-title"><span>基础信息</span></div>
            <div className="fm-field">
              <label className="fm-field-label">任务名 <span className="req">*</span></label>
              <input
                className={`fm-input${showErrors && nameBad ? ' invalid' : ''}`}
                type="text"
                value={form.name}
                placeholder="e.g. 每晚全量备份"
                onChange={(e) => set({ name: e.target.value })}
                autoFocus
              />
              {showErrors && nameBad
                ? <div className="fm-field-hint error">任务名是必填的 —— 后面列表里靠它认出这个任务</div>
                : <div className="fm-field-hint">留空会自动用 prompt 前 20 字代替</div>}
            </div>
            {/*
              ⛔ [2026-11-17 用户裁定] 「描述」输入框已删 —— 它是纯死字段：
                fire 不用（prompt 走 command）、CronListTool 不输出（agent 看不到）、
                主区列表也不显示，全仓唯一消费者是设置页旧面板那行小字。
              后端的 schedules.description 列**保留不动**（存量数据不丢）。
            */}
          </div>

          {/* ---- 调度类型（稿子 ②）---- */}
          <div className="fm-section">
            <div className="fm-section-title"><span>调度类型</span></div>
            <KindCards
              value={form.kind}
              onChange={(k) => { set({ kind: k }); onChangeKind(k) }}
              examples={examples}
            />
            <div className="kind-fields">
              {form.kind === 'cron' && (
                <>
                  <div className="kf-label">cron 表达式</div>
                  <input
                    className={`fm-input mono${showErrors && cronBad ? ' invalid' : ''}`}
                    type="text"
                    value={form.cron}
                    onChange={(e) => set({ cron: e.target.value })}
                  />
                  <div className="kf-human">
                    <span className="kf-arrow">↳</span>
                    {cronBad ? <>看不懂这个表达式</> : <>每天 <b>{examples.cron.replace(/^每天\s*/, '')}</b> 执行</>}
                  </div>
                  <div className="fm-field-hint">秒 分 时 日 月 周 · 「日」和「周」必须有一个写 ?</div>
                </>
              )}
              {form.kind === 'once' && (
                <>
                  <div className="kf-label">执行时间</div>
                  <input
                    className={`fm-input${showErrors && oncePast ? ' invalid' : ''}`}
                    type="datetime-local"
                    value={form.runAt}
                    onChange={(e) => set({ runAt: e.target.value })}
                  />
                  <div className="kf-human">
                    <span className="kf-arrow">↳</span>
                    {oncePast ? <>执行时间不能早于现在</> : <>跑完自动删除</>}
                  </div>
                </>
              )}
              {form.kind === 'interval' && (
                <>
                  <div className="kf-label">间隔</div>
                  <div className="chan-group">
                    {INTERVAL_PRESETS.map((p) => (
                      <span
                        key={p.seconds}
                        className={`chan${form.intervalSeconds === p.seconds ? ' on' : ''}`}
                        role="button"
                        tabIndex={0}
                        onClick={() => set({ intervalSeconds: p.seconds })}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); set({ intervalSeconds: p.seconds }) }
                        }}
                      >
                        {p.label}
                      </span>
                    ))}
                  </div>
                  <input
                    className="fm-input mono"
                    type="number"
                    min={1}
                    value={form.intervalSeconds}
                    onChange={(e) => set({ intervalSeconds: Number(e.target.value) || 0 })}
                  />
                  <div className="kf-human">
                    <span className="kf-arrow">↳</span>
                    每 <b>{humanInterval(form.intervalSeconds).replace(/^每\s*/, '')}</b>执行一次（{form.intervalSeconds} 秒）
                  </div>
                  <div className="fm-field-hint">小于 60 秒可能排队堆积</div>
                </>
              )}
            </div>
          </div>

          {/* ---- 会话形态（稿子 ③）---- */}
          <div className="fm-section">
            <div className="fm-section-title"><span>执行时绑到哪个会话</span></div>
            <ModeCards
              value={form.sessionMode}
              onChange={(m) => set({
                sessionMode: m,
                // [V317] 切到 A/D 形态 ⇒ 专家没有载体 ⇒ 一并清掉选中值
                //   （否则会带着一个永不生效的专家提交，后端按形态相容性拒绝）
                ...(modeAcceptsExpert(m) ? {} : { mainThreadAgent: '' }),
                // 切到 A 形态时默认选中当前活动会话（稿子 ③ 3.1「默认选中：当前活动会话」）
                ...(m === 'REUSE' && !form.sessionId && activeSessionId ? { sessionId: activeSessionId } : {}),
              })}
            />
            <ModeDetail mode={form.sessionMode} />
            {/* [V317] 「专家」只在 B/C 形态出现（A 复用用户自己的会话、D 不产生会话） */}
            {modeAcceptsExpert(form.sessionMode) && (
              <div className="fm-field">
                <label className="fm-field-label">用哪个专家</label>
                <ExpertPicker
                  value={form.mainThreadAgent}
                  onChange={(v) => set({ mainThreadAgent: v })}
                  sessionId={activeSessionId}
                />
                <div className="fm-field-hint">
                  任务执行时新建的会话由该专家驱动（人格 / 系统提示词都换成它的）
                </div>
              </div>
            )}
            {form.sessionMode === 'REUSE' && (
              <div className="fm-field">
                <label className="fm-field-label">绑到哪个会话 <span className="req">*</span></label>
                <SessionPicker
                  sessions={sessions}
                  projects={projects}
                  value={form.sessionId}
                  onChange={(id) => set({ sessionId: id })}
                  activeSessionId={activeSessionId}
                />
                {showErrors && sessionBad ? (
                  <div className="fm-field-hint error">
                    {sessions.length === 0
                      ? '当前没有打开的会话，可以选其他形态，或先去左栏打开一个会话'
                      : '「绑定已有会话」需要选一个会话'}
                  </div>
                ) : (
                  <div className="fm-field-hint">
                    会话被删除后，该任务会<strong>自动降级为「每次触发都新建」</strong>，不会变成跑了没痕迹
                  </div>
                )}
              </div>
            )}
            {form.sessionMode === 'NONE' && <ModeWarnD />}
          </div>

          {/* ---- 任务内容：放在「绑到哪个会话 / 用哪个专家」之后 ---- */}
          {/* 为什么后置：prompt 是「让这个专家做的事」，先定专家再写内容读起来才顺。 */}
          <div className="fm-section">
            <div className="fm-section-title"><span>任务内容</span></div>
            <div className="fm-field">
              <label className="fm-field-label">任务内容（prompt） <span className="req">*</span></label>
              <textarea
                className={`fm-textarea${showErrors && commandBad ? ' invalid' : ''}`}
                rows={3}
                value={form.command}
                placeholder="任务触发时执行的指令…"
                onChange={(e) => set({ command: e.target.value })}
              />
              {showErrors && commandBad && (
                <div className="fm-field-hint error">任务内容是必填的 —— 到点要执行什么？</div>
              )}
            </div>
          </div>

          {/* ---- 通知目标（稿子 ④）----
               只在「每次新建 / 首次新建」出现：绑定已有会话的结果直接落那个会话里，
               主体是会话的 agent（Claude 模式）；不绑会话则只在运行记录里看。 */}
          {modeNeedsChannel(form.sessionMode) && (
          <div className="fm-section">
            <div className="fm-section-title"><span>执行完成后通知 <span className="req">*</span></span></div>
            <NotifyPicker
              chats={form.notifyChats}
              channelIds={form.notifyChannelIds}
              onChatsChange={(v) => set({ notifyChats: v })}
              onChannelIdsChange={(v) => set({ notifyChannelIds: v })}
            />
            {showErrors && channelBad ? (
              <div className="fm-field-hint error">需先配置渠道：至少勾选一个聊天（结果从这里送达）</div>
            ) : (
              <div className="fm-field-hint">
                先选渠道，再勾该渠道下的聊天；<strong>都可多选</strong>。「你自己」= 机器人跟你的私聊，<strong>不是群聊</strong>
              </div>
            )}
          </div>
          )}

          {/* 稿子 ⑤ 4.4：后端拒绝的原因留在弹窗内 */}
          {saveError && (
            <div className="banner warn" style={{ marginTop: 4 }}>
              <svg className="bi" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}>
                <path d="M12 3l9.5 16.5H2.5L12 3z" />
                <path d="M12 9v4M12 16.5h.01" />
              </svg>
              <div>
                <b>保存失败</b>
                <div>{saveError}</div>
              </div>
            </div>
          )}
        </div>
        <div className="fm-footer">
          <span className="left-note">
            {showErrors && missingCount > 0 ? `还有 ${missingCount} 项未填` : `已选：${summary}`}
          </span>
          <span className="spacer"></span>
          <button className="fm-btn" onClick={onCancel} disabled={saving}>取消</button>
          <button className="fm-btn primary" onClick={() => void onSubmit()} disabled={saving}>
            {saving ? '保存中…' : '保存'}
          </button>
        </div>
      </div>
    </div>
  )
}

/** 会话下拉的一行：标题 · 会话 id · 最近活跃（稿子 ③ 3.1） */
/**
 * 「绑到哪个会话」选择器 —— 稿子方案 A：**内联展开的下拉搜索**（2026-11-17 用户拍板）。
 *
 * <p><b>为什么不是原生 `select`</b>：`<option>` 只能塞一行纯文本 ⇒ 项目、消息数、「正在用」
 * 全都看不到，会话一多就是盲翻，也没法搜索。这三点才是「不友好」的实质。
 *
 * <p><b>为什么不是弹窗</b>：本组件嵌在「添加任务」的 `fm-modal` 里，再弹一层就是
 * **弹窗套弹窗**（两层遮罩 / Esc 先关谁 / 焦点陷阱套焦点陷阱）。面板贴在触发器下方即可。
 *
 * <p><b>视觉语言</b>取自仓库现成的 `.ms-pick-*`（模型选择器同款：搜索框 / 分组头 /
 * `.ms-pick-item` / `.ms-pick-check`），⛔ 不自造一套列表样式。
 */
function SessionPicker({
  sessions,
  projects,
  value,
  onChange,
  activeSessionId,
}: {
  sessions: SessionDto[]
  projects: ProjectDto[]
  value: string
  onChange: (id: string) => void
  activeSessionId?: string | null
}) {
  const [open, setOpen] = useState(false)
  const [q, setQ] = useState('')
  const boxRef = useRef<HTMLDivElement>(null)

  // 点面板外 / 按 Esc ⇒ 收起（Esc 只收面板，不关整个任务弹窗）
  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      if (boxRef.current && !boxRef.current.contains(e.target as Node)) setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation()
        setOpen(false)
      }
    }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey, true)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey, true)
    }
  }, [open])

  const cur = sessions.find((s) => s.id === value) ?? null

  /** 每条会话的副行：最后活跃 · 消息条数（选了空会话 = 任务从零开始，读不到历史）。 */
  const metaOf = (s: SessionDto) => {
    const at = s.updatedAt ?? s.time ?? null
    const parts: string[] = []
    if (at) parts.push(humanLastActive(at))
    if (s.messageCount != null) parts.push(`${s.messageCount} 条消息`)
    return parts.join(' · ') || '暂无记录'
  }

  /** 按项目分组（会话跨项目，同名标题不分组会选错）。 */
  const groups = useMemo(() => {
    const kw = q.trim().toLowerCase()
    const hit = kw
      ? sessions.filter((s) => (s.title || '').toLowerCase().includes(kw))
      : sessions
    const byProj = new Map<string, SessionDto[]>()
    for (const s of hit) {
      const k = s.mainProjectId ?? ''
      const arr = byProj.get(k) ?? []
      arr.push(s)
      byProj.set(k, arr)
    }
    return [...byProj.entries()].map(([pid, list]) => ({
      pid,
      name: pid ? (projects.find((p) => p.id === pid)?.name ?? '其它项目') : '未绑定项目',
      list,
    }))
  }, [sessions, projects, q])

  const total = groups.reduce((n, g) => n + g.list.length, 0)

  return (
    <div className="sess-pick" ref={boxRef}>
      <button
        type="button"
        className={`sess-pick-trigger${open ? ' on' : ''}`}
        onClick={() => setOpen((v) => !v)}
      >
        <span className="sess-pick-t">{cur ? (cur.title?.trim() || '未命名会话') : '请选择会话'}</span>
        <span className="sess-pick-s">
          {cur ? metaOf(cur) : `${sessions.length} 个可用会话`}
        </span>
        <svg className="sess-pick-chev" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}>
          <path d={open ? 'M6 15l6-6 6 6' : 'M6 9l6 6 6-6'} />
        </svg>
      </button>

      {open && (
        <div className="ms-pick sess-pick-pop">
          <div className="ms-pick-head">
            <div className="ms-pick-search">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}>
                <circle cx="11" cy="11" r="7" />
                <path d="M16.5 16.5L21 21" />
              </svg>
              <input
                autoFocus
                placeholder="搜索会话标题…"
                value={q}
                onChange={(e) => setQ(e.target.value)}
              />
            </div>
          </div>
          <div className="ms-pick-body">
            {total === 0 ? (
              <div className="sess-pick-none">
                {sessions.length === 0 ? '当前没有打开的会话' : '没有匹配的会话'}
              </div>
            ) : (
              groups.map((g) => (
                <div key={g.pid} className="ms-pick-group">
                  <div className="ms-pick-group-head">
                    {g.name}<span className="ms-pick-count">{g.list.length} 个</span>
                  </div>
                  {g.list.map((s) => (
                    <div
                      key={s.id}
                      className={`ms-pick-item${s.id === value ? ' bound' : ''}`}
                      onClick={() => { onChange(s.id); setOpen(false); setQ('') }}
                    >
                      <div className="ms-pick-info">
                        <div className="ms-pick-name">
                          {s.title?.trim() || '未命名会话'}
                          {s.id === activeSessionId && <span className="ms-pick-type">正在用</span>}
                        </div>
                        <div className="ms-pick-desc-line">{metaOf(s)}</div>
                      </div>
                      <div className="ms-pick-badges"></div>
                      <span className="ms-pick-check"></span>
                    </div>
                  ))}
                </div>
              ))
            )}
          </div>
        </div>
      )}
    </div>
  )
}

/** 最近活跃的人话 */
function humanLastActive(at: string): string {
  const t = new Date(at).getTime()
  if (Number.isNaN(t)) return '最近活跃'
  const days = Math.floor((Date.now() - t) / 86400000)
  if (days <= 0) return '今天活跃'
  if (days === 1) return '昨天活跃'
  if (days < 30) return `${days} 天前活跃`
  return '30 天前活跃'
}

/** 添加请求体（稿子 ④ 的落库字段：notify_chats / notify_channel_ids + session_mode） */
function buildAddRequest(form: AddFormValue): CreateScheduleRequest {
  const req: CreateScheduleRequest = {
    name: form.name.trim(),
    kind: form.kind,
    description: form.description,
    sessionMode: form.sessionMode,
    notifyChats: form.notifyChats,
    notifyChannelIds: form.notifyChannelIds,
  }
  if (form.command.trim()) req.command = form.command.trim()
  if (form.kind === 'cron') req.cron = form.cron.trim()
  else if (form.kind === 'interval') req.intervalSeconds = form.intervalSeconds
  else if (form.kind === 'once' && form.runAt) req.runAt = new Date(form.runAt).toISOString()
  // A 形态才带会话 id（B/C/D 不带 —— 形态自带会话语义）
  if (form.sessionMode === 'REUSE' && form.sessionId) req.sessionId = form.sessionId
  // [V317] 专家：只有 B/C 形态带（其它形态带非空会被后端 400 拒绝）
  if (modeAcceptsExpert(form.sessionMode) && form.mainThreadAgent.trim()) {
    req.mainThreadAgent = form.mainThreadAgent.trim()
  }
  return req
}
