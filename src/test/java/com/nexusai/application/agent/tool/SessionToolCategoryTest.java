package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.kb.agent.tool.FakeKbToolForCategoryTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SessionToolCategory} 分类判定单测 · ⛔ 纯 POJO，不启动 Spring（不碰用户真库）。
 *
 * <p>验证意图（WHY）：分类是前端面板分组的唯一来源。判错一类的后果不是报错，而是
 * 静默把工具归错组 —— 例如把电脑操作工具归进 SYS，用户在一级目录看到「系统工具 N 个」
 * 却永远在电脑操作组里找不到它，且「默认禁用电脑操作」会放过真实的桌面控制工具。
 * 故逐条钉死四条判据（前缀 × 2 / 包名 / 兜底）。
 */
class SessionToolCategoryTest {

    @AfterEach
    void tearDown() {
        SessionToolCategory.reset();
    }

    /** 最小 Tool 实现（只实现 4 个必需方法，其余走接口 default）。 */
    static Tool tool(String name) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "test tool " + name;
            }

            @Override
            public JsonNode inputSchema() {
                return null;
            }

            @Override
            public AgentToolResult<?> execute(ToolUseBlock call) {
                throw new UnsupportedOperationException("test tool never executes");
            }
        };
    }

    @Test
    @DisplayName("mcp__computer-use__ 前缀 → CU（电脑操作）")
    void computerUseMcpTool_isCu() {
        assertEquals(SessionToolCategory.CU, SessionToolCategory.of(tool("mcp__computer-use__screenshot")));
        assertEquals(SessionToolCategory.CU, SessionToolCategory.of(tool("mcp__computer-use__left_click")));
    }

    @Test
    @DisplayName("mcp__nexusai-in-chrome__ 前缀 → BR（浏览器控制）")
    void browserMcpTool_isBr() {
        assertEquals(SessionToolCategory.BR, SessionToolCategory.of(tool("mcp__nexusai-in-chrome__read_page")));
        assertEquals(SessionToolCategory.BR, SessionToolCategory.of(tool("mcp__nexusai-in-chrome__navigate")));
    }

    @Test
    @DisplayName("实现类在 com.nexusai.kb 包下 → KB（知识库）")
    void kbPackageTool_isKb() {
        // ⚠️ 必须用测试源里 com.nexusai.kb 包下的真实类：匿名类包名恒为测试类所在包，
        //    造不出「属于 kb 包」的假工具（这正是本用例单独落一个测试源文件的原因）。
        assertEquals(SessionToolCategory.KB,
            SessionToolCategory.of(new FakeKbToolForCategoryTest("search_knowledge")));
        assertEquals(SessionToolCategory.KB,
            SessionToolCategory.of(new FakeKbToolForCategoryTest("wiki_read_page")));
    }

    @Test
    @DisplayName("其余工具 → SYS（系统工具），含裸名与其它 mcp 前缀")
    void others_areSys() {
        assertEquals(SessionToolCategory.SYS, SessionToolCategory.of(tool("Bash")));
        assertEquals(SessionToolCategory.SYS, SessionToolCategory.of(tool("Agent")));
        assertEquals(SessionToolCategory.SYS, SessionToolCategory.of(tool("mcp__some-other-server__x")));
    }

    @Test
    @DisplayName("null 工具 → SYS（防御，不 NPE）")
    void nullTool_isSys() {
        assertEquals(SessionToolCategory.SYS, SessionToolCategory.of(null));
    }

    @Test
    @DisplayName("注册表未注入 → 默认禁用集为空（不误禁）")
    void defaultDisabledNames_noRegistry_isEmpty() {
        SessionToolCategory.reset();
        assertTrue(SessionToolCategory.defaultDisabledNamesStatic().isEmpty());
    }

    @Test
    @DisplayName("默认禁用集只含 CU + KB，不含 BR（浏览器组默认开）")
    void defaultDisabledNames_onlyCuAndKb() {
        ToolRegistry registry = new ToolRegistry();
        registry.registerAll(java.util.List.of(
            tool("mcp__computer-use__screenshot"),
            tool("mcp__computer-use__key"),
            new FakeKbToolForCategoryTest("search_knowledge"),
            tool("mcp__nexusai-in-chrome__read_page"),
            tool("Bash"),
            tool("Agent")));
        SessionToolCategory.setToolRegistry(registry);

        java.util.Set<String> defaults = SessionToolCategory.defaultDisabledNamesStatic();

        assertTrue(defaults.contains("mcp__computer-use__screenshot"), "电脑操作应默认禁用");
        assertTrue(defaults.contains("mcp__computer-use__key"), "电脑操作应默认禁用");
        assertTrue(defaults.contains("search_knowledge"), "知识库应默认禁用");
        assertFalse(defaults.contains("mcp__nexusai-in-chrome__read_page"),
            "⛔ 浏览器组默认「开」，不得进入默认禁用集");
        assertFalse(defaults.contains("Bash"), "⛔ 系统工具默认开，不得进入默认禁用集");
        assertFalse(defaults.contains("Agent"), "⛔ 核心编排工具不得进入默认禁用集");
        assertEquals(3, defaults.size(), "默认禁用集应恰好 3 项（2 CU + 1 KB）");
    }
}
