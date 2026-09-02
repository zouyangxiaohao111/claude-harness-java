package com.nexusai.application.agent.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BrowserToolRegistry 18 个 nexusai-in-chrome 浏览器工具移植验证。
 *
 * <p><b>WHY（意图验证，规则九）</b> — 这些行为为何重要：
 * <ul>
 *   <li><b>18 工具必须全部注册</b>：模型需要看到 {@code mcp__nexusai-in-chrome__*} 全量工具集，
 *       否则 skill 激活后（NexusaiInChromeSkill.SKILL_ACTIVATION_MESSAGE 引导调 tabs_context_mcp）
 *       部分工具不可见 → 用户期望的浏览器自动化能力残缺。</li>
 *   <li><b>description / inputSchema 非空且含 required</b>：模型按 description 判断何时调用、
 *       按 schema 生成参数；required 缺失 → 模型可能漏传必填参数，扩展执行失败。</li>
 *   <li><b>通道未注入 → fail loud</b>：本阶段 WS 通道未实现，模型调用浏览器工具时不得静默假成功
 *       —— 必须返回「浏览器扩展未连接，请先连接 NexusAI in Chrome 扩展」，让模型/用户知道需先连接。</li>
 *   <li><b>通道注入 → 转发</b>：BrowserChannel 接线后（后续 WS 批次）执行真实转发，工具面
 *       契约先行闭环。</li>
 * </ul>
 */
