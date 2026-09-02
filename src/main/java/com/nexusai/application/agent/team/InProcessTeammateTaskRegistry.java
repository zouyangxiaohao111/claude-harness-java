package com.nexusai.application.agent.team;

import com.nexusai.application.agent.subagent.AutonomousAgentLoop;
import com.nexusai.application.agent.tasks.BackgroundTask;
import com.nexusai.application.agent.tasks.BackgroundTaskStatus;
import com.nexusai.application.agent.tasks.TaskFrameworkService;
import com.nexusai.application.agent.tasks.TaskType;
import com.nexusai.common.SessionKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    private final ConcurrentHashMap<String, AutonomousAgentLoop> loops = new ConcurrentHashMap<>();

    private final TaskFrameworkService taskFrameworkService;

    public InProcessTeammateTaskRegistry(TaskFrameworkService taskFrameworkService) {
        this.taskFrameworkService = taskFrameworkService;
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
     * 无 teammate ThreadLocal（Java 工具经 CompletableFuture 异步执行，TeammateContext ThreadLocal 不可见）。
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
     */
    public void evictTerminalTask(String taskId) {
        if (taskFrameworkService != null) {
            taskFrameworkService.evictTerminalTask(taskId);
        }
        loops.remove(taskId);
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
        return new BackgroundTask(
            state.taskId(), TaskType.IN_PROCESS_TEAMMATE, BackgroundTaskStatus.RUNNING,
            description, toolUseId,
            System.currentTimeMillis(), null, null,
            com.nexusai.application.agent.tasks.BackgroundTaskRunner.taskOutputPath(state.taskId()), 0L, false,
            null, true);
    }
}
