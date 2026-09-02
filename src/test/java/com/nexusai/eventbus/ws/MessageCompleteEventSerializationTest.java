package com.nexusai.eventbus.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.cost.CostTracker;
import com.nexusai.application.agent.tool.AgentUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [V-TOK] message.complete 序列化契约测试（防字段名漂移）。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：前端按 CC result 事件字段名消费
 * （total_cost_usd / duration_ms / num_turns snake + modelUsage camel + usage 嵌套 snake）——
 * 字段名是前后端契约，一旦漂移（如 modelUsage 被全局命名策略改成 model_usage）前端静默断链。
 * 本测试锁定序列化后的 JSON 字段名（含 NON_NULL 省略 null），与 待前端对接.md §42 契约表一致。
 */
@DisplayName("[V-TOK] MessageCompleteEvent 序列化契约")
class MessageCompleteEventSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("字段名对齐 CC result：total_cost_usd/duration_ms/num_turns snake + modelUsage/context camel + usage 嵌套 snake")
    void serializesCcResultFieldNames() throws Exception {
        Map<String, CostTracker.ModelUsage> modelUsage = new LinkedHashMap<>();
        modelUsage.put("deepseek-v4-flash",
            new CostTracker.ModelUsage(1000, 500, 200, 100, 1, 0.0123, 1_048_576, 384_000));
        // [B7-R9] decode_ms 净新增字段（非 CC 对齐）：usage.decode_ms = 后端测输出解码耗时 ms
        MessageUsageDto usageDto = MessageUsageDto.from(new AgentUsage(1000L, 500L, 100L, 200L,
            new AgentUsage.ServerToolUse(1L, 0L), "standard", new AgentUsage.CacheCreation(0L, 0L)),
            1234L);
        MessageCompleteEvent evt = new MessageCompleteEvent("sess-1", "msg-u", "msg-a",
            "回复", "思考", "stop", 1000, 500, null,
            usageDto, 0.0123, modelUsage, 12345L, 3, 1_048_576L, 1300L, 99);

        String json = mapper.writeValueAsString(evt);
        JsonNode root = mapper.readTree(json);

        // 顶层 snake_case 字段（@JsonProperty 显式映射）
        assertThat(root.has("total_cost_usd")).as("total_cost_usd snake_case").isTrue();
        assertThat(root.get("total_cost_usd").asDouble()).as("total_cost_usd 值=元").isEqualTo(0.0123);
        assertThat(root.has("duration_ms")).as("duration_ms snake_case").isTrue();
        assertThat(root.get("duration_ms").asLong()).isEqualTo(12345);
        assertThat(root.has("num_turns")).as("num_turns snake_case").isTrue();
        assertThat(root.get("num_turns").asInt()).isEqualTo(3);

        // usage 嵌套 snake_case（MessageUsageDto）
        JsonNode usage = root.get("usage");
        assertThat(usage).as("usage 对象存在").isNotNull();
        assertThat(usage.get("input_tokens").asLong()).as("usage.input_tokens snake").isEqualTo(1000L);
        assertThat(usage.get("output_tokens").asLong()).as("usage.output_tokens snake").isEqualTo(500L);
        assertThat(usage.get("cache_read_input_tokens").asLong()).isEqualTo(200L);
        assertThat(usage.get("cache_creation_input_tokens").asLong()).isEqualTo(100L);
        assertThat(usage.get("server_tool_use").get("web_search_requests").asLong()).isEqualTo(1L);
        assertThat(usage.get("server_tool_use").get("web_fetch_requests").asLong()).isEqualTo(0L);
        assertThat(usage.get("service_tier").asText()).isEqualTo("standard");
        // [B7-R9] decode_ms snake_case 出站（前端 t/s = output_tokens*1000/decode_ms）
        assertThat(usage.get("decode_ms").asLong()).as("usage.decode_ms snake").isEqualTo(1234L);

        // modelUsage 值 camelCase（CostTracker.ModelUsage 原样序列化）
        JsonNode mu = root.get("modelUsage");
        assertThat(mu.has("deepseek-v4-flash")).as("modelUsage 键 = 模型名").isTrue();
        JsonNode bucket = mu.get("deepseek-v4-flash");
        assertThat(bucket.has("inputTokens")).as("modelUsage 值 camelCase inputTokens").isTrue();
        assertThat(bucket.has("costUSD")).as("modelUsage 值 camelCase costUSD").isTrue();
        assertThat(bucket.has("contextWindow")).isTrue();
        assertThat(bucket.get("inputTokens").asLong()).isEqualTo(1000L);

        // 上下文三字段 camel（常驻每轮推）
        assertThat(root.has("contextWindow")).isTrue();
        assertThat(root.get("contextWindow").asLong()).isEqualTo(1_048_576L);
        assertThat(root.has("contextTokensUsed")).isTrue();
        assertThat(root.get("contextTokensUsed").asLong()).isEqualTo(1300L);
        assertThat(root.has("percentLeft")).isTrue();
        assertThat(root.get("percentLeft").asInt()).isEqualTo(99);

        // 块 3 现有投影保留
        assertThat(root.has("inputTokens")).as("块 3 inputTokens 投影保留").isTrue();
        assertThat(root.has("outputTokens")).as("块 3 outputTokens 投影保留").isTrue();
    }

    @Test
    @DisplayName("@JsonInclude(NON_NULL)：usage/modelUsage/percentLeft null → 省略")
    void omitsNullFields() throws Exception {
        // 9 参构造器 delegate → 新字段全 null（净新增字段默认省略，向后兼容旧前端）
        MessageCompleteEvent evt = new MessageCompleteEvent("sess-1", "msg-u", "msg-a",
            "回复", "思考", "stop", null, null, null);
        String json = mapper.writeValueAsString(evt);
        JsonNode root = mapper.readTree(json);
        assertThat(root.has("usage")).as("usage null → NON_NULL 省略").isFalse();
        assertThat(root.has("modelUsage")).as("modelUsage null → NON_NULL 省略").isFalse();
        assertThat(root.has("percentLeft")).as("percentLeft null → NON_NULL 省略").isFalse();
        // 既有字段不受影响
        assertThat(root.get("content").asText()).isEqualTo("回复");
        assertThat(root.get("reasoning").asText()).isEqualTo("思考");
    }
}
