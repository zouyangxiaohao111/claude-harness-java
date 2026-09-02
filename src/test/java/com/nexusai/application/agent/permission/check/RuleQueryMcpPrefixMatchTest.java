package com.nexusai.application.agent.permission.check;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.PermissionRuleValue;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.McpServerInfo;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [MCP-SEC-04] RuleQuery ask/allow 的 MCP whole-server / wildcard 前缀匹配测试。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图）</b>: CC {@code toolMatchesRule}
 * （permissions.ts:238-269）让 {@code toolAlwaysAllowedRule} / {@code getAskRuleForTool} /
 * {@code getDenyRuleForTool} 三者统一走 whole-tool 匹配（direct + MCP server-level）。
 * Java 侧 {@code getDenyRuleForTool} 已对齐（RuleQuery.java:260-264 用 toolMatchesRule），
 * 但 {@code toolAlwaysAllowedRule} 与 {@code getAskRuleForTool} 仍用
 * {@code rule.toolName.equals(tool.name())} 精确比较 —— whole-server 规则
 * {@code mcp__server} 与 wildcard 规则 {@code mcp__server__*} 对 {@code mcp__server__tool}
 * 永不命中（放权/问询缺口）。本测试锁定「ask/allow 桶也必须支持 MCP 前缀匹配」，
 * 与 deny 桶对称。
 *
 * <p>RED 机制：修复前 {@code toolAlwaysAllowedRule} / {@code getAskRuleForTool} 用 equals，
 * 断言 whole-server/wildcard 命中会 FAIL；修复后复用 {@code toolMatchesRule} → GREEN。
 */
