package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [WF2-R4 返工] subagent 工具结果序列化改走 per-tool mapToToolResultBlockParam。
 *
 * <p>WHY (CLAUDE.md 规则 9 · 测试验证意图): CC 主/子循环共享 mapper
 * (Open-ClaudeCode/src/services/tools/toolExecution.ts:1292
 * {@code tool.mapToolResultToToolResultBlockParam(result.data, toolUseID)})。
 * FileReadTool 的行号前缀只在 mapper 序列化层拼（FileReadTool.ts:692-715 text case
 * {@code freshness + formatFileLines→addLineNumbers + reminder}），call 层 data() 是 raw 无行号
 * （:1046-1055）。旧 SubagentExecutor.toolResultMessage 直走 renderToolResultPayloadText
 * 旁路 → subagent Read 结果丢行号。本测试证明 subagent ReadFileTool 结果经共享 mapper 后带行号前缀。
 */
@DisplayName("[WF2-R4] subagent ReadFileTool 结果含行号前缀（主/子共享 mapper）")
class SubagentExecutorReadFileLineNumberTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 构造最小 SubagentExecutor（toolResultMessage 只依赖 result + tool 入参, 实例状态无关）. */
    private SubagentExecutor executor() {
        return new SubagentExecutor(
            new ToolRegistry(),
            new HookRegistry(),
            (LlmAgentLoop) null,
            new LlmProviderFactory(),
            ProviderConfig.empty(),
            "fallback-model",
            "fallback system prompt",
            null);
    }

    private static ToolUseBlock callWith(String path) {
        ObjectNode input = JSON.createObjectNode();
        input.put("file_path", path);
        return new ToolUseBlock("call-read-1", "read_file", input);
    }

    @Test
    @DisplayName("subagent Read 结果经共享 mapper 带行号前缀 —— 而非 raw 丢行号")
    void subagentReadFileResultCarriesLineNumbers(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "hello\n");
        ReadFileTool tool = new ReadFileTool(new PathGuard(workspace));

        ToolResult<?> result = (ToolResult<?>) tool.execute(callWith("a.txt"));
        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();

        // 对照: 旁路直拼（旧实现）返回 raw 无行号 —— 证明行号只存在 mapper 序列化层
        assertThat(ToolResult.renderToolResultPayloadText(result))
            .as("call 层 data() 为 raw 无行号（CC FileReadTool.ts:1046-1055）")
            .isEqualTo("hello\n");

        // 主断言: subagent 工具结果序列化改走 per-tool mapper → 带行号前缀（CC toolExecution.ts:1292）
        // [IMP-C2] toolUseId 由 mapper 参数透传（ToolResult 4 字段契约），经 5 参重载显式传入调用 id
        ChatMessageDto msg = executor().toolResultMessage(
            "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), result, tool, "call-read-1", false);
        assertThat(msg.role()).isEqualTo(Role.tool);
        assertThat(msg.content())
            .as("subagent ReadFileTool 结果必须带行号前缀（1\\thello\\n），而非 raw")
            .startsWith("1\thello\n");
    }
}
