package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.source.PermissionRuleValueParser;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.common.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [fix-ts04 IMPL-01] buildJsonInput 六字段对齐（RED→GREEN）· 对齐 CC
 * {@code Open-ClaudeCode/src/utils/hooks.ts:301-328 createBaseHookInput} 恒备字段语义
 * （OD-TS04-01 方案 B：分发层注入；OD-TS04-02：agent_type = ctx.agentType）。
 *
 * <p>验收断言面（REQ-01..06）：
 * <ol>
 *   <li>PreToolUse 事件（subagent ctx，经 executeEvent 3 参）→ jsonInput 含
 *       transcript_path（{workspaceDir}/{sessionId}.jsonl）/ cwd / agent_type / permission_mode</li>
 *   <li>主线程 ctx（agentType=null）→ jsonInput 无 agent_type key（省略语义，对齐 CC 无 --agent）</li>
 *   <li>sessionId=null 事件 + RequestContext（MDC）有值 → session_id 回退</li>
 *   <li>Stop 事件 data 已有 agent_type → 不双写（单值 REQ-06）</li>
 *   <li>permissionRequest 7 参顶层 permission_mode 已有 → 不覆盖</li>
 *   <li>SessionStart（无 ctx）→ transcript_path/cwd 仍恒备（REQ-01）</li>
 *   <li>PermissionDenied 工具事件（subagent ctx）→ agent_type/permission_mode 注入</li>
 * </ol>
 *
 * <p>不依赖 Spring 容器：HooksSettings/HooksConfigSnapshot/HookMatcherEngine + StubCommandExecutor
 * （无真实进程），复用 ConfiguredPreToolUseHookDecisionTest 的 stub 模式。
 */
