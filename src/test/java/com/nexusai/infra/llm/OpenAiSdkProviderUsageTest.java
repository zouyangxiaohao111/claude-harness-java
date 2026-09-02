package com.nexusai.infra.llm;

import com.nexusai.application.agent.prompt.CacheScope;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
import com.nexusai.application.agent.tool.AgentUsage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.openai.models.ChatCompletion;
import com.openai.models.ChatCompletionChunk;
import com.openai.models.ChatCompletionCreateParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [DEC-04 R2-USAGE] OpenAiSdkProvider usage 数据源解析测试。
 *
 * <p><b>WHY (规则 9 · 验证意图)</b>: CC finalizeAgentTool 透传 lastAssistantMessage.message.usage
 * (agentToolUtils.ts:355), Java OpenAI provider 旧实现完全未解析 usage (buildAssistantMessage 走
 * 3-arg 构造, outputTokens=0) → ChatMessageDto.inputTokens/outputTokens 恒 null (DEC-04 症状).
 * 本测试验证:
 * <ol>
 *   <li>流式请求开启 stream_options.include_usage=true (OpenAI 流式默认不返回 usage)</li>
 *   <li>final chunk usage.prompt_tokens/completion_tokens → input/output</li>
 *   <li>prompt_tokens_details.cached_tokens → cacheReadInputTokens (OpenAI cache read 等价)</li>
 *   <li>buildAssistantMessage 产出完整 AgentUsage (非零)</li>
 * </ol>
 */
