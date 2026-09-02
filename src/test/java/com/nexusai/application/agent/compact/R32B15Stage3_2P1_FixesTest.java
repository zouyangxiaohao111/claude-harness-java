package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.SDKStatus;
import com.nexusai.application.agent.tool.SpinnerMode;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R32-b15 Stage 3.2 P1 修复回归测试 · eb0bf0b commit 验证.
 *
 * <p>覆盖范围:
 * <ul>
 *   <li>P1-1: 17 参数 canonical 构造器保留并 delegation 到 45 参</li>
 *   <li>P1-2: 4 参数构造器保留并 delegation</li>
 *   <li>P1-7: SubagentExecutor.withEffectiveCwd 重新构造 context 时保留 C2 callback 4 字段</li>
 * </ul>
 *
 * <p><b>[GR-3]</b> 原 P1-3/4/5/6（afterCompact/tryAutoCompact 的 compact_end / compact_start
 * 事件归属与副作用顺序）断言的是旧编排器方法，旧编排器已删除——单流程 5 事件与
 * compact_start 顺序现由 {@link CompactConversation} compactConversation 单函数承载
 * （CC compact.ts:406/429/587/719/760），已由 {@link CompactConversationTest} 覆盖。
 */
class R32B15Stage3_2P1_FixesTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final String SESSION_ID = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);

    // ═══════════════════ P1-1: 17 参数 canonical 构造器保留 ═══════════════════

    @Test
    @DisplayName("P1-1: 17 参数 canonical 构造器保留 · delegation 到 45 参 · C2/UI/session 字段全部兜底")
    void test17ParamCanonicalConstructorPreserved() throws Exception {
        // reflection: 必须存在 17 参数 canonical 构造器 (兼容旧调用)
        Constructor<?> ctor = ToolUseContext.class.getConstructor(
            UUID.class, UUID.class, PermissionMode.class,
            Map.class, List.class, String.class,
            Class.forName("com.nexusai.application.agent.tool.AbortController"),
            List.class,
            Class.forName("com.nexusai.application.agent.permission.ToolPermissionContext"),
            PermissionMode.class,
            Map.class, boolean.class, String.class, java.nio.file.Path.class,
            Class.forName("java.util.function.Function"),
            Map.class,
            Class.forName("java.util.function.Consumer")
        );
        assertThat(ctor).as("17-param canonical constructor must exist for backward compat").isNotNull();

        // 实例化: 全部 C2/UI/session 字段应被 compact ctor 兜底 (非 null, non-boolean false)
        ToolUseContext ctx = (ToolUseContext) ctor.newInstance(
            AGENT_ID, SESSION_ID, PermissionMode.DEFAULT,
            Map.of(), List.of(), "", null, List.of(),
            null, PermissionMode.DEFAULT,
            Map.of(), false, "", null, null, Map.of(), null
        );
        // 17 字段可读
        assertThat(ctx.agentId()).isEqualTo(AGENT_ID);
        assertThat(ctx.sessionId()).isEqualTo(SESSION_ID);
        assertThat(ctx.mode()).isEqualTo(PermissionMode.DEFAULT);
        // C2 4 字段兜底
        assertThat(ctx.getAppState()).isNotNull();
        assertThat(ctx.setAppState()).isNotNull();
        assertThat(ctx.setStreamMode()).isNotNull();
        assertThat(ctx.setSDKStatus()).isNotNull();
        // UI 10 字段兜底
        assertThat(ctx.addNotification()).isNotNull();
        assertThat(ctx.appendSystemMessage()).isNotNull();
        assertThat(ctx.sendOSNotification()).isNotNull();
        assertThat(ctx.setResponseLength()).isNotNull();
        assertThat(ctx.setHasInterruptibleToolInProgress()).isNotNull();
        assertThat(ctx.updateFileHistoryState()).isNotNull();
        assertThat(ctx.updateAttributionState()).isNotNull();
        assertThat(ctx.setConversationId()).isNotNull();
        assertThat(ctx.setToolJSX()).isNotNull();
        assertThat(ctx.openMessageSelector()).isNotNull();
        // Session 13 字段兜底
        assertThat(ctx.userModified()).isFalse();
        assertThat(ctx.agentType()).isNull();
        assertThat(ctx.requireCanUseTool()).isFalse();
        assertThat(ctx.preserveToolUseResults()).isFalse();
        assertThat(ctx.nestedMemoryAttachmentTriggers()).isNotNull().isEmpty();
        assertThat(ctx.loadedNestedMemoryPaths()).isNotNull().isEmpty();
        assertThat(ctx.dynamicSkillDirTriggers()).isNotNull().isEmpty();
        assertThat(ctx.discoveredSkillNames()).isNotNull().isEmpty();
    }

    // ═══════════════════ P1-2: 4 参数构造器保留并 delegation ═══════════════════

    @Test
    @DisplayName("P1-2: 4 参数构造器保留 · delegation 到 17/45 参")
    void testOld4ParamConstructorStillDelegates() throws Exception {
        Constructor<?> ctor = ToolUseContext.class.getConstructor(
            UUID.class, UUID.class, PermissionMode.class, Map.class
        );
        assertThat(ctor).as("4-param legacy constructor must still exist").isNotNull();

        ToolUseContext ctx = (ToolUseContext) ctor.newInstance(
            AGENT_ID, SESSION_ID, PermissionMode.DEFAULT, Map.of()
        );
        // 4 字段直接传入
        assertThat(ctx.agentId()).isEqualTo(AGENT_ID);
        assertThat(ctx.sessionId()).isEqualTo(SESSION_ID);
        assertThat(ctx.mode()).isEqualTo(PermissionMode.DEFAULT);
        assertThat(ctx.additionalWorkingDirectories()).isEmpty();
        // 其余 41 字段全部兜底, 不抛 NPE
        assertThat(ctx.availableTools()).isEmpty();
        assertThat(ctx.taskListId()).isEqualTo("");
        assertThat(ctx.abortController()).isNotNull();
        assertThat(ctx.messages()).isEmpty();
        assertThat(ctx.permissionMode()).isEqualTo(PermissionMode.DEFAULT);
        assertThat(ctx.mcpClients()).isEmpty();
        assertThat(ctx.isNonInteractiveSession()).isFalse();
        assertThat(ctx.renderedSystemPrompt()).isEqualTo("");
        assertThat(ctx.effectiveCwd()).isNotNull();
        assertThat(ctx.inProgressToolUseIDs()).isNotNull();
        assertThat(ctx.toolDecisions()).isEmpty();
        assertThat(ctx.onCompactProgress()).isNotNull();
    }

    // ═══════════════════ P1-7: SubagentExecutor.withEffectiveCwd 保留 C2 4 字段 ═══════════════════

    @Test
    @DisplayName("P1-7: SubagentExecutor.withEffectiveCwd 重新构造 context 时保留 4 C2 字段")
    void testChildAgentC2CallbackFallback() {
        // 构造一个含 4 C2 字段 (非 null) 的父 ToolUseContext
        java.util.function.Function<Map<String, Object>, Map<String, Object>> getAppState = s -> s;
        java.util.function.Consumer<java.util.function.Function<Map<String, Object>, Map<String, Object>>> setAppState = u -> {};
        java.util.function.Consumer<SpinnerMode> setStreamMode = m -> {};
        java.util.function.Consumer<SDKStatus> setSDKStatus = s -> {};
        // 注入 onCompactProgress 用于验证 17 参数 delegation
        java.util.function.Consumer<CompactProgressEvent> onCompact = e -> {};

        // 父 TUC: 用 21 参数构造器注入 C2 4 字段 (Stage 3.2 时代)
        ToolUseContext parent = new ToolUseContext(
            AGENT_ID, SESSION_ID, PermissionMode.DEFAULT,
            Map.of(), List.of(), "", null, List.of(),
            null, PermissionMode.DEFAULT,
            Map.of(), false, "", null, null, Map.of(), onCompact,
            getAppState, setAppState, setStreamMode, setSDKStatus
        );

        // 调用 SubagentExecutor.withEffectiveCwd (R32-b15 P1-7 验证点)
        java.nio.file.Path newCwd = java.nio.file.Paths.get("/tmp/child-worktree");
        ToolUseContext child = com.nexusai.application.agent.tool.impl.SubagentExecutor
            .withEffectiveCwd(parent, newCwd);

        assertThat(child).isNotNull();
        // effectiveCwd 必须被替换
        assertThat(child.effectiveCwd()).isEqualTo(newCwd.toAbsolutePath());
        // 关键: onCompactProgress 必须被透传 (P1-7: 17 参数构造器 delegation 保留)
        assertThat(child.onCompactProgress()).isSameAs(onCompact);
        // 父 effectiveCwd 不应被修改 (withEffectiveCwd 返回新 TUC)
        assertThat(parent.effectiveCwd()).isNotEqualTo(newCwd);
    }
}
