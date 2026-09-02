package com.nexusai.application.agent.toolsearch;

import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.impl.BashTool;
import com.nexusai.application.agent.tool.impl.ToolSearchTool;
import com.nexusai.application.agent.tool.impl.WebSearchTool;
import com.nexusai.infra.llm.CountTokensClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [H4] ToolSearch definitive 门控测试 · 对齐 CC toolSearch.ts:385-473 {@code isToolSearchEnabled}
 * （modelSupportsToolReference :239-252 + isToolSearchToolAvailable + mode + tst-auto 阈值）+
 * {@code filteredTools}（claude.ts:1154-1172）+ {@code isDeferredToolsDeltaEnabled}
 * （toolSearch.ts:629-633）。
 *
 * <p>WHY: H3 的 isToolSearchEnabledOptimistic 只是乐观门控（mode≠standard 恒 true），主循环
 * schema 必须走 definitive 门控（model 支持 tool_reference + ToolSearch 可用 + 阈值）——
 * 否则 haiku 模型会被塞入 tool_reference 产物（无法解析）导致 API 报错，definitive 门控
 * 是 CC claude.ts:1120 的真实入口行为。
 *
 * <p>变异点：删 modelSupportsToolReference 负向模式 → haiku 用例变红；删 filteredTools
 * discovered 过滤 → 未发现 deferred 工具错误入 schema 变红；短路/auto 边界缺一即红。
 */
class ToolSearchServiceDefinitiveGateTest {

    /** Bash(非 deferred) + ToolSearch + WebSearch(shouldDefer=true，H3 接线) · 均非 SPECIAL_TOOLS. */
    private static final List<Tool> TOOLS_WITH_SEARCH =
            List.of(new BashTool(), new ToolSearchTool(), new WebSearchTool());
    /** 无 ToolSearch 的列表（模拟 disallowedTools 剔除）. */
    private static final List<Tool> TOOLS_NO_SEARCH = List.of(new BashTool(), new WebSearchTool());

    @AfterEach
    void resetEnv() {
        ToolSearchService.envOverride = null;
        // IMP-C6 memoize：getDeferredToolTokenCount 按 deferred 工具名缓存（toolSearch.ts:124-152），
        // 测试间必须隔离（tokenPath_takesPrecedence 缓存高 token 值 → 污染后续 null-client 用例的
        // char fallback 断言）。生产 MCP connect/disconnect 显式失效（OPD-IMP-30）。
        ToolSearchService.invalidateDeferredToolTokenCountCache();
    }

    @Test
    @DisplayName("modelSupportsToolReference 负向模式：haiku 命中 → false；其余模型 → true")
    void modelSupportsToolReference_negativePattern() {
        // WHY: CC toolSearch.ts:241-244 负向匹配（新模型默认支持 tool_reference）。
        //   变异点：默认不支持列表改用正向白名单 → haiku 之外也会误杀 → 红。
        assertThat(ToolSearchService.modelSupportsToolReference("claude-haiku-4-5")).isFalse();
        assertThat(ToolSearchService.modelSupportsToolReference("claude-3-5-haiku")).isFalse();
        assertThat(ToolSearchService.modelSupportsToolReference("claude-sonnet-4-5")).isTrue();
        assertThat(ToolSearchService.modelSupportsToolReference("claude-opus-4-1")).isTrue();
    }

    @Test
    @DisplayName("isToolSearchEnabled：haiku 模型恒 false（不支持 tool_reference，即使 ToolSearch 可用）")
    void isToolSearchEnabled_haikuModel_alwaysFalse() {
        // WHY: CC toolSearch.ts:411-418 —— tool_reference 仅 Sonnet 4+ / Opus 4+ 支持，
        //   haiku 收到 tool_reference 会解析失败；门控必须在主循环 schema 构建前拦截。
        ToolSearchService.envOverride = Map.of();
        assertThat(ToolSearchService.isToolSearchEnabled(TOOLS_WITH_SEARCH, "claude-haiku-4-5")).isFalse();
    }

