package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code open_application} 单测（Phase 2a）。
 *
 * <p><b>WHY（规则 9）</b>：
 * <ul>
 *   <li><b>必须在会话白名单里</b>：CCB {@code toolCalls.ts:2845-2866} ——
 *       未授权的应用<b>不许被启动</b>（否则模型能让本机弹出任意程序）
 *       ⇒ 断言「未授权 ⇒ 不启动」；</li>
 *   <li><b>无档位门控</b>：CCB {@code toolCalls.ts:2868-2870} 明写
 *       “open_application works at any tier” ⇒ 断言 read 档也能启动
 *       （把它做成「只读授权不许开应用」是本仓自创的更严规则 = 与 CCB 不一致）。</li>
 * </ul>
 */
class ComputerUseOpenApplicationToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION = "sess-1";

    private static ObjectNode input(String app) {
        ObjectNode node = JSON.createObjectNode();
        node.put("app", app);
        return node;
    }

    private static ComputerUseGrantStore grantStore(String tier) {
        return ComputerUsePhase2aFakes.grantStore(SESSION,
                new ComputerUseGrants(List.of(new ComputerUseGrant("notepad", "记事本", 1L, tier)),
                        ComputerUseGrantFlags.DEFAULT).toJson());
    }

    @Test
    @DisplayName("工具名 / 描述逐字对齐 CCB tools.ts:402-404")
    void nameAndDescriptionAreVerbatim() {
        ComputerUseOpenApplicationTool tool = new ComputerUseOpenApplicationTool(
                new ComputerUsePhase2aFakes.FakeAppLauncher(), null);

        assertThat(tool.name()).isEqualTo("mcp__computer-use__open_application");
        assertThat(tool.description()).isEqualTo(
                "Bring an application to the front, launching it if necessary. "
                        + "The target application must already be in the session allowlist — call request_access first.");
    }

    @Test
    @DisplayName("schema 逐字对齐 CCB tools.ts:405-414（app 必填字符串）")
    void schemaIsVerbatim() {
        ComputerUseOpenApplicationTool tool = new ComputerUseOpenApplicationTool(
                new ComputerUsePhase2aFakes.FakeAppLauncher(), null);
        JsonNode schema = tool.inputSchema();

        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("properties").path("app").path("type").asText()).isEqualTo("string");
        assertThat(schema.path("properties").path("app").path("description").asText()).isEqualTo(
                "Display name (e.g. \"Slack\") or bundle identifier (e.g. \"com.tinyspeck.slackmacgap\").");
        assertThat(schema.path("required").get(0).asText()).isEqualTo("app");
        assertThat(schema.path("required")).hasSize(1);
    }

    @Test
    @DisplayName("未在会话白名单 ⇒ 拒绝且不启动（CCB toolCalls.ts:2861-2865 逐字文案）")
    void notGrantedAppIsRejected() {
        ComputerUsePhase2aFakes.FakeAppLauncher launcher = new ComputerUsePhase2aFakes.FakeAppLauncher();
        ComputerUseOpenApplicationTool tool = new ComputerUseOpenApplicationTool(launcher, grantStore("full"));

        var result = tool.execute(new ToolUseBlock("c1", tool.name(), input("chrome")),
                ToolUseContext.of(null, SESSION));

        assertThat(((ToolResult<?>) result).data().toString())
                .isEqualTo("\"chrome\" is not granted for this session. Call request_access first.");
        assertThat(launcher.launched).as("⛔ 未授权 ⇒ 不得启动应用").isEmpty();
    }

    @Test
    @DisplayName("已授权 ⇒ 启动并置前（成功文案逐字）")
    void grantedAppIsLaunched() {
        ComputerUsePhase2aFakes.FakeAppLauncher launcher = new ComputerUsePhase2aFakes.FakeAppLauncher();
        ComputerUseOpenApplicationTool tool = new ComputerUseOpenApplicationTool(launcher, grantStore("full"));

        var result = tool.execute(new ToolUseBlock("c1", tool.name(), input("notepad")),
                ToolUseContext.of(null, SESSION));

        assertThat(((ToolResult<?>) result).data()).isEqualTo("Opened \"notepad\".");
        assertThat(launcher.launched).containsExactly("notepad");
    }

    @Test
    @DisplayName("read 档也能启动（CCB toolCalls.ts:2868-2870：open_application 不做档位门控）")
    void readTierStillAllowsLaunch() {
        ComputerUsePhase2aFakes.FakeAppLauncher launcher = new ComputerUsePhase2aFakes.FakeAppLauncher();
        ComputerUseOpenApplicationTool tool = new ComputerUseOpenApplicationTool(launcher, grantStore("read"));

        var result = tool.execute(new ToolUseBlock("c1", tool.name(), input("notepad")),
                ToolUseContext.of(null, SESSION));

        assertThat(((ToolResult<?>) result).data()).isEqualTo("Opened \"notepad\".");
        assertThat(launcher.launched).containsExactly("notepad");
    }

    @Test
    @DisplayName("多显示器 ⇒ 追加「可能开在别的屏」提示（CCB toolCalls.ts:2877-2893 逐字文案）")
    void multiMonitorAddsHint() {
        ComputerUsePhase2aFakes.FakeAppLauncher launcher = new ComputerUsePhase2aFakes.FakeAppLauncher();
        ComputerUsePhase2aFakes.FakeDisplayProvider provider = new ComputerUsePhase2aFakes.FakeDisplayProvider();
        provider.displays.add(new ComputerUseDisplay(0, "display 0", 1920, 1080, 0, 0, true, ""));
        provider.displays.add(new ComputerUseDisplay(1, "display 1", 2560, 1440, 1920, 0, false, ""));
        ComputerUseOpenApplicationTool tool = new ComputerUseOpenApplicationTool(launcher, grantStore("full"), provider);

        var result = tool.execute(new ToolUseBlock("c1", tool.name(), input("notepad")),
                ToolUseContext.of(null, SESSION));

        assertThat(((ToolResult<?>) result).data()).isEqualTo(
                "Opened \"notepad\". If it isn't visible in the next screenshot, it may "
                        + "have opened on a different monitor — use switch_display to check.");
        assertThat(launcher.launched).containsExactly("notepad");
    }

    @Test
    @DisplayName("app 非字符串 ⇒ 报错（CCB toolCalls.ts:2842-2843 逐字文案）")
    void nonStringAppIsRejected() {
        ComputerUsePhase2aFakes.FakeAppLauncher launcher = new ComputerUsePhase2aFakes.FakeAppLauncher();
        ComputerUseOpenApplicationTool tool = new ComputerUseOpenApplicationTool(launcher, grantStore("full"));

        ObjectNode input = JSON.createObjectNode();
        input.put("app", 7);
        var result = tool.execute(new ToolUseBlock("c1", tool.name(), input),
                ToolUseContext.of(null, SESSION));

        assertThat(((ToolResult<?>) result).data().toString()).isEqualTo("\"app\" must be a string.");
        assertThat(launcher.launched).isEmpty();
    }
}
