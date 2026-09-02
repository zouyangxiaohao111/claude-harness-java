package com.nexusai.application.agent.toolsearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.McpServerInfo;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Session H P2-1] SchemaNotSentHint 完整版 4 道乐观门全覆盖测试 · 对齐 CC
 * {@code Open-ClaudeCode/src/services/tools/toolExecution.ts:578-597}
 * {@code buildSchemaNotSentHint}.
 *
 * <p><b>WHY (规则九 · 测试验证意图)</b>: H 把基础版（isMcp && !alwaysLoad 单门）升级为
 * CC 完整 4 道门（feature gate → ToolSearch 可用 → deferred tool → discovered set），
 * 每道门命中返回 null（不注入 hint）、全过才注入 hint 文案。本测试锁定 4 道门的
 * 命中/放行分支，防止回退为"只看 isMcp"的基础版语义。
 *
 * <p><b>可达性说明</b>: 第 4 道门（discovered set 扫描）在 Java 端已为真扫描
 * ({@link SchemaNotSentHint#extractDiscoveredToolNames} 对齐 CC toolSearch.ts:545-592,
 * 扫 user 消息 tool_result→tool_reference.tool_name)。H2 修复后 gate4 不再恒放行：
 * discovered 含目标工具 → build()==null（schema 已发, 不注入）；不含 → 注入 hint。
 * 边界为含 tool_reference 的历史消息 → discovered 非空 → 拦截。
 *
 * <h2>测试用例 (8 分支)</h2>
 * <ol>
 *   <li>gate1: env 关（CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS=1 / ENABLE_TOOL_SEARCH=false）→ null</li>
 *   <li>gate1 放行: env 未设 → 默认启用（CC getToolSearchMode 默认 'tst'）</li>
 *   <li>gate2: tools 列表无 ToolSearch → null</li>
 *   <li>gate2 放行: tools 列表含 ToolSearch → 继续</li>
 *   <li>gate3: alwaysLoad=true → null（CC prompt.ts:64-66 优先级最高）</li>
 *   <li>gate3: ToolSearch 自身 → null（CC prompt.ts:70-71 永不 defer）</li>
 *   <li>gate3: 非 MCP 非 defer → null（CC prompt.ts:107 shouldDefer 默认 false）</li>
 *   <li>gate3 放行: MCP tool → 过门（CC prompt.ts:67-68 isMcp 永远 defer）</li>
 *   <li>gate4 真扫描: 含 tool_result→tool_reference 的 user 消息 → discovered 非空</li>
 *   <li>gate4 拦截: discovered 含目标工具 → build()==null（不注入, 消除误报）</li>
 *   <li>gate4 放行: discovered 不含目标 → 注入 hint（schema 未发送场景）</li>
 *   <li>gate4 防御: 非 ChatMessageDto / 非 user / null contentBlocks 不抛异常</li>
 *   <li>全过 → hint 文案为 CC 英文三段原文（schema 未发送/字符串化/select: 加载后重试）</li>
 * </ol>
 */
class SchemaNotSentHintFullCoverageTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 可配置的 mock tool: isMcp / alwaysLoad / shouldDefer / name. */
    static class ConfigurableTool implements Tool {
        private final String name;
        private final boolean isMcp;
        private final boolean alwaysLoad;
        private final boolean shouldDefer;

        ConfigurableTool(String name, boolean isMcp, boolean alwaysLoad, boolean shouldDefer) {
            this.name = name;
            this.isMcp = isMcp;
            this.alwaysLoad = alwaysLoad;
            this.shouldDefer = shouldDefer;
        }

        @Override public String name() { return name; }

        @Override public String description() { return "mock tool " + name; }

        @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }

        @Override public AgentToolResult execute(ToolUseBlock call) {
            return ToolResult.success(call.id(), "ok");
        }

        @Override public boolean isMcp() { return isMcp; }

        @Override public boolean alwaysLoad() { return alwaysLoad; }

        @Override public boolean shouldDefer(JsonNode input) { return shouldDefer; }

        @Override public McpServerInfo mcpInfo() {
            return isMcp ? new McpServerInfo(name + "_server", "stdio") : null;
        }
    }

    private static ToolUseContext ctxWith(List<Tool> availableTools) {
        return ctxWith(availableTools, List.of());
    }

    private static ToolUseContext ctxWith(List<Tool> availableTools, List<?> messages) {
        UUID agentId = UUID.randomUUID();
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        return new ToolUseContext(agentId, sessionId, PermissionMode.DEFAULT,
            Map.of(), availableTools, "", null, messages,
            null, PermissionMode.DEFAULT, Map.of(), false, "", null, null, null, null);
    }

    private static JsonNode input() {
        return JSON.createObjectNode();
    }

    /** 构造含 tool_result→tool_reference 块的 user 消息 (对齐 CC ToolSearchTool.ts:465-468 发射形状). */
    private static ChatMessageDto userMsgWithToolReference(String... toolNames) {
        JsonNode toolResult = JSON.createObjectNode()
            .put("type", "tool_result")
            .set("content", JSON.createArrayNode());
        for (String name : toolNames) {
            ((com.fasterxml.jackson.databind.node.ArrayNode) toolResult.path("content"))
                .add(JSON.createObjectNode()
                    .put("type", "tool_reference")
                    .put("tool_name", name));
        }
        return new ChatMessageDto("u1", "s1", Role.user, "user", "tool result",
            null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null,
            List.of(toolResult), List.of(), null, false, false);
    }

    /** 构造无 contentBlocks 的 user 消息 (防御: null contentBlocks). */
    private static ChatMessageDto userMsgWithoutBlocks() {
        return new ChatMessageDto("u2", "s1", Role.user, "user", "plain",
            null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null,
            null, List.of(), null, false, false);
    }

    /** 构造 assistant 消息 (防御: 非 user role 跳过). */
    private static ChatMessageDto assistantMsg() {
        return new ChatMessageDto("a1", "s1", Role.assistant, "assistant", "reply",
            null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false);
    }

    // ─────────────────────── gate1: feature gate ───────────────────────

    @Test
    @DisplayName("gate1 命中: CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS=1 → mode=standard → null")
    void gate1_killSwitchEnvDisables_hintNull() {
        boolean enabled = ToolSearchService.isToolSearchEnabledOptimistic(
            Map.of("CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS", "1"));
        assertThat(enabled).as("CC getToolSearchMode: kill switch → 'standard' → optimistic=false").isFalse();

        // build() 全链路: 经 envOverride seam 注入 env (镜像 CC 测试直接写 process.env)
        ToolSearchService.envOverride = Map.of("CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS", "1");
        try {
            Tool mcpTool = new ConfigurableTool("mcp__gh__create", true, false, true);
            ToolUseContext ctx = ctxWith(List.of(new ConfigurableTool("ToolSearch", false, false, false), mcpTool));
            assertThat(SchemaNotSentHint.build(mcpTool, ctx, input())).isNull();
        } finally {
            ToolSearchService.envOverride = null;
        }
    }

    @Test
    @DisplayName("gate1 命中: ENABLE_TOOL_SEARCH=false → mode=standard → null")
    void gate1_falsyEnvDisables_hintNull() {
        boolean enabled = ToolSearchService.isToolSearchEnabledOptimistic(
            Map.of("ENABLE_TOOL_SEARCH", "false"));
        assertThat(enabled).as("CC isEnvDefinedFalsy('false') → 'standard' → optimistic=false").isFalse();
    }

    @Test
    @DisplayName("gate1 放行: env 未设 → 默认启用 (CC 默认 'tst')")
    void gate1_unsetEnv_defaultEnabled() {
        boolean enabled = ToolSearchService.isToolSearchEnabledOptimistic(Map.of());
        assertThat(enabled).as("CC 默认 mode='tst' → optimistic=true").isTrue();
    }

    @Test
    @DisplayName("gate1 放行: ENABLE_TOOL_SEARCH=auto → tst-auto → 启用")
    void gate1_autoModeEnabled() {
        boolean enabled = ToolSearchService.isToolSearchEnabledOptimistic(
            Map.of("ENABLE_TOOL_SEARCH", "auto"));
        assertThat(enabled).as("CC auto → tst-auto → optimistic=true").isTrue();
    }

    // ─────────────────────── gate2: ToolSearch 可用性 ───────────────────────

    @Test
    @DisplayName("gate2 命中: tools 列表无 ToolSearch → null")
    void gate2_noToolSearchInTools_hintNull() {
        Tool mcpTool = new ConfigurableTool("mcp__gh__create", true, false, true);
        ToolUseContext ctx = ctxWith(List.of(mcpTool)); // 无 ToolSearch

        String hint = SchemaNotSentHint.build(mcpTool, ctx, input());

        assertThat(hint).isNull();
    }

    @Test
    @DisplayName("gate2 放行: tools 列表含 ToolSearch → 继续到 gate3")
    void gate2_toolSearchPresent_passes() {
        Tool mcpTool = new ConfigurableTool("mcp__gh__create", true, false, true);
        ToolUseContext ctx = ctxWith(List.of(
            new ConfigurableTool("ToolSearch", false, false, false), mcpTool));

        assertThat(ToolSearchService.isToolSearchToolAvailable(ctx.availableTools()))
            .as("isToolSearchToolAvailable 必须命中 name 匹配").isTrue();
    }

    @Test
    @DisplayName("gate2 放行: ToolSearch 经 aliases 注册 → 命中 (CC toolMatchesName Tool.ts:348-353)")
    void gate2_toolSearchByAlias_passes() {
        // CC isToolSearchToolAvailable → toolMatchesName: name === name || aliases?.includes(name).
        // Tool 被重命名时老名进 aliases (Tool.java:634-636), 经 alias 注册的 ToolSearch
        // 同样满足 gate2 — Java Tool 接口有 aliases() 机制, 此处必须匹配.
        Tool searchByAlias = new ConfigurableTool("ToolSearchRenamed", false, false, false) {
            @Override public java.util.List<String> aliases() {
                return java.util.List.of("ToolSearch");
            }
        };
        Tool mcpTool = new ConfigurableTool("mcp__gh__create", true, false, true);
        ToolUseContext ctx = ctxWith(List.of(searchByAlias, mcpTool));

        assertThat(ToolSearchService.isToolSearchToolAvailable(ctx.availableTools()))
            .as("CC toolMatchesName: aliases 含 'ToolSearch' 必须命中").isTrue();
    }

    // ─────────────────────── gate3: deferred tool ───────────────────────

    @Test
    @DisplayName("gate3 命中: alwaysLoad=true → 永不 defer → null (CC prompt.ts:64-66)")
    void gate3_alwaysLoad_hintNull() {
        Tool mcpTool = new ConfigurableTool("mcp__core__essential", true, true, true);
        ToolUseContext ctx = ctxWith(List.of(new ConfigurableTool("ToolSearch", false, false, false), mcpTool));

        String hint = SchemaNotSentHint.build(mcpTool, ctx, input());

        assertThat(hint).isNull();
    }

    @Test
    @DisplayName("gate3 命中: ToolSearch 自身 → 永不 defer → null (CC prompt.ts:70-71)")
    void gate3_toolSearchItself_hintNull() {
        Tool searchTool = new ConfigurableTool("ToolSearch", false, false, false);
        ToolUseContext ctx = ctxWith(List.of(searchTool));

        String hint = SchemaNotSentHint.build(searchTool, ctx, input());

        assertThat(hint).isNull();
    }

    @Test
    @DisplayName("gate3 命中: 非 MCP 非 defer → null (CC prompt.ts:107 shouldDefer 默认 false)")
    void gate3_nonMcpNonDeferred_hintNull() {
        Tool readTool = new ConfigurableTool("Read", false, false, false);
        ToolUseContext ctx = ctxWith(List.of(new ConfigurableTool("ToolSearch", false, false, false), readTool));

        String hint = SchemaNotSentHint.build(readTool, ctx, input());

        assertThat(hint).isNull();
    }

    @Test
    @DisplayName("gate3 放行: MCP tool → 永远 defer → 过门 (CC prompt.ts:67-68)")
    void gate3_mcpTool_deferred() {
        Tool mcpTool = new ConfigurableTool("mcp__gh__create", true, false, true);
        ToolUseContext ctx = ctxWith(List.of(new ConfigurableTool("ToolSearch", false, false, false), mcpTool));

        assertThat(ToolSearchService.isDeferredTool(mcpTool, input()))
            .as("CC isDeferredTool: isMcp=true → 永远 defer").isTrue();
        // shouldDefer 显式 true 的非 MCP 工具同样 defer (CC prompt.ts:107)
        Tool deferTool = new ConfigurableTool("Plan", false, false, true);
        assertThat(ToolSearchService.isDeferredTool(deferTool, input())).isTrue();
    }

    // ─────────────────────── gate4: discovered set 扫描 ───────────────────────

    @Test
    @DisplayName("gate4 真扫描: 含 tool_result→tool_reference 的 user 消息 → discovered 非空")
    void gate4_scanToolReference_discoveredNotEmpty() {
        // CC toolSearch.ts:545-592 扫描对象: user 消息 tool_result 内容内的 tool_reference.tool_name
        Set<String> discovered = SchemaNotSentHint.extractDiscoveredToolNames(
            List.of(userMsgWithToolReference("mcp__github__create_issue", "mcp__gh__delete")));

        assertThat(discovered)
            .as("真扫描必须提取 tool_reference.tool_name, 非恒空 (H2 消除 gate4 恒放行)")
            .containsExactlyInAnyOrder("mcp__github__create_issue", "mcp__gh__delete");
    }

    @Test
    @DisplayName("gate4 拦截: discovered 含目标工具 → build()==null (schema 已发, 不注入, 消除误报)")
    void gate4_discoveredContainsTarget_hintNull() {
        // 目标工具 schema 已随 ToolSearch tool_reference 发送 → gate4 返回 null, 不再注入误导 hint
        Tool mcpTool = new ConfigurableTool("mcp__github__create_issue", true, false, true);
        ToolUseContext ctx = ctxWith(
            List.of(new ConfigurableTool("ToolSearch", false, false, false), mcpTool),
            List.of(userMsgWithToolReference("mcp__github__create_issue")));

        String hint = SchemaNotSentHint.build(mcpTool, ctx, input());

        assertThat(hint).as("discovered 含目标工具 → schema 已发 → 不注入 (gate4 不再恒放行)").isNull();
    }

    @Test
    @DisplayName("gate4 放行: discovered 不含目标 → 注入 hint (schema 未发送)")
    void gate4_discoveredWithoutTarget_hintInjected() {
        // 历史只发现其它工具 → 目标工具 schema 未发送 → gate4 放行, 注入 hint 引导加载
        Tool mcpTool = new ConfigurableTool("mcp__github__create_issue", true, false, true);
        ToolUseContext ctx = ctxWith(
            List.of(new ConfigurableTool("ToolSearch", false, false, false), mcpTool),
            List.of(userMsgWithToolReference("mcp__gh__delete")));

        String hint = SchemaNotSentHint.build(mcpTool, ctx, input());

        assertThat(hint).isNotNull();
        assertThat(hint).contains("select:mcp__github__create_issue");
    }

    @Test
    @DisplayName("gate4 防御: 非 ChatMessageDto / 非 user / null contentBlocks → 不抛异常")
    void gate4_defensiveMessages_noThrow() {
        // 生产 ctx.messages() 可能含非 ChatMessageDto 元素 (List<?>) → instanceof 守卫必须跳过
        assertThat(SchemaNotSentHint.extractDiscoveredToolNames(
            List.of("not-a-message", 42, userMsgWithToolReference("mcp__a"), assistantMsg(), userMsgWithoutBlocks())))
            .as("非 ChatMessageDto 跳过 + assistant 跳过 + null contentBlocks 跳过, 仍提取 user 消息")
            .containsExactly("mcp__a");
        assertThat(SchemaNotSentHint.extractDiscoveredToolNames(null)).isEmpty();
    }

    // ─────────────────────── 全过 → hint 文案 ───────────────────────

    @Test
    @DisplayName("4 道门全过 → hint 文案为 CC 英文三段原文 (含 select: 指引)")
    void allGatesPass_hintContainsToolName() {
        Tool mcpTool = new ConfigurableTool("mcp__gh__create_issue", true, false, true);
        ToolUseContext ctx = ctxWith(List.of(new ConfigurableTool("ToolSearch", false, false, false), mcpTool));

        String hint = SchemaNotSentHint.build(mcpTool, ctx, input());

        assertThat(hint).isNotNull();
        // CC 三段英文原文 (toolExecution.ts:592-596): schema 未发送 / 字符串化 / select: 加载后重试
        assertThat(hint).contains("schema was not sent to the API");
        assertThat(hint).contains("typed parameters (arrays, numbers, booleans)");
        assertThat(hint).contains("Load the tool first");
        assertThat(hint).contains("select:mcp__gh__create_issue"); // 工具名供 LLM 识别
        assertThat(hint).contains(ToolNameConstants.TOOL_SEARCH_TOOL_NAME); // 引导调用 ToolSearch
    }

    @Test
    @DisplayName("null tool / null ctx → null (defensive, 不抛 NPE)")
    void nullInputs_noHint_noNpe() {
        assertThat(SchemaNotSentHint.build(null, ctxWith(List.of()), input())).isNull();
        Tool mcpTool = new ConfigurableTool("mcp__test", true, false, true);
        assertThat(SchemaNotSentHint.build(mcpTool, null, input())).isNull();
    }
}
