package com.nexusai.application.agent.team;

import com.nexusai.application.agent.subagent.AutonomousAgentLoop;
import com.nexusai.application.agent.tasks.BackgroundTask;
import com.nexusai.application.agent.tasks.BackgroundTaskStatus;
import com.nexusai.application.agent.tasks.TaskFrameworkService;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.tasks.TaskType;
import com.nexusai.infra.util.AbortControllerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-A · teammate 身份解析 14 函数 + TeammateMode 枚举 · 对齐 CC utils/teammate.ts。
 *
 * <p><b>WHY（规则九）</b>：
 * <ul>
 *   <li><b>身份解析优先级 in-process &gt; dynamicTeamContext</b>（teammate.ts:11-13）——in-process
 *       teammate 经 AsyncLocalStorage（Java ThreadLocal）隔离，dynamicTeamContext 是 tmux teammate
 *       的 CLI 参数载体。若优先级倒置，并发 in-process teammate 的身份会被全局 dynamicTeamContext
 *       覆盖（teammateContext.ts 模块注释明示该隔离动机）。</li>
 *   <li><b>isTeammate 双条件</b>（teammate.ts:125-131）——tmux teammate 必须<b>同时</b>有 agentId
 *       与 teamName 才算 teammate；只设其一不算。否则半个身份被误判为 teammate，SendMessage/shutdown
 *       等 swarm 分支被错误触发。</li>
 *   <li><b>isTeamLead 向后兼容分支</b>（teammate.ts:193-195）——主会话无 agentId（原始创建 team 的
 *       session）必须判为 lead，否则 team lead 无法批准 plan。</li>
 *   <li><b>TeammateMode 三值</b>（teammateModeSnapshot.ts:13）——原 Java {@code AUTO/PLAN/CHAT} 与
 *       CC {@code auto/tmux/in-process} 错位，错位会导致 CLI override 解析失败、模式快照错误。</li>
 * </ul>
 */
@DisplayName("T-A · teammate 身份解析 + TeammateMode 枚举（对齐 utils/teammate.ts）")
class TeammateIdentityTest {

    private static TeammateContext inProcess(String agentId, String agentName, String teamName,
                                             boolean planModeRequired) {
        return new TeammateContext(agentId, agentName, teamName, "#ff0000", planModeRequired,
                "leader-session-1", AbortControllerFactory.create());
    }

    @BeforeEach
    void setUp() {
        Teammate.clearDynamicTeamContext();
        TeammateModeSnapshot.resetForTest();
        TaskSystemConfig.clearForTest();
    }

