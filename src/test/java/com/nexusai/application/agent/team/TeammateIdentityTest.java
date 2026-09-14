package com.nexusai.application.agent.team;

import com.nexusai.application.agent.subagent.AutonomousAgentLoop;
import com.nexusai.application.agent.tasks.BackgroundTask;
import com.nexusai.application.agent.tasks.BackgroundTaskStatus;
import com.nexusai.application.agent.tasks.TaskFrameworkService;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.tasks.TaskType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T-A · teammate 身份解析 14 函数 + TeammateMode 枚举 · 对齐 CC utils/teammate.ts。
 *
 * <p><b>WHY（规则九）</b>：
 * <ul>
 *   <li><b>身份解析唯一来源 = 显式 identity</b>（[S1-T13]）——in-process teammate 的身份是
 *       <b>显式传参的纯数据</b>（[S1-T5] 原 ThreadLocal 载体已删）；CC 的 {@code dynamicTeamContext}
 *       （teammate.ts:44-51，tmux CLI 参数载体）在本仓<b>整体删除</b>：CLI 的隐含前提是
 *       「一 teammate 一进程」= 进程级槽等价 teammate 作用域，而本仓是单 JVM 多会话 Web 后端，
 *       保留它会让 A 会话身份被 B 会话读到。⇒「null 入参 ⇒ 全 null」必须成立（零进程级回落）。</li>
 *   <li><b>isTeammate 判据单点</b>（teammate.ts:125-131）——本仓只剩 in-process 分支语义
 *       （{@code if (inProcessCtx) return true}）；CC tmux 分支的「agentId 与 teamName 必须同时非空」
 *       随槽删除，不再适用。</li>
 *   <li><b>isTeamLead 向后兼容分支</b>（teammate.ts:193-195）——主会话无 agentId（原始创建 team 的
 *       session）必须判为 lead，否则 team lead 无法批准 plan。</li>
 *   <li><b>TeammateContextBootstrap 改为启动拒绝</b>（[S1-T13]）——部署侧 sysprop 已无运行期读者，
 *       Web 部署下出现即 fail-fast（把「部署约定」变成「启动期硬保证」）。</li>
 *   <li><b>TeammateMode 三值</b>（teammateModeSnapshot.ts:13）——原 Java {@code AUTO/PLAN/CHAT} 与
 *       CC {@code auto/tmux/in-process} 错位，错位会导致 CLI override 解析失败、模式快照错误。</li>
 * </ul>
 */
@DisplayName("T-A · teammate 身份解析 + TeammateMode 枚举（对齐 utils/teammate.ts）")
class TeammateIdentityTest {

    /**
     * [S1-T5] in-process 身份 = {@link TeammateIdentity} 纯数据（原 ThreadLocal 载体类已删）。
     * 作为「显式首参」注入各 helper —— 与生产 {@code ToolUseContext.teammateIdentity()} 同型。
     */
    private static TeammateIdentity inProcess(String agentId, String agentName, String teamName,
                                              boolean planModeRequired) {
        return new TeammateIdentity(agentId, agentName, teamName, "#ff0000", planModeRequired,
                "leader-session-1");
    }

    @BeforeEach
    void setUp() {
        TeammateModeSnapshot.resetForTest();
        TaskSystemConfig.clearForTest();
    }

