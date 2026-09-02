package com.nexusai.application.agent.team;

import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * LeaderPermissionBridge 定向测试 · 对齐 CC utils/swarm/leaderPermissionBridge.ts。
 *
 * <p>WHY (规则九)：bridge 是「权限委派双路径」leader ToolUseConfirm 队列分支的注册中枢 ——
 * REPL 注册 {@code setToolUseConfirmQueue} / {@code setToolPermissionContext}，in-process runner
 * 经 {@code getLeaderToolUseConfirmQueue()} 判空决定走 leader 队列还是 mailbox fallback
 * （CC inProcessRunner.ts:195-334）。若 get 返回 null 或 unregister 不生效，双路径选择错误。
 */
class LeaderPermissionBridgeTest {

    @AfterEach
    void tearDown() {
        LeaderPermissionBridge.unregisterLeaderToolUseConfirmQueue();
        LeaderPermissionBridge.unregisterLeaderSetToolPermissionContext();
    }

    @Test
    void toolUseConfirmQueue_registerGetUnregister() {
        // WHY: CC leaderPermissionBridge.ts:28-40 register/get/unregister 三态。
        assertNull(LeaderPermissionBridge.getLeaderToolUseConfirmQueue(), "初始应无注册 setter");

        LeaderPermissionBridge.SetToolUseConfirmQueueFn setter = updater -> {
            List<LeaderPermissionBridge.ToolUseConfirmEntry> next = updater.apply(List.of());
            assertNotNull(next);
        };
        LeaderPermissionBridge.registerLeaderToolUseConfirmQueue(setter);
        assertSame(setter, LeaderPermissionBridge.getLeaderToolUseConfirmQueue(), "get 应返回注册的同一 setter");

        LeaderPermissionBridge.unregisterLeaderToolUseConfirmQueue();
        assertNull(LeaderPermissionBridge.getLeaderToolUseConfirmQueue(), "unregister 后应返回 null");
    }

    @Test
    void setToolPermissionContext_registerGetUnregister() {
        // WHY: CC leaderPermissionBridge.ts:42-54 权限上下文 setter 三态（context 收紧为
        // ToolPermissionContext，apply("ctx") 占位已替换为真实 ToolPermissionContext）。
        assertNull(LeaderPermissionBridge.getLeaderSetToolPermissionContext());

        ToolPermissionContext expectedCtx = ToolPermissionContext.strict(PermissionMode.DEFAULT);
        final Object[] captured = new Object[2];
        LeaderPermissionBridge.SetToolPermissionContextFn setter = (ToolPermissionContext context, boolean preserveMode) -> {
            captured[0] = context;
            captured[1] = preserveMode;
        };
        LeaderPermissionBridge.registerLeaderSetToolPermissionContext(setter);
        assertSame(setter, LeaderPermissionBridge.getLeaderSetToolPermissionContext());

        LeaderPermissionBridge.getLeaderSetToolPermissionContext().apply(expectedCtx, true);
        assertSame(expectedCtx, captured[0]);
        assertEquals(true, captured[1]);

        LeaderPermissionBridge.unregisterLeaderSetToolPermissionContext();
        assertNull(LeaderPermissionBridge.getLeaderSetToolPermissionContext());
    }
}
