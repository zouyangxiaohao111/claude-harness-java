package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.infra.llm.TaskBudgetParam;
import com.nexusai.application.agent.coordinator.CoordinatorMode;
import com.nexusai.application.agent.subagent.AgentSummaryHandle;
import com.nexusai.application.agent.subagent.AgentSummaryService;
import com.nexusai.application.agent.subagent.ForkSubagent;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [R31-03 返工] maybeStartSummary 分路径门控语义验证（CC 四生产点三套门）。
 *
 * <p><b>WHY（规则九 · 测试验证意图而非行为）</b>: CC 的 summary 触发门不是单一三 flag 或，而是
 * 四生产点三套门（规则三禁止简单实现）:
 * <ul>
 *   <li>{@code ASYNC}（AgentTool.tsx:750）/ {@code RESUME}（resumeAgent.ts:250-253）→
 *       {@code coordinator || fork || sdk}（三 flag 或）</li>
 *   <li>{@code SYNC}（AgentTool.tsx:852）→ {@code summaryTaskId && sdk}（SDK 门 + 前台任务登记守卫）</li>
 *   <li>{@code BACKGROUNDED}（AgentTool.tsx:934）→ {@code sdk}（仅 SDK 门）</li>
 * </ul>
 *
 * <p>修复前（统一三 flag 或）会误开 sync（coordinator/fork 下）与 backgrounded（coordinator/fork 下）
 * 摘要；本测试的红断言（coordinator=true 但 sync summaryTaskId=null / backgrounded sdk=false 仍返回
 * null）在旧统一门下恒绿→变红，锁定分路径语义。
 */
@DisplayName("[R31-03] SubagentExecutor.maybeStartSummary 分路径门控语义")
class SubagentSummaryGatePathTest {

    @TempDir
    Path tmpDir;

    @AfterEach
    void restoreDefaultForkGate() {
        ForkSubagent.syncRuntimeGate(true, false, false);
    }

    private static final class FakeProvider implements LlmProvider {
        @Override public String type() { return "mock"; }
        @Override public String chat(ProviderConfig config, String modelName,
                                     String systemPrompt, String userMessage) {
            return "Reading runAgent.ts";
        }
        @Override public void stream(ProviderConfig config, String modelName,
                                     List<SystemPromptBlock> systemPromptBlocks,
                                     List<ChatMessageDto> history, ArrayNode tools,
                                     Integer maxOutputTokensOverride, TaskBudgetParam taskBudget,
                                     String effortValue, String querySource,
                                     Consumer<String> onChunk,
                                     Consumer<AssistantMessage> onAssistantMessage,
                                     Consumer<ToolUseBlock> onToolCallComplete,
                                     Consumer<String> onReasoningChunk,
                                     Runnable onStreamingFallback,
                                     AbortController abortController,
                                     Consumer<Throwable> onError, Runnable onComplete) {
            if (onChunk != null) onChunk.accept("Reading runAgent.ts");
            if (onComplete != null) onComplete.run();
        }
    }

    /** 启动摘要，返回 handle（可 null）。调用方负责 stop + shutdown。 */
    private AgentSummaryHandle start(SubagentExecutor.SummarySpawnPath path, String summaryTaskId,
                                     boolean coordinatorOn, boolean forkOn, boolean sdkOn,
                                     ScheduledExecutorService scheduler, String agentId) {
        ForkSubagent.syncRuntimeGate(forkOn, false, false);
        AgentSummaryService svc = new AgentSummaryService(60_000, scheduler);
        CoordinatorMode coordinator = coordinatorOn
                ? new CoordinatorMode(() -> true, () -> "true")
                : new CoordinatorMode(() -> false, () -> null);
        return SubagentExecutor.maybeStartSummary(
            path, summaryTaskId,
            svc, coordinator, sdkOn, agentId, tmpDir, "session-1",
            new LlmProviderFactory(), ProviderConfig.empty(), "test-model",
            null, null);
    }

    // ── ASYNC 路径（AgentTool.tsx:750 · 三 flag 或）────────────────────────

    @Test
    @DisplayName("ASYNC 路径: coordinator=true 单开即触发（三 flag 或之一）")
    void asyncPath_coordinatorOnly_triggers() {
        ScheduledExecutorService scheduler = newScheduler();
        AgentSummaryHandle h = start(SubagentExecutor.SummarySpawnPath.ASYNC, null,
            true, false, false, scheduler, "async-coordinator");
        try {
            assertThat(h).isNotNull();
        } finally {
            if (h != null) h.stop();
            scheduler.shutdownNow();
        }
    }

    @Test
    @DisplayName("ASYNC 路径: fork=true 单开即触发（三 flag 或之一）")
    void asyncPath_forkOnly_triggers() {
        ScheduledExecutorService scheduler = newScheduler();
        AgentSummaryHandle h = start(SubagentExecutor.SummarySpawnPath.ASYNC, null,
            false, true, false, scheduler, "async-fork");
        try {
            assertThat(h).isNotNull();
        } finally {
            if (h != null) h.stop();
            scheduler.shutdownNow();
        }
    }