    @Test
    @DisplayName("isToolSearchEnabled：ToolSearch 不在列表 → false（respects disallowedTools，CC :420-427）")
    void isToolSearchEnabled_noSearchTool_false() {
        ToolSearchService.envOverride = Map.of();
        assertThat(ToolSearchService.isToolSearchEnabled(TOOLS_NO_SEARCH, "claude-sonnet-4-5")).isFalse();
    }

    @Test
    @DisplayName("isToolSearchEnabled：model 支持 + ToolSearch 可用 + 默认 mode(tst) → true")
    void isToolSearchEnabled_defaultMode_true() {
        // WHY: ENABLE_TOOL_SEARCH 未设置 → mode='tst'（CC toolSearch.ts:197 默认启用）。
        ToolSearchService.envOverride = Map.of();
        assertThat(ToolSearchService.isToolSearchEnabled(TOOLS_WITH_SEARCH, "claude-sonnet-4-5")).isTrue();
    }

    @Test
    @DisplayName("isToolSearchEnabled：ENABLE_TOOL_SEARCH=0（standard）→ false")
    void isToolSearchEnabled_standardMode_false() {
        ToolSearchService.envOverride = Map.of("ENABLE_TOOL_SEARCH", "0");
        assertThat(ToolSearchService.isToolSearchEnabled(TOOLS_WITH_SEARCH, "claude-sonnet-4-5")).isFalse();
    }

    @Test
    @DisplayName("isToolSearchEnabled：auto:100（standard）→ false；auto:0（tst）→ true（CC :189-190）")
    void isToolSearchEnabled_autoEdges() {
        ToolSearchService.envOverride = Map.of("ENABLE_TOOL_SEARCH", "auto:100");
        assertThat(ToolSearchService.isToolSearchEnabled(TOOLS_WITH_SEARCH, "claude-sonnet-4-5")).isFalse();

        ToolSearchService.envOverride = Map.of("ENABLE_TOOL_SEARCH", "auto:0");
        assertThat(ToolSearchService.isToolSearchEnabled(TOOLS_WITH_SEARCH, "claude-sonnet-4-5")).isTrue();
    }

    @Test
    @DisplayName("isToolSearchEnabled：tst-auto 阈值 char fallback（默认 10%×200k×2.5=50k chars）→ 小工具集 false")
    void isToolSearchEnabled_tstAuto_belowThreshold_false() {
        // WHY: CC toolSearch.ts:742-754 char fallback（token 计数不可得时）。默认阈值 50,000 chars，
        //   Bash/ToolSearch/WebSearch 描述远低于 → deferred 工具量不足时自动模式保持关闭（防全量预声明）。
        ToolSearchService.envOverride = Map.of("ENABLE_TOOL_SEARCH", "auto");
        assertThat(ToolSearchService.isToolSearchEnabled(TOOLS_WITH_SEARCH, "claude-sonnet-4-5")).isFalse();
    }

    @Test
    @DisplayName("isToolSearchEnabled：tst-auto token 优先——count_tokens 返回超阈值 → true（char fallback 本应 false）")
    void isToolSearchEnabled_tstAuto_tokenPath_takesPrecedence() {
        // WHY: CC checkAutoThreshold toolSearch.ts:712-738 先走精确 token 计数，非 null 即用 token 阈值
        //   （不回退 char）。char fallback（50k chars）下 WebSearch 描述远低于阈值 → false；
        //   但 token client 返回 100_000（raw）→ max(0,100000-500) ≥ 10%×200k=20_000 tokens → true。
        //   若实现退化回纯 char fallback（或 token 结果被忽略），本用例变红——锁定 token 优先契约。
        CountTokensClient highTokenClient = new CountTokensClient() {
            @Override public Integer countTokens(String content) { return 0; }
            @Override public Integer countTokensForTools(List<CountTokensClient.ToolSchema> tools) {
                return 100_000;
            }
        };
        ToolSearchService.envOverride = Map.of("ENABLE_TOOL_SEARCH", "auto");
        assertThat(ToolSearchService.isToolSearchEnabled(TOOLS_WITH_SEARCH, "claude-sonnet-4-5", highTokenClient))
                .isTrue();
    }

