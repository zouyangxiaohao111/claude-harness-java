package com.nexusai.application.agent.team;

import com.nexusai.application.agent.subagent.AgentNameRegistry;
import com.nexusai.application.agent.subagent.AutonomousAgentLoop;
import com.nexusai.application.agent.subagent.ResumeQuota;
import com.nexusai.application.agent.tasks.BackgroundTask;
import com.nexusai.application.agent.tasks.BackgroundTaskStatus;
import com.nexusai.application.agent.tasks.TaskFrameworkService;
import com.nexusai.application.agent.tasks.TaskType;
import com.nexusai.common.SessionKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * In-process teammate 任务注册表 · 对齐 CC utils/task/framework.ts:77-117 registerTask +
 * spawnInProcess.ts:191 注册 + :227-328 kill。
 *
 * <p><b>职责</b>：
 * <ul>
 *   <li><b>registerTask 桥接</b>（framework.ts:77-117）：把 {@link InProcessTeammateTaskState}
 *       载体的 teammate 状态注册进 {@link TaskFrameworkService} 的 BackgroundTask 状态层
 *       （status:'running' + task_started SDK 书签），使 kill/complete/fail 状态机可见。</li>
 *   <li><b>loop 实例持有</b>：taskId → {@link AutonomousAgentLoop} 映射——runTeammateLoop /
 *       kill / complete / fail 的生产调用方入口（R1 阻断项：spawn 后状态机必须可被生产调用）。</li>
 * </ul>
 *
 * <p>CC 对齐：spawn 后 InProcessBackend.kill → InProcessTeammateTask.tsx kill →
 * killInProcessTeammate（spawnInProcess.ts:227-328）；Java 侧 kill 由
 * {@link #kill(String)} 分发到 loop.kill()（loop 内已实现 :256 abort + :280-296 killed
 * 状态转换 + :306-319 evict/SDK 链）。
 */
public class InProcessTeammateTaskRegistry {

    private static final Logger log = LoggerFactory.getLogger(InProcessTeammateTaskRegistry.class);

    /**
     * 终态 → 逐出的展示窗口 · <b>对齐 CC {@code ML=30000}</b>（claude.exe 2.1.328 偏移 322959003 /
     * {@code evictAfter = Date.now()+ML} 偏移 322968319）。
     *
     * <p>CC 的 {@code fr(id,setState)} 要求「终态 + notified + 无 retain 或 evictAfter<=now +
     * keepaliveReasons 为空」才删内存记录；本仓 BackgroundTask 无 retain/keepalive 字段
     * （{@code TaskFrameworkService.isRetained} 恒 false）⇒ 唯一条件就是这 30s 窗口。
     */
    public static final long EVICT_AFTER_MS = 30_000L;

    /** 共享逐出调度器（daemon）· 每个 registry 一个线程会在长跑进程里堆线程，故进程级共享。 */
    private static final ScheduledExecutorService EVICT_SCHEDULER =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "teammate-registry-evict");
            t.setDaemon(true);
            return t;
        });

    private final ConcurrentHashMap<String, AutonomousAgentLoop> loops = new ConcurrentHashMap<>();

    private final TaskFrameworkService taskFrameworkService;

    /** 终态退役落点（teammate 也走「活跃表 → 退役表」同一条解析链）。可选注入。 */
    private volatile AgentNameRegistry agentNameRegistry;

    /**
     * 已排入逐出的 taskId（幂等闸）· 同一任务只会走一次
     * {@code task evict → registry remove → retire → release quota}。
     */
    private final Set<String> evictionScheduled = ConcurrentHashMap.newKeySet();

    /** 终态逐出延迟（默认 {@link #EVICT_AFTER_MS}；测试可缩短，生产 ⛔ 不改成其它值）。 */
    private volatile long evictAfterMs = EVICT_AFTER_MS;

    /**
     * 并发槽释放句柄 · spawn 侧（B3 配额门）预留槽后登记，逐出管线第四步释放。
     * 用句柄而不是「按会话减一」：释放必须幂等且只归还本任务占的那个槽。
     */
    private final Map<String, ResumeQuota.Release> quotaReleases = new ConcurrentHashMap<>();

    /** 生命周期阶段观察者（测试锁顺序 / 生产可接指标）。 */
    private volatile LifecycleObserver lifecycleObserver;

    /** 生命周期阶段（顺序即 CC 要求的固定顺序）。 */
    public enum LifecycleStage {
        TASK_EVICTED, REGISTRY_REMOVED, RETIRED, QUOTA_RELEASED
    }

    /** 阶段观察者。 */
    @FunctionalInterface
    public interface LifecycleObserver {
        void onStage(LifecycleStage stage, String taskId, String agentId);
    }

    /**
     * 终态回调缝 · {@link AutonomousAgentLoop} 在 terminal 转换完成后调用
     * （对齐 CC terminal 后 {@code evictAfter=now+ML} 的登记点）。
     */
    @FunctionalInterface
    public interface TerminalSink {
        void onTerminal(String taskId, String terminalStatus, String stopReason);
    }

    public InProcessTeammateTaskRegistry(TaskFrameworkService taskFrameworkService) {
        this.taskFrameworkService = taskFrameworkService;
    }

    /** 测试/接线：终态退役落点。 */
    public void setAgentNameRegistry(AgentNameRegistry agentNameRegistry) {
        this.agentNameRegistry = agentNameRegistry;
    }

    /** 测试：缩短逐出窗口（生产保持 {@link #EVICT_AFTER_MS}）。 */
    public void setEvictAfterMs(long evictAfterMs) {
        this.evictAfterMs = Math.max(0L, evictAfterMs);
    }

    /** 测试/可观测性：生命周期阶段观察者。 */
    public void setLifecycleObserver(LifecycleObserver lifecycleObserver) {
        this.lifecycleObserver = lifecycleObserver;
    }

    /** spawn 侧预留并发槽后登记释放句柄（逐出管线第四步调用）。 */
    public void registerQuotaRelease(String taskId, ResumeQuota.Release release) {
        if (taskId != null && release != null) {
            quotaReleases.put(taskId, release);
        }
    }

    /** 当前持有 loop 条目的任务数（测试/观测：逐出后必须回落，防只增不减）。 */
    public int size() {
        return loops.size();
    }

    /**
     * registerTask 桥接 + loop 注册。
     *
     * @param state  spawn 产出的 teammate 状态载体
     * @param loop   已接线的 AutonomousAgentLoop 实例
     * @param toolUseId 关联 tool_use block ID（对齐 CC spawnInProcess.ts:162 context.toolUseId，
     *                  BackgroundTask.toolUseId 字段）
     */
    public void register(InProcessTeammateTaskState state, AutonomousAgentLoop loop, String toolUseId) {
        BackgroundTask bg = toBackgroundTask(state, toolUseId);
        if (taskFrameworkService != null) {
            taskFrameworkService.registerTask(bg);
        }
        loops.put(state.taskId(), loop);
        if (log.isInfoEnabled()) {
            log.info("InProcessTeammateTaskRegistry.register: taskId={}, agent={}@{} 已注册 (registerTask 桥接)",
                state.taskId(), state.identity().agentName(), state.identity().teamName());
        }
    }

    /**
     * 按 taskId 查 loop。
     */
    public Optional<AutonomousAgentLoop> get(String taskId) {
        return Optional.ofNullable(taskId).map(loops::get);
    }

    /**
     * W8-GAP-02: 按 agentName 查 loop · 对齐 CC InProcessTeammateTask.tsx:92-108
     * findTeammateTaskByAgentId（偏好 running/存活，兜底首个匹配，避免同 agentId 旧 killed 任务残留）。
     *
     * <p>WHY（SendMessageTool shutdown approve 用）：模型批准 shutdown 时 SendMessageTool 只能拿到
     * shutdown_request.request_id（内含被 shutdown 的 teammate 名，CC agentId.ts:62-84），无 taskId /
     * 无 teammate ThreadLocal（Java 工具经 CompletableFuture 异步执行，thread-local 身份载体不可见）。
     * 故经 agentName 匹配 registry 中 loop 的 taskState().identity().agentName() 定位生命周期 abortController
     * （与 CC findTeammateTaskByAgentId 语义一致：匹配 identity.agentId，偏好 running）。
     *
     * @param agentName teammate agent 名（无 @ 后缀，CC types.ts:15；request_id 内嵌裸名）
     * @return 存活（非 aborted）loop 优先；仅 killed/aborted 匹配时兜底返回；无匹配 empty
     */
    public Optional<AutonomousAgentLoop> findByAgentName(String agentName) {
        if (agentName == null) {
            return Optional.empty();
        }
        AutonomousAgentLoop fallback = null;
        for (AutonomousAgentLoop loop : loops.values()) {
            InProcessTeammateTaskState state = loop.taskState();
            if (state == null || state.identity() == null) {
                continue;
            }
            if (agentName.equalsIgnoreCase(state.identity().agentName())) {
                if (!loop.isAborted()) {
                    return Optional.of(loop);
                }
                if (fallback == null) {
                    fallback = loop;
                }
            }
        }
        return Optional.ofNullable(fallback);
    }

    /**
     * [perm-timeout #136] 按 agentId（name@team）查 loop · 对齐 CC findTeammateTaskByAgentId
     * （InProcessTeammateTask.tsx:92-108，匹配 {@code identity.agentId} 全形，偏好 running，兜底首个
     * 匹配）。kill REST 端点经此定位 loop —— {@link #findByAgentName} 匹配裸名 agentName（无 @），
     * 前端 kill 按钮传 name@team 全形（spawnInProcess.ts:112 formatAgentId）。
     *
     * @param agentId teammate agent 全形 ID（name@team）
     * @return 存活（非 aborted）loop 优先；仅 killed/aborted 匹配时兜底返回；无匹配 empty
     */
    public Optional<AutonomousAgentLoop> findByAgentId(String agentId) {
        if (agentId == null) {
            return Optional.empty();
        }
        AutonomousAgentLoop fallback = null;
        for (AutonomousAgentLoop loop : loops.values()) {
            InProcessTeammateTaskState state = loop.taskState();
            if (state == null || state.identity() == null) {
                continue;
            }
            if (agentId.equalsIgnoreCase(state.identity().agentId())) {
                if (!loop.isAborted()) {
                    return Optional.of(loop);
                }
                if (fallback == null) {
                    fallback = loop;
                }
            }
        }
        return Optional.ofNullable(fallback);
    }

    /**
     * 会话级清理 · 对齐 CC spawnInProcess.ts:183-188 registerCleanup(() => abortController.abort()).
     *
     * <p><b>WHY</b>：Java 常驻 JVM 无进程退出，registerCleanup 回调的最近等价触发点=Leader 会话删除。
     * 迭代本会话 spawn 的全部 in-process teammate，调用其 unregisterCleanup（abort 生命周期控制器），
     * runner 线程（runTeammateLoop 轮循 isAborted）自然退出 —— 解决会话销毁不 abort teammate、
     * daemon 线程只随 JVM 退出被杀（探查 A2）。不推进 kill/completed 状态转换（CC cleanup 仅 abort，
     * 状态由执行循环检测 abort 后自行转换，Batch4 再细化）。
     *
     * @param sessionId Leader 会话 ID（TeammateIdentity.parentSessionId，types.ts:19）
     * @return 实际 abort 的 teammate 数
     */
    public int cleanupSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return 0;
        }
        int aborted = 0;
        // [session-id-short] 双形态裸 equals 根因消失：SessionService.delete 传 raw 'sess-xxx'，
        // identity.parentSessionId() 存 ctx.sessionId() 同为 short（SubagentTool 直传）→ 裸 String equals 必中。
        // 原 A2-FIX canonicalUuid 归一化比较删除。
        for (AutonomousAgentLoop loop : loops.values()) {
            InProcessTeammateTaskState st = loop.taskState();
            if (st == null || st.identity() == null) {
                continue;
            }
            if (!sessionId.equals(st.identity().parentSessionId())) {
                continue;
            }
            Runnable unregister = st.unregisterCleanup();
            if (unregister == null) {
                log.warn("cleanupSession: session={} teammate {} 缺 unregisterCleanup（Batch1 前 spawn？）",
                    sessionId, st.identity().agentId());
                continue;
            }
            try {
                unregister.run();
                aborted++;
                // [loop-leak] 会话删除只 abort（CC 语义），但 runner 线程若已死/未走到 terminal，
                //   loops 条目会永久残留（旧实现即「只增不减」）。这里同时登记逐出（走同一条
                //   固定顺序管线）；正常线程随后 complete() 的登记会被幂等闸合并，不会双执行。
                onTerminal(st.taskId(), null, null);
            } catch (Exception e) {
                log.warn("cleanupSession: abort teammate {} 失败 session={}: {}",
                    st.identity().agentId(), sessionId, e.getMessage());
            }
        }
        if (log.isInfoEnabled()) {
            log.info("cleanupSession: session={} abort in-process teammate 数={}", sessionId, aborted);
        }
        return aborted;
    }

    /**
     * kill 生产入口 · 对齐 CC InProcessTeammateTask.tsx:27-30 kill → killInProcessTeammate
     * （spawnInProcess.ts:227-328）。
     *
     * @return true 实际 kill; false 无该 task / 已非 running
     */
    public boolean kill(String taskId) {
        AutonomousAgentLoop loop = taskId != null ? loops.get(taskId) : null;
        if (loop == null) {
            if (log.isDebugEnabled()) {
                log.debug("InProcessTeammateTaskRegistry.kill: task {} 不存在, no-op", taskId);
            }
            return false;
        }
        return loop.kill();
    }

    /**
     * complete 生产入口 · 对齐 CC inProcessRunner.ts:1419-1461。
     */
    public boolean complete(String taskId) {
        AutonomousAgentLoop loop = taskId != null ? loops.get(taskId) : null;
        return loop != null && loop.complete();
    }

    /**
     * fail 生产入口 · 对齐 CC inProcessRunner.ts:1465-1533。
     */
    public boolean fail(String taskId, String error) {
        AutonomousAgentLoop loop = taskId != null ? loops.get(taskId) : null;
        return loop != null && loop.fail(error);
    }

    /**
     * 终端任务 evict 桥接 · 对齐 CC framework.ts:124-147 evictTerminalTask。
     *
     * <p><b>立即逐出</b>（跳过 30s 窗口；仅供 {@link #cleanupSession} / 显式清理使用）。
     * 正常终态走 {@link #onTerminal} 的延迟管线，两者最终都汇到 {@link #runEviction}。
     */
    public void evictTerminalTask(String taskId) {
        runEviction(taskId, null, null);
    }

    /**
     * 终态登记点 · 由 {@link AutonomousAgentLoop} 在 terminal 转换完成后调用
     * （对齐 CC「终态 + notified → {@code evictAfter = now + ML}」）。
     *
     * <p>不立即删：CC 的 30s 展示窗口内 SendMessage 仍应看到「agent-stopped（带 status）」，
     * 窗口过后记录消失才转成「agent-evicted」，否则会在「task 还在但已退役」的窗口里误走
     * subagent resume 分支。
     *
     * @param taskId         任务 ID（teammate taskId，≠ agentId）
     * @param terminalStatus completed/failed/killed（可空 = 执行时按任务记录现读）
     * @param stopReason     killed → {@code "killed"}；completed/failed → null
     */
    public void onTerminal(String taskId, String terminalStatus, String stopReason) {
        if (taskId == null) {
            return;
        }
        if (!evictionScheduled.add(taskId)) {
            log.debug("[InProcessTeammateTaskRegistry] onTerminal: task {} 已排入逐出，忽略重复登记", taskId);
            return;
        }
        long delay = evictAfterMs;
        log.info("[InProcessTeammateTaskRegistry] 终态逐出已登记: task={} status={} stopReason={} "
            + "delayMs={}（对齐 CC evictAfter=now+30000）", taskId, terminalStatus, stopReason, delay);
        EVICT_SCHEDULER.schedule(() -> runEviction(taskId, terminalStatus, stopReason),
            delay, TimeUnit.MILLISECONDS);
    }

    /**
     * 逐出执行体 · <b>固定顺序</b>（顺序错了会误走 subagent resume 或泄漏配额）：
     * <ol>
     *   <li><b>task evict</b> —— 删 BackgroundTask 记录（SendMessage 的 evicted 判据 = task==null）</li>
     *   <li><b>registry remove</b> —— 删 loop 条目（修「loops 只增不减」内存泄漏）</li>
     *   <li><b>retire</b> —— 名字/agentId/终态/transcript/停止原因进退役表，活跃表不再命中</li>
     *   <li><b>release quota</b> —— 归还 spawn/resume 预留的会话并发槽</li>
     * </ol>
     * 幂等：同一 taskId 重复调用只会执行一次（{@link #evictionScheduled} 闸）。
     */
    private void runEviction(String taskId, String terminalStatus, String stopReason) {
        if (taskId == null) {
            return;
        }
        AutonomousAgentLoop loop = loops.get(taskId);
        com.nexusai.application.agent.team.TeammateIdentity identity =
            loop != null && loop.taskState() != null ? loop.taskState().identity() : null;
        String agentId = identity != null ? identity.agentId() : null;
        String status = terminalStatus;
        if (status == null && taskFrameworkService != null) {
            status = taskFrameworkService.getTask(taskId)
                .map(t -> t.status().getStatusString()).orElse(null);
        }
        String reason = stopReason;
        if (reason == null && status != null && "killed".equalsIgnoreCase(status)) {
            reason = "killed";
        }
        try {
            // 1. task evict
            if (taskFrameworkService != null) {
                taskFrameworkService.evictTerminalTask(taskId);
            }
            notifyStage(LifecycleStage.TASK_EVICTED, taskId, agentId);
            // 2. registry remove（loop 表释放 —— 修只增不减）
            loops.remove(taskId);
            notifyStage(LifecycleStage.REGISTRY_REMOVED, taskId, agentId);
            // 3. retire（task 记录已消失 ⇒ 解析走「活跃 → 退役」而非 agent-stopped）
            if (identity != null && agentNameRegistry != null) {
                try {
                    agentNameRegistry.retireAgent(identity.parentSessionId(), identity.agentName(),
                        identity.agentId(), status, reason);
                } catch (Exception e) {
                    log.warn("[InProcessTeammateTaskRegistry] 退役 teammates 失败 task={} agent={}: {}",
                        taskId, agentId, e.toString());
                }
            }
            notifyStage(LifecycleStage.RETIRED, taskId, agentId);
            // 4. release quota（释放句柄幂等）
            releaseQuota(taskId);
            notifyStage(LifecycleStage.QUOTA_RELEASED, taskId, agentId);
            log.info("[InProcessTeammateTaskRegistry] 逐出完成: task={} agent={} status={} "
                + "（task evict → registry remove → retire → release quota）", taskId, agentId, status);
        } catch (Exception e) {
            log.warn("[InProcessTeammateTaskRegistry] 逐出 pipeline 异常 task={}: {}", taskId, e.toString());
        } finally {
            evictionScheduled.remove(taskId);
        }
    }

    /**
     * 显式归还某个任务的并发槽（spawn 在「已登记句柄但启动失败」的异常路径用）。
     *
     * <p>与 {@link #runEviction} 内的释放共用同一份句柄表：先释放者 remove，后到者是 no-op
     * ⇒ 不会重复归还。⚠️ 只用于「确定不再进入逐出管线」的失败路径；正常终态一律走 30s 管线。
     */
    public void releaseQuotaForTask(String taskId) {
        releaseQuota(taskId);
    }

    /**
     * 释放 spawn 预留的会话并发槽 · 逐出管线的<b>唯一</b>释放点。
     *
     * <p>为什么只在逐出释放：所有终态路由（completed / failed / killed / cleanupSession abort）
     * 都经 {@link #onTerminal} 汇入同一条 30s 管线 ⇒ 在这里释放一次即覆盖全部终态；
     * 句柄 {@code remove} 后再次逐出是 no-op，{@link ResumeQuota.Release#release()} 本身也幂等
     * ⇒ 重复调用不会把计数减成负数。
     *
     * <p>⛔ 不要在 {@link #onTerminal} 提前释放：B1 固定的生命周期顺序是
     * {@code task evict → registry remove → retire → release quota}，提前释放会让
     * 「task 记录已消失 / 已退役」的观测点失真。
     */
    private void releaseQuota(String taskId) {
        ResumeQuota.Release release = quotaReleases.remove(taskId);
        if (release == null) {
            return;
        }
        try {
            release.release();
        } catch (Exception e) {
            log.warn("[InProcessTeammateTaskRegistry] 释放并发槽失败 task={}: {}", taskId, e.toString());
        }
    }

    private void notifyStage(LifecycleStage stage, String taskId, String agentId) {
        LifecycleObserver observer = lifecycleObserver;
        if (observer == null) {
            return;
        }
        try {
            observer.onStage(stage, taskId, agentId);
        } catch (Exception e) {
            log.debug("[InProcessTeammateTaskRegistry] lifecycle observer 异常: {}", e.toString());
        }
    }

    /**
     * 状态载体 → BackgroundTask 状态层 · 对齐 CC framework.ts:77-117 registerTask
     * 收 taskState（含 type:'in_process_teammate' + status:'running' + description）。
     *
     * <p>description = {@code `${name}: ${prompt.substring(0,50)}...`}（CC spawnInProcess.ts:155）；
     * outputFile 走唯一根 {@code BackgroundTaskRunner.taskOutputPath(taskId)}（对齐 CC
     * getTaskOutputPath(id)，旧平铺 /tmp/agent-{taskId}.out 已删）。
     */
    static BackgroundTask toBackgroundTask(InProcessTeammateTaskState state, String toolUseId) {
        String name = state.identity().agentName();
        String prompt = state.prompt() != null ? state.prompt() : "";
        String description = name + ": "
            + (prompt.length() > 50 ? prompt.substring(0, 50) + "..." : prompt);
        // [刀 1a · teammate 会话归属] 显式把父会话装进 BackgroundTask.sessionId。
        //   WHY：13 参兼容构造默认 sessionId=null（BackgroundTask.java:174 javadoc 自陈），而
        //   TaskController.listTasks 会话级过滤把 sessionId==null 的任务整条剔除（sessionId() == null
        //   → continue）⇒ teammate 在会话级 REST 清单里不可见，前端只剩 STOMP 实时一条命（事件丢窗即
        //   永久空白）。对照：普通 local_agent 由 spawn 侧显式带 createSessionId（类型间不一致）。
        //   parentSessionId 可能为 null（standalone teammate）⇒ withSessionId(null) 保持既有
        //   sessionId=null 语义，不伪造会话。
        String parentSessionId = state.identity().parentSessionId();
        BackgroundTask task = new BackgroundTask(
            state.taskId(), TaskType.IN_PROCESS_TEAMMATE, BackgroundTaskStatus.RUNNING,
            description, toolUseId,
            System.currentTimeMillis(), null, null,
            // [批 3b-D7] 输出根所属会话 = teammate 的父会话（SpawnContext.parentSessionId 显式装入）；
                //   ⛔ 不再由下游读 MDC（本方法可能在非会话线程构造 BackgroundTask）
                com.nexusai.application.agent.tasks.BackgroundTaskRunner.taskOutputPath(
                    parentSessionId, state.taskId()), 0L, false,
            null, true);
        // withSessionId = 返回新对象的不可变副本（BackgroundTask.java:195-200），20 个实参位与 canonical
        //   record 构造一一对应（仅 sessionId 槽替换）⇒ 语义等价（已读码确证，非采信上游摘要）。
        return task.withSessionId(parentSessionId);
    }
}
