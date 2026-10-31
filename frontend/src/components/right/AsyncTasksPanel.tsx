import { useCallback, useEffect, useRef, useState } from 'react'
import { tasksApi, type BackgroundTaskDto } from '@/api/tasks'
import { ApiError } from '@/api/rest'
import { useSubagentStore } from '@/stores/subagentStore'
import { RunMoreButton, capRunRows } from './CollapsibleGroup'

/**
 * 异步任务清单面板（任务 tab 内嵌模块 · 独立组件隔离轮询）。
 *
 * <p>WHY：4s 轮询若放 RightPanel 主组件会触发整个任务 tab 重渲染（TeamPanel/子代理/
 * workflow 全部）→ 卡顿。抽成独立组件，asyncTasks state + 轮询 effect 都在本组件内，
 * 只有本组件 re-render，任务 tab 其他模块不受影响。
 *
 * <p>默认展示 5 个（running 优先）·「查看更多」弹窗全量 · 全部停止为当前会话级。
 */
const TASK_TYPE_LABEL: Record<string, string> = {
  local_bash: '命令', local_agent: '子代理', in_process_teammate: '队友',
  local_workflow: 'workflow', monitor_mcp: '监控',
}
const TASK_STATUS_LABEL: Record<string, string> = {
  pending: '等待', running: '运行中', completed: '已完成', failed: '失败', killed: '已停止',
}

