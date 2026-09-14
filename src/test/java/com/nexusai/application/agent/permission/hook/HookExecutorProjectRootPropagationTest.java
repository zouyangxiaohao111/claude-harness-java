package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.source.PermissionRuleValueParser;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.test.support.SessionProjectRootTestSupport;
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
 * <p>[批 4b-1] 原「HOOK_EXECUTOR 线程 projectRoot 捕获-回放传播」用例已退役（载体
 * {@code AutoMemPaths.CURRENT_PROJECT_ROOT} 删除，用户铁律：会话态一律显式传参）。本类现只保留
 * <b>活守卫</b>：configured command hook 的 spawn cwd 经 {@link CwdResolution}（sessionId 键冻结表）
 * 在池线程求值 = 会话项目根 —— 该链路与 ThreadLocal 无关，跨线程无需回放。
 */
@DisplayName("IMP-C · HOOK_EXECUTOR 线程 projectRoot 捕获-回放传播（HookRegistry withSessionProjectRoot）")
class HookExecutorProjectRootPropagationTest {

    // ── [S2 · F-09/F-20 2026-09-14] 夹具 DB 姿态显式声明 ──
    //   本夹具不接 DB 回源 ⇒ 未绑定 sessionId 属「确无会话」（还原本批前的 cwd 域行为）。
    //   ⛔ 不声明则 SessionProjectRoot.lookup 走「未接线 = 无法判定」⇒ CwdResolution fail-loud 抛。
    //   见 SessionProjectRootTestSupport 的类 javadoc。

    @org.junit.jupiter.api.BeforeEach
    void declareNoDatabaseForSessionProjectRoot() {
        SessionProjectRootTestSupport.declareNoDatabase();
    }

    @org.junit.jupiter.api.AfterEach
    void clearNoDatabaseForSessionProjectRoot() {
        SessionProjectRootTestSupport.clearNoDatabase();
    }

    private static final ObjectMapper JSON = new ObjectMapper();


    /** 捕获 hookCwd 与池线程 projectRoot 的 stub · 不启动真实进程。 */
    static class StubCaptureExecutor extends CommandHookExecutor {
        final AtomicReference<String> capturedJsonInput = new AtomicReference<>();
        final AtomicReference<String> capturedHookCwd = new AtomicReference<>();
        private final Function<String, CommandHookExecutor.CommandHookResult> responder;

        StubCaptureExecutor(Function<String, CommandHookExecutor.CommandHookResult> responder) {
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
            // 本重载在 HOOK_EXECUTOR 线程执行（executeOneConfiguredHook supplyAsync 任务体）——
            // 捕获 hookCwd（resolveSpawnCwd 池线程求值）+ 同线程 projectRoot。
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

    // [批 4b-1 已退役] 原「programmatic PreToolUse hook 在 HOOK_EXECUTOR 线程读到会话绑定 projectRoot」
    //   用例——其被验证的机制 = AutoMemPaths.CURRENT_PROJECT_ROOT（ThreadLocal）捕获-回放，该载体
    //   已随批 4b-1 删除（用户铁律：会话态一律显式传参，回放不算合规，不得保留「删了实现仍恒绿」
    //   的回放断言）。会话项目根的显式传递现由下面的 configured-hook spawn cwd 用例与
    //   AutoMemoryExplicitRootPlumbingTest 正向锚守护。

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
            // [批 4b-1] 原 setCurrentProjectRoot(P) 已删（载体删除）；会话项目根经
            //   SessionProjectRoot 冻结表（sessionId 键）显式解析 —— 跨线程无需回放。
            com.nexusai.common.SessionProjectRoot.setForSession(sid, P); // 供 CwdResolution.getCwd
            registry.executePreToolUse("Bash", JSON.createObjectNode(), mainThreadCtx(), "tu-1");
            assertThat(stub.capturedHookCwd.get())
                .as("hook spawn cwd（CwdResolution.getCwd 池线程求值）必须 = 会话项目根 P（G14 单一入口）")
                .isEqualTo(com.nexusai.application.agent.agent.CwdResolution.normalizeCwd(P));
        } finally {
            com.nexusai.common.SessionProjectRoot.clearSession(sid);
        }
    }
}
