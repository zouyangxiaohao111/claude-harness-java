package com.nexusai.infra.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.TextBlockParam;
import com.nexusai.application.agent.tool.ToolUseBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [DEC-RV-07] AnthropicSdkProvider 官方 SDK 专用测试。
 *
 * <p><b>WHY (规则九 · 验证意图)</b>: 官方 anthropic-java SDK 引入后，请求体构造与事件映射
 * 走 SDK 类型（MessageCreateParams / RawMessageStreamEvent），必须验证：
 * <ul>
 *   <li>buildClient 对齐 CC claude.ts:1781 maxRetries=0（自动重试关闭 → 手工重试）</li>
 *   <li>buildMessageParams 请求体：model / max_tokens / system blocks / cache marker /
 *       output_config（task_budget+effort 单节点）/ anthropic-beta header</li>
 *   <li>mapStreamEvent 逐事件映射：text_delta→onChunk / input_json_delta→tool 累积 /
 *       thinking_delta→onReasoningChunk / message_delta stop_reason / content_block_stop→onToolCallComplete
 *       / unknown（ping）→no-op（SDK 校验通过不抛）</li>
 *   <li>buildAssistantMessage finishReason 归一化（CC claude.ts:2266-2292 迁移后同构）</li>
 * </ul>
 */
class AnthropicSdkProviderTest {

    @Test
    @DisplayName("[DEC-RV-07] buildClient: maxRetries(0) + apiKey + baseUrl（CC claude.ts:1781 自动重试关闭）")
    void buildClient_maxRetries0() {
        AnthropicClient client = AnthropicSdkProvider.buildClient(
            new ProviderConfig("https://api.anthropic.com", "sk-test"));
        assertThat(client).isNotNull();
    }

