package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.nexusai.application.agent.messaging.PeerExecutionOrigin;
import com.nexusai.application.agent.messaging.PeerUiApprovalAuthority;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C 批 · peer 回合不得进入/退出 plan 模式（防 prePlanMode=bypass 恢复提权）。
 *
 * <p>工具级纵深防御：即使未来新增调用路径绕过 PeerActionGuard 三点，工具 execute 自身
 * 在 peer 来源下也必须拒绝，且不得发生任何 appState / 会话列写入。
 */
class PeerPlanModeEscalationTest {

    private static final PeerExecutionOrigin PEER = new PeerExecutionOrigin(
        "sess-cccccccc", "Peer C", PeerExecutionOrigin.SOURCE_KIND_PEER,
        "msg-0003", 11L, "bypassPermissions", "peer-c [abcdef]", List.of());

    @BeforeEach
    @AfterEach
    void clearAuthority() {
        PeerUiApprovalAuthority.clearForTest();
    }

    @Test
    void peerCannotEnterPlanMode() {
        EnterPlanModeTool tool = new EnterPlanModeTool();
        ToolUseBlock call = new ToolUseBlock("call-enter", tool.name(), JsonNodeFactory.instance.objectNode());

        AgentToolResult<?> result = tool.execute(call, peerCtx());

        ToolResult<?> tr = assertInstanceOf(ToolResult.class, result);
        assertTrue(String.valueOf(tr.data()).contains("unavailable for cross-session"),
            "peer 进入 plan 必须返回可读拒绝");
    }

    @Test
    void peerCannotExitPlanModeAndRestorePrePlanMode() {
        ExitPlanModeTool tool = new ExitPlanModeTool();
        ToolUseBlock call = new ToolUseBlock("call-exit", tool.name(), JsonNodeFactory.instance.objectNode());

        AgentToolResult<?> result = tool.execute(call, peerCtx());

        ToolResult<?> tr = assertInstanceOf(ToolResult.class, result);
        assertTrue(String.valueOf(tr.data()).contains("unavailable for cross-session"),
            "peer 退出 plan 必须返回可读拒绝");
    }

    @Test
    void planExitEndpointKeepsNormalUserBehaviorAndOnlyRejectsPeerAttributable() {
        com.nexusai.apis.session.SessionController controller = new com.nexusai.apis.session.SessionController();
        // ① 正常用户（origin=null，无法归因为 peer，未安装可信 UI）：既有行为不变 ——
        //    不再因为「没接 UI 认证」就把提权模式一律 403；继续走到「无 sessionMapper」的显式错误。
        org.junit.jupiter.api.Assertions.assertThrows(
            com.nexusai.infra.exception.ValidationException.class,
            () -> controller.exitPlan("sess-cccccccc",
                new com.nexusai.model.session.dto.PlanExitRequest("plan", "bypassPermissions", false)),
            "未可归因 peer 的正常用户请求不得被默认门 403");
        // ② 前端计划审批固定走的 acceptEdits 同样不被拦。
        org.junit.jupiter.api.Assertions.assertThrows(
            com.nexusai.infra.exception.ValidationException.class,
            () -> controller.exitPlan("sess-cccccccc",
                new com.nexusai.model.session.dto.PlanExitRequest("plan", "acceptEdits", false)));

        // ③ 可归因为 peer 的请求（服务端归因器判定）→ 提权写入 403。
        PeerUiApprovalAuthority.installAttributor(claim -> true);
        org.junit.jupiter.api.Assertions.assertThrows(
            com.nexusai.infra.exception.ForbiddenException.class,
            () -> controller.exitPlan("sess-cccccccc",
                new com.nexusai.model.session.dto.PlanExitRequest("plan", "bypassPermissions", false)),
            "peer 来源的提权写入必须被拒");

        // ④ 归因门只拦提权模式：peer 归因下 acceptEdits 仍走正常（既有）路径，不被默认门打死。
        org.junit.jupiter.api.Assertions.assertThrows(
            com.nexusai.infra.exception.ValidationException.class,
            () -> controller.exitPlan("sess-cccccccc",
                new com.nexusai.model.session.dto.PlanExitRequest("plan", "acceptEdits", false)));
    }

    @Test
    void stricterVerifierRejectsPrivilegedModeButNotNormalModes() {
        com.nexusai.apis.session.SessionController controller = new com.nexusai.apis.session.SessionController();
        // 模拟后期真实认证未通过：提权模式被拒；default/acceptEdits 保持既有路径。
        PeerUiApprovalAuthority.installVerifier(claim -> false);
        org.junit.jupiter.api.Assertions.assertThrows(
            com.nexusai.infra.exception.ForbiddenException.class,
            () -> controller.exitPlan("sess-cccccccc",
                new com.nexusai.model.session.dto.PlanExitRequest("plan", "bypassPermissions", false)));
        org.junit.jupiter.api.Assertions.assertThrows(
            com.nexusai.infra.exception.ValidationException.class,
            () -> controller.exitPlan("sess-cccccccc",
                new com.nexusai.model.session.dto.PlanExitRequest("plan", "acceptEdits", false)));
    }

    private static ToolUseContext peerCtx() {
        ToolUseContext ctx = mock(ToolUseContext.class);
        when(ctx.peerExecutionOrigin()).thenReturn(PEER);
        when(ctx.agentType()).thenReturn(null);
        when(ctx.sessionId()).thenReturn("sess-cccccccc");
        return ctx;
    }
}
