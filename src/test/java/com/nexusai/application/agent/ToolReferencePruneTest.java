package com.nexusai.application.agent;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code LlmAgentLoop.pruneUnavailableToolCalls} 单测 —— 纯函数，<b>不启动 Spring</b>。
 *
 * <p>防的线上故障：会话工具被禁用后，历史里仍留着引用它的 {@code tool_use}，
 * Anthropic 报 {@code tool reference 'X' not found in available tools} ⇒ 整轮 400 且永久无法自愈。
 */
class ToolReferencePruneTest {

    private static ChatMessageDto assistantWithCalls(String content, ToolCallDto... calls) {
        return new ChatMessageDto(
            "m-" + content, "s1", Role.assistant, "assistant", content, null,
            List.of(calls), FinishReason.tool_calls,
            null, null, "刚刚", null, null, null, null,
            null, null, null, false);
    }

    private static ChatMessageDto toolResult(String toolCallId, String content) {
        return new ChatMessageDto(
            "t-" + toolCallId, "s1", Role.tool, "tool", content, null,
            List.of(), FinishReason.stop,
            null, null, "刚刚", null, toolCallId, null, null,
            null, null, null, false);
    }

    private static ChatMessageDto userMessage(String content) {
        return new ChatMessageDto(
            "u-" + content, "s1", Role.user, "user", content, null,
            List.of(), FinishReason.stop,
            null, null, "刚刚", null, null, null, null,
            null, null, null, false);
    }

    private static ArrayNode toolsSchema(String... names) {
        ArrayNode arr = JsonNodeFactory.instance.arrayNode();
        for (String n : names) {
            ObjectNode o = arr.addObject();
            o.put("name", n);
        }
        return arr;
    }

    private static LlmAgentLoop.ToolsAssembly assembly(String... names) {
        return new LlmAgentLoop.ToolsAssembly(toolsSchema(names), false, Set.of());
    }

    private static ToolCallDto call(String id, String name) {
        return new ToolCallDto(id, name, "{}", null, false);
    }

    @Test
    @DisplayName("失效工具调用被剔除，同条消息的文本与其它有效调用保留")
    void dropsUnavailableCallKeepsOthers() {
        ToolCallDto ok = call("c1", "Read");
        ToolCallDto gone = call("c2", "mcp__computer-use__activate_window");
        List<ChatMessageDto> in = List.of(assistantWithCalls("我看看", ok, gone));

        List<ChatMessageDto> out = LlmAgentLoop.pruneUnavailableToolCalls(in, assembly("Read", "Grep"), 3);

        assertEquals(1, out.size());
        ChatMessageDto m = out.get(0);
        assertEquals("我看看", m.content(), "文本必须保留");
        assertEquals(1, m.toolCalls().size(), "只剩有效的那个");
        assertEquals("Read", m.toolCalls().get(0).name());
    }

    @Test
    @DisplayName("失效调用对应的 tool_result 消息（孤儿）一并丢弃")
    void dropsOrphanToolResult() {
        List<ChatMessageDto> in = List.of(
            assistantWithCalls("调一下", call("c9", "mcp__computer-use__activate_window")),
            toolResult("c9", "结果"),
            userMessage("继续"));

        List<ChatMessageDto> out = LlmAgentLoop.pruneUnavailableToolCalls(in, assembly("Read"), 1);

        assertEquals(2, out.size(), "assistant 文本保留 + user 保留，tool 结果丢弃");
        assertTrue(out.stream().noneMatch(m -> m.role() == Role.tool));
        assertEquals("继续", out.get(1).content());
    }

    @Test
    @DisplayName("工具全部可见 → 原引用返回（零行为变化）")
    void allAvailableReturnsSameInstance() {
        List<ChatMessageDto> in = List.of(
            assistantWithCalls("hi", call("c1", "Read")),
            toolResult("c1", "ok"));

        List<ChatMessageDto> out = LlmAgentLoop.pruneUnavailableToolCalls(
            in, assembly("Read", "Write"), 1);

        assertSame(in, out, "无失效调用时必须返回原引用，避免无意义重建");
    }

    @Test
    @DisplayName("deferred 工具（名字在 tools 数组里但 schema 不下发）不被误剔")
    void deferredToolsAreNotPruned() {
        ToolCallDto deferred = call("d1", "mcp__computer-use__activate_window");
        List<ChatMessageDto> in = List.of(
            assistantWithCalls("用电脑操作", deferred),
            toolResult("d1", "done"));
        LlmAgentLoop.ToolsAssembly asm = new LlmAgentLoop.ToolsAssembly(
            toolsSchema("Read", "mcp__computer-use__activate_window"), true,
            Set.of("mcp__computer-use__activate_window"));

        List<ChatMessageDto> out = LlmAgentLoop.pruneUnavailableToolCalls(in, asm, 1);

        assertSame(in, out, "deferred 工具经 tool_reference 引用是合法的，⛔ 不能剔");
    }

    @Test
    @DisplayName("tools 为空数组 / assembly 为 null → 原样返回（不死循环剔光历史）")
    void emptyToolsOrNullAssemblyIsNoop() {
        List<ChatMessageDto> in = List.of(
            assistantWithCalls("x", call("c1", "Read")));

        assertSame(in, LlmAgentLoop.pruneUnavailableToolCalls(in, assembly(), 1));
        assertSame(in, LlmAgentLoop.pruneUnavailableToolCalls(in, null, 1));
        assertNull(LlmAgentLoop.pruneUnavailableToolCalls(null, assembly("Read"), 1), "null 入参 → null 返回，不抛");
    }

    @Test
    @DisplayName("原始消息不被改写（只生成副本）")
    void originalMessagesUntouched() {
        ChatMessageDto original = assistantWithCalls("原文",
            call("c1", "Read"), call("c2", "mcp__computer-use__activate_window"));
        List<ChatMessageDto> in = new ArrayList<>(List.of(original));

        LlmAgentLoop.pruneUnavailableToolCalls(in, assembly("Read"), 1);

        assertEquals(2, original.toolCalls().size(), "入参对象不能被原地修改");
        assertNotNull(in.get(0).toolCalls().get(1));
        assertEquals("mcp__computer-use__activate_window", in.get(0).toolCalls().get(1).name());
    }
}
