package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.source.PermissionRuleValueParser;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-C D2-A/F3] HOOK_EXECUTOR 线程 projectRoot 捕获-回放传播集成测试。
 *
 * <p>WHY (M-04 D2): {@link HookRegistry#HOOK_EXECUTOR} cached 池线程执行 hook，ThreadLocal
 * 不跨线程 —— 不传播则 hook 执行路径（programmatic hook 回调 / configured hook spawn cwd
 * {@link CommandHookExecutor#resolveSpawnCwd} 池线程求值）读回落值而非会话绑定 P。
 * 修复 = 提交线程（会话/工具执行线程）capture → 任务体开头 set → finally restore
 * （{@code withSessionProjectRoot}，对齐 LlmAgentLoop.run() :1637/:1645）。
 *
 * <p>RED 条件：删除 {@code withSessionProjectRoot} 包裹 → HOOK_EXECUTOR 线程读到回落值 ≠ P。
 */
@DisplayName("IMP-C · HOOK_EXECUTOR 线程 projectRoot 捕获-回放传播（HookRegistry withSessionProjectRoot）")
class HookExecutorProjectRootPropagationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @AfterEach
    void tearDown() {
        AutoMemPaths.resetCurrentProjectRoot();
    }

    /** 捕获 hookCwd 与池线程 projectRoot 的 stub · 不启动真实进程。 */
    static class StubCaptureExecutor extends CommandHookExecutor {
        final AtomicReference<String> capturedJsonInput = new AtomicReference<>();
        final AtomicReference<String> capturedHookCwd = new AtomicReference<>();
        final AtomicReference<String> seenOnHookThread = new AtomicReference<>();
        private final Function<String, CommandHookExecutor.CommandHookResult> responder;

        StubCaptureExecutor(Function<String, CommandHookExecutor.CommandHookResult> responder) {
            this.responder = responder;
        }

        @Override
        public CommandHookExecutor.CommandHookResult execute(CommandHook hook, HookEvent hookEvent, String hookName,
                                                             String jsonInput, String pluginRoot, String pluginId,
                                                             String skillRoot, Integer hookIndex,
                                                             boolean forceSyncExecution, AbortController parentAbort) {
            seenOnHookThread.set(AutoMemPaths.currentSessionProjectRoot());
            capturedJsonInput.set(jsonInput);
            return responder.apply(jsonInput);
        }

        @Override
        public CommandHookExecutor.CommandHookResult execute(CommandHook hook, HookEvent hookEvent, String hookName,
                                                             String jsonInput, String pluginRoot, String pluginId,
                                                             String skillRoot, Integer hookIndex,
                                                             boolean forceSyncExecution, AbortController parentAbort,
                                                             long defaultTimeoutMs, String hookCwd) {
            // 本重载在 HOOK_EXECUTOR 线程执行（executeOneConfiguredHook supplyAsync 任务体）——
            // 捕获 hookCwd（resolveSpawnCwd 池线程求值）+ 同线程 projectRoot。
            seenOnHookThread.set(AutoMemPaths.currentSessionProjectRoot());
            capturedHookCwd.set(hookCwd);
            capturedJsonInput.set(jsonInput);
            return responder.apply(jsonInput);
        }
    }

    private static CommandHookExecutor.CommandHookResult exit0EmptyJson(String jsonInput) {
        return new CommandHookExecutor.CommandHookResult("{}", "", jsonInput, 0, false, false);
    }

    /** settings 配 1 条 PreToolUse:Bash command hook → registry（含 stub executor）。 */
    private static HookRegistry registryWithConfiguredHook(StubCaptureExecutor stub, HookEventType type) {
        HooksSettings settings = new HooksSettings(key -> null);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(type,
                new CommandHook("echo stub", null, null, null, null, null, null, null),
                "Bash", HookSource.USER_SETTINGS, null)
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

    /** 主线程 ctx：agentType=null、effectiveCwd=null（LlmAgentLoop base TUC 等价）。 */
    private static ToolUseContext mainThreadCtx() {
        return ToolUseContext.of(
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
            "", PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), null, PermissionMode.DEFAULT,
            Map.of(), false, "", null);
    }

    @Test
    @DisplayName("programmatic PreToolUse hook 在 HOOK_EXECUTOR 线程读到会话绑定 projectRoot")
    void programmaticHook_onHookExecutorThread_readsSessionProjectRoot() throws Exception {
        // WHY: registerPreToolUse 走 submitPreToolUseHook（supplyAsync + HOOK_EXECUTOR）——
        //      修复生效 = onPreToolUse 回调线程（池线程）读到 P。
        String P = Files.createTempDirectory("imp-c-hook-prog").toString();
        HookRegistry registry = new HookRegistry();
        AtomicReference<String> seen = new AtomicReference<>();
        AtomicReference<String> threadName = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        registry.registerPreToolUse("probe", (toolName, input, ctx) -> {
            threadName.set(Thread.currentThread().getName());
            seen.set(AutoMemPaths.currentSessionProjectRoot());
            done.countDown();
            return AggregatedHookResult.proceed();
        });
        try {
            AutoMemPaths.setCurrentProjectRoot(P);
            registry.executePreToolUse("Bash", JSON.createObjectNode(), mainThreadCtx(), "tu-1");
            assertThat(done.await(5, TimeUnit.SECONDS))
                .as("hook 必须在 5s 内执行")
                .isTrue();
            assertThat(threadName.get())
                .as("programmatic hook 必须在 HOOK_EXECUTOR 线程执行（非测试线程）")
                .contains("nexusai-hook-");
            assertThat(seen.get())
                .as("HOOK_EXECUTOR 线程必须读到会话绑定 projectRoot（捕获-回放传播）")
                .isEqualTo(P);
        } finally {
            AutoMemPaths.resetCurrentProjectRoot();
        }
    }

    @Test
    @DisplayName("configured command hook spawn cwd = 会话项目根（CwdResolution 池线程求值 G14）")
    void configuredHook_spawnCwd_isSessionProjectRoot() throws Exception {
        // WHY (G14): executeOneConfiguredHook 在 supplyAsync 任务体（HOOK_EXECUTOR 线程）调
        //   resolveSpawnCwd → CwdResolution.getCwd(event.sessionId())。原 IMP-C D3 链
        //   effectiveCwd ?: currentSessionProjectRoot 已收敛 CwdResolution 单一入口；
        //   CwdResolution 读 SessionProjectRoot.getForSession（sessionId 键 ConcurrentHashMap，
        //   跨线程无需 capture-replay）→ 池线程求值仍 = 会话绑定 P。
        String P = Files.createTempDirectory("imp-c-hook-cwd").toString();
        // 事件 sessionId = ctx.sessionId()（mainThreadCtx 第二 UUID）；CwdResolution.getCwd 据此查绑定
        String sid = mainThreadCtx().sessionId().toString();
        StubCaptureExecutor stub = new StubCaptureExecutor(HookExecutorProjectRootPropagationTest::exit0EmptyJson);
        HookRegistry registry = registryWithConfiguredHook(stub, HookEventType.PRE_TOOL_USE);
        try {
            AutoMemPaths.setCurrentProjectRoot(P); // 仍供 seenOnHookThread（CLAUDE_PROJECT_DIR env 域）
            com.nexusai.common.SessionProjectRoot.setForSession(sid, P); // 供 CwdResolution.getCwd
            registry.executePreToolUse("Bash", JSON.createObjectNode(), mainThreadCtx(), "tu-1");
            assertThat(stub.seenOnHookThread.get())
                .as("configured hook 在 HOOK_EXECUTOR 线程执行时必须读到会话绑定 projectRoot（CLAUDE_PROJECT_DIR env 域）")
                .isEqualTo(P);
            assertThat(stub.capturedHookCwd.get())
                .as("hook spawn cwd（CwdResolution.getCwd 池线程求值）必须 = 会话项目根 P（G14 单一入口）")
                .isEqualTo(com.nexusai.application.agent.agent.CwdResolution.normalizeCwd(P));
        } finally {
            AutoMemPaths.resetCurrentProjectRoot();
            com.nexusai.common.SessionProjectRoot.clearSession(sid);
        }
    }
}
