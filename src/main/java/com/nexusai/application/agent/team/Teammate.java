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
 *   <li>AsyncLocalStorage → {@link TeammateIdentity} 显式形参（[S1-T5] 由 ToolUseContext
 *       {@code teammateIdentity} 沿链显式下传；原 ThreadLocal 载体已随 S1-T4 删除）；</li>
 *   <li>[S1-T13] {@code dynamicTeamContext}（进程级 tmux 槽）<b>整体删除</b>：
 *       CC 的 CLI 形态是「一 teammate 一进程」（tmux 起独立 CLI），进程级槽 == teammate 作用域；
 *       本仓是单 JVM 多会话 Web 后端，保留该槽 = A 会话身份被 B 会话读到的跨会话缺陷。
 *       ⇒ 身份唯一来源 = 显式 {@link TeammateIdentity}；部署侧 sysprop
 *       （{@code nexusai.agent.name}/{@code nexusai.team.name}/{@code nexusai.agent.color}）
 *       仅由 {@link TeammateContextBootstrap} 在启动期校验，web 部署下出现即 <b>fail-fast</b>。
 *       name/team/color 解析运行期<b>无 sysprop 尾回退</b>（对齐 CC teammate.ts:98-142）。</li>
 * </ul>
 *
 * <p>本类为 teammate.ts 的 Java 等价模块，静态工具类风格同 {@link TeammateMailbox}（无实例状态）。
 */
public final class Teammate {

    private static final Logger log = LoggerFactory.getLogger(Teammate.class);

    private Teammate() {}

    // ════════════════════════════════════════════════════════════════════════
    // 身份解析 · 唯一来源 = 显式形参 identity（TUC 载体）
    //   [S1-T5] 原优先级 1「in-process ThreadLocal 载体」改为**显式首参**：ThreadLocal 不跨线程
    //   （工具执行池线程 / hook 池线程读恒 null），且用户铁律「会话态一律显式传参，回放不算合规」
    //   ⇒ 身份由 ToolUseContext.teammateIdentity() 沿链显式下传，本类不再读任何 ThreadLocal。
    //   [S1-T13] 原优先级 2「进程级 static volatile dynamicTeamContext」**整体删除**：
    //   CC 的该槽（teammate.ts:44-51）只由 CLI 参数（--agent-id/--agent-name/--team-name）在
    //   main.tsx:1203 填充，而 CLI 的语义前提是「**一个 teammate 一个进程**」（tmux 为每个
    //   teammate 起独立 CLI 进程）⇒ 进程级槽 == teammate 作用域。本仓是**单 JVM 多会话**的
    //   Web 后端，同一个进程级槽会让 A 会话的身份被 B 会话读到（本仓「会话身份最纯的跨会话槽」）。
    //   故身份一律经 {@link TeammateIdentity} 显式传参（来源 = TUC 组件 / 会话级身份），
    //   本类**零**进程级状态。sysprop 侧的启动校验见 {@link TeammateContextBootstrap}。
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 返回 agent ID（running-as-teammate 时），standalone 会话返回 null。
     * 唯一来源：显式 identity。对齐 CC teammate.ts:88-92（CC 侧 tmux 分支已随本仓定位删除）。
     *
     * @param identity 本 agent 的 teammate 身份（null = 非 teammate；⛔ 不回落任何进程级槽）
     */
    public static String getAgentId(TeammateIdentity identity) {
        return identity != null ? identity.agentId() : null;
    }

    /**
     * 返回 agent 名（无 @ 后缀）。
     * 唯一来源：显式 identity（无 sysprop 回退）。对齐 CC teammate.ts:98-102。
     *
     * @param identity 本 agent 的 teammate 身份（null = 非 teammate）
     */
    public static String getAgentName(TeammateIdentity identity) {
        if (identity != null) {
            return identity.agentName();
        }
        if (log.isDebugEnabled()) {
            log.debug("[Teammate] getAgentName：显式 identity 为 null，返回 null（对齐 CC teammate.ts:98-102，无 sysprop 尾回退）");
        }
        return null;
    }