    @Test
    @DisplayName("isToolSearchEnabled：tst-auto token client 返回 null → 回退 char fallback（小工具集 false）")
    void isToolSearchEnabled_tstAuto_tokenNull_fallsBackToChar() {
        // WHY: CC getDeferredToolTokenCount toolSearch.ts:133-136 API 不可得（null）→ char fallback。
        //   token client 返回 null 时不得改变 char fallback 结论（小工具集 → false）。
        CountTokensClient nullTokenClient = new CountTokensClient() {
            @Override public Integer countTokens(String content) { return null; }
            @Override public Integer countTokensForTools(List<CountTokensClient.ToolSchema> tools) { return null; }
        };
        ToolSearchService.envOverride = Map.of("ENABLE_TOOL_SEARCH", "auto");
        assertThat(ToolSearchService.isToolSearchEnabled(TOOLS_WITH_SEARCH, "claude-sonnet-4-5", nullTokenClient))
                .isFalse();
    }

    @Test
    @DisplayName("filterToolsForSchema：useToolSearch=true 三支（非 deferred 恒留 / ToolSearch 恒留 / deferred 仅 discovered 含才留）")
    void filterToolsForSchema_enabledThreeBranches() {
        // WHY: claude.ts:1163-1168 动态工具加载语义——未预声明的 deferred 工具绝不进 schema，
        //   只有消息历史 tool_reference 已发现的才发送（消除全量预声明 + 数量上限）。
        Set<String> deferred = Set.of("WebSearch");

        List<Tool> noDiscovery = ToolSearchService.filterToolsForSchema(TOOLS_WITH_SEARCH, true, deferred, Set.of());
        assertThat(names(noDiscovery))
                .as("deferred 未发现 → 剔除 WebSearch，Bash/ToolSearch 恒留")
                .containsExactlyInAnyOrder("Bash", "ToolSearch");

        List<Tool> withDiscovery = ToolSearchService.filterToolsForSchema(TOOLS_WITH_SEARCH, true, deferred, Set.of("WebSearch"));
        assertThat(names(withDiscovery))
                .as("deferred 已发现 → 三工具全留")
                .containsExactlyInAnyOrder("Bash", "ToolSearch", "WebSearch");
    }

    @Test
    @DisplayName("filterToolsForSchema：useToolSearch=false（openai-lazy）→ ToolSearch 保留 + deferred 过滤（懒加载始终成立）")
    void filterToolsForSchema_disabled_openaiLazy() {
        // WHY: [openai-lazy] Java 扩展偏离 CC claude.ts:1170-1172（排除 ToolSearch + 全发）——
        //   openai_compatible（deepseek）无 tool_reference，ToolSearch 是唯一拿到 defer 工具完整 schema
        //   的通道（命中返回 <functions> 文本），排除则死锁；deferred 照常过滤（懒加载不默认占 prompt），
        //   discovered/activated 例外（activate-on-search 控制）。
        // ① 无 deferred（短路）→ 排除 ToolSearch（无对象可搜，对齐 CC claude.ts:1140-1147 短路 + :1170-1172）
        List<Tool> noDeferred = ToolSearchService.filterToolsForSchema(TOOLS_WITH_SEARCH, false, null, null);
        assertThat(names(noDeferred))
                .as("useToolSearch=false 且无 deferred（短路）→ 排除 ToolSearch")
                .containsExactlyInAnyOrder("Bash", "WebSearch");
        // ② deferred 存在但未发现/未激活 → WebSearch 过滤，ToolSearch 保留（懒加载）
        List<Tool> withDeferred = ToolSearchService.filterToolsForSchema(
                TOOLS_WITH_SEARCH, false, Set.of("WebSearch"), Set.of());
        assertThat(names(withDeferred))
                .as("deferred 未激活 → 剔除 WebSearch，Bash/ToolSearch 恒留（懒加载）")
                .containsExactlyInAnyOrder("Bash", "ToolSearch");
        // ③ deferred 已 discovered/activated → 保留
        List<Tool> activated = ToolSearchService.filterToolsForSchema(
                TOOLS_WITH_SEARCH, false, Set.of("WebSearch"), Set.of("WebSearch"));
        assertThat(names(activated))
                .as("deferred 已发现/激活 → 三工具全留")
                .containsExactlyInAnyOrder("Bash", "ToolSearch", "WebSearch");
    }

