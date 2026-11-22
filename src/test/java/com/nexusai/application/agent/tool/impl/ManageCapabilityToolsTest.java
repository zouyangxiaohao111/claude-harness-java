package com.nexusai.application.agent.tool.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.plugin.PluginInstallService;
import com.nexusai.application.agent.skill.InstallPluginRegistrar;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.domain.mcp.McpServerService;
import com.nexusai.model.market.dto.PluginInstallRequest;
import com.nexusai.model.market.dto.PluginInstallResult;
import com.nexusai.model.mcp.dto.McpServerDto;
import com.nexusai.model.mcp.dto.McpStatus;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * ManagePlugin / ManageMcpServer 工具测试（规则九：测意图）。
 *
 * <p><b>WHY（意图）</b>：两个工具把「装/列/卸」三类动作合成一个（按 {@code action} 分发），
 * 省掉 4 个工具的 name/description/prompt/schema 成本。合成后最需要钉住的是：
 * <ol>
 *   <li><b>action 分发正确</b> —— 合成工具最容易错的就是「拿了别的 action 的参数」；</li>
 *   <li><b>破坏性动作的 confirmed 守卫</b> —— 未确认 ⇒ Service <b>零交互</b>；</li>
 *   <li><b>凭证脱敏</b> —— 列表绝不能回显 env/headers 的值（否则 API key 进 LLM 上下文）；</li>
 *   <li><b>注册不漏</b> —— @Component + CC_TOOL_ORDER 两处都在（漏了 ⇒ 工具静默不出现）。</li>
 * </ol>
 */
