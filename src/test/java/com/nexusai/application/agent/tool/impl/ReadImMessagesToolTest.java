package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.domain.im_message.ImMessageQueryService;
import com.nexusai.model.im_message.ImMessageEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ReadImMessages} 工具层验证（无 DB：查询服务用替身）。
 *
 * <h2>WHY 要单独验工具层</h2>
 * 三个坑都在这一层：
 * <ul>
 *   <li><b>返回 Map 会落成空 content</b>（本仓踩过两次）⇒ 必须返回 <b>String</b>；</li>
 *   <li>{@code platform} / {@code origin} 必须真的出现在输出里（用户明确要求）；</li>
 *   <li>媒体消息的 {@code mediaJson} 不能直接给模型（太长）⇒ 只给占位。</li>
 * </ul>
 */
class ReadImMessagesToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("返回必须是 JSON 字符串（不是 Map）：Map 落库会变成空 content")
    void returnsJsonStringNotMap() throws Exception {
        ReadImMessagesTool tool = new ReadImMessagesTool();
        tool.setQueryService(stub(new ImMessageQueryService.ImMessageReadResult(
            "imch-1", "feishu", "产品需求群", "imc-1",
            List.of(entry("om_1", "在的", "text", null)),
            List.of())));

        AgentToolResult<?> result = tool.execute(call("{\"chatId\":\"imch-1\"}"));
        assertThat(result.data()).as("必须是 String（落库链路只对 String 生成 content）")
            .isInstanceOf(String.class);

        JsonNode out = JSON.readTree(String.valueOf(result.data()));
        assertThat(out.path("chatId").asText()).isEqualTo("imch-1");
        assertThat(out.path("platform").asText()).as("用户要求能看出是哪个渠道").isEqualTo("feishu");
        assertThat(out.path("chatName").asText()).isEqualTo("产品需求群");
        assertThat(out.path("count").asInt()).isEqualTo(1);

        JsonNode m = out.path("messages").get(0);
        assertThat(m.path("sentAt").asText()).isEqualTo("2026-11-18T10:00:00.000+08:00");
        assertThat(m.path("sender").asText()).isEqualTo("张三");
        assertThat(m.path("senderId").asText()).isEqualTo("ou_1");
        assertThat(m.path("text").asText()).isEqualTo("在的");
        assertThat(m.path("msgType").asText()).isEqualTo("text");
        assertThat(m.path("direction").asText()).isEqualTo("in");
        assertThat(m.path("origin").asText()).as("用户要求能看出本地/官方").isEqualTo("official");
        assertThat(m.path("messageId").asText()).isEqualTo("om_1");
        assertThat(m.has("mediaJson")).as("媒体引用不能直接暴露给模型").isFalse();
    }

    @Test
    @DisplayName("媒体消息只给占位文字（[图片] / [文件: 需求.pdf]）")
    void mediaMessagesGetPlaceholders() throws Exception {
        ReadImMessagesTool tool = new ReadImMessagesTool();
        tool.setQueryService(stub(new ImMessageQueryService.ImMessageReadResult(
            "imch-1", "feishu", "群", "imc-1",
            List.of(
                entry("om_1", null, "image", "[{\"kind\":\"image\",\"fileName\":null,\"ref\":\"img_1\"}]"),
                entry("om_2", null, "file",
                    "[{\"kind\":\"file\",\"fileName\":\"需求.pdf\",\"ref\":\"file_1\"}]")),
            List.of())));

        AgentToolResult<?> result = tool.execute(call("{\"chatId\":\"imch-1\"}"));
        JsonNode out = JSON.readTree(String.valueOf(result.data()));
        assertThat(out.path("messages").get(0).path("text").asText()).isEqualTo("[图片]");
        assertThat(out.path("messages").get(1).path("text").asText()).isEqualTo("[文件: 需求.pdf]");
    }

    @Test
    @DisplayName("notes 透传（企微 7 天窗口这类说明必须给模型看到）")
    void notesArePassedThrough() throws Exception {
        ReadImMessagesTool tool = new ReadImMessagesTool();
        tool.setQueryService(stub(new ImMessageQueryService.ImMessageReadResult(
            "imch-1", "wecom", "张三", "imc-1", List.of(),
            List.of("企微仅能补拉最近 7 天（begin_time 官方限制），更早的消息只能来自本地记录"))));

        AgentToolResult<?> result = tool.execute(call("{\"chatId\":\"imch-1\"}"));
        JsonNode out = JSON.readTree(String.valueOf(result.data()));
        assertThat(out.path("notes").get(0).asText()).contains("7 天");
    }

    @Test
    @DisplayName("chatId 缺失 ⇒ 错误结果必须以 \"Error: \" 开头（否则模型会当成功）")
    void missingChatIdReturnsPrefixedError() {
        ReadImMessagesTool tool = new ReadImMessagesTool();
        tool.setQueryService(stub(new ImMessageQueryService.ImMessageReadResult(
            "imch-1", "feishu", "群", "imc-1", List.of(), List.of())));

        AgentToolResult<?> result = tool.execute(call("{}"));
        assertThat(result.data()).isInstanceOf(String.class);
        assertThat(String.valueOf(result.data())).startsWith("Error: ");
    }

    @Test
    @DisplayName("schema：chatId 必填、additionalProperties=false、limit 有上限")
    void schemaShape() {
        ReadImMessagesTool tool = new ReadImMessagesTool();
        JsonNode schema = tool.inputSchema();
        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.path("required").get(0).asText()).isEqualTo("chatId");
        assertThat(schema.path("properties").has("since")).isTrue();
        assertThat(schema.path("properties").has("until")).isTrue();
        assertThat(schema.path("properties").has("senderId")).isTrue();
        assertThat(schema.path("properties").path("limit").path("type").asText()).isEqualTo("integer");
    }

    @Test
    @DisplayName("只读 + 并发安全")
    void readOnlyAndConcurrencySafe() {
        ReadImMessagesTool tool = new ReadImMessagesTool();
        JsonNode input = JSON.createObjectNode();
        assertThat(tool.isReadOnly(input)).isTrue();
        assertThat(tool.isConcurrencySafe(input)).isTrue();
    }

    // ───────────────────────── 辅助 ─────────────────────────

    private static ImMessageEntry entry(String messageId, String content, String msgType, String mediaJson) {
        return new ImMessageEntry("row-1", "imc-1", "feishu", "oc_1", messageId,
            "ou_1", "张三", msgType, content, mediaJson,
            "2026-11-18T10:00:00.000+08:00", "in", "official");
    }

    /** 查询服务替身：只回固定结果（工具层只关心「怎么把结果讲清楚」）。 */
    private static ImMessageQueryService stub(ImMessageQueryService.ImMessageReadResult result) {
        return new ImMessageQueryService() {
            @Override
            public ImMessageReadResult readWithNotes(String chatId, String sinceIso, String untilIso,
                                                     String senderId, int limit, boolean refresh) {
                return result;
            }

            @Override
            public List<ImMessageEntry> read(String chatId, String sinceIso, String untilIso,
                                             String senderId, int limit, boolean refresh) {
                return new ArrayList<>(result.messages());
            }
        };
    }

    private static ToolUseBlock call(String inputJson) {
        try {
            ObjectNode in = (ObjectNode) JSON.readTree(inputJson);
            return new ToolUseBlock("t-1", ReadImMessagesTool.NAME, in);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