    @AfterEach
    void tearDown() {
        TeammateModeSnapshot.resetForTest();
        TaskSystemConfig.clearForTest();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 身份解析：唯一来源 = 显式 identity（[S1-T13] 进程级 dynamicTeamContext 槽已删）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("getAgentId/getAgentName/getTeamName/getTeammateColor: 唯一来源 = 显式 identity，identity=null ⇒ 全 null")
    void explicitIdentity_isSoleSource() {
        // WHY: [S1-T13] CC 的 dynamicTeamContext（teammate.ts:44-51）只由 CLI 参数填充，而 CLI 的
        //   隐含前提是「一个 teammate 一个进程」（tmux）= 进程级槽等价于 teammate 作用域。本仓是
        //   单 JVM 多会话 Web 后端 ⇒ 保留该槽会让 A 会话身份被 B 会话读到。故槽整体删除，
        //   身份唯一来源 = 显式形参。本用例锁「null 入参 ⇒ 全 null」，即**不存在**任何进程级回落。
        TeammateIdentity identity = inProcess("proc@team", "proc-name", "proc-team", true);

        assertThat(Teammate.getAgentId(identity)).isEqualTo("proc@team");
        assertThat(Teammate.getAgentName(identity)).isEqualTo("proc-name");
        assertThat(Teammate.getTeamName(identity)).isEqualTo("proc-team");
        assertThat(Teammate.getTeammateColor(identity)).isEqualTo("#ff0000");
        assertThat(identity.parentSessionId())
                .as("显式 identity 自带 parentSessionId（原 getParentSessionId 已删：生产 0 调用方）")
                .isEqualTo("leader-session-1");

        // ⭐ 鉴别力：identity=null 时**每一个** helper 都必须返回 null（若实现里重新长出任何
        //   进程级身份槽，本方向即红）
        assertThat(Teammate.getAgentId(null)).isNull();
        assertThat(Teammate.getAgentName(null)).isNull();
        assertThat(Teammate.getTeamName(null)).isNull();
        assertThat(Teammate.getTeammateColor(null)).isNull();
    }

    @Test
    @DisplayName("getAgentName/getTeamName/getTeammateColor: 无 sysprop 尾回退（teammate.ts:98-142）")
    void noSyspropFallback_whenNoIdentity() {
        // WHY: CC teammate.ts 名称/颜色解析不落 sysprop。本仓的部署侧 sysprop
        //      （nexusai.agent.name/team.name/color）[S1-T13] 已无任何运行期读者，
        //      只由 TeammateContextBootstrap 在启动期校验。若此处仍回退 sysprop，则「无身份」
        //      会被静默当成「有身份」，制造跨会话串扰。
        System.setProperty("nexusai.agent.name", "cli-agent");
        System.setProperty("nexusai.team.name", "cli-team");
        System.setProperty("nexusai.agent.color", "#00ff00");

        assertThat(Teammate.getAgentName(null)).as("仅设 sysprop 无 identity → agentName null").isNull();
        assertThat(Teammate.getTeamName(null)).as("仅设 sysprop 无 identity → teamName null").isNull();
        assertThat(Teammate.getTeammateColor(null)).as("仅设 sysprop 无 identity → color null").isNull();
        // getTeamName(identity, teamContext)：teamContext 参数空时，同样不落 sysprop（对齐 CC teammate.ts:117 返回 undefined）
        assertThat(Teammate.getTeamName(null, ""))
                .as("空 teamContext 且无 identity → null（不落 sysprop）").isNull();
    }

    @Test
    @DisplayName("getTeamName(teamContext): 显式 identity 优先，其次 teamContext.teamName（leader 无身份时，teammate.ts:111-118）")
    void getTeamName_teamContextParamPriority() {
        // WHY: leader 无 identity 时经 AppState teamContext 传入 teamName（CC :104-107 注释语义）。
        assertThat(Teammate.getTeamName(null, "from-appstate")).isEqualTo("from-appstate");

        // 显式 identity 优先于 teamContext 参数
        assertThat(Teammate.getTeamName(
                new TeammateIdentity("ip@t", "ip", "ip-team", null, false, null), "from-appstate"))
                .as("显式 identity.teamName 优先于 teamContext 参数").isEqualTo("ip-team");
    }

    // ════════════════════════════════════════════════════════════════════════
    // isTeammate
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("isTeammate: 判据单点 = 显式 identity != null（teammate.ts:125-131）")
    void isTeammate_singleCriterion() {
        TeammateIdentity identity = inProcess("p@t", "p", "t", false);
        assertThat(Teammate.isTeammate(identity)).as("显式 identity 恒 true").isTrue();
        // 反方向：identity 为 null → false（证明判据是被判别的东西驱动，而非恒真）
        assertThat(Teammate.isTeammate(null)).as("无 identity → false").isFalse();

        // ⭐ 补充鉴别力：agentId/teamName 字段为 null 的 identity 仍是「有身份」（非 null 载体），
        //   与 CC teammate.ts:126-127 in-process 分支 `if (inProcessCtx) return true` 同语义
        //   （CC 不检查字段非空，字段非空要求只存在于 tmux 分支）。
        assertThat(Teammate.isTeammate(new TeammateIdentity(null, null, null, null, false, null)))
                .as("载体存在即 teammate（对齐 CC in-process 分支）").isTrue();
    }

    @Test
    @DisplayName("isTeammate: 身份判断不再逐次查 sysprop（teammate.ts:125-131 无 env 回退）")
    void isTeammate_noSyspropFallback() {
        // WHY: CC isTeammate 只查 in-process + dynamicTeamContext，无 env/sysprop 逐次回退。
        //      [S1-T13] 后本仓连 dynamicTeamContext 也没有 ⇒ 仅 identity 一个判据；
        //      若此处仍回退 sysprop，则「启动接线前」身份误判为 teammate。
        System.setProperty("nexusai.agent.name", "cli-agent");
        System.setProperty("nexusai.team.name", "cli-team");

        assertThat(Teammate.isTeammate(null))
                .as("仅设 sysprop 但无显式 identity → 非 teammate").isFalse();
    }

    // ════════════════════════════════════════════════════════════════════════
    // TeammateContextBootstrap · [S1-T13] 由「启动接线」改为「启动拒绝」
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[S1-T13] TeammateContextBootstrap: Web 部署 + teammate 身份 sysprop ⇒ 启动即抛（fail-fast）")
    void teammateContextBootstrap_webDeploymentWithSysprop_failsFast() {
        // WHY: 进程级 dynamicTeamContext 槽已删 ⇒ 这两个 sysprop 失去全部运行期读者；若仍在 Web
        //   部署（单 JVM 多会话）下被设置，说明部署侧仍在试图注入**进程级 teammate 身份**，
        //   该意图在本仓形态下必然是跨会话缺陷 ⇒ 必须启动即拒，而不是静默忽略（本仓铁律：fail loud）。
        System.setProperty("nexusai.agent.name", "cli-agent");
        System.setProperty("nexusai.team.name", "cli-team");

        assertThatThrownBy(() -> new TeammateContextBootstrap().validateIdentitySysprops(true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fail-fast")
                .hasMessageContaining("nexusai.agent.name");
    }

    @Test
    @DisplayName("[S1-T13] TeammateContextBootstrap: 非 Web 装配 + sysprop ⇒ 不拒绝（测试/单机路径）")
    void teammateContextBootstrap_nonWebDeploymentWithSysprop_doesNotFail() {
        // WHY: 非 Web 装配（plain JUnit / 直接 new）下这两个 sysprop 是被测输入而非部署配置
        //   ⇒ 不拒绝启动，但必须留下 ≥WARN 披露「该身份已无任何运行期读者」。
        System.setProperty("nexusai.agent.name", "cli-agent");
        System.setProperty("nexusai.team.name", "cli-team");

        assertThatCode(() -> new TeammateContextBootstrap().validateIdentitySysprops(false))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("TeammateContextBootstrap: 无 sysprop 时 no-op（standalone 会话）")
    void teammateContextBootstrap_noSysprop_skips() {
        // WHY: 无 agent/team name sysprop → standalone 会话，无任何进程级身份槽（对齐 CC 无 CLI 参数注入）。
        assertThatCode(() -> new TeammateContextBootstrap().init()).doesNotThrowAnyException();
        assertThatCode(() -> new TeammateContextBootstrap().validateIdentitySysprops(true))
                .doesNotThrowAnyException();
    }

    // ════════════════════════════════════════════════════════════════════════
    // isPlanModeRequired
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("isPlanModeRequired: 显式 identity 驱动（true/false 双向，teammate.ts:149-156）")
    void isPlanModeRequired_explicitIdentityDriven() {
        // ⭐ 正反双向：证明判据由显式 identity 驱动，而非硬编码
        assertThat(Teammate.isPlanModeRequired(inProcess("p@t", "p", "t", true)))
                .as("identity.planModeRequired=true").isTrue();
        assertThat(Teammate.isPlanModeRequired(inProcess("p@t", "p", "t", false)))
                .as("identity.planModeRequired=false").isFalse();
    }

    @Test
    @DisplayName("isPlanModeRequired: identity 为 null 时只剩 env 回退（CC :155 isEnvTruthy）")
    void isPlanModeRequired_envFallbackWhenNoIdentity() {
        // 无 identity 且 env 未设 ⇒ false（不回落任何进程级身份槽）
        assertThat(Teammate.isPlanModeRequired(null))
                .as("无 identity + 无 env → false").isFalse();
    }

    // ════════════════════════════════════════════════════════════════════════
    // isTeamLead truth table
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("isTeamLead: 4 分支 truth table（teammate.ts:178-197）")
    void isTeamLead_truthTable() {
        // 1) leadAgentId null → false（:178-180）
        assertThat(Teammate.isTeamLead(null, null)).as("无 lead 恒非 lead").isFalse();
        assertThat(Teammate.isTeamLead(inProcess("lead@team", "lead", "team", false), null))
                .as("无 lead 恒非 lead（即便 identity 是 lead）").isFalse();

        // 2) myAgentId == leadAgentId → true（:187-189）
        TeammateIdentity lead = inProcess("lead@team", "lead", "team", false);
        assertThat(Teammate.isTeamLead(lead, "lead@team"))
                .as("agentId 等于 leadAgentId 为 lead").isTrue();

        // 3) myAgentId null → true（向后兼容，:193-195）
        assertThat(Teammate.isTeamLead(null, "lead@team"))
                .as("主会话无 agentId 为 lead").isTrue();

        // 4) myAgentId != leadAgentId → false（:197）
        TeammateIdentity other = inProcess("mate@team", "mate", "team", false);
        assertThat(Teammate.isTeamLead(other, "lead@team"))
                .as("agentId != leadAgentId 非 lead").isFalse();

        // 4b) identity 载体存在但 agentId 字段为 null → 等同主会话（CC myAgentId undefined）
        assertThat(Teammate.isTeamLead(new TeammateIdentity(null, "x", "t", null, false, null), "lead@team"))
                .as("agentId 字段 null → 无 agent id → lead（CC :193-195）").isTrue();
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
