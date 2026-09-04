package com.nexusai.application.agent.config;

import com.nexusai.application.agent.coordinator.CoordinatorMode;
import com.nexusai.application.agent.prompt.AgentToolSection;
import com.nexusai.application.agent.subagent.AgentSummaryService;
import com.nexusai.application.agent.tasks.SdkEventQueue;
import com.nexusai.application.agent.tool.impl.SubagentExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-SP-SUB] {@link ToolRegistrationConfig#subagentExecutor} bean fallbackSystemPrompt 新源验证。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则九 · 测试验证意图）: 该 bean 的 fallbackSystemPrompt 是
 * {@code SubagentExecutor.buildAgentSystemPrompt(:1824-1843)} 在子代理自身
 * {@code agentDefinition.getSystemPrompt()} 空白时的兜底（fork path 由 forkParentSystemPrompt
 * 覆盖，CC runAgent.ts:508-509）。伪真源删除后该兜底必须等于 {@link AgentToolSection#get()}
 * （CC getAgentToolSection prompts.ts:319 非 fork 变体），否则空白-prompt 子代理会拿到残留旧文本。
 */
@DisplayName("[IMP-SP-SUB] ToolRegistrationConfig.subagentExecutor bean fallbackSystemPrompt 指向 AgentToolSection")
class ToolRegistrationConfigSubagentExecutorBeanTest {

    @Test
    @DisplayName("bean fallbackSystemPrompt == AgentToolSection.get()（CC prompts.ts:319 非 fork 变体）")
    void beanFallbackSystemPrompt_isAgentToolSection() throws Exception {
        // GIVEN: 无 Spring 容器，bean 方法各依赖传 null（构造器 + setter 均 null-safe）
        ToolRegistrationConfig config = new ToolRegistrationConfig();
        // [MCP-I-9 Q-29 R1 + Q-32] bean 已加第 6/7 参 ManagedPolicySettingsSupplier + McpServerService（null-safe）
        // [R3-SUMMARY MS-✗1] 再加第 8/9 参 summaryService + coordinatorMode（null-safe）
        // [RF-2 返工] 再加第 10 参 backgroundTaskRunner（null-safe）
        // [R31-03 返工] 再加第 11 参 sdkAgentProgressSummariesEnabled（null-safe 布尔）
        // [D-3] 再加第 11 参 sdkEventQueue（null-safe，插到布尔前）
        // [冲突裁决·并集] 第 13/14 参 analyticsTracker+agentNameRegistry（HEAD=IMP-G4 hard_metrics 接线）+
        //   第 15 参 yoloClassifier（subagent_v3=IMP-SUB-25 R2 接线归零，handoff 分类）——签名并集 15 参
        // [循环依赖修复] 第 16 参 agentMemoryDirectory（null-safe 参数注入；null → 回落 productionDefault()）
        // [prompt-align UP-01] 第 17 参 promptAlignSettingsResolver（null → setter 回落 coordinatorMode.isCoordinatorMode()）
        // [A5-2] 第 18/19 参 modelMapper+providerMapper（null-safe；null → SubagentExecutor 回落 anthropic 语义）
        SubagentExecutor executor = config.subagentExecutor(null, null, null, null, null, null, null, null, null, null, null, false, null, null, null, null, null, null, null);

        // WHEN: 反射读私有 final 字段 fallbackSystemPrompt
        Field f = SubagentExecutor.class.getDeclaredField("fallbackSystemPrompt");
        f.setAccessible(true);
        String fallback = (String) f.get(executor);

        // THEN: 必须等于 AgentToolSection.get()（仅 agent 自身 prompt 空白时兜底）
        assertThat(fallback)
            .as("bean fallbackSystemPrompt 必须等于 AgentToolSection.get()")
            .isEqualTo(AgentToolSection.get())
            .doesNotContain("Do not delegate further")
            .doesNotContain("coding agent at");
    }

    @Test
    @DisplayName("bean 装配路径注入 summaryService + coordinatorMode（MS-✗1 消除生产死代码）")
    void beanWiresSummaryServiceAndCoordinatorMode() throws Exception {
        // WHY: CC agentToolUtils.ts:543-553 onCacheSafeParams → startAgentSummarization 生产启动。
        //   此前 ToolRegistrationConfig.subagentExecutor @Bean 手动 new 后未调 setSummaryService/
        //   setCoordinatorMode → SubagentExecutor.summaryService/coordinatorMode 恒 null →
        //   maybeStartSummary 恒返回 null → AgentSummaryService.start() 永不触发（生产死代码）。
        ToolRegistrationConfig config = new ToolRegistrationConfig();
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "test-summary-wiring");
            t.setDaemon(true);
            return t;
        });
        AgentSummaryService summaryService = new AgentSummaryService(60_000, scheduler);
        CoordinatorMode coordinatorMode = new CoordinatorMode(() -> true, () -> "true");

        try {
            SubagentExecutor executor = config.subagentExecutor(
                // [冲突裁决·并集] 签名并集 15 参：analyticsTracker+agentNameRegistry（IMP-G4）+ yoloClassifier（IMP-SUB-25）
                // [循环依赖修复] 第 16 参 agentMemoryDirectory（null-safe 参数注入；null → 回落 productionDefault()）
                // [prompt-align UP-01] 第 17 参 promptAlignSettingsResolver（null → setter 回落 coordinatorMode.isCoordinatorMode()）
                // [A5-2] 第 18/19 参 modelMapper+providerMapper（null-safe；null → 回落 anthropic 语义）
                null, null, null, null, null, null, null, summaryService, coordinatorMode, null, null, true, null, null, null, null, null, null, null);

            Field sf = SubagentExecutor.class.getDeclaredField("summaryService");
            sf.setAccessible(true);
            Field cf = SubagentExecutor.class.getDeclaredField("coordinatorMode");
            cf.setAccessible(true);
            Field sdkf = SubagentExecutor.class.getDeclaredField("sdkAgentProgressSummariesEnabled");
            sdkf.setAccessible(true);

            assertThat(sf.get(executor))
                .as("装配路径必须注入 summaryService，否则 maybeStartSummary 恒 null（MS-✗1 死代码）")
                .isSameAs(summaryService);
            assertThat(cf.get(executor))
                .as("装配路径必须注入 coordinatorMode，否则 coordinator gate 恒 null（MS-✗1 死代码）")
                .isSameAs(coordinatorMode);
            assertThat(sdkf.getBoolean(executor))
                .as("装配路径必须注入 sdkAgentProgressSummariesEnabled（R31-03 三 flag 门之一）")
                .isTrue();
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    @DisplayName("bean 装配路径注入 sdkEventQueue（D-3 fork 路径周期摘要不再 null）")
    void beanWiresSdkEventQueue() throws Exception {
        // WHY: fork 路径（ToolRegistrationConfig.subagentExecutor @Bean）周期摘要回调经
        //   AgentProgressTracker.applySummary → SdkEventQueue.emitTaskProgress 发射
        //   task_progress SDK 事件（CC sdkProgress.ts:10-36）。此前 @Bean 未接线
        //   setSdkEventQueue → fork 路径 sdkEventQueue==null → applySummary 只记录摘要
        //   不发射 SDK（摘要面板无进度、前端无 task_progress）。
        ToolRegistrationConfig config = new ToolRegistrationConfig();
        SdkEventQueue sdkEventQueue = new SdkEventQueue();

        SubagentExecutor executor = config.subagentExecutor(
            // [冲突裁决·并集] 签名并集 15 参：analyticsTracker+agentNameRegistry（IMP-G4）+ yoloClassifier（IMP-SUB-25）
            // [循环依赖修复] 第 16 参 agentMemoryDirectory（null-safe 参数注入；null → 回落 productionDefault()）
            // [prompt-align UP-01] 第 17 参 promptAlignSettingsResolver（null → setter 回落 coordinatorMode.isCoordinatorMode()）
            // [A5-2] 第 18/19 参 modelMapper+providerMapper（null-safe；null → 回落 anthropic 语义）
            null, null, null, null, null, null, null, null, null, null, sdkEventQueue, false, null, null, null, null, null, null, null);

        Field sdkq = SubagentExecutor.class.getDeclaredField("sdkEventQueue");
        sdkq.setAccessible(true);

        assertThat(sdkq.get(executor))
            .as("fork 路径装配必须注入 sdkEventQueue，否则周期摘要只记录不发射 task_progress（D-3）")
            .isSameAs(sdkEventQueue);
    }
}
