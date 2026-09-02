package com.nexusai.application.agent.toolsearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.impl.ToolSearchTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ToolSearchTool.isEnabled 门控测试 · 对齐 CC ToolSearchTool.ts:305-306
 * {@code isEnabled() = isToolSearchEnabledOptimistic()}。
 *
 * <p><b>WHY（规则九 · 验证意图）</b>: CC 在 tools.ts:249 注册时按
 * {@code isToolSearchEnabledOptimistic} 门控 ToolSearch 是否暴露给 LLM（A48）。
 * Java 端 ToolRegistry 的 isEnabled 守卫（toOpenAiToolsArray:414 / getTools:553 /
 * getToolsForDefaultPreset:598 / all:374）在 mode=standard（feature 关闭）时把
 * ToolSearchTool 从 LLM 工具列表过滤——若 isEnabled 恒 true（Tool.java:465 默认），
 * 门控失效。本测试锁定 mode=standard → isEnabled false；unset 默认 → true。
 *
 * <p>经 {@link ToolSearchService#envOverride} seam 注入 env（镜像 CC 测试直接写
 * process.env）；{@code null} → 读 System.getenv()（生产路径，本测试不依赖）。
 */
class ToolSearchToolGateTest {

    @AfterEach
    void resetEnvOverride() {
        ToolSearchService.envOverride = null;
    }

    @Test
    @DisplayName("feature 关闭（ENABLE_TOOL_SEARCH=false → mode=standard）→ isEnabled()==false")
    void standardMode_isEnabledFalse() {
        ToolSearchService.envOverride = Map.of("ENABLE_TOOL_SEARCH", "false");

        assertThat(new ToolSearchTool().isEnabled())
            .as("CC ToolSearchTool.ts:306 isToolSearchEnabledOptimistic()=false → ToolRegistry 过滤")
            .isFalse();
    }

    @Test
    @DisplayName("kill switch（CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS=1）→ isEnabled()==false")
    void killSwitch_isEnabledFalse() {
        ToolSearchService.envOverride = Map.of("CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS", "1");

        assertThat(new ToolSearchTool().isEnabled()).isFalse();
    }

    @Test
    @DisplayName("unset 默认（mode=tst）→ isEnabled()==true（CC 默认启用）")
    void unsetEnv_isEnabledTrue() {
        ToolSearchService.envOverride = Map.of();

        assertThat(new ToolSearchTool().isEnabled())
            .as("CC getToolSearchMode 默认 'tst' → optimistic=true → ToolSearch 暴露")
            .isTrue();
    }

    @Test
    @DisplayName("显式启用（ENABLE_TOOL_SEARCH=true / auto）→ isEnabled()==true")
    void enabledEnv_isEnabledTrue() {
        ToolSearchService.envOverride = Map.of("ENABLE_TOOL_SEARCH", "true");
        assertThat(new ToolSearchTool().isEnabled()).isTrue();

        ToolSearchService.envOverride = Map.of("ENABLE_TOOL_SEARCH", "auto");
        assertThat(new ToolSearchTool().isEnabled()).isTrue();
    }

    @Test
    @DisplayName("ToolRegistry isEnabled 守卫：mode=standard → ToolSearch 从 LLM 工具列表消失")
    void registryFiltersToolSearchWhenStandard() {
        // 验证 H3 门控接线闭环：ToolSearchTool.isEnabled()=optimistic 经 ToolRegistry
        // isEnabled 守卫（all():374 / getTools():553 / getToolsForDefaultPreset():598 /
        // toOpenAiToolsArray():414）在 feature 关闭时把 ToolSearch 过滤出 LLM 列表
        // （CC tools.ts:249 注册门控 A48）。all() 不滤 SPECIAL_TOOLS → 若 isEnabled 恒 true
        // 本断言必红。
        ToolSearchService.envOverride = Map.of("ENABLE_TOOL_SEARCH", "false");

        ToolRegistry registry = new ToolRegistry()
            .register(new ToolSearchTool())
            .register(new SimpleTool("Read"));

        assertThat(registry.all()).noneMatch(t -> "ToolSearch".equals(t.name()));
        assertThat(registry.getTools(null)).noneMatch(t -> "ToolSearch".equals(t.name()));
        assertThat(registry.getToolsForDefaultPreset()).doesNotContain("ToolSearch");
        assertThat(registry.toOpenAiToolsArray().toString()).doesNotContain("ToolSearch");
        // 对照：普通工具不受影响
        assertThat(registry.all()).extracting(Tool::name).contains("Read");
    }

    @Test
    @DisplayName("feature 启用（unset 默认）→ ToolSearch 保留在 ToolRegistry.all()（isEnabled=true）")
    void registryKeepsToolSearchWhenEnabled() {
        ToolSearchService.envOverride = Map.of(); // 默认 tst

        ToolRegistry registry = new ToolRegistry()
            .register(new ToolSearchTool())
            .register(new SimpleTool("Read"));

        assertThat(registry.all()).extracting(Tool::name).contains("ToolSearch", "Read");
    }

    /** 最小 mock tool（默认方法满足 Tool 接口，仅需 name()）. */
    static class SimpleTool implements Tool {
        private final String name;

        SimpleTool(String name) {
            this.name = name;
        }

        @Override public String name() { return name; }

        @Override public String description() { return "minimal mock"; }

        @Override public JsonNode inputSchema() {
            return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        }

        @Override public AgentToolResult execute(ToolUseBlock call) {
            return ToolResult.success(call.id(), "ok");
        }
    }
}
