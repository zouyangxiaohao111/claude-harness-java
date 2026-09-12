package com.nexusai.application.agent.compact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.prompt.PromptAlignSettingsResolver;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.BashTool;
import com.nexusai.application.agent.tool.impl.ToolSearchTool;
import com.nexusai.application.agent.tool.impl.WebSearchTool;
import com.nexusai.application.agent.toolsearch.ToolSearchService;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [dtd-cfg] deferred_tools_delta 门控「统一判定」测试 · 对齐 CC
 * {@code utils/toolSearch.ts:624-634} {@code isDeferredToolsDeltaEnabled}。
 *
 * <p><b>WHY（CLAUDE.md 规则 9）</b>：语义 = true → 持久化增量附件 {@code deferred_tools_delta}
 * 公告 deferred 工具；false → 每轮在消息队首 prepend 全量 {@code <available-deferred-tools>}
 * 清单。默认 false。此前该判定分裂：主循环走 DB-aware 门，而压缩内圈
 * {@code PostCompactAttachmentRestorer} 与主循环 prepend 各读 env-only 拷贝 → 前端把 DB 开关
 * 打开（生产 env 非 ant）时，内圈压缩重宣布被 env 挡掉、prepend 又照发全量 → 开关空转 + 双发。
 * 本测试锁死收敛后的单一判定
 * {@code PromptAlignSettingsResolver.staticDeferredToolsDeltaEnabled()}（DB 覆盖 → env 回落）
 * 在三条路径上的一致取值。
 *
 * <p><b>变异自证</b>：把内圈 {@code deferredToolsDeltaAttachment} 的 gate 改回 env-only
 * （{@code ToolSearchService.isDeferredToolsDeltaEnabled()}）→
 * {@link #dbTrue_envFalse_deltaAttachmentProduced()} 与
 * {@link #dbTrue_envFalse_prependSuppressed()} 转红；把 prepend 改回 env-only →
 * {@link #dbFalse_envTrue_prependFullList()} 转红。
 */
class DeferredToolsDeltaUnifiedGateTest {

    private static final String MODEL = "claude-sonnet-4-5";
    private static final String ANTHROPIC = "anthropic";

    @AfterEach
    void resetSeams() {
        // 静态槽位 + env seam 全局单例 → 逐测复位，杜绝串扰（统一判定读 staticResolver）。
        PromptAlignSettingsResolver.setStaticResolver(null);
        ToolSearchService.envOverride = null;
        PostCompactAttachmentRestorer.envOverride = null;
    }

    // ════════════════════════════════════════════════════════════════════
    // (a) NULL / 未设置 → false
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("(a) 无 static resolver + env 非 ant → 统一判定 false（默认，对齐 CC）")
    void noResolver_envEmpty_defaultFalse() {
        ToolSearchService.envOverride = Map.of();
        assertThat(PromptAlignSettingsResolver.staticDeferredToolsDeltaEnabled())
            .as("未接线 / 无 Spring → 回落 env，默认非 ant → false")
            .isFalse();
    }

    @Test
    @DisplayName("(a) DB 列为 NULL（未配置）→ 回落 env，env 非 ant → false")
    void dbNull_fallsBackToEnv_false() {
        PromptAlignSettingsResolver.setStaticResolver(resolverWith(null));
        ToolSearchService.envOverride = Map.of();
        assertThat(PromptAlignSettingsResolver.staticDeferredToolsDeltaEnabled()).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // (b) DB 设 true（env 关）→ delta 附件路径生效（内圈不再挡）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("(b) DB deferred_tools_delta_enabled=true + env 非 ant → 内圈产出 deferred_tools_delta 附件")
    void dbTrue_envFalse_deltaAttachmentProduced() {
        PromptAlignSettingsResolver.setStaticResolver(resolverWith(true));
        ToolSearchService.envOverride = Map.of(); // env 层判 false —— 旧 env-only 内圈会返回 null

        List<Tool> tools = List.of(
            tool("mcp__docs-server__search", true),  // MCP → 恒 deferred（added 非空）
            tool("ToolSearch", false));

        ChatMessageDto dtd = PostCompactAttachmentRestorer.deferredToolsDeltaAttachment(
            tools, MODEL, ANTHROPIC, List.of());

        assertThat(dtd)
            .as("DB 开关打开 → 压缩内圈须产出 delta（旧 env-only 拷贝会在此返回 null → 红）")
            .isNotNull();
        assertThat(dtd.subtype()).isEqualTo(PostCompactAttachmentRestorer.DELTA_TYPE_DEFERRED_TOOLS);
        assertThat(dtd.content()).contains("mcp__docs-server__search");
    }

    @Test
    @DisplayName("(b') DB=true + env 非 ant → prepend 被抑制（不双发）")
    void dbTrue_envFalse_prependSuppressed() {
        PromptAlignSettingsResolver.setStaticResolver(resolverWith(true));
        ToolSearchService.envOverride = Map.of();

        LlmAgentLoop.ToolsAssembly assembly = assembly(List.of(
            new BashTool(), new ToolSearchTool(), new WebSearchTool()));

        List<ChatMessageDto> out = assembly.prependAvailableDeferredTools(List.of());
        assertThat(out)
            .as("delta 路径生效时 prepend 全量清单须被抑制（旧 env-only 判断会误 prepend → 红）")
            .isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════
    // (c) DB 设 false（env 真）→ 走 prepend 全清单
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("(c) DB deferred_tools_delta_enabled=false + env USER_TYPE=ant → 每轮 prepend 全量清单")
    void dbFalse_envTrue_prependFullList() {
        PromptAlignSettingsResolver.setStaticResolver(resolverWith(false));
        ToolSearchService.envOverride = Map.of("USER_TYPE", "ant"); // env 层判 true —— DB 须覆盖为 false

        LlmAgentLoop.ToolsAssembly assembly = assembly(List.of(
            new BashTool(), new ToolSearchTool(), new WebSearchTool()));
        assertThat(assembly.useToolSearch()).isTrue();

        List<ChatMessageDto> out = assembly.prependAvailableDeferredTools(List.of());
        assertThat(out)
            .as("DB=false → prepend 全清单（DB 覆盖 env 的 true；改回 env-only 则此处 size=0 → 红）")
            .hasSize(1);
        assertThat(out.get(0).content())
            .contains("<available-deferred-tools>")
            .contains("</available-deferred-tools>");
    }

    @Test
    @DisplayName("(c') DB=false + env USER_TYPE=ant → 内圈 delta 附件被 DB 关挡（与 prepend 互补）")
    void dbFalse_envTrue_deltaAttachmentNull() {
        PromptAlignSettingsResolver.setStaticResolver(resolverWith(false));
        ToolSearchService.envOverride = Map.of("USER_TYPE", "ant");

        List<Tool> tools = List.of(
            tool("mcp__docs-server__search", true),
            tool("ToolSearch", false));

        assertThat(PostCompactAttachmentRestorer.deferredToolsDeltaAttachment(
                tools, MODEL, ANTHROPIC, List.of()))
            .as("DB=false 覆盖 env=true → delta 关闭")
            .isNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // 小工具
    // ════════════════════════════════════════════════════════════════════

    /** 造带 DB 值的统一读源（mapper.selectOneById(1) 返回该行）。 */
    private static PromptAlignSettingsResolver resolverWith(Boolean dbValue) {
        SettingsMapper mapper = Mockito.mock(SettingsMapper.class);
        SettingsRecord row = new SettingsRecord();
        row.setDeferredToolsDeltaEnabled(dbValue);
        Mockito.when(mapper.selectOneById(1)).thenReturn(row);
        PromptAlignSettingsResolver r = new PromptAlignSettingsResolver();
        r.setSettingsMapper(mapper);
        return r;
    }

    private static LlmAgentLoop.ToolsAssembly assembly(List<Tool> available) {
        ToolUseContext tuc = new ToolUseContext(
            UUID.randomUUID(), "sess-test", PermissionMode.DEFAULT, Map.of(),
            available, null, AbortController.NOOP, List.of(),
            (ToolPermissionContext) null, PermissionMode.DEFAULT)
            .withEffectiveProviderType(ANTHROPIC);
        return LlmAgentLoop.llmToolsArray(tuc, QuerySource.USER, List.of(), MODEL);
    }

    /** 假 Tool（isMcp → 恒 deferred，CC isDeferredTool MCP 分支）。 */
    private static Tool tool(String name, boolean mcp) {
        return new Tool() {
            @Override
            public String name() { return name; }
            @Override
            public String description() { return name + " desc"; }
            @Override
            public JsonNode inputSchema() { return JsonNodeFactory.instance.objectNode(); }
            @Override
            public AgentToolResult<?> execute(ToolUseBlock call) { return null; }
            @Override
            public boolean isMcp() { return mcp; }
            @Override
            public boolean shouldDefer(JsonNode input) { return mcp; }
        };
    }
}