@DisplayName("BrowserToolRegistry 18 个浏览器工具移植（CCB browserTools.ts）")
class BrowserToolRegistryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 18 个工具全名（前缀 mcp__nexusai-in-chrome__ + CCB browserTools.ts 原名）。 */
    private static final List<String> EXPECTED_NAMES = List.of(
        "mcp__nexusai-in-chrome__javascript_tool",
        "mcp__nexusai-in-chrome__read_page",
        "mcp__nexusai-in-chrome__find",
        "mcp__nexusai-in-chrome__form_input",
        "mcp__nexusai-in-chrome__computer",
        "mcp__nexusai-in-chrome__navigate",
        "mcp__nexusai-in-chrome__resize_window",
        "mcp__nexusai-in-chrome__gif_creator",
        "mcp__nexusai-in-chrome__upload_image",
        "mcp__nexusai-in-chrome__get_page_text",
        "mcp__nexusai-in-chrome__tabs_context_mcp",
        "mcp__nexusai-in-chrome__tabs_create_mcp",
        "mcp__nexusai-in-chrome__update_plan",
        "mcp__nexusai-in-chrome__read_console_messages",
        "mcp__nexusai-in-chrome__read_network_requests",
        "mcp__nexusai-in-chrome__shortcuts_list",
        "mcp__nexusai-in-chrome__shortcuts_execute",
        "mcp__nexusai-in-chrome__switch_browser"
    );

    @Test
    @DisplayName("18 个工具全部注册：name/description 非空/inputSchema 有 required 字段")
    void registersAll18BrowserTools() {
        List<Tool> tools = BrowserToolRegistry.createTools(null);

        assertThat(tools)
            .as("必须恰好 18 个 nexusai-in-chrome 浏览器工具（对齐 CCB browserTools.ts 18 个 BROWSER_TOOLS）")
            .hasSize(18);

        List<String> names = tools.stream().map(Tool::name).toList();
        assertThat(names)
            .as("工具全名必须 = mcp__nexusai-in-chrome__<CCB原名>（对齐 NexusaiInChromeSkill.TOOL_PREFIX）")
            .containsExactlyInAnyOrderElementsOf(EXPECTED_NAMES);

        for (Tool t : tools) {
            assertThat(t.description())
                .as("%s 的 description 必须非空（模型判断何时调用的关键文案）", t.name())
                .isNotBlank();
            JsonNode schema = t.inputSchema();
            assertThat(schema.path("type").asText())
                .as("%s 的 inputSchema.type 必须为 object", t.name())
                .isEqualTo("object");
            assertThat(schema.path("required").isArray())
                .as("%s 的 inputSchema 必须含 required 数组（模型漏参 → 扩展执行失败）", t.name())
                .isTrue();
        }
    }

    @Test
    @DisplayName("关键 schema 保真：read_page filter enum + form_input value 多类型 + computer 13 种 action")
    void keySchemasMatchCcb() {
        List<Tool> tools = BrowserToolRegistry.createTools(null);

        // read_page.filter: enum ["interactive","all"]（CCB browserTools.ts:36）
        JsonNode readPage = schemaOf(tools, "mcp__nexusai-in-chrome__read_page");
        JsonNode filter = readPage.path("properties").path("filter");
        assertThat(filter.path("enum")).hasSize(2);
        assertThat(filter.path("enum").get(0).asText()).isEqualTo("interactive");
        assertThat(filter.path("enum").get(1).asText()).isEqualTo("all");

        // form_input.value: type ["string","boolean","number"]（CCB browserTools.ts:98）
        JsonNode formInput = schemaOf(tools, "mcp__nexusai-in-chrome__form_input");
        JsonNode valueType = formInput.path("properties").path("value").path("type");
        assertThat(valueType.isArray()).isTrue();
        assertThat(valueType).hasSize(3);
        assertThat(valueType.get(0).asText()).isEqualTo("string");
        assertThat(valueType.get(1).asText()).isEqualTo("boolean");
        assertThat(valueType.get(2).asText()).isEqualTo("number");

        // computer.action enum 13 种（CCB browserTools.ts:119-133）
        JsonNode computer = schemaOf(tools, "mcp__nexusai-in-chrome__computer");
        JsonNode action = computer.path("properties").path("action");
        assertThat(action.path("enum")).hasSize(13);
        assertThat(action.path("enum").get(0).asText()).isEqualTo("left_click");
        assertThat(action.path("enum").get(12).asText()).isEqualTo("hover");
        // computer required = ["action","tabId"]（CCB browserTools.ts:208）
        assertThat(computer.path("required").get(0).asText()).isEqualTo("action");
        assertThat(computer.path("required").get(1).asText()).isEqualTo("tabId");
    }

    @Test
    @DisplayName("通道未注入（null）→ execute fail loud 返回「浏览器扩展未连接」")
    void failsLoudWhenChannelNotInjected() {
        List<Tool> tools = BrowserToolRegistry.createTools(null);
        Tool tool = byName(tools, "mcp__nexusai-in-chrome__read_page");

        ObjectNode input = JSON.createObjectNode();
        input.put("tabId", 1);
        ToolResult<String> r = executeAsString(tool, new ToolUseBlock("b1", tool.name(), input));

        assertThat(r.data())
            .as("WS 通道未接线时调用浏览器工具必须 fail loud，不得静默假成功")
            .contains("浏览器扩展未连接");
    }

    @Test
    @DisplayName("通道注入 → execute 真实转发（多会话 sessionId 透传，工具面契约先行闭环）")
    void forwardsWhenChannelInjected() {
        // 多会话并行：send 三参 (sessionId, tool, args)——sessionId 由 execute 读 RequestContext 透传
        BrowserChannel channel = (sessionId, tool, args) -> "ok:" + tool + ":session=" + sessionId + ":action=" + args.get("action");
        List<Tool> tools = BrowserToolRegistry.createTools(channel);
        Tool tool = byName(tools, "mcp__nexusai-in-chrome__javascript_tool");

        com.nexusai.common.RequestContext.setSession("sess-cc");
        try {
            ObjectNode input = JSON.createObjectNode();
            input.put("action", "javascript_exec");
            input.put("text", "1+1");
            input.put("tabId", 1);
            ToolResult<String> r = executeAsString(tool, new ToolUseBlock("b2", tool.name(), input));

            assertThat(r.data())
                .as("execute 必须把当前会话 sessionId 透传给 channel.send（扩展按它定位 tab 组）")
                .isEqualTo("ok:javascript_tool:session=sess-cc:action=javascript_exec");
        } finally {
            com.nexusai.common.RequestContext.clear();
        }
    }

    @Test
    @DisplayName("读类工具 readOnly/concurrencySafe=true；写类工具=false")
    void readWriteFlags() {
        List<Tool> tools = BrowserToolRegistry.createTools(null);

        assertThat(byName(tools, "mcp__nexusai-in-chrome__read_page").isReadOnly(null)).isTrue();
        assertThat(byName(tools, "mcp__nexusai-in-chrome__read_page").isConcurrencySafe(null)).isTrue();
        assertThat(byName(tools, "mcp__nexusai-in-chrome__get_page_text").isReadOnly(null)).isTrue();
        assertThat(byName(tools, "mcp__nexusai-in-chrome__read_console_messages").isReadOnly(null)).isTrue();
        assertThat(byName(tools, "mcp__nexusai-in-chrome__read_network_requests").isReadOnly(null)).isTrue();

        assertThat(byName(tools, "mcp__nexusai-in-chrome__computer").isReadOnly(null)).isFalse();
        assertThat(byName(tools, "mcp__nexusai-in-chrome__form_input").isReadOnly(null)).isFalse();
        assertThat(byName(tools, "mcp__nexusai-in-chrome__navigate").isReadOnly(null)).isFalse();
        assertThat(byName(tools, "mcp__nexusai-in-chrome__upload_image").isReadOnly(null)).isFalse();
    }

    @Test
    @DisplayName("registerAll 进 ToolRegistry 后，toOpenAiToolsArray 含 18 个 mcp__nexusai-in-chrome__* 工具（模型可见）")
    void registeredToolsAppearInLlmToolsArray() {
        // WHY: 工具注册的最终验收 = 模型 tools 列表可见。ToolRegistry.toOpenAiToolsArray() 是
        // LlmAgentLoop 每轮发给 LLM 的 schema 来源；registerAll 后必须含全部 18 个浏览器工具。
        com.nexusai.application.agent.tool.ToolRegistry registry =
            new com.nexusai.application.agent.tool.ToolRegistry();
        registry.registerAll(BrowserToolRegistry.createTools(null));

        java.util.Set<String> names = new java.util.HashSet<>();
        for (JsonNode n : registry.toOpenAiToolsArray()) {
            names.add(n.path("function").path("name").asText());
        }

        assertThat(names)
            .as("注册后模型 tools 列表必须含全部 18 个 mcp__nexusai-in-chrome__* 工具（经 toOpenAiToolsArray 发射）")
            .containsAll(EXPECTED_NAMES);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static Tool byName(List<Tool> tools, String name) {
        return tools.stream().filter(t -> name.equals(t.name())).findFirst().orElseThrow();
    }

    /** execute 返回 {@code AgentToolResult<?>}，本测试只关心 String data 路径 → 显式 cast（契约保证）。 */
    @SuppressWarnings("unchecked")
    private static ToolResult<String> executeAsString(Tool tool, ToolUseBlock call) {
        return (ToolResult<String>) tool.execute(call);
    }

    private static JsonNode schemaOf(List<Tool> tools, String name) {
        return byName(tools, name).inputSchema();
    }
}
