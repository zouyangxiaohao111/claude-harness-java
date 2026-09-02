package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.coordinator.CoordinatorMode;
import com.nexusai.application.agent.subagent.AgentSummaryHandle;
import com.nexusai.application.agent.subagent.AgentSummaryService;
import com.nexusai.application.agent.subagent.ForkSubagent;
import com.nexusai.application.agent.tasks.BackgroundTask;
import com.nexusai.application.agent.tasks.BackgroundTaskRunner;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tasks.TaskFrameworkService;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * [RF-2 返工] sync 前台任务登记生产接线链路验证（覆盖生产接线，而非仅静态 seam）。
 *
 * <p><b>WHY（规则九 · 测试验证意图而非行为）</b>：RF-2 独立反思（REWORK_REQUIRED）P0-② 认定
 * {@code SubagentExecutor.backgroundTaskRunner} 全仓 0 注入点 → Step 19.7 前台登记守卫
 * （{@code SYNC && backgroundTaskRunner != null}）恒 false → {@code summaryTaskId} 恒 null →
 * SYNC 摘要门 {@code summaryTaskId && sdk} 恒 false，目标 ②「sync 摘要门控生产生效」是假接线。
 * 旧测试 {@link SubagentSummaryGatePathTest} 直接把 {@code summaryTaskId="task-1"} 当静态实参传入
 * {@code maybeStartSummary}，{@link com.nexusai.application.agent.tasks.BackgroundTaskRunnerForegroundBackgroundTest}
 * 直接测 runner 方法——二者隔离测通过 ≠ 生产接线打通（CLAUDE.md 前科模式「谎报接通 N 个 bean 实际只接通 1 个」）。
 *
 * <p>本测试锁定生产全链三环：
 * <ul>
 *   <li><b>Link A（wiring）</b>：{@link SubagentTool#applySummaryWiring} 把 SubagentTool 注入的
 *       {@code backgroundTaskRunner} 透传到 SubagentExecutor（反射断言字段同实例）</li>
 *   <li><b>Link B（registration）</b>：{@link SubagentExecutor#registerSyncForeground} 真的调
 *       {@code registerAgentForeground}（任务落入 TaskFrameworkService 统一 store）并写回
 *       {@code summaryTaskId}</li>
 *   <li><b>Link C（gate）</b>：写回的非 null {@code summaryTaskId} 使
 *       {@code maybeStartSummary(SYNC, summaryTaskId, ..., sdk=true)} 返回非 null handle</li>
 * </ul>
 */
@DisplayName("[RF-2 返工] SubagentExecutor sync 前台登记生产接线链路")
class SubagentSyncForegroundRegistrationTest {

    @TempDir
    Path tmpDir;

    @AfterEach
    void restoreDefaultForkGate() {
        ForkSubagent.syncRuntimeGate(true, false, false);
    }

    private static String reflectSummaryTaskId(SubagentExecutor executor) throws Exception {
        Field f = SubagentExecutor.class.getDeclaredField("summaryTaskId");
        f.setAccessible(true);
        return (String) f.get(executor);
    }

    private static Object reflectBackgroundTaskRunner(SubagentExecutor executor) throws Exception {
        Field f = SubagentExecutor.class.getDeclaredField("backgroundTaskRunner");
        f.setAccessible(true);
        return f.get(executor);
    }

    private static ScheduledExecutorService newScheduler() {
        return Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "test-sync-foreground");
            t.setDaemon(true);
            return t;
        });
    }

    @Test
    @DisplayName("生产链路: wiring 注入 backgroundTaskRunner → registerSyncForeground 写回 summaryTaskId → maybeStartSummary 非 null")
    void syncForegroundRegistration_fullChain_flowsToSummary() throws Exception {
        // ── Link A: SubagentTool.applySummaryWiring 注入 backgroundTaskRunner ──
        SubagentTool tool = new SubagentTool(
            List.of(), null, new LlmProviderFactory(), ProviderConfig.empty(),
            "gpt-4", "", null, tmpDir, List.of());
        TaskFrameworkService framework = new TaskFrameworkService(null);
        BackgroundTaskRunner runner = new BackgroundTaskRunner(mock(NotificationQueue.class), framework);
        tool.setBackgroundTaskRunner(runner);

        SubagentExecutor executor = new SubagentExecutor(
            null, null, null, null, null, "gpt-4", "");
        tool.applySummaryWiring(executor, SubagentExecutor.SummarySpawnPath.SYNC);

        assertThat(reflectBackgroundTaskRunner(executor))
            .as("applySummaryWiring 必须注入 backgroundTaskRunner，否则 Step 19.7 守卫恒 false（RF-2 P0-②）")
            .isSameAs(runner);

        // ── Link B: registerSyncForeground 调 registerAgentForeground + 写回 summaryTaskId ──
        UUID agentId = UUID.randomUUID();
        BackgroundTask registered = executor.registerSyncForeground(agentId, "研究项目结构", "prompt", "general-purpose", null);

        assertThat(registered).isNotNull();
        assertThat(registered.id()).isEqualTo(agentId.toString());
        assertThat(framework.getTask(agentId.toString()))
            .as("前台任务必须落入统一 store（CC registerTask → state.tasks）")
            .isPresent();
        assertThat(reflectSummaryTaskId(executor))
            .as("registerSyncForeground 必须把 registered.id() 写回 summaryTaskId（CC AgentTool.tsx:843）")
            .isEqualTo(agentId.toString());

        // ── Link C: 非 null summaryTaskId + sdk=true → maybeStartSummary SYNC 门命中 ──
        ScheduledExecutorService scheduler = newScheduler();
        AgentSummaryService svc = new AgentSummaryService(60_000, scheduler);
        CoordinatorMode coordinator = new CoordinatorMode(() -> true, () -> "true");
        AgentSummaryHandle handle = SubagentExecutor.maybeStartSummary(
            SubagentExecutor.SummarySpawnPath.SYNC, reflectSummaryTaskId(executor),
            svc, coordinator, true,
            agentId.toString(), tmpDir, "session-1",
            new LlmProviderFactory(), ProviderConfig.empty(), "test-model",
            null, null);
        try {
            assertThat(handle)
                .as("sync 摘要门必须在 summaryTaskId 非 null 且 sdk=true 时命中（生产接线打通后 summaryTaskId 不再恒 null）")
                .isNotNull();
        } finally {
            if (handle != null) handle.stop();
            scheduler.shutdownNow();
        }
    }

    @Test
    @DisplayName("生产链路控制: 未注入 backgroundTaskRunner → registerSyncForeground 返 null → summaryTaskId 恒 null（RED 语义）")
    void syncForegroundRegistration_withoutRunner_staysNull() throws Exception {
        // WHY: 锁定 P0-② 假接线根因——runner 未注入时守卫短路，summaryTaskId 恒 null，sync 摘要被抑制。
        //   此断言在 RF-2 返工前（applySummaryWiring 不注入 runner）恒绿，返工后 wiring 注入 runner 仍绿；
        //   它与正向测试成对，证明「非 null summaryTaskId」确实来自 runner 注入而非其它来源。
        SubagentTool tool = new SubagentTool(
            List.of(), null, new LlmProviderFactory(), ProviderConfig.empty(),
            "gpt-4", "", null, tmpDir, List.of());
        // 不注入 backgroundTaskRunner（模拟测试/手动直构缺源）
        SubagentExecutor executor = new SubagentExecutor(
            null, null, null, null, null, "gpt-4", "");
        tool.applySummaryWiring(executor, SubagentExecutor.SummarySpawnPath.SYNC);

        BackgroundTask registered = executor.registerSyncForeground(
            UUID.randomUUID(), "desc", "prompt", "general-purpose", null);

        assertThat(registered).isNull();
        assertThat(reflectSummaryTaskId(executor)).isNull();
    }

    @Test
    @DisplayName("生产链路控制: 非 SYNC spawn 路径 → registerSyncForeground 短路（仅 sync 前台登记）")
    void syncForegroundRegistration_asyncPath_shortCircuits() throws Exception {
        // WHY: CC 仅 sync 路径 registerAgentForeground（AgentTool.tsx:818-833 在 sync 分支）；
        //   async/resume/backgrounded 不登记前台任务。锁定 spawn 路径守卫。
        SubagentTool tool = new SubagentTool(
            List.of(), null, new LlmProviderFactory(), ProviderConfig.empty(),
            "gpt-4", "", null, tmpDir, List.of());
        TaskFrameworkService framework = new TaskFrameworkService(null);
        BackgroundTaskRunner runner = new BackgroundTaskRunner(mock(NotificationQueue.class), framework);
        tool.setBackgroundTaskRunner(runner);

        SubagentExecutor executor = new SubagentExecutor(
            null, null, null, null, null, "gpt-4", "");
        tool.applySummaryWiring(executor, SubagentExecutor.SummarySpawnPath.ASYNC);

        BackgroundTask registered = executor.registerSyncForeground(
            UUID.randomUUID(), "desc", "prompt", "general-purpose", null);

        assertThat(registered).isNull();
        assertThat(reflectSummaryTaskId(executor)).isNull();
    }
}
