package com.nexusai.application.agent.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code session/update} → 内部事件映射 · 6 类已知 + 未知必须安全丢弃。
 */
@DisplayName("[acp] AcpEventMapper 事件映射")
class AcpEventMapperTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode json(Object value) {
        return JSON.valueToTree(value);
    }

    @Test
    @DisplayName("agent_message_chunk ⇒ CHUNK 文本")
    void messageChunk() {
        AcpEventMapper.MappedUpdate mapped = AcpEventMapper.map(json(Map.of(
                "sessionUpdate", "agent_message_chunk",
                "content", Map.of("type", "text", "text", "hello"))));

        assertThat(mapped.kind()).isEqualTo(AcpEventMapper.Kind.CHUNK);
        assertThat(mapped.text()).isEqualTo("hello");
    }

    @Test
    @DisplayName("agent_thought_chunk ⇒ REASONING")
    void thoughtChunk() {
        AcpEventMapper.MappedUpdate mapped = AcpEventMapper.map(json(Map.of(
                "sessionUpdate", "agent_thought_chunk",
                "content", Map.of("type", "text", "text", "thinking"))));

        assertThat(mapped.kind()).isEqualTo(AcpEventMapper.Kind.REASONING);
        assertThat(mapped.text()).isEqualTo("thinking");
    }

    @Test
    @DisplayName("tool_call ⇒ TOOL_CALL，rawInput 转成 Map")
    void toolCall() {
        AcpEventMapper.MappedUpdate mapped = AcpEventMapper.map(json(Map.of(
                "sessionUpdate", "tool_call",
                "toolCallId", "t-1",
                "title", "Run tests",
                "kind", "execute",
                "status", "in_progress",
                "rawInput", Map.of("command", "mvn test"))));

        assertThat(mapped.kind()).isEqualTo(AcpEventMapper.Kind.TOOL_CALL);
        assertThat(mapped.toolCallId()).isEqualTo("t-1");
        assertThat(mapped.toolName()).isEqualTo("Run tests");
        assertThat(mapped.arguments()).containsEntry("command", "mvn test");
    }

    @Test
    @DisplayName("tool_call_update completed ⇒ TOOL_RESULT（非错误）")
    void toolCallCompleted() {
        AcpEventMapper.MappedUpdate mapped = AcpEventMapper.map(json(Map.of(
                "sessionUpdate", "tool_call_update",
                "toolCallId", "t-1",
                "status", "completed",
                "content", List.of(Map.of("type", "content",
                        "content", Map.of("type", "text", "text", "OK"))))));

        assertThat(mapped.kind()).isEqualTo(AcpEventMapper.Kind.TOOL_RESULT);
        assertThat(mapped.isError()).isFalse();
        assertThat(mapped.text()).isEqualTo("OK");
    }

    @Test
    @DisplayName("tool_call_update failed ⇒ TOOL_RESULT(isError=true)")
    void toolCallFailed() {
        AcpEventMapper.MappedUpdate mapped = AcpEventMapper.map(json(Map.of(
                "sessionUpdate", "tool_call_update",
                "toolCallId", "t-2",
                "status", "failed")));

        assertThat(mapped.kind()).isEqualTo(AcpEventMapper.Kind.TOOL_RESULT);
        assertThat(mapped.isError()).isTrue();
    }

    @Test
    @DisplayName("tool_call_update 中间态 ⇒ IGNORE（不刷结果）")
    void toolCallInProgressIgnored() {
        AcpEventMapper.MappedUpdate mapped = AcpEventMapper.map(json(Map.of(
                "sessionUpdate", "tool_call_update",
                "toolCallId", "t-1",
                "status", "in_progress")));

        assertThat(mapped.kind()).isEqualTo(AcpEventMapper.Kind.IGNORE);
    }

    @Test
    @DisplayName("usage / plan / 未知 update ⇒ IGNORE（不炸）")
    void unknownUpdatesIgnored() {
        assertThat(AcpEventMapper.map(json(Map.of("sessionUpdate", "usage_update",
                "used", 1, "size", 2))).kind()).isEqualTo(AcpEventMapper.Kind.IGNORE);
        assertThat(AcpEventMapper.map(json(Map.of("sessionUpdate", "plan"))).kind())
                .isEqualTo(AcpEventMapper.Kind.IGNORE);
        assertThat(AcpEventMapper.map(json(Map.of("sessionUpdate", "brand_new_thing"))).kind())
                .isEqualTo(AcpEventMapper.Kind.IGNORE);
        assertThat(AcpEventMapper.map(null).kind()).isEqualTo(AcpEventMapper.Kind.IGNORE);
    }

    @Test
    @DisplayName("diff 内容取路径 + 新内容；空 content ⇒ text 为 null")
    void diffAndEmpty() {
        AcpEventMapper.MappedUpdate diff = AcpEventMapper.map(json(Map.of(
                "sessionUpdate", "tool_call_update",
                "toolCallId", "t-3",
                "status", "completed",
                "content", List.of(Map.of("type", "diff", "path", "a.txt", "newText", "NEW")))));
        assertThat(diff.text()).contains("a.txt").contains("NEW");

        AcpEventMapper.MappedUpdate empty = AcpEventMapper.map(json(Map.of(
                "sessionUpdate", "agent_message_chunk")));
        assertThat(empty.text()).isNull();
    }
}
