package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AppState;
import com.nexusai.application.agent.tool.Notification;
import com.nexusai.application.agent.tool.SDKStatus;
import com.nexusai.application.agent.tool.SpinnerMode;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * R32-b15 Stage 3.2 + 3.3 + 3.4 重建版 · AppState / SDKStatus / SpinnerMode 集成测试.
 *
 * <p>覆盖范围:
 * <ul>
 *   <li>AppState.empty() / with(key, value) / with(Map) / 防御性 copy</li>
 *   <li>SpinnerMode 4 值枚举</li>
 *   <li>SDKStatus 2 值枚举 (COMPACTING / NULL)</li>
 *   <li>ToolUseContext 44 字段顺序 (17 + 4 C2 + 10 UI + 13 session)</li>
 *   <li>C2 4 字段 compact ctor 兜底</li>
 *   <li>UI 10 字段 compact ctor 兜底</li>
 *   <li>Session 13 字段 compact ctor 兜底 (4 个 Set 必须可变)</li>
 * </ul>
 */
class R32B15Stage3_2_AppStateSDKStatusTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final String SESSION_ID = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);

    // ═══════════════════ AppState 测试 ═══════════════════

    @Test
    @DisplayName("AppState.empty() 单例 + 防御性 copy")
    void appStateEmpty() {
        AppState empty = AppState.empty();
        assertThat(empty.fields()).isEmpty();
        // Map.copyOf immutable
        assertThatCode(() -> empty.fields().put("k", "v"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("AppState.with(key, value) 函数式更新 (返回新 AppState)")
    void appStateWith() {
        AppState initial = AppState.empty();
        AppState updated = initial.with("streamMode", SpinnerMode.REQUESTING);
        assertThat(updated.fields()).containsEntry("streamMode", SpinnerMode.REQUESTING);
        assertThat(initial.fields()).doesNotContainKey("streamMode"); // 不可变
    }

    @Test
    @DisplayName("AppState.with(Map) 批量更新 · null 值被跳过")
    void appStateWithMap() {
        AppState initial = AppState.empty().with("a", 1);
        java.util.Map<String, Object> input = new java.util.HashMap<>();
        input.put("b", 2);
        input.put("c", null);
        AppState updated = initial.with(input);
        assertThat(updated.get("a")).isEqualTo(1);
        assertThat(updated.get("b")).isEqualTo(2);
        assertThat(updated.fields()).doesNotContainKey("c"); // null 跳过
    }

    @Test
    @DisplayName("AppState.get(key) 返回字段值")
    void appStateGet() {
        AppState s = AppState.empty().with("foo", "bar");
        assertThat(s.get("foo")).isEqualTo("bar");
        assertThat(s.get("missing")).isNull();
    }

    // ═══════════════════ SpinnerMode 测试 ═══════════════════

    @Test
    @DisplayName("SpinnerMode 2 值枚举 (REQUESTING / RESPONDING；IMP-H4 收敛：compacting 属 SDKStatus，idle 无 CC 证据)")
    void spinnerModeValues() {
        assertThat(SpinnerMode.values()).containsExactly(
            SpinnerMode.REQUESTING, SpinnerMode.RESPONDING);
    }

    // ═══════════════════ SDKStatus 测试 ═══════════════════

    @Test
    @DisplayName("SDKStatus 2 值枚举 (COMPACTING / NULL, 严格 1:1 对齐 CC schema)")
    void sdkStatusValues() {
        assertThat(SDKStatus.values()).containsExactly(SDKStatus.COMPACTING, SDKStatus.NULL);
    }

    // ═══════════════════ ToolUseContext 45 字段顺序 ═══════════════════

    @Test
    @DisplayName("ToolUseContext canonical 45 参数构造: 全部字段可读")
    void toolUseContext45FieldCanonical() {
        // 显式注入所有 45 字段验证 record 完整性
        java.util.function.Function<Map<String, Object>, Map<String, Object>> getAppState = s -> s;
        java.util.function.Consumer<java.util.function.Function<Map<String, Object>, Map<String, Object>>> setAppState = u -> {};
        java.util.function.Consumer<SpinnerMode> setStreamMode = m -> {};
        java.util.function.Consumer<SDKStatus> setSDKStatus = s -> {};
        java.util.function.Consumer<Notification> addNotif = n -> {};
        java.util.function.Consumer<com.nexusai.application.agent.tool.SystemMessage> appendSys = s -> {};
        java.util.function.Consumer<com.nexusai.application.agent.tool.OSNotification> sendOS = o -> {};
        java.util.function.Consumer<String> setRespLen = s -> {};
        java.util.function.Consumer<Boolean> setHasInterrupt = b -> {};
        java.util.function.Consumer<com.nexusai.application.agent.tool.FileHistoryState> updateFHS = f -> {};
        java.util.function.Consumer<com.nexusai.application.agent.tool.UiAttribution> updateAS = a -> {};
        java.util.function.Consumer<String> setConvId = c -> {};
        java.util.function.Consumer<Object> setJSX = j -> {};
        java.util.function.Consumer<com.nexusai.application.agent.tool.MessageSelector> openSel = m -> {};
        java.util.function.Consumer<CompactProgressEvent> progress = e -> {};

        ToolUseContext ctx = new ToolUseContext(
            AGENT_ID, SESSION_ID, PermissionMode.DEFAULT,
            Map.of(), List.of(), "", null, List.of(),
            null, PermissionMode.DEFAULT,
            Map.of(), false, "", null, null, Map.of(), progress,
            getAppState, setAppState, setStreamMode, setSDKStatus,
            addNotif, appendSys, sendOS, setRespLen, setHasInterrupt,
            updateFHS, updateAS, setConvId, setJSX, openSel,
            true,
            java.util.concurrent.ConcurrentHashMap.newKeySet(),
            java.util.concurrent.ConcurrentHashMap.newKeySet(),
            java.util.concurrent.ConcurrentHashMap.newKeySet(),
            java.util.concurrent.ConcurrentHashMap.newKeySet(),
            "test-agent",
            true, true,
            Map.of("k", "v"), Map.of("k2", "v2"), Map.of("k3", "v3"),
            "tool-id-1", "critical-reminder-text",
            null);  // [L+ R1/round 5] readFileState · null → compact ctor createFileStateCache() 兜底

        // 验证所有 45 字段可读 (Session J 后实际 43 字段: 撤回 querySource/assistantMessage)
        assertThat(ctx.agentId()).isEqualTo(AGENT_ID);
        assertThat(ctx.sessionId()).isEqualTo(SESSION_ID);
        assertThat(ctx.onCompactProgress()).isSameAs(progress);
        assertThat(ctx.getAppState()).isSameAs(getAppState);
        assertThat(ctx.setAppState()).isSameAs(setAppState);
        assertThat(ctx.setStreamMode()).isSameAs(setStreamMode);
        assertThat(ctx.setSDKStatus()).isSameAs(setSDKStatus);
        assertThat(ctx.addNotification()).isSameAs(addNotif);
        assertThat(ctx.appendSystemMessage()).isSameAs(appendSys);
        assertThat(ctx.sendOSNotification()).isSameAs(sendOS);
        assertThat(ctx.setResponseLength()).isSameAs(setRespLen);
        assertThat(ctx.setHasInterruptibleToolInProgress()).isSameAs(setHasInterrupt);
        assertThat(ctx.updateFileHistoryState()).isSameAs(updateFHS);
        assertThat(ctx.updateAttributionState()).isSameAs(updateAS);
        assertThat(ctx.setConversationId()).isSameAs(setConvId);
        assertThat(ctx.setToolJSX()).isSameAs(setJSX);
        assertThat(ctx.openMessageSelector()).isSameAs(openSel);
        assertThat(ctx.userModified()).isTrue();
        assertThat(ctx.agentType()).isEqualTo("test-agent");
        assertThat(ctx.requireCanUseTool()).isTrue();
        assertThat(ctx.preserveToolUseResults()).isTrue();
        assertThat(ctx.toolUseId()).isEqualTo("tool-id-1");
        assertThat(ctx.criticalSystemReminder_EXPERIMENTAL()).isEqualTo("critical-reminder-text");
    }

    // ═══════════════════ C2 4 字段 compact ctor 兜底 ═══════════════════

    @Test
    @DisplayName("C2 4 字段 null → compact ctor 兜底 noop / identity (不抛 NPE)")
    void c2FourFieldsFallbackToNoop() {
        ToolUseContext ctx = new ToolUseContext(
            AGENT_ID, SESSION_ID, PermissionMode.DEFAULT, Map.of(), List.of(), "",
            null, List.of(), null, PermissionMode.DEFAULT, Map.of(), false, "",
            null, null, Map.of(), null
        );
        // 4 C2 字段全部兜底
        assertThat(ctx.getAppState()).isNotNull();
        assertThat(ctx.setAppState()).isNotNull();
        assertThat(ctx.setStreamMode()).isNotNull();
        assertThat(ctx.setSDKStatus()).isNotNull();
        // getAppState 是 identity (s -> s): Map 透传
        assertThat(ctx.getAppState().apply(Map.of("k", "v"))).containsEntry("k", "v");
        // 3 个 setter 不抛异常 (compact ctor 默认 noop Consumer, 不真正应用 updater)
        AtomicInteger counter = new AtomicInteger();
        assertThatCode(() -> {
            ctx.setAppState().accept(u -> { counter.incrementAndGet(); return u; });
            ctx.setStreamMode().accept(SpinnerMode.REQUESTING);
            ctx.setSDKStatus().accept(SDKStatus.COMPACTING);
        }).doesNotThrowAnyException();
        // note: compact ctor noop setAppState Consumer 不调用 updater; 仅在 LlmAgentLoop 注入时
        // 才会真正应用 updater
    }

    // ═══════════════════ UI 10 字段 compact ctor 兜底 ═══════════════════

    @Test
    @DisplayName("UI 10 字段 null → compact ctor 兜底 noop / value -> null")
    void uiTenFieldsFallbackToNoop() {
        ToolUseContext ctx = new ToolUseContext(
            AGENT_ID, SESSION_ID, PermissionMode.DEFAULT, Map.of(), List.of(), "",
            null, List.of(), null, PermissionMode.DEFAULT, Map.of(), false, "",
            null, null, Map.of(), null
        );
        // 10 UI callback 全部兜底
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
    }

    // ═══════════════════ Session 13 字段 compact ctor 兜底 ═══════════════════

    @Test
    @DisplayName("Session 13 字段: 4 个 Set 默认空可变 (CC 工具可 .add()/.clear()), boolean false, String/Map null")
    void session13FieldsFallback() {
        ToolUseContext ctx = new ToolUseContext(
            AGENT_ID, SESSION_ID, PermissionMode.DEFAULT, Map.of(), List.of(), "",
            null, List.of(), null, PermissionMode.DEFAULT, Map.of(), false, "",
            null, null, Map.of(), null
        );

        // 4 个 Set 必须可变
        assertThat(ctx.nestedMemoryAttachmentTriggers()).isNotNull().isEmpty();
        assertThat(ctx.loadedNestedMemoryPaths()).isNotNull().isEmpty();
        assertThat(ctx.dynamicSkillDirTriggers()).isNotNull().isEmpty();
        assertThat(ctx.discoveredSkillNames()).isNotNull().isEmpty();

        // Set 必须可写 (CC 工具会 .add()/.clear())
        ctx.nestedMemoryAttachmentTriggers().add("test-trigger");
        assertThat(ctx.nestedMemoryAttachmentTriggers()).containsExactly("test-trigger");

        // boolean 默认 false
        assertThat(ctx.userModified()).isFalse();
        assertThat(ctx.requireCanUseTool()).isFalse();
        assertThat(ctx.preserveToolUseResults()).isFalse();

        // String/Map 默认 null
        assertThat(ctx.agentType()).isNull();
        assertThat(ctx.localDenialTracking()).isNull();
        assertThat(ctx.contentReplacementState()).isNull();
        assertThat(ctx.queryTracking()).isNull();
        assertThat(ctx.toolUseId()).isNull();
        assertThat(ctx.criticalSystemReminder_EXPERIMENTAL()).isNull();
    }

    // ═══════════════════ Stage 3.4 13 session 字段 Stage 3.4 P0-1 校正 ═══════════════════

    @Test
    @DisplayName("Set 跨 record-component 持有: 输入 Set 内容透传 (compact ctor 复制为可变 Set)")
    void setsShareIdentity() {
        java.util.Set<String> nested = java.util.concurrent.ConcurrentHashMap.newKeySet();
        nested.add("n1");
        java.util.Set<String> loaded = java.util.concurrent.ConcurrentHashMap.newKeySet();
        loaded.add("l1");
        java.util.Set<String> dynamic = java.util.concurrent.ConcurrentHashMap.newKeySet();
        java.util.Set<String> discovered = java.util.concurrent.ConcurrentHashMap.newKeySet();

        ToolUseContext ctx = new ToolUseContext(
            AGENT_ID, SESSION_ID, PermissionMode.DEFAULT, Map.of(), List.of(), "",
            null, List.of(), null, PermissionMode.DEFAULT, Map.of(), false, "",
            null, null, Map.of(), null,
            null, null, null, null,
            null, null, null, null, null, null, null, null, null, null,
            false, nested, loaded, dynamic, discovered,
            null, false, false, null, null, null, null, null,
            null);  // [L+ R1/round 5] readFileState · null → compact ctor createFileStateCache() 兜底

        // 内容透传 (compact ctor 复制到新可变 Set)
        assertThat(ctx.nestedMemoryAttachmentTriggers()).containsExactly("n1");
        assertThat(ctx.loadedNestedMemoryPaths()).containsExactly("l1");
        assertThat(ctx.dynamicSkillDirTriggers()).isEmpty();
        assertThat(ctx.discoveredSkillNames()).isEmpty();

        // 关键: TUC 持有的 Set 必须可变 (CC 工具会 .add()/.clear())
        assertThatCode(() -> ctx.discoveredSkillNames().add("d1")).doesNotThrowAnyException();
        assertThat(ctx.discoveredSkillNames()).containsExactly("d1");
    }

    // ═══════════════════ 4 个 Set 必须可变 — 防止 Set.copyOf 不可变集合 ═══════════════════

    @Test
    @DisplayName("Set 不可变性防护: 即使从不可变 Set 输入, compact ctor 仍输出可变 Set")
    void setsRemainMutableEvenFromImmutableInput() {
        // Set.copyOf 返回不可变 Set
        java.util.Set<String> immutable = java.util.Set.copyOf(java.util.List.of("a", "b"));
        ToolUseContext ctx = new ToolUseContext(
            AGENT_ID, SESSION_ID, PermissionMode.DEFAULT, Map.of(), List.of(), "",
            null, List.of(), null, PermissionMode.DEFAULT, Map.of(), false, "",
            null, null, Map.of(), null,
            null, null, null, null,
            null, null, null, null, null, null, null, null, null, null,
            false, immutable, immutable, immutable, immutable,
            null, false, false, null, null, null, null, null,
            null);  // [L+ R1/round 5] readFileState · null → compact ctor createFileStateCache() 兜底
        // 关键不变量: 必须是可变 Set (CC 工具会 .add()/.clear())
        assertThatCode(() -> ctx.nestedMemoryAttachmentTriggers().add("c")).doesNotThrowAnyException();
        assertThatCode(() -> ctx.loadedNestedMemoryPaths().clear()).doesNotThrowAnyException();
    }

    // ═══════════════════ Stage 3.4 contentReplacementState 不双写 AgentState ═══════════════════

    @Test
    @DisplayName("contentReplacementState 默认 null: AgentState 保持 canonical source, TUC 为 local bridge/view")
    void contentReplacementStateDefaultsToNull() {
        ToolUseContext ctx = new ToolUseContext(
            AGENT_ID, SESSION_ID, PermissionMode.DEFAULT, Map.of(), List.of(), "",
            null, List.of(), null, PermissionMode.DEFAULT, Map.of(), false, "",
            null, null, Map.of(), null
        );
        assertThat(ctx.contentReplacementState()).isNull();
        // localDenialTracking / queryTracking 同款
        assertThat(ctx.localDenialTracking()).isNull();
        assertThat(ctx.queryTracking()).isNull();
    }
}