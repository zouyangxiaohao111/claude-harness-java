package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.toolsearch.ToolSearchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内置工具「常驻初始 tools 数组」豁免（{@code alwaysLoad}）· 2026-11-13。
 *
 * <p><b>CC 真源</b>（exe 2.1.323）：{@code isDeferredTool} 的<b>首条</b>规则是
 * {@code alwaysLoad} 豁免（优先级高于 {@code shouldDefer}），另有一份
 * {@code non_deferrable_builtins} 名单（{@code function tO()}）——但二进制里默认
 * {@code var c=[]} 为空，实际名单来自 GrowthBook / settings。本仓无该通道，按用户裁定把
 * 需要的工具直接写进工具自身的 {@code alwaysLoad()}。
 *
 * <p>判别点：{@code shouldDefer()} 保持 CC 原值 {@code true}，翻转发生在优先级更高的
 * {@code alwaysLoad()} 上 —— 断言必须同时钉住两条，否则把 {@code shouldDefer} 改成 false
 * 也能「看起来通过」。
 */
class BuiltinAlwaysLoadTest {

    @Test
    @DisplayName("WebFetch：shouldDefer 仍为 true，但 alwaysLoad 豁免使其不再被 defer")
    void webFetchIsAlwaysLoaded() {
        Tool tool = new WebFetchTool();
        assertTrue(tool.shouldDefer(null),
                "底层 shouldDefer 必须保持 CC 原值 true —— 豁免走的是优先级更高的 alwaysLoad 规则");
        assertTrue(tool.alwaysLoad());
        assertFalse(ToolSearchService.isDeferredTool(tool, null),
                "不 deferred ⇒ 不发 defer_loading、不进 deferred_tools_delta");
    }

    @Test
    @DisplayName("WebSearch：shouldDefer 仍为 true，但 alwaysLoad 豁免使其不再被 defer")
    void webSearchIsAlwaysLoaded() {
        Tool tool = new WebSearchTool();
        assertTrue(tool.shouldDefer(null));
        assertTrue(tool.alwaysLoad());
        assertFalse(ToolSearchService.isDeferredTool(tool, null));
    }

    @Test
    @DisplayName("AskUserQuestion：用户 2026-11-13 裁定常驻（相对 CC 的刻意偏离，CC 侧仅 shouldDefer=true）")
    void askUserQuestionIsAlwaysLoaded() {
        Tool tool = new AskUserQuestionTool();
        assertTrue(tool.shouldDefer(null), "CC 原值保留，便于日后回退");
        assertTrue(tool.alwaysLoad());
        assertFalse(ToolSearchService.isDeferredTool(tool, null));
    }

    @Test
    @DisplayName("对照组：其余内置工具默认不豁免，仍按 shouldDefer 走 deferred")
    void otherBuiltinStillDeferred() {
        Tool tool = new NotebookEditTool();
        assertFalse(tool.alwaysLoad(), "默认值为 false，不得被本批改动波及");
        assertTrue(ToolSearchService.isDeferredTool(tool, null));
    }

    @Test
    @DisplayName("SendMessage：用户 2026-11-13 裁定常驻（CC 侧仅 shouldDefer=true，其常驻的是 SendUserMessage）")
    void sendMessageIsAlwaysLoaded() {
        Tool tool = new SendMessageTool(new com.nexusai.application.agent.team.TeamHelpers());
        assertTrue(tool.shouldDefer(null), "CC 原值保留，便于日后回退");
        assertTrue(tool.alwaysLoad());
        assertFalse(ToolSearchService.isDeferredTool(tool, null));
    }

    @Test
    @DisplayName("基础四件 Bash/Read/Grep/Glob：本就不 defer（shouldDefer 默认 false），恒在初始 schema")
    void basicFourAreNeverDeferred() {
        com.nexusai.application.agent.tool.PathGuard guard =
            new com.nexusai.application.agent.tool.PathGuard(java.nio.file.Path.of("."));
        Tool[] basics = {
            new BashTool(),
            new ReadFileTool(guard),
            new GrepTool(guard),
            new GlobTool(guard),
        };
        for (Tool tool : basics) {
            assertFalse(tool.shouldDefer(null), tool.name() + " 不应延迟执行");
            assertFalse(ToolSearchService.isDeferredTool(tool, null),
                tool.name() + " 不应进 deferred 集合（恒常驻）");
        }
    }

    @Test
    @DisplayName("CC 同款常驻（非本批新增）：ToolSearch / SyntheticOutput / SendUserMessage 本就不 defer")
    void ccForceLoadedOnesAlreadyAligned() {
        assertFalse(ToolSearchService.isDeferredTool(new ToolSearchTool(), null),
                "ToolSearch 自身永不 defer（isDeferredTool 第 3 条）");
        assertFalse(ToolSearchService.isDeferredTool(new SyntheticOutputTool(), null),
                "StructuredOutput 对齐 CC u() 里的 Ka 强制常驻");
        assertFalse(ToolSearchService.isDeferredTool(new BriefTool(), null),
                "SendUserMessage（CC BRIEF_TOOL_NAME）对齐 CC u() 里的 S 强制常驻");
    }
}