    @Test
    @DisplayName("ASYNC 路径: 三 flag 全关 → 不触发")
    void asyncPath_allOff_notTriggered() {
        ScheduledExecutorService scheduler = newScheduler();
        AgentSummaryHandle h = start(SubagentExecutor.SummarySpawnPath.ASYNC, null,
            false, false, false, scheduler, "async-off");
        try {
            assertThat(h).isNull();
        } finally {
            scheduler.shutdownNow();
        }
    }

    // ── RESUME 路径（resumeAgent.ts:250-253 · 三 flag 或）───────────────────

    @Test
    @DisplayName("RESUME 路径: coordinator=true 单开即触发（三 flag 或，同 async）")
    void resumePath_coordinatorOnly_triggers() {
        ScheduledExecutorService scheduler = newScheduler();
        AgentSummaryHandle h = start(SubagentExecutor.SummarySpawnPath.RESUME, null,
            true, false, false, scheduler, "resume-coordinator");
        try {
            assertThat(h).isNotNull();
        } finally {
            if (h != null) h.stop();
            scheduler.shutdownNow();
        }
    }

    // ── SYNC 路径（AgentTool.tsx:852 · summaryTaskId && sdk）────────────────

    @Test
    @DisplayName("SYNC 路径: summaryTaskId 有值且 sdk=true → 触发")
    void syncPath_summaryTaskIdAndSdk_triggers() {
        ScheduledExecutorService scheduler = newScheduler();
        AgentSummaryHandle h = start(SubagentExecutor.SummarySpawnPath.SYNC, "task-1",
            false, false, true, scheduler, "sync-sdk");
        try {
            assertThat(h).isNotNull();
        } finally {
            if (h != null) h.stop();
            scheduler.shutdownNow();
        }
    }

    @Test
    @DisplayName("SYNC 路径: summaryTaskId=null 即使 sdk=true 也不触发（前台登记守卫）")
    void syncPath_nullSummaryTaskId_notTriggered_evenWithSdk() {
        // RED 断言: 旧统一三 flag 或下 sdk=true 恒触发; 分路径后 summaryTaskId=null → 不触发
        //   （CC AgentTool.tsx:818-833/:843 foregroundTaskId 未登记 → summaryTaskId undefined → 门 false）
        ScheduledExecutorService scheduler = newScheduler();
        AgentSummaryHandle h = start(SubagentExecutor.SummarySpawnPath.SYNC, null,
            false, false, true, scheduler, "sync-no-task");
        try {
            assertThat(h).isNull();
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    @DisplayName("SYNC 路径: coordinator=true 但 summaryTaskId=null → 不触发（coordinator 不进 sync 门）")
    void syncPath_coordinatorIgnored_withoutSummaryTaskId() {
        // RED 断言: 旧统一三 flag 或下 coordinator=true 恒触发; 分路径后 sync 门只看 summaryTaskId && sdk
        //   → coordinator 被忽略, summaryTaskId=null → 不触发（CC sync 门无 coordinator 项）
        ScheduledExecutorService scheduler = newScheduler();
        AgentSummaryHandle h = start(SubagentExecutor.SummarySpawnPath.SYNC, null,
            true, false, false, scheduler, "sync-coordinator");
        try {
            assertThat(h).isNull();
        } finally {
            scheduler.shutdownNow();
        }
    }

    // ── BACKGROUNDED 路径（AgentTool.tsx:934 · 仅 sdk 门）───────────────────

    @Test
    @DisplayName("BACKGROUNDED 路径: sdk=true 单开即触发")
    void backgroundedPath_sdkOnly_triggers() {
        ScheduledExecutorService scheduler = newScheduler();
        AgentSummaryHandle h = start(SubagentExecutor.SummarySpawnPath.BACKGROUNDED, null,
            false, false, true, scheduler, "bg-sdk");
        try {
            assertThat(h).isNotNull();
        } finally {
            if (h != null) h.stop();
            scheduler.shutdownNow();
        }
    }

    @Test
    @DisplayName("BACKGROUNDED 路径: coordinator=true 但 sdk=false → 不触发（coordinator 不进 backgrounded 门）")
    void backgroundedPath_coordinatorIgnored_withoutSdk() {
        // RED 断言: 旧统一三 flag 或下 coordinator=true 恒触发; 分路径后 backgrounded 门仅 sdk
        //   → coordinator 被忽略 → 不触发（CC AgentTool.tsx:934 仅 getSdkAgentProgressSummariesEnabled()）
        ScheduledExecutorService scheduler = newScheduler();
        AgentSummaryHandle h = start(SubagentExecutor.SummarySpawnPath.BACKGROUNDED, null,
            true, false, false, scheduler, "bg-coordinator");
        try {
            assertThat(h).isNull();
        } finally {
            scheduler.shutdownNow();
        }
    }

    private static ScheduledExecutorService newScheduler() {
        return Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "test-summary-gate-path");
            t.setDaemon(true);
            return t;
        });
    }
}