@DisplayName("[fix-ts04 IMPL-01] buildJsonInput 六字段对齐（transcript_path/cwd/agent_type/permission_mode/session_id）")
class FixTs04BuildJsonInputAlignTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 覆写 execute 的 stub：不启动真实进程，捕获 jsonInput + event。 */
    static class StubCommandExecutor extends CommandHookExecutor {
        final AtomicReference<String> capturedJsonInput = new AtomicReference<>();
        final AtomicReference<HookEvent> capturedEvent = new AtomicReference<>();
        private final Function<String, CommandHookExecutor.CommandHookResult> responder;

        StubCommandExecutor(Function<String, CommandHookExecutor.CommandHookResult> responder) {
            this.responder = responder;
        }

        @Override
        public CommandHookExecutor.CommandHookResult execute(CommandHook hook, HookEvent hookEvent, String hookName,
                                                             String jsonInput, String pluginRoot, String pluginId,
                                                             String skillRoot, Integer hookIndex,
                                                             boolean forceSyncExecution, AbortController parentAbort) {
            capturedJsonInput.set(jsonInput);
            capturedEvent.set(hookEvent);
            return responder.apply(jsonInput);
        }

        @Override
        public CommandHookExecutor.CommandHookResult execute(CommandHook hook, HookEvent hookEvent, String hookName,
                                                             String jsonInput, String pluginRoot, String pluginId,
                                                             String skillRoot, Integer hookIndex,
                                                             boolean forceSyncExecution, AbortController parentAbort,
                                                             long defaultTimeoutMs, String hookCwd) {
            // [IMP-HOOKS-S9 DEL-01d] 12 参重载委托 10 参 — prompt 回调 参数已删除
            return execute(hook, hookEvent, hookName, jsonInput, pluginRoot, pluginId, skillRoot,
                hookIndex, forceSyncExecution, parentAbort);
        }
    }

    private static CommandHookExecutor.CommandHookResult exit0EmptyJson(String jsonInput) {
        return new CommandHookExecutor.CommandHookResult("{}", "", jsonInput, 0, false, false);
    }

    /** settings 配 1 条 PreToolUse:Bash command hook → registry（含 stub executor）。 */
    private HookRegistry registryWithConfiguredHook(StubCommandExecutor stub, HookEventType type) {
        return registryWithConfiguredHook(stub, type, "Bash");
    }

    /** settings 配 1 条 command hook（matcher 可空 → 命中任意事件）→ registry（含 stub executor）。
     *  <p>matcher 语义（CC getMatchingHooks hooks.ts:1615-1670）：工具事件 matchQuery=toolName，
     *  会话事件（SESSION_START）matchQuery=source。测试 6 用 null matcher 让 SESSION_START hook
     *  命中（CC matchQuery==null 时全部通过，hooks.ts:1684）——该测试只验证基础字段注入，非匹配语义。 */
    private HookRegistry registryWithConfiguredHook(StubCommandExecutor stub, HookEventType type, String matcher) {
        HooksSettings settings = new HooksSettings(key -> null);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(type,
                new CommandHook("echo stub", null, null, null, null, null, null, null),
                matcher, HookSource.USER_SETTINGS, null)
        ));
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        snapshot.captureHooksConfigSnapshot();
        HookMatcherEngine engine = new HookMatcherEngine(snapshot, new PermissionRuleValueParser());
        HookRegistry registry = new HookRegistry();
        registry.setHooksConfigSnapshot(snapshot);
        registry.setHookMatcherEngine(engine);
        registry.setCommandHookExecutor(stub);
        return registry;
    }

    /** subagent 工具 ctx：agentType 非 null（createSubagentContext 等价）+ effectiveCwd + DEFAULT 权限模式。 */
    private static ToolUseContext subagentCtx(String agentType, String agentId, String sessionId, Path effectiveCwd) {
        ToolUseContext base = ToolUseContext.of(
            UUID.fromString(agentId), sessionId, PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), null, PermissionMode.DEFAULT,
            Map.of(), false, "", effectiveCwd);
        return base.with(new ToolUseContext.SubagentContextOverrides(
            UUID.fromString(agentId), agentType, null, null, null,
            null, null, null, null, null, null, null, null, null));
    }

    /** 主线程 ctx：agentType=null、effectiveCwd=null（LlmAgentLoop base TUC 等价）。 */
    private static ToolUseContext mainThreadCtx(String agentId, String sessionId) {
        return ToolUseContext.of(
            UUID.fromString(agentId), sessionId, PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), null, PermissionMode.DEFAULT,
            Map.of(), false, "", null);
    }

    private static JsonNode readJson(String jsonInput) throws Exception {
        return JSON.readTree(jsonInput);
    }

    @AfterEach
    void tearDown() {
        AutoMemPaths.resetCurrentProjectRoot();
        RequestContext.clear();
    }

    @Test
    @DisplayName("1. PreToolUse + subagent ctx → jsonInput 含 transcript_path/cwd/agent_type/permission_mode/session_id")
    void preToolUse_subagentCtx_jsonInputAllSixFields() throws Exception {
        AutoMemPaths.setCurrentProjectRoot("C:/proj");
        StubCommandExecutor stub = new StubCommandExecutor(FixTs04BuildJsonInputAlignTest::exit0EmptyJson);
        HookRegistry registry = registryWithConfiguredHook(stub, HookEventType.PRE_TOOL_USE);

        String agentId = UUID.randomUUID().toString();
        String sessionId = UUID.randomUUID().toString();
        ToolUseContext ctx = subagentCtx("explore-agent", agentId, sessionId, Path.of("C:/wt"));

        registry.executePreToolUse("Bash", JSON.createObjectNode().put("k", "v"), ctx, "tu-1");

        JsonNode node = readJson(stub.capturedJsonInput.get());
        assertThat(node.has("transcript_path"))
            .as("transcript_path 必须恒备（REQ-02）：{workspaceDir}/{sessionId}.jsonl")
            .isTrue();
        assertThat(node.get("transcript_path").asText())
            .isEqualTo(Path.of("C:/proj").resolve(sessionId + ".jsonl").toString());
        assertThat(node.has("cwd"))
            .as("cwd 必须恒备且取 ctx.effectiveCwd（REQ-03，子代理 worktree 路径）")
            .isTrue();
        assertThat(node.get("cwd").asText()).isEqualTo(Path.of("C:/wt").toString());
        assertThat(node.has("agent_type"))
            .as("工具事件 agent_type = ctx.agentType()（OD-TS04-02 subagent 注入）")
            .isTrue();
        assertThat(node.get("agent_type").asText()).isEqualTo("explore-agent");
        assertThat(node.has("permission_mode"))
            .as("工具事件 permission_mode = modeToCcString(ctx.permissionMode())（REQ-05）")
            .isTrue();
        assertThat(node.get("permission_mode").asText()).isEqualTo("default");
        assertThat(node.get("session_id").asText()).isEqualTo(sessionId);
        assertThat(node.get("agent_id").asText()).isEqualTo(agentId);
    }

    @Test
    @DisplayName("2. PreToolUse + 主线程 ctx（agentType=null）→ jsonInput 无 agent_type，cwd = CwdResolution.boundProject（[G14] 收敛）")
    void preToolUse_mainThreadCtx_omitsAgentType_hasCwd() throws Exception {
        // WHY [G14]: 原 hook payload cwd 三级链 effectiveCwd 非 user.dir ?? AutoMemPaths.identity；
        //   收敛后 event.cwd ?? parentTuc.effectiveCwd ?? CwdResolution.getCwd(sessionId)。主线程 ctx
        //   effectiveCwd=null → compact ctor 经 CwdResolution.getCwd(sessionId) 解析 → boundProject
        //   （SessionProjectRoot.getForSession，work 域）。绑定 sessionId→"C:/proj" 验证 hook cwd 经
        //   CwdResolution.boundProject 流转（非 AutoMemPaths identity —— CC getCwd 不读 identity projectRoot）。
        AutoMemPaths.setCurrentProjectRoot("C:/proj");
        StubCommandExecutor stub = new StubCommandExecutor(FixTs04BuildJsonInputAlignTest::exit0EmptyJson);
        HookRegistry registry = registryWithConfiguredHook(stub, HookEventType.PRE_TOOL_USE);

        String sessionId = UUID.randomUUID().toString();
        com.nexusai.common.SessionProjectRoot.clearSession(sessionId);
        com.nexusai.common.SessionProjectRoot.setForSession(sessionId, "C:/proj");
        try {
            ToolUseContext ctx = mainThreadCtx(UUID.randomUUID().toString(), sessionId);
            registry.executePreToolUse("Bash", JSON.createObjectNode(), ctx, "tu-1");

            JsonNode node = readJson(stub.capturedJsonInput.get());
            assertThat(node.has("agent_type"))
                .as("主线程 ctx.agentType()=null → agent_type key 省略（对齐 CC 无 --agent，OD-TS04-02）")
                .isFalse();
            assertThat(node.get("cwd").asText())
                .as("主线程无显式 effectiveCwd → cwd = CwdResolution.getCwd(sessionId) = boundProject（[G14] 收敛，非 AutoMemPaths.identity）")
                .isEqualTo(Path.of("C:/proj").toString());
            assertThat(node.get("transcript_path").asText())
                .isEqualTo(Path.of("C:/proj").resolve(sessionId + ".jsonl").toString());
        } finally {
            com.nexusai.common.SessionProjectRoot.clearSession(sessionId);
        }
    }

    @Test
    @DisplayName("3. 事件 sessionId=null + RequestContext(MDC) 有值 → session_id 回退")
    void sessionIdNullEvent_fallsBackToRequestContextMdc() throws Exception {
        AutoMemPaths.setCurrentProjectRoot("C:/proj");
        StubCommandExecutor stub = new StubCommandExecutor(FixTs04BuildJsonInputAlignTest::exit0EmptyJson);
        HookRegistry registry = registryWithConfiguredHook(stub, HookEventType.PRE_TOOL_USE);

        RequestContext.setSession("mdc-sess-1");
        String agentId = UUID.randomUUID().toString();
        // toolPre 5 参 sessionId=null（对齐 ElicitationHandler 等无会话调用点）
        HookEvent event = HookEvent.toolPre("Bash", JSON.createObjectNode(), null, agentId, "tu-1");
        ToolUseContext ctx = subagentCtx("explore-agent", agentId, "00000000-0000-0000-0000-000000000001",
            Path.of("C:/wt"));

        registry.executeEvent(event, null, ctx);

        JsonNode node = readJson(stub.capturedJsonInput.get());
        assertThat(node.has("session_id"))
            .as("事件 sessionId=null → RequestContext.sessionId()（MDC）回退（REQ-01）")
            .isTrue();
        assertThat(node.get("session_id").asText()).isEqualTo("mdc-sess-1");
        assertThat(node.get("transcript_path").asText())
            .as("transcript_path 用回退后 session_id 计算")
            .isEqualTo(Path.of("C:/proj").resolve("mdc-sess-1.jsonl").toString());
    }

    @Test
    @DisplayName("4. Stop 事件 data 已有 agent_type → 不双写（单值 REQ-06）")
    void stopEvent_dataAgentType_singleValue() throws Exception {
        AutoMemPaths.setCurrentProjectRoot("C:/proj");
        StubCommandExecutor stub = new StubCommandExecutor(FixTs04BuildJsonInputAlignTest::exit0EmptyJson);
        HookRegistry registry = registryWithConfiguredHook(stub, HookEventType.STOP);

        // [IMP-CF-01] data 改类型化 record：STOP 载荷 {stop_hook_active, agent_type}
        HookEvent event = new HookEvent(HookEventType.STOP, "s1", null, null, null, "a1",
            null, null, null, null, null, null, new HookEventData.Stop(false, null, "subagent-1"), 0);
        ToolUseContext ctx = subagentCtx("other-agent", "11111111-1111-1111-1111-111111111111",
            "22222222-2222-2222-2222-222222222222", Path.of("C:/wt"));

        registry.executeEvent(event, null, ctx);

        JsonNode node = readJson(stub.capturedJsonInput.get());
        assertThat(node.get("agent_type").asText())
            .as("事件特有覆盖（Stop data agent_type）优先于 ctx 派生，且不双写")
            .isEqualTo("subagent-1");
        String raw = stub.capturedJsonInput.get();
        assertThat(raw.split("\"agent_type\"", -1).length - 1)
            .as("agent_type key 只能出现一次（单值 REQ-06）")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("5. permissionRequest 7 参顶层 permission_mode 已有 → 不覆盖（ctx DEFAULT 不覆盖 acceptEdits）")
    void permissionRequest_topLevelPermissionMode_notOverridden() throws Exception {
        AutoMemPaths.setCurrentProjectRoot("C:/proj");
        StubCommandExecutor stub = new StubCommandExecutor(FixTs04BuildJsonInputAlignTest::exit0EmptyJson);
        HookRegistry registry = registryWithConfiguredHook(stub, HookEventType.PERMISSION_REQUEST);

        HookEvent event = HookEvent.permissionRequest("Bash", JSON.createObjectNode(), null,
            "acceptEdits", "tu-1", "s1", "a1");
        ToolUseContext ctx = subagentCtx("explore-agent", "11111111-1111-1111-1111-111111111111",
            "22222222-2222-2222-2222-222222222222", Path.of("C:/wt"));

        registry.executeEvent(event, null, ctx);

        JsonNode node = readJson(stub.capturedJsonInput.get());
        assertThat(node.get("permission_mode").asText())
            .as("事件顶层 permission_mode（工厂注入）优先于 ctx 派生（合并优先级单值）")
            .isEqualTo("acceptEdits");
        String raw = stub.capturedJsonInput.get();
        assertThat(raw.split("\"permission_mode\"", -1).length - 1)
            .as("permission_mode key 只能出现一次")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("6. SessionStart（无 ctx）→ transcript_path/cwd 仍恒备（[G14] cwd = CwdResolution.getCwd(sessionId)）")
    void sessionStartEvent_noCtx_stillHasBaseFields() throws Exception {
        // WHY [G14]: 无 ctx 事件 parentTuc=null → L3 CwdResolution.getCwd(sessionId)。绑定 sessionId→
        //   "C:/proj" 验证无 ctx 场景 hook cwd 经 CwdResolution.boundProject 恒备（REQ-01 三恒备字段），
        //   非 AutoMemPaths.identity（CC createBaseHookInput cwd=getCwd()）。
        AutoMemPaths.setCurrentProjectRoot("C:/proj");
        StubCommandExecutor stub = new StubCommandExecutor(FixTs04BuildJsonInputAlignTest::exit0EmptyJson);
        HookRegistry registry = registryWithConfiguredHook(stub, HookEventType.SESSION_START, null);

        com.nexusai.common.SessionProjectRoot.clearSession("s1");
        com.nexusai.common.SessionProjectRoot.setForSession("s1", "C:/proj");
        try {
            HookEvent event = HookEvent.sessionStart("s1", "a1", "startup", null, null);
            registry.executeEvent(event, null, null);

            JsonNode node = readJson(stub.capturedJsonInput.get());
            assertThat(node.has("transcript_path"))
                .as("无 ctx 事件 transcript_path 恒备（workspaceDir 经静态源）")
                .isTrue();
            assertThat(node.get("transcript_path").asText())
                .isEqualTo(Path.of("C:/proj").resolve("s1.jsonl").toString());
            assertThat(node.get("cwd").asText())
                .as("无 ctx 事件 cwd 恒备 = CwdResolution.getCwd(sessionId) = boundProject（[G14] 收敛，非 AutoMemPaths.identity）")
                .isEqualTo("C:/proj");
        } finally {
            com.nexusai.common.SessionProjectRoot.clearSession("s1");
        }
    }

    @Test
    @DisplayName("7. PermissionDenied 工具事件 + subagent ctx → agent_type/permission_mode 注入")
    void permissionDeniedToolEvent_injectsCtxFields() throws Exception {
        AutoMemPaths.setCurrentProjectRoot("C:/proj");
        StubCommandExecutor stub = new StubCommandExecutor(FixTs04BuildJsonInputAlignTest::exit0EmptyJson);
        HookRegistry registry = registryWithConfiguredHook(stub, HookEventType.PERMISSION_DENIED);

        HookEvent event = HookEvent.permissionDenied("Bash", JSON.createObjectNode(), "denied",
            "tu-1", "s1", "a1");
        ToolUseContext ctx = subagentCtx("explore-agent", "11111111-1111-1111-1111-111111111111",
            "22222222-2222-2222-2222-222222222222", Path.of("C:/wt"));

        registry.executeEvent(event, null, ctx);

        JsonNode node = readJson(stub.capturedJsonInput.get());
        assertThat(node.get("agent_type").asText())
            .as("PermissionDenied 工具事件 agent_type = ctx.agentType()")
            .isEqualTo("explore-agent");
        assertThat(node.get("permission_mode").asText())
            .as("PermissionDenied 工具事件 permission_mode = modeToCcString(ctx.permissionMode())")
            .isEqualTo("default");
        assertThat(node.get("transcript_path").asText())
            .isEqualTo(Path.of("C:/proj").resolve("s1.jsonl").toString());
    }
}
