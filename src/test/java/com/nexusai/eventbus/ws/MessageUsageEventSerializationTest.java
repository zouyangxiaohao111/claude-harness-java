package com.nexusai.eventbus.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tool.AgentUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [usage-push] message.usage 序列化契约测试（防字段名漂移）。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：前端靠 {@code type} 区分 message.usage（不匹配 isComplete
 * → <b>绝不退订</b> activeStreams，规避提前退订中断后续轮次流式）；消费字段 = assistantMessageId /
 * usage（嵌套 snake_case，含 cache_read/creation + decode_ms）+ context 三字段 camel（与
 * message.complete 同命名，前端 useMemo([msgs,...]) 直接重算）。字段名一旦漂移前端静默断链。
 *
 * <p>关键契约断言：
 * <ul>
 *   <li>{@code type} == {@code "message.usage"}（≠ "message.complete" → 前端 isComplete 不匹配）；</li>
 *   <li>{@code usage} 嵌套 snake_case + decode_ms；</li>
 *   <li>{@code contextWindow} / {@code contextTokensUsed} / {@code percentLeft} camel（对齐 complete）；</li>
 *   <li>{@code @JsonInclude(NON_NULL)}：usage null → 省略（不过 message.usage 事件按守卫只在 usage
 *       非 null 时发送，此处防装配误传 null）。</li>
 * </ul>
 */
@DisplayName("[usage-push] MessageUsageEvent 序列化契约")
class MessageUsageEventSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("type=message.usage + usage 嵌套 snake + context camel（字段名漂移防线）")
    void serializesCcMessageUsageShape() throws Exception {
        MessageUsageDto usageDto = MessageUsageDto.from(new AgentUsage(1000L, 500L, 100L, 200L,
            new AgentUsage.ServerToolUse(1L, 0L), "standard", new AgentUsage.CacheCreation(0L, 0L)),
            1234L);
        MessageUsageEvent evt = MessageUsageEvent.of("sess-1", "msg-u", "msg-a", usageDto,
            1_048_576L, 1300L, 99);

        String json = mapper.writeValueAsString(evt);
        JsonNode root = mapper.readTree(json);

        // type = message.usage（≠ message.complete → 前端 isComplete 天然不匹配，不提前退订）
        assertThat(root.get("type").asText())
            .as("type 必须为 message.usage（复用 complete 名会触发前端 onSessionDone 提前退订）")
            .isEqualTo("message.usage");
        assertThat(root.get("assistantMessageId").asText())
            .as("assistantMessageId = turnAssistantId（前端块 id 同源）").isEqualTo("msg-a");
        assertThat(root.get("sessionId").asText()).isEqualTo("sess-1");
        assertThat(root.get("userMessageId").asText()).isEqualTo("msg-u");

        // usage 嵌套 snake_case（MessageUsageDto 复用 complete 契约）
        JsonNode usage = root.get("usage");
        assertThat(usage).as("usage 对象存在").isNotNull();
        assertThat(usage.get("input_tokens").asLong()).isEqualTo(1000L);
        assertThat(usage.get("output_tokens").asLong()).isEqualTo(500L);
        assertThat(usage.get("cache_read_input_tokens").asLong()).isEqualTo(200L);
        assertThat(usage.get("cache_creation_input_tokens").asLong()).isEqualTo(100L);
        assertThat(usage.get("decode_ms").asLong()).as("usage.decode_ms snake（t/s 前端展示）").isEqualTo(1234L);

        // 上下文三字段 camel（对齐 complete 事件命名，前端实时重算缓存%/上下文条）
        assertThat(root.get("contextWindow").asLong()).isEqualTo(1_048_576L);
        assertThat(root.get("contextTokensUsed").asLong()).isEqualTo(1300L);
        assertThat(root.get("percentLeft").asInt()).isEqualTo(99);
    }

    @Test
    @DisplayName("@JsonInclude(NON_NULL)：usage null → 省略；percentLeft null → 省略")
    void omitsNullFields() throws Exception {
        MessageUsageEvent evt = MessageUsageEvent.of("sess-1", "msg-u", "msg-a", null, 0L, 0L, null);
        String json = mapper.writeValueAsString(evt);
        JsonNode root = mapper.readTree(json);
        assertThat(root.has("usage")).as("usage null → NON_NULL 省略").isFalse();
        assertThat(root.has("percentLeft")).as("percentLeft null → NON_NULL 省略").isFalse();
        // type 恒在（StreamEvent 基类字段）
        assertThat(root.get("type").asText()).isEqualTo("message.usage");
    }
}
