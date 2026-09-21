package com.nexusai.application.agent.subagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [team-hang] 周期摘要**只能随真实活动前进**（A9）· 防「面板显示在干活、实际零动作」。
 *
 * <p><b>规则九（验证意图，而非仅验证行为）</b>：意图是——
 * 摘要契约（CC agentSummary.ts:33）是「Describe your most recent action」，
 * 即摘要**描述**动作、不**产生**动作。若 agent 的 transcript 自上次摘要有增无变，
 * 就没有 most recent action 可描述；此时再 fork 一次 LLM，模型在
 * {@code Previous: "…" — say something NEW} 的提示下只能**编**一句新的。
 *
 * <p><b>实机证据（2026-09-20 e2e，teamtest-0920/0920b）</b>：teammate {@code worker-d} 卡在
 * Bash 权限等待（jstack 证 tool-exec 线程停在 WebSocketPermissionPrompter.prompt 的
 * {@code future.get()}），transcript 自 15:45:08 起冻结，而摘要仍每 ~32 秒换一句：
 * 15:46:11 "Implementing matmul in matrix.py" / 15:47:15 "Writing test_matrix.py" /
 * 15:47:47 "Running test_matrix.py suite" …… 直到 16:03 仍在产出。那些动作**从未发生**，
 * 产物零落地。⇒ 摘要当时不是事实信号，是个谎报器。
 *
 * <p><b>反向实验（本测试的防护对象）</b>：把 {@link AgentSummaryService} 里的
 * transcript 指纹守卫去掉 ⇒ 每个 tick 都会 fork provider ⇒ 本类第 1 个测试
 * （{@code steadyTranscript_...}）的 {@code providerCalls} / {@code callbackCount} 随
 * tick 增长，断言立即变红。
 */
@DisplayName("[team-hang] AgentSummaryService 摘要仅随 transcript 前进（A9 · 防编造进度）")
class AgentSummaryActivityProvenanceTest {

    @TempDir
    Path tmpDir;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 每次调用返回**不同**摘要的 provider（计数器驱动）—— 精确模拟「模型在 say something NEW
     * 提示下逐轮编新句」的真实行为。若守卫失效，调用次数会随 tick 线性增长。
     */
    private static final class CountingNovelSummaryProvider implements LlmProvider {
        final AtomicInteger calls = new AtomicInteger();
        @Override public String type() { return "mock-novel"; }
        @Override public String chat(ProviderConfig config, String modelName,
                                     String systemPrompt, String userMessage) {
            return novel();
        }
        private String novel() {
            return "Invented step #" + calls.incrementAndGet();
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
                                     Consumer<Throwable> onError, Runnable onComplete, Boolean skipCacheWrite,
                                     com.nexusai.application.agent.subagent.AgentContext agentContext) {
            if (onChunk != null) onChunk.accept(novel());
            if (onComplete != null) onComplete.run();
        }
        @Override public String chatWithOptions(ProviderConfig config, String modelName,
                                                String systemPrompt, String userMessage,
                                                LlmProvider.ChatRequestOptions options) {
            return novel();
        }
    }

    /** 固定返回指定 provider 的工厂（绕过 new LlmProviderFactory() 时 @Autowired 字段未装配）。 */
    private static final class FixedFactory extends LlmProviderFactory {
        private final LlmProvider provider;
        FixedFactory(LlmProvider provider) { this.provider = provider; }
        @Override public LlmProvider getProvider(ProviderConfig config) { return provider; }
    }