@DisplayName("ManagePlugin / ManageMcpServer：装/列/卸合成工具")
class ManageCapabilityToolsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ToolUseBlock call(String tool, ObjectNode input) {
        return new ToolUseBlock("c1", tool, input);
    }

    private static ObjectNode input(String action) {
        ObjectNode in = JSON.createObjectNode();
        in.put("action", action);
        return in;
    }

    // ── ManagePlugin ────────────────────────────────────────────────

    @Test
    @DisplayName("action 非法 → 报错且不碰任何服务")
    void unknownActionIsRejected() {
        PluginInstallService svc = mock(PluginInstallService.class);
        ManagePluginTool tool = new ManagePluginTool(svc);

        ToolResult<?> r = (ToolResult<?>) tool.execute(call("ManagePlugin", input("reinstall")));

        assertThat(String.valueOf(r.data())).startsWith("Error:").contains("action 必须是");
        assertThat(LlmAgentLoop.isToolErrorData(r.data())).isTrue();
        verify(svc, never()).install(any());
        verify(svc, never()).uninstall(anyString());
    }

    @Test
    @DisplayName("action=install → 走安装服务，市场 URL 由后端注入（模型不猜）")
    void installPassesThroughToService() {
        PluginInstallService svc = mock(PluginInstallService.class);
        when(svc.install(any())).thenReturn(new PluginInstallResult(true, "ok",
            "zjky-code-reviewer@zjky-market", "zjky-code-reviewer", "user"));
        ObjectNode in = input("install");
        in.put("pluginId", "zjky-code-reviewer@zjky-market");

        ToolResult<?> r = (ToolResult<?>) new ManagePluginTool(svc).execute(call("ManagePlugin", in));

        ArgumentCaptor<PluginInstallRequest> cap = ArgumentCaptor.forClass(PluginInstallRequest.class);
        verify(svc).install(cap.capture());
        assertThat(cap.getValue().marketplaceUrl())
            .isEqualTo(InstallPluginRegistrar.DEFAULT_MARKET_MANIFEST_URL);
        assertThat(String.valueOf(r.data())).contains("zjky-code-reviewer");
    }

    @Test
    @DisplayName("action=uninstall 未确认 → 拒绝且 Service 零交互（用户没确认就不许删）")
    void uninstallWithoutConfirmationIsRejected() {
        PluginInstallService svc = mock(PluginInstallService.class);
        ObjectNode in = input("uninstall");
        in.put("name", "zjky-code-reviewer");

        ToolResult<?> r = (ToolResult<?>) new ManagePluginTool(svc).execute(call("ManagePlugin", in));

        assertThat(String.valueOf(r.data())).startsWith("Error:").contains("确认");
        verify(svc, never()).uninstall(anyString());
    }

    @Test
    @DisplayName("action=uninstall 已确认 → 走卸载服务")
    void uninstallWithConfirmationCallsService() {
        PluginInstallService svc = mock(PluginInstallService.class);
        when(svc.uninstall("zjky-code-reviewer")).thenReturn(
            new PluginInstallResult(true, "已卸载 zjky-code-reviewer", null, "zjky-code-reviewer", "user"));
        ObjectNode in = input("uninstall");
        in.put("name", "zjky-code-reviewer");
        in.put("confirmed", true);

        ToolResult<?> r = (ToolResult<?>) new ManagePluginTool(svc).execute(call("ManagePlugin", in));

        verify(svc).uninstall("zjky-code-reviewer");
        assertThat(String.valueOf(r.data())).contains("已卸载插件 zjky-code-reviewer");
    }

    // ── ManageMcpServer ─────────────────────────────────────────────

    @Test
    @DisplayName("action=add → 结构化字段原样交给服务层（command/args/env 不错位）")
    void addPassesStructuredFieldsThrough() {
        McpServerService svc = mock(McpServerService.class);
        when(svc.create(any())).thenReturn(new McpServerDto(
            "id1", "fs", "npx", List.of("-y"), Map.of("K", "v"),
            McpStatus.stopped, null, true, null, "stdio", "approved"));
        ObjectNode in = input("add");
        in.put("name", "fs");
        in.put("command", "npx");
        in.putArray("args").add("-y");
        in.putObject("env").put("K", "v");

        ToolResult<?> r = (ToolResult<?>) new ManageMcpServerTool(svc).execute(call("ManageMcpServer", in));

        ArgumentCaptor<com.nexusai.model.mcp.dto.McpCreateRequest> cap =
            ArgumentCaptor.forClass(com.nexusai.model.mcp.dto.McpCreateRequest.class);
        verify(svc).create(cap.capture());
        assertThat(cap.getValue().name()).isEqualTo("fs");
        assertThat(cap.getValue().command()).isEqualTo("npx");
        assertThat(cap.getValue().args()).containsExactly("-y");
        assertThat(cap.getValue().env()).containsEntry("K", "v");
        assertThat(String.valueOf(r.data())).contains("已添加 MCP server fs");
    }

    @Test
    @DisplayName("action=list → env/headers 的值绝不回显（凭证不进 LLM 上下文）")
    void listMasksCredentialValues() {
        McpServerService svc = mock(McpServerService.class);
        when(svc.listAll()).thenReturn(List.of(new McpServerDto(
            "id1", "fs", "npx", List.of("-y"),
            Map.of("API_KEY", "sk-super-secret"),
            McpStatus.stopped, null, true, null, "stdio", "approved")));

        ToolResult<?> r = (ToolResult<?>) new ManageMcpServerTool(svc).execute(call("ManageMcpServer", input("list")));

        String text = String.valueOf(r.data());
        assertThat(text).contains("API_KEY").contains("***");
        assertThat(text).doesNotContain("sk-super-secret");
    }

    @Test
    @DisplayName("action=remove 未确认 → 拒绝且 Service 零交互")
    void removeWithoutConfirmationIsRejected() {
        McpServerService svc = mock(McpServerService.class);
        ObjectNode in = input("remove");
        in.put("name", "fs");

        ToolResult<?> r = (ToolResult<?>) new ManageMcpServerTool(svc).execute(call("ManageMcpServer", in));

        assertThat(String.valueOf(r.data())).startsWith("Error:").contains("确认");
        verify(svc, never()).delete(anyString());
        verify(svc, never()).listAll();
    }

    @Test
    @DisplayName("action=remove 已确认 → 先断连再删（delete 不断连 ⇒ 工具会留在会话里）")
    void removeStopsBeforeDeleting() {
        McpServerService svc = mock(McpServerService.class);
        when(svc.listAll()).thenReturn(List.of(new McpServerDto(
            "id-1", "fs", "npx", List.of(), Map.of(),
            McpStatus.stopped, null, true, null, "stdio", "approved")));
        ObjectNode in = input("remove");
        in.put("name", "fs");
        in.put("confirmed", true);

        ToolResult<?> r = (ToolResult<?>) new ManageMcpServerTool(svc).execute(call("ManageMcpServer", in));

        InOrder inOrder = org.mockito.Mockito.inOrder(svc);
        inOrder.verify(svc).stop("id-1");
        inOrder.verify(svc).delete("id-1");
        assertThat(String.valueOf(r.data())).contains("已删除 MCP server fs");
    }

    @Test
    @DisplayName("schema 的 items 必须是 object 而不是 array（写成 array ⇒ 服务商 400，整个工具面一起挂）")
    void itemsMustBeObjectNotArray() {
        com.fasterxml.jackson.databind.JsonNode schema =
            new ManageMcpServerTool(mock(McpServerService.class)).inputSchema();
        com.fasterxml.jackson.databind.JsonNode items = schema.at("/properties/args/items");
        assertThat(items.isObject())
            .as("items 写成数组（旧 tuple 语法）= 非法 JSON Schema ⇒ OpenAiSdkProvider 回 400 '''Invalid schema'''"
                + " ⇒ 每轮请求都失败、连别的工具也用不了")
            .isTrue();
        assertThat(items.get("type").asText()).isEqualTo("string");
    }

    // ── 注册守卫 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("两个工具类都带 @Component（注册通道 A 的前提：没有它就静默不注册）")
    void toolsAreSpringComponents() {
        for (Class<?> c : List.of(ManagePluginTool.class, ManageMcpServerTool.class)) {
            assertThat(c.isAnnotationPresent(org.springframework.stereotype.Component.class))
                .as(c.getSimpleName() + " 缺 @Component ⇒ ToolRegistry 收集不到").isTrue();
            assertThat(com.nexusai.application.agent.tool.Tool.class.isAssignableFrom(c)).isTrue();
        }
    }

    @Test
    @DisplayName("工具 name() 与 ToolNameConstants 常量对齐，且都在 CC_TOOL_ORDER 里")
    @SuppressWarnings("unchecked")
    void registrationIsComplete() throws Exception {
        assertThat(new ManagePluginTool(mock(PluginInstallService.class)).name())
            .isEqualTo(ToolNameConstants.MANAGE_PLUGIN_TOOL_NAME);
        assertThat(new ManageMcpServerTool(mock(McpServerService.class)).name())
            .isEqualTo(ToolNameConstants.MANAGE_MCP_SERVER_TOOL_NAME);

        Field f = ToolRegistry.class.getDeclaredField("CC_TOOL_ORDER");
        f.setAccessible(true);
        List<String> order = (List<String>) f.get(null);
        assertThat(order).contains(
            ToolNameConstants.MANAGE_PLUGIN_TOOL_NAME,
            ToolNameConstants.MANAGE_MCP_SERVER_TOOL_NAME);
    }
}
