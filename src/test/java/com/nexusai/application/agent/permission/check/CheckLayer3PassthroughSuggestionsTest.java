package com.nexusai.application.agent.permission.check;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.PermissionRuleValue;
import com.nexusai.application.agent.permission.PermissionUpdate;
import com.nexusai.application.agent.permission.ToolCheckCache;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.McpServerInfo;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [RF-6 ①] CheckLayer3_PassthroughToAsk suggestions 透传（CC permissions.ts:1299-1310 spread）。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图）</b>: CC passthrough→ask 转换用
 * {@code {...toolPermissionResult, behavior:'ask', message}} spread，<b>保留</b>工具自带的
 * {@code suggestions}（MCP 工具 checkPermissions 返回 addRules allow→localSettings，
 * client.ts:1818-1830）。Java 旧实现只读 {@code Passthrough.pendingClassifierCheck()}，丢弃
 * {@code Passthrough.suggestions()} 自建 CLI_ARG + USER_SETTINGS 死代码建议（照搬即死代码的旧结论）。
 * 本测试锁两层语义：
 * <ol>
 *   <li>passthrough 带非空 suggestions → Ask 透传工具建议（不覆盖为自建建议）</li>
 *   <li>passthrough 空 suggestions → Ask 沿用自建建议（CLI_ARG + USER_SETTINGS，回归不变）</li>
 * </ol>
 */
@DisplayName("[RF-6 ①] CheckLayer3 passthrough→ask suggestions 透传（CC permissions.ts:1299-1310）")
class CheckLayer3PassthroughSuggestionsTest {

    private static final String TOOL_NAME = "mcp__filesystem__read_file";

    @AfterEach
    void tearDown() {
        ToolCheckCache.clear();
    }

    private static CheckLayer3_PassthroughToAsk layer3() {
        return new CheckLayer3_PassthroughToAsk();
    }

    private static Tool stubTool(String name) {
        return new StubTool(name);
    }

    @Test
    @DisplayName("passthrough 带 addRules suggestions → Ask 透传工具建议（CC spread，非自建死代码）")
    void passthroughWithAddRulesSuggestion_isPropagatedToAsk() {
        // WHY: CC permissions.ts:1299-1310 {...toolPermissionResult} spread 保留工具自带的 addRules
        //   suggestions（MCP checkPermissions，client.ts:1818-1830）。旧 Java 丢弃并自建 CLI_ARG/
        //   USER_SETTINGS 建议 → 用户拿不到 "Allow this MCP tool forever (localSettings)" 专属建议。
        Tool tool = stubTool(TOOL_NAME);
        List<PermissionUpdate> addRules = List.of(new PermissionUpdate.AddRules(
            PermissionUpdate.Destination.LOCAL_SETTINGS,
            List.of(new PermissionRule(
                PermissionRuleSource.LOCAL_SETTINGS,
                PermissionBehavior.ALLOW,
                PermissionRuleValue.wholeTool(TOOL_NAME))),
            PermissionBehavior.ALLOW));
        ToolCheckCache.put(TOOL_NAME, new PermissionResult.Passthrough(
            "MCPTool requires permission.", null, addRules, null, null));

        PermissionResult result = layer3().check(tool, null, null, null, null);

        assertThat(result).isInstanceOf(PermissionResult.Ask.class);
        PermissionResult.Ask ask = (PermissionResult.Ask) result;
        assertThat(ask.suggestions())
            .as("必须透传 passthrough 自带的 addRules 建议（CC spread），非自建建议")
            .hasSize(1);
        assertThat(ask.suggestions().get(0)).isInstanceOf(PermissionUpdate.AddRules.class);
        PermissionUpdate.AddRules propagated = (PermissionUpdate.AddRules) ask.suggestions().get(0);
        assertThat(propagated.destination())
            .as("CC destination='localSettings'")
            .isEqualTo(PermissionUpdate.Destination.LOCAL_SETTINGS);
        assertThat(propagated.rules().get(0).ruleValue().toolName()).isEqualTo(TOOL_NAME);
    }

    @Test
    @DisplayName("passthrough 空 suggestions → Ask 沿用自建建议（CLI_ARG + USER_SETTINGS，回归不变）")
    void passthroughWithEmptySuggestions_fallsBackToSelfBuiltSuggestions() {
        // WHY: 非 MCP 工具（Bash 等）checkPermissions 返回空 suggestions 的 passthrough 时，
        //   本层仍自建 "Allow this session (CLI_ARG) + Allow forever (USER_SETTINGS)" 建议，
        //   不能因为 RF-6 ① 的 spread 透传改造而丢失该默认建议。
        Tool tool = stubTool(TOOL_NAME);
        ToolCheckCache.put(TOOL_NAME, new PermissionResult.Passthrough(
            "no opinion", null, List.of(), null, null));

        PermissionResult result = layer3().check(tool, null, null, null, null);

        assertThat(result).isInstanceOf(PermissionResult.Ask.class);
        PermissionResult.Ask ask = (PermissionResult.Ask) result;
        assertThat(ask.suggestions()).hasSize(2);
        assertThat(ask.suggestions().get(0))
            .as("默认建议① = Allow this session → CLI_ARG")
            .isInstanceOf(PermissionUpdate.AddRules.class);
        assertThat(ask.suggestions().get(1))
            .as("默认建议② = Allow forever → USER_SETTINGS")
            .isInstanceOf(PermissionUpdate.AddRules.class);
        assertThat(((PermissionUpdate.AddRules) ask.suggestions().get(0)).destination())
            .isEqualTo(PermissionUpdate.Destination.CLI_ARG);
        assertThat(((PermissionUpdate.AddRules) ask.suggestions().get(1)).destination())
            .isEqualTo(PermissionUpdate.Destination.USER_SETTINGS);
    }

    /** 最小 Tool 桩：CheckLayer3 只消费 name()（message + ToolCheckCache 键 + wholeTool）。 */
    private static final class StubTool implements Tool {
        private final String name;

        StubTool(String name) {
            this.name = name;
        }

        @Override public String name() { return name; }
        @Override public String description() { return "stub tool"; }
        @Override public JsonNode inputSchema() { return null; }
        @Override public AgentToolResult<?> execute(ToolUseBlock call) { return null; }
        @Override public boolean isMcp() { return false; }
        @Override public McpServerInfo mcpInfo() { return null; }
    }
}
