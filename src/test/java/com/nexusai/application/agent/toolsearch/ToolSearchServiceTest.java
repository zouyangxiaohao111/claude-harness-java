package com.nexusai.application.agent.toolsearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.McpServerInfo;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.infra.llm.CountTokensClient;
import com.nexusai.infra.llm.CountTokensClient.ToolSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ToolSearchService 全分支测试 · 对齐 CC
 * {@code Open-ClaudeCode/src/utils/toolSearch.ts}（getToolSearchMode :172-198 /
 * isToolSearchEnabledOptimistic :270-320 / isToolSearchToolAvailable :330-334）+
 * {@code isDeferredTool}（tools/ToolSearchTool/prompt.ts:62-108）。
 *
 * <p><b>WHY（规则九）</b>: H3 将 SchemaNotSentHint 私有副本收敛为共享服务 ToolSearchService，
 * 测试锁定 CC 每个 env 分支的 mode 映射 + 4 门判定，防止服务回归为"只看 isMcp"的简化版，
 * 或 env 解析偏离 CC（auto:N clamp / kill switch / unset 默认等）。
 *
 * <p>provider 子检查（toolSearch.ts:299-311）Java N/A（无 API provider 抽象）→ 不测，
 * 见类 javadoc 登记。
 */
class ToolSearchServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 可配置 mock tool: isMcp / alwaysLoad / shouldDefer / name / aliases. */
    static class ConfigurableTool implements Tool {
        private final String name;
        private final List<String> aliases;
        private final boolean isMcp;
        private final boolean alwaysLoad;
        private final boolean shouldDefer;

        ConfigurableTool(String name, List<String> aliases, boolean isMcp,
                         boolean alwaysLoad, boolean shouldDefer) {
            this.name = name;
            this.aliases = aliases;
            this.isMcp = isMcp;
            this.alwaysLoad = alwaysLoad;
            this.shouldDefer = shouldDefer;
        }

        @Override public String name() { return name; }

        @Override public List<String> aliases() { return aliases; }

        @Override public String description() { return "Mock tool"; }

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

    // ─────────────────────── getToolSearchMode 全分支 ───────────────────────

    @Test
    @DisplayName("mode: kill switch CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS truthy → standard")
    void mode_killSwitch_standard() {
        // CC toolSearch.ts:181-183 — proxy 网关逃生阀，优先级最高
        assertThat(ToolSearchService.getToolSearchMode(
            Map.of("CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS", "1"))).isEqualTo("standard");
    }

    @Test
    @DisplayName("mode: auto:0 → tst（恒启用）")
    void mode_auto0_tst() {
        // CC toolSearch.ts:189
        assertThat(ToolSearchService.getToolSearchMode(Map.of("ENABLE_TOOL_SEARCH", "auto:0")))
            .isEqualTo("tst");
    }

    @Test
    @DisplayName("mode: auto:100 → standard（恒禁用）")
    void mode_auto100_standard() {
        // CC toolSearch.ts:190
        assertThat(ToolSearchService.getToolSearchMode(Map.of("ENABLE_TOOL_SEARCH", "auto:100")))
            .isEqualTo("standard");
    }

    @Test
    @DisplayName("mode: auto / auto:1-99 → tst-auto")
    void mode_autoRange_tstAuto() {
        // CC toolSearch.ts:191-193
        assertThat(ToolSearchService.getToolSearchMode(Map.of("ENABLE_TOOL_SEARCH", "auto")))
            .isEqualTo("tst-auto");
        assertThat(ToolSearchService.getToolSearchMode(Map.of("ENABLE_TOOL_SEARCH", "auto:50")))
            .isEqualTo("tst-auto");
        assertThat(ToolSearchService.getToolSearchMode(Map.of("ENABLE_TOOL_SEARCH", "auto:1")))
            .isEqualTo("tst-auto");
        assertThat(ToolSearchService.getToolSearchMode(Map.of("ENABLE_TOOL_SEARCH", "auto:99")))
            .isEqualTo("tst-auto");
    }

    @Test
    @DisplayName("mode: auto 越界 clamp 0-100（auto:200→100→standard）")
    void mode_autoClamp() {
        // CC parseAutoPercentage :55-70 clamp(0,100)；auto:200 → 100 → standard
        assertThat(ToolSearchService.getToolSearchMode(Map.of("ENABLE_TOOL_SEARCH", "auto:200")))
            .isEqualTo("standard");
        // auto:-5 → 0 → tst
        assertThat(ToolSearchService.getToolSearchMode(Map.of("ENABLE_TOOL_SEARCH", "auto:-5")))
            .isEqualTo("tst");
    }

    @Test
    @DisplayName("mode: auto 非法数字 → 按 auto:N 前缀回落 tst-auto")
    void mode_autoInvalidNumber_tstAuto() {
        // CC parseAutoPercentage 非数字返回 null → isAutoToolSearchMode('auto:xyz')=true → tst-auto
        assertThat(ToolSearchService.getToolSearchMode(Map.of("ENABLE_TOOL_SEARCH", "auto:xyz")))
            .isEqualTo("tst-auto");
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "1", "yes", "on", "TRUE", " Yes "})
    @DisplayName("mode: truthy → tst（CC isEnvTruthy envUtils.ts:32-37，case-insensitive trim）")
    void mode_truthy_tst(String v) {
        assertThat(ToolSearchService.getToolSearchMode(Map.of("ENABLE_TOOL_SEARCH", v)))
            .isEqualTo("tst");
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "0", "no", "off", "FALSE", " No "})
    @DisplayName("mode: defined falsy → standard（CC isEnvDefinedFalsy envUtils.ts:39-47）")
    void mode_falsy_standard(String v) {
        assertThat(ToolSearchService.getToolSearchMode(Map.of("ENABLE_TOOL_SEARCH", v)))
            .isEqualTo("standard");
    }

    @Test
    @DisplayName("mode: unset / 空 → tst（CC :197 默认启用）")
    void mode_unset_tst() {
        assertThat(ToolSearchService.getToolSearchMode(Map.of())).isEqualTo("tst");
        assertThat(ToolSearchService.getToolSearchMode(null)).isEqualTo("tst");
        assertThat(ToolSearchService.getToolSearchMode(Map.of("ENABLE_TOOL_SEARCH", "")))
            .isEqualTo("tst");
    }

    // ─────────────────────── isToolSearchEnabledOptimistic ───────────────────────

    @Test
    @DisplayName("optimistic: mode=standard → false")
    void optimistic_standard_false() {
        assertThat(ToolSearchService.isToolSearchEnabledOptimistic(
            Map.of("ENABLE_TOOL_SEARCH", "false"))).isFalse();
        assertThat(ToolSearchService.isToolSearchEnabledOptimistic(
            Map.of("CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS", "1"))).isFalse();
    }

    @Test
    @DisplayName("optimistic: tst / tst-auto → true；unset 默认 true")
    void optimistic_enabled_true() {
        assertThat(ToolSearchService.isToolSearchEnabledOptimistic(Map.of())).isTrue();
        assertThat(ToolSearchService.isToolSearchEnabledOptimistic(
            Map.of("ENABLE_TOOL_SEARCH", "true"))).isTrue();
        assertThat(ToolSearchService.isToolSearchEnabledOptimistic(
            Map.of("ENABLE_TOOL_SEARCH", "auto"))).isTrue();
        assertThat(ToolSearchService.isToolSearchEnabledOptimistic(
            Map.of("ENABLE_TOOL_SEARCH", "auto:0"))).isTrue();
    }

    @Test
    @DisplayName("optimistic: env null → 空 map → 默认 tst → true")
    void optimistic_nullEnv_true() {
        assertThat(ToolSearchService.isToolSearchEnabledOptimistic((Map<String, String>) null))
            .isTrue();
    }

    // ─────────────────────── isToolSearchToolAvailable ───────────────────────

    @Test
    @DisplayName("available: name 精确命中 → true")
    void available_nameMatch_true() {
        List<Tool> tools = List.of(
            new ConfigurableTool("Bash", null, false, false, false),
            new ConfigurableTool("ToolSearch", null, false, false, false));
        assertThat(ToolSearchService.isToolSearchToolAvailable(tools)).isTrue();
    }

    @Test
    @DisplayName("available: aliases 命中 → true（Tool 重命名后老名进 aliases）")
    void available_aliasMatch_true() {
        List<Tool> tools = List.of(
            new ConfigurableTool("RenamedTool", List.of("ToolSearch"), false, false, false));
        assertThat(ToolSearchService.isToolSearchToolAvailable(tools)).isTrue();
    }

    @Test
    @DisplayName("available: 无 ToolSearch → false；null 列表 → false")
    void available_noMatch_false() {
        List<Tool> tools = List.of(
            new ConfigurableTool("Read", null, false, false, false),
            new ConfigurableTool("Edit", null, false, false, false));
        assertThat(ToolSearchService.isToolSearchToolAvailable(tools)).isFalse();
        assertThat(ToolSearchService.isToolSearchToolAvailable(null)).isFalse();
        assertThat(ToolSearchService.isToolSearchToolAvailable(List.of())).isFalse();
    }

    // ─────────────────────── isDeferredTool（CC prompt.ts:62-108） ───────────────────────

    @Test
    @DisplayName("deferred: alwaysLoad=true → false（CC :65 优先级最高）")
    void deferred_alwaysLoad_false() {
        Tool t = new ConfigurableTool("mcp__essential", null, true, true, true);
        assertThat(ToolSearchService.isDeferredTool(t, null)).isFalse();
    }

    @Test
    @DisplayName("deferred: MCP 工具 → true（CC :68）")
    void deferred_mcp_true() {
        Tool t = new ConfigurableTool("mcp__gh__create", null, true, false, false);
        assertThat(ToolSearchService.isDeferredTool(t, JSON.createObjectNode())).isTrue();
    }

    @Test
    @DisplayName("deferred: ToolSearch 自身 → false（CC :71 模型需要它加载其他工具）")
    void deferred_toolSearch_false() {
        Tool t = new ConfigurableTool(ToolNameConstants.TOOL_SEARCH_TOOL_NAME, null,
            false, false, true);
        assertThat(ToolSearchService.isDeferredTool(t, null)).isFalse();
    }

    @Test
    @DisplayName("deferred: 默认规则 shouldDefer=true → true / false → false（CC :107）")
    void deferred_shouldDefer_defaultRule() {
        assertThat(ToolSearchService.isDeferredTool(
            new ConfigurableTool("Read", null, false, false, true), JSON.createObjectNode()))
            .isTrue();
        assertThat(ToolSearchService.isDeferredTool(
            new ConfigurableTool("Read", null, false, false, false), JSON.createObjectNode()))
            .isFalse();
    }

    @Test
    @DisplayName("deferred: null tool → false（defensive）")
    void deferred_nullTool_false() {
        assertThat(ToolSearchService.isDeferredTool(null, null)).isFalse();
    }

    // ─────────────────────── IMP-C6: memoize + token 优先 ───────────────────────

    /** 每测重置 env 快照 + 清空 deferred token 计数缓存（IMP-C6 memoize 静态缓存跨测隔离）。 */
    @AfterEach
    void resetToolSearchEnvAndCache() {
        ToolSearchService.envOverride = null;
        ToolSearchService.invalidateDeferredToolTokenCountCache();
    }

    /**
     * memoize 生效 · CC {@code getDeferredToolTokenCount} memoize（toolSearch.ts:124-152）。
     * 同 deferred 工具集两次 definitive 门控 → count_tokens API 仅调用一次（缓存命中）。
     * 变异点（去 memoize 缓存）→ calls==2 → 断言红。
     */
    @Test
    @DisplayName("memoize: 同 deferred 工具集两次 isToolSearchEnabled → countTokensForTools 仅一次")
    void deferredTokenCount_memoized_singleCountTokensCall() {
        ToolSearchService.envOverride = Map.of("ENABLE_TOOL_SEARCH", "auto"); // tst-auto
        List<Tool> tools = List.of(
            new ConfigurableTool("ToolSearch", null, false, false, false),
            new ConfigurableTool("mcp__gh__create", null, true, false, false),
            new ConfigurableTool("mcp__gh__read", null, true, false, false));
        AtomicInteger calls = new AtomicInteger();
        CountTokensClient mock = new CountTokensClient() {
            @Override public Integer countTokens(String content) { return 0; }
            @Override public Integer countTokensForTools(List<ToolSchema> schemas) {
                calls.incrementAndGet();
                return 2000;
            }
        };
        ToolSearchService.isToolSearchEnabled(tools, "claude-sonnet-4", mock);
        ToolSearchService.isToolSearchEnabled(tools, "claude-sonnet-4", mock);
        // CC memoize 键=deferred 工具名（同集合）→ 第二次缓存命中，不重打 count_tokens
        assertThat(calls.get()).as("同 deferred 工具集第二次调用应命中 memoize 缓存").isEqualTo(1);
    }

    /**
     * 显式失效 · CC memoize 缓存生命周期（MCP connect/disconnect 失效语义，toolSearch.ts:121）。
     * {@link ToolSearchService#invalidateDeferredToolTokenCountCache()} 后同工具集重打 → calls==2。
     * 变异点（invalidate 不清缓存）→ calls==1 → 断言红。
     */
    @Test
    @DisplayName("invalidateDeferredToolTokenCountCache: 清空后同工具集重算（MCP 生命周期接线点）")
    void deferredTokenCount_invalidateCache_recomputes() {
        ToolSearchService.envOverride = Map.of("ENABLE_TOOL_SEARCH", "auto");
        List<Tool> tools = List.of(
            new ConfigurableTool("ToolSearch", null, false, false, false),
            new ConfigurableTool("mcp__gh__create", null, true, false, false));
        AtomicInteger calls = new AtomicInteger();
        CountTokensClient mock = new CountTokensClient() {
            @Override public Integer countTokens(String content) { return 0; }
            @Override public Integer countTokensForTools(List<ToolSchema> schemas) {
                calls.incrementAndGet();
                return 2000;
            }
        };
        ToolSearchService.isToolSearchEnabled(tools, "claude-sonnet-4", mock);
        ToolSearchService.invalidateDeferredToolTokenCountCache();
        ToolSearchService.isToolSearchEnabled(tools, "claude-sonnet-4", mock);
        assertThat(calls.get()).as("显式失效后应重新打 count_tokens").isEqualTo(2);
    }

    /**
     * token 优先（3 参注入生效）· CC checkAutoThreshold（toolSearch.ts:712-756）token 分支优先
     * 于 char fallback。构造工具 prompt/schema 极小（char 计数 ~52 远低于 char 阈值 50000）
     * → 无 token client（2 参）= char 分支 enabled=false；有 token client 返回 100000
     * （≥ token 阈值 20000）= token 分支 enabled=true。变异点（3 参忽略 token client）→ 恒 false。
     */
    @Test
    @DisplayName("tst-auto: 3 参 token 分支优先于 char fallback（token 达阈值 → true）")
    void definitive_tokenPriority_tokenBranchAboveThreshold() {
        ToolSearchService.envOverride = Map.of("ENABLE_TOOL_SEARCH", "auto"); // tst-auto
        List<Tool> tools = List.of(
            new ConfigurableTool("ToolSearch", null, false, false, false),
            new ConfigurableTool("mcp__gh__create", null, true, false, false));
        // char fallback：prompt/schema 极小 → char 计数远低于 char 阈值 → false
        assertThat(ToolSearchService.isToolSearchEnabled(tools, "claude-sonnet-4"))
            .as("无 token client → char fallback → 低于 char 阈值").isFalse();
        // token 分支：100000 >= threshold(20000) → true
        CountTokensClient mock = new CountTokensClient() {
            @Override public Integer countTokens(String content) { return 0; }
            @Override public Integer countTokensForTools(List<ToolSchema> schemas) { return 100000; }
        };
        assertThat(ToolSearchService.isToolSearchEnabled(tools, "claude-sonnet-4", mock))
            .as("token 计数达阈值 → token 分支 enabled=true").isTrue();
    }
}