@DisplayName("[MCP-SEC-04] RuleQuery ask/allow MCP 前缀匹配（whole-server / wildcard）")
class RuleQueryMcpPrefixMatchTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SERVER = "filesystem";
    private static final String TOOL = "read_file";
    private static final String MCP_FULL_NAME = "mcp__filesystem__read_file";

    // ────────────────────────────────────────────────────────────────────
    // toolAlwaysAllowedRule（2b 层 whole-tool allow）
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("toolAlwaysAllowedRule：allow 桶 MCP 前缀匹配")
    class AllowBucketPrefixMatchTests {

        @Test
        @DisplayName("whole-server allow 规则 mcp__filesystem 命中 mcp__filesystem__read_file")
        void wholeServerAllowRule_matches() {
            ToolPermissionContext permCtx = allowCtx("mcp__filesystem");
            PermissionRule hit = RuleQuery.toolAlwaysAllowedRule(permCtx, mcpTool());
            assertThat(hit).as("whole-server allow 规则必须命中该 server 全部工具").isNotNull();
            assertThat(hit.ruleValue().toolName()).isEqualTo("mcp__filesystem");
        }

        @Test
        @DisplayName("wildcard allow 规则 mcp__filesystem__* 命中 mcp__filesystem__read_file")
        void wildcardAllowRule_matches() {
            ToolPermissionContext permCtx = allowCtx("mcp__filesystem__*");
            PermissionRule hit = RuleQuery.toolAlwaysAllowedRule(permCtx, mcpTool());
            assertThat(hit).as("wildcard allow 规则必须命中该 server 全部工具").isNotNull();
        }

        @Test
        @DisplayName("whole-tool allow 规则 mcp__filesystem__read_file 精确命中（回归）")
        void wholeToolAllowRule_stillMatches() {
            ToolPermissionContext permCtx = allowCtx(MCP_FULL_NAME);
            PermissionRule hit = RuleQuery.toolAlwaysAllowedRule(permCtx, mcpTool());
            assertThat(hit).as("whole-tool 精确 allow 规则不得回归").isNotNull();
        }

        @Test
        @DisplayName("跨 server allow 规则 mcp__otherserver 不命中 mcp__filesystem__read_file")
        void crossServerAllowRule_doesNotMatch() {
            ToolPermissionContext permCtx = allowCtx("mcp__otherserver");
            assertThat(RuleQuery.toolAlwaysAllowedRule(permCtx, mcpTool()))
                .as("跨 server 前缀不得误命中").isNull();
        }

        @Test
        @DisplayName("带 content 的 allow 规则（非 whole-tool）不进入 whole-tool 匹配")
        void withContentAllowRule_notWholeTool() {
            ToolPermissionContext permCtx = allowContentCtx(MCP_FULL_NAME, "file_path:/x");
            assertThat(RuleQuery.toolAlwaysAllowedRule(permCtx, mcpTool()))
                .as("ruleContent != null 不是 whole-tool 规则，toolAlwaysAllowedRule 须返 null").isNull();
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // getAskRuleForTool（1b 层 whole-tool ask）
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getAskRuleForTool：ask 桶 MCP 前缀匹配")
    class AskBucketPrefixMatchTests {

        @Test
        @DisplayName("whole-server ask 规则 mcp__filesystem 命中 mcp__filesystem__read_file")
        void wholeServerAskRule_matches() {
            ToolPermissionContext permCtx = askCtx("mcp__filesystem");
            PermissionRule hit = RuleQuery.getAskRuleForTool(permCtx, mcpTool());
            assertThat(hit).as("whole-server ask 规则必须命中该 server 全部工具").isNotNull();
            assertThat(hit.ruleValue().toolName()).isEqualTo("mcp__filesystem");
        }

        @Test
        @DisplayName("wildcard ask 规则 mcp__filesystem__* 命中 mcp__filesystem__read_file")
        void wildcardAskRule_matches() {
            ToolPermissionContext permCtx = askCtx("mcp__filesystem__*");
            PermissionRule hit = RuleQuery.getAskRuleForTool(permCtx, mcpTool());
            assertThat(hit).as("wildcard ask 规则必须命中该 server 全部工具").isNotNull();
        }

        @Test
        @DisplayName("whole-tool ask 规则 mcp__filesystem__read_file 精确命中（回归）")
        void wholeToolAskRule_stillMatches() {
            ToolPermissionContext permCtx = askCtx(MCP_FULL_NAME);
            PermissionRule hit = RuleQuery.getAskRuleForTool(permCtx, mcpTool());
            assertThat(hit).as("whole-tool 精确 ask 规则不得回归").isNotNull();
        }

        @Test
        @DisplayName("跨 server ask 规则 mcp__otherserver 不命中 mcp__filesystem__read_file")
        void crossServerAskRule_doesNotMatch() {
            ToolPermissionContext permCtx = askCtx("mcp__otherserver");
            assertThat(RuleQuery.getAskRuleForTool(permCtx, mcpTool()))
                .as("跨 server 前缀不得误命中").isNull();
        }

        @Test
        @DisplayName("带 content 的 ask 规则（非 whole-tool）不进入 whole-tool 匹配")
        void withContentAskRule_notWholeTool() {
            ToolPermissionContext permCtx = askContentCtx(MCP_FULL_NAME, "file_path:/x");
            assertThat(RuleQuery.getAskRuleForTool(permCtx, mcpTool()))
                .as("ruleContent != null 不是 whole-tool 规则，getAskRuleForTool 须返 null").isNull();
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 对称性：deny 桶已对齐（回归，防未来退化）
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("对称性回归：deny 桶 MCP 前缀匹配已对齐")
    class DenyBucketSymmetryTests {

        @Test
        @DisplayName("whole-server deny 规则 mcp__filesystem 命中（与 ask/allow 对称）")
        void wholeServerDenyRule_matches() {
            ToolPermissionContext permCtx = denyCtx("mcp__filesystem");
            assertThat(RuleQuery.getDenyRuleForTool(permCtx, mcpTool()))
                .as("deny 桶 whole-server 前缀匹配不得退化").isNotNull();
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 辅助构造
    // ────────────────────────────────────────────────────────────────────

    /** 桩 MCP 工具：isMcp()=true + mcpInfo() 提供 serverName/toolName，name() 为全名。 */
    private static Tool mcpTool() {
        return new Tool() {
            @Override public String name() { return MCP_FULL_NAME; }
            @Override public String description() { return "stub mcp tool"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult<?> execute(ToolUseBlock call) { return null; }
            @Override public boolean isMcp() { return true; }
            @Override public McpServerInfo mcpInfo() { return new McpServerInfo(SERVER, TOOL); }
        };
    }

    private static ToolPermissionContext allowCtx(String toolName) {
        return ctx(toolName, PermissionBehavior.ALLOW, false);
    }

    private static ToolPermissionContext allowContentCtx(String toolName, String ruleContent) {
        return ctx(toolName, PermissionBehavior.ALLOW, true, ruleContent);
    }

    private static ToolPermissionContext askCtx(String toolName) {
        return ctx(toolName, PermissionBehavior.ASK, false);
    }

    private static ToolPermissionContext askContentCtx(String toolName, String ruleContent) {
        return ctx(toolName, PermissionBehavior.ASK, true, ruleContent);
    }

    private static ToolPermissionContext denyCtx(String toolName) {
        return ctx(toolName, PermissionBehavior.DENY, false);
    }

    private static ToolPermissionContext ctx(String toolName, PermissionBehavior behavior, boolean withContent) {
        return ctx(toolName, behavior, withContent, null);
    }

    private static ToolPermissionContext ctx(String toolName, PermissionBehavior behavior,
                                             boolean withContent, String ruleContent) {
        PermissionRuleValue value = withContent
            ? PermissionRuleValue.withContent(toolName, ruleContent)
            : PermissionRuleValue.wholeTool(toolName);
        PermissionRule rule = new PermissionRule(PermissionRuleSource.USER_SETTINGS, behavior, value);
        Set<PermissionRule> rules = Set.of(rule);

        Map<PermissionRuleSource, Set<PermissionRule>> allow = new EnumMap<>(PermissionRuleSource.class);
        Map<PermissionRuleSource, Set<PermissionRule>> deny = new EnumMap<>(PermissionRuleSource.class);
        Map<PermissionRuleSource, Set<PermissionRule>> ask = new EnumMap<>(PermissionRuleSource.class);
        switch (behavior) {
            case ALLOW -> allow.put(PermissionRuleSource.USER_SETTINGS, rules);
            case DENY -> deny.put(PermissionRuleSource.USER_SETTINGS, rules);
            case ASK -> ask.put(PermissionRuleSource.USER_SETTINGS, rules);
        }
        return ToolPermissionContext.of(PermissionMode.DEFAULT, allow, deny, ask, Map.of());
    }
}