export function AsyncTasksPanel({ activeSessionId, showToast, hideHeader = false, onCount, maxRows = 0, showAll = false, onToggleShowAll }: {
  activeSessionId: string | null
  showToast: (msg: string, type?: 'success' | 'info') => void
  /** 折叠分组已提供标题 → 不再渲染自身标题行 */
  hideHeader?: boolean
  /** 上报任务数给折叠组（0 → 该组折叠成一行） */
  onCount?: (n: number) => void
  /** 最多渲染的已完成任务数（0 = 不截断 · 进行中/待运行永远全显示） */
  maxRows?: number
  /** 是否展开全部（由父级统一控制 · 与运行 tab 其它分组共用） */
  showAll?: boolean
  onToggleShowAll?: () => void
}) {
  const [asyncTasks, setAsyncTasks] = useState<BackgroundTaskDto[]>([])
  // [D2] 上报任务数给折叠组（0 → 折叠成一行）
  useEffect(() => { onCount?.(asyncTasks.length) }, [asyncTasks.length, onCount])
  /** 单个异步任务详情（点击列表行打开 · 用户 2026-10-21） */
  const [detail, setDetail] = useState<BackgroundTaskDto | null>(null)
  // 常驻展示（对齐子代理运行状况 · 无折叠）：2s 轮询始终执行 REST 兜底补录，子代理实时恢复

  // [刀 2c] 「缺席即删」宽限期计数：key = `${sessionId}|${taskId}` → 连续未出现在 REST 清单的次数。
  //   组件 ref（⛔ 不用模块级单槽：多会话同页会互相污染；key 带 sessionId 亦保证切换会话不串）。
  const restMissCount = useRef<Map<string, number>>(new Map())

  // 会话级加载（list(activeSessionId) 传会话 id · 弹窗打开时也刷新，确保会话隔离最新）
  const load = useCallback(() => {
    if (!activeSessionId) return
    tasksApi.list(activeSessionId)
      .then((list) => {
        // [subagent-restore 2026-08-25] REST 兜底补录：STOMP /topic/tasks 不重放历史事件，子代理
        //   task_started 若在 STOMP 断连/未订阅窗口被 drain 即丢失 → subagentStore 空 → 「子代理运行
        //   状况」区不显示。此处从 REST 同源（BackgroundTaskRunner 持久 task store）对子代理类型任务
        //   补录 —— 2s 轮询恢复子代理卡片，STOMP 丢失不再留白。
        // [刀 1b] 类型白名单与 useChatSocket.ts:663-665 的 task_started 白名单对齐
        //   （local_agent / in_process_teammate / remote_agent）：此前只认 local_agent ⇒ teammate 的
        //   唯一 REST 恢复通道被挡死，只剩 STOMP 一条命（事件丢窗即永久空白）。teammate 进入「异步任务」
        //   清单是与 local_agent 对等的**预期行为**。
        //   ⚠️ 依赖刀 1a（teammate 注册时带上 parentSessionId）才能在会话级清单里查到。
        for (const t of list ?? []) {
          if (t.type !== 'local_agent' && t.type !== 'in_process_teammate'
            && t.type !== 'remote_agent') continue
          const st = useSubagentStore.getState()
          const existing = st.bySession[activeSessionId]?.[t.id]
          // 已终态（STOMP 事件已登记 done/failed/stopped）→ 无需再补，跳过
          if (existing && existing.status !== 'running') continue
          // 未登记（STOMP 断连/未订阅窗口丢失 task_started）→ 先 register 恢复卡片；
          //   已登记但仍是 running（终态事件在断连窗口丢失）→ 复用既有身份，不重复 register
          if (!existing) {
            // taskId 作 key（register 第 2 参；toolUseId null 与 STOMP 事件同构）。description 兜底 task_type。
            st.register(null, t.id, t.description || t.type, t.type, activeSessionId)
          }
          // REST 终态权威补录：身份刚 register 或仍为陈旧 running，只要 REST 显示终态就补终态活动
          //   （completed→done · failed/killed→stopped）——否则 localStorage 持久化的 running 会
          //   跨会话/跨天永久虚高（终态事件丢失后 addActivity(done) 永不执行）。
          if (t.status === 'completed') {
            st.addActivity(t.id, { type: 'done', text: t.status, ts: Date.now() }, activeSessionId)
          } else if (t.status === 'failed' || t.status === 'killed') {
            st.addActivity(t.id, { type: 'stopped', text: t.status, ts: Date.now() }, activeSessionId)
          }
        }
        // [刀 2c · 缺席即删] 本会话桶里仍是 running 的子代理条目，若**连续两次** REST 响应中都不存在
        //   → forget。WHY：终态 STOMP 事件丢窗 + REST 清单不再包含（已 evict / 已不在会话级范围）时，
        //   localStorage 持久化的 running 卡片会永久虚高（无任何出口能清）。宽限期=连续 2 次，防
        //   「任务刚注册、REST 清单还没反映」时误删（约 4s 内出现即复位计数）。
        //   [本批修正 · 类型白名单三方一致] 与上方 REST 恢复白名单（本文件 :52-53）、
        //   useChatSocket.ts:663-665 的 task_started 白名单**同一组字面量**（local_agent /
        //   in_process_teammate / remote_agent）。此前只有前两类 ⇒ 上一批让 remote_agent
        //   「进得来（REST 恢复放行）」却「出不去（缺席清理排除）」= 自相矛盾，remote_agent 的
        //   幽灵卡片仍无出口可清。
        //   ⚠️ 误删风险已实证排除：remote_agent 注册路径
        //   （RemoteAgentTaskService.registerRemoteAgentTask）构 BackgroundTask 时显式
        //   `.withSessionId(creatingSession)`（:233-238）再 `framework.registerTask(base)`（:249），
        //   而会话级清单 = listAllTasks（本地 tasks ∪ framework store，BackgroundTaskRunner.java:529-543）
        //   经 TaskController.listTasks 按 `sessionId.equals(task.sessionId())` 过滤（:119-122）
        //   ⇒ running 的 remote_agent **必定**出现在本会话 REST 清单里（creatingSession 非空的生产路径），
        //   不会被本块的「连续两次缺席」误删。仅 creatingSession 缺省的降级路径（同处 :224-228 打 WARN，
        //   taskOutputDir 已 fail-loud）不满足该前提，属既有坏路径，非本块引入。
        const restIds = new Set((list ?? []).map((t) => t.id))
        const seenIds = new Set<string>()
        for (const identity of Object.values(useSubagentStore.getState().bySession[activeSessionId] ?? {})) {
          const tid = identity.taskId
          if (!tid || seenIds.has(tid)) continue // 别名键（tool_use_id）指向同一 identity，按 taskId 去重
          seenIds.add(tid)
          if (identity.status !== 'running') continue
          if (identity.taskType !== 'local_agent' && identity.taskType !== 'in_process_teammate'
            && identity.taskType !== 'remote_agent') continue
          const missKey = `${activeSessionId}|${tid}`
          if (restIds.has(tid)) {
            restMissCount.current.delete(missKey)
            continue
          }
          const misses = (restMissCount.current.get(missKey) ?? 0) + 1
          if (misses >= 2) {
            restMissCount.current.delete(missKey)
            useSubagentStore.getState().forget(tid, activeSessionId)
          } else {
            restMissCount.current.set(missKey, misses)
          }
        }
        // 只展示真异步后台任务（isBackgrounded=true；undefined=旧后端兼容保留）：同步前台任务
        //   （如 Bash 工具）已在对话内工具卡展示，不重复出现在异步任务面板。
        const filtered = (list ?? []).filter((t) => t.isBackgrounded !== false)
        // 内容守卫：轮询数据未变则不 setState（避免新数组引用触发 re-render → 任务 tab 抖动）
        setAsyncTasks((prev) => (JSON.stringify(prev) === JSON.stringify(filtered) ? prev : filtered))
      })
      .catch(() => {})
  }, [activeSessionId])
  // 2s 轮询（会话级 · 仅本组件 re-render）。常驻展示（无折叠）→ 始终轮询 REST 兜底补录
  useEffect(() => {
    load()
    const t = setInterval(load, 2000)
    return () => clearInterval(t)
  }, [load])

  // running 优先排序
  const sortedTasks = [...asyncTasks].sort((a, b) =>
    (a.status === 'running' ? 0 : 1) - (b.status === 'running' ? 0 : 1))
  // 抽屉「运行」tab：已完成任务最多 maxRows 条，进行中/待运行永远全显示
  const { shown: shownTasks, over: tasksOver } = capRunRows(
    sortedTasks, (t) => t.status === 'running' || t.status === 'pending', showAll, maxRows,
  )
  const handleKillTask = async (taskId: string) => {
    try {
      const res = await tasksApi.killTask(taskId)
      if (!res?.success) { showToast('停止失败', 'info'); void load(); return }
      showToast('任务已停止', 'success')
      void load()
    } catch (e) {
      // [stop-404/409 幂等] 任务已不存在或已非运行态（已结束/已移除）→ 刷新清单清掉幽灵"运行中"
      //   ⚠️ 为什么 404 与 409 **都要认**（本批主因）：
      //   后端 stopTask 对 **store-only running local_agent**（主会话后台化，不经 spawn）返回
      //   NOT_RUNNING —— TaskController.java:229-231 把 NOT_RUNNING（以及 :233-235 的 UNSUPPORTED_TYPE）
      //   映射为 409 Conflict。NOT_RUNNING 的语义就是「任务已结束/非运行态」；即便后端查到 status
      //   仍是 running，killAsyncAgent 也以 runner 本地 tasks 地图为权威（首行 `tasks.get(taskId)`，
      //   BackgroundTaskRunner.java:1233-1240），而这类任务按定义**不在**该地图 ⇒ 刀 3 永远走不到
      //   「成功停止」分支，409 是它**唯一**可达的结果。若此处只认 404，后端那条回退把原来的 404
      //   纠正成 409 之后，本分支再也命中不了 ⇒ 用户点停止不清卡片 ⇒ 卡片继续显示"运行中"
      //   （正好是用户报的症状）——即「刀 3 + 刀 2a(仅 404)」互相抵消，比只做刀 2a 还差。
      //   ⛔ 与 RightPanel.tsx 的收敛分支（404 || 409）保持同一状态码口径，两处不得再分叉。
      if (e instanceof ApiError && (e.status === 404 || e.status === 409)) {
        showToast('该任务已结束（已刷新）', 'info')
        // [刀 2a] 除刷新清单外，补一次本地子代理身份清理（与 RightPanel handleKillSubagent 的 404/409
        //   出口同语义：任务已不存在/已非运行态 ⇒ 清本地身份）。
        //   WHY：本处理器此前从不碰 subagentStore ⇒ 在「异步任务」区点停止时，子代理卡片仍停在
        //   "运行中"（子代理运行状况面板不收敛）。
        //   ⚠️ 显式传 activeSessionId（第二个实参）——会话态不得经全局单槽读（forget 的
        //   `sessionId ?? get().sessionId` 回落是给存量调用方的，新调用点一律显式传）。
        if (activeSessionId) useSubagentStore.getState().forget(taskId, activeSessionId)
        void load()
        return
      }
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    }
  }
  const handleStopAll = async () => {
    if (!activeSessionId) return
    try {
      const res = await tasksApi.stopAllTasks(activeSessionId)
      // [stop-all 假成功修正] 后端 success = (failed == 0)（TaskController.stopAll：NOT_RUNNING
      //   幂等口径不算失败，只有真错才计入 failed）—— 失败时按 failed 数如实告知，
      //   ⛔ 不再把「有任务没停掉」也报成功（用户裁定本批一起修）。
      if (res?.success === false) {
        const failedCount = res.failed ?? 0
        showToast(failedCount > 0 ? `部分任务未能停止（${failedCount} 个）` : '部分任务未能停止', 'info')
        return
      }
      showToast('已发送全部停止', 'success')
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    }
  }

  return (
    <>
      {!hideHeader && <div className="task-group-title async-title">
        <span>异步任务</span>
        <span className="task-actions" onClick={(e) => e.stopPropagation()}>
          <button className="stop-all" onClick={() => { if (confirm('停止当前会话全部异步任务？')) void handleStopAll() }}>全部停止</button>
        </span>
      </div>}
      {/* 抽屉里 hideHeader 不渲染标题；只要有进行中/待运行任务，就保留「停止全部」入口 */}
      {hideHeader && sortedTasks.some((t) => t.status === 'running' || t.status === 'pending') && (
        <div className="async-stop-all-row">
          <button className="stop-all" onClick={() => { if (confirm('停止当前会话全部异步任务？')) void handleStopAll() }}>停止全部</button>
        </div>
      )}
      {/* [D2] 展开态 = 行式列表（用户 2026-10-21：按设计稿，不要三张 0 卡片） */}
      {sortedTasks.length === 0 ? (
        <div className="todo-empty">暂无异步任务</div>
      ) : (
        <div className="todo-rows">
          {shownTasks.map((t) => {
            const running = t.status === 'running' || t.status === 'pending'
            const statusLabel = TASK_STATUS_LABEL[t.status] ?? t.status
            const typeLabel = TASK_TYPE_LABEL[t.type] ?? t.type
            return (
              <div key={t.id} className="todo-row" onClick={() => setDetail(t)} title="查看任务详情">
                <span className={`todo-row-dot ${running ? 'in_progress' : t.status === 'completed' ? 'completed' : 'pending'}`} />
                <span className="todo-row-main">
                  {/* 长命令单行截断（hover 看全）：整条命令当标题会把卡片撑成 3-4 行 */}
                  <span className="todo-row-title one-line" title={t.description || typeLabel}>
                    {t.description || typeLabel}
                  </span>
                  <span className="todo-row-sub">{typeLabel} · {statusLabel}</span>
                </span>
                {/* 停止入口随三态弹窗删除后迁到行内（仅运行中/待运行） */}
                {running && (
                  <button
                    className="at-kill"
                    title="停止任务"
                    onClick={(e) => { e.stopPropagation(); void handleKillTask(t.id) }}
                  >⏹</button>
                )}
                <span className="todo-row-arrow">›</span>
              </div>
            )
          })}
          <RunMoreButton over={tasksOver} showAll={showAll} onToggle={() => onToggleShowAll?.()} />
        </div>
      )}
      {detail && (
        <AsyncTaskDetailModal task={detail} onClose={() => setDetail(null)} onKill={handleKillTask} />
      )}
    </>
  )
}