    @Test
    @DisplayName("[mode=full] filterToolsForSchema：全发（含 deferred，排除 ToolSearch，无搜索环节）")
    void filterToolsForSchema_modeFull_sendsAll() {
        // WHY: mode=full（全发）→ 对齐旧「完整 schema 模式」：所有工具含 defer 直接进 schema，
        //   模型直接调用；ToolSearch 排除（无搜索环节，对齐 CC claude.ts:1170-1172）。
        ToolSearchService.modeOverride = "full";
        try {
            List<Tool> filtered = ToolSearchService.filterToolsForSchema(
                    TOOLS_WITH_SEARCH, false, Set.of("WebSearch"), Set.of());
            assertThat(names(filtered))
                    .as("mode=full → 全量（含 deferred WebSearch），排除 ToolSearch")
                    .containsExactlyInAnyOrder("Bash", "WebSearch");
        } finally {
            ToolSearchService.modeOverride = null;
        }
    }

    @Test
    @DisplayName("[mode=activate] filterToolsForSchema：懒加载同 search（deferred 过滤 + ToolSearch 保留）")
    void filterToolsForSchema_modeActivate_lazyLikeSearch() {
        // WHY: mode=activate 的过滤行为与 search 相同（ToolSearch 保留 + deferred 过滤）；
        //   区别只在 activateTools 是否写入激活集（activate → 是，下轮进 API tools）。
        //   ACTIVATED_TOOLS 私有不可直注 → 验证过滤形状（懒加载成立）+ 门控不破坏。
        ToolSearchService.modeOverride = "activate";
        try {
            List<Tool> filtered = ToolSearchService.filterToolsForSchema(
                    TOOLS_WITH_SEARCH, false, Set.of("WebSearch"), Set.of());
            assertThat(names(filtered))
                    .as("mode=activate → deferred WebSearch 未激活 → 剔除，Bash/ToolSearch 保留")
                    .containsExactlyInAnyOrder("Bash", "ToolSearch");
        } finally {
            ToolSearchService.modeOverride = null;
        }
    }

    @Test
    @DisplayName("[mode] 三态互斥解析：search（默认）/ activate / full / 非法值回落 search")
    void modeEnum_threeStates() {
        // WHY: 用户拍板 openai 三态互斥（search | activate | full）——一个键切换，
        //   非法值/未配 → 回落默认 search（懒加载，最安全）。
        try {
            ToolSearchService.modeOverride = "full";
            assertThat(ToolSearchService.isFullSchemaMode()).isTrue();
            assertThat(ToolSearchService.isActivateMode()).isFalse();
            ToolSearchService.modeOverride = "activate";
            assertThat(ToolSearchService.isActivateMode()).isTrue();
            assertThat(ToolSearchService.isFullSchemaMode()).isFalse();
            ToolSearchService.modeOverride = "search";
            assertThat(ToolSearchService.isActivateMode()).isFalse();
            assertThat(ToolSearchService.isFullSchemaMode()).isFalse();
            ToolSearchService.modeOverride = "bogus";
            assertThat(ToolSearchService.isActivateMode()).isFalse();
            assertThat(ToolSearchService.isFullSchemaMode()).isFalse();
        } finally {
            ToolSearchService.modeOverride = null;
        }
    }

    @Test
    @DisplayName("isDeferredToolsDeltaEnabled：默认 false（USER_TYPE≠ant + glacier flag Java N/A）")
    void isDeferredToolsDeltaEnabled_defaultFalse() {
        // WHY: toolSearch.ts:629-633 —— false → claude.ts:1330 prepend 路径（H4 实现）；
        //   true → 完整 deferred_tools_delta attachment（OPD-H-06 残留）。USER_TYPE 读 System.getenv
        //   只读不可注入，此处仅断言默认非 'ant' 环境 → false。
        assertThat(ToolSearchService.isDeferredToolsDeltaEnabled()).isFalse();
    }

    private static List<String> names(List<Tool> tools) {
        return tools.stream().map(Tool::name).toList();
    }
}