@DisplayName("[DEC-04] OpenAiSdkProvider usage 数据源解析 (prompt/completion/cached → AgentUsage)")
class OpenAiSdkProviderUsageTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ChatCompletionChunk chunk(String json) throws Exception {
        return com.openai.core.ObjectMappers.jsonMapper()
            .convertValue(JSON.readTree(json), ChatCompletionChunk.class);
    }

    private static ChatCompletion completion(String json) throws Exception {
        return com.openai.core.ObjectMappers.jsonMapper()
            .convertValue(JSON.readTree(json), ChatCompletion.class);
    }

    private static ChatMessageDto user(String text) {
        return new ChatMessageDto(
            "m1", "s1", com.nexusai.model.session.dto.Role.user, null, text, null,
            null, null, null, null, null, java.time.OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    @Test
    @DisplayName("[DEC-04] 流式请求带 stream_options.include_usage=true (OpenAI 流式 usage 需显式开启)")
    void buildRequestParams_streaming_shouldEnableIncludeUsage() throws Exception {
        // WHY: OpenAI 流式默认不返回 usage, 必须 stream_options.include_usage=true 才能拿到
        //   final chunk usage → AgentUsage 数据源 (对齐 CC Anthropic 流式恒返回 usage).
        ChatCompletionCreateParams params = OpenAiSdkProvider.buildRequestParams(
            "gpt-4o", "sys", List.of(user("你好")), null, null, false, null, null, null, true);

        assertThat(params.streamOptions())
            .as("includeUsage=true 必须设置 stream_options (CC 流式 usage 恒返回)")
            .isPresent();
        assertThat(params.streamOptions().get().includeUsage())
            .as("includeUsage 必须为 true")
            .contains(true);
    }

    @Test
    @DisplayName("[DEC-04] parseChunk final chunk usage → state (prompt/completion/cached)")
    void parseChunk_finalChunkUsage_capturesTokens() throws Exception {
        // WHY: 最终空 chunk (无 choices) 携带 usage (include_usage=true), prompt_tokens 是累计输入,
        //   completion_tokens 是累计输出, cached_tokens 是 cache read (OpenAI 语义 → CC cache_read_input_tokens).
        OpenAiStreamState state = new OpenAiStreamState();
        ChatCompletionChunk usageChunk = chunk(
            "{\"id\":\"chatcmpl-u1\",\"object\":\"chat.completion.chunk\",\"created\":0,\"model\":\"gpt-4o\","
                + "\"choices\":[],"
                + "\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":42,\"total_tokens\":142,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":20}}}");

        new OpenAiSdkProvider().parseChunk(usageChunk, state, null, null, null,
            ConcurrentHashMap.newKeySet());

        assertThat(state.inputTokens).as("prompt_tokens → inputTokens").isEqualTo(100L);
        assertThat(state.outputTokens).as("completion_tokens → outputTokens").isEqualTo(42L);
        assertThat(state.cacheReadInputTokens).as("prompt_tokens_details.cached_tokens → cacheRead").isEqualTo(20L);
    }

    @Test
    @DisplayName("[B2-R1/R2] parseChunk final chunk usage 含 DeepSeek cache 字段 → state (hit→cacheRead/miss→cacheCreation)")
    void parseChunk_finalChunkUsage_deepseekCacheTokens() throws Exception {
        // WHY: DeepSeek/openai-compatible 顶层 usage 用 prompt_cache_hit_tokens /
        //   prompt_cache_miss_tokens 表达缓存读/写（OpenAI 标准 usage 无 cache_creation 等价；
        //   2026-08-25 用户提供实际响应 {prompt_cache_hit_tokens:0, prompt_cache_miss_tokens:10}）。
        //   parseChunk 镜像非流式 extractUsage (:1062-1078) 的 additionalProperties 读取（B2-R1/R2）→
        //   流式链路 cache 字段必须正确映射，否则 t/s + 账本 + compact 基线系统性低估（DRIFT-1/2/6）。
        OpenAiStreamState state = new OpenAiStreamState();
        ChatCompletionChunk usageChunk = chunk(
            "{\"id\":\"chatcmpl-u3\",\"object\":\"chat.completion.chunk\",\"created\":0,\"model\":\"deepseek-v4\","
                + "\"choices\":[],"
                + "\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":42,\"total_tokens\":142,"
                + "\"prompt_cache_hit_tokens\":30,\"prompt_cache_miss_tokens\":5}}");

        new OpenAiSdkProvider().parseChunk(usageChunk, state, null, null, null,
            ConcurrentHashMap.newKeySet());

        assertThat(state.inputTokens).as("prompt_tokens → inputTokens").isEqualTo(100L);
        assertThat(state.outputTokens).as("completion_tokens → outputTokens").isEqualTo(42L);
        assertThat(state.cacheReadInputTokens).as("prompt_cache_hit_tokens → cacheRead").isEqualTo(30L);
        assertThat(state.cacheCreationInputTokens).as("prompt_cache_miss_tokens → cacheCreation").isEqualTo(5L);
    }

    @Test
    @DisplayName("[DEC-04] parseChunk 无 usage chunk → state 保持 0 (不误写)")
    void parseChunk_noUsageChunk_keepsZero() throws Exception {
        // WHY: 流式前段 chunk 无 usage 字段 (仅 final chunk 有), 不能把缺省当 0 覆盖已捕获值.
        OpenAiStreamState state = new OpenAiStreamState();
        ChatCompletionChunk textChunk = chunk(
            "{\"id\":\"chatcmpl-u2\",\"object\":\"chat.completion.chunk\",\"created\":0,\"model\":\"gpt-4o\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"hi\"},"
                + "\"finish_reason\":null}]}");

        new OpenAiSdkProvider().parseChunk(textChunk, state, null, null, null,
            ConcurrentHashMap.newKeySet());

        assertThat(state.inputTokens).isZero();
        assertThat(state.outputTokens).isZero();
        assertThat(state.cacheReadInputTokens).isZero();
    }

    @Test
    @DisplayName("[DEC-04] buildAssistantMessage 产出完整 AgentUsage (非零, 4 字段)")
    void buildAssistantMessage_carriesFullUsage() throws Exception {
        // WHY: 旧实现走 3-arg 构造 (outputTokens=0) → SubagentResult.usage 恒 0/EMPTY (DEC-04).
        OpenAiStreamState state = new OpenAiStreamState();
        state.inputTokens = 100L;
        state.outputTokens = 42L;
        state.cacheReadInputTokens = 20L;
        state.finishReason = "stop";

        AssistantMessage msg = new OpenAiSdkProvider().buildAssistantMessage(state);

        assertThat(msg.usage()).isNotNull();
        assertThat(msg.inputTokens()).isEqualTo(100L);
        assertThat(msg.outputTokens()).isEqualTo(42L);
        assertThat(msg.cacheReadInputTokens()).isEqualTo(20L);
        assertThat(msg.cacheCreationInputTokens())
            .as("OpenAI 无 cache_creation 等价 → 零初始化 0（CC emptyUsage.ts:10）")
            .isEqualTo(0L);
    }

    @Test
    @DisplayName("[IMP-SUB-26 A6] 非流式 ChatCompletion usage → extractUsage (prompt/completion/cached → AgentUsage)")
    void extractUsage_nonStreaming_parsesTokens() throws Exception {
        // WHY: 非流式 ChatCompletion 响应携带 usage (prompt_tokens/completion_tokens/
        //   prompt_tokens_details.cached_tokens), 旧实现 chatWithOptionsMessage 丢弃 usage →
        //   ChatMessageDto.usage=null → 回退 fromInputOutput/EMPTY (DEC-04 残留缺口).
        //   CC 非流式路径透传 message.usage (claude.ts:870-903), Java 必须同语义.
        ChatCompletion resp = completion(
            "{\"id\":\"chatcmpl-n1\",\"object\":\"chat.completion\",\"created\":0,\"model\":\"gpt-4o\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"hi\"},"
                + "\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":42,\"total_tokens\":142,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":20}}}");

        AgentUsage usage = new OpenAiSdkProvider().extractUsage(resp);

        assertThat(usage).isNotNull();
        assertThat(usage.inputTokens()).as("prompt_tokens → inputTokens").isEqualTo(100L);
        assertThat(usage.outputTokens()).as("completion_tokens → outputTokens").isEqualTo(42L);
        assertThat(usage.cacheReadInputTokens()).as("prompt_tokens_details.cached_tokens → cacheRead").isEqualTo(20L);
        assertThat(usage.cacheCreationInputTokens())
            .as("OpenAI 无 cache_creation 等价 → 零初始化 0（CC emptyUsage.ts:10）")
            .isEqualTo(0L);
        assertThat(usage.serverToolUse()).as("OpenAI 无 server_tool_use 等价 → null").isNull();
    }

    @Test
    @DisplayName("[B2-R1/R2] extractUsage 非流式 usage 含 DeepSeek cache 字段 → cacheRead/cacheCreation 映射")
    void extractUsage_nonStreaming_deepseekCacheTokens() throws Exception {
        // WHY: DeepSeek/openai-compatible 响应 usage 顶层带 prompt_cache_hit_tokens /
        //   prompt_cache_miss_tokens（2026-08-25 用户提供实际响应 {hit:0, miss:10}）→
        //   extractUsage 经 additionalProperties 映射 cacheReadInputTokens / cacheCreationInputTokens
        //   （:1064-1078）。非流式链路（chatWithOptionsMessage）缺此映射时 ChatMessageDto.usage
        //   缓存字段恒 0 → 账本/compact 基线低估（与流式 B2-R1/R2 对称，两链路必须同源映射）。
        ChatCompletion resp = completion(
            "{\"id\":\"chatcmpl-n3\",\"object\":\"chat.completion\",\"created\":0,\"model\":\"deepseek-v4\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"hi\"},"
                + "\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":42,\"total_tokens\":142,"
                + "\"prompt_cache_hit_tokens\":30,\"prompt_cache_miss_tokens\":5}}");

        AgentUsage usage = new OpenAiSdkProvider().extractUsage(resp);

        assertThat(usage).isNotNull();
        assertThat(usage.inputTokens()).as("prompt_tokens → inputTokens").isEqualTo(100L);
        assertThat(usage.outputTokens()).as("completion_tokens → outputTokens").isEqualTo(42L);
        assertThat(usage.cacheReadInputTokens()).as("prompt_cache_hit_tokens → cacheRead").isEqualTo(30L);
        assertThat(usage.cacheCreationInputTokens()).as("prompt_cache_miss_tokens → cacheCreation").isEqualTo(5L);
    }

    @Test
    @DisplayName("[IMP-SUB-26 A6] 非流式 ChatCompletion 无 usage → extractUsage 返回 null (AssistantMessage 归一化 EMPTY)")
    void extractUsage_noUsage_returnsNull() throws Exception {
        // WHY: 非流式响应缺 usage 字段 (Optional.empty) 时, 不能伪造 0 值 —
        //   返回 null 由 AssistantMessage 构造器归一化 AgentUsage.EMPTY (对齐 CC emptyUsage.ts:8).
        ChatCompletion resp = completion(
            "{\"id\":\"chatcmpl-n2\",\"object\":\"chat.completion\",\"created\":0,\"model\":\"gpt-4o\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"hi\"},"
                + "\"finish_reason\":\"stop\"}]}");

        AgentUsage usage = new OpenAiSdkProvider().extractUsage(resp);

        assertThat(usage).as("缺 usage → null, 不伪造 0 值").isNull();
    }

    @Test
    @DisplayName("[IMP-SUB-26 A6] MockLlmProvider 配置 usage 后流式产出真实值 (E3 mock provider)")
    void mockProvider_stream_emitsConfiguredUsage() throws Exception {
        // WHY: E3 要求 mock provider 响应断言 usage 真实值 — MockLlmProvider 是唯一可注入 provider 的
        //   测试通道, 配置真实 4 字段后 assistant message 必须携带 (不再恒 0).
        MockLlmProvider mock = new MockLlmProvider();
        mock.setMockUsage(new com.nexusai.application.agent.tool.AgentUsage(
            111L, 222L, 333L, 444L, null, null, null));

        List<AssistantMessage> captured = new ArrayList<>();
        List<String> chunks = new ArrayList<>();
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        mock.stream(new ProviderConfig("http://mock", "k"), "mock-model",
            List.of(new SystemPromptBlock("sys", CacheScope.NULL)),
            List.of(), null, null, null, null, null,
            chunks::add, captured::add, null, null, null, null, e -> {
            }, done::countDown);
        done.await(5, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(captured).isNotEmpty();
        AssistantMessage am = captured.get(captured.size() - 1);
        assertThat(am.usage()).isNotNull();
        assertThat(am.inputTokens()).isEqualTo(111L);
        assertThat(am.outputTokens()).isEqualTo(222L);
        assertThat(am.cacheCreationInputTokens()).isEqualTo(333L);
        assertThat(am.cacheReadInputTokens()).isEqualTo(444L);
    }

    private static JsonNode json(String s) throws Exception {
        return JSON.readTree(s);
    }
}