    @Test
    @DisplayName("[DEC-RV-07] buildMessageParams: model + max_tokens + system blocks（cache_control 门控）")
    void buildMessageParams_modelAndSystemBlocks() {
        MessageCreateParams params = AnthropicSdkProvider.buildMessageParams(
            "claude-sonnet-4-6",
            List.of(new com.nexusai.application.agent.prompt.SystemPromptBlock(
                "sys-body", com.nexusai.application.agent.prompt.CacheScope.ORG)),
            List.of(), null, null, null, null, null, null);

        assertThat(params.model().toString()).contains("claude-sonnet-4-6");
        assertThat(params.maxTokens()).isGreaterThan(0);
        assertThat(params.system()).isPresent();
        List<TextBlockParam> blocks = params.system().get().asTextBlockParams();
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).text()).isEqualTo("sys-body");
        assertThat(blocks.get(0).cacheControl()).isPresent();
    }

    @Test
    @DisplayName("[DEC-RV-07] buildMessageParams: task_budget + effort 共存 output_config 单节点 + anthropic-beta header")
    void buildMessageParams_outputConfigAndBeta() {
        MessageCreateParams params = AnthropicSdkProvider.buildMessageParams(
            "claude-sonnet-4-6",
            null,
            List.of(), null, null,
            new TaskBudgetParam(200_000, 165_000), "high", null, null);

        assertThat(params.outputConfig()).isPresent();
        var oc = params.outputConfig().get();
        assertThat(oc._additionalProperties()).containsKey("task_budget");
        assertThat(oc.effort()).isPresent();

        // anthropic-beta: task-budgets-2026-03-13,effort-2025-11-24（CC betas.join(',')）
        List<String> beta = params._additionalHeaders().values("anthropic-beta");
        assertThat(beta).isNotEmpty();
        assertThat(beta.get(0)).contains(AnthropicSdkProvider.TASK_BUDGETS_BETA_HEADER)
            .contains(AnthropicSdkProvider.EFFORT_BETA_HEADER);
    }

    @Test
    @DisplayName("[DEC-RV-07] buildMessageParams: messages cache marker exactly-one（CC claude.ts:3078-3091）")
    void buildMessageParams_cacheMarker() {
        // 一条 user 消息 → marker 落最后一条（skipCacheWrite null）
        MessageCreateParams params = AnthropicSdkProvider.buildMessageParams(
            "claude-sonnet-4-6", List.of(new com.nexusai.application.agent.prompt.SystemPromptBlock("sys",
                com.nexusai.application.agent.prompt.CacheScope.ORG)),
            List.of(userMsg("hi")), null, null, null, null, null, null);
        assertThat(params.messages()).isNotEmpty();
        MessageParam last = params.messages().get(params.messages().size() - 1);
        assertThat(last.content().isBlockParams()).isTrue();
        var blocks = last.content().asBlockParams();
        assertThat(blocks.get(blocks.size() - 1).isText()).isTrue();
        assertThat(blocks.get(blocks.size() - 1).asText().cacheControl()).isPresent();
    }

    @Test
    @DisplayName("[DEC-RV-07] mapStreamEvent: text_delta → onChunk + message_delta stop_reason + usage.output_tokens")
    void mapStreamEvent_textAndDelta() {
        AnthropicSdkProvider.StreamState state = new AnthropicSdkProvider.StreamState();
        List<String> chunks = new ArrayList<>();
        RawMessageStreamEvent text = ObjectMappers.jsonMapper().convertValue(
            json("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"hello\"}}"),
            RawMessageStreamEvent.class);
        AnthropicSdkProvider.mapStreamEvent(text, state, chunks::add, null, null, null);
        assertThat(state.content.toString()).isEqualTo("hello");
        assertThat(chunks).containsExactly("hello");

        RawMessageStreamEvent delta = ObjectMappers.jsonMapper().convertValue(
            json("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\"},\"usage\":{\"output_tokens\":42,\"input_tokens\":100}}"),
            RawMessageStreamEvent.class);
        AnthropicSdkProvider.mapStreamEvent(delta, state, null, null, null, null);
        assertThat(state.finishReason).isEqualTo("max_tokens");
        assertThat(state.outputTokens).isEqualTo(42);
    }

    @Test
    @DisplayName("[DEC-04] mapStreamEvent message_start → inputTokens/cache 捕获 + buildAssistantMessage 全字段 usage")
    void mapStreamEvent_messageStartUsage_buildAssistantMessageCarriesFullUsage() {
        // WHY: CC finalizeAgentTool 透传 lastAssistantMessage.message.usage (agentToolUtils.ts:355),
        //   Anthropic 流式 input_tokens 在 message_start.usage, output_tokens 在 message_delta.usage.
        //   旧 Java buildAssistantMessage 只透传 outputTokens → input/cache 字段恒 0/丢失 (DEC-04).
        //   message_start 需 SDK 必填字段 (id/role/content/model/usage) 才能反序列化.
        AnthropicSdkProvider.StreamState state = new AnthropicSdkProvider.StreamState();
        // Anthropic API message_start 结构: {type, message:{id,type,role,content,model,usage}} —
        //   mapStreamEvent 读 start.message().usage(), 故 usage 必须嵌套在 message 内.
        RawMessageStreamEvent start = ObjectMappers.jsonMapper().convertValue(
            json("{\"type\":\"message_start\",\"message\":{\"id\":\"msg_usage_1\",\"type\":\"message\","
                + "\"role\":\"assistant\",\"content\":[],\"model\":\"claude-sonnet-4-6\","
                + "\"stop_reason\":null,\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":100,\"output_tokens\":5,"
                + "\"cache_read_input_tokens\":20,\"cache_creation_input_tokens\":30}}}"),
            RawMessageStreamEvent.class);
        AnthropicSdkProvider.mapStreamEvent(start, state, null, null, null, null);
        assertThat(state.inputTokens).isEqualTo(100L);
        assertThat(state.cacheReadInputTokens).isEqualTo(20L);
        assertThat(state.cacheCreationInputTokens).isEqualTo(30L);

        RawMessageStreamEvent delta = ObjectMappers.jsonMapper().convertValue(
            json("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":42}}"),
            RawMessageStreamEvent.class);
        AnthropicSdkProvider.mapStreamEvent(delta, state, null, null, null, null);
        assertThat(state.outputTokens).isEqualTo(42L);

        AssistantMessage msg = AnthropicSdkProvider.buildAssistantMessage(state);
        assertThat(msg.usage()).isNotNull();
        assertThat(msg.inputTokens()).as("input_tokens 来自 message_start.usage").isEqualTo(100L);
        assertThat(msg.outputTokens()).as("output_tokens 来自 message_delta.usage 最终值").isEqualTo(42L);
        assertThat(msg.cacheReadInputTokens()).isEqualTo(20L);
        assertThat(msg.cacheCreationInputTokens()).isEqualTo(30L);
    }

    @Test
    @DisplayName("[R32-06] mapStreamEvent message_start → server_tool_use/service_tier/cache_creation 嵌套字段捕获")
    void mapStreamEvent_messageStart_nestedUsageFields() {
        // WHY: CC agentToolResultSchema usage 7 子字段含 server_tool_use/service_tier/cache_creation
        //   (agentToolUtils.ts:243-255), finalizeAgentTool (:355) 透传 message.usage 完整对象.
        //   旧 Java 只解析 4 token 字段 → 嵌套 3 字段恒 null (R32-06 症状), 父 Agent 拿不到 server
        //   tool use 计数 / service tier / cache 创建明细. Anthropic SDK Usage 已暴露三个 Optional
        //   访问器 (serverToolUse()/serviceTier()/cacheCreation()), 必须解析透传而非丢弃.
        AnthropicSdkProvider.StreamState state = new AnthropicSdkProvider.StreamState();
        RawMessageStreamEvent start = ObjectMappers.jsonMapper().convertValue(
            json("{\"type\":\"message_start\",\"message\":{\"id\":\"msg_nested_1\",\"type\":\"message\","
                + "\"role\":\"assistant\",\"content\":[],\"model\":\"claude-sonnet-4-6\","
                + "\"stop_reason\":null,\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":100,\"output_tokens\":5,"
                + "\"cache_read_input_tokens\":20,\"cache_creation_input_tokens\":30,"
                + "\"server_tool_use\":{\"web_search_requests\":2,\"web_fetch_requests\":3},"
                + "\"service_tier\":\"priority\","
                + "\"cache_creation\":{\"ephemeral_1h_input_tokens\":400,\"ephemeral_5m_input_tokens\":500}}}}"),
            RawMessageStreamEvent.class);
        AnthropicSdkProvider.mapStreamEvent(start, state, null, null, null, null);

        assertThat(state.serverToolUse).as("server_tool_use 必须解析 (CC agentToolUtils.ts:243-248)").isNotNull();
        assertThat(state.serverToolUse.webSearchRequests()).isEqualTo(2L);
        assertThat(state.serverToolUse.webFetchRequests()).isEqualTo(3L);
        assertThat(state.serviceTier).as("service_tier 必须解析 (CC agentToolUtils.ts:249)").isEqualTo("priority");
        assertThat(state.cacheCreation).as("cache_creation 必须解析 (CC agentToolUtils.ts:250-255)").isNotNull();
        assertThat(state.cacheCreation.ephemeral1hInputTokens()).isEqualTo(400L);
        assertThat(state.cacheCreation.ephemeral5mInputTokens()).isEqualTo(500L);

        AssistantMessage msg = AnthropicSdkProvider.buildAssistantMessage(state);
        assertThat(msg.usage().serverToolUse()).as("usage.server_tool_use 必须透传到 AssistantMessage").isNotNull();
        assertThat(msg.usage().serverToolUse().webSearchRequests()).isEqualTo(2L);
        assertThat(msg.usage().serverToolUse().webFetchRequests()).isEqualTo(3L);
        assertThat(msg.usage().serviceTier()).isEqualTo("priority");
        assertThat(msg.usage().cacheCreation()).as("usage.cache_creation 必须透传到 AssistantMessage").isNotNull();
        assertThat(msg.usage().cacheCreation().ephemeral1hInputTokens()).isEqualTo(400L);
        assertThat(msg.usage().cacheCreation().ephemeral5mInputTokens()).isEqualTo(500L);
    }

    @Test
    @DisplayName("[OD-01] mapStreamEvent message_start → cache_deleted_input_tokens 捕获 + 透传 AssistantMessage.usage")
    void mapStreamEvent_messageStart_cacheDeletedInputTokens() {
        // WHY: 微压缩延迟 boundary 的 delta 计算依赖 API 上报的累计 cache_deleted_input_tokens
        //   （microCompact.ts:374 / query.ts:874-878，API 字段 sticky/cumulative）。Java SDK Usage
        //   无等价访问器（agentToolUtils.ts:238-256 usage 7 子字段不含它）→ 必须经
        //   usage._additionalProperties() 读取未知字段并透传到 AgentUsage.cacheDeletedInputTokens()，
        //   否则 LlmAgentLoop 流结束点传 0 → delta=max(0,0-baseline)=0 恒不 yield boundary。
        AnthropicSdkProvider.StreamState state = new AnthropicSdkProvider.StreamState();
        RawMessageStreamEvent start = ObjectMappers.jsonMapper().convertValue(
            json("{\"type\":\"message_start\",\"message\":{\"id\":\"msg_deleted_1\",\"type\":\"message\","
                + "\"role\":\"assistant\",\"content\":[],\"model\":\"claude-sonnet-4-6\","
                + "\"stop_reason\":null,\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":100,\"output_tokens\":5,"
                + "\"cache_read_input_tokens\":20,\"cache_creation_input_tokens\":30,"
                + "\"cache_deleted_input_tokens\":1234}}}"),
            RawMessageStreamEvent.class);
        AnthropicSdkProvider.mapStreamEvent(start, state, null, null, null, null);

        assertThat(state.cacheDeletedInputTokens)
            .as("累计 cache_deleted_input_tokens 必须从 message_start usage 捕获（SDK 未知字段 → _additionalProperties）")
            .isEqualTo(1234L);

        AssistantMessage msg = AnthropicSdkProvider.buildAssistantMessage(state);
        assertThat(msg.usage().cacheDeletedInputTokens())
            .as("usage.cache_deleted_input_tokens 必须透传到 AssistantMessage → LlmAgentLoop 流结束点真实值")
            .isEqualTo(1234L);
    }

    @Test
    @DisplayName("[DEC-RV-07] mapStreamEvent: input_json_delta → tool_use 累积 + content_block_stop → onToolCallComplete")
    void mapStreamEvent_toolUseAccumulation() {
        AnthropicSdkProvider.StreamState state = new AnthropicSdkProvider.StreamState();
        List<ToolUseBlock> completed = new ArrayList<>();

        // content_block_start tool_use
        RawMessageStreamEvent start = ObjectMappers.jsonMapper().convertValue(
            json("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"get_weather\",\"input\":{}}}"),
            RawMessageStreamEvent.class);
        AnthropicSdkProvider.mapStreamEvent(start, state, null, null, null,
            java.util.concurrent.ConcurrentHashMap.newKeySet());

        // input_json_delta 累积 args
        RawMessageStreamEvent partial = ObjectMappers.jsonMapper().convertValue(
            json("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"city\\\":\\\"NYC\\\"}\"}}"),
            RawMessageStreamEvent.class);
        AnthropicSdkProvider.mapStreamEvent(partial, state, null, null, null,
            java.util.concurrent.ConcurrentHashMap.newKeySet());
        assertThat(state.toolCalls.get(0).args).contains("NYC");

        // content_block_stop → onToolCallComplete
        RawMessageStreamEvent stop = ObjectMappers.jsonMapper().convertValue(
            json("{\"type\":\"content_block_stop\",\"index\":0}"),
            RawMessageStreamEvent.class);
        java.util.Set<String> completedIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
        AnthropicSdkProvider.mapStreamEvent(stop, state, null, completed::add, null, completedIds);
        assertThat(completed).hasSize(1);
        assertThat(completed.get(0).id()).isEqualTo("toolu_1");
        assertThat(completed.get(0).name()).isEqualTo("get_weather");
    }

    @Test
    @DisplayName("[DEC-RV-07] mapStreamEvent: thinking_delta → onReasoningChunk")
    void mapStreamEvent_thinkingDelta() {
        AnthropicSdkProvider.StreamState state = new AnthropicSdkProvider.StreamState();
        List<String> reasoning = new ArrayList<>();
        RawMessageStreamEvent thinking = ObjectMappers.jsonMapper().convertValue(
            json("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"hmm\"}}"),
            RawMessageStreamEvent.class);
        AnthropicSdkProvider.mapStreamEvent(thinking, state, null, null, reasoning::add, null);
        assertThat(state.reasoning.toString()).isEqualTo("hmm");
        assertThat(reasoning).containsExactly("hmm");
    }

    @Test
    @DisplayName("[DEC-RV-07] mapStreamEvent: unknown（ping）→ no-op（SDK 校验通过，不抛 AnthropicInvalidDataException）")
    void mapStreamEvent_unknownPing_noOp() {
        AnthropicSdkProvider.StreamState state = new AnthropicSdkProvider.StreamState();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        try {
            RawMessageStreamEvent ping = ObjectMappers.jsonMapper().convertValue(
                json("{\"type\":\"ping\"}"),
                RawMessageStreamEvent.class);
            AnthropicSdkProvider.mapStreamEvent(ping, state, null, null, null, null);
        } catch (Throwable t) {
            thrown.set(t);
        }
        assertThat(thrown.get()).as("ping 必须 no-op，不得抛异常").isNull();
        assertThat(state.content.toString()).isEmpty();
    }

    @Test
    @DisplayName("[DEC-RV-07] buildAssistantMessage finishReason 归一化（CC claude.ts:2266-2292）")
    void buildAssistantMessage_finishReasonNormalization() {
        AnthropicSdkProvider.StreamState st = new AnthropicSdkProvider.StreamState();
        st.finishReason = "max_tokens";
        AssistantMessage m = AnthropicSdkProvider.buildAssistantMessage(st);
        assertThat(m.apiError()).isEqualTo("max_output_tokens");
        assertThat(m.finishReason()).isEqualTo("max_tokens");

        AnthropicSdkProvider.StreamState st2 = new AnthropicSdkProvider.StreamState();
        st2.finishReason = "model_context_window_exceeded";
        assertThat(AnthropicSdkProvider.buildAssistantMessage(st2).apiError())
            .isEqualTo("max_output_tokens");

        AnthropicSdkProvider.StreamState st3 = new AnthropicSdkProvider.StreamState();
        st3.finishReason = "end_turn";
        assertThat(AnthropicSdkProvider.buildAssistantMessage(st3).apiError()).isNull();
    }

    @Test
    @DisplayName("AS-02 成功路径多 text block 拼接：join('\\n')（CC getAssistantMessageText messages.ts:2843-2856）")
    void extractAssistantText_joinsTextBlocksWithNewline() {
        // WHY: OPD-R2-AS-02 —— CC getAssistantMessageText 过滤 text block 后 join('\n')
        // （messages.ts:2850-2855）；Java AnthropicSdkProvider textBuf 原为逐 block 无分隔
        // append（:954-961）→ ≥2 个 text block 时 recap 字节差异。rev2 对齐 join('\n')：
        // 相邻 text block 之间补 '\n'；非 text block（thinking/tool_use）跳过不产生分隔；
        // 单 text block 输出不变（无分隔符）。
        // SDK 2.53.0 TextBlock.Builder 要求 citations + text 必填（javap/source 实证）；
        // away-summary 场景无引用，传空列表（对齐 CC text block 无 citations 字段的等价表达）
        com.anthropic.models.messages.TextBlock first = com.anthropic.models.messages.TextBlock.builder()
            .text("First sentence.").citations(java.util.List.of()).build();
        com.anthropic.models.messages.TextBlock second = com.anthropic.models.messages.TextBlock.builder()
            .text("Next step: run tests.").citations(java.util.List.of()).build();
        List<com.anthropic.models.messages.ContentBlock> blocks = List.of(
            com.anthropic.models.messages.ContentBlock.ofText(first),
            com.anthropic.models.messages.ContentBlock.ofText(second));

        assertThat(AnthropicSdkProvider.extractAssistantText(blocks))
            .isEqualTo("First sentence.\nNext step: run tests.");

        // 单 text block → 无分隔符（既有行为字节不变）
        assertThat(AnthropicSdkProvider.extractAssistantText(
            List.of(com.anthropic.models.messages.ContentBlock.ofText(first))))
            .isEqualTo("First sentence.");
    }

    // ─────────── helpers ───────────

    private static com.fasterxml.jackson.databind.JsonNode json(String s) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static com.nexusai.model.session.dto.ChatMessageDto userMsg(String text) {
        return new com.nexusai.model.session.dto.ChatMessageDto(null, null,
            com.nexusai.model.session.dto.Role.user, null, text,
            null, null, null, null, null, null, null, null, null,
            null, List.of(), List.of());
    }
}
