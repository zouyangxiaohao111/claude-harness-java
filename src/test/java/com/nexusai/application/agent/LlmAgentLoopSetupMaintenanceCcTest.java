package com.nexusai.application.agent;

import com.nexusai.application.agent.permission.hook.CommandHook;
import com.nexusai.application.agent.permission.hook.CommandHookExecutor;
import com.nexusai.application.agent.permission.hook.HookEvent;
import com.nexusai.application.agent.permission.hook.HookEventType;
import com.nexusai.application.agent.permission.hook.HookMatcherEngine;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.permission.hook.HookSource;
import com.nexusai.application.agent.permission.hook.HooksConfigSnapshot;
import com.nexusai.application.agent.permission.hook.HooksSettings;
import com.nexusai.application.agent.permission.hook.IndividualHookConfig;
import com.nexusai.application.agent.permission.hook.MatchedHook;
import com.nexusai.application.agent.permission.source.PermissionRuleValueParser;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [IMP-LC-03 · OPD-WF4-LC-04] Setup maintenance 触发点 · 对齐 CC
 * {@code executeSetupHooks(trigger: 'init' | 'maintenance')}（utils/hooks.ts:3902-3922 +
 * main.tsx:2571 {@code setupTrigger = initOnly || init ? 'init' : maintenance ? 'maintenance' : null}）。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>: CC Setup hook trigger 为 union
 * 'init'|'maintenance'（CLI {@code --maintenance} flag，main.tsx:1131/2571）。探查发现 Java
 * 旧实现仅 LlmAgentLoop 会话启动硬编码 trigger='init'（✗-3，EV-WF4-LC-043）——配置
 * matcher='maintenance' 的 Setup hook 永不触发。本测试锁定修复后行为：
 * <ol>
 *   <li>会话启动仍发射 Setup('init')（既有行为保留，经 HookRegistry.executeSetupHooks 单一发射路径）</li>
 *   <li>{@code LlmAgentLoop.fireSetupMaintenanceHooks} 发射 Setup(trigger='maintenance')，
 *       配置 matcher='maintenance' 的 Setup hook 真实触发（HookMatcherEngine SETUP →
 *       data.trigger 匹配，HookMatcherEngine.java:331）</li>
 *   <li>maintenance 触发不误触 matcher='init' 的 Setup hook（matchQuery=trigger 语义）</li>
 * </ol>
 *
 * <p><b>RED 条件</b>: 修复前 LlmAgentLoop 无 {@code fireSetupMaintenanceHooks} 方法（maintenance
 * 无发射点），本测试对 maintenance 的断言全部失败（方法不存在 / matcher='maintenance' hook 不触发）。
 */
