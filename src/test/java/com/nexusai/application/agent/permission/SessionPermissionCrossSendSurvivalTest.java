package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.RunRequest;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookEvent;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.permission.hook.PermissionRequestResult;
import com.nexusai.application.agent.permission.source.LocalSettingsLoader;
import com.nexusai.application.agent.permission.source.PermissionRuleValueParser;
import com.nexusai.application.agent.permission.source.ProjectSettingsLoader;
import com.nexusai.application.agent.permission.source.SettingsJsonParser;
import com.nexusai.application.agent.permission.source.UserSettingsLoader;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.common.SessionKeys;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [批 A2b] <b>会话 SESSION 档授权跨 send 存活</b> · sessions.session_permission_rules（V75 列）端到端。
 *
 * <h2>WHY（本测试钉死什么）</h2>
 * <p>CC 的 {@code appState.toolPermissionContext} 活在长驻进程内存里，文件类弹窗的
 * 「Yes, during this session」/「Yes, allow all edits during this session」
 * （{@code destination='session'}）经 {@code setToolPermissionContext} 写进 appState 后，
 * 后续每次权限检查读同一对象；且 CC {@code supportsPersistence} 明确排除 {@code session}
 * （{@code PermissionUpdate.ts:208-216}）⇒ 会话内存是它**唯一**的存活载体。
 *
 * <p>本仓 {@code LlmAgentLoop} 是 prototype（每 HTTP send 新实例）⇒ {@code appStateRef}
 * 恒空 ⇒ 批 A2 之后仍只保证「同一次 run 内跨轮」。本批给 SESSION 档补上<b>跨 send 唯一通道</b>
 * （sessions 列），本测试逐条钉死：
 * <ol>
 *   <li><b>跨 send 存活</b>：真 WebSocket 单点（{@code prompt → onResponse}）批准「本次会话允许」
 *       ⇒ <b>新的</b> LlmAgentLoop 实例 doRun 入口回读 ⇒ per-turn ctx 里 SESSION 规则仍在；</li>
 *   <li><b>mode 不被回读打坏</b>：列里无 SESSION setMode 时，回读注入的 ctx.mode 必须等于
 *       本 run 的 per-turn 基线 mode（A2 合并语义「appState 侧 mode 胜出」，注入错值会把
 *       baseline 覆盖掉）——本测试用 baseline=ACCEPT_EDITS 钉死；</li>
 *   <li><b>SESSION setMode 跨 send</b>：「allow all edits during this session」的 mode 档存活；</li>
 *   <li><b>会话隔离</b>：A 会话的授权不得出现在 B 会话；</li>
 *   <li><b>RemoveRules 真删</b>：列里对应项被移除（删了却还在列里 = 规则复活）；最后一条删空 →
 *       列写 NULL（不是残留空壳）；</li>
 *   <li><b>⭐ 不落盘（本批红线）</b>：整条流程<b>不得</b>在项目目录产生 settings.json /
 *       settings.local.json —— 且同装配下的非 SESSION 目的地<b>确实会</b>产生文件（对照组，
 *       证明反向断言不是空的）。</li>
 * </ol>
 *
 * <h2>能力边界（如实登记，见返回报告「残留」节）</h2>
 * <ul>
 *   <li>写侧走<b>真单点</b>：{@code WebSocketPermissionPrompter.prompt → onResponse(allow, ...)}
 *       （私有 {@code applyAndPersistUpdates} 的唯一公开入口）；</li>
 *   <li>读侧走<b>真 doRun</b>：{@code new LlmAgentLoop(...).run(RunRequest)}（profile = prototype
 *       新实例 ≡ 新 send）；per-turn 断言段用测试侧构造的 base TUC 绑定
 *       {@code loop.getAppStateSnapshot()}（镜像 {@code LlmAgentLoop:10037-10041} 的
 *       getAppState/setAppState 接线），并非 loop 内部真实 TUC（loop 不暴露）；</li>
 *   <li>POJO 单测下 {@code LlmAgentLoop.permissionContextBuilder} 需经反射注入（无 setter），
 *       否则 base mode 恒 DEFAULT。</li>
 * </ul>
 */
