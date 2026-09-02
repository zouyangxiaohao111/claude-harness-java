package com.nexusai.application.agent.subagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.coordinator.CoordinatorMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.impl.SubagentExecutor;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S5 [差异 3] AgentSummaryService 接通 SubagentExecutor + 4Good4Bad prompt + 实现类.
 *
 * <p>规则九 (测试验证意图而非行为): 意图是 —
 * <ul>
 *   <li>summary 功能必须被生产调用方 (SubagentExecutor) 接通, 否则是死代码 (agentSummary.ts:46
 *       startAgentSummarization + agentToolUtils.ts:543-553 onCacheSafeParams 启动)。</li>
 *   <li>buildSummaryPrompt 必须含 4 Good + 4 Bad 示例 (agentSummary.ts:35-43)，缺示例 → LLM 摘要
 *       可能过长 / 过模糊 / 含分支名。</li>
 *   <li>SummarySummarizer 必须有实现类: readTranscript 调 AgentTranscript.getAgentTranscript
 *       (CC agentSummary.ts:68)，filterIncompleteToolCalls 过滤残缺 tool_use (CC runAgent.ts:866)。</li>
 * </ul>
 */
@DisplayName("[S5] AgentSummaryService 接通 + 4Good4Bad + SummarySummarizerImpl")
class AgentSummaryServiceIntegrationTest {

    @TempDir
    Path tmpDir;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 无外部依赖的 mock provider (chat 返固定摘要). */
    private static final class FakeProvider implements LlmProvider {
        @Override public String type() { return "mock"; }
        @Override public String chat(ProviderConfig config, String modelName,
                                     String systemPrompt, String userMessage) {
            return "Reading runAgent.ts";
        }
        @Override public void stream(ProviderConfig config, String modelName,
                                     List<com.nexusai.application.agent.prompt.SystemPromptBlock> systemPromptBlocks,
                                     List<ChatMessageDto> history, ArrayNode tools,
                                     Integer maxOutputTokensOverride,
                                     com.nexusai.infra.llm.TaskBudgetParam taskBudget,
                                     String effortValue, String querySource,
                                     Consumer<String> onChunk,
                                     Consumer<AssistantMessage> onAssistantMessage,
                                     Consumer<ToolUseBlock> onToolCallComplete,
                                     Consumer<String> onReasoningChunk,
                                     Runnable onStreamingFallback,
                                     com.nexusai.application.agent.tool.AbortController abortController,
                                     Consumer<Throwable> onError, Runnable onComplete) {
            if (onChunk != null) onChunk.accept("Reading runAgent.ts");
            if (onAssistantMessage != null) {
                onAssistantMessage.accept(new AssistantMessage("Reading runAgent.ts", "stop", List.of()));
            }
            if (onComplete != null) onComplete.run();
        }
    }

    private static LlmProviderFactory factory() {
        LlmProviderFactory f = new LlmProviderFactory();
        return f;
    }

    /**
     * 捕获 chatWithOptions 收到的 history (验证 toChatHistory 的角色/条数/顺序透传).
     * 4 参 summarize 经 llmProviderFactory.getProvider(config) 取 provider, 故测试经 FixedFactory 注入.
     */
    private static final class CapturingProvider implements LlmProvider {
        volatile List<ChatMessageDto> lastHistory;
        @Override public String type() { return "mock-capture"; }
        @Override public String chat(ProviderConfig config, String modelName,
                                     String systemPrompt, String userMessage) {
            return "Reading runAgent.ts";
        }
        @Override public void stream(ProviderConfig config, String modelName,
                                     List<com.nexusai.application.agent.prompt.SystemPromptBlock> systemPromptBlocks,
                                     List<ChatMessageDto> history, ArrayNode tools,
                                     Integer maxOutputTokensOverride,
                                     com.nexusai.infra.llm.TaskBudgetParam taskBudget,
                                     String effortValue, String querySource,
                                     Consumer<String> onChunk,
                                     Consumer<AssistantMessage> onAssistantMessage,
                                     Consumer<ToolUseBlock> onToolCallComplete,
                                     Consumer<String> onReasoningChunk,
                                     Runnable onStreamingFallback,
                                     AbortController abortController,
                                     Consumer<Throwable> onError, Runnable onComplete) {
            if (onChunk != null) onChunk.accept("Reading runAgent.ts");
            if (onAssistantMessage != null) {
                onAssistantMessage.accept(new AssistantMessage("Reading runAgent.ts", "stop", List.of()));
            }
            if (onComplete != null) onComplete.run();
        }
        @Override public String chatWithOptions(ProviderConfig config, String modelName,
                                                String systemPrompt, String userMessage,
                                                LlmProvider.ChatRequestOptions options) {
            lastHistory = options != null ? options.history() : List.of();
            return "Reading runAgent.ts";
        }
    }

