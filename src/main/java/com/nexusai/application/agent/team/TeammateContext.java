package com.nexusai.application.agent.team;

import com.nexusai.infra.util.AbortControllerFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * TeammateContext · 对齐 CC utils/teammateContext.ts.
 *
 * <p>L1 语义: in-process teammate AsyncLocalStorage 风格 context store。
 * getTeammateContext() → 当前 context;runWithTeammateContext(ctx, fn)→fn(ctx);
 * createTeammateContext(config) 构造新 context;isInProcessTeammate() → boolean。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: TeammateContext record (6 字段) + getTeammateContext + runWithTeammateContext + isInProcessTeammate + createTeammateContext 静态方法</li>
 *   <li><b>A2 Golden Trace</b>: 无 context→undefined;runWith→ctx 内部 getTeammateContext 返回;isInProcess true;createTeammateContext 6 字段 + isInProcess: true</li>
 *   <li><b>A3 线程隔离</b>: ThreadLocal-backed;no global state leak</li>
 *   <li><b>A4 边界</b>: null runFn→NPE;empty runWith→supplier 仅返 context;null context→throw</li>
 *   <li><b>A5 业务场景</b>: spawn in-process teammate 启 Task → runWithTeammateContext 设 context → 任务体内 getTeammateContext 返回</li>
 * </ul>
 *
 * <p>L3 升级: TS AsyncLocalStorage → Java ThreadLocal (CC 等价);
 * TS runtime context → Java record 字段;
 * TS Object spread → Java HashMap copy.
 *
 * <p><b>生产化状态（2026-08-14 用户裁定 DEC-31/32 撤销，team 运行时归 task 模块对齐度）</b>：
 * 本类为 in-process teammate 的 context 载体，已被 16 个生产/运行时文件调用
 * （LlmAgentLoop / AutonomousAgentLoop / SwarmReconnection / TaskService /
 * InProcessTeammateTaskRegistry / SpawnInProcess / Teammate / TeammateIdentity /
 * CronCreateTool / CronDeleteTool / CronListTool / SendMessageTool / SubagentTool /
 * StreamingToolExecutor / ScheduleService / ScheduleRecord）。原「本期未启用」标注已移除。
 */
public final class TeammateContext {

    private final ContextData data;

    public record ContextData(
        String agentId,
        String agentName,
        String teamName,
        String color,
        boolean planModeRequired,
        String parentSessionId,
        boolean isInProcess,
        AbortControllerFactory.AbortControllerRef abortController) {

        public ContextData {
            isInProcess = true; // always true for in-process teammates
        }
    }

    private static final ThreadLocal<TeammateContext> CURRENT = new ThreadLocal<>();

    public TeammateContext(ContextData data) {
        this.data = data;
    }

    /** Convenience 7-arg constructor (used by tests; matches CC API). */
    public TeammateContext(String agentId, String agentName, String teamName,
                           String color, boolean planModeRequired,
                           String parentSessionId, AbortControllerFactory.AbortControllerRef abortController) {
        this.data = new ContextData(agentId, agentName, teamName, color,
            planModeRequired, parentSessionId, true, abortController);
    }

    private TeammateContext() { this.data = new ContextData(null, null, null, null, false, null, true, AbortControllerFactory.create()); }

    public ContextData getData() { return data; }

    /** Returns the current in-process teammate context, or null if not in one. */
    public static TeammateContext getTeammateContext() {
        return CURRENT.get();
    }

    /** Run {@code fn} with the given context set as current. */
    public static <T> T runWithTeammateContext(TeammateContext context, Supplier<T> fn) {
        if (context == null) throw new IllegalArgumentException("context must not be null");
        if (fn == null) throw new IllegalArgumentException("fn must not be null");
        TeammateContext prev = CURRENT.get();
        try {
            CURRENT.set(context);
            return fn.get();
        } finally {
            if (prev != null) CURRENT.set(prev);
            else CURRENT.remove();
        }
    }

    /** Returns true if the current thread is running within an in-process teammate. */
    public static boolean isInProcessTeammate() {
        return CURRENT.get() != null;
    }

    /** Create a TeammateContext from spawn configuration. */
    public static TeammateContext create(TeammateConfig config) {
        return new TeammateContext(new ContextData(
            config.agentId(),
            config.agentName(),
            config.teamName(),
            config.color(),
            config.planModeRequired(),
            config.parentSessionId(),
            true,
            config.abortController() != null
                ? config.abortController()
                : AbortControllerFactory.create()));
    }

    public record TeammateConfig(
        String agentId,
        String agentName,
        String teamName,
        String color,
        boolean planModeRequired,
        String parentSessionId,
        AbortControllerFactory.AbortControllerRef abortController) {}
}