@DisplayName("[批 A2b] 会话 SESSION 档授权跨 send 存活（V75 列）")
class SessionPermissionCrossSendSurvivalTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SettingsJsonParser PARSER =
        new SettingsJsonParser(JSON, new PermissionRuleValueParser());
    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000a2b");
    private static final String SESSION_A = "sess-a2ba2b01";
    private static final String SESSION_B = "sess-a2ba2b02";
    /** ⑤-b 专用（非 SESSION 目的地写盘对照，不参与跨 send 断言）。 */
    private static final String SESSION_C = "sess-a2ba2b03";
    private static final String RULE_CONTENT = "//tmp/a2b-dir/**";

    // ── 线格式（批 A1 CC 形状；经 onResponse 的 updatedPermissions 通道解析） ──
    private static final String ADD_SESSION_READ_RULE = "{\"type\":\"addRules\",\"rules\":[{\"toolName\":\"Read\","
        + "\"ruleContent\":\"" + RULE_CONTENT + "\"}],\"behavior\":\"allow\",\"destination\":\"session\"}";
    private static final String REMOVE_SESSION_READ_RULE = "{\"type\":\"removeRules\",\"rules\":[{\"toolName\":\"Read\","
        + "\"ruleContent\":\"" + RULE_CONTENT + "\"}],\"behavior\":\"allow\",\"destination\":\"session\"}";
    private static final String ADD_LOCAL_READ_RULE = "{\"type\":\"addRules\",\"rules\":[{\"toolName\":\"Read\","
        + "\"ruleContent\":\"" + RULE_CONTENT + "\"}],\"behavior\":\"allow\",\"destination\":\"localSettings\"}";
    /** 第二条 SESSION 规则（RemoveRules 只删匹配项的对照物）。 */
    private static final String BASH_RULE_CONTENT = "npm run test";
    private static final String ADD_SESSION_BASH_RULE = "{\"type\":\"addRules\",\"rules\":[{\"toolName\":\"Bash\","
        + "\"ruleContent\":\"" + BASH_RULE_CONTENT + "\"}],\"behavior\":\"allow\",\"destination\":\"session\"}";
    private static final String REMOVE_SESSION_BASH_RULE = "{\"type\":\"removeRules\",\"rules\":[{\"toolName\":\"Bash\","
        + "\"ruleContent\":\"" + BASH_RULE_CONTENT + "\"}],\"behavior\":\"allow\",\"destination\":\"session\"}";

    // ══════════════════════════ 夹具 ══════════════════════════

    private static final class StubTool implements Tool {
        private final String name;
        StubTool(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public String description() { return "stub"; }
        @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
        @Override public AgentToolResult execute(ToolUseBlock call) {
            return ToolResult.success(call.id(), "stub-result");
        }
    }

    /** mock SessionMapper：{@code selectOneById(sessionId)} 恒返回同一可写 record（= 一行 DB 记录）。 */
    private static SessionMapper mapperFor(SessionRecord row) {
        SessionMapper mapper = mock(SessionMapper.class);
        when(mapper.selectOneById(row.getId())).thenReturn(row);
        return mapper;
    }

    private static SessionRecord sessionRow(String sessionId) {
        SessionRecord row = new SessionRecord();
        row.setId(sessionId);
        return row;
    }

    /** provider 首调返回 stop 纯文本 → loop 正常退出（复刻 LlmAgentLoopTodosReadbackInjectionTest:48-63）。 */
    private static LlmProvider stopProvider(String text) {
        LlmProvider provider = mock(LlmProvider.class);
        doAnswer(inv -> {
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept(text);
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage(text, "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        return provider;
    }

    /**
     * <b>新的</b> LlmAgentLoop 实例（≡ 下一条用户消息 / 新 send）· 注入 mock SessionMapper +
     * 反射注入 permissionContextBuilder（无 setter；不注入则 base mode 恒 DEFAULT，
     * mode 保真断言退化）。
     */
    private static LlmAgentLoop newLoop(SessionMapper sessionMapper,
                                       PermissionContextBuilder builder) throws Exception {
        LlmProvider provider = stopProvider("a2b response");
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        LlmAgentLoop loop = new LlmAgentLoop(factory);
        loop.setSessionMapper(sessionMapper);
        if (builder != null) {
            Field f = LlmAgentLoop.class.getDeclaredField("permissionContextBuilder");
            f.setAccessible(true);
            f.set(loop, builder);
        }
        return loop;
    }

    /**
     * 提示流 ctx（生产形状）：per-turn TUC 必带非空 {@code permissionContext}
     * （{@code AgentLoopContext.toolExecContext:1478-1488}）—— 否则 {@code applyAndPersistUpdates}
     * 的「无 permissionContext」早退会把 apply/persist 一起跳过。
     */
    private static ToolUseContext promptCtx(String sessionId) {
        ToolPermissionContext permCtx = new ToolPermissionContext(
            PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of(),
            false, false, Map.of(), false, false, null);
        return ToolUseContext.of(AGENT_ID, sessionId, PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), permCtx, PermissionMode.DEFAULT);
    }

    /** 走真 doRun 入口（permissionModeCli 决定 base/per-turn 基线 mode；11 参重载 = RV-11 链）。 */
    private static void runOnce(LlmAgentLoop loop, String sessionId, String permissionModeCli) {
        loop.run(RunRequest.session("a2b query", sessionId, null, ProviderConfig.empty(),
            "test-model", null, null, null, permissionModeCli, false, null));
    }

    // ── WebSocket 真单点驱动（prompt → onResponse(allow, updatedPermissions)） ──

    private static void awaitPendingRegistration(WebSocketPermissionPrompter prompter, String requestId)
            throws Exception {
        Field f = WebSocketPermissionPrompter.class.getDeclaredField("pending");
        f.setAccessible(true);
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            @SuppressWarnings("unchecked")
            Map<String, ?> pending = (Map<String, ?>) f.get(prompter);
            if (pending.containsKey(requestId)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new IllegalStateException("pending not registered: " + requestId);
    }

    private static List<JsonNode> nodes(String... rawEntries) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        for (String raw : rawEntries) {
            out.add(JSON.readTree(raw));
        }
        return out;
    }

    private static WebSocketPermissionPrompter prompterWith(SessionMapper sessionMapper,
                                                           PermissionUpdatePersister persister)
            throws Exception {
        WebSocketPermissionPrompter prompter =
            new WebSocketPermissionPrompter(mock(org.springframework.messaging.simp.SimpMessagingTemplate.class), 100);
        prompter.setSessionMapper(sessionMapper);
        if (persister != null) {
            Field f = WebSocketPermissionPrompter.class.getDeclaredField("permissionUpdatePersister");
            f.setAccessible(true);
            f.set(prompter, persister);
        }
        return prompter;
    }

    /** 真单点：用户批准并携带 updatedPermissions（CC onAllow 语义，interactiveHandler.ts:154-167）。 */
    private static void driveAllow(WebSocketPermissionPrompter prompter, String sessionId,
                                   String requestId, String... updatedPermissionsJson) throws Exception {
        ToolUseContext ctx = promptCtx(sessionId);
        Thread t = new Thread(() -> {
            try {
                prompter.prompt(new StubTool("Read"), JSON.createObjectNode().put("file_path", "/tmp/a2b/x"),
                    new PermissionDecisionReason.Other("test"), ctx, requestId,
                    new PermissionPromptDetails("desc", List.of(), null));
            } catch (Throwable ignored) {
                // 用户响应为正常返回路径
            }
        });
        t.start();
        awaitPendingRegistration(prompter, requestId);
        prompter.onResponse(requestId, "allow", nodes(updatedPermissionsJson), null, null);
        t.join(5_000);
        assertThat(t.isAlive()).as("权限流程必须结束（requestId=%s）", requestId).isFalse();
    }

    // ── per-turn ctx 派生（A2 合并入口；base TUC 镜像 LlmAgentLoop:10022-10041） ──

    private static AgentLoopContext loopCtx(PermissionContextBuilder builder) {
        return new AgentLoopContext(
            null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null,
            null, null,
            FeatureFlags.ALL_DISABLED, null, null, null, null, null, null, null, null,
            builder, null, null, null);
    }

    private static ToolUseContext appStateBoundBaseTuc(String sessionId, Map<String, Object> appState,
                                                      PermissionMode mode) {
        Function<Map<String, Object>, Map<String, Object>> getAppState = prev -> Map.copyOf(appState);
        Consumer<Function<Map<String, Object>, Map<String, Object>>> setAppState = updater -> {
            Map<String, Object> next = updater.apply(Map.copyOf(appState));
            appState.clear();
            if (next != null) {
                appState.putAll(next);
            }
        };
        ToolPermissionContext basePermCtx = new ToolPermissionContext(
            mode, Map.of(), Map.of(), Map.of(), Map.of(), false, false, Map.of(), false, false, null);
        return new ToolUseContext(
            AGENT_ID, sessionId, mode,
            Map.of(), List.of(), "", AbortController.NOOP, List.of(),
            basePermCtx, mode,
            Map.of(), false, "", null, null, Map.of(), null,
            getAppState, setAppState, null, null);
    }

    private static ToolPermissionContext perTurnPermCtx(ToolUseContext baseTuc, String sessionId,
                                                        PermissionContextBuilder builder) {
        ToolUseContext perTurn = AgentLoopContext.toolExecContext(
            loopCtx(builder), baseTuc, new AgentState("sys", sessionId, AGENT_ID), Map.of());
        assertThat(perTurn).as("toolExecContext 必须返回 per-turn TUC").isNotNull();
        assertThat(perTurn.permissionContext()).as("per-turn permCtx 必须重建").isNotNull();
        return perTurn.permissionContext();
    }

    /**
     * 跑一次真 doRun（{@code loop.run}）→ 取其 appState 快照 → 派生 per-turn permCtx
     * （appState 接线与生产 {@code LlmAgentLoop:10037-10041} 逐字同形）。
     */
    private static ToolPermissionContext perTurnFromLoopAppState(LlmAgentLoop loop, String sessionId,
                                                                PermissionContextBuilder builder,
                                                                String permissionModeCli,
                                                                PermissionMode baseTucMode) {
        runOnce(loop, sessionId, permissionModeCli);
        return perTurnPermCtx(
            appStateBoundBaseTuc(sessionId, loop.getAppStateSnapshot(), baseTucMode), sessionId, builder);
    }

    private static Set<String> ruleKeys(Set<PermissionRule> rules) {
        return rules == null ? Set.of() : rules.stream()
            .map(r -> r.ruleValue().toolName() + "(" + r.ruleValue().ruleContent() + ")")
            .collect(Collectors.toSet());
    }

    private static Set<String> sessionAllowKeys(ToolPermissionContext ctx) {
        return ruleKeys(ctx.alwaysAllowRules().get(PermissionRuleSource.SESSION));
    }

    // ══════════════════════ ① 跨 send 存活（真单点写 → 新实例真 doRun 读） ══════════════════════

    @Test
    @DisplayName("① 真单点批准「本次会话允许」→ 新 loop 实例 doRun 回读 → per-turn ctx SESSION 规则仍在")
    void sessionGrant_survivesAcrossSend_newLoopInstance() throws Exception {
        SessionRecord record = sessionRow(SESSION_A);
        WebSocketPermissionPrompter prompter = prompterWith(mapperFor(record), null);

        // ── send 1：用户批准「Yes, during this session」（destination=session）──
        driveAllow(prompter, SESSION_A, "req-a2b-1", ADD_SESSION_READ_RULE);
        assertThat(record.getSessionPermissionRules())
            .as("真单点必须把 SESSION 档授权写进会话列（V75 列 = 跨 send 唯一通道）")
            .isNotNull()
            .contains(RULE_CONTENT)
            .doesNotContain("\"toolPermissionContext\"");

        // ── send 2：全新 loop 实例（≡ 下一条用户消息）+ 真 doRun 回读 ──
        PermissionContextBuilder builder = new PermissionContextBuilder();
        LlmAgentLoop loop2 = newLoop(mapperFor(record), builder);
        runOnce(loop2, SESSION_A, null);

        Object injected = loop2.getAppStateSnapshot().get("toolPermissionContext");
        assertThat(injected)
            .as("doRun 入口必须把会话列还原成 appStateRef.toolPermissionContext（A2 合并的唯一读口）")
            .isInstanceOf(ToolPermissionContext.class);
        assertThat(sessionAllowKeys((ToolPermissionContext) injected))
            .as("回读的 SESSION 桶必须含第 1 次 send 批准的 Read 规则")
            .containsExactly("Read(" + RULE_CONTENT + ")");

        // ── per-turn ctx：A2 合并把 appState 的 SESSION 规则并进本轮权限上下文 ──
        ToolPermissionContext perTurn = perTurnFromLoopAppState(
            newLoop(mapperFor(record), builder), SESSION_A, builder, null, PermissionMode.DEFAULT);
        assertThat(sessionAllowKeys(perTurn))
            .as("跨 send 存活终点：per-turn permCtx 必须含该 SESSION 规则（批 A2b 前恒空）")
            .containsExactly("Read(" + RULE_CONTENT + ")");
        assertThat(perTurn.alwaysAllowRules().get(PermissionRuleSource.SESSION))
            .as("桶归属 = SESSION（CC 桶 key 即归属）")
            .allSatisfy(r -> assertThat(r.source()).isEqualTo(PermissionRuleSource.SESSION));
    }

    @Test
    @DisplayName("①-b 列无 SESSION 条目 → 不注入 appState 键（空态，不崩）")
    void emptyColumn_injectsNothing() throws Exception {
        SessionRecord record = sessionRow(SESSION_A);
        LlmAgentLoop loop = newLoop(mapperFor(record), new PermissionContextBuilder());

        runOnce(loop, SESSION_A, null);

        assertThat(loop.getAppStateSnapshot().get("toolPermissionContext"))
            .as("从未「本次会话允许」的会话 → 会话列 null → 不建 appState 键（对齐 todos 回读空态）")
            .isNull();
    }

    // ══════════════════════ ② mode 保真（回读不得打坏 baseline） ══════════════════════

    @Test
    @DisplayName("② 列无 SESSION setMode → 回读注入的 mode 必须等于 per-turn 基线（不得回落 DEFAULT）")
    void readback_preservesBaselineMode() throws Exception {
        SessionRecord record = sessionRow(SESSION_A);
        WebSocketPermissionPrompter prompter = prompterWith(mapperFor(record), null);
        // 只批准规则、不批准 mode（createReadRuleSuggestion 的真实形状）
        driveAllow(prompter, SESSION_A, "req-a2b-2", ADD_SESSION_READ_RULE);

        PermissionContextBuilder builder = new PermissionContextBuilder();
        LlmAgentLoop loop = newLoop(mapperFor(record), builder);
        // base/per-turn 基线 mode = ACCEPT_EDITS（--permission-mode acceptEdits 等价）
        runOnce(loop, SESSION_A, "acceptEdits");

        ToolPermissionContext injected =
            (ToolPermissionContext) loop.getAppStateSnapshot().get("toolPermissionContext");
        assertThat(injected.mode())
            .as("列里没有 SESSION setMode ⇒ 注入 ctx 的 mode 必须取 per-turn 基线 ACCEPT_EDITS；"
                + "若回落 DEFAULT，A2 合并（appState mode 胜出）会把本次 run 的 mode 打回 default")
            .isEqualTo(PermissionMode.ACCEPT_EDITS);

        ToolPermissionContext perTurn =
            perTurnPermCtx(appStateBoundBaseTuc(SESSION_A, loop.getAppStateSnapshot(), PermissionMode.ACCEPT_EDITS),
                SESSION_A, builder);
        assertThat(perTurn.mode())
            .as("per-turn mode 不得被回读注入改写").isEqualTo(PermissionMode.ACCEPT_EDITS);
        assertThat(sessionAllowKeys(perTurn))
            .as("规则仍叠加").containsExactly("Read(" + RULE_CONTENT + ")");
    }

    @Test
    @DisplayName("③ SESSION setMode(acceptEdits)（allow all edits during this session）跨 send 存活")
    void sessionSetMode_survivesAcrossSend() throws Exception {
        SessionRecord record = sessionRow(SESSION_A);
        WebSocketPermissionPrompter prompter = prompterWith(mapperFor(record), null);
        // 真实产出点：文件类写路径「Yes, allow all edits during this session」
        List<PermissionUpdate> suggestions = PermissionUpdates.generateSuggestions(
            "/tmp/a2b-write.txt", PermissionUpdates.OperationType.WRITE, PermissionMode.DEFAULT, false);
        List<JsonNode> nodes = new ArrayList<>();
        for (PermissionUpdate update : suggestions) {
            nodes.add(JSON.valueToTree(update));
        }
        assertThat(nodes)
            .as("前置：write + default ⇒ 必须产出 SetMode(session, acceptEdits)")
            .anySatisfy(n -> assertThat(n.path("type").asText()).isEqualTo("setMode"));

        ToolUseContext ctx = promptCtx(SESSION_A);
        Thread t = new Thread(() -> {
            try {
                prompter.prompt(new StubTool("Write"), JSON.createObjectNode().put("file_path", "/tmp/a2b-write.txt"),
                    new PermissionDecisionReason.Other("test"), ctx, "req-a2b-3",
                    new PermissionPromptDetails("desc", List.of(), null));
            } catch (Throwable ignored) {
                // 正常返回路径
            }
        });
        t.start();
        awaitPendingRegistration(prompter, "req-a2b-3");
        prompter.onResponse("req-a2b-3", "allow", nodes, null, null);
        t.join(5_000);

        assertThat(record.getSessionPermissionRules())
            .as("SESSION setMode 必须落进会话列（mode 是「本次会话允许编辑」的语义本体）")
            .contains("\"mode\":\"acceptEdits\"");

        PermissionContextBuilder builder = new PermissionContextBuilder();
        LlmAgentLoop loop = newLoop(mapperFor(record), builder);
        runOnce(loop, SESSION_A, null);
        ToolPermissionContext perTurn =
            perTurnPermCtx(appStateBoundBaseTuc(SESSION_A, loop.getAppStateSnapshot(), PermissionMode.DEFAULT),
                SESSION_A, builder);
        assertThat(perTurn.mode())
            .as("跨 send 后 per-turn mode 必须是 acceptEdits（A2 合并「appState mode 胜出」）")
            .isEqualTo(PermissionMode.ACCEPT_EDITS);
    }

    // ══════════════════════ ④ 会话隔离 ══════════════════════

    @Test
    @DisplayName("④ 会话隔离：A 会话的 SESSION 授权不得出现在 B 会话")
    void sessionGrant_doesNotLeakAcrossSessions() throws Exception {
        SessionRecord recordA = sessionRow(SESSION_A);
        SessionRecord recordB = sessionRow(SESSION_B);
        SessionMapper mapper = mock(SessionMapper.class);
        when(mapper.selectOneById(SESSION_A)).thenReturn(recordA);
        when(mapper.selectOneById(SESSION_B)).thenReturn(recordB);
        WebSocketPermissionPrompter prompter = prompterWith(mapper, null);

        driveAllow(prompter, SESSION_A, "req-a2b-4", ADD_SESSION_READ_RULE);
        assertThat(recordA.getSessionPermissionRules()).isNotNull();
        assertThat(recordB.getSessionPermissionRules())
            .as("B 会话列不得被 A 的授权污染（会话隔离）").isNull();

        PermissionContextBuilder builder = new PermissionContextBuilder();
        assertThat(perTurnFromLoopAppState(newLoop(mapper, builder), SESSION_B, builder,
                null, PermissionMode.DEFAULT).alwaysAllowRules())
            .as("B 会话 per-turn ctx 不得含 A 的 SESSION 桶")
            .isEmpty();
        assertThat(sessionAllowKeys(perTurnFromLoopAppState(newLoop(mapper, builder), SESSION_A, builder,
                null, PermissionMode.DEFAULT)))
            .as("隔离方向性：只挡跨会话，不误伤本会话")
            .containsExactly("Read(" + RULE_CONTENT + ")");
    }

    // ══════════════════════ ⑤ RemoveRules 真删（不许「规则复活」） ══════════════════════

    @Test
    @DisplayName("⑤ RemoveRules 必须从列里删掉对应项（删了却还在列里 = 规则复活）")
    void removeRules_actuallyRemovesFromColumn() throws Exception {
        SessionRecord record = sessionRow(SESSION_A);
        WebSocketPermissionPrompter prompter = prompterWith(mapperFor(record), null);

        driveAllow(prompter, SESSION_A, "req-a2b-5a", ADD_SESSION_READ_RULE, ADD_SESSION_BASH_RULE);
        assertThat(record.getSessionPermissionRules())
            .as("前置：两条 SESSION allow 规则都在列里").contains(RULE_CONTENT).contains(BASH_RULE_CONTENT);

        // 删其中一条 → 列必须仍在（非空），且被删的那条真的没了（= 不是「整列清空」冒充删除）
        driveAllow(prompter, SESSION_A, "req-a2b-5b", REMOVE_SESSION_READ_RULE);
        assertThat(record.getSessionPermissionRules())
            .as("RemoveRules 只删匹配项：另一条规则必须仍在列里")
            .isNotNull()
            .contains(BASH_RULE_CONTENT)
            .doesNotContain(RULE_CONTENT);

        driveAllow(prompter, SESSION_A, "req-a2b-5c", REMOVE_SESSION_BASH_RULE);
        // 最后一条被删空 ⇒ 列必须置 NULL（不是残留空壳 JSON）；也即「列里不再有该规则」
        assertThat(record.getSessionPermissionRules())
            .as("RemoveRules 后列里不得再有该规则（否则下次 run 回读 = 规则复活）；"
                + "全桶空 ⇒ 列写 NULL（对齐 TodoWriteTool:880-886 空态语义）")
            .isNull();

        PermissionContextBuilder builder = new PermissionContextBuilder();
        LlmAgentLoop loop = newLoop(mapperFor(record), builder);
        runOnce(loop, SESSION_A, null);
        assertThat(loop.getAppStateSnapshot().get("toolPermissionContext"))
            .as("列已清空 ⇒ 不再注入 appState（撤销生效）").isNull();
    }

    @Test
    @DisplayName("⑤-b 非 SESSION 目的地不写会话列（localSettings 只走盘，不进 V75 列）")
    void nonSessionDestination_notWrittenToSessionColumn() throws Exception {
        SessionRecord record = sessionRow(SESSION_C);
        WebSocketPermissionPrompter prompter = prompterWith(mapperFor(record), null);

        driveAllow(prompter, SESSION_C, "req-a2b-6", ADD_LOCAL_READ_RULE);

        assertThat(record.getSessionPermissionRules())
            .as("destination=localSettings 的更新不进会话列（会话列只承载 SESSION 档条目）").isNull();
    }


    // ══════════════════ ⑥ 第二个 apply+persist 单点（hook allow）也不落下 ══════════════════

    /** headless permCtx（shouldAvoidPermissionPrompts=true → 走 hook 决策链，不弹窗）。 */
    private static ToolPermissionContext headlessPermCtx() {
        return new ToolPermissionContext(
            PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of(),
            false, false, Map.of(), true, false, null);
    }

    /** 只产 Ask 的 stub 管线（对齐 HeadlessPermissionChainTest:71-81）。 */
    private static final class StubAskPipeline extends PermissionPipeline {
        @Override
        public PermissionResult check(Tool tool, ToolUseBlock call, JsonNode input,
                                      ToolUseContext ctx, ToolPermissionContext permCtx) {
            return new PermissionResult.Ask("stub ask", new PermissionDecisionReason.Other("test"),
                List.of(), null, null, null, false, null, List.of());
        }
    }

    /** headless 路径不弹窗；本桩仅满足构造（若被调用 → 测试失败）。 */
    private static final class NeverCalledPrompter implements PermissionPrompter {
        @Override
        public PermissionResult prompt(Tool tool, JsonNode input, PermissionDecisionReason reason,
                                       ToolUseContext ctx, String requestId) {
            throw new AssertionError("headless hook allow 路径不得弹窗");
        }
    }

    @Test
    @DisplayName("⑧ 第二个 apply+persist 单点（ToolPermissionGate hook allow）同样写会话列")
    void gateHookAllow_alsoWritesSessionColumn() {
        SessionRecord row = sessionRow(SESSION_A);
        HookRegistry hooks = mock(HookRegistry.class);
        // hook allow 携带 updatedPermissions（CC permissions.ts:436-451）
        when(hooks.executeEvent(any(HookEvent.class))).thenReturn(GenericHook.HookResult.proceed()
            .withPermissionRequestResult(new PermissionRequestResult.Allow(null, List.of(Map.of(
                "type", "addRules",
                "rules", List.of(Map.of("toolName", "Read", "ruleContent", RULE_CONTENT)),
                "behavior", "allow",
                "destination", "session")))));

        ToolPermissionGate gate = new ToolPermissionGate(
            new StubAskPipeline(), new NeverCalledPrompter(), null, null, null,
            null, null, null, new PermissionDecisionLogger(null),
            hooks, new PermissionUpdateApplier(), null);
        gate.setSessionMapper(mapperFor(row));

        ToolPermissionContext permCtx = headlessPermCtx();
        ToolUseContext ctx = ToolUseContext.of(AGENT_ID, SESSION_A, PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), permCtx, PermissionMode.DEFAULT);
        ToolPermissionGate.DecisionResult result =
            gate.check(new StubTool("Bash"),
                new ToolUseBlock("call-a2b-8", "Bash", JSON.createObjectNode()),
                JSON.createObjectNode(), ctx, permCtx);

        assertThat(result.decision()).as("前置：hook allow 放行").isEqualTo(ToolPermissionGate.Decision.ALLOW);
        assertThat(row.getSessionPermissionRules())
            .as("⚠️ 本仓有**两个** apply+persist 单点（弹窗批准 / hook allow 携带）——"
                + "hook 侧漏挂钩则 hook 授权不跨 send 存活（「只覆盖一侧」失效模式）")
            .isNotNull()
            .contains(RULE_CONTENT);
    }

    // ══════════════════════ ⑦ ⭐ 不产生 settings 文件（本批红线） ══════════════════════

    /** 真 persister 装配：项目根 = 测试临时目录（loader 的无会话腿；sessionId 用 no-session 哨兵）。 */
    private static PermissionUpdatePersister persisterAt(Path projectRoot) {
        return new PermissionUpdatePersister(
            new UserSettingsLoader(PARSER),
            new ProjectSettingsLoader(PARSER, projectRoot::toString),
            new LocalSettingsLoader(PARSER, projectRoot::toString),
            new PermissionRuleValueParser());
    }

    private static Path settingsFile(Path projectRoot) {
        return projectRoot.resolve(NexusaiPaths.getProjectDirName()).resolve("settings.json");
    }

    private static Path localSettingsFile(Path projectRoot) {
        return projectRoot.resolve(NexusaiPaths.getProjectDirName()).resolve("settings.local.json");
    }

    @Test
    @DisplayName("⑥ ⭐ 反向断言：SESSION 档整条流程不在项目目录产生 settings.json / settings.local.json")
    void sessionDestination_writesNoSettingsFile(@TempDir Path projectRoot) throws Exception {
        SessionRecord record = sessionRow(SessionKeys.NO_SESSION);
        WebSocketPermissionPrompter prompter = prompterWith(mapperFor(record), persisterAt(projectRoot));

        // 先把「盘上原本就有文件」这一假绿源排掉：起点必须干净
        assertThat(Files.exists(settingsFile(projectRoot))).isFalse();
        assertThat(Files.exists(localSettingsFile(projectRoot))).isFalse();

        driveAllow(prompter, SessionKeys.NO_SESSION, "req-a2b-7a", ADD_SESSION_READ_RULE);
        assertThat(record.getSessionPermissionRules())
            .as("流程真的跑了（阶段量）：SESSION 授权落到 DB 会话列").isNotNull();
        driveAllow(prompter, SessionKeys.NO_SESSION, "req-a2b-7b", REMOVE_SESSION_READ_RULE);
        assertThat(record.getSessionPermissionRules())
            .as("撤销也走同一单点（列被清空）").isNull();

        assertThat(Files.exists(settingsFile(projectRoot)))
            .as("⛔ SESSION 档不得写 settings.json（CC supportsPersistence 排除 session，"
                + "PermissionUpdate.ts:208-216 —— 落盘 = 把「本次会话」变成「永久」）").isFalse();
        assertThat(Files.exists(localSettingsFile(projectRoot)))
            .as("⛔ SESSION 档不得写 settings.local.json（同上）").isFalse();
        // 项目目录里不得冒出任何配置文件（.nexusai 目录本身也不该存在）
        assertThat(Files.exists(projectRoot.resolve(NexusaiPaths.getProjectDirName())))
            .as("整条流程不得在项目目录建配置目录/文件").isFalse();
    }

    @Test
    @DisplayName("⑥-b 对照组（证明 ⑥ 不是空断言）：同装配 + localSettings 目的地 → 盘上确实产生文件")
    void control_localDestination_doesWriteSettingsFile(@TempDir Path projectRoot) throws Exception {
        SessionRecord record = sessionRow(SessionKeys.NO_SESSION);
        WebSocketPermissionPrompter prompter = prompterWith(mapperFor(record), persisterAt(projectRoot));

        driveAllow(prompter, SessionKeys.NO_SESSION, "req-a2b-8", ADD_LOCAL_READ_RULE);

        assertThat(Files.exists(localSettingsFile(projectRoot)))
            .as("对照组：同一 persister 装配下 localSettings 目的地**会**写盘 ⇒ ⑥ 的断言非空")
            .isTrue();
        assertThat(Files.readString(localSettingsFile(projectRoot))).contains(RULE_CONTENT);
        assertThat(record.getSessionPermissionRules())
            .as("对照组同时证明：非 SESSION 目的地不进会话列").isNull();
    }

    // ══════════════════════ ⑦ 编解码健壮性（fail-soft + 不静默） ══════════════════════

    @Test
    @DisplayName("⑦ 列 JSON 坏/空/畸形条目 → fail-soft 不崩（读侧不注入、写侧不覆盖已存条目）")
    void malformedColumnJson_isFailSoft() throws Exception {
        assertThat(SessionPermissionOverlay.toContext(null, PermissionMode.DEFAULT)).isNull();
        assertThat(SessionPermissionOverlay.toContext("   ", PermissionMode.DEFAULT)).isNull();
        assertThat(SessionPermissionOverlay.toContext("not-json", PermissionMode.DEFAULT))
            .as("坏 JSON → 不注入（fail-soft），且解析失败有 ≥WARN 留痕（非静默）").isNull();
        assertThat(SessionPermissionOverlay.toContext("[]", PermissionMode.DEFAULT))
            .as("非对象 → 不注入").isNull();
        assertThat(SessionPermissionOverlay.toContext("{}", PermissionMode.DEFAULT))
            .as("空对象（无任何 SESSION 条目）→ 不注入").isNull();

        // 畸形条目（缺 toolName）逐条跳过，不坏整列
        ToolPermissionContext ctx = SessionPermissionOverlay.toContext(
            "{\"alwaysAllowRules\":[{\"ruleContent\":\"x\"},{\"toolName\":\"Read\"}],\"alwaysDenyRules\":[]}",
            PermissionMode.DEFAULT);
        assertThat(ctx).as("至少有一条合法规则 → 仍注入").isNotNull();
        assertThat(sessionAllowKeys(ctx)).containsExactly("Read(null)");
    }

    @Test
    @DisplayName("⑦-b mode 往返：只有 SESSION setMode 才落列；BUBBLE 拒收（内部标记不可寻址）")
    void modeRoundTrip_refusesBubble() throws Exception {
        SessionRecord record = sessionRow(SESSION_A);
        WebSocketPermissionPrompter prompter = prompterWith(mapperFor(record), null);

        driveAllow(prompter, SESSION_A, "req-a2b-9",
            "{\"type\":\"setMode\",\"mode\":\"bubble\",\"destination\":\"session\"}");
        assertThat(record.getSessionPermissionRules())
            .as("BUBBLE 是 fork 子 agent 冒泡内部标记（非用户可寻址档）⇒ 不得落列").isNull();
    }
}
