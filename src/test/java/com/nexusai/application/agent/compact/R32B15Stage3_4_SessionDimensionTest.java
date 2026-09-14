package com.nexusai.application.agent.compact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;



import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R32-b15 Stage 3.4 · session 维度 13 字段测试.
 *
 * <p>覆盖范围:
 * <ul>
 *   <li>reflection 验证 record components <b>存在性 + 相对顺序</b>（canonical 顺序不变量，
 *       [批 7 结构性锁重锚] 取代旧「总数 == 46」数字锁；见 {@link #recordComponentOrder()})</li>
 *   <li>所有新增 callback/session 字段均 {@code @JsonIgnore} (BudgetTracker local-only 约束)</li>
 *   <li>4 个 Set 默认空可变 (CC 工具可 .add()/.clear())</li>
 *   <li>Jackson 序列化不暴露 27 个新字段 (4 C2 + 10 UI + 13 session)</li>
 *   <li>不重复字段: sessionId / autoMode / isAutoMode / getSessionMemory</li>
 * </ul>
 */
class R32B15Stage3_4_SessionDimensionTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final String SESSION_ID = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);

    /**
     * [批 7 结构性锁重锚 2026-09-14] canonical record components 顺序表
     * （= 2026-09-14 实测 `getRecordComponents()` 输出，52 项）。
     *
     * <p>⛔ 本表是「顺序基线」，不是「字段总数锁」—— 断言用 {@code containsSubsequence}
     * （存在性 + 相对顺序），**新增字段不会让本锁变红**；只有
     * <b>改名 / 删除 / 重排</b>既有组件才会红。
     * 新增字段时请把新名按实际位置插进本表（保持表与实测一致）。
     */
    private static final List<String> EXPECTED_CANONICAL_ORDER = List.of(
        // 1-17 既有字段
        "agentId", "sessionId", "mode", "additionalWorkingDirectories", "availableTools",
        "taskListId", "abortController", "messages", "permissionContext", "permissionMode",
        "mcpClients", "isNonInteractiveSession", "renderedSystemPrompt", "effectiveCwd",
        "inProgressToolUseIDs", "toolDecisions", "onCompactProgress",
        // 18-21 Stage 3.2 C2 4 字段
        "getAppState", "setAppState", "setStreamMode", "setSDKStatus",
        // 22-31 Stage 3.3 UI 10 字段
        "addNotification", "appendSystemMessage", "sendOSNotification", "setResponseLength",
        "setHasInterruptibleToolInProgress", "updateFileHistoryState", "updateAttributionState",
        "setConversationId", "setToolJSX", "openMessageSelector",
        // 32-44 Stage 3.4 session 13 字段
        "userModified", "nestedMemoryAttachmentTriggers", "loadedNestedMemoryPaths",
        "dynamicSkillDirTriggers", "discoveredSkillNames", "agentType", "requireCanUseTool",
        "preserveToolUseResults", "localDenialTracking", "contentReplacementState",
        "queryTracking", "toolUseId", "criticalSystemReminder_EXPERIMENTAL",
        // 45 [Session L+ R1] readFileState
        "readFileState",
        // 46 [MCP-I-9 Q-30] mcpServerConnections
        "mcpServerConnections",
        // 47 [OPD-D1-01] fileReadingLimits
        "fileReadingLimits",
        // 48-49 [openai-lazy] effectiveModelName / effectiveProviderType
        "effectiveModelName", "effectiveProviderType",
        // 50-51 [tuc-subagent-identity] subagentName / isBuiltIn
        "subagentName", "isBuiltIn",
        // 52 [批 5b-1] agentContext
        "agentContext");

    /** record components 名（canonical 顺序 · {@code getRecordComponents()} 规范保证顺序）。 */
    private static List<String> componentNames() {
        return java.util.Arrays.stream(ToolUseContext.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)
            .toList();
    }

    /**
     * [批 7 结构性锁重锚] 存在性 + 相对顺序锁 —— 取代旧「非 static 字段数 == 46」数字锁。
     *
     * <p><b>WHY 不再数总数</b>：ToolUseContext 是 per-turn 装配的会话载体，字段<b>持续增长</b>
     * （Stage 3.2 C2 4 / Stage 3.3 UI 10 / Stage 3.4 session 13 / L+ R1 readFileState /
     * MCP-I-9 mcpServerConnections / OPD-D1-01 fileReadingLimits / openai-lazy 2 /
     * tuc-subagent-identity 2 / 批 5b-1 agentContext）。旧断言每加一个字段必红 ⇒
     * <b>基线即红、永远不可能绿</b>（2026-09-14 实测：期望 46 / 实测 52），
     * 红是噪音而非信号 ⇒ 锁失去守护意义（这正是它被重锚的原因）。
     *
     * <p><b>WHY 仍必须锁顺序</b>：canonical 构造器是<b>位置参数</b>的（52 参），而组件中存在
     * <b>相邻同型</b>组 —— 3 个 {@code Map}（{@code localDenialTracking} /
     * {@code contentReplacementState} / {@code queryTracking}）、2 个 {@code boolean}
     * （{@code requireCanUseTool} / {@code preserveToolUseResults}）—— 重排/互换时
     * <b>编译器不报错</b>（位置参数类型仍匹配）而语义静默互换。故本锁守的是
     * 「既有组件名都在，且相对顺序不变」；<b>新增</b>组件（追加或插段）不应使锁变红。
     */
    @Test
    @DisplayName("record components 存在性 + 相对顺序锁（canonical 顺序不变量 · 新增字段不误报）")
    void recordComponentOrder() {
        assertThat(componentNames())
            .as("record components 必须包含既有全部组件且相对顺序不变"
                + "（改名/删除/重排 ⇒ 红；纯新增 ⇒ 绿）")
            .containsSubsequence(EXPECTED_CANONICAL_ORDER);
    }

    @Test
    @DisplayName("sessionId 仅 1 个 record component (不重复)")
    void sessionIdUnique() throws Exception {
        Field[] fields = ToolUseContext.class.getDeclaredFields();
        long sessionIdCount = java.util.Arrays.stream(fields)
            .filter(f -> f.getName().equals("sessionId"))
            .count();
        assertThat(sessionIdCount).isEqualTo(1);
    }

    @Test
    @DisplayName("不重复字段: 无 autoMode / isAutoMode / getSessionMemory")
    void noDuplicateFields() {
        Field[] fields = ToolUseContext.class.getDeclaredFields();
        List<String> fieldNames = new ArrayList<>();
        for (Field f : fields) {
            fieldNames.add(f.getName());
        }
        // AutoModeGate 负责 autoMode / isAutoMode, 不在 TUC 重复
        assertThat(fieldNames).doesNotContain("autoMode");
        assertThat(fieldNames).doesNotContain("isAutoMode");
        // getSessionMemory 是 CC 服务函数, 不是 TUC 字段
        assertThat(fieldNames).noneMatch(n -> n.contains("SessionMemory"));
    }

    @Test
    @DisplayName("13 个 session 字段均 @JsonIgnore (BudgetTracker local-only 兄弟约束)")
    void sessionFieldsAllJsonIgnored() throws Exception {
        Field[] fields = ToolUseContext.class.getDeclaredFields();
        String[] sessionFieldNames = {
            "userModified", "nestedMemoryAttachmentTriggers", "loadedNestedMemoryPaths",
            "dynamicSkillDirTriggers", "discoveredSkillNames", "agentType",
            "requireCanUseTool", "preserveToolUseResults", "localDenialTracking",
            "contentReplacementState", "queryTracking", "toolUseId",
            "criticalSystemReminder_EXPERIMENTAL"
        };
        for (String name : sessionFieldNames) {
            Field f = ToolUseContext.class.getDeclaredField(name);
            assertThat(f.isAnnotationPresent(com.fasterxml.jackson.annotation.JsonIgnore.class))
                .as("Field %s must have @JsonIgnore", name)
                .isTrue();
        }
    }

    @Test
    @DisplayName("4 个 C2 callback 字段均 @JsonIgnore")
    void c2FieldsAllJsonIgnored() throws Exception {
        String[] c2Names = {"getAppState", "setAppState", "setStreamMode", "setSDKStatus"};
        for (String name : c2Names) {
            Field f = ToolUseContext.class.getDeclaredField(name);
            assertThat(f.isAnnotationPresent(com.fasterxml.jackson.annotation.JsonIgnore.class))
                .as("Field %s must have @JsonIgnore", name)
                .isTrue();
        }
    }

    @Test
    @DisplayName("10 个 UI callback 字段均 @JsonIgnore")
    void uiFieldsAllJsonIgnored() throws Exception {
        String[] uiNames = {
            "addNotification", "appendSystemMessage", "sendOSNotification",
            "setResponseLength", "setHasInterruptibleToolInProgress",
            "updateFileHistoryState", "updateAttributionState", "setConversationId",
            "setToolJSX", "openMessageSelector"
        };
        for (String name : uiNames) {
            Field f = ToolUseContext.class.getDeclaredField(name);
            assertThat(f.isAnnotationPresent(com.fasterxml.jackson.annotation.JsonIgnore.class))
                .as("Field %s must have @JsonIgnore", name)
                .isTrue();
        }
    }

    @Test
    @DisplayName("Jackson 序列化不暴露任何 callback / session 字段 (BudgetTracker local-only)")
    void jacksonDoesNotExposeCallbacksOrSessionFields() throws Exception {
        // 注入所有 callback / session 字段为非空值, 验证 Jackson 不序列化
        ObjectMapper mapper = new ObjectMapper();
        // [R32-b15 Stage 3.1 C13 P1-1] onCompactProgress 现在 @JsonIgnore, 必须以非空 Consumer 注入,
        // 否则无法验证 Jackson 不暴露其字段名 (lambda 引用本身 + 闭包 session 状态均敏感).
        java.util.concurrent.atomic.AtomicInteger compactEventCount = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Consumer<CompactProgressEvent> sensitiveOnCompactProgress =
            event -> compactEventCount.incrementAndGet();
        ToolUseContext ctx = new ToolUseContext(
            AGENT_ID, SESSION_ID, PermissionMode.DEFAULT,
            Map.of(), List.of(), "", null, List.of(),
            null, PermissionMode.DEFAULT,
            Map.of(), false, "", null, null, Map.of(), sensitiveOnCompactProgress,
            // C2 4 字段
            s -> s, u -> {}, m -> {}, st -> {},
            // UI 10 字段
            n -> {}, sm -> {}, o -> {}, l -> {}, b -> {},
            f -> {}, a -> {}, c -> {}, j -> {}, sel -> {},
            // Session 13 字段
            true,
            ConcurrentHashMap.newKeySet(),
            ConcurrentHashMap.newKeySet(),
            ConcurrentHashMap.newKeySet(),
            ConcurrentHashMap.newKeySet(),
            "sensitive-agent-type",
            true, true,
            Map.of("denial", "track"), Map.of("replacement", "v"), Map.of("chain", "id"),
            "sensitive-tool-use-id", "critical-reminder-text",
            null);  // [L+ R1/round 5] readFileState · null → compact ctor createFileStateCache() 兜底

        String json = mapper.writeValueAsString(ctx);
        // [R32-b15 Stage 3.1 C13 P1-1] onCompactProgress 字段名不应出现在 JSON 中
        assertThat(json).doesNotContain("onCompactProgress");
        // 4 C2 字段名不应出现
        assertThat(json).doesNotContain("getAppState");
        assertThat(json).doesNotContain("setAppState");
        assertThat(json).doesNotContain("setStreamMode");
        assertThat(json).doesNotContain("setSDKStatus");
        // 10 UI 字段名不应出现
        assertThat(json).doesNotContain("addNotification");
        assertThat(json).doesNotContain("appendSystemMessage");
        // 13 session 字段名不应出现 (尤其是 sensitive 值)
        assertThat(json).doesNotContain("sensitive-agent-type");
        assertThat(json).doesNotContain("sensitive-tool-use-id");
        assertThat(json).doesNotContain("critical-reminder-text");
        assertThat(json).doesNotContain("userModified");
        assertThat(json).doesNotContain("localDenialTracking");
        // 验证 lambda 引用仍可调用 (注入成功, 不是被 strip)
        assertThat(ctx.onCompactProgress()).isSameAs(sensitiveOnCompactProgress);
    }

    @Test
    @DisplayName("31 参数兼容: Stage 3.3 兼容构造器 (含 C2 4 + UI 10, 无 session) 兜底 session")
    void stage33CompatibilityConstructorDelegatesToCanonical() {
        // 31 参构造 (Stage 3.3 时代)
        ToolUseContext ctx = new ToolUseContext(
            AGENT_ID, SESSION_ID, PermissionMode.DEFAULT,
            Map.of(), List.of(), "", null, List.of(),
            null, PermissionMode.DEFAULT,
            Map.of(), false, "", null, null, Map.of(), null,
            s -> s, u -> {}, m -> {}, st -> {},
            n -> {}, sm -> {}, o -> {}, l -> {}, b -> {},
            f -> {}, a -> {}, c -> {}, j -> {}, sel -> {}
            // [L+ R1] readFileState 由 compact ctor 兜底 (空 Map)
        );
        // session 13 字段全部 null/false
        assertThat(ctx.userModified()).isFalse();
        assertThat(ctx.agentType()).isNull();
        assertThat(ctx.requireCanUseTool()).isFalse();
        assertThat(ctx.preserveToolUseResults()).isFalse();
        assertThat(ctx.localDenialTracking()).isNull();
        assertThat(ctx.contentReplacementState()).isNull();
        assertThat(ctx.queryTracking()).isNull();
        assertThat(ctx.toolUseId()).isNull();
        assertThat(ctx.criticalSystemReminder_EXPERIMENTAL()).isNull();
        // 4 Set 兜底空可变
        assertThat(ctx.nestedMemoryAttachmentTriggers()).isEmpty();
        assertThat(ctx.loadedNestedMemoryPaths()).isEmpty();
        assertThat(ctx.dynamicSkillDirTriggers()).isEmpty();
        assertThat(ctx.discoveredSkillNames()).isEmpty();
    }

    @Test
    @DisplayName("contentReplacementState 不双写 AgentState (默认 null) · TUC 为 local bridge/view")
    void contentReplacementStateLocalBridgeOnly() {
        ToolUseContext ctx = new ToolUseContext(
            AGENT_ID, SESSION_ID, PermissionMode.DEFAULT,
            Map.of(), List.of(), "", null, List.of(),
            null, PermissionMode.DEFAULT,
            Map.of(), false, "", null, null, Map.of(), null
        );
        // canonical source 在 AgentState.contentReplacements, TUC 仅 bridge
        assertThat(ctx.contentReplacementState()).isNull();
        assertThat(ctx.localDenialTracking()).isNull();
        assertThat(ctx.queryTracking()).isNull();
    }

    @Test
    @DisplayName("4 个 Set 必须可变 · 不可变 Set 输入被转换为可变 (CC 工具 .add()/.clear() 不抛)")
    void setsMustBeMutable() {
        Set<String> immutable = java.util.Set.copyOf(List.of("a", "b"));
        ToolUseContext ctx = new ToolUseContext(
            AGENT_ID, SESSION_ID, PermissionMode.DEFAULT,
            Map.of(), List.of(), "", null, List.of(),
            null, PermissionMode.DEFAULT,
            Map.of(), false, "", null, null, Map.of(), null,
            null, null, null, null,
            null, null, null, null, null, null, null, null, null, null,
            false, immutable, immutable, immutable, immutable,
            null, false, false, null, null, null, null, null,
            null);  // [L+ R1/round 5] readFileState · null → compact ctor createFileStateCache() 兜底
        // 4 个 Set 都必须支持 .add() 和 .clear()
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
            () -> ctx.nestedMemoryAttachmentTriggers().add("new"));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
            () -> ctx.loadedNestedMemoryPaths().clear());
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
            () -> ctx.dynamicSkillDirTriggers().add("trigger"));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
            () -> ctx.discoveredSkillNames().clear());
    }
}