/**
 * 异步任务详情弹窗（用户 2026-10-21：列表项要能点开）。
 *
 * <p>字段全部来自 BackgroundTaskDto：type / status / description / startTime / endTime /
 * agentId / toolUseId / isBackgrounded。**不展示「日志输出」**——DTO 里没有该字段，
 * 硬塞只能伪造；要看输出请回会话里那条工具调用。
 */
function AsyncTaskDetailModal({ task, onClose, onKill }: {
  task: BackgroundTaskDto
  onClose: () => void
  onKill: (taskId: string) => void
}) {
  const running = task.status === 'running' || task.status === 'pending'
  const elapsed = task.endTime != null
    ? task.endTime - task.startTime
    : Date.now() - task.startTime
  const fmt = (ms: number) => {
    if (ms < 1000) return `${ms}ms`
    const s = Math.floor(ms / 1000)
    if (s < 60) return `${s} 秒`
    const m = Math.floor(s / 60)
    return `${m} 分 ${s % 60} 秒`
  }
  const typeLabel = TASK_TYPE_LABEL[task.type] ?? task.type
  const statusLabel = TASK_STATUS_LABEL[task.status] ?? task.status
  return (
    <div className="overlay-backdrop" onClick={onClose}>
      <div className="subagent-modal task-detail" onClick={(e) => e.stopPropagation()}>
        <div className="sam-head">
          <span className="sam-title">{task.description || typeLabel}</span>
          <button type="button" className="sam-close" onClick={onClose} aria-label="关闭">
            <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="M3 3L9 9M9 3L3 9" /></svg>
          </button>
        </div>
        <div className="sam-list task-detail-body">
          <div className="td-meta">
            <span className={`td-badge ${running ? 'in_progress' : task.status === 'completed' ? 'completed' : 'pending'}`}>
              {statusLabel}
            </span>
            <span className="td-id">{typeLabel}</span>
            {task.isBackgrounded && <span className="td-id">后台</span>}
          </div>
          <div className="td-sec">
            <div className="td-label">{running ? '已运行' : '耗时'}</div>
            <div className="td-val">{fmt(Math.max(0, elapsed))}</div>
          </div>
          <div className="td-sec">
            <div className="td-label">开始时间</div>
            <div className="td-val">{new Date(task.startTime).toLocaleString()}</div>
          </div>
          {task.agentId && (
            <div className="td-sec">
              <div className="td-label">执行者</div>
              <div className="td-val td-mono">{task.agentId}</div>
            </div>
          )}
          {task.toolUseId && (
            <div className="td-sec">
              <div className="td-label">工具调用</div>
              <div className="td-val td-mono">{task.toolUseId}</div>
            </div>
          )}
          {running && (
            <div className="td-actions">
              <button className="td-danger" onClick={() => { onKill(task.id); onClose() }}>停止任务</button>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
