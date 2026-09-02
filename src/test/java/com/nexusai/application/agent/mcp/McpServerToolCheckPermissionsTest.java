package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.PermissionUpdate;
import com.nexusai.application.agent.subagent.JsonRpcMcpClient;
import com.nexusai.application.agent.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P0-2 / MCP-SEC-02] McpServerTool.checkPermissions → Passthrough · 对齐 CC
 * {@code MCPTool.ts:56-61} + {@code client.ts:1814-1832}（mcp client 实例化时 override 为
 * passthrough）。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图）</b>: CC MCPTool 刻意<b>不表态</b>
 * （{@code behavior:'passthrough'}），把 mcp__ 工具的放行决策交给通用规则层——无规则 +
 * DEFAULT mode → 第 3 层 passthrough→ask（弹窗）；auto-mode → 分类器；deny/ask 规则在
 * 1a/1b 层先于 1c 命中。主 agent 全局 MCP 工具 {@link McpServerTool} 若沿用 {@code Tool}
 * 接口默认 {@code Allow}（Tool.java:287-295），1c 层 {@code CheckLayer1c_ToolCheck} 直接
 * 短路放行 → mcp__ 工具在「无规则 + 非 bypass」下被<b>静默执行</b>（CC 会弹窗 ask），
 * 且 auto-mode 分类器永不覆盖（放权升级）。子 agent 版 AgentMcpTool（MCP-SEC-01）同源缺陷
 * 已闭环（AgentMcpTool.checkPermissions → Passthrough，与本类同分支同 commit 落地，EV-SEC-016 可关闭）。
 *
 * <p>本测试锁 {@code checkPermissions} 必须返 {@link PermissionResult.Passthrough}（而非
 * 默认 Allow），让管线继续到 1d/1e/1f/1g → 2a/2b → 第 3 层转 ask。
 */
@DisplayName("[P0-2] McpServerTool.checkPermissions 返 Passthrough（对齐 CC MCPTool.ts:56-61）")
class McpServerToolCheckPermissionsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static McpServerTool tool() {
        return new McpServerTool("filesystem", "read_file", "mcp__filesystem__read_file", MAPPER.createObjectNode(), null, null, "read a file", null, new McpToolPool(new McpTransportFactory(), new ToolRegistry(), new JsonRpcMcpClient()));
    }

    @Test
    @DisplayName("checkPermissions 返回 Passthrough 而非默认 Allow（防 1c 短路静默放行）")
    void checkPermissions_returnsPassthrough_notAllow() {
        // WHY: mcp__ 工具不得用 Tool 接口默认 Allow 短路（否则静默放权，跳过 ask 弹窗与
        // auto-mode 分类器）。CC MCPTool.ts:56-61 显式 override 为 passthrough。
        McpServerTool t = tool();

        PermissionResult result = t.checkPermissions(null, null);

        assertThat(result)
            .as("mcp__ 工具 checkPermissions 必须返 Passthrough（CC 不表态），非 Tool 默认 Allow")
            .isInstanceOf(PermissionResult.Passthrough.class);
        assertThat(result)
            .as("严禁回退为 Allow（1c 层会短路放行，静默执行 mcp__ 工具）")
            .isNotInstanceOf(PermissionResult.Allow.class);
    }

    @Test
    @DisplayName("checkPermissions 的 message 对齐 CC MCPTool.ts:59 逐字")
    void checkPermissions_message_matchesCCVerbatim() {
        // CC MCPTool.ts:56-61: { behavior:'passthrough', message:'MCPTool requires permission.' }
        McpServerTool t = tool();

        PermissionResult result = t.checkPermissions(null, null);

        assertThat(result).isInstanceOf(PermissionResult.Passthrough.class);
        PermissionResult.Passthrough passthrough = (PermissionResult.Passthrough) result;
        assertThat(passthrough.message())
            .as("message 必须逐字对齐 CC MCPTool.ts:59")
            .isEqualTo("MCPTool requires permission.");
    }

    @Test
    @DisplayName("checkPermissions 不消费 input/ctx（CC 无参 async checkPermissions）")
    void checkPermissions_ignoresInputAndContext() {
        // CC MCPTool.ts:56-61 checkPermissions 是无参 async 方法，不读 input/context。
        // Java 端同样不消费两参 → 传 null 也应安全返回 Passthrough（不抛 NPE）。
        McpServerTool t = tool();

        PermissionResult result = t.checkPermissions(null, null);

        assertThat(result).isInstanceOf(PermissionResult.Passthrough.class);
    }

    @Test
    @DisplayName("[RF-6 ①] checkPermissions 带 addRules 建议（allow→localSettings，对齐 CC client.ts:1818-1830）")
    void checkPermissions_carriesAddRulesSuggestion_localSettings() {
        // WHY: CC client.ts:1818-1830 checkPermissions override 追加 addRules suggestion
        //   （type='addRules', rules=[{toolName: fullyQualifiedName, ruleContent: undefined}],
        //   behavior='allow', destination='localSettings'），供 passthrough→ask spread
        //   （permissions.ts:1299-1310）透传到最终 Ask，给用户 "Allow this MCP tool forever"
        //   （localSettings 持久化）放行建议。缺失则第 3 层拿不到 MCP 专属建议。
        McpServerTool t = tool();

        PermissionResult result = t.checkPermissions(null, null);

        assertThat(result).isInstanceOf(PermissionResult.Passthrough.class);
        PermissionResult.Passthrough passthrough = (PermissionResult.Passthrough) result;
        assertThat(passthrough.suggestions()).hasSize(1);
        PermissionUpdate update = passthrough.suggestions().get(0);
        assertThat(update).isInstanceOf(PermissionUpdate.AddRules.class);
        PermissionUpdate.AddRules addRules = (PermissionUpdate.AddRules) update;
        assertThat(addRules.destination())
            .as("CC destination='localSettings' → Destination.LOCAL_SETTINGS")
            .isEqualTo(PermissionUpdate.Destination.LOCAL_SETTINGS);
        assertThat(addRules.behavior())
            .as("CC behavior='allow' → PermissionBehavior.ALLOW")
            .isEqualTo(PermissionBehavior.ALLOW);
        assertThat(addRules.rules()).hasSize(1);
        assertThat(addRules.rules().get(0).ruleValue().toolName())
            .as("CC toolName=fullyQualifiedName（mcp__server__tool）")
            .isEqualTo("mcp__filesystem__read_file");
        assertThat(addRules.rules().get(0).ruleValue().ruleContent())
            .as("CC ruleContent=undefined → wholeTool（无内容限定）")
            .isNull();
    }
}
