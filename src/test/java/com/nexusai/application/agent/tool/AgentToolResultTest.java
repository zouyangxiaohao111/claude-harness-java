package com.nexusai.application.agent.tool;

import com.nexusai.model.session.dto.ChatMessageDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-C2] AgentToolResult 4 字段契约验证（组 2-1 拍板，对齐 CC ToolResult&lt;T&gt;
 * = {data, newMessages?, contextModifier?, mcpMeta?}，Tool.ts:321-336）。
 *
 * <p><b>WHY（意图验证 · CLAUDE.md 规则九）</b>：
 * Java 原 AgentToolResult 携带 toolUseId/isError/errorCategory/structuredOutput 4 个偏离
 * 字段（自注"A1 遗憾"），组 2-1 拍板全部删除对齐 CC。本测试断言接口仅暴露 CC 4 方法：
 * data/newMessages/contextModifier/mcpMeta，且删除字段访问器（toolUseId/isError/
 * errorCategory/structuredOutput）在类型系统中不可见 —— 未来若有人重新添加偏离字段，
 * 编译/断言必红（CC 契约回归防护）。
 */
@DisplayName("IMP-C2 · AgentToolResult 4 字段契约")
class AgentToolResultTest {

    @Test
    @DisplayName("接口仅暴露 CC 4 方法: data/newMessages/contextModifier/mcpMeta")
    void interfaceExposesOnlyCcFourMethods() {
        // 通过反射断言接口方法集 = {data, newMessages, contextModifier, mcpMeta}
        java.util.Set<String> methods = new java.util.HashSet<>();
        for (java.lang.reflect.Method m : AgentToolResult.class.getDeclaredMethods()) {
            methods.add(m.getName());
        }
        // default 方法: newMessages/contextModifier/mcpMeta + 抽象 data
        assertThat(methods)
            .as("AgentToolResult 接口方法集（对齐 CC ToolResult 4 字段）")
            .containsExactlyInAnyOrder("data", "newMessages", "contextModifier", "mcpMeta");
    }

    @Test
    @DisplayName("删除字段访问器在接口中不可见 (toolUseId/isError/errorCategory/structuredOutput 0 命中)")
    void deletedAccessorsAbsentFromInterface() {
        java.util.Set<String> methods = new java.util.HashSet<>();
        for (java.lang.reflect.Method m : AgentToolResult.class.getDeclaredMethods()) {
            methods.add(m.getName());
        }
        assertThat(methods)
            .as("组 2-1 拍板删除的 4 个偏离字段访问器不得出现在接口")
            .doesNotContain("toolUseId", "isError", "errorCategory", "structuredOutput");
    }

    @Test
    @DisplayName("ToolResult 实现: 4 字段 record 组件 (data/newMessages/contextModifier/mcpMeta)")
    void toolResultRecordHasFourComponents() {
        java.util.List<String> components = java.util.Arrays.stream(ToolResult.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)
            .toList();
        assertThat(components)
            .as("ToolResult record 组件集（对齐 CC ToolResult 4 字段）")
            .containsExactlyInAnyOrder("data", "newMessages", "contextModifier", "mcpMeta");
    }

    @Test
    @DisplayName("ToolResult 默认: newMessages 空 List / contextModifier null / mcpMeta null")
    void toolResultDefaults() {
        ToolResult<String> r = ToolResult.success("call-1", "ok");
        assertThat(r.data()).isEqualTo("ok");
        assertThat(r.newMessages()).isEmpty();
        assertThat(r.contextModifier()).isNull();
        assertThat(r.mcpMeta()).isNull();
    }

    @Test
    @DisplayName("successWithNewMessagesWithContextModifier: 4 字段均可访问 (CC SkillTool 三件套)")
    void successWithNewMessagesWithContextModifier() {
        ChatMessageDto skillMsg = new ChatMessageDto(
            java.util.UUID.randomUUID().toString(), null, com.nexusai.model.session.dto.Role.user,
            "user", "skill instruction", null, null, null, null, null, null, null, null, null,
            null, List.of(), List.of(), null, false, false, null);
        Function<ToolUseContext, ToolUseContext> modifier = ctx -> ctx;
        ToolResult<String> r = ToolResult.successWithNewMessagesWithContextModifier(
            "call-1", "meta", List.of(skillMsg), modifier);
        assertThat(r.data()).isEqualTo("meta");
        assertThat(r.newMessages()).containsExactly(skillMsg);
        assertThat(r.contextModifier()).isSameAs(modifier);
        assertThat(r.mcpMeta()).isNull();
    }
}
