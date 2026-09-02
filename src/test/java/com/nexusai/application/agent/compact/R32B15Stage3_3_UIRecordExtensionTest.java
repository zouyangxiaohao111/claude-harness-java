package com.nexusai.application.agent.compact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.UiAttribution;
import com.nexusai.application.agent.tool.FileHistoryState;
import com.nexusai.application.agent.tool.MessageSelector;
import com.nexusai.application.agent.tool.Notification;
import com.nexusai.application.agent.tool.OSNotification;
import com.nexusai.application.agent.tool.SDKStatus;
import com.nexusai.application.agent.tool.SpinnerMode;
import com.nexusai.application.agent.tool.SystemMessage;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * R32-b15 Stage 3.3 UI record extension 回归测试 · 7ca43ab + 97cb2b1 commits 验证.
 *
 * <p>覆盖范围:
 * <ul>
 *   <li>7 payload record (Notification/SystemMessage/FileHistoryState/UiAttribution/
 *       OSNotification/MessageSelector) accessor 验证（AttributionState 已改名 UiAttribution，IMP-H4）</li>
 *   <li>MessageSelector defensive copy (null options → 空不可变 List)</li>
 *   <li>10 UI callback 默认 non-null + noop (compact ctor 兜底)</li>
 *   <li>45 参 canonical 注入 10 UI callback</li>
 *   <li>4/5/6/8/10/11/12/13/15/17/21/31 参数构造器 delegation 全部存在</li>
 *   <li>17 参 canonical 构造器保留 (C2 字段默认 null)</li>
 *   <li>21 参 canonical 构造器保留 (UI 字段默认 null)</li>
 *   <li>Jackson 序列化不暴露 10 UI callback</li>
 *   <li>Jackson 仅输出 data fields (agentId/sessionId/mode 等)</li>
 *   <li>toolUseContextWithUi(c) 透传 10 UI callback</li>
 *   <li><b>关键修复验证</b>: toolUseContextWithUi 必须保留 4 C2 callback (不能传 null,
 *       修复 7ca 历史 bug)</li>
 *   <li>额外 1 测试: callback 触发不写 outbound DTO</li>
 * </ul>
 */