    @AfterEach
    void tearDown() {
        Teammate.clearDynamicTeamContext();
        TeammateModeSnapshot.resetForTest();
        TaskSystemConfig.clearForTest();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 身份解析优先级 in-process > dynamicTeamContext
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("getAgentId/getAgentName: in-process (ThreadLocal) 优先于 dynamicTeamContext（teammate.ts:88-102）")
    void inProcessContext_beatsDynamicTeamContext() {
        // WHY: in-process teammate 经 ThreadLocal 隔离，必须优先；否则并发 teammate 身份被全局覆盖。
        Teammate.setDynamicTeamContext(new Teammate.DynamicTeamContext(
                "tmux@team", "tmux-name", "team", null, false, "parent-tmux"));
        TeammateContext ctx = inProcess("proc@team", "proc-name", "team", true);

        String agentId = TeammateContext.runWithTeammateContext(ctx, Teammate::getAgentId);
        String agentName = TeammateContext.runWithTeammateContext(ctx, Teammate::getAgentName);
        String parent = TeammateContext.runWithTeammateContext(ctx, Teammate::getParentSessionId);

        assertThat(agentId).as("in-process agentId 必须优先").isEqualTo("proc@team");
        assertThat(agentName).as("in-process agentName 必须优先").isEqualTo("proc-name");
        assertThat(parent).as("in-process parentSessionId 必须优先").isEqualTo("leader-session-1");
    }

    @Test
    @DisplayName("getAgentId/getAgentName: 无 in-process 时回退 dynamicTeamContext（teammate.ts:88-102）")
    void dynamicTeamContext_usedWhenNoInProcess() {
        Teammate.setDynamicTeamContext(new Teammate.DynamicTeamContext(
                "tmux@team", "tmux-name", "team", null, false, "parent-tmux"));

        assertThat(Teammate.getAgentId()).isEqualTo("tmux@team");
        assertThat(Teammate.getAgentName()).isEqualTo("tmux-name");
        assertThat(Teammate.getParentSessionId()).isEqualTo("parent-tmux");
    }

    @Test
    @DisplayName("getAgentName/getTeamName/getTeammateColor: 无 sysprop 尾回退（teammate.ts:98-142）")
    void noSyspropFallback_whenNoContextAndNoDynamic() {
        // WHY: CC teammate.ts 名称/颜色解析只查 in-process + dynamicTeamContext，无 env/sysprop
        //      尾回退。sysprop 由 TeammateContextBootstrap 启动阶段一次性注入 dynamicTeamContext
        //      （对齐 CC main.tsx:1202-1211），运行期身份解析不再逐次读 sysprop。若此处仍回退
        //      sysprop，则「启动接线前」名称被误判为已配置，违背启动注入语义。
        System.setProperty("nexusai.agent.name", "cli-agent");
        System.setProperty("nexusai.team.name", "cli-team");
        System.setProperty("nexusai.agent.color", "#00ff00");

        assertThat(Teammate.getAgentName())
                .as("仅设 sysprop 无 dynamicTeamContext → agentName null").isNull();
        assertThat(Teammate.getTeamName())
                .as("仅设 sysprop 无 dynamicTeamContext → teamName null").isNull();
        assertThat(Teammate.getTeammateColor())
                .as("仅设 sysprop 无 dynamicTeamContext → color null").isNull();
        // getTeamName(String)：teamContext 参数空时，同样不落 sysprop（对齐 CC teammate.ts:117 返回 undefined）
        assertThat(Teammate.getTeamName(""))
                .as("空 teamContext 且无 dynamic → null（不落 sysprop）").isNull();
    }

    @Test
    @DisplayName("getTeamName(teamContext): 第 3 优先级 teamContext.teamName（leader 无 dynamic 时，teammate.ts:111-118）")
    void getTeamName_teamContextParamThirdPriority() {
        // WHY: leader 无 dynamicTeamContext 时经 AppState teamContext 传入 teamName（CC :104-107 注释语义）。
        assertThat(Teammate.getTeamName("from-appstate")).isEqualTo("from-appstate");

        // dynamic 优先于 teamContext 参数
        Teammate.setDynamicTeamContext(new Teammate.DynamicTeamContext(
                "a@dyn", "dyn", "dyn-team", null, false, null));
        assertThat(Teammate.getTeamName("from-appstate")).isEqualTo("dyn-team");
    }

    // ════════════════════════════════════════════════════════════════════════
    // isTeammate 双条件
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("isTeammate: in-process 恒 true（teammate.ts:125-131）")
    void isTeammate_inProcessTrue() {
        TeammateContext ctx = inProcess("p@t", "p", "t", false);
        Boolean result = TeammateContext.runWithTeammateContext(ctx, Teammate::isTeammate);
        assertThat(result).as("in-process teammate 恒 true").isTrue();
    }

    @Test
    @DisplayName("isTeammate: dynamic 需同时有 agentId 与 teamName（teammate.ts:130）")
    void isTeammate_dynamicRequiresBoth() {
        // 只有 agentId 无 teamName → 非 teammate
        Teammate.setDynamicTeamContext(new Teammate.DynamicTeamContext(
                "a@x", "a", null, null, false, null));
        assertThat(Teammate.isTeammate()).as("缺 teamName 非 teammate").isFalse();

        // 只有 teamName 无 agentId → 非 teammate
        Teammate.setDynamicTeamContext(new Teammate.DynamicTeamContext(
                null, "a", "team", null, false, null));
        assertThat(Teammate.isTeammate()).as("缺 agentId 非 teammate").isFalse();

        // 两者都有 → teammate
        Teammate.setDynamicTeamContext(new Teammate.DynamicTeamContext(
                "a@x", "a", "team", null, false, null));
        assertThat(Teammate.isTeammate()).as("agentId+teamName 齐全为 teammate").isTrue();
    }

    @Test
    @DisplayName("isTeammate: 无 in-process 无 dynamic 无 sysprop 时 false")
    void isTeammate_falseWhenNeither() {
        assertThat(Teammate.isTeammate()).isFalse();
    }

    @Test
    @DisplayName("isTeammate: 身份判断不再逐次查 sysprop（teammate.ts:125-131 无 env 回退）")
    void isTeammate_noSyspropFallback() {
        // WHY: CC isTeammate 只查 in-process + dynamicTeamContext，无 env/sysprop 逐次回退。
        //      dynamicTeamContext 由 TeammateContextBootstrap 启动阶段一次性填充。若此处仍回退
        //      sysprop，则「启动接线前」身份误判为 teammate，违背启动注入语义。
        System.setProperty("nexusai.agent.name", "cli-agent");
        System.setProperty("nexusai.team.name", "cli-team");

        assertThat(Teammate.isTeammate())
                .as("仅设 sysprop 但 dynamicTeamContext 为 null → 非 teammate").isFalse();
    }

    @Test
    @DisplayName("TeammateContextBootstrap: sysprop 启动接线填充 dynamicTeamContext")
    void teammateContextBootstrap_wiresSyspropToDynamicContext() {
        // WHY: Java 无 CLI，sysprop 代理 --agent-id/--agent-name/--team-name。启动接线读 sysprop
        //      一次性 setDynamicTeamContext，使 isTeammate 运行期不再逐次读 sysprop。
        System.setProperty("nexusai.agent.name", "cli-agent");
        System.setProperty("nexusai.team.name", "cli-team");
        System.setProperty("nexusai.agent.color", "#00ff00");

        new TeammateContextBootstrap().init();

        Teammate.DynamicTeamContext ctx = Teammate.getDynamicTeamContext();
        assertThat(ctx).as("启动接线后 dynamicTeamContext 非 null").isNotNull();
        assertThat(ctx.agentId()).as("agentId 由 agentName@teamName 派生").isEqualTo("cli-agent@cli-team");
        assertThat(ctx.agentName()).isEqualTo("cli-agent");
        assertThat(ctx.teamName()).isEqualTo("cli-team");
        assertThat(ctx.color()).isEqualTo("#00ff00");
        assertThat(Teammate.isTeammate()).as("接线后 isTeammate true").isTrue();
        // 名称解析经 dynamicTeamContext 返回（证明启动接线已覆盖 sysprop→dynamic 场景，运行期无需再回退 sysprop）
        assertThat(Teammate.getAgentName()).as("接线后 getAgentName 经 dynamic").isEqualTo("cli-agent");
        assertThat(Teammate.getTeamName()).as("接线后 getTeamName 经 dynamic").isEqualTo("cli-team");
        assertThat(Teammate.getTeammateColor()).as("接线后 getTeammateColor 经 dynamic").isEqualTo("#00ff00");
    }

    @Test
    @DisplayName("TeammateContextBootstrap: 无 sysprop 时 no-op（standalone 会话）")
    void teammateContextBootstrap_noSysprop_skips() {
        // WHY: 无 agent/team name sysprop → standalone 会话，dynamicTeamContext 保持 null（对齐 CC
        //      无 CLI 参数注入）。
        new TeammateContextBootstrap().init();
        assertThat(Teammate.getDynamicTeamContext()).isNull();
    }

    // ════════════════════════════════════════════════════════════════════════
    // isPlanModeRequired
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("isPlanModeRequired: in-process 优先于 dynamicTeamContext（teammate.ts:149-156）")
    void isPlanModeRequired_inProcessPriority() {
        Teammate.setDynamicTeamContext(new Teammate.DynamicTeamContext(
                "d@t", "d", "team", null, false, null)); // dynamic=false
        TeammateContext ctx = inProcess("p@t", "p", "team", true); // in-process=true
        Boolean result = TeammateContext.runWithTeammateContext(ctx, Teammate::isPlanModeRequired);
        assertThat(result).as("in-process planModeRequired 必须优先").isTrue();
    }

    @Test
    @DisplayName("isPlanModeRequired: 无 in-process 时读 dynamicTeamContext.planModeRequired")
    void isPlanModeRequired_dynamicValue() {
        Teammate.setDynamicTeamContext(new Teammate.DynamicTeamContext(
                "d@t", "d", "team", null, true, null));
        assertThat(Teammate.isPlanModeRequired()).isTrue();

        Teammate.setDynamicTeamContext(new Teammate.DynamicTeamContext(
                "d@t", "d", "team", null, false, null));
        assertThat(Teammate.isPlanModeRequired()).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════════
    // isTeamLead truth table
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("isTeamLead: 4 分支 truth table（teammate.ts:178-197）")
    void isTeamLead_truthTable() {
        // 1) leadAgentId null → false（:178-180）
        assertThat(Teammate.isTeamLead(null)).as("无 lead 恒非 lead").isFalse();

        // 2) myAgentId == leadAgentId → true（:187-189）
        TeammateContext ctx = inProcess("lead@team", "lead", "team", false);
        Boolean match = TeammateContext.runWithTeammateContext(ctx, () -> Teammate.isTeamLead("lead@team"));
        assertThat(match).as("agentId 等于 leadAgentId 为 lead").isTrue();

        // 3) myAgentId null → true（向后兼容，:193-195）
        Teammate.clearDynamicTeamContext();
        assertThat(Teammate.isTeamLead("lead@team")).as("主会话无 agentId 为 lead").isTrue();

        // 4) myAgentId != leadAgentId → false（:197）
        TeammateContext other = inProcess("mate@team", "mate", "team", false);
        Boolean noMatch = TeammateContext.runWithTeammateContext(other, () -> Teammate.isTeamLead("lead@team"));
        assertThat(noMatch).as("agentId != leadAgentId 非 lead").isFalse();
    }

    // ════════════════════════════════════════════════════════════════════════
    // dynamicTeamContext set/clear/get
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("setDynamicTeamContext/clearDynamicTeamContext/getDynamicTeamContext（teammate.ts:56-81）")
    void dynamicTeamContext_setClearGet() {
        assertThat(Teammate.getDynamicTeamContext()).as("初始 null").isNull();

        Teammate.DynamicTeamContext ctx = new Teammate.DynamicTeamContext(
                "a@t", "a", "team", "#123456", true, "parent");
        Teammate.setDynamicTeamContext(ctx);
        assertThat(Teammate.getDynamicTeamContext()).as("设置后应返回同一 context").isSameAs(ctx);
        assertThat(Teammate.getTeammateColor()).as("dynamic color").isEqualTo("#123456");

        Teammate.clearDynamicTeamContext();
        assertThat(Teammate.getDynamicTeamContext()).as("清除后 null").isNull();
        assertThat(Teammate.getAgentId()).as("清除后无 agentId").isNull();
    }

    // ════════════════════════════════════════════════════════════════════════
    // TeammateMode 枚举 + 快照
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TeammateMode 三值对齐 CC auto/tmux/in-process（teammateModeSnapshot.ts:13）")
    void teammateMode_threeValues() {
        assertThat(TeammateModeSnapshot.TeammateMode.AUTO.ccValue()).isEqualTo("auto");
        assertThat(TeammateModeSnapshot.TeammateMode.TMUX.ccValue()).isEqualTo("tmux");
        assertThat(TeammateModeSnapshot.TeammateMode.IN_PROCESS.ccValue()).isEqualTo("in-process");
        assertThat(TeammateModeSnapshot.TeammateMode.fromCc("in-process"))
                .isEqualTo(TeammateModeSnapshot.TeammateMode.IN_PROCESS);
        assertThat(TeammateModeSnapshot.TeammateMode.fromCc("bogus")).isNull();
    }

    @Test
    @DisplayName("captureTeammateModeSnapshot: CLI override 优先于 config，缺省 auto（teammateModeSnapshot.ts:56-69）")
    void captureTeammateModeSnapshot_cliOverridePriority() {
        // CLI override 优先
        TeammateModeSnapshot.setCliTeammateModeOverride(TeammateModeSnapshot.TeammateMode.TMUX);
        TeammateModeSnapshot.captureTeammateModeSnapshot();
        assertThat(TeammateModeSnapshot.getTeammateModeFromSnapshot())
                .isEqualTo(TeammateModeSnapshot.TeammateMode.TMUX);

        // config 缺省 → auto
        TeammateModeSnapshot.resetForTest();
        TeammateModeSnapshot.captureTeammateModeSnapshot();
        assertThat(TeammateModeSnapshot.getTeammateModeFromSnapshot())
                .isEqualTo(TeammateModeSnapshot.TeammateMode.AUTO);

        // config 提供 in-process
        TeammateModeSnapshot.resetForTest();
        TeammateModeSnapshot.setConfigTeammateModeSupplier(
                () -> TeammateModeSnapshot.TeammateMode.IN_PROCESS);
        TeammateModeSnapshot.captureTeammateModeSnapshot();
        assertThat(TeammateModeSnapshot.getTeammateModeFromSnapshot())
                .isEqualTo(TeammateModeSnapshot.TeammateMode.IN_PROCESS);
    }

    @Test
    @DisplayName("clearCliTeammateModeOverride: 清 override 并直改快照（teammateModeSnapshot.ts:43-49）")
    void clearCliTeammateModeOverride_updatesSnapshot() {
        TeammateModeSnapshot.setCliTeammateModeOverride(TeammateModeSnapshot.TeammateMode.TMUX);
        TeammateModeSnapshot.captureTeammateModeSnapshot();
        assertThat(TeammateModeSnapshot.getCliTeammateModeOverride())
                .isEqualTo(TeammateModeSnapshot.TeammateMode.TMUX);

        TeammateModeSnapshot.clearCliTeammateModeOverride(TeammateModeSnapshot.TeammateMode.IN_PROCESS);
        assertThat(TeammateModeSnapshot.getCliTeammateModeOverride()).isNull();
        assertThat(TeammateModeSnapshot.getTeammateModeFromSnapshot())
                .isEqualTo(TeammateModeSnapshot.TeammateMode.IN_PROCESS);
    }

    // ════════════════════════════════════════════════════════════════════════
    // in-process teammate 存活/工作态 · RF-4 按 CC task.type/status 过滤
    // ════════════════════════════════════════════════════════════════════════

    /** 构造一个 running 态 in-process teammate loop（taskId + taskFrameworkService 接线）。 */
    private static AutonomousAgentLoop runningLoop(TaskFrameworkService tfs, String taskId, boolean idle) {
        AutonomousAgentLoop loop = new AutonomousAgentLoop();
        loop.setTaskId(taskId);
        loop.setTaskFrameworkService(tfs);
        loop.setIdle(idle);
        return loop;
    }

    /** 向 tfs 注册一个 type + status 指定的任务。 */
    private static void registerTask(TaskFrameworkService tfs, String taskId,
                                     TaskType type, BackgroundTaskStatus status) {
        tfs.registerTask(new BackgroundTask(
            taskId, type, status, "mate: " + taskId, null,
            System.currentTimeMillis(), null, null,
            "/tmp/agent-" + taskId + ".out", 0L, false));
    }

    @Test
    @DisplayName("hasActiveInProcessTeammates: 按 CC task.type/status 过滤（teammate.ts:205-213）")
    void hasActive_filtersByTypeAndStatus() {
        assertThat(Teammate.hasActiveInProcessTeammates(null)).isFalse();
        assertThat(Teammate.hasActiveInProcessTeammates(List.of())).isFalse();

        TaskFrameworkService tfs = new TaskFrameworkService();

        // running in-process-teammate → active（无论 idle）
        registerTask(tfs, "t-run", TaskType.IN_PROCESS_TEAMMATE, BackgroundTaskStatus.RUNNING);
        AutonomousAgentLoop runningIdle = runningLoop(tfs, "t-run", true);
        assertThat(Teammate.hasActiveInProcessTeammates(List.of(runningIdle)))
                .as("running in_process_teammate 为 active（idle 也算存活）").isTrue();

        // completed → 非 active（status != running）
        registerTask(tfs, "t-done", TaskType.IN_PROCESS_TEAMMATE, BackgroundTaskStatus.COMPLETED);
        AutonomousAgentLoop completed = runningLoop(tfs, "t-done", true);
        assertThat(Teammate.hasActiveInProcessTeammates(List.of(completed)))
                .as("completed 非 running，不得 active").isFalse();

        // 类型过滤：running 但 type 非 in_process_teammate → 非 active
        registerTask(tfs, "t-bash", TaskType.LOCAL_BASH, BackgroundTaskStatus.RUNNING);
        AutonomousAgentLoop wrongType = runningLoop(tfs, "t-bash", false);
        assertThat(Teammate.hasActiveInProcessTeammates(List.of(wrongType)))
                .as("type != in_process_teammate 不得 active").isFalse();
    }

    @Test
    @DisplayName("hasWorkingInProcessTeammates: running 且非 idle 才算 working（teammate.ts:220-231）")
    void hasWorking_filtersByRunningAndNotIdle() {
        assertThat(Teammate.hasWorkingInProcessTeammates(List.of())).isFalse();

        TaskFrameworkService tfs = new TaskFrameworkService();

        // running + idle → 非 working
        registerTask(tfs, "t-idle", TaskType.IN_PROCESS_TEAMMATE, BackgroundTaskStatus.RUNNING);
        AutonomousAgentLoop idle = runningLoop(tfs, "t-idle", true);
        assertThat(Teammate.hasWorkingInProcessTeammates(List.of(idle)))
                .as("idle 队友非 working").isFalse();

        // running + 非 idle → working
        registerTask(tfs, "t-work", TaskType.IN_PROCESS_TEAMMATE, BackgroundTaskStatus.RUNNING);
        AutonomousAgentLoop working = runningLoop(tfs, "t-work", false);
        assertThat(Teammate.hasWorkingInProcessTeammates(List.of(working)))
                .as("running 且非 idle 为 working").isTrue();

        // completed（status != running）→ 非 working
        registerTask(tfs, "t-done", TaskType.IN_PROCESS_TEAMMATE, BackgroundTaskStatus.COMPLETED);
        AutonomousAgentLoop completed = runningLoop(tfs, "t-done", false);
        assertThat(Teammate.hasWorkingInProcessTeammates(List.of(completed)))
                .as("completed 非 working").isFalse();
    }

    @Test
    @DisplayName("waitForTeammatesToBecomeIdle: N≥2 等全部空闲才返回（teammate.ts:238-292）")
    void waitForTeammatesToBecomeIdle_waitsForAllWorking() throws Exception {
        // 无 working → 立即完成
        assertThat(Teammate.waitForTeammatesToBecomeIdle(List.of()).isDone()).isTrue();

        TaskFrameworkService tfs = new TaskFrameworkService();
        registerTask(tfs, "t-a", TaskType.IN_PROCESS_TEAMMATE, BackgroundTaskStatus.RUNNING);
        registerTask(tfs, "t-b", TaskType.IN_PROCESS_TEAMMATE, BackgroundTaskStatus.RUNNING);
        AutonomousAgentLoop a = runningLoop(tfs, "t-a", false);
        AutonomousAgentLoop b = runningLoop(tfs, "t-b", false);

        CompletableFuture<Void> future = Teammate.waitForTeammatesToBecomeIdle(List.of(a, b));
        assertThat(future.isDone()).as("2 个 working 时未完成").isFalse();

        // 仅 1 个转 idle → 仍未完成（修 N≥2 提前完成的双触发竞态）
        a.transitionToIdle();
        assertThat(future.isDone()).as("仅 1/2 转 idle 时不得完成").isFalse();

        // 第 2 个转 idle → 完成
        b.transitionToIdle();
        future.get(2, TimeUnit.SECONDS);
        assertThat(future.isDone()).as("全部转 idle 后完成").isTrue();
    }

    @Test
    @DisplayName("addOnIdleCallbackIfNotIdle: 原子注册防双触发（teammate.ts:279-286 either-or）")
    void addOnIdleCallbackIfNotIdle_noDoubleFire() {
        TaskFrameworkService tfs = new TaskFrameworkService();
        registerTask(tfs, "t-1", TaskType.IN_PROCESS_TEAMMATE, BackgroundTaskStatus.RUNNING);
        AutonomousAgentLoop loop = runningLoop(tfs, "t-1", false);

        AtomicInteger fired = new AtomicInteger();
        // 非 idle → 注册成功
        assertThat(loop.addOnIdleCallbackIfNotIdle(fired::incrementAndGet))
                .as("非 idle 时注册成功").isTrue();

        // 转 idle → 触发恰好一次
        loop.transitionToIdle();
        assertThat(fired.get()).as("转 idle 触发恰好一次").isEqualTo(1);

        // 已 idle → 不再注册（返回 false），无残留回调
        assertThat(loop.addOnIdleCallbackIfNotIdle(fired::incrementAndGet))
                .as("已 idle 时不再注册").isFalse();
        assertThat(loop.idleCallbackCount()).as("已 idle 后无残留回调").isZero();
    }
}
