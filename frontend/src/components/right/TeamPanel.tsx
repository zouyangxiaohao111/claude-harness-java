import { useEffect, useMemo, useState } from 'react'
import { sessionApi } from '@/api/sessions'
import { teamsApi } from '@/api/teams'
import { featuresApi } from '@/api/features'
import { ApiError } from '@/api/rest'
import { useTeamStore } from '@/stores/teamStore'
import { subagentColor, type SessionTeamContext } from '@/api/types'
import { FormModal } from '@/components/ui/FormModal'

interface TeamPanelProps {
  sessionId: string | null
  showToast: (msg: string, type?: 'success' | 'info') => void
}

const PlusIcon = ({ size = 12 }: { size?: number }) => (
  <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: size, height: size }}>
    <path d="M6 2.5V9.5M2.5 6H9.5" />
  </svg>
)

/** Team 头像（双人头像 · 对齐项目 SVG 图标风格，不用 emoji） */
const TeamAvatarIcon = ({ size = 14 }: { size?: number }) => (
  <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: size, height: size }}>
    <circle cx="7" cy="6.5" r="2.8" />
    <circle cx="13.5" cy="6.5" r="2.8" />
    <path d="M2.8 16c.6-3 2.2-4.5 4.2-4.5S10.6 13 11.2 16" />
    <path d="M10.5 16c.6-2.8 1.9-4.2 3.6-4.2 2.3 0 3.9 1.7 4.3 4.2" />
  </svg>
)

