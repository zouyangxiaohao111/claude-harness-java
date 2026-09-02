package com.nexusai.application.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.PermissionRuleValue;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.BashTool;
import com.nexusai.application.agent.tool.impl.ReadFileTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [B1] 主循环 LLM schema 路径 deny 过滤 · 对齐 CC {@code getTools tools.ts:310 filterToolsByDenyRules}.
 *
 * <p>WHY: CC 主循环 tools 数组 = SPECIAL_TOOLS 剔除 → deny 过滤 ({@code filterToolsByDenyRules}
 * tools.ts:310) → REPL_ONLY → isEnabled. blanket deny 工具在 <b>schema 阶段</b>即被隐藏，
 * 而非仅运行时 {@code getDenyRuleForTool} 调用期拦截（CC 1a）。Java 现状 {@code llmToolsArray}
 * 只经 {@code toOpenAiToolsArray} 的 isEnabled + SPECIAL_TOOLS 过滤，缺 deny 过滤。
 *
 * <p>变异点：去掉 llmToolsArray 中的 deny 过滤 → 被 blanket deny 的 Bash 重新出现在 schema →
 * 测试变红。
 */
class LlmAgentLoopToolsArrayDenyTest {

    /** 构造 blanket deny Bash 的 per-turn 权限上下文（对齐 CC permissions.ts:287 getDenyRuleForTool）。 */
    private ToolPermissionContext denyBashContext() {
        PermissionRule denyBash = new PermissionRule(
                PermissionRuleSource.USER_SETTINGS,
                PermissionBehavior.DENY,
                PermissionRuleValue.wholeTool("Bash"));
        return ToolPermissionContext.of(
                PermissionMode.DEFAULT,
                Map.of(),
                Map.of(PermissionRuleSource.USER_SETTINGS, Set.of(denyBash)),
                Map.of(),
                Map.of());
    }

    /** 10 参构造器 per-turn TUC：availableTools + permissionContext（对齐生产 perTurnTuc 形态）。 */
    private ToolUseContext tuc(List<Tool> availableTools, ToolPermissionContext permCtx) {
        return new ToolUseContext(
                UUID.randomUUID(),
                "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8),
                PermissionMode.DEFAULT,
                Map.of(),
                availableTools,
                null,
                AbortController.NOOP,
                List.of(),
                permCtx,
                PermissionMode.DEFAULT);
    }

    @Test
    @DisplayName("主循环 schema（llmToolsArray）隐藏 blanket deny 工具：deny Bash → schema 无 Bash 有 read_file")
    void llmToolsArray_hidesBlanketDeniedToolFromSchema() {
        // WHY: CC getTools tools.ts:310 在 schema 阶段经 filterToolsByDenyRules 剔除 deny 工具，
        //   模型"看不到"即不会请求（比运行时拦截省一轮往返且不误导模型）。
        //   变异点: 删除 llmToolsArray 中的 deny 过滤 → Bash 重新出现在 schema → 红.
        List<Tool> availableTools = List.of(
                new BashTool(),
                new ReadFileTool(new PathGuard(Paths.get("."))));

        ArrayNode schema = LlmAgentLoop.llmToolsArray(tuc(availableTools, denyBashContext()), QuerySource.USER);

        List<String> toolNames = new java.util.ArrayList<>();
        for (JsonNode fn : schema) {
            toolNames.add(fn.path("function").path("name").asText());
        }
        assertThat(toolNames)
                .as("主循环 schema 不得暴露被 blanket deny 的工具（CC tools.ts:310）")
                .contains("Read") // 未 deny 工具保留（B2 主名对齐 CC）
                .doesNotContain("Bash"); // blanket deny 工具 schema 阶段隐藏
    }

    @Test
    @DisplayName("permCtx 为 null 时 deny 过滤跳过，schema 正常返回（filterToolsByDenyRules null-safe）")
    void llmToolsArray_permCtxNull_filtersNothing() {
        // WHY: per-turn TUC.permissionContext() 可 null（builder 异常 fallback）。
        //   filterToolsByDenyRules 已 null-safe（permCtx null → 原列表），不误删工具。
        List<Tool> availableTools = List.of(new BashTool());

        ArrayNode schema = LlmAgentLoop.llmToolsArray(tuc(availableTools, null), QuerySource.USER);

        assertThat(schema)
                .as("permCtx null → 不过滤（Bash 仍在 schema），不误删")
                .isNotNull()
                .hasSize(1);
    }
}
