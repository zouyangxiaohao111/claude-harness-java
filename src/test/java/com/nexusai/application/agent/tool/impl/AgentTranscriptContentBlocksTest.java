package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.subagent.AgentMessage;
import com.nexusai.application.agent.subagent.AgentTranscript;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.ContentBlockDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [content-blocks V331] 子代理 transcript「一块一行 + apiBlockIndex」写入与解析。
 *
 * <p><b>WHY</b>：对齐 CC assistant 行结构（同一 message.id 跨 N 行，每行一个块 + apiBlockIndex）。
 * 判据（派单 d）：jsonl 里一条三块 assistant 消息必须落 3 行、索引 0/1/2；
 * 读侧（getAgentTranscript）不得把块续行当成独立消息（会话链/模型面零影响）；
 * 且 SubagentExecutor.chatMessageToMap 与主库 content_blocks 同构（同一 ContentBlockDto）。
 */
@DisplayName("[content-blocks V331] 子代理 transcript 一块一行")
class AgentTranscriptContentBlocksTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    private static ChatMessageDto assistantWithBlocks(String id, List<ContentBlockDto> blocks) {
        List<ToolCallDto> toolCalls = new ArrayList<>();
        for (ContentBlockDto b : blocks) {
            if ("tool_use".equals(b.type())) {
                toolCalls.add(new ToolCallDto(b.id(), b.name(), b.input(), null, null));
            }
        }
        return new ChatMessageDto(
            id, "sess-tx", Role.assistant, "assistant", "text block", "thinking body",
            toolCalls, null, null, null, null, OffsetDateTime.now(), null, null,
            null, null, null, null, false, false)
            .withBlocks(blocks);
    }

    @Test
    @DisplayName("d. chatMessageToMap 带 blocks；jsonl 一块一行；读侧仍是一条消息")
    void oneBlockPerLine() throws Exception {
        String sessionId = "sess-tx-1";
        String agentId = "agent-tx-1";
        String assistantId = "msg-tx-a1";

        List<ContentBlockDto> blocks = List.of(
            ContentBlockDto.toolUse("call_a", "Bash", "{}", 9),
            ContentBlockDto.text("text block", 9),
            ContentBlockDto.toolUse("call_b", "Read", "{}", 9));
        ChatMessageDto assistant = assistantWithBlocks(assistantId, blocks);

        ChatMessageDto user = new ChatMessageDto(
            "msg-tx-u1", sessionId, Role.user, "user", "do it", null,
            null, null, null, null, null, OffsetDateTime.now(), null, null,
            null, null, null, null, false, false);

        Map<String, Object> assistantMap = SubagentExecutor.chatMessageToMap(assistant);
        assertNotNull(assistantMap.get("blocks"), "chatMessageToMap \u5fc5\u987b\u5199 blocks");
        assertEquals(3, ((List<?>) assistantMap.get("blocks")).size());

        AgentTranscript.recordSidechainTranscript(tempDir, sessionId, agentId,
            List.of(SubagentExecutor.chatMessageToMap(user), assistantMap));

        Path path = AgentTranscript.getTranscriptPath(tempDir, sessionId, agentId);
        assertTrue(Files.exists(path), "transcript \u6587\u4ef6\u5fc5\u987b\u5b58\u5728");
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        assertEquals(4, lines.size(), "user 1 \u884c + assistant 3 \u884c\uff08\u4e00\u5757\u4e00\u884c\uff09");

        int assistantLines = 0;
        List<Integer> indexes = new ArrayList<>();
        for (String line : lines) {
            JsonNode node = JSON.readTree(line);
            if (node.path("blockContinuation").asBoolean(false)) {
                assistantLines++;
                indexes.add(node.path("apiBlockIndex").asInt());
                assertEquals(assistantId, node.path("parentUuid").asText(), "\u7eed\u884c\u6302\u5230\u9996\u884c");
                assertEquals(true, node.path("isSidechain").asBoolean());
            }
        }
        assertEquals(2, assistantLines, "\u4e09\u5757 \u21d2 \u4e24\u6761\u7eed\u884c");
        assertEquals(List.of(1, 2), indexes, "\u7eed\u884c\u5e26\u8fde\u7eed apiBlockIndex");

        // 读侧：续行不是独立消息（\u4f1a\u8bdd\u94fe\u91cd\u5efa\u4e0e\u6a21\u578b\u9762\u4e0d\u53d7\u5f71\u54cd）
        List<AgentMessage> messages = AgentTranscript.getAgentTranscript(tempDir, sessionId, agentId)
            .orElseThrow().messages();
        assertEquals(2, messages.size(), "\u8bfb\u4fa7\u53ea\u5e94\u770b\u5230 user + assistant \u4e24\u6761");
        AgentMessage readAssistant = messages.stream()
            .filter(m -> assistantId.equals(m.uuid())).findFirst().orElse(null);
        assertNotNull(readAssistant, "\u9996\u884c\uff08\u5b8c\u6574\u6d88\u606f\uff09\u5fc5\u987b\u88ab\u89e3\u6790");
        assertEquals("text block", readAssistant.content());
    }
}
