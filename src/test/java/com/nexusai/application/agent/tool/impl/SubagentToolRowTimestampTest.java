package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.subagent.AgentTranscript;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [transcript-timestamp] 工具行（tool_use / tool_result）落 transcript 必须带 timestamp。
 *
 * <h2>WHY（2026-11-20 实测根因）</h2>
 * <p>真实会话 {@code sess-01c0afd5/agent-ab1bb3af453e9f75f.jsonl} 的 line 72 是
 * {@code role=tool}，timestamp 为 null —— 根因不是「没人想写」，而是
 * {@link LlmAgentLoop#toolResultMessage} 构造的 {@link ChatMessageDto}
 * {@code createdAt=null}（该 DTO 的 12 参位置就是 null），写入侧
 * {@code chatMessageToMap} 旧实现「createdAt != null 才落 timestamp」⇒ 键整个不落。
 * 多个 null 叶子让读取侧只能靠「文件序最后一条」兜底。
 *
 * <h2>RED teeth（变异）</h2>
 * <p>把 {@code chatMessageToMap} 的 null-createdAt 兜底（以及写入漏斗的补齐）去掉 ⇒ 两个用例同时红。
 */
@DisplayName("[transcript-timestamp] 工具行 tool_use / tool_result 必须落 timestamp")
class SubagentToolRowTimestampTest {

    @TempDir
    Path tmpDir;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SESSION = "sess-tool-ts";
    private static final String AGENT = "a0123456789abcdef";

    private Path transcriptPath() {
        return AgentTranscript.getTranscriptPath(tmpDir, SESSION, AGENT);
    }

    @Test
    @DisplayName("tool_result 行：真实 LlmAgentLoop 形状（createdAt=null）落盘后必须有 timestamp")
    void toolResultRow_hasTimestamp_evenWhenDtoCreatedAtIsNull() throws Exception {
        // GIVEN: 生产构造点构造出来的 tool 消息（LlmAgentLoop.toolResultMessage）
        ChatMessageDto toolMsg = LlmAgentLoop.toolResultMessage(
            ToolResult.success("toolu_1", "(Bash completed with no output)"),
            "toolu_1", false, null, null, null,
            List.of(), List.of(), Map.of());
        assertThat(toolMsg.createdAt())
            .as("前置：真实工具结果 DTO 的 createdAt 就是 null（缺陷来源）").isNull();

        // WHEN: 走生产落盘助手（appendListener 同款）
        SubagentExecutor.recordMessageTranscript(tmpDir, SESSION, AGENT, toolMsg, null);

        // THEN: 落盘行有 timestamp，且可被读取侧按 ISO 解析
        JsonNode line = MAPPER.readTree(Files.readAllLines(transcriptPath()).get(0));
        assertThat(line.path("role").asText()).isEqualTo("tool");
        assertThat(line.hasNonNull("timestamp"))
            .as("tool_result 行必须有 timestamp（CC 每行都有）").isTrue();
        assertThat(OffsetDateTime.parse(line.path("timestamp").asText()))
            .as("格式与既有行一致（OffsetDateTime/ISO-8601 可解析）").isNotNull();
    }

    @Test
    @DisplayName("tool_use 行：assistant 带 toolCalls 且 createdAt=null 时也必须有 timestamp，且 toolCalls 不被改动")
    void toolUseRow_hasTimestamp_evenWhenDtoCreatedAtIsNull() throws Exception {
        ChatMessageDto toolUse = new ChatMessageDto(
            "asst-1", SESSION, Role.assistant, "assistant", "", null,
            List.of(new ToolCallDto("toolu_1", "Bash", "{\"command\":\"ls\"}", null, null)),
            FinishReason.tool_calls, null, null, "刚刚", null,
            null, null, null, List.of(), List.of(), null,
            false, false, null);
        assertThat(toolUse.createdAt()).as("前置：createdAt=null").isNull();

        SubagentExecutor.recordMessageTranscript(tmpDir, SESSION, AGENT, toolUse, null);

        JsonNode line = MAPPER.readTree(Files.readAllLines(transcriptPath()).get(0));
        assertThat(line.path("role").asText()).isEqualTo("assistant");
        assertThat(line.hasNonNull("timestamp")).as("tool_use 行必须有 timestamp").isTrue();
        assertThat(line.path("toolCalls").isArray()).as("toolCalls 原样保留").isTrue();
        assertThat(line.path("toolCalls").get(0).path("id").asText()).isEqualTo("toolu_1");
    }

    @Test
    @DisplayName("同一轮：tool_use 先写、tool_result 后写，时间戳不倒序")
    void toolUseThenResult_timestampsStayMonotonic() throws Exception {
        ChatMessageDto toolUse = new ChatMessageDto(
            "asst-2", SESSION, Role.assistant, "assistant", "", null,
            List.of(new ToolCallDto("toolu_2", "Bash", "{}", null, null)),
            FinishReason.tool_calls, null, null, "刚刚", null,
            null, null, null, List.of(), List.of(), null,
            false, false, null);
        SubagentExecutor.recordMessageTranscript(tmpDir, SESSION, AGENT, toolUse, null);

        ChatMessageDto toolMsg = LlmAgentLoop.toolResultMessage(
            ToolResult.success("toolu_2", "ok"),
            "toolu_2", false, null, "asst-2", null,
            List.of(), List.of(), Map.of());
        SubagentExecutor.recordMessageTranscript(tmpDir, SESSION, AGENT, toolMsg, "asst-2");

        List<String> lines = Files.readAllLines(transcriptPath());
        long first = OffsetDateTime.parse(
            MAPPER.readTree(lines.get(0)).path("timestamp").asText()).toInstant().toEpochMilli();
        long second = OffsetDateTime.parse(
            MAPPER.readTree(lines.get(1)).path("timestamp").asText()).toInstant().toEpochMilli();
        assertThat(second).as("后写的 tool_result 不得比先写的 tool_use 更早").isGreaterThanOrEqualTo(first);
    }
}