    /**
     * 进入后阻塞直至 abort 信号; 按 returnAfterAbort 决定 abort 后:
     * false → 抛 {@link CancellationException} (A6: summarize 捕获后返 null);
     * true  → 返回非空摘要 (A8: 即便 summarize 返回值, stopped 复查仍须阻止回调).
     */
    private static final class AbortAwareProvider implements LlmProvider {
        final CountDownLatch entered = new CountDownLatch(1);
        final boolean returnAfterAbort;
        volatile boolean abortObserved;
        volatile boolean threwCancellation;
        AbortAwareProvider(boolean returnAfterAbort) { this.returnAfterAbort = returnAfterAbort; }
        @Override public String type() { return "mock-abort"; }
        @Override public String chat(ProviderConfig config, String modelName,
                                     String systemPrompt, String userMessage) {
            return "Reading runAgent.ts";
        }
        @Override public void stream(ProviderConfig config, String modelName,
                                     List<com.nexusai.application.agent.prompt.SystemPromptBlock> systemPromptBlocks,
                                     List<ChatMessageDto> history, ArrayNode tools,
                                     Integer maxOutputTokensOverride,
                                     com.nexusai.infra.llm.TaskBudgetParam taskBudget,
                                     String effortValue, String querySource,
                                     Consumer<String> onChunk,
                                     Consumer<AssistantMessage> onAssistantMessage,
                                     Consumer<ToolUseBlock> onToolCallComplete,
                                     Consumer<String> onReasoningChunk,
                                     Runnable onStreamingFallback,
                                     AbortController abortController,
                                     Consumer<Throwable> onError, Runnable onComplete) {
            if (onChunk != null) onChunk.accept("Reading runAgent.ts");
            if (onAssistantMessage != null) {
                onAssistantMessage.accept(new AssistantMessage("Reading runAgent.ts", "stop", List.of()));
            }
            if (onComplete != null) onComplete.run();
        }
        @Override public String chatWithOptions(ProviderConfig config, String modelName,
                                                String systemPrompt, String userMessage,
                                                LlmProvider.ChatRequestOptions options) {
            AbortController ac = options != null ? options.abortController() : null;
            entered.countDown();
            // 内部兜底 deadline: stop() 未传播 abort 时 10s 后也抛, 避免测试挂死 (失败会 fail loud)
            long deadline = System.currentTimeMillis() + 10_000;
            while (ac == null || !ac.isCancelled()) {
                if (System.currentTimeMillis() > deadline) {
                    threwCancellation = true;
                    throw new CancellationException("abort 等待超时");
                }
                try {
                    Thread.sleep(5);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("interrupted");
                }
            }
            abortObserved = true;
            if (returnAfterAbort) {
                return "late summary";
            }
            threwCancellation = true;
            throw new CancellationException("aborted by stop()");
        }
    }

    /** 固定返回指定 provider 的工厂 · 绕过 new LlmProviderFactory() 时 @Autowired 字段未装配 (mock 为 null). */
    private static final class FixedFactory extends LlmProviderFactory {
        private final LlmProvider provider;
        FixedFactory(LlmProvider provider) { this.provider = provider; }
        @Override
        public LlmProvider getProvider(ProviderConfig config) {
            return provider;
        }
    }

    @AfterEach
    void restoreDefaultForkGate() {
        // R31-03: maybeStartSummary 门读 ForkSubagent 静态门槽；测试污染门槽后还原默认 {true,false,false}
        ForkSubagent.syncRuntimeGate(true, false, false);
    }

