package com.nexusai.application.agent.memory;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.nexusai.application.agent.compact.CompactionResult;
import com.nexusai.application.agent.compact.fork.ProductionForkedQuery;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.infra.llm.TaskBudgetParam;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SessionMemoryService telemetry 测试 · 对齐 CC sessionMemoryUtils.ts:117
 * {@code tengu_session_memory_loaded}（IMP-M-C-1）+ [IMP-CM-17] {@code tengu_sm_compact_*} 6 事件族
 * （sessionMemoryCompact.ts:534/541/558/565/609/624 结构化遥测）。
 *
 * <p><b>WHY (规则九 · 测试验证意图)</b>: CC 在 SM 压缩各回落分支发射结构化 logEvent：
 * <ul>
 *   <li>无 session memory 文件 → {@code tengu_sm_compact_no_session_memory}（:534）</li>
 *   <li>内容与模板相同 → {@code tengu_sm_compact_empty_template}（:541）</li>
 *   <li>lastSummarizedMessageId 不在消息集 → {@code tengu_sm_compact_summarized_id_not_found}（:558）</li>
 *   <li>resumed session → {@code tengu_sm_compact_resumed_session}（:565）</li>
 *   <li>post 超阈 → {@code tengu_sm_compact_threshold_exceeded}（:609）</li>
 *   <li>期望内错误 → {@code tengu_sm_compact_error}（:624，logEvent 而非 logError）</li>
 * </ul>
 * 事件名/属性名漂移 = analytics 数据丢失，故测试断言事件名 + 属性字段。
 */
