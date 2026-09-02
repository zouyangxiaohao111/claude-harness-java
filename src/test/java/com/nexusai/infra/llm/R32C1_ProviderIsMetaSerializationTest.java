package com.nexusai.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nexusai.application.agent.permission.RetryMessageFactory;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [Session C-2 Phase 3 Task B] 验证 {@code isMeta=true} ChatMessageDto 在 Provider
 * 序列化层的行为, 对齐 CC 真源 {@code Open-ClaudeCode/src/services/api/claude.ts:1340}
 * 创建的 {@code createUserMessage({ content, isMeta: true })} 消息流向 Provider 的方式.
 *
 * <p><b>CC 真源行为</b> (Pattern #9 — grep 实测, 不信注释):
 * <ul>
 *   <li>{@code isMeta} 是 {@code UserMessage} 上的内部标志 (CC utils/messages.ts:460-501),
 *       只影响 REPL 渲染/UUID 派生/[id:] 标签追加, 不影响 Provider 序列化</li>
 *   <li>{@code normalizeMessagesForAPI} (CC utils/messages.ts:1989-2137) 把 isMeta 消息
 *       与普通 user 消息同等对待, content 完整送入 API</li>
 *   <li>CC messagesForAPI 数组中的 isMeta 消息包含真实 content (例如
 *       {@code claude.ts:1340} 的 {@code <available-deferred-tools>} 块), Provider
 *       看到的只是普通 user role message</li>
 * </ul>
 *
 * <p><b>Java 等价行为</b>:
 * <ul>
 *   <li>[P-13/F29 2026-08-15] {@link ChatMessageDto#isMeta()} 移除 {@code @JsonIgnore}
 *       （ChatMessageDto.java:84-86 注释实证），outbound JSON 现含 isMeta 字段
 *       （前端按 isMeta 隐藏元消息，待前端对接.md F29 登记）</li>
 *   <li>AnthropicSdkProvider / OpenAiSdkProvider 序列化时 isMeta=true 的消息与普通
 *       user 消息完全等价: {@code role=user, content=<text>}</li>
 *   <li>Provider 序列化的是 SDK 类型（不含 Java DTO 的 isMeta 字段）→ Provider 层
 *       payload 仍无 isMeta（测试 3/4 断言保持"payload 不含 isMeta"不变）</li>
 * </ul>
 *
 * <p><b>本测试覆盖</b> (4 项):
 * <ol>
 *   <li>{@code chatMessageDto_isMetaTrue_serializesWithIsMeta} — ObjectMapper 序列化
 *       ChatMessageDto(isMeta=true) → JSON 含 {@code isMeta} 字段（[P-13/F29] 出站新契约）</li>
 *   <li>{@code retryMessageFactory_isMetaTrue_serializesWithIsMeta} —
 *       {@link RetryMessageFactory#createRetryMessage(String)} 输出(isMeta=true)
 *       序列化为 JSON 含 {@code isMeta}, 含 retry 文本</li>
 *   <li>{@code anthropicProvider_isMetaTrueUser_serializesAsUserRole} —
 *       AnthropicSdkProvider.buildMessageParams 把 isMeta=true 的 user 消息作为
 *       {@code role=user} 序列化, 不含 isMeta 字段</li>
 *   <li>{@code openAiProvider_isMetaTrueUser_serializesAsUserRole} —
 *       OpenAiProvider.buildRequestBody 同样把 isMeta=true 的 user 消息作为
 *       {@code role=user} 序列化, 不含 isMeta 字段</li>
 * </ol>
 *
 * @see ChatMessageDto#isMeta()
 * @see RetryMessageFactory#createRetryMessage(String)
 * @see AnthropicSdkProvider#buildMessageParams(String, String, java.util.List, com.fasterxml.jackson.databind.node.ArrayNode, Integer, TaskBudgetParam, String, com.nexusai.infra.llm.LlmProvider.ChatRequestOptions.OutputFormat, Boolean)
 * @see OpenAiSdkProvider
 */
class R32C1_ProviderIsMetaSerializationTest {

    private static final ObjectMapper JSON = new ObjectMapper()
        // [WHY] RetryMessageFactory.createRetryMessage 内部用 OffsetDateTime.now() 填
        //   ChatMessageDto.createdAt, 默认 ObjectMapper 不支持 java.time 类型, 关闭
        //   "require handlers for JSR-310" 让 ISO-8601 字符串直接走 Object#toString
        //   即可. 测试只关心 isMeta 是否被剥离, 不关心 createdAt 序列化格式.
        .disable(MapperFeature.REQUIRE_HANDLERS_FOR_JAVA8_TIMES)
        .registerModule(new JavaTimeModule());
    private static final String RETRY_TEXT =
        "The PermissionDenied hook indicated this command is now approved. You may retry it if you would like.";

    /**
     * 构造 {@code role=user} 的 isMeta=true 消息, 对齐 CC
     * {@code createUserMessage({content, isMeta:true})} 的最小形态.
     */
    private ChatMessageDto isMetaUserMessage(String content) {
        return new ChatMessageDto(
            "msg-id-1",
            "session-id-1",
            Role.user,
            null,
            content,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            null,
            true                  // isMeta = true
        );
    }

    /**
     * 构造普通 user 消息 (isMeta=false), 用作 mixed-list 对照, 验证 isMeta 字段
     * 是否仅从 isMeta=true 的消息被剥离.
     */
    private ChatMessageDto regularUserMessage(String content) {
        return new ChatMessageDto(
            "msg-id-2",
            "session-id-1",
            Role.user,
            null,
            content,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            null,
            false                 // isMeta = false
        );
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 1. ChatMessageDto.isMeta=true → ObjectMapper JSON 含 isMeta 字段 ([P-13/F29] 出站新契约)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ChatMessageDto(isMeta=true) → ObjectMapper JSON 含 isMeta 字段 ([P-13/F29] isMeta 出站新契约)")
    void chatMessageDto_isMetaTrue_serializesWithIsMeta() throws Exception {
        ChatMessageDto msg = isMetaUserMessage(RETRY_TEXT);

        // [P-13/F29 2026-08-15] ChatMessageDto.isMeta 移除 @JsonIgnore —— CC isMeta 参与消息
        // 序列化（前端按 isMeta 隐藏元消息），outbound JSON 必须含 isMeta 字段.
        String json = JSON.writeValueAsString(msg);
        JsonNode root = JSON.readTree(json);

        assertNotNull(root, "ChatMessageDto 序列化结果不应为 null");
        assertTrue(root.has("isMeta"),
            "[P-13/F29 2026-08-15] isMeta 出站新契约：ChatMessageDto 移除 @JsonIgnore，"
                + "outbound JSON 含 isMeta 字段（前端按 isMeta 隐藏元消息，待前端对接.md F29）\n"
                + "实际 JSON: " + json);
        assertTrue(root.has("role"),
            "序列化 JSON 必须保留 role 字段");
        assertEquals("user", root.get("role").asText());
        assertEquals(RETRY_TEXT, root.get("content").asText());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 2. RetryMessageFactory 输出 (isMeta=true) → JSON 含 isMeta ([P-13/F29] 出站新契约)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("RetryMessageFactory 输出 (isMeta=true) → JSON 含 isMeta 字段, 含 retry 文本 ([P-13/F29] 出站新契约)")
    void retryMessageFactory_isMetaTrue_serializesWithIsMeta() throws Exception {
        ChatMessageDto retryMsg = RetryMessageFactory.createRetryMessage("session-c-2-test");

        // [P-13/F29 2026-08-15] RetryMessageFactory 输出的 ChatMessageDto isMeta=true，
        // 移除 @JsonIgnore 后 outbound JSON 必须含 isMeta 字段（前端按 isMeta 隐藏元消息）.
        String json = JSON.writeValueAsString(retryMsg);
        JsonNode root = JSON.readTree(json);

        assertTrue(root.has("isMeta"),
            "[P-13/F29 2026-08-15] isMeta 出站新契约：ChatMessageDto 移除 @JsonIgnore，"
                + "outbound JSON 含 isMeta 字段（前端按 isMeta 隐藏元消息，待前端对接.md F29）\n"
                + "实际 JSON: " + json);
        assertEquals(Role.user, retryMsg.role());
        assertEquals(RETRY_TEXT, retryMsg.content());
        assertTrue(retryMsg.isMeta(),
            "RetryMessageFactory 必须输出 isMeta=true ([P-13/F29] 后参与序列化)");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 3. AnthropicSdkProvider: isMeta=true user 消息作为 role=user 序列化
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AnthropicSdkProvider: isMeta=true user 消息 → role=user (无 isMeta 字段)")
    void anthropicProvider_isMetaTrueUser_serializesAsUserRole() throws Exception {
        // 混合列表: 普通 user + isMeta=true user (即 retry 消息), 模拟 retry hook
        // 触发后的真实场景: AgentState.messages 包含用户原始输入 + 重试元消息.
        List<ChatMessageDto> history = List.of(
            regularUserMessage("Original user request"),
            isMetaUserMessage(RETRY_TEXT)
        );

        // [DEC-RV-07 REWORK-2] 生产 SDK wire：buildMessageParams → _body() 序列化
        com.anthropic.models.messages.MessageCreateParams params = AnthropicSdkProvider.buildMessageParams(
            "claude-test", null, history, null, null, null, null, null, null);
        String body = com.anthropic.core.ObjectMappers.jsonMapper()
            .writeValueAsString(params._body());
        JsonNode root = JSON.readTree(body);
        JsonNode messages = root.get("messages");

        assertNotNull(messages, "Provider payload 必须含 messages 数组");
        assertEquals(2, messages.size(),
            "混合列表 [regular + isMeta] 必须 2 条消息, 不可因 isMeta 被过滤");

        // 关键断言 1: 两条消息都是 role=user
        assertEquals("user", messages.get(0).get("role").asText(),
            "普通 user 消息 role=user");
        assertEquals("user", messages.get(1).get("role").asText(),
            "isMeta=true 的 user 消息 role=user (CC 等价: isMeta 不改变 Provider 看到的 role)");

        // 关键断言 2: 两条消息 content 完整
        assertEquals("Original user request", messages.get(0).get("content").asText());
        // [ODF-B3] 末条为 messages 通道 cache marker → 字符串 content 转数组（对齐 CC
        //   addCacheBreakpoints claude.ts:3078-3091 / userMessageToMessageParam claude.ts:594-607）
        assertEquals(RETRY_TEXT, messages.get(1).get("content").get(0).get("text").asText());
        assertEquals("ephemeral", messages.get(1).get("content").get(0).get("cache_control").get("type").asText());

        // 关键断言 3: 整条 payload 任何位置都不出现 "isMeta" 字段 (CC isMeta 是 internal-only)
        String bodyLower = body.toLowerCase();
        assertFalse(bodyLower.contains("ismeta"),
            "AnthropicSdkProvider payload 任何位置都不应出现 isMeta 字段 (对齐 CC internal-only)\n"
                + "实际 body: " + body);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 4. OpenAiSdkProvider: isMeta=true user 消息作为 role=user 序列化 ([OpenAI-SDK 迁移])
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("OpenAiSdkProvider: isMeta=true user 消息 → role=user (无 isMeta 字段)")
    void openAiProvider_isMetaTrueUser_serializesAsUserRole() throws Exception {
        List<ChatMessageDto> history = List.of(
            regularUserMessage("Original user request"),
            isMetaUserMessage(RETRY_TEXT)
        );

        // [OpenAI-SDK 迁移] 旧 OpenAiProvider.buildRequestBody 已删除 → 生产 SDK wire：
        //   OpenAiSdkProvider.buildSdkMessages → ObjectMappers 序列化（与 R32B9 同模式）
        java.util.List<com.openai.models.ChatCompletionMessageParam> msgs =
            OpenAiSdkProvider.buildSdkMessages(history);
        String body = com.openai.core.ObjectMappers.jsonMapper().writeValueAsString(msgs);
        JsonNode root = JSON.readTree(body);

        assertNotNull(root, "Provider payload 必须含 messages 数组");
        assertEquals(2, root.size(),
            "OpenAI Provider 混合列表 [regular + isMeta] 必须 2 条消息, 不可因 isMeta 被过滤");

        assertEquals("user", root.get(0).get("role").asText());
        assertEquals("user", root.get(1).get("role").asText());
        assertEquals("Original user request", root.get(0).get("content").asText());
        assertEquals(RETRY_TEXT, root.get(1).get("content").asText());

        String bodyLower = body.toLowerCase();
        assertFalse(bodyLower.contains("ismeta"),
            "OpenAiSdkProvider payload 任何位置都不应出现 isMeta 字段 (对齐 CC internal-only)\n"
                + "实际 body: " + body);
    }
}