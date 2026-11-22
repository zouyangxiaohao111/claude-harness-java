package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.messaging.PeerExecutionOrigin;
import com.nexusai.application.agent.messaging.PeerUiApprovalAuthority;
import com.nexusai.application.agent.permission.hook.HookPermissionResolver;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C 批 · peer 副作用工具交给既有权限管线（非 Spring）· 2026-11-19 语义重排后。
 *
 * <p>验证意图（对齐 CC）：
 * <ul>
 *   <li>hook allow + peer Bash：peer 层不硬拦，保持 hook allow 结果（既有管线语义）；</li>
 *   <li>bypassPermissions + peer Bash：照常执行（不因来源是 peer 就拒绝）；</li>
 *   <li>updatedInput 改写受保护路径 → 仍硬 Deny；</li>
 *   <li>正常用户（origin=null）hook allow 语义不变。</li>
 * </ul>
 */
class PeerGuardBypassTest {

    private final PeerExecutionOrigin peerOrigin = new PeerExecutionOrigin(
        "sess-bbbbbbbb", "Peer B", PeerExecutionOrigin.SOURCE_KIND_PEER,
        "msg-0002", 9L, "default", "peer-b [abcdef]", List.of());

    @AfterEach
    void tearDown() {
        PeerUiApprovalAuthority.clearForTest();
    }

    @Test
    void hookAllowWithPeerBashKeepsExistingPipelineResult() {
        ToolUseContext ctx = peerContext(peerOrigin);
        JsonNode input = objectNode().put("command", "curl http://127.0.0.1:3458/x");
        PermissionResult.Allow hookAllow = hookAllow(input);
        AtomicBoolean promptCalled = new AtomicBoolean(false);
        HookPermissionResolver.CanUseTool canUse = (t, in, c, id, force) -> {
            promptCalled.set(true);
            return ToolPermissionGate.DecisionResult.allow();
        };

        HookPermissionResolver.ResolvedPermission resolved = new HookPermissionResolver()
            .resolve(hookAllow, null, tool("Bash", false), input, ctx, "tool-1", canUse);

        assertInstanceOf(PermissionResult.Allow.class, resolved.decision(),
            "peer Bash 不再被 peer 层一刀切拒绝；hook allow 是既有管线的一部分");
        assertFalse(promptCalled.get(), "已由 hook allow 命中，不应再造第二次确认");
    }

    @Test
    void pipelineBypassModeExecutesPeerBashNormally() {
        PermissionPipeline pipeline = new PermissionPipeline();
        ToolUseContext ctx = peerContext(peerOrigin);
        JsonNode input = objectNode().put("command", "rm -rf /tmp/x");
        ToolPermissionContext bypass = ToolPermissionContext.strict(PermissionMode.BYPASS_PERMISSIONS);
        ToolUseBlock call = new ToolUseBlock("call-bypass", "Bash", input);

        PermissionResult result = pipeline.check(tool("Bash", false), call, input, ctx, bypass);
        assertInstanceOf(PermissionResult.Allow.class, result,
            "bypass 模式下 peer 消息照常执行，peer 层不得否决既有权限管线");
    }

    @Test
    void pipelineDefaultModeIsNotRejectedByPeerLayer() {
        PermissionPipeline pipeline = new PermissionPipeline();
        ToolUseContext ctx = peerContext(peerOrigin);
        JsonNode input = objectNode().put("command", "echo hi");
        ToolPermissionContext permCtx = ToolPermissionContext.strict(PermissionMode.DEFAULT);
        ToolUseBlock call = new ToolUseBlock("call-default", "Bash", input);

        PermissionResult result = pipeline.check(tool("Bash", false), call, input, ctx, permCtx);
        assertFalse(result instanceof PermissionResult.Deny,
            "default 模式下 peer Bash 交给既有管线（弹审批/规则），不得被 peer 层直接 Deny；实际=" + result);
    }

    @Test
    void updatedInputCannotChangeOrdinaryWriteIntoProtectedTarget() {
        ToolUseContext ctx = peerContext(peerOrigin);
        JsonNode rewritten = objectNode().put("file_path", "/tmp/project/CLAUDE.md");
        PermissionResult.Allow hookAllow = hookAllow(rewritten);
        AtomicBoolean promptCalled = new AtomicBoolean(false);

        HookPermissionResolver.ResolvedPermission resolved = new HookPermissionResolver()
            .resolve(hookAllow, null, tool("Write", false), rewritten, ctx, "tool-3",
                (t, in, c, id, force) -> {
                    promptCalled.set(true);
                    return ToolPermissionGate.DecisionResult.allow();
                });

        assertInstanceOf(PermissionResult.Deny.class, resolved.decision());
        assertFalse(promptCalled.get());
    }

    @Test
    void normalUserHookAllowSemanticsUnchanged() {
        ToolUseContext ctx = peerContext(null);
        JsonNode input = objectNode().put("command", "echo hi");
        PermissionResult.Allow hookAllow = hookAllow(input);

        HookPermissionResolver.ResolvedPermission resolved = new HookPermissionResolver()
            .resolve(hookAllow, null, tool("Bash", false), input, ctx, "tool-4",
                (t, in, c, id, force) -> ToolPermissionGate.DecisionResult.allow());

        assertInstanceOf(PermissionResult.Allow.class, resolved.decision(),
            "origin=null 的正常用户必须保持 hook allow 语义");
    }

    // ── fixtures ──

    private static ToolUseContext peerContext(PeerExecutionOrigin origin) {
        ToolUseContext ctx = mock(ToolUseContext.class);
        when(ctx.peerExecutionOrigin()).thenReturn(origin);
        when(ctx.requireCanUseTool()).thenReturn(false);
        when(ctx.permissionContext()).thenReturn(null);
        return ctx;
    }

    private static PermissionResult.Allow hookAllow(JsonNode input) {
        return new PermissionResult.Allow(input, new PermissionDecisionReason.Other("hook_allow"),
            null, false, null, List.of());
    }

    private static ObjectNode objectNode() {
        return JsonNodeFactory.instance.objectNode();
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