    @Test
    void start_isCalledBySubagentExecutor_whenCoordinatorEnabled() {
        // WHY: summary 功能必须被生产调用方接通 (当前死代码)
        // 不写 = 死代码: summary 永远不生效 (CC agentToolUtils.ts:543-553 startAgentSummarization)
        // maybeStartSummary 是 SubagentExecutor.executeStreaming 用的真实 seam (Step 19/20 之间)
        // [R31-03] 门按 spawn 路径拆分; ASYNC 路径三 flag 或 (coordinator / fork / SDK)，coordinator=true 必触发。
        ForkSubagent.syncRuntimeGate(false, false, false); // 隔离 fork 门，单独验证 coordinator 门
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "test-summary");
            t.setDaemon(true);
            return t;
        });
        AgentSummaryService svc = new AgentSummaryService(60_000, scheduler);
        CoordinatorMode coordinator = new CoordinatorMode(() -> true, () -> "true");

        AgentSummaryHandle handle = SubagentExecutor.maybeStartSummary(
            SubagentExecutor.SummarySpawnPath.ASYNC, null,
            svc, coordinator, false, "agent-1", tmpDir, "session-1", factory(),
            ProviderConfig.empty(), "test-model", null, null);
        try {
            assertThat(handle).isNotNull();
            assertThat(svc.activeAgents()).contains("agent-1");
        } finally {
            if (handle != null) handle.stop();
            scheduler.shutdownNow();
        }
    }

    @Test
    void start_notCalled_whenAllThreeGatesOff() {
        // 反向: 三门全关 (coordinator=false / fork=false / SDK=false) → 不 start
        // [R31-03] 修复前只有 coordinator 门，此测试的 fork 门需显式关闭才能验证"全关"语义。
        ForkSubagent.syncRuntimeGate(false, false, false); // feature 关 → fork 门 false
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "test-summary-off");
            t.setDaemon(true);
            return t;
        });
        AgentSummaryService svc = new AgentSummaryService(60_000, scheduler);
        CoordinatorMode coordinator = new CoordinatorMode(() -> false, () -> null);
        AgentSummaryHandle handle = SubagentExecutor.maybeStartSummary(
            SubagentExecutor.SummarySpawnPath.ASYNC, null,
            svc, coordinator, false, "agent-2", tmpDir, "session-1", factory(),
            ProviderConfig.empty(), "test-model", null, null);
        try {
            assertThat(handle).isNull();
            assertThat(svc.activeAgents()).doesNotContain("agent-2");
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void start_called_whenSdkFlagEnabled_only() {
        // R31-03 RED→GREEN: CC backgrounded 路径门 getSdkAgentProgressSummariesEnabled()
        //   (AgentTool.tsx:934) 单独可触发。修复前 maybeStartSummary 仅 coordinator 门
        //   → coordinator=false 时恒 null (RED)；修复后 SDK 门=true → 启动 (GREEN)。
        ForkSubagent.syncRuntimeGate(false, false, false); // fork 关，隔离 SDK 门
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "test-summary-sdk");
            t.setDaemon(true);
            return t;
        });
        AgentSummaryService svc = new AgentSummaryService(60_000, scheduler);
        CoordinatorMode coordinator = new CoordinatorMode(() -> false, () -> null);
        AgentSummaryHandle handle = SubagentExecutor.maybeStartSummary(
            SubagentExecutor.SummarySpawnPath.BACKGROUNDED, null,
            svc, coordinator, true, "agent-sdk", tmpDir, "session-1", factory(),
            ProviderConfig.empty(), "test-model", null, null);
        try {
            assertThat(handle).isNotNull();
            assertThat(svc.activeAgents()).contains("agent-sdk");
        } finally {
            if (handle != null) handle.stop();
            scheduler.shutdownNow();
        }
    }

    @Test
    void start_called_whenForkSubagentEnabled_only() {
        // R31-03 RED→GREEN: CC async/resume 门 isForkSubagentEnabled()
        //   (AgentTool.tsx:750 / resumeAgent.ts:250-253)。fork gate 默认 {true,false,false} 开启，
        //   coordinator=false / SDK=false 时修复前恒 null (RED)；修复后 fork 门=true → 启动 (GREEN)。
        ForkSubagent.syncRuntimeGate(true, false, false); // fork 开（默认），coordinator 关
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "test-summary-fork");
            t.setDaemon(true);
            return t;
        });
        AgentSummaryService svc = new AgentSummaryService(60_000, scheduler);
        CoordinatorMode coordinator = new CoordinatorMode(() -> false, () -> null);
        AgentSummaryHandle handle = SubagentExecutor.maybeStartSummary(
            SubagentExecutor.SummarySpawnPath.ASYNC, null,
            svc, coordinator, false, "agent-fork", tmpDir, "session-1", factory(),
            ProviderConfig.empty(), "test-model", null, null);
        try {
            assertThat(handle).isNotNull();
            assertThat(svc.activeAgents()).contains("agent-fork");
        } finally {
            if (handle != null) handle.stop();
            scheduler.shutdownNow();
        }
    }

    @Test
    void buildSummaryPrompt_containsAll4GoodAnd4BadExamples() {
        // WHY: LLM 需完整 Good/Bad 示例约束摘要格式 (agentSummary.ts:35-43)
        // 缺 "Adding retry logic to fetchUser" / "Bad (too long)" / "Bad (branch name)"
        // → 摘要可能过长 / 过模糊 / 含分支名
        String prompt = AgentSummaryService.buildSummaryPrompt(null);
        assertThat(prompt).contains("Good: \"Reading runAgent.ts\"");
        assertThat(prompt).contains("Good: \"Fixing null check in validate.ts\"");
        assertThat(prompt).contains("Good: \"Running auth module tests\"");
        assertThat(prompt).contains("Good: \"Adding retry logic to fetchUser\"");
        assertThat(prompt).contains("Bad (past tense): \"Analyzed the branch diff\"");
        assertThat(prompt).contains("Bad (too vague): \"Investigating the issue\"");
        assertThat(prompt).contains("Bad (too long): \"Reviewing full branch diff and AgentTool.tsx integration\"");
        assertThat(prompt).contains("Bad (branch name): \"Analyzed adam/background-summary branch diff\"");
    }

    @Test
    void summarySummarizer_readTranscript_callsAgentTranscriptGetAgentTranscript() throws Exception {
        // 断言: SummarySummarizer 实现类的 readTranscript 调 AgentTranscript.getAgentTranscript
        Path sessionDir = Files.createDirectories(tmpDir.resolve("sessions"));
        writeTranscript(sessionDir, "session-1", "agent-x", List.of(
            msg("user", "first prompt", "agent-x", true, "u1", null),
            msg("assistant", "working...", "agent-x", true, "u2", "u1")));

        SummarySummarizerImpl summarizer = new SummarySummarizerImpl(
            sessionDir, "session-1", factory(), ProviderConfig.empty(), "model");
        List<AgentMessage> transcript = summarizer.readTranscript("agent-x");
        assertThat(transcript).hasSize(2);
        assertThat(transcript.get(0).role()).isEqualTo("user");
        assertThat(transcript.get(1).role()).isEqualTo("assistant");
    }

    @Test
    void summarySummarizer_filterIncompleteToolCalls_filtersUnresolvedToolUse() {
        // 断言: 含 tool_use 无 tool_result 的 assistant 消息被过滤 (CC runAgent.ts:866)
        SummarySummarizerImpl summarizer = new SummarySummarizerImpl(
            tmpDir, "session-1", factory(), ProviderConfig.empty(), "model");
        List<AgentMessage> messages = List.of(
            AgentMessage.of("user", "do something"),
            // assistant 有 tool_use "t1" 但无对应 tool_result → 应被过滤 (CC runAgent.ts:866 ANY-incomplete)
            new AgentMessage("assistant", "calling", false, "a1", true, "m2", "m1",
                List.of(new AgentMessage.ToolCallInfo("t1", "Bash", "{}")), null),
            // tool_result 只解决 "t2", 不解决 "t1" → t1 仍 incomplete
            new AgentMessage("tool", "result for t2", false, "a1", true, "m3", "m2", List.of(), "t2"));

        List<AgentMessage> clean = summarizer.filterIncompleteToolCalls(messages);
        assertThat(clean).extracting(AgentMessage::role)
            .containsExactly("user", "tool");
    }

    @Test
    void summarize_fourArg_preservesAssistantAndSystemRoles() {
        // WHY (BUG-1 回归防护): 旧 Role.valueOf(role.toUpperCase()) 对全小写 Role 枚举恒抛
        //   IllegalArgumentException → catch 后一律 Role.user. assistant 纯文本 + system 是 transcript
        //   最常见形态, 若全落 user → LLM 看到的 history 角色被破坏, 摘要上下文失真 (违背 A7 对齐 CC
        //   forkContextMessages 保留消息角色). 4 参 summarize → toChatHistory 必须保留 Role.assistant /
        //   Role.system (对齐 SubagentExecutor.convertToChatMessageDto:4664 Role.valueOf(role.toLowerCase())).
        CapturingProvider provider = new CapturingProvider();
        SummarySummarizerImpl summarizer = new SummarySummarizerImpl(
            tmpDir, "session-1", new FixedFactory(provider), ProviderConfig.empty(), "model");
        List<AgentMessage> clean = List.of(
            AgentMessage.of("user", "first prompt"),
            new AgentMessage("assistant", "working on runAgent.ts", false, "agent-x", true, "u2", "u1",
                List.of(), null),
            AgentMessage.of("system", "system instruction"));
        String summary = summarizer.summarize("agent-x", "prompt", clean, null);
        assertThat(summary).isEqualTo("Reading runAgent.ts");
        assertThat(provider.lastHistory).extracting(ChatMessageDto::role)
            .containsExactly(Role.user, Role.assistant, Role.system);
    }

    @Test
    void summarize_fourArg_passesCleanContextCountAndOrder() {
        // WHY: clean 上下文条数/顺序必须按序透传 (CC agentSummary.ts:81-84 forkContextMessages 顺序保留)
        //   — LLM 依赖消息顺序理解对话进展; tool_result 配对 (toolCallId) + assistant tool_use 块
        //   (toolCalls) 透传对齐 convertToChatMessageDto 语义. 条数/顺序/配对任一被破坏 → 摘要上下文失真.
        CapturingProvider provider = new CapturingProvider();
        SummarySummarizerImpl summarizer = new SummarySummarizerImpl(
            tmpDir, "session-1", new FixedFactory(provider), ProviderConfig.empty(), "model");
        List<AgentMessage> clean = List.of(
            AgentMessage.of("user", "do X"),
            new AgentMessage("assistant", "calling Bash", false, "agent-x", true, "u2", "u1",
                List.of(new AgentMessage.ToolCallInfo("t1", "Bash", "{}")), null),
            new AgentMessage("tool", "result ok", false, "agent-x", true, "u3", "u2", List.of(), "t1"),
            AgentMessage.of("user", "next step"));
        String summary = summarizer.summarize("agent-x", "prompt", clean, null);
        assertThat(summary).isEqualTo("Reading runAgent.ts");
        // 条数 = 输入 clean 条数 (无丢弃)
        assertThat(provider.lastHistory).hasSize(4);
        // 顺序 = 输入顺序
        assertThat(provider.lastHistory).extracting(ChatMessageDto::role)
            .containsExactly(Role.user, Role.assistant, Role.tool, Role.user);
        assertThat(provider.lastHistory).extracting(ChatMessageDto::content)
            .containsExactly("do X", "calling Bash", "result ok", "next step");
        // assistant 含 toolCalls → tool_use 块透传
        assertThat(provider.lastHistory.get(1).toolCalls()).hasSize(1);
        assertThat(provider.lastHistory.get(1).toolCalls().get(0).name()).isEqualTo("Bash");
        assertThat(provider.lastHistory.get(1).finishReason()).isEqualTo(FinishReason.tool_calls);
        // tool → toolCallId 配对保留
        assertThat(provider.lastHistory.get(2).toolCallId()).isEqualTo("t1");
    }

    @Test
    void stop_abortsInflightSummary_andCallbackNotTriggered() throws Exception {
        // WHY (A6/A8): stop() 必须 abort in-flight LLM 调用 (CC agentSummary.ts:169-171),
        //   summarize 捕获 CancellationException 返 null (A6, CC :145), 且 stopped 复查 (CC :121)
        //   保证停产后回调不再触发 (A8). 无此测试 → 停产后 in-flight 摘要仍可能回调 (T3/T4 回归).
        //   断言: 服务级 start → in-flight → stop() → provider 观察到 abort (summarize 经
        //   CancellationException 返 null) + 回调 0 次.
        Path sessionDir = Files.createDirectories(tmpDir.resolve("sessions2"));
        writeTranscript(sessionDir, "session-1", "agent-abort", List.of(
            msg("user", "p1", "agent-abort", true, "u1", null),
            msg("assistant", "working", "agent-abort", true, "u2", "u1"),
            msg("user", "p2", "agent-abort", true, "u3", "u2")));
        AbortAwareProvider provider = new AbortAwareProvider(false);
        SummarySummarizerImpl summarizer = new SummarySummarizerImpl(
            sessionDir, "session-1", new FixedFactory(provider), ProviderConfig.empty(), "model");
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "summary-abort-test");
            t.setDaemon(true);
            return t;
        });
        AgentSummaryService svc = new AgentSummaryService(10, scheduler);
        AtomicInteger callbackCount = new AtomicInteger();
        AgentSummaryHandle handle = svc.start("task-abort", "agent-abort", summarizer,
            s -> callbackCount.incrementAndGet());
        try {
            // 等 runSummary 进入 provider (in-flight 已建立, entered 在 chatWithOptions 内 countDown)
            assertThat(provider.entered.await(5, TimeUnit.SECONDS)).isTrue();
            handle.stop();
            long deadline = System.currentTimeMillis() + 5000;
            while (!provider.threwCancellation && System.currentTimeMillis() < deadline) {
                Thread.sleep(5);
            }
            assertThat(provider.abortObserved).isTrue();     // A6: stop() abort 传播到 provider
            assertThat(provider.threwCancellation).isTrue(); // A6: summarize 捕获 CancellationException → 返 null
            assertThat(callbackCount).hasValue(0);           // A8: 停产后回调不触发
        } finally {
            handle.stop();
            svc.shutdown();
        }
    }

    @Test
    void stop_preventsCallback_evenWhenSummarizeReturnsAfterAbort() throws Exception {
        // WHY (A8 独立实证): 即便 provider 在 abort 后仍返回非空摘要 (不抛异常), stopped 复查
        //   (CC agentSummary.ts:121) 必须阻止回调更新 — 这是 A8 的独证 (若去掉 stopped 复查, 本测试变红).
        //   用 returnAfterAbort=true 的 provider 隔离 A6 (返 null) 与 A8 (停止回调) 两语义.
        Path sessionDir = Files.createDirectories(tmpDir.resolve("sessions3"));
        writeTranscript(sessionDir, "session-1", "agent-late", List.of(
            msg("user", "p1", "agent-late", true, "u1", null),
            msg("assistant", "working", "agent-late", true, "u2", "u1"),
            msg("user", "p2", "agent-late", true, "u3", "u2")));
        AbortAwareProvider provider = new AbortAwareProvider(true);
        SummarySummarizerImpl summarizer = new SummarySummarizerImpl(
            sessionDir, "session-1", new FixedFactory(provider), ProviderConfig.empty(), "model");
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "summary-late-test");
            t.setDaemon(true);
            return t;
        });
        AgentSummaryService svc = new AgentSummaryService(10, scheduler);
        AtomicInteger callbackCount = new AtomicInteger();
        AgentSummaryHandle handle = svc.start("task-late", "agent-late", summarizer,
            s -> callbackCount.incrementAndGet());
        try {
            assertThat(provider.entered.await(5, TimeUnit.SECONDS)).isTrue();
            handle.stop();
            long deadline = System.currentTimeMillis() + 5000;
            while (!provider.abortObserved && System.currentTimeMillis() < deadline) {
                Thread.sleep(5);
            }
            assertThat(provider.abortObserved).isTrue();
            // 等 summarize 返回 "late summary" → runSummary 走到 stopped 复查 → return (回调已不可能触发)
            Thread.sleep(200);
            assertThat(callbackCount).hasValue(0); // A8: 即便 summarize 返回摘要, stopped 复查阻止回调
        } finally {
            handle.stop();
            svc.shutdown();
        }
    }

    /** 写一条 sidechain transcript (直接构造带 agentId/isSidechain/uuid/parentUuid 的 JSONL). */
    private static void writeTranscript(Path sessionDir, String sessionId, String agentId,
                                        List<AgentMessage> messages) throws Exception {
        Path path = sessionDir.resolve(sessionId).resolve("subagents")
            .resolve("agent-" + agentId + ".jsonl");
        Files.createDirectories(path.getParent());
        StringBuilder sb = new StringBuilder();
        for (AgentMessage m : messages) {
            ObjectNode n = MAPPER.createObjectNode();
            n.put("role", m.role());
            n.put("content", m.content());
            n.put("agentId", m.agentId());
            n.put("isSidechain", m.isSidechain());
            n.put("uuid", m.uuid());
            if (m.parentUuid() != null) n.put("parentUuid", m.parentUuid());
            sb.append(MAPPER.writeValueAsString(n)).append("\n");
        }
        Files.writeString(path, sb.toString());
    }

    private static AgentMessage msg(String role, String content, String agentId,
                                    boolean isSidechain, String uuid, String parentUuid) {
        return new AgentMessage(role, content, false, agentId, isSidechain, uuid, parentUuid,
            List.of(), null);
    }
}