@DisplayName("[IMP-CM-17] SessionMemoryService tengu_sm_compact_* 结构化遥测")
class SessionMemoryTelemetryTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        // [sm-cursor-sessionize] 会话游标按 sessionId 键控 → 全量复位（含全部会话游标 + 配置）
        SessionMemoryUtils.resetSessionMemoryState();
        SessionMemoryService.resetLastMemoryMessageUuid();
        SessionMemoryService.resetGateDisabledLogging();
    }

    @Test
    @DisplayName("读 session memory 文件成功 → tengu_session_memory_loaded(content_length)（CC :117）")
    void getSessionMemoryContent_emitsLoadedTelemetry() throws Exception {
        SessionMemoryService svc = new SessionMemoryService(tempDir);
        RecordingTelemetry telemetry = new RecordingTelemetry();
        svc.setTelemetry(telemetry);

        String sessionId = "sess-1";
        Path file = tempDir.resolve(sessionId).resolve("session-memory").resolve("summary.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "hello memory");

        String content = svc.getSessionMemoryContent(sessionId);

        assertThat(content).isEqualTo("hello memory");
        assertThat(telemetry.events)
            .as("读成功必须发射 tengu_session_memory_loaded")
            .contains("tengu_session_memory_loaded");
    }

    @Test
    @DisplayName("文件不存在（ENOENT）→ null 且不发 tengu_session_memory_loaded（CC :122-123）")
    void getSessionMemoryContent_missingFile_noTelemetry() {
        SessionMemoryService svc = new SessionMemoryService(tempDir);
        RecordingTelemetry telemetry = new RecordingTelemetry();
        svc.setTelemetry(telemetry);

        String content = svc.getSessionMemoryContent("sess-missing");

        assertThat(content).isNull();
        assertThat(telemetry.events)
            .as("文件不可访问 → null，不发射 loaded 事件")
            .doesNotContain("tengu_session_memory_loaded");
    }

    // ════════════════════════════════════════════════════════════════════
    // [IMP-CM-17] tengu_sm_compact_* 6 事件族（sessionMemoryCompact.ts:514-630）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("无 session memory 文件 → tengu_sm_compact_no_session_memory（CC :533-536）")
    void trySessionMemoryCompaction_noSessionMemory_emitsEvent() {
        SessionMemoryService svc = new SessionMemoryService(tempDir);
        svc.setSmSessionMemoryEnabled(true);
        svc.setSmCompactEnabled(true);
        RecordingTelemetry telemetry = new RecordingTelemetry();
        svc.setTelemetry(telemetry);

        CompactionResult r = svc.trySessionMemoryCompaction(
            messages(5), "sess-nofile", "agent-1", null);

        assertThat(r).as("无文件 → 回落 null").isNull();
        assertThat(telemetry.events)
            .as("CC :534 无 session memory → logEvent('tengu_sm_compact_no_session_memory', {})")
            .contains("tengu_sm_compact_no_session_memory");
    }

    @Test
    @DisplayName("session memory 与模板相同 → tengu_sm_compact_empty_template（CC :540-543）")
    void trySessionMemoryCompaction_emptyTemplate_emitsEvent() throws Exception {
        writeSessionMemory("sess-template",
            com.nexusai.application.agent.session.SessionMemoryPrompts.DEFAULT_SESSION_MEMORY_TEMPLATE);

        SessionMemoryService svc = new SessionMemoryService(tempDir);
        svc.setSmSessionMemoryEnabled(true);
        svc.setSmCompactEnabled(true);
        RecordingTelemetry telemetry = new RecordingTelemetry();
        svc.setTelemetry(telemetry);

        CompactionResult r = svc.trySessionMemoryCompaction(
            messages(5), "sess-template", "agent-1", null);

        assertThat(r).as("模板内容 → 回落 null").isNull();
        assertThat(telemetry.events)
            .as("CC :541 模板相同 → logEvent('tengu_sm_compact_empty_template', {})")
            .contains("tengu_sm_compact_empty_template");
    }

    @Test
    @DisplayName("lastSummarizedMessageId 不在消息集 → tengu_sm_compact_summarized_id_not_found（CC :554-560）")
    void trySessionMemoryCompaction_summarizedIdNotFound_emitsEvent() throws Exception {
        writeSessionMemory("sess-missing-id", "# Learnings\nsome real learning content\n");

        SessionMemoryService svc = new SessionMemoryService(tempDir);
        svc.setSmSessionMemoryEnabled(true);
        svc.setSmCompactEnabled(true);
        RecordingTelemetry telemetry = new RecordingTelemetry();
        svc.setTelemetry(telemetry);
        SessionMemoryService.setLastSummarizedMessageId("sess-missing-id", "nonexistent-uuid");

        CompactionResult r = svc.trySessionMemoryCompaction(
            messages(5), "sess-missing-id", "agent-1", null);

        assertThat(r).as("摘要 ID 不存在 → 回落 null").isNull();
        assertThat(telemetry.events)
            .as("CC :558 摘要 ID 不在消息集 → logEvent('tengu_sm_compact_summarized_id_not_found', {})")
            .contains("tengu_sm_compact_summarized_id_not_found");
    }

    @Test
    @DisplayName("无 lastSummarizedMessageId（resumed session）→ tengu_sm_compact_resumed_session（CC :562-566）")
    void trySessionMemoryCompaction_resumedSession_emitsEvent() throws Exception {
        writeSessionMemory("sess-resumed", "# Learnings\nsome real learning content\n");

        SessionMemoryService svc = new SessionMemoryService(tempDir);
        svc.setSmSessionMemoryEnabled(true);
        svc.setSmCompactEnabled(true);
        RecordingTelemetry telemetry = new RecordingTelemetry();
        svc.setTelemetry(telemetry);
        SessionMemoryService.setLastSummarizedMessageId("sess-resumed", null);

        CompactionResult r = svc.trySessionMemoryCompaction(
            messages(15), "sess-resumed", "agent-1", Integer.MAX_VALUE);

        assertThat(r).as("resumed session + 有效内容 → 压缩成功").isNotNull();
        assertThat(telemetry.events)
            .as("CC :565 resumed session → logEvent('tengu_sm_compact_resumed_session', {})")
            .contains("tengu_sm_compact_resumed_session");
    }

    @Test
    @DisplayName("post 超阈 → tengu_sm_compact_threshold_exceeded（postCompactTokenCount/autoCompactThreshold，CC :609-613）")
    void trySessionMemoryCompaction_thresholdExceeded_emitsEventWithAttrs() throws Exception {
        writeSessionMemory("sess-threshold", "# Learnings\nsome real learning content\n");

        SessionMemoryService svc = new SessionMemoryService(tempDir);
        svc.setSmSessionMemoryEnabled(true);
        svc.setSmCompactEnabled(true);
        RecordingTelemetry telemetry = new RecordingTelemetry();
        svc.setTelemetry(telemetry);
        SessionMemoryService.setLastSummarizedMessageId("sess-threshold", null);

        // postCompactTokenCount 远大于 1 → 必超阈 → 回落 null
        CompactionResult r = svc.trySessionMemoryCompaction(
            messages(15), "sess-threshold", "agent-1", 1);

        assertThat(r).as("post 超阈 → 回落 null").isNull();
        assertThat(telemetry.events)
            .as("CC :609 超阈 → logEvent('tengu_sm_compact_threshold_exceeded', {postCompactTokenCount, autoCompactThreshold})")
            .contains("tengu_sm_compact_threshold_exceeded");
        Map<String, Object> attrs = telemetry.attrsOf("tengu_sm_compact_threshold_exceeded");
        assertThat(attrs)
            .as("CC :609-613 属性含 postCompactTokenCount + autoCompactThreshold")
            .containsKeys("postCompactTokenCount", "autoCompactThreshold");
        assertThat(attrs.get("autoCompactThreshold"))
            .as("阈值透传 = 入参 1")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("期望内错误 → tengu_sm_compact_error（CC :621-629，logEvent 而非 logError）")
    void trySessionMemoryCompaction_error_emitsEvent() throws Exception {
        writeSessionMemory("sess-error", "# Learnings\nsome real learning content\n");

        SessionMemoryService svc = new SessionMemoryService(tempDir);
        svc.setSmSessionMemoryEnabled(true);
        svc.setSmCompactEnabled(true);
        RecordingTelemetry telemetry = new RecordingTelemetry();
        svc.setTelemetry(telemetry);
        SessionMemoryService.setLastSummarizedMessageId("sess-error", null);

        // messages=null → resumed 分支 messages.size() NPE → try 内 catch → error 事件
        CompactionResult r = svc.trySessionMemoryCompaction(
            null, "sess-error", "agent-1", null);

        assertThat(r).as("期望内错误 → 回落 null").isNull();
        assertThat(telemetry.events)
            .as("CC :624 期望内错误 → logEvent('tengu_sm_compact_error', {})")
            .contains("tengu_sm_compact_error");
    }

    // ════════════════════════════════════════════════════════════════════
    // [IMP-MV2-10] tengu_extract_memories_extraction usage 保真
    // （forkedAgent.ts:557-566 全量累计 → extractMemories.ts:473-485 事件字段）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[IMP-MV2-10] extraction 事件携带真实 input/cache usage（ProductionForkedQuery 全量累计 · 非恒 0）")
    void extractionEvent_carriesFullUsageFromFork() {
        // WHY: D1 △-2 —— ProductionForkedQuery 旧实现仅累计 outputTokens
        //   （new ForkUsage(0, outputTokens, 0, 0)）→ tengu_extract_memories_extraction 的
        //   input/cache 恒 0，成本监控失真。CC forkedAgent.ts:557-566 从 message_delta 累计
        //   全量 usage（input/output/cache_read/cache_create）→ 事件字段必须非恒 0。
        MemoryStorage storage = new MemoryStorage(tempDir);
        ExtractMemoriesAgent agent = new ExtractMemoriesAgent(storage);
        RecordingTelemetry telemetry = new RecordingTelemetry();
        agent.setTelemetry(telemetry);

        // provider 上报完整 usage（含 cache_read>0）→ ProductionForkedQuery 全量累计
        UsageProvider provider = new UsageProvider(
            new AssistantMessage("final answer", "stop", List.of(), "", null,
                new AgentUsage(1000L, 300L, 50L, 700L, null, null, null)));
        ProductionForkedQuery loop = new ProductionForkedQuery(
            () -> provider, () -> "test-model", () -> ProviderConfig.empty(),
            ToolRegistry.from(List.of()));
        agent.setForkedQuery(loop);

        runExtractSync(agent, List.of(msg("m1", "a"), msg("m2", "b")));

        Map<String, Object> attrs = telemetry.attrsOf("tengu_extract_memories_extraction");
        assertThat(attrs).as("fork 成功必须发射 extraction 事件").isNotNull();
        assertThat(attrs.get("input_tokens"))
            .as("input 非恒 0（CC forkedAgent.ts:557-566 全量累计）").isEqualTo(1000L);
        assertThat(attrs.get("output_tokens")).isEqualTo(300L);
        assertThat(attrs.get("cache_read_input_tokens"))
            .as("cache read 必须 >0（prompt cache 命中可观测）").isEqualTo(700L);
        assertThat(attrs.get("cache_creation_input_tokens")).isEqualTo(50L);
    }

    @Test
    @DisplayName("[IMP-MV2-10] extraction 事件跨轮累计 usage（多轮 fork Σ input/output/cache）")
    void extractionEvent_accumulatesUsageAcrossTurns() {
        // WHY: CC accumulateUsage 逐 API call 累计（forkedAgent.ts:564-565）——多轮 fork
        //   的 totalUsage 必须是各轮 Σ（非末轮覆盖）。工具轮（空注册表 → 未知工具 error
        //   结果，loop 继续）+ 最终回答轮，验证两轮 usage 求和进事件。
        MemoryStorage storage = new MemoryStorage(tempDir);
        ExtractMemoriesAgent agent = new ExtractMemoriesAgent(storage);
        RecordingTelemetry telemetry = new RecordingTelemetry();
        agent.setTelemetry(telemetry);

        UsageProvider provider = new UsageProvider(
            new AssistantMessage("", "tool_calls",
                List.of(new ToolUseBlock("c1", "Echo", JsonNodeFactory.instance.objectNode())),
                "", null,
                new AgentUsage(500L, 150L, 20L, 300L, null, null, null)),
            new AssistantMessage("done", "stop", List.of(), "", null,
                new AgentUsage(700L, 42L, 30L, 400L, null, null, null)));
        ProductionForkedQuery loop = new ProductionForkedQuery(
            () -> provider, () -> "test-model", () -> ProviderConfig.empty(),
            ToolRegistry.from(List.of()));
        agent.setForkedQuery(loop);

        runExtractSync(agent, List.of(msg("m1", "a"), msg("m2", "b")));

        Map<String, Object> attrs = telemetry.attrsOf("tengu_extract_memories_extraction");
        assertThat(attrs).as("fork 成功必须发射 extraction 事件").isNotNull();
        assertThat(attrs.get("input_tokens")).as("两轮 Σ input").isEqualTo(1200L);
        assertThat(attrs.get("output_tokens")).as("两轮 Σ output").isEqualTo(192L);
        assertThat(attrs.get("cache_read_input_tokens")).as("两轮 Σ cache read").isEqualTo(700L);
        assertThat(attrs.get("cache_creation_input_tokens")).as("两轮 Σ cache create").isEqualTo(50L);
    }

    /** 显式开 gate 同步跑一轮提取（对齐 ExtractMemoriesAgentTest.runExtractSync 模式）。 */
    private static void runExtractSync(ExtractMemoriesAgent agent, List<ChatMessageDto> messages) {
        agent.setExtractionGate(() -> true);
        agent.setAutoMemoryEnabled(() -> true);
        agent.executeExtractMemories(messages, null);
        agent.drainPendingExtraction(5000);
    }

    /** 脚本化 provider：每轮 stream 按脚本返回 assistant message（含完整 AgentUsage）。 */
    static final class UsageProvider implements LlmProvider {
        private final List<AssistantMessage> script;
        private final AtomicInteger callCount = new AtomicInteger();

        UsageProvider(AssistantMessage... script) {
            this.script = List.of(script);
        }

        @Override public String type() { return "test"; }
        @Override public String chat(ProviderConfig c, String m, String s, String u) { return ""; }

        @Override
        public void stream(ProviderConfig config, String modelName,
                           List<SystemPromptBlock> systemPromptBlocks,
                           List<ChatMessageDto> history, com.fasterxml.jackson.databind.node.ArrayNode tools,
                           Integer maxOutputTokensOverride, TaskBudgetParam taskBudget,
                           String effortValue, String querySource,
                           Consumer<String> onChunk,
                           Consumer<AssistantMessage> onAssistantMessage,
                           Consumer<ToolUseBlock> onToolCallComplete,
                           Consumer<String> onReasoningChunk,
                           Runnable onStreamingFallback,
                           AbortController abortController,
                           Consumer<Throwable> onError,
                           Runnable onComplete) {
            int idx = Math.min(callCount.getAndIncrement(), script.size() - 1);
            onAssistantMessage.accept(script.get(idx));
            onComplete.run();
        }
    }

    // ── helpers ──

    private void writeSessionMemory(String sessionId, String content) throws Exception {
        Path file = tempDir.resolve(sessionId).resolve("session-memory").resolve("summary.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static List<ChatMessageDto> messages(int count) {
        List<ChatMessageDto> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(msg("u" + i, Role.user, "hi"));
        }
        return list;
    }

    private static ChatMessageDto msg(String id, Role role, String content) {
        return new ChatMessageDto(id, null, role, role == Role.assistant ? "assistant" : "user",
            content, null, List.<ToolCallDto>of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null, List.of(), List.of(), null, false, false);
    }

    /** 便捷构造：默认 user 角色消息（[IMP-MV2-10] extraction 事件测试用）。 */
    private static ChatMessageDto msg(String id, String content) {
        return msg(id, Role.user, content);
    }

    /** 记录事件名 + 属性 + 计数的 Telemetry 假实现。 */
    private static final class RecordingTelemetry extends Telemetry {
        final List<String> events = new ArrayList<>();
        final Map<String, Map<String, Object>> attrsByEvent = new HashMap<>();

        @Override
        public void recordEvent(String eventName, java.util.Map<String, Object> attributes) {
            events.add(eventName);
            attrsByEvent.put(eventName, attributes);
            super.recordEvent(eventName, attributes);
        }

        Map<String, Object> attrsOf(String eventName) {
            return attrsByEvent.get(eventName);
        }
    }
}