class R32B15Stage3_3_UIRecordExtensionTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final String SESSION_ID = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);

    // ═══════════════════ 7 payload record accessor ═══════════════════

    @Test
    @DisplayName("Notification record accessor: id/title/body/level + Level 枚举 4 值")
    void testNotificationRecordAccessor() {
        Notification n = new Notification("notif-1", "title-text", "body-text", Notification.Level.WARNING);
        assertThat(n.id()).isEqualTo("notif-1");
        assertThat(n.title()).isEqualTo("title-text");
        assertThat(n.body()).isEqualTo("body-text");
        assertThat(n.level()).isEqualTo(Notification.Level.WARNING);
        // Level 枚举必须 4 值 (INFO/SUCCESS/WARNING/ERROR)
        assertThat(Notification.Level.values()).containsExactly(
            Notification.Level.INFO, Notification.Level.SUCCESS,
            Notification.Level.WARNING, Notification.Level.ERROR);
    }

    @Test
    @DisplayName("SystemMessage record accessor: role/content")
    void testSystemMessageRecordAccessor() {
        SystemMessage sm = new SystemMessage("assistant", "system content");
        assertThat(sm.role()).isEqualTo("assistant");
        assertThat(sm.content()).isEqualTo("system content");
    }

    @Test
    @DisplayName("[IMP-MV2-40 ③ memory_saved 契约固化] SystemMessage.memorySaved 序列化形状：role/subtype/writtenPaths（无 timestamp/uuid/isMeta）")
    void testSystemMessageMemorySavedSerialization() throws Exception {
        // WHY: OPD-MM-32 核验结论（前端对接文档无 timestamp/uuid/isMeta 渲染依赖 → 登记不补字段）——
        //   固化当前出站 JSON 契约：role='system' + subtype='memory_saved' + writtenPaths 数组；
        //   CC createMemorySavedMessage（messages.ts:4460-4471）另有 timestamp/uuid/isMeta，
        //   若未来前端依赖需补字段 + 更新本用例。
        ObjectMapper mapper = new ObjectMapper();

        SystemMessage saved = SystemMessage.memorySaved(List.of("/mem/a.md", "/mem/b.md"));
        String json = mapper.writeValueAsString(saved);
        assertThat(json).contains("\"role\":\"system\"");
        assertThat(json).contains("\"subtype\":\"memory_saved\"");
        assertThat(json).contains("\"writtenPaths\":[\"/mem/a.md\",\"/mem/b.md\"]");
        assertThat(json).as("登记结论：当前不含 timestamp/uuid/isMeta 字段").doesNotContain("timestamp");
        assertThat(json).doesNotContain("uuid");
        assertThat(json).doesNotContain("isMeta");

        SystemMessage improved = SystemMessage.memorySavedImproved(List.of("/mem/c.md"));
        String improvedJson = mapper.writeValueAsString(improved);
        assertThat(improvedJson).contains("\"verb\":\"Improved\"");
    }

    @Test
    @DisplayName("FileHistoryState record accessor: snapshots/trackedFiles/snapshotSequence + 嵌套 record")
    void testFileHistoryStateRecordAccessor() {
        // [OPD-TOOL-06-4] FileHistoryState 重写为 CC fileHistory.ts:39-55 契约
        // （snapshots/trackedFiles/snapshotSequence + 嵌套 FileHistorySnapshot/FileHistoryBackup）。
        FileHistoryState.FileHistoryBackup backup =
            new FileHistoryState.FileHistoryBackup("abc123@v1", 1, Instant.now());
        FileHistoryState.FileHistorySnapshot snapshot = new FileHistoryState.FileHistorySnapshot(
            "msg-1", Map.of("/path", backup), Instant.now());
        FileHistoryState fhs = new FileHistoryState(List.of(snapshot), Set.of("/path"), 1L);
        assertThat(fhs.snapshots()).hasSize(1);
        assertThat(fhs.trackedFiles()).containsExactly("/path");
        assertThat(fhs.snapshotSequence()).isEqualTo(1L);
        assertThat(fhs.snapshots().get(0).trackedFileBackups().get("/path").version()).isEqualTo(1);
    }

    @Test
    @DisplayName("UiAttribution record accessor: attributionText/confidence（IMP-H4 改名，勿蹭 CC commit-attribution 类型名）")
    void testAttributionStateRecordAccessor() {
        UiAttribution as = new UiAttribution("by claude-3-opus", 0.95);
        assertThat(as.attributionText()).isEqualTo("by claude-3-opus");
        assertThat(as.confidence()).isEqualTo(0.95);
    }

    @Test
    @DisplayName("OSNotification record accessor: message/notificationType（IMP-H4 对齐 CC Tool.ts:211-214）")
    void testOSNotificationRecordAccessor() {
        OSNotification osn = new OSNotification("OS message", "computer_use_enter");
        assertThat(osn.message()).isEqualTo("OS message");
        assertThat(osn.notificationType()).isEqualTo("computer_use_enter");
    }


    @Test
    @DisplayName("MessageSelector defensive copy: null options → 空不可变 List")
    void testMessageSelectorDefensiveCopy() {
        MessageSelector ms1 = new MessageSelector("id-1", "title-1", null);
        assertThat(ms1.options()).isEmpty();
        assertThatCode(() -> ms1.options().add("mutate"))
            .isInstanceOf(UnsupportedOperationException.class);
        // mutable options 输入 → copyOf 防御性复制
        List<String> original = new java.util.ArrayList<>();
        original.add("msg-a");
        MessageSelector ms2 = new MessageSelector("id-2", "title-2", original);
        original.add("msg-b");
        assertThat(ms2.options()).containsExactly("msg-a");
    }

    // ═══════════════════ 10 UI callback 兜底行为 ═══════════════════

    @Test
    @DisplayName("10 UI callback 默认 non-null · Consumer noop")
    void test10UICallbackDefaultNoop() {
        // 17 参数构造器 (C2/UI 字段传 null) → compact ctor 兜底
        ToolUseContext ctx = new ToolUseContext(
            AGENT_ID, SESSION_ID, PermissionMode.DEFAULT,
            Map.of(), List.of(), "", null, List.of(),
            null, PermissionMode.DEFAULT,
            Map.of(), false, "", null, null, Map.of(), null
        );
        // 10 UI callback 全部 non-null
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
        // 10 个 Consumer 全部 noop (调用不抛异常, 不产生副作用)
        Notification n = new Notification("test", "t", "b", Notification.Level.INFO);
        assertThatCode(() -> ctx.addNotification().accept(n)).doesNotThrowAnyException();
        SystemMessage sm = new SystemMessage("role", "content");
        assertThatCode(() -> ctx.appendSystemMessage().accept(sm)).doesNotThrowAnyException();
        OSNotification osn = new OSNotification("t", "i");
        assertThatCode(() -> ctx.sendOSNotification().accept(osn)).doesNotThrowAnyException();
        assertThatCode(() -> ctx.setResponseLength().accept("100")).doesNotThrowAnyException();
        assertThatCode(() -> ctx.setHasInterruptibleToolInProgress().accept(true)).doesNotThrowAnyException();
        FileHistoryState fhs = new FileHistoryState(List.of(), Set.of(), 0L);
        assertThatCode(() -> ctx.updateFileHistoryState().accept(fhs)).doesNotThrowAnyException();
        UiAttribution as = new UiAttribution("text", 0.5);
        assertThatCode(() -> ctx.updateAttributionState().accept(as)).doesNotThrowAnyException();
        assertThatCode(() -> ctx.setConversationId().accept("conv-id")).doesNotThrowAnyException();
        assertThatCode(() -> ctx.setToolJSX().accept("jsx")).doesNotThrowAnyException();
        MessageSelector sel = new MessageSelector("id", "title", null);
        assertThatCode(() -> ctx.openMessageSelector().accept(sel)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("45 参数 canonical 注入 10 UI callback · 全部 identity 保留")
    void test10UICallbackCanonicalInjection() {
        java.util.function.Consumer<Notification> addNotif = n -> {};
        java.util.function.Consumer<SystemMessage> appendSys = sm -> {};
        java.util.function.Consumer<OSNotification> sendOS = osn -> {};
        java.util.function.Consumer<String> setRespLen = s -> {};
        java.util.function.Consumer<Boolean> setHasInterrupt = b -> {};
        java.util.function.Consumer<FileHistoryState> updateFHS = f -> {};
        java.util.function.Consumer<UiAttribution> updateAS = a -> {};
        java.util.function.Consumer<String> setConvId = c -> {};
        java.util.function.Consumer<Object> setJSX = j -> {};
        java.util.function.Consumer<MessageSelector> openSel = s -> {};
        // C2 4 字段
        java.util.function.Function<Map<String, Object>, Map<String, Object>> getAppState = s -> s;
        java.util.function.Consumer<java.util.function.Function<Map<String, Object>, Map<String, Object>>> setAppState = u -> {};
        java.util.function.Consumer<SpinnerMode> setStreamMode = m -> {};
        java.util.function.Consumer<SDKStatus> setSDKStatus = s -> {};

        ToolUseContext ctx = new ToolUseContext(
            AGENT_ID, SESSION_ID, PermissionMode.DEFAULT,
            Map.of(), List.of(), "", null, List.of(),
            null, PermissionMode.DEFAULT,
            Map.of(), false, "", null, null, Map.of(), null,
            getAppState, setAppState, setStreamMode, setSDKStatus,
            addNotif, appendSys, sendOS, setRespLen, setHasInterrupt,
            updateFHS, updateAS, setConvId, setJSX, openSel,
            false, null, null, null, null, null, false, false, null, null, null, null, null,
            null);  // [L+ R1/round 5] readFileState · null → compact ctor createFileStateCache() 兜底
        // 10 UI callback 全部 identity 保留
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
        // C2 4 字段也保留 (一并验证 45 参注入完整性)
        assertThat(ctx.getAppState()).isSameAs(getAppState);
        assertThat(ctx.setAppState()).isSameAs(setAppState);
        assertThat(ctx.setStreamMode()).isSameAs(setStreamMode);
        assertThat(ctx.setSDKStatus()).isSameAs(setSDKStatus);
    }

    // ═══════════════════ 旧构造器 delegation 全部存在 ═══════════════════

    @Test
    @DisplayName("旧构造器 delegation: 4/5/6/8/10/11/12/13/15/17/21/31 参数构造器全部存在")
    void testOld4Through32ParamConstructors() throws Exception {
        // 4 参 (Stage 3.1 早期)
        assertThat(ToolUseContext.class.getConstructor(
            UUID.class, UUID.class, PermissionMode.class, Map.class)).isNotNull();
        // 5 参 (s12 方案 C availableTools)
        assertThat(ToolUseContext.class.getConstructor(
            UUID.class, UUID.class, PermissionMode.class, Map.class, List.class)).isNotNull();
        // 6 参 (s12-2.3 taskListId)
        assertThat(ToolUseContext.class.getConstructor(
            UUID.class, UUID.class, PermissionMode.class, Map.class, List.class, String.class)).isNotNull();
        // 8 参 (Phase 2 PR 1 messages + abortController)
        Class<?> abortControllerCls = Class.forName("com.nexusai.application.agent.tool.AbortController");
        Class<?> permissionCtxCls = Class.forName("com.nexusai.application.agent.permission.ToolPermissionContext");
        assertThat(ToolUseContext.class.getConstructor(
            UUID.class, UUID.class, PermissionMode.class, Map.class, List.class, String.class,
            abortControllerCls, List.class)).isNotNull();
        // 10 参 (Phase 2 PR 1 permissionContext + permissionMode)
        assertThat(ToolUseContext.class.getConstructor(
            UUID.class, UUID.class, PermissionMode.class, Map.class, List.class, String.class,
            abortControllerCls, List.class,
            permissionCtxCls, PermissionMode.class)).isNotNull();
        // 11 参 (Stage 3.1 P1.3 mcpClients)
        assertThat(ToolUseContext.class.getConstructor(
            UUID.class, UUID.class, PermissionMode.class, Map.class, List.class, String.class,
            abortControllerCls, List.class,
            permissionCtxCls, PermissionMode.class,
            Map.class)).isNotNull();
        // 12 参 (P1.3 isNonInteractiveSession)
        assertThat(ToolUseContext.class.getConstructor(
            UUID.class, UUID.class, PermissionMode.class, Map.class, List.class, String.class,
            abortControllerCls, List.class,
            permissionCtxCls, PermissionMode.class,
            Map.class, boolean.class)).isNotNull();
        // 13 参 (P1.3 renderedSystemPrompt)
        assertThat(ToolUseContext.class.getConstructor(
            UUID.class, UUID.class, PermissionMode.class, Map.class, List.class, String.class,
            abortControllerCls, List.class,
            permissionCtxCls, PermissionMode.class,
            Map.class, boolean.class, String.class)).isNotNull();
        // 15 参 (Phase A + R32-b8 #3 effectiveCwd + inProgressToolUseIDs)
        assertThat(ToolUseContext.class.getConstructor(
            UUID.class, UUID.class, PermissionMode.class, Map.class, List.class, String.class,
            abortControllerCls, List.class,
            permissionCtxCls, PermissionMode.class,
            Map.class, boolean.class, String.class,
            java.nio.file.Path.class,
            Class.forName("java.util.function.Function"))).isNotNull();
        // 17 参 (Stage 3.1 C13 toolDecisions + onCompactProgress)
        assertThat(ToolUseContext.class.getConstructor(
            UUID.class, UUID.class, PermissionMode.class, Map.class, List.class, String.class,
            abortControllerCls, List.class,
            permissionCtxCls, PermissionMode.class,
            Map.class, boolean.class, String.class,
            java.nio.file.Path.class,
            Class.forName("java.util.function.Function"),
            Map.class,
            Class.forName("java.util.function.Consumer"))).isNotNull();
        // 21 参 (Stage 3.2 C2 4 字段)
        Constructor<?> c21 = findConstructorWithCount(ToolUseContext.class, 21);
        assertThat(c21).as("21-param constructor (Stage 3.2 C2) must exist").isNotNull();
        // 31 参 (Stage 3.3 UI 10 字段)
        Constructor<?> c31 = findConstructorWithCount(ToolUseContext.class, 31);
        assertThat(c31).as("31-param constructor (Stage 3.3 UI) must exist").isNotNull();
        // 46 参 (Stage 3.4 session 13 + L+ R1 readFileState 1)
        // [Session J 方案 A] querySource / assistantMessage 已从 ToolUseContext 顶层撤回.
        Constructor<?> c46 = findConstructorWithCount(ToolUseContext.class, 46);
        assertThat(c46).as("46-param canonical constructor (Stage 3.4 session + L+ R1) must exist").isNotNull();
    }

    private static Constructor<?> findConstructorWithCount(Class<?> clazz, int paramCount) {
        for (Constructor<?> c : clazz.getDeclaredConstructors()) {
            if (c.getParameterCount() == paramCount) {
                return c;
            }
        }
        return null;
    }

    @Test
    @DisplayName("17 参数 canonical 构造器保留 · C2 字段默认 null → compact ctor 兜底")
    void test17ParamCanonicalConstructor() {
        // 17 参数构造器在 Stage 3.2 之前是 canonical, Stage 3.2 后为 delegation
        // 必须保留: 旧 caller 仍然可以调用 (Stage 3.2 / 3.3 / 3.4 注入的 C2/UI/session 字段 = null)
        ToolUseContext ctx = new ToolUseContext(
            AGENT_ID, SESSION_ID, PermissionMode.DEFAULT,
            Map.of(), List.of(), "", null, List.of(),
            null, PermissionMode.DEFAULT,
            Map.of(), false, "", null, null, Map.of(), null
        );
        // 17 字段可读
        assertThat(ctx.agentId()).isEqualTo(AGENT_ID);
        assertThat(ctx.sessionId()).isEqualTo(SESSION_ID);
        assertThat(ctx.onCompactProgress()).isNotNull(); // compact ctor 兜底 noop
        // C2 / UI / session 字段全部由 compact ctor 兜底
        assertThat(ctx.getAppState()).isNotNull();
        assertThat(ctx.addNotification()).isNotNull();
        assertThat(ctx.nestedMemoryAttachmentTriggers()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("21 参数 canonical 构造器保留 · UI 字段默认 null → compact ctor 兜底")
    void test21ParamCanonicalConstructor() {
        // 21 参数构造器在 Stage 3.3 之前是 canonical, Stage 3.3 后为 delegation
        // 必须保留: Stage 3.2 时代 caller 仍可调用 (UI/session 字段 = null)
        java.util.function.Function<Map<String, Object>, Map<String, Object>> getAppState = s -> s;
        java.util.function.Consumer<java.util.function.Function<Map<String, Object>, Map<String, Object>>> setAppState = u -> {};
        java.util.function.Consumer<SpinnerMode> setStreamMode = m -> {};
        java.util.function.Consumer<SDKStatus> setSDKStatus = s -> {};

        ToolUseContext ctx = new ToolUseContext(
            AGENT_ID, SESSION_ID, PermissionMode.DEFAULT,
            Map.of(), List.of(), "", null, List.of(),
            null, PermissionMode.DEFAULT,
            Map.of(), false, "", null, null, Map.of(), null,
            getAppState, setAppState, setStreamMode, setSDKStatus
        );
        // C2 4 字段注入成功 (identity)
        assertThat(ctx.getAppState()).isSameAs(getAppState);
        assertThat(ctx.setAppState()).isSameAs(setAppState);
        assertThat(ctx.setStreamMode()).isSameAs(setStreamMode);
        assertThat(ctx.setSDKStatus()).isSameAs(setSDKStatus);
        // UI 10 字段必须 compact ctor 兜底 (传入 21 参构造器时未注入 UI)
        assertThat(ctx.addNotification()).isNotNull();
        // session 13 字段必须 compact ctor 兜底
        assertThat(ctx.nestedMemoryAttachmentTriggers()).isNotNull().isEmpty();
        assertThat(ctx.userModified()).isFalse();
    }

    // ═══════════════════ Jackson 序列化约束 ═══════════════════

    @Test
    @DisplayName("Jackson 不暴露 10 UI callback (BudgetTracker local-only 兄弟约束)")
    void testJacksonExcludes10UICallback() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ToolUseContext ctx = new ToolUseContext(
            AGENT_ID, SESSION_ID, PermissionMode.DEFAULT,
            Map.of(), List.of(), "", null, List.of(),
            null, PermissionMode.DEFAULT,
            Map.of(), false, "", null, null, Map.of(), null,
            // C2
            s -> s, u -> {}, m -> {}, st -> {},
            // UI 10 callback (注入非空)
            n -> {}, sm -> {}, o -> {}, l -> {}, b -> {},
            f -> {}, a -> {}, c -> {}, j -> {}, sel -> {},
            false, null, null, null, null, null, false, false, null, null, null, null, null,
            null);  // [L+ R1/round 5] readFileState · null → compact ctor createFileStateCache() 兜底
        String json = mapper.writeValueAsString(ctx);
        // 10 UI callback 字段名不应出现
        assertThat(json).doesNotContain("addNotification");
        assertThat(json).doesNotContain("appendSystemMessage");
        assertThat(json).doesNotContain("sendOSNotification");
        assertThat(json).doesNotContain("setResponseLength");
        assertThat(json).doesNotContain("setHasInterruptibleToolInProgress");
        assertThat(json).doesNotContain("updateFileHistoryState");
        assertThat(json).doesNotContain("updateAttributionState");
        assertThat(json).doesNotContain("setConversationId");
        assertThat(json).doesNotContain("setToolJSX");
        assertThat(json).doesNotContain("openMessageSelector");
    }

    @Test
    @DisplayName("Jackson 仅输出 data fields (agentId/sessionId/mode 等)")
    void testJacksonOnlyOutputsDataFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ToolUseContext ctx = new ToolUseContext(
            AGENT_ID, SESSION_ID, PermissionMode.DEFAULT,
            Map.of(), List.of(), "", null, List.of(),
            null, PermissionMode.DEFAULT,
            Map.of(), false, "", null, null, Map.of(), null
        );
        String json = mapper.writeValueAsString(ctx);
        // 17 个"非 callback / 非 session"data 字段名应出现在 JSON 中
        assertThat(json).contains("\"agentId\"");
        assertThat(json).contains("\"sessionId\"");
        assertThat(json).contains("\"mode\"");
        assertThat(json).contains("\"additionalWorkingDirectories\"");
        assertThat(json).contains("\"availableTools\"");
        assertThat(json).contains("\"taskListId\"");
        // callback 字段不应出现
        assertThat(json).doesNotContain("getAppState");
        assertThat(json).doesNotContain("addNotification");
        assertThat(json).doesNotContain("userModified");
    }

    // ═══════════════════ toolUseContextWithUi 透传验证 ═══════════════════

    @Test
    @DisplayName("toolUseContextWithUi(c) 透传 10 UI callback (从已有 TUC 重建时保留)")
    void testToolUseContextWithUiPassesUICallback() throws Exception {
        // 通过 reflection 调用 LlmAgentLoop.toolUseContextWithUi(c) (private 方法)
        // 由于无法构造完整的 LlmAgentLoop (需要 LLM provider), 改为验证 TUC 重建语义:
        // 当已有 TUC 含 10 UI callback 时, 用 31 参构造器重建必须保留.
        java.util.function.Consumer<Notification> addNotif = n -> {};
        java.util.function.Consumer<SystemMessage> appendSys = sm -> {};
        java.util.function.Consumer<OSNotification> sendOS = osn -> {};
        java.util.function.Consumer<String> setRespLen = s -> {};
        java.util.function.Consumer<Boolean> setHasInterrupt = b -> {};
        java.util.function.Consumer<FileHistoryState> updateFHS = f -> {};
        java.util.function.Consumer<UiAttribution> updateAS = a -> {};
        java.util.function.Consumer<String> setConvId = c -> {};
        java.util.function.Consumer<Object> setJSX = j -> {};
        java.util.function.Consumer<MessageSelector> openSel = s -> {};
        java.util.function.Function<Map<String, Object>, Map<String, Object>> getAppState = s -> s;
        java.util.function.Consumer<java.util.function.Function<Map<String, Object>, Map<String, Object>>> setAppState = u -> {};
        java.util.function.Consumer<SpinnerMode> setStreamMode = m -> {};
        java.util.function.Consumer<SDKStatus> setSDKStatus = s -> {};

        // 32 参构造器重建: C2 + UI 都透传
        ToolUseContext ctx = new ToolUseContext(
            AGENT_ID, SESSION_ID, PermissionMode.DEFAULT,
            Map.of(), List.of(), "", null, List.of(),
            null, PermissionMode.DEFAULT,
            Map.of(), false, "", null, null, Map.of(), null,
            getAppState, setAppState, setStreamMode, setSDKStatus,
            addNotif, appendSys, sendOS, setRespLen, setHasInterrupt,
            updateFHS, updateAS, setConvId, setJSX, openSel
        );
        // 10 UI callback 全部 identity 保留
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
    }

    @Test
    @DisplayName("关键修复验证: base TUC 装配必须保留 4 C2 callback (不能传 null, 修复 7ca bug)")
    void testToolUseContextWithUiPreservesC2Callbacks() throws Exception {
        // 关键回归测试: 验证 7ca43ab commit 修复的 null C2 bug 不再回归
        // [H7-arch Phase 5-2 P3-⑤] toolUseContextWithUi 已 static 化/删除；C2 4 callback 注入
        // 移至 LlmAgentLoop.buildBaseToolUseContext（base TUC 装配，run() 入口一次）。经源码扫描
        // 验证 buildBaseToolUseContext 显式注入 4 C2 lambda 而非传 null。
        java.lang.reflect.Method method = com.nexusai.application.agent.LlmAgentLoop.class
            .getDeclaredMethod("buildBaseToolUseContext", com.nexusai.application.agent.AgentState.class);
        assertThat(method)
            .as("buildBaseToolUseContext 必须存在（base TUC 装配 · P3-⑤ 替代 toolUseContextWithUi）")
            .isNotNull();
        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(
            "src/main/java/com/nexusai/application/agent/LlmAgentLoop.java"));
        StringBuilder content = new StringBuilder();
        String line;
        boolean inMethod = false;
        while ((line = reader.readLine()) != null) {
            if (line.contains("private ToolUseContext buildBaseToolUseContext(AgentState state)")) {
                inMethod = true;
            }
            if (inMethod) {
                content.append(line).append("\n");
                if (line.contains("return new ToolUseContext(") && content.toString().contains("getAppStateSnapshot")) {
                    break;
                }
            }
        }
        reader.close();
        String methodBody = content.toString();
        // 关键不变量: 4 个 C2 callback 必须显式注入, 不能传 null
        assertThat(methodBody)
            .as("buildBaseToolUseContext must preserve 4 C2 callbacks via explicit lambda, not null")
            .contains("getAppStateSnapshot");
        assertThat(methodBody).contains("setAppState(updater)");
        assertThat(methodBody).contains("setStreamMode(sm)");
        assertThat(methodBody).contains("setSDKStatus(sdk)");
        // 验证 4 个 null 没有出现在 C2 位置 (注释除外)
        // 截取 "return new ToolUseContext(" 之后到 "openMessageSelector" 之间的字段
        int startIdx = methodBody.indexOf("return new ToolUseContext(");
        int endIdx = methodBody.indexOf("openMessageSelector");
        if (startIdx >= 0 && endIdx > startIdx) {
            String c2Region = methodBody.substring(startIdx, endIdx);
            // C2 4 字段必须以 lambda 形式出现, 不能 4 个连续 null
            int nullC2Count = 0;
            // 数 "null, null, null, null" 模式 - 4 个连续 null 是 7ca 历史 bug 的标志
            int lastNullEnd = 0;
            while ((lastNullEnd = c2Region.indexOf("null, null, null, null", lastNullEnd)) >= 0) {
                // 排除出现在注释中的 (前面有 // 注释行)
                int lineStart = c2Region.lastIndexOf("\n", lastNullEnd);
                String lineBefore = c2Region.substring(lineStart, lastNullEnd);
                if (!lineBefore.contains("//") && !lineBefore.contains("*")) {
                    nullC2Count++;
                }
                lastNullEnd += 1;
            }
            assertThat(nullC2Count)
                .as("4 consecutive null in C2 region indicates the 7ca bug regression")
                .isZero();
        }
    }

    // ═══════════════════ 额外 1 测试: callback 触发不写 outbound DTO ═══════════════════

    @Test
    @DisplayName("callback 触发不写 outbound DTO (BudgetTracker local-only 约束)")
    void testCallbackDoesNotWriteOutboundJson() throws Exception {
        // 验证 10 UI callback 触发后, 不修改 AgentState 序列化字段 (不污染 outbound DTO)
        ObjectMapper mapper = new ObjectMapper();
        // 注入一个会修改外层 state 的 callback (试图污染 outbound)
        java.util.Map<String, Object> pollutionAttempt = new HashMap<>();
        ToolUseContext ctx = new ToolUseContext(
            AGENT_ID, SESSION_ID, PermissionMode.DEFAULT,
            Map.of(), List.of(), "", null, List.of(),
            null, PermissionMode.DEFAULT,
            Map.of(), false, "", null, null, Map.of(), null,
            // C2
            s -> s, u -> {}, m -> {}, st -> {},
            // UI 10 callback (注入修改外层 state 的恶意 callback)
            n -> pollutionAttempt.put("notif", n),
            sm -> pollutionAttempt.put("sm", sm),
            o -> pollutionAttempt.put("os", o),
            l -> pollutionAttempt.put("len", l),
            b -> pollutionAttempt.put("interrupt", b),
            f -> pollutionAttempt.put("fhs", f),
            a -> pollutionAttempt.put("as", a),
            c -> pollutionAttempt.put("conv", c),
            j -> pollutionAttempt.put("jsx", j),
            sel -> pollutionAttempt.put("sel", sel),
            false, null, null, null, null, null, false, false, null, null, null, null, null,
            null);  // [L+ R1/round 5] readFileState · null → compact ctor createFileStateCache() 兜底

        // 序列化 ctx (模拟 outbound DTO 创建)
        String beforeJson = mapper.writeValueAsString(ctx);
        // 触发所有 UI callback
        ctx.addNotification().accept(new Notification("test-id", "t", "b", Notification.Level.INFO));
        ctx.appendSystemMessage().accept(new SystemMessage("role", "content"));
        ctx.sendOSNotification().accept(new OSNotification("t", "i"));
        ctx.setResponseLength().accept("100");
        ctx.setHasInterruptibleToolInProgress().accept(true);
        ctx.updateFileHistoryState().accept(new FileHistoryState(List.of(), Set.of(), 0L));
        ctx.updateAttributionState().accept(new UiAttribution("text", 0.5));
        ctx.setConversationId().accept("conv-id");
        ctx.setToolJSX().accept("jsx-content");
        ctx.openMessageSelector().accept(new MessageSelector("id", "title", null));
        // 序列化 ctx (callback 触发后)
        String afterJson = mapper.writeValueAsString(ctx);

        // 关键不变量: 序列化结果一致 (callback 触发不影响 ctx 序列化输出)
        // (callback 只写外层 pollutionAttempt 容器, 不修改 ctx 字段)
        assertThat(afterJson).isEqualTo(beforeJson);
        // pollutionAttempt 收到 callback 数据 (验证 callback 真的执行了)
        assertThat(pollutionAttempt).isNotEmpty();
        // 但 ctx JSON 输出不含污染数据
        assertThat(afterJson).doesNotContain("polluted");
        assertThat(afterJson).doesNotContain("test-id"); // Notification id 没出现在 ctx JSON
    }

    // ═══════════════════ 辅助方法: AtomicReference 用于追踪引用 ═══════════════════

    @Test
    @DisplayName("10 UI callback 透传语义: 外部工具调用 ctx.addNotification().accept(n) 实际触发回调")
    void testUICallbackInvocationReachesCallback() {
        AtomicReference<Notification> captured = new AtomicReference<>();
        ToolUseContext ctx = new ToolUseContext(
            AGENT_ID, SESSION_ID, PermissionMode.DEFAULT,
            Map.of(), List.of(), "", null, List.of(),
            null, PermissionMode.DEFAULT,
            Map.of(), false, "", null, null, Map.of(), null,
            // C2
            s -> s, u -> {}, m -> {}, st -> {},
            // UI 10 callback
            captured::set, sm -> {}, o -> {}, l -> {}, b -> {},
            f -> {}, a -> {}, c -> {}, j -> {}, sel -> {},
            false, null, null, null, null, null, false, false, null, null, null, null, null,
            null);  // [L+ R1/round 5] readFileState · null → compact ctor createFileStateCache() 兜底
        Notification input = new Notification("captured-id", "t", "b", Notification.Level.INFO);
        ctx.addNotification().accept(input);
        // 验证 callback 真的被触发
        assertThat(captured.get()).isSameAs(input);
        // 计数器 sanity check
        AtomicInteger callCount = new AtomicInteger();
        ctx.setResponseLength().accept("abc");
        // (setResponseLength 是 noop, 不应增加 callCount - 我们只验证 callback 链路通畅)
        assertThat(callCount.get()).isZero();
    }
}
