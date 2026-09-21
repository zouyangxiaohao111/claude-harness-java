package com.nexusai.application.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SendUserMessage 的「UI 面载荷」落库/推送判定 · 对齐 CC「数据 / 给模型看的文本」分离。
 *
 * <p><b>WHY（意图验证 · CLAUDE.md 规则九）</b>：CC 里 SendUserMessage 有两张脸 ——
 * 「数据」= 工具 output {@code {message, attachments:[{path,size,isImage}], sentAt}}
 * （BriefTool.ts:42-63，渲染给用户）与「给模型看的文本」= 一句人类文案
 * {@code 'Message delivered to user.'}（BriefTool.ts:175-183，进 tool_result）。
 *
 * <p>本仓的结构对应：模型面文本走 {@code messages(role=tool).content}（本测试不改它）；
 * UI 面载荷走 {@code tool_calls.result}（本测试钉住的判定）。
 * <b>若这两条混了</b>（把 JSON 写进模型面，或把人类文案写进 UI 面）⇒ 模型看到 JSON
 * （token 爆炸 + 语义污染）或用户看到「Message delivered to user.」而看不到答案 ——
 * 后者正是改造前的实际形态。
 *
 * <p>无 Spring 上下文（纯静态方法；不触 DB —— 本仓 {@code @SpringBootTest} 会迁移用户真库）。
 */
@DisplayName("SendUserMessage UI 面载荷判定（tool_calls.result）· CC 数据/文本分离")
class ChatServiceToolResultUiPayloadTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** tool_result 消息（structuredOutput = 工具 output data，由 ToolResultApplier 挂载）。 */
    private static ChatMessageDto toolMsg(Map<String, Object> structuredOutput) {
        return new ChatMessageDto(
            "msg-1", "sess-1", Role.tool, "tool",
            "Message delivered to user.",
            null, null, null, null, null, null,
            OffsetDateTime.now(), "t-1", "a-1",
            null, List.of(), List.of(), structuredOutput);
    }

    private static Map<String, Object> briefData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("message", "## 验收结果\n全部通过");
        data.put("sentAt", "2026-09-20T00:00:00Z");
        data.put("attachments", List.of(Map.of("path", "/tmp/a.png", "size", 4096, "isImage", true)));
        return data;
    }

    @Test
    @DisplayName("SendUserMessage：result = 结构化 payload JSON（message/attachments/sentAt 齐备）")
    void sendUserMessageCarriesStructuredPayload() throws Exception {
        String modelFacing = "Message delivered to user. (1 attachment included)";

        String payload = ChatService.toolResultUiPayload(
            "SendUserMessage", toolMsg(briefData()), modelFacing);

        JsonNode node = JSON.readTree(payload);
        assertThat(node.path("message").asText()).as("答案原文必须在 UI 载荷里").isEqualTo("## 验收结果\n全部通过");
        assertThat(node.path("sentAt").asText()).isEqualTo("2026-09-20T00:00:00Z");
        JsonNode att = node.path("attachments").get(0);
        assertThat(att.path("path").asText()).isEqualTo("/tmp/a.png");
        assertThat(att.path("size").asLong()).isEqualTo(4096L);
        assertThat(att.path("isImage").asBoolean()).isTrue();
        // 判别点：UI 载荷里不得混入模型面文案（两条通道必须是分开的两份数据）
        assertThat(payload).doesNotContain(modelFacing);
    }

    @Test
    @DisplayName("旧名 Brief 同样识别（历史 transcript 别名）")
    void legacyBriefAliasAlsoCarriesPayload() {
        String payload = ChatService.toolResultUiPayload(
            "Brief", toolMsg(briefData()), "Message delivered to user.");
        assertThat(payload).contains("\"message\"").contains("验收结果");
    }

    @Test
    @DisplayName("不破坏别的工具：非本工具 → 原样人类文案（截断语义不变）")
    void otherToolsUnchanged() {
        assertThat(ChatService.toolResultUiPayload("Bash", toolMsg(briefData()), "line-0\nline-1"))
            .isEqualTo("line-0\nline-1");
        // 超长沿用既有 5000 截断（原行为）
        String longText = "x".repeat(6000);
        String out = ChatService.toolResultUiPayload("Read", toolMsg(briefData()), longText);
        assertThat(out).hasSizeLessThan(6000).endsWith("... (truncated)");
    }

    @Test
    @DisplayName("回落链：结构化载荷缺失 / 记录查不到 → 人类文案（前端按 arguments 兜底，不空白）")
    void fallsBackWhenStructuredPayloadMissing() {
        // 本工具但 structuredOutput 未接线（历史行 / 非本路径产生）
        assertThat(ChatService.toolResultUiPayload("SendUserMessage", toolMsg(null), "Message delivered to user."))
            .isEqualTo("Message delivered to user.");
        // structuredOutput 存在但非本工具协议（无 message 键）
        Map<String, Object> other = Map.of("summary", "some other tool payload");
        assertThat(ChatService.toolResultUiPayload("SendUserMessage", toolMsg(other), "Message delivered to user."))
            .isEqualTo("Message delivered to user.");
        // tool_calls 记录查不到（toolName=null）
        assertThat(ChatService.toolResultUiPayload(null, toolMsg(briefData()), "Message delivered to user."))
            .isEqualTo("Message delivered to user.");
    }
}
