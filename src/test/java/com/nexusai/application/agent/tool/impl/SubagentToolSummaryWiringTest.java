package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.coordinator.CoordinatorMode;
import com.nexusai.application.agent.subagent.AgentSummaryService;
import com.nexusai.application.agent.subagent.ForkSubagent;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [R31-03 返工] 主 Agent-tool spawn 路径周期摘要装配验证。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则九 · 测试验证意图）: 重验 EV-R31-009 认定
 * {@link SubagentTool} 主 spawn 路径（executeSync / asyncWorker / 降级 sync / resume 共 4 处
 * 手动 {@code new SubagentExecutor}）未调 setSummaryService/setCoordinatorMode → 这些实例
 * summaryService/coordinatorMode 恒 null → {@link SubagentExecutor#maybeStartSummary} 恒 null →
 * AgentSummaryService.start() 主链不可达（CC 三条 spawn 路径均产摘要 · agentToolUtils.ts:543-553
 * startAgentSummarization）。返工后 4 构造点统一调 {@code applySummaryWiring}（对称 applyMcpWiring
 * 模式），本测试验证该装配 seam 真实注入 summaryService + coordinatorMode + sdkAgentProgressSummariesEnabled。
 *
 * <p>4 构造点调用 applySummaryWiring 的实证：{@code SubagentTool.java} grep
 * {@code applySummaryWiring(exec} 命中 4 处（executeSync / asyncWorker / 降级 sync / resume），
 * 与 {@code applyMcpWiring(exec} 4 处一一对称。
 */
@DisplayName("[R31-03] SubagentTool.applySummaryWiring 主 spawn 路径周期摘要装配")
class SubagentToolSummaryWiringTest {

    @TempDir
    Path tmpDir;

    @AfterEach
    void restoreDefaultForkGate() {
        ForkSubagent.syncRuntimeGate(true, false, false);
    }

    @Test
    @DisplayName("applySummaryWiring 注入 summaryService + coordinatorMode + SDK 门 + spawn 路径到 SubagentExecutor")
    void applySummaryWiring_injectsAllThreeGates() throws Exception {
        // WHY: 主 Agent-tool spawn 路径 (4 构造点) 经 applySummaryWiring 注入三 flag 门 + spawn 路径，
        //   否则 maybeStartSummary 恒 null → AgentSummaryService.start() 主链不可达 (EV-R31-009)。
        //   4 个装配值经反射断言注入到 executor 实例字段（Pattern #14 seam）。
        SubagentTool tool = new SubagentTool(
            List.of(), null, new LlmProviderFactory(), ProviderConfig.empty(),
            "gpt-4", "", null, tmpDir, List.of());

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "test-summary-wiring");
            t.setDaemon(true);
            return t;
        });
        AgentSummaryService summaryService = new AgentSummaryService(60_000, scheduler);
        CoordinatorMode coordinatorMode = new CoordinatorMode(() -> true, () -> "true");

        try {
            // WHEN: 装配方注入三源 + spawn 路径，再经 applySummaryWiring 透传 executor
            tool.setSummaryService(summaryService);
            tool.setCoordinatorModeBean(coordinatorMode);
            tool.setSdkAgentProgressSummariesEnabled(true);

            SubagentExecutor executor = new SubagentExecutor(
                null, null, null, null, null, "gpt-4", "");
            tool.applySummaryWiring(executor, SubagentExecutor.SummarySpawnPath.ASYNC);

            // THEN: executor 四字段均已注入（非 null / 真值）
            Field sf = SubagentExecutor.class.getDeclaredField("summaryService");
            sf.setAccessible(true);
            Field cf = SubagentExecutor.class.getDeclaredField("coordinatorMode");
            cf.setAccessible(true);
            Field sdkf = SubagentExecutor.class.getDeclaredField("sdkAgentProgressSummariesEnabled");
            sdkf.setAccessible(true);
            Field pathf = SubagentExecutor.class.getDeclaredField("summarySpawnPath");
            pathf.setAccessible(true);

            assertThat(sf.get(executor))
                .as("主 spawn 路径必须注入 summaryService，否则 maybeStartSummary 恒 null（EV-R31-009）")
                .isSameAs(summaryService);
            assertThat(cf.get(executor))
                .as("主 spawn 路径必须注入 coordinatorMode，否则 coordinator 门恒 null（EV-R31-009）")
                .isSameAs(coordinatorMode);
            assertThat(sdkf.getBoolean(executor))
                .as("主 spawn 路径必须注入 sdkAgentProgressSummariesEnabled（CC 三 flag 门之一）")
                .isTrue();
            assertThat(pathf.get(executor))
                .as("主 spawn 路径必须注入 summarySpawnPath（CC 四生产点三套门分路径语义）")
                .isEqualTo(SubagentExecutor.SummarySpawnPath.ASYNC);
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    @DisplayName("applySummaryWiring 未注入时 executor 三字段保持 null/false（装配缺源短路）")
    void applySummaryWiring_withoutSources_staysNull() throws Exception {
        // WHY: 测试/手动直构 SubagentTool 未注入 summary 三源 → applySummaryWiring 应透传
        //   null/false 而非抛错；executor.maybeStartSummary null-check 短路（不启动，不失败）。
        SubagentTool tool = new SubagentTool(
            List.of(), null, new LlmProviderFactory(), ProviderConfig.empty(),
            "gpt-4", "", null, tmpDir, List.of());

        SubagentExecutor executor = new SubagentExecutor(
            null, null, null, null, null, "gpt-4", "");
        tool.applySummaryWiring(executor, SubagentExecutor.SummarySpawnPath.SYNC);

        Field sf = SubagentExecutor.class.getDeclaredField("summaryService");
        sf.setAccessible(true);
        Field cf = SubagentExecutor.class.getDeclaredField("coordinatorMode");
        cf.setAccessible(true);
        Field sdkf = SubagentExecutor.class.getDeclaredField("sdkAgentProgressSummariesEnabled");
        sdkf.setAccessible(true);

        assertThat(sf.get(executor)).isNull();
        assertThat(cf.get(executor)).isNull();
        assertThat(sdkf.getBoolean(executor)).isFalse();
    }
}