    /**
     * 返回 team 名。
     * 唯一来源：显式 identity（无 sysprop 回退）。对齐 CC teammate.ts:111-118。
     *
     * @param identity 本 agent 的 teammate 身份（null = 非 teammate）
     */
    public static String getTeamName(TeammateIdentity identity) {
        if (identity != null) {
            return identity.teamName();
        }
        if (log.isDebugEnabled()) {
            log.debug("[Teammate] getTeamName：显式 identity 为 null，返回 null（对齐 CC teammate.ts:111-118，无 sysprop 尾回退）");
        }
        return null;
    }

    /**
     * 返回 team 名（带 teamContext 回退——leader 无 identity 时经 AppState 传入）。
     * 优先级：显式 identity &gt; {@code teamContextTeamName}（无 sysprop 回退）。
     * 对齐 CC teammate.ts:111-118（{@code teamContext?.teamName} 为第 3 优先级）。
     *
     * @param identity            本 agent 的 teammate 身份（null = 非 teammate）
     * @param teamContextTeamName 可选 team 名（CC 参数 teamContext.teamName，来自 AppState）
     */
    public static String getTeamName(TeammateIdentity identity, String teamContextTeamName) {
        if (identity != null) {
            return identity.teamName();
        }
        if (teamContextTeamName != null && !teamContextTeamName.isBlank()) {
            return teamContextTeamName;
        }
        if (log.isDebugEnabled()) {
            log.debug("[Teammate] getTeamName(String)：显式 identity/teamContext 均空，返回 null（对齐 CC teammate.ts:111-118，无 sysprop 尾回退）");
        }
        return null;
    }

    /**
     * 返回 true 当本会话作为 swarm 中的 teammate 运行。
     * 唯一判据：显式 identity 非 null。对齐 CC teammate.ts:125-131（无 env/sysprop 逐次回退）。
     *
     * @param identity 本 agent 的 teammate 身份（null = 非 teammate）
     */
    public static boolean isTeammate(TeammateIdentity identity) {
        // [S1-T13] 判据单点化：唯一来源 = 显式身份载体。
        //   CC 侧 in-process 分支为真 ⇒ true；tmux 分支（进程级 dynamicTeamContext）在本仓
        //   无对应部署形态（见类头 WHY），已随槽删除。
        return identity != null;
    }

    /**
     * 返回 teammate 分配的颜色；非 teammate 或无颜色返回 null。
     * 唯一来源：显式 identity（无 sysprop 回退）。对齐 CC teammate.ts:138-145。
     *
     * @param identity 本 agent 的 teammate 身份（null = 非 teammate）
     */
    public static String getTeammateColor(TeammateIdentity identity) {
        if (identity != null) {
            return identity.color();
        }
        if (log.isDebugEnabled()) {
            log.debug("[Teammate] getTeammateColor：显式 identity 为 null，返回 null（对齐 CC teammate.ts:138-145，无 sysprop 尾回退）");
        }
        return null;
    }

    /**
     * 返回 true 当该 teammate 实施前必须先进入 plan 模式并获批准。
     * 优先级：显式 identity &gt; env CLAUDE_CODE_PLAN_MODE_REQUIRED。
     * 对齐 CC teammate.ts:149-156。
     *
     * @param identity 本 agent 的 teammate 身份（null = 非 teammate）
     */
    public static boolean isPlanModeRequired(TeammateIdentity identity) {
        if (identity != null) {
            return identity.planModeRequired();
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
     * @param identity    本 agent 的 teammate 身份（[S1-T5] 显式首参，替代原 ThreadLocal 读）
     * @param leadAgentId team 配置的 lead agent ID（CC 参数 teamContext.leadAgentId），null 恒非 lead
     * @return true=team lead；false=非 lead
     */
    public static boolean isTeamLead(TeammateIdentity identity, String leadAgentId) {
        if (leadAgentId == null) {
            return false; // CC :178-180: !teamContext?.leadAgentId → false
        }
        String myAgentId = getAgentId(identity);
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