/** 时间戳展示：ISO 串或 epoch 毫秒串 → HH:MM（解析失败返回空） */
function fmtTime(ts: string): string {
  if (!ts) return ''
  const n = Number(ts)
  const d = /^\d+$/.test(ts) && Number.isFinite(n) ? new Date(n) : new Date(ts)
  if (Number.isNaN(d.getTime())) return ''
  return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

/** 消息排序键（epoch 毫秒 · ISO 串兼容） */
function tsOf(ts: string): number {
  const n = Number(ts)
  if (/^\d+$/.test(ts) && Number.isFinite(n)) return n
  const d = new Date(ts).getTime()
  return Number.isNaN(d) ? 0 : d
}

/**
 * Team 协作面板（右栏任务 tab）· 会话有 teamContext 才显示状态卡。
 * 数据流：sessionApi.get → teamContext（门控）→ teamsApi.get 拉详情；
 * 实时消息/状态由 useChatSocket 订阅 /topic/sessions/{leadSessionId}/team-* 写入 useTeamStore 消费。
 */
export function TeamPanel({ sessionId, showToast }: TeamPanelProps) {
  const teamName = useTeamStore((s) => s.teamName)
  const team = useTeamStore((s) => s.team)
  const messages = useTeamStore((s) => s.messages)
  const unread = useTeamStore((s) => s.unread)
  const inbox = useTeamStore((s) => s.inbox)
  const setTeamName = useTeamStore((s) => s.setTeamName)
  const setTeam = useTeamStore((s) => s.setTeam)
  const setInbox = useTeamStore((s) => s.setInbox)
  const markAllRead = useTeamStore((s) => s.markAllRead)
  const clearTeam = useTeamStore((s) => s.clear)

  /** 会话级 teamContext（非 null 才显示状态卡；null 渲染空态） */
  const [teamContext, setTeamContext] = useState<SessionTeamContext | null>(null)
  /** Agent Swarms 门控（features.agentSwarms · store 订阅，设置页开关实时同步） */
  const agentSwarms = useTeamStore((s) => s.agentSwarms)
  const setAgentSwarms = useTeamStore((s) => s.setAgentSwarms)
  const [loading, setLoading] = useState(false)
  const [showCreate, setShowCreate] = useState(false)
  const [showAddMember, setShowAddMember] = useState(false)
  const [inboxOpen, setInboxOpen] = useState(false)
  // 常驻展示（对齐子代理运行状况 · 无折叠）

  // Agent Swarms 门控：挂载时读 features.agentSwarms 初始化 store（后端未开 → 面板隐藏）
  useEffect(() => {
    let cancelled = false
    featuresApi.get().then((f) => { if (!cancelled) setAgentSwarms(!!f.agentSwarms) }).catch(() => {})
    return () => { cancelled = true }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // 挂载 + sessionId 变化：拉会话 → 读 teamContext → 有 team 再拉详情
  // 注意：不在开头无条件 clearTeam —— tab 切换重挂载时保留 store 里的消息流/未读数；
  //      会话无 team / 切换团队时由 setTeamName 或 clearTeam 兜底重置。
  // 拉取会话 teamContext → 有 team 再拉详情（门控 + 订阅键）。成员增删/团队变化靠
  //   STOMP member_joined/created 实时刷新 + 4s 轮询兜底（防推送丢失/后端成员未落 config）。
  useEffect(() => {
    if (!sessionId) {
      setTeamContext(null)
      clearTeam()
      return
    }
    let cancelled = false
    // silent：轮询刷新不闪 loading（避免「加载中…」↔ 状态卡高度切换顶动下方模块 → 整个任务 tab 抖动）
    const fetchTeam = (silent = false) => {
      if (!silent) setLoading(true)
      sessionApi
        .get(sessionId)
        .then((s) => {
          if (cancelled) return
          const tc = s.teamContext ?? null
          // 内容守卫：轮询数据未变则不 setState（避免新对象引用触发 re-render → 展开态 DOM 重建闪烁）
          setTeamContext((prev) => (JSON.stringify(prev) === JSON.stringify(tc) ? prev : tc))
          const name = tc?.teamName ?? null
          if (name) {
            // 方案3：STOMP 订阅键 = leadSessionId（创建团队的会话），从 teamContext 透出
            setTeamName(name, tc?.leadSessionId ?? null)
            // 详情拉取失败不阻塞：状态卡仍可凭 teamName/leadAgentId 渲染
            teamsApi.get(name).then(setTeam).catch(() => {})
          } else {
            // 会话无 team（非团队会话 / 后端已清 teamContext）→ 清空团队态
            clearTeam()
          }
        })
        .catch(() => { /* 轮询静默，不 toast 刷屏 */ })
        .finally(() => { if (!cancelled && !silent) setLoading(false) })
    }
    fetchTeam()
    // 4s 轮询兜底（对齐 Workflow 面板模式）：成员变化后端可能未推 STOMP，定期重拉 · silent=true 不闪 loading
    const timer = window.setInterval(() => fetchTeam(true), 2000)
    return () => { cancelled = true; window.clearInterval(timer) }
  }, [sessionId, showToast, setTeamName, setTeam, clearTeam])

  // 团队解散（STOMP team_status deleted → store.clear() 置 teamName null）→ 本地 teamContext 同步回空态
  useEffect(() => {
    if (!teamName) setTeamContext(null)
  }, [teamName])

  // 收件箱 + 实时消息流合并（按 timestamp 去重排序）
  const allMessages = useMemo(() => {
    const map = new Map<string, { from: string; text: string; timestamp: string; color?: string | null }>()
    for (const m of inbox ?? []) map.set(`${m.from}:${m.timestamp}`, { from: m.from, text: m.text, timestamp: m.timestamp, color: m.color })
    for (const m of messages) map.set(`${m.from}:${m.timestamp}`, { from: m.from, text: m.text, timestamp: m.timestamp, color: m.color })
    return [...map.values()].sort((a, b) => tsOf(a.timestamp) - tsOf(b.timestamp))
  }, [inbox, messages])

  /** 创建团队（409「你已领导一个团队」由后端 userMessage 透出） */
  const handleCreate = async (v: { teamName: string; description?: string; agentType?: string }) => {
    try {
      const created = await teamsApi.create({
        teamName: v.teamName.trim(),
        description: v.description?.trim() || undefined,
        agentType: v.agentType?.trim() || undefined,
        sessionId: sessionId ?? undefined,
      })
      setShowCreate(false)
      // 后端重名自动换名 → 以响应名为准；leadSessionId 透出 → STOMP 订阅键
      setTeamName(created.name, created.leadSessionId ?? sessionId)
      setTeam(created)
      // 空态创建：teamContext 门控同步置为非 null，立即渲染状态卡
      setTeamContext({ teamName: created.name, leadAgentId: created.leadAgentId, leadSessionId: created.leadSessionId ?? sessionId })
      showToast(`团队「${created.name}」已创建`, 'success')
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    }
  }

  /** 解散团队：活跃成员先收 shutdown_request，仍活跃后端返回 409 */
  const dissolveTeam = async () => {
    if (!teamName) return
    if (!confirm('解散团队？活跃成员会先收到关闭请求')) return
    try {
      await teamsApi.remove(teamName)
      clearTeam()
      setTeamContext(null)
      showToast('团队已解散', 'success')
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    }
  }

  /** 移除成员（非 lead 成员卡上 ✕） */
  const removeMember = async (agentId: string) => {
    if (!teamName) return
    try {
      const updated = await teamsApi.removeMember(teamName, agentId)
      setTeam(updated)
      showToast('已移除成员', 'success')
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    }
  }

  /** 停止成员任务（非 lead 成员卡 ⏹ · kill 后成员从 config 移除 → 刷新团队） */
  const killMember = async (agentId: string) => {
    if (!teamName) return
    try {
      const res = await teamsApi.kill(teamName, agentId)
      if (!res?.success) { showToast('停止失败', 'info'); return }
      showToast(`已停止 @${agentId.split('@')[0]}`, 'success')
      // kill 后成员从 config 移除 → 刷新团队详情
      const updated = await teamsApi.get(teamName)
      setTeam(updated)
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    }
  }

  /** 添加成员 = spawn 真实子代理（Agent 工具 input 带 name → 后端写 config + 跑进程）。
   *  对齐 CC spawnMultiAgent：name 是成员唯一显示名（agentId = name@team），缺 name 后端只 fork 不入队。 */
  const addMember = async (v: { name: string; subagentType?: string; prompt?: string }) => {
    if (!teamName) return
    const name = v.name.trim()
    if (!name) {
      showToast('请填写成员名称（name 必填 · 作为成员唯一显示名）', 'info')
      return
    }
    try {
      const updated = await teamsApi.spawnMember(teamName, {
        name,
        subagentType: v.subagentType?.trim() || 'general-purpose',
        prompt: v.prompt?.trim() || undefined,
      })
      setTeam(updated)
      setShowAddMember(false)
      showToast(`已启动成员 @${name}`, 'success')
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    }
  }

  /** 展开收件箱 → 拉历史 + 标记已读（折叠时新消息累计未读角标） */
  const toggleInbox = () => {
    const next = !inboxOpen
    setInboxOpen(next)
    if (next && teamName) {
      teamsApi.inbox(teamName).then(setInbox).catch(() => {})
      teamsApi.markRead(teamName).then(() => markAllRead()).catch(() => {})
    }
  }

  // 门控：swarm 功能开启（features.agentSwarms）+ 会话 teamContext 非 null（本会话绑团队）或 store 已有团队详情
  const hasTeam = agentSwarms && (teamContext != null || team != null)

  return (
    <>
      <div className="task-group-title async-title">
        <span>Team 协作</span>
        {agentSwarms && !hasTeam && !loading && (
          <span className="team-add" title="创建团队" onClick={(e) => { e.stopPropagation(); setShowCreate(true) }}>
            <PlusIcon size={11} />
          </span>
        )}
      </div>

      {loading ? (
        <div className="right-empty">加载中…</div>
      ) : hasTeam ? (
        <>
          <div className="team-card">
            <div className="team-card-head">
              <span className="team-avatar"><TeamAvatarIcon /></span>
              <span className="team-name">{team?.name ?? teamName}</span>
              <span className="team-lead">@{team?.leadAgentId ?? teamContext?.leadAgentId}</span>
              <button className="team-dissolve" onClick={() => void dissolveTeam()}>解散</button>
            </div>
            <div className="team-sub">
              成员 ({team?.members.length ?? 0})
              <button className="team-add-member" onClick={() => setShowAddMember(true)} title="添加成员">+ 添加</button>
            </div>
            <div className="team-members">
              {(team?.members ?? []).map((m) => (
                <div key={m.agentId} className="team-member">
                  <span className="avatar" style={{ background: m.color ?? subagentColor(m.name) }}>
                    {m.name.slice(0, 1).toUpperCase()}
                  </span>
                  <span className="info">
                    <span className="name">@{m.name}</span>
                    <span className="type">{m.agentType ?? 'agent'}</span>
                  </span>
                  <span className={`dot ${m.isActive ? 'on' : ''}`} title={m.isActive ? '活跃' : '离线'} />
                  {m.mode && <span className="mode">{m.mode}</span>}
                  {m.agentId !== team?.leadAgentId && (
                    <>
                      <button className="team-kill" title="停止任务" onClick={(e) => { e.stopPropagation(); void killMember(m.agentId) }}>⏹</button>
                      <button className="team-remove" title="移除成员" onClick={() => void removeMember(m.agentId)}>✕</button>
                    </>
                  )}
                </div>
              ))}
            </div>
          </div>

          {/* 收件箱 / teammate 消息流 */}
          <div className={`team-inbox ${inboxOpen ? 'open' : ''}`} onClick={toggleInbox}>
            <div className="team-inbox-head">
              <span>收件箱</span>
              {!inboxOpen && unread > 0 && <span className="team-unread">{unread}</span>}
              <span className="chevron">{inboxOpen ? '▾' : '▸'}</span>
            </div>
          </div>
          {inboxOpen && (
            <div className="team-inbox-body">
              {allMessages.length === 0 ? (
                <div className="right-empty" style={{ padding: '12px 0' }}>暂无消息</div>
              ) : (
                allMessages.map((m, i) => (
                  <div key={`${m.from}:${m.timestamp}:${i}`} className="team-msg">
                    <span className="team-msg-from" style={{ color: m.color ?? subagentColor(m.from) }}>@{m.from}</span>
                    <span className="team-msg-text">{m.text || ''}</span>
                    <span className="team-msg-time">{fmtTime(m.timestamp)}</span>
                  </div>
                ))
              )}
            </div>
          )}
        </>
      ) : (
        <div className="right-empty">
          {agentSwarms ? '暂无团队 · 点击右上角 + 创建' : 'Agent Swarms 未开启 · 去设置-环境配置开启'}
        </div>
      )}

      {/* 创建团队弹窗（FormModal · 409 后端文案透出） */}
      {showCreate && (
        <FormModal<{ teamName: string; description: string; agentType: string }>
          title="创建团队"
          subtitle="Team 协作"
          initial={{ teamName: '', description: '', agentType: '' }}
          sections={[
            {
              title: '团队信息',
              fields: [
                { type: 'text', name: 'teamName', label: '团队名', placeholder: 'my-team' },
                { type: 'text', name: 'description', label: '描述（可选）', placeholder: '团队用途说明' },
                { type: 'text', name: 'agentType', label: 'Agent 类型（可选）', placeholder: '如 claude / deepseek' },
              ],
            },
          ]}
          onSave={(v) => void handleCreate(v)}
          onCancel={() => setShowCreate(false)}
        />
      )}

      {/* 添加成员 = spawn 真实子代理（后端 /members/spawn · name 必填 → agentId=name@team） */}
      {showAddMember && (
        <FormModal<{ name: string; subagentType: string; prompt: string }>
          title="启动成员"
          subtitle={`Team ${teamName ?? ''} · spawn 真实子代理`}
          initial={{ name: '', subagentType: 'general-purpose', prompt: '' }}
          sections={[
            {
              title: '成员信息',
              fields: [
                { type: 'text', name: 'name', label: '成员名称（必填）', placeholder: '如 explorer-1（agentId=name@team）' },
                { type: 'text', name: 'subagentType', label: '子代理类型', placeholder: '如 Explore / general-purpose' },
                { type: 'textarea', name: 'prompt', label: '任务提示词', placeholder: '描述该成员要执行的任务', rows: 3 },
              ],
            },
          ]}
          onSave={(v) => void addMember(v)}
          onCancel={() => setShowAddMember(false)}
        />
      )}
    </>
  )
}