    @Test
    @DisplayName("转录不前进 ⇒ 不重复 fork、不刷新 callback（首轮 1 次，之后 0 次）")
    void steadyTranscript_doesNotEmitRepeatedSummaries() throws Exception {
        Path sessionDir = Files.createDirectories(tmpDir.resolve("sessions-steady"));
        String agentId = "agent-steady";
        writeTranscript(sessionDir, "session-1", agentId, List.of(
            msg("user", "p1", agentId, "u1", null, "2026-09-20T15:45:08Z"),
            msg("assistant", "working on task 4", agentId, "u2", "u1", "2026-09-20T15:45:09Z"),
            msg("assistant", "calling Bash", agentId, "u3", "u2", "2026-09-20T15:45:10Z")));

        CountingNovelSummaryProvider provider = new CountingNovelSummaryProvider();
        SummarySummarizerImpl summarizer = new SummarySummarizerImpl(
            sessionDir, "session-1", new FixedFactory(provider), ProviderConfig.empty(), "model");
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "summary-steady-test");
            t.setDaemon(true);
            return t;
        });
        AgentSummaryService svc = new AgentSummaryService(20, scheduler);
        AtomicInteger callbackCount = new AtomicInteger();
        StringBuilder lastSummary = new StringBuilder();
        AgentSummaryHandle handle = svc.start("task-steady", agentId, summarizer,
            s -> { callbackCount.incrementAndGet(); lastSummary.setLength(0); lastSummary.append(s); }, null);
        try {
            // 首轮：转录「有内容」⇒ 必须产出一次真实摘要（守卫不得把首轮也吞掉）
            awaitUntil(() -> callbackCount.get() >= 1, 5_000, "首轮摘要未产出");
            assertThat(provider.calls).as("首轮必须真的 fork 过一次").hasValue(1);
            assertThat(lastSummary.toString()).isEqualTo("Invented step #1");

            // 转录冻结：再放 ~15 个 tick（interval=20ms）
            Thread.sleep(300);

            assertThat(provider.calls)
                .as("转录未前进时**不得**再 fork LLM —— 否则产出的就是编造进度"
                    + "（实机形态：worker-d 挂住后摘要仍每 30s 换一句）")
                .hasValue(1);
            assertThat(callbackCount)
                .as("转录未前进时 callback 不得再触发 ⇒ 面板上的摘要文本原地不动（诚实信号）")
                .hasValue(1);

            // 转录前进（agent 真的又做了一步）⇒ 摘要必须恢复前进
            writeTranscript(sessionDir, "session-1", agentId, List.of(
                msg("user", "p1", agentId, "u1", null, "2026-09-20T15:45:08Z"),
                msg("assistant", "working on task 4", agentId, "u2", "u1", "2026-09-20T15:45:09Z"),
                msg("assistant", "calling Bash", agentId, "u3", "u2", "2026-09-20T15:45:10Z"),
                msg("tool", "ls output", agentId, "u4", "u3", "2026-09-20T15:45:11Z")));
            awaitUntil(() -> callbackCount.get() >= 2, 5_000, "转录前进后摘要未恢复");
            assertThat(provider.calls.get()).as("转录前进后必须重新产出摘要").isGreaterThanOrEqualTo(2);
            assertThat(lastSummary.toString()).isNotEqualTo("Invented step #1");
        } finally {
            handle.stop();
            svc.shutdown();
        }
    }

    @Test
    @DisplayName("指纹只由「条数 + 末条身份」决定 ⇒ 末条变化即视为前进（不把新动作误判为静止）")
    void fingerprint_advancesWhenTranscriptGrowsOrLastMessageChanges() {
        String a = "a";
        AgentMessage m1 = msg("user", "p1", a, "u1", null, null);
        AgentMessage m2 = msg("assistant", "working", a, "u2", "u1", null);
        String one = AgentSummaryService.transcriptFingerprint(List.of(m1));
        String two = AgentSummaryService.transcriptFingerprint(List.of(m1, m2));
        assertThat(two).as("新增一条消息必须改变指纹").isNotEqualTo(one);
        // 同长度同末条 → 指纹稳定（不因重复读取而抖动，否则守卫失效）
        assertThat(AgentSummaryService.transcriptFingerprint(List.of(m1, m2))).isEqualTo(two);
        // 末条内容变了但条数没变（legacy 无 uuid 转录）→ 仍必须判为前进
        AgentMessage m2b = msg("assistant", "working harder", a, null, "u1", null);
        assertThat(AgentSummaryService.transcriptFingerprint(List.of(m1, m2b)))
            .as("末条无 uuid 时用内容长度+散列兜底，内容变化必须改变指纹")
            .isNotEqualTo(AgentSummaryService.transcriptFingerprint(List.of(m1, msg("assistant", "x", a, null, "u1", null))));
    }

    // ─────────────────────────── helpers ───────────────────────────

    private static void awaitUntil(java.util.function.BooleanSupplier cond, long timeoutMs, String failMsg)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError(failMsg + "（等待 " + timeoutMs + "ms 超时）");
            }
            Thread.sleep(10);
        }
    }

    private static AgentMessage msg(String role, String content, String agentId, String uuid,
                                    String parentUuid, String timestamp) {
        return new AgentMessage(role, content, false, agentId, true, uuid, parentUuid,
            List.of(), null, timestamp);
    }

    /** 写 sidechain transcript（路径与 SummarySummarizerImpl.readTranscript 的读侧同源）。 */
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
            if (m.timestamp() != null) n.put("timestamp", m.timestamp());
            sb.append(MAPPER.writeValueAsString(n)).append("\n");
        }
        Files.writeString(path, sb.toString());
    }
}
