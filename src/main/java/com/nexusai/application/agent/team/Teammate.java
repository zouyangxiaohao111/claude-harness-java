package com.nexusai.application.agent.team;

import com.nexusai.application.agent.subagent.AutonomousAgentLoop;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.infra.util.SwarmConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Teammate 身份解析工具 · 对齐 CC utils/teammate.ts（14 个 export 函数）。
 *
 * <p>CC 真源结构（grep 自验 teammate.ts，不信注释）：
 * <ul>
 *   <li>模块级 {@code dynamicTeamContext}（teammate.ts:44-51）——tmux teammate 经 CLI 参数
 *       （--agent-id/--agent-name/--team-name）在 main.tsx:1203 由
 *       {@code setDynamicTeamContext} 填充；in-process teammate 经 AsyncLocalStorage 隔离。</li>
 *   <li>身份解析优先级：AsyncLocalStorage（in-process）&gt; dynamicTeamContext（tmux）。
 *       见各方法 Javadoc 内 CC 行号。</li>
 * </ul>
 *
 * <p>Java 映射：
 * <ul>
 *   <li>AsyncLocalStorage → {@link TeammateContext}（ThreadLocal 载体）；</li>
 *   <li>{@code dynamicTeamContext} → 本类模块级 {@link #dynamicTeamContext} 状态（{@link DynamicTeamContext} record）；</li>
 *   <li>CC main.tsx 由 CLI 参数填充 dynamicTeamContext → Java 无 CLI，部署侧以 sysprop
 *       {@code nexusai.agent.name}/{@code nexusai.team.name}/{@code nexusai.agent.color} 代理
 *       （{@link TaskSystemConfig}），由 {@link TeammateContextBootstrap} 启动阶段一次性注入
 *       dynamicTeamContext（对齐 CC main.tsx:1202-1211）；name/team/color 解析运行期
 *       <b>无 sysprop 尾回退</b>（对齐 CC teammate.ts:98-142）。</li>
 * </ul>
 *
 * <p>本类为 teammate.ts 的 Java 等价模块，静态工具类风格同 {@link TeammateMailbox}（无实例状态）。
 */
public final class Teammate {

    private static final Logger log = LoggerFactory.getLogger(Teammate.class);

    private Teammate() {}

    // ════════════════════════════════════════════════════════════════════════
    // dynamicTeamContext 模块级状态 · 对齐 CC teammate.ts:44-51
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 动态 team 上下文（runtime team join 时设置，值优先于环境变量）。
     *
     * @param agentId         完整 agent ID（"name@team"，CC :46）
     * @param agentName       显示名（无 @ 后缀，CC :47）
     * @param teamName        team 名（CC :48）
     * @param color           可选 UI 颜色（CC :49）
     * @param planModeRequired 是否必须先进入 plan 模式（CC :50）
     * @param parentSessionId 可选 leader session ID（CC :51）
     */
    public record DynamicTeamContext(
        String agentId,
        String agentName,
        String teamName,
        String color,
        boolean planModeRequired,
        String parentSessionId) {}

    private static volatile DynamicTeamContext dynamicTeamContext;

    /**
     * 设置动态 team 上下文（runtime join team 时调用）· 对齐 CC teammate.ts:56-67。
     */
    public static void setDynamicTeamContext(DynamicTeamContext context) {
        dynamicTeamContext = context;
        if (log.isDebugEnabled()) {
            log.debug("[Teammate] 设置动态 team 上下文 agentId={} teamName={}",
                context != null ? context.agentId() : null,
                context != null ? context.teamName() : null);
        }
    }

    /**
     * 清除动态 team 上下文（离开 team 时调用）· 对齐 CC teammate.ts:72-74。
     */
    public static void clearDynamicTeamContext() {
        dynamicTeamContext = null;
        if (log.isDebugEnabled()) {
            log.debug("[Teammate] 清除动态 team 上下文");
        }
    }

    /**
     * 获取当前动态 team 上下文（供检查/调试）· 对齐 CC teammate.ts:79-81。
     */
    public static DynamicTeamContext getDynamicTeamContext() {
        return dynamicTeamContext;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 身份解析 · 优先级 in-process (TeammateContext ThreadLocal) > dynamicTeamContext
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 返回当前 teammate 的 parent session ID（in-process 为 team lead 的 session ID）。
     * 优先级：in-process &gt; dynamicTeamContext。对齐 CC teammate.ts:34-38。
     */
    public static String getParentSessionId() {
        TeammateContext inProcess = TeammateContext.getTeammateContext();
        if (inProcess != null) {
            return inProcess.getData().parentSessionId();
        }
        return dynamicTeamContext != null ? dynamicTeamContext.parentSessionId() : null;
    }

    /**
     * 返回 agent ID（running-as-teammate 时），standalone 会话返回 null。
     * 优先级：in-process &gt; dynamicTeamContext。对齐 CC teammate.ts:88-92。
     */
    public static String getAgentId() {
        TeammateContext inProcess = TeammateContext.getTeammateContext();
        if (inProcess != null) {
            return inProcess.getData().agentId();
        }
        return dynamicTeamContext != null ? dynamicTeamContext.agentId() : null;
    }

    /**
     * 返回 agent 名（无 @ 后缀）。
     * 优先级：in-process &gt; dynamicTeamContext（无 sysprop 回退）。
     * 对齐 CC teammate.ts:98-102。
     */
    public static String getAgentName() {
        TeammateContext inProcess = TeammateContext.getTeammateContext();
        if (inProcess != null) {
            return inProcess.getData().agentName();
        }
        if (dynamicTeamContext != null && dynamicTeamContext.agentName() != null) {
            return dynamicTeamContext.agentName();
        }
        if (log.isDebugEnabled()) {
            log.debug("[Teammate] getAgentName：无 in-process 上下文与 dynamicTeamContext，返回 null（对齐 CC teammate.ts:98-102，无 sysprop 尾回退）");
        }
        return null;
    }

    /**
     * 返回 team 名。
     * 优先级：in-process &gt; dynamicTeamContext（无 sysprop 回退）。
     * 对齐 CC teammate.ts:111-118。
     */
    public static String getTeamName() {
        TeammateContext inProcess = TeammateContext.getTeammateContext();
        if (inProcess != null) {
            return inProcess.getData().teamName();
        }
        if (dynamicTeamContext != null && dynamicTeamContext.teamName() != null) {
            return dynamicTeamContext.teamName();
        }
        if (log.isDebugEnabled()) {
            log.debug("[Teammate] getTeamName：无 in-process 上下文与 dynamicTeamContext，返回 null（对齐 CC teammate.ts:111-118，无 sysprop 尾回退）");
        }
        return null;
    }

    /**
     * 返回 team 名（带 teamContext 回退——leader 无 dynamicTeamContext 时经 AppState 传入）。
     * 优先级：in-process &gt; dynamicTeamContext &gt; {@code teamContextTeamName}（无 sysprop 回退）。
     * 对齐 CC teammate.ts:111-118（{@code teamContext?.teamName} 为第 3 优先级）。
     *
     * @param teamContextTeamName 可选 team 名（CC 参数 teamContext.teamName，来自 AppState）
     */
    public static String getTeamName(String teamContextTeamName) {
        TeammateContext inProcess = TeammateContext.getTeammateContext();
        if (inProcess != null) {
            return inProcess.getData().teamName();
        }
        if (dynamicTeamContext != null && dynamicTeamContext.teamName() != null) {
            return dynamicTeamContext.teamName();
        }
        if (teamContextTeamName != null && !teamContextTeamName.isBlank()) {
            return teamContextTeamName;
        }
        if (log.isDebugEnabled()) {
            log.debug("[Teammate] getTeamName(String)：无 in-process/dynamic/teamContext，返回 null（对齐 CC teammate.ts:111-118，无 sysprop 尾回退）");
        }
        return null;
    }

    /**
     * 返回 true 当本会话作为 swarm 中的 teammate 运行。
     * in-process → true；否则需同时有 agentId 与 teamName（dynamicTeamContext）。
     * 对齐 CC teammate.ts:125-131（无 env/sysprop 逐次回退——dynamicTeamContext 由
     * {@link TeammateContextBootstrap} 启动阶段一次性填充）。
     */
    public static boolean isTeammate() {
        // in-process teammate 在同一进程内运行
        if (TeammateContext.getTeammateContext() != null) {
            return true;
        }
        // dynamic team context：需同时有 agentId 与 teamName（CC :130）
        return dynamicTeamContext != null
                && dynamicTeamContext.agentId() != null
                && dynamicTeamContext.teamName() != null;
    }

    /**
     * 返回 teammate 分配的颜色；非 teammate 或无颜色返回 null。
     * 优先级：in-process &gt; dynamicTeamContext（无 sysprop 回退）。
     * 对齐 CC teammate.ts:138-145。
     */
    public static String getTeammateColor() {
        TeammateContext inProcess = TeammateContext.getTeammateContext();
        if (inProcess != null) {
            return inProcess.getData().color();
        }
        if (dynamicTeamContext != null && dynamicTeamContext.color() != null) {
            return dynamicTeamContext.color();
        }
        if (log.isDebugEnabled()) {
            log.debug("[Teammate] getTeammateColor：无 in-process 上下文与 dynamicTeamContext，返回 null（对齐 CC teammate.ts:138-145，无 sysprop 尾回退）");
        }
        return null;
    }

    /**
     * 返回 true 当该 teammate 实施前必须先进入 plan 模式并获批准。
     * 优先级：in-process &gt; dynamicTeamContext &gt; env CLAUDE_CODE_PLAN_MODE_REQUIRED。
     * 对齐 CC teammate.ts:149-156。
     */
    public static boolean isPlanModeRequired() {
        TeammateContext inProcess = TeammateContext.getTeammateContext();
        if (inProcess != null) {
            return inProcess.getData().planModeRequired();
        }
        if (dynamicTeamContext != null) {
            return dynamicTeamContext.planModeRequired();
        }
        // CC :155 isEnvTruthy(process.env.CLAUDE_CODE_PLAN_MODE_REQUIRED)
        String env = System.getenv(SwarmConstants.PLAN_MODE_REQUIRED_ENV_VAR);
        return TaskSystemConfig.isEnvTruthy(env);
    }

    /**
     * 判定当前执行者是否为 team lead。
     * 对齐 CC teammate.ts:171-198 isTeamLead(teamContext)。
     *
     * <p>CC truth table（grep 自验 :178-197，不信注释）：
     * <ol>
     *   <li>{@code !teamContext?.leadAgentId → false}（:178-180）——无 lead 恒非 lead；</li>
     *   <li>{@code myAgentId === leadAgentId → true}（:187-189）——自身 agent id 等于 lead；</li>
     *   <li>{@code !myAgentId → true}（:193-195）——无 agent id（原始创建 team 的 session）= lead
     *       （向后兼容）；</li>
     *   <li>否则 {@code false}（:197）——teammate，agent id != leadAgentId。</li>
     * </ol>
     *
     * @param leadAgentId team 配置的 lead agent ID（CC 参数 teamContext.leadAgentId），null 恒非 lead
     * @return true=team lead；false=非 lead
     */
    public static boolean isTeamLead(String leadAgentId) {
        if (leadAgentId == null) {
            return false; // CC :178-180: !teamContext?.leadAgentId → false
        }
        String myAgentId = getAgentId();
        if (myAgentId != null && myAgentId.equals(leadAgentId)) {
            return true; // CC :187-189: myAgentId === leadAgentId → true
        }
        if (myAgentId == null) {
            return true; // CC :193-195: 主会话无 agent id（原始 session）= lead
        }
        return false; // CC :197: teammate, agentId != leadAgentId → false
    }

    // ════════════════════════════════════════════════════════════════════════
    // in-process teammate 存活/工作态 · 对齐 CC teammate.ts:205-231
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 是否存在存活（active）的 in-process teammate。
     * 对齐 CC teammate.ts:205-213 hasActiveInProcessTeammates(appState)。
     *
     * <p>CC 判 {@code task.type === 'in_process_teammate' && task.status === 'running'}；
     * Java 侧经 {@link AutonomousAgentLoop#isRunningInProcessTeammate()} 反查
     * {@link com.nexusai.application.agent.tasks.TaskFrameworkService} 的 BackgroundTask
     * type/status（IN_PROCESS_TEAMMATE + RUNNING），不再以「未 abort 未 shutdown」代理。
     *
     * @param loops in-process teammate 运行循环集合（Java AppState.tasks 等价）
     */
    public static boolean hasActiveInProcessTeammates(Collection<AutonomousAgentLoop> loops) {
        if (loops == null || loops.isEmpty()) {
            return false;
        }
        for (AutonomousAgentLoop loop : loops) {
            if (loop.isRunningInProcessTeammate()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否存在仍在工作（working）的 in-process teammate（running 但非 idle）。
     * 对齐 CC teammate.ts:220-231 hasWorkingInProcessTeammates(appState)。
     *
     * <p>CC 判 {@code type === 'in_process_teammate' && status === 'running' && !task.isIdle}；
     * Java 侧 = {@link AutonomousAgentLoop#isRunningInProcessTeammate()} && !isIdle。
     *
     * @param loops in-process teammate 运行循环集合
     */
    public static boolean hasWorkingInProcessTeammates(Collection<AutonomousAgentLoop> loops) {
        if (loops == null || loops.isEmpty()) {
            return false;
        }
        for (AutonomousAgentLoop loop : loops) {
            if (loop.isRunningInProcessTeammate() && !loop.isIdle()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 等待所有 working 的 in-process teammate 变为 idle。
     * 对齐 CC teammate.ts:238-292 waitForTeammatesToBecomeIdle(setAppState, appState)。
     *
     * <p>CC 在每个 working teammate 的 task 上注册 onIdleCallbacks；无 working 时立即 resolve。
     * Java 经 {@link AutonomousAgentLoop#addOnIdleCallbackIfNotIdle} 原子注册（either-or，
     * 对齐 CC :279-286），{@link CompletableFuture} 承载 resolve 语义（CC Promise）。
     *
     * @param loops in-process teammate 运行循环集合
     * @return 立即完成（无 working）或全部 working 转 idle 后完成的 future
     */
    public static CompletableFuture<Void> waitForTeammatesToBecomeIdle(
            Collection<AutonomousAgentLoop> loops) {
        if (loops == null || loops.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        List<AutonomousAgentLoop> working = loops.stream()
                .filter(l -> l.isRunningInProcessTeammate() && !l.isIdle())
                .toList();
        if (working.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        AtomicInteger remaining = new AtomicInteger(working.size());
        Runnable onIdle = () -> {
            if (remaining.decrementAndGet() == 0) {
                future.complete(null);
            }
        };
        for (AutonomousAgentLoop loop : working) {
            // 竞态防护（CC :279-286 either-or）：注册与快照之间 teammate 可能已转 idle。
            // addOnIdleCallbackIfNotIdle 原子判断（非 idle 才注册，与 transitionToIdle 同锁）；
            // 返回 false = 已 idle（未注册）→ 手动补触发一次（不会二次 complete）。
            if (!loop.addOnIdleCallbackIfNotIdle(onIdle)) {
                onIdle.run();
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("[Teammate] 等待 {} 个 working teammate 转 idle", working.size());
        }
        return future;
    }
}