@DisplayName("[IMP-LC-03] Setup maintenance 触发点（对齐 CC executeSetupHooks trigger union）")
class LlmAgentLoopSetupMaintenanceCcTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── 基建 1：捕获事件的 StubMatcherEngine + HookRegistry ──

    /**
     * 捕获事件的 HookRegistry：HookMatcherEngine 匿名子类 override getMatchingHooks
     * 记录事件并返回空（只捕获，不执行命令 hook）。null 构造参数仅为过构造器；
     * override 方法不触 hooksConfigSnapshot/permissionRuleValueParser。
     */
    private static HookRegistry capturingRegistry(List<HookEvent> captured) {
        HookRegistry registry = new HookRegistry();
        registry.setHookMatcherEngine(
            new HookMatcherEngine(null, null) {
                @Override
                public List<MatchedHook> getMatchingHooks(HookEvent event) {
                    captured.add(event);
                    return java.util.List.of();
                }
            });
        return registry;
    }

    // ── 基建 2：真实 HookRegistry（settings 配 Setup command hook matcher=maintenance/init + stub executor）──

    /** 覆写 execute 的 stub：不启动真实进程，按预设 stdout/status 返回。 */
    static class StubCommandExecutor extends CommandHookExecutor {
        final AtomicReference<String> capturedJsonInput = new AtomicReference<>();
        private final Function<String, CommandHookResult> responder;

        StubCommandExecutor(Function<String, CommandHookResult> responder) {
            this.responder = responder;
        }

        @Override
        public CommandHookExecutor.CommandHookResult execute(CommandHook hook, HookEvent hookEvent, String hookName,
                                         String jsonInput, String pluginRoot, String pluginId,
                                         String skillRoot, Integer hookIndex,
                                         boolean forceSyncExecution, AbortController parentAbort) {
            capturedJsonInput.set(jsonInput);
            return responder.apply(jsonInput);
        }

        @Override
        public CommandHookExecutor.CommandHookResult execute(CommandHook hook, HookEvent hookEvent, String hookName,
                                         String jsonInput, String pluginRoot, String pluginId,
                                         String skillRoot, Integer hookIndex,
                                         boolean forceSyncExecution, AbortController parentAbort,
                                         long defaultTimeoutMs, String hookCwd) {
            return execute(hook, hookEvent, hookName, jsonInput, pluginRoot, pluginId, skillRoot,
                hookIndex, forceSyncExecution, parentAbort);
        }
    }

    private static CommandHookExecutor.CommandHookResult exit0Json(String stdout) {
        return new CommandHookExecutor.CommandHookResult(stdout, "", stdout, 0, false, false);
    }

    /** settings 配 2 条 Setup command hook（matcher='maintenance' + matcher='init'）+ stub executor. */
    private HookRegistry registryWithConfiguredSetupHooks(StubCommandExecutor stub) {
        HooksSettings settings = new HooksSettings(key -> null);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.SETUP,
                new CommandHook("echo maintenance", null, null, null, null, null, null, null),
                "maintenance", HookSource.USER_SETTINGS, null),
            new IndividualHookConfig(HookEventType.SETUP,
                new CommandHook("echo init", null, null, null, null, null, null, null),
                "init", HookSource.USER_SETTINGS, null)
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

    // ── 基建 3：LlmAgentLoop 注入 hookRegistry ──

    private static void setHookRegistry(LlmAgentLoop loop, HookRegistry registry) throws Exception {
        Field f = LlmAgentLoop.class.getDeclaredField("hookRegistry");
        f.setAccessible(true);
        f.set(loop, registry);
    }

    /** mocked provider: 首调返回纯文本 stop → loop 正常退出（对齐 LlmAgentLoopHookMessageInjectionTest）。 */
    private static LlmProviderFactory captureFactory() {
        LlmProvider provider = mock(LlmProvider.class);
        doAnswer(inv -> {
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("ok");
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage("ok", "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        return factory;
    }

    // ── 1. 会话启动仍发射 Setup('init')（既有行为保留，经通用发射点）──

    @Test
    @DisplayName("会话启动: LlmAgentLoop 仍发射 Setup(trigger='init')（既有行为保留，经 executeSetupHooks 单一路径）")
    void sessionStart_stillFiresSetupInit() throws Exception {
        // WHY: 修复不得破坏既有会话启动 Setup('init') 发射（LlmAgentLoop:1949-1962 重构前硬编码
        //   "init"；重构后经 HookRegistry.executeSetupHooks(sessionId, agentId, "init") 单一发射路径，
        //   对齐 CC executeSetupHooks 会话启动 trigger='init'，main.tsx:2571 setupTrigger init 分支）。
        List<HookEvent> captured = new ArrayList<>();
        LlmAgentLoop loop = new LlmAgentLoop(captureFactory());
        setHookRegistry(loop, capturingRegistry(captured));
        loop.setStreamContext(null, "sess-lc04-1", null);

        AgentState state = loop.run(RunRequest.session("hello",
            "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null, ProviderConfig.empty(), "test-model", null, null));

        assertThat(state).isNotNull();
        assertThat(captured.stream().filter(e -> e.type() == HookEventType.SETUP))
            .as("会话启动必须发射 Setup 事件")
            .isNotEmpty();
        String trigger = captured.stream().filter(e -> e.type() == HookEventType.SETUP)
            .findFirst()
            .map(e -> String.valueOf(e.data().get("trigger")))
            .orElse("<no setup event>");
        assertThat(trigger)
            .as("会话启动 Setup trigger 必须为 'init'（CC main.tsx:2571 setupTrigger init 分支）")
            .isEqualTo("init");
    }

    // ── 2. maintenance 触发点: 发射 Setup('maintenance') ──

    @Test
    @DisplayName("fireSetupMaintenanceHooks: 发射 Setup(trigger='maintenance')（对齐 CC --maintenance flag）")
    void fireSetupMaintenanceHooks_emitsSetupMaintenance() throws Exception {
        // WHY: CC --maintenance flag（main.tsx:1131 maintenance → :2571 setupTrigger='maintenance'）
        //   → executeSetupHooks(trigger='maintenance') 发射 Setup maintenance hooks。修复前 Java
        //   无 maintenance 发射点（✗-3，EV-WF4-LC-043）——本方法即补触发点。
        List<HookEvent> captured = new ArrayList<>();
        LlmAgentLoop loop = new LlmAgentLoop(captureFactory());
        setHookRegistry(loop, capturingRegistry(captured));

        loop.fireSetupMaintenanceHooks("sess-lc04-2", null);

        assertThat(captured.stream().filter(e -> e.type() == HookEventType.SETUP))
            .as("fireSetupMaintenanceHooks 必须发射 Setup 事件")
            .isNotEmpty();
        String trigger = captured.stream().filter(e -> e.type() == HookEventType.SETUP)
            .findFirst()
            .map(e -> String.valueOf(e.data().get("trigger")))
            .orElse("<no setup event>");
        assertThat(trigger)
            .as("maintenance 触发点 Setup trigger 必须为 'maintenance'（CC --maintenance flag）")
            .isEqualTo("maintenance");
        // 载荷对齐 CC createBaseHookInput：sessionId 透传、agent_type 未传（主线程 null）
        HookEvent setupEvent = captured.stream().filter(e -> e.type() == HookEventType.SETUP).findFirst().orElseThrow();
        assertThat(setupEvent.sessionId()).isEqualTo("sess-lc04-2");
    }

    // ── 3. maintenance 触发点: matcher='maintenance' hook 触发、matcher='init' hook 不触发 ──

    @Test
    @DisplayName("fireSetupMaintenanceHooks: matcher='maintenance' Setup hook 触发、matcher='init' 不触发（matchQuery=trigger）")
    void fireSetupMaintenanceHooks_matchesMaintenanceHookOnly() throws Exception {
        // WHY: CC executeSetupHooks 经 matchQuery=trigger 匹配（hooks.ts:3914-3921）——
        //   maintenance 触发应命中 matcher='maintenance' 的 Setup hook，不得误触 matcher='init'
        //   （HookMatcherEngine.java:331 SETUP → dataStr(event,"trigger")）。修复前 maintenance
        //   无发射点，matcher='maintenance' hook 永不触发（✗-3）。
        AtomicReference<String> executedHook = new AtomicReference<>();
        StubCommandExecutor stub = new StubCommandExecutor(j -> {
            try {
                JsonNode input = MAPPER.readTree(j);
                executedHook.set(input.path("trigger").asText());
            } catch (Exception e) {
                executedHook.set("<parse-error>");
            }
            return exit0Json("{}");
        });
        HookRegistry registry = registryWithConfiguredSetupHooks(stub);

        LlmAgentLoop loop = new LlmAgentLoop(captureFactory());
        setHookRegistry(loop, registry);

        loop.fireSetupMaintenanceHooks("sess-lc04-3", null);

        assertThat(stub.capturedJsonInput.get())
            .as("matcher='maintenance' 的 Setup hook 必须被执行（修复前 maintenance 无发射点）")
            .isNotNull();
        assertThat(executedHook.get())
            .as("执行的是 maintenance 分支（matchQuery=trigger='maintenance'），不得是 init 分支")
            .isEqualTo("maintenance");
        // 只命中 maintenance hook：若 init hook 也被执行，第二次调用会覆盖 executedHook（其 trigger 为 init）
        // —— 真实 HookMatcherEngine 按 matchQuery 只命中 trigger='maintenance'，故只执行一次。
        assertThat(executedHook.get()).isEqualTo("maintenance");
    }
}
