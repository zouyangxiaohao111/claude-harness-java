package com.nexusai.application.agent.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C 批 · {@link PeerActionGuard} 单元测试（非 Spring）· 2026-11-19 语义重排后。
 *
 * <p>验证意图（对齐 CC）：
 * <ul>
 *   <li>只有提权面硬 Deny：plan 模式工具 / 配置写 / 受保护路径写 / 结构化 denied 转交标记；</li>
 *   <li>peer 的 Bash / Write / Edit / MCP 等正常副作用<b>不</b>在 peer 层拦截，
 *       返回 ALLOW 交给目标会话既有权限管线（default 弹审批、bypass 执行）；</li>
 *   <li>正常用户（origin=null）语义零变化；默认 local-user 可审批且带 userId。</li>
 * </ul>
 */
class PeerActionGuardTest {

    private PeerExecutionOrigin peerOrigin;
    private String previousConfigHome;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        peerOrigin = new PeerExecutionOrigin(
            "sess-aaaaaaaa", "Peer A", PeerExecutionOrigin.SOURCE_KIND_PEER,
            "msg-0001", 7L, "default", "peer-a [abcdef]", List.of());
        previousConfigHome = NexusaiPaths.getAppConfigHomeDir();
        NexusaiPaths.setConfigHomeDirOverride(tempDir.resolve("config-home").toString());
        PeerUiApprovalAuthority.clearForTest();
    }

    @AfterEach
    void tearDown() {
        PeerUiApprovalAuthority.clearForTest();
        NexusaiPaths.setConfigHomeDirOverride(previousConfigHome);
    }

    @Test
    void normalUserOriginIsAlwaysAllowed() {
        assertEquals(PeerActionGuard.Action.ALLOW,
            PeerActionGuard.check(null, tool("Bash", false), objectNode(), null).action());
        assertEquals(PeerActionGuard.Action.ALLOW,
            PeerActionGuard.check(null, tool("EnterPlanMode", true), objectNode(), null).action());
        assertEquals(PeerActionGuard.Action.ALLOW,
            PeerActionGuard.check(null, tool("Write", false), writeInput("/x/CLAUDE.md"), null).action());
    }

    @Test
    void peerCannotUsePlanModeToolsEvenWithHookStyleAllow() {
        assertEquals(PeerActionGuard.Action.DENY,
            PeerActionGuard.check(peerOrigin, tool("EnterPlanMode", true), objectNode(), null).action());
        assertEquals(PeerActionGuard.Action.DENY,
            PeerActionGuard.check(peerOrigin, tool("ExitPlanMode", false), objectNode(), null).action());
        // 最终执行点也不得放行（防 updatedInput 绕过）
        assertEquals(PeerActionGuard.Action.DENY,
            PeerActionGuard.checkFinal(peerOrigin, tool("ExitPlanMode", false), objectNode(), null).action());
    }

    @Test
    void peerCannotWriteProtectedInstructionOrConfigTargets() {
        // 任意目录 CLAUDE.md
        assertEquals(PeerActionGuard.Action.DENY,
            PeerActionGuard.check(peerOrigin, tool("Write", false),
                writeInput("/tmp/project/CLAUDE.md"), null).action());
        // 配置自有根下的 settings
        String configSettings = Path.of(NexusaiPaths.getAppConfigHomeDir(), "settings.json").toString();
        assertEquals(PeerActionGuard.Action.DENY,
            PeerActionGuard.check(peerOrigin, tool("Edit", false),
                writeInput(configSettings), null).action());
        // 项目 .nexusai 配置目录
        assertEquals(PeerActionGuard.Action.DENY,
            PeerActionGuard.check(peerOrigin, tool("Write", false),
                writeInput("/tmp/project/.nexusai/settings.local.json"), null).action());
        // 路径穿越（normalize 后仍是 CLAUDE.md）
        assertEquals(PeerActionGuard.Action.DENY,
            PeerActionGuard.check(peerOrigin, tool("Write", false),
                writeInput("/tmp/project/sub/../CLAUDE.md"), null).action());
        // AGENTS.md 同样保护
        assertEquals(PeerActionGuard.Action.DENY,
            PeerActionGuard.check(peerOrigin, tool("Write", false),
                writeInput("/tmp/project/AGENTS.md"), null).action());
        // 最终执行点（updatedInput 改写路径后）同样拒绝
        assertEquals(PeerActionGuard.Action.DENY,
            PeerActionGuard.checkFinal(peerOrigin, tool("Write", false),
                writeInput("/tmp/project/CLAUDE.md"), null).action());
    }

    @Test
    void configWriteDeniedButConfigGetAllowed() {
        ObjectNode get = JsonNodeFactory.instance.objectNode().put("setting", "permissions.defaultMode");
        assertEquals(PeerActionGuard.Action.ALLOW,
            PeerActionGuard.check(peerOrigin, tool("Config", true), get, null).action());
        ObjectNode set = JsonNodeFactory.instance.objectNode()
            .put("setting", "permissions.defaultMode").put("value", "bypassPermissions");
        assertEquals(PeerActionGuard.Action.DENY,
            PeerActionGuard.check(peerOrigin, tool("Config", false), set, null).action());
    }

    @Test
    void peerOrdinarySideEffectsAreDelegatedToExistingPipeline() {
        // 语义重排核心：peer 的 Bash / MCP / 普通 Write 不在 peer 层被一刀切拒绝，
        // 返回 ALLOW = 交给目标会话既有权限管线（default 弹审批、bypass 执行）。
        assertEquals(PeerActionGuard.Action.ALLOW,
            PeerActionGuard.check(peerOrigin, tool("Bash", false),
                objectNode().put("command", "echo hi"), null).action());
        assertEquals(PeerActionGuard.Action.ALLOW,
            PeerActionGuard.check(peerOrigin, tool("mcp__files__write", false), objectNode(), null).action());
        assertEquals(PeerActionGuard.Action.ALLOW,
            PeerActionGuard.check(peerOrigin, tool("Write", false),
                writeInput("/tmp/project/normal.txt"), null).action());
        // 最终执行点同样不拦普通副作用（不二次打断已由管线批准的调用）。
        assertEquals(PeerActionGuard.Action.ALLOW,
            PeerActionGuard.checkFinal(peerOrigin, tool("Bash", false), objectNode(), null).action());
        assertEquals(PeerActionGuard.Action.ALLOW,
            PeerActionGuard.checkFinal(peerOrigin, tool("Write", false),
                writeInput("/tmp/project/normal.txt"), null).action());
    }

    @Test
    void readOnlyAndStateToolsRemainUsable() {
        assertEquals(PeerActionGuard.Action.ALLOW,
            PeerActionGuard.check(peerOrigin, tool("Read", true), objectNode(), null).action());
        assertEquals(PeerActionGuard.Action.ALLOW,
            PeerActionGuard.check(peerOrigin, tool("Grep", true), objectNode(), null).action());
        assertEquals(PeerActionGuard.Action.ALLOW,
            PeerActionGuard.check(peerOrigin, tool("TodoWrite", false), objectNode(), null).action());
        assertEquals(PeerActionGuard.Action.ALLOW,
            PeerActionGuard.check(peerOrigin, tool("SendMessage", true), objectNode(), null).action());
    }

    @Test
    void structuredDeniedRelayMarkerIsRejectedForAllTools() {
        PeerExecutionOrigin deniedRelay = new PeerExecutionOrigin(
            "sess-aaaaaaaa", "Peer A", PeerActionGuard.SOURCE_KIND_DENIED_RELAY,
            "msg-denied-1", 7L, "default", "peer-a [abcdef]", List.of());
        for (String name : List.of("Bash", "Write", "Read", "SendMessage")) {
            PeerActionGuard.Decision d = PeerActionGuard.check(
                deniedRelay, tool(name, "Read".equals(name)), objectNode(), null);
            assertEquals(PeerActionGuard.Action.DENY, d.action(), "denied 转交标记必须拒绝: " + name);
            assertTrue(d.message() != null && d.message().contains("denied"),
                "拒绝说明应可读并 surface 给用户: " + d.message());
            assertEquals(PeerActionGuard.Action.DENY,
                PeerActionGuard.checkFinal(deniedRelay, tool(name, false), objectNode(), null).action());
        }
        // 普通 peer 来源不受影响
        assertEquals(PeerActionGuard.Action.ALLOW,
            PeerActionGuard.check(peerOrigin, tool("Bash", false), objectNode(), null).action());
    }

    @Test
    void defaultLocalUserIsTrustedAndCarriesUserId() {
        assertEquals("default", PeerUiApprovalAuthority.getUserId());
        assertTrue(PeerUiApprovalAuthority.isTrustedUi(
            PeerUiApprovalAuthority.Source.HTTP_PEER_INBOX, "sess-aaaaaaaa", "msg-1"));
        PeerUiApprovalAuthority.Admission admission = PeerUiApprovalAuthority.admit(
            PeerUiApprovalAuthority.Source.STOMP_MESSAGE, "sess-aaaaaaaa", "req-1");
        assertTrue(admission.trusted());
        assertEquals("default", admission.userId());

        // 可配置 userId（后期接真实认证只换实现，不改调用方）
        PeerUiApprovalAuthority.installUserIdSource(() -> "alice");
        assertEquals("alice", PeerUiApprovalAuthority.getUserId());
        assertEquals("alice", PeerUiApprovalAuthority.admit(
            PeerUiApprovalAuthority.Source.STOMP_MESSAGE, "sess-aaaaaaaa", "req-1").userId());

        // 更严验证器安装后以其结论为准（不消耗默认信任）
        PeerUiApprovalAuthority.installVerifier(claim -> false);
        assertFalse(PeerUiApprovalAuthority.isTrustedUi(
            PeerUiApprovalAuthority.Source.STOMP_MESSAGE, "sess-aaaaaaaa", "req-1"));

        // 清理恢复默认 local-user
        PeerUiApprovalAuthority.clearForTest();
        assertEquals("default", PeerUiApprovalAuthority.getUserId());
        assertTrue(PeerUiApprovalAuthority.isTrustedUi(
            PeerUiApprovalAuthority.Source.STOMP_MESSAGE, "sess-aaaaaaaa", "req-1"));
    }

    @Test
    void prefixCollisionDoesNotCountAsConfigHome() {
        // 非受保护路径在 peer 层不拦（ALLOW），交给既有管线；
        // 若实现用 startsWith 近似判定，这里会错误地得到 DENY。
        String evil = Path.of(NexusaiPaths.getAppConfigHomeDir() + "-evil", "settings.json").toString();
        assertEquals(PeerActionGuard.Action.ALLOW,
            PeerActionGuard.check(peerOrigin, tool("Write", false), writeInput(evil), null).action());
    }

    // ── fixtures ──

    private static ObjectNode objectNode() {
        return JsonNodeFactory.instance.objectNode();
    }

    private static ObjectNode writeInput(String path) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("file_path", path);
        return node;
    }

    private static Tool tool(String name, boolean readOnly) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return name;
            }

            @Override
            public JsonNode inputSchema() {
                return objectNode();
            }

            @Override
            public boolean isReadOnly(JsonNode input) {
                return readOnly;
            }

            @Override
            public AgentToolResult<?> execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "ok");
            }
        };
    }
}